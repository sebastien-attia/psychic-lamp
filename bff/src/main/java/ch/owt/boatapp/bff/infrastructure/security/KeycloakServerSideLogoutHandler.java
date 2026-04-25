package ch.owt.boatapp.bff.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Terminate the user's Keycloak SSO session server-to-server (no browser
 * redirect) on logout.
 *
 * <p>The default OIDC RP-initiated logout flow bounces the browser through
 * Keycloak's {@code end_session_endpoint}. On Chromium-based browsers
 * (including Brave) running against the plain-HTTP {@code local-intg} stack,
 * Keycloak's session cookies (issued with {@code SameSite=None} but
 * unavoidably non-{@code Secure} over HTTP) are silently dropped — the
 * bounce arrives at Keycloak with no session cookie and the SSO session
 * survives the round-trip. Firefox is permissive enough that the same flow
 * works there, which is exactly the symptom this handler fixes.
 *
 * <p>Instead this handler calls Keycloak's logout endpoint directly from the
 * BFF, authenticated with {@code private_key_jwt} (same {@code RSAKey} the
 * code/refresh-token grants already use) and carrying the user's refresh
 * token. Keycloak terminates the SSO session, revokes the refresh token, and
 * the SPA simply redirects to {@code /} — no Keycloak round-trip needed.
 *
 * <p>Failures are logged and swallowed: the local session is invalidated by
 * Spring's downstream handlers regardless, so a Keycloak outage cannot leave
 * the user "stuck" logged in to the BFF.
 *
 * <p>Registered via {@code .addLogoutHandler(...)} on the Spring Security
 * {@code LogoutConfigurer}. User-added logout handlers run before
 * {@code SecurityContextLogoutHandler} (which invalidates the HTTP session),
 * so the {@link OAuth2AuthorizedClient} is still readable here.
 *
 * <p><strong>Note on the protocol used:</strong> this is Keycloak's
 * <em>logout-by-refresh-token</em> extension to {@code /protocol/openid-connect/logout},
 * not the OIDC RP-Initiated Logout 1.0 spec (which is browser-redirect-based
 * and is exactly what we are deliberately avoiding). Access tokens already
 * issued to the BFF are not separately revoked: they are short-lived JWTs
 * that never leave the server side, and Keycloak's session termination
 * causes any refresh attempt to fail. RFC 7009 token revocation lives on a
 * separate {@code revocation_endpoint} and is intentionally not called.
 */
public class KeycloakServerSideLogoutHandler implements LogoutHandler {

    private static final Logger log =
            LoggerFactory.getLogger(KeycloakServerSideLogoutHandler.class);

    /** Spring registration id for the Keycloak OIDC client (must match {@code application-*.yml}). */
    private static final String CLIENT_REGISTRATION_ID = "keycloak";

    /** Discovery metadata key for the OIDC end-session endpoint. */
    private static final String END_SESSION_ENDPOINT_KEY = "end_session_endpoint";

    /** Path appended to the issuer URI as a fallback if discovery metadata is absent. */
    private static final String END_SESSION_PATH_SUFFIX = "/protocol/openid-connect/logout";

    /** RFC 7523 client-assertion type for {@code private_key_jwt} client authentication. */
    private static final String JWT_BEARER_ASSERTION_TYPE =
            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    /**
     * Lifetime of the {@code client_assertion} JWT — short, since it's used
     * once. RFC 7523 §3 mandates a short-lived assertion; Keycloak tolerates
     * up to 30s of clock skew by default, so 60s leaves a comfortable margin
     * without inviting replay attacks.
     */
    private static final long ASSERTION_VALIDITY_SECONDS = 60;

    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final RSAKey bffSigningJwk;
    private final RestClient restClient;

    /**
     * @param authorizedClientRepository    looks up the per-principal
     *                                      {@link OAuth2AuthorizedClient}
     *                                      (refresh-token storage is
     *                                      session-scoped via Spring Session
     *                                      JDBC); must be readable when this
     *                                      handler runs (i.e. before the
     *                                      session is invalidated)
     * @param clientRegistrationRepository  resolves the {@code keycloak}
     *                                      client registration so we can
     *                                      build the assertion audience and
     *                                      end-session URL from configuration
     *                                      rather than hard-coding them
     * @param bffSigningJwk                 the BFF's RSA signing key — same
     *                                      one used for code/refresh-token
     *                                      {@code client_assertion}s, so
     *                                      Keycloak's JWKS lookup already
     *                                      knows it
     * @param restClient                    HTTP client used to POST to
     *                                      Keycloak's logout endpoint;
     *                                      injected so tests can wire a
     *                                      WireMock-targeted client
     */
    public KeycloakServerSideLogoutHandler(
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            ClientRegistrationRepository clientRegistrationRepository,
            RSAKey bffSigningJwk,
            RestClient restClient) {
        this.authorizedClientRepository = authorizedClientRepository;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.bffSigningJwk = bffSigningJwk;
        this.restClient = restClient;
    }

    /**
     * Best-effort POST to Keycloak's logout endpoint; never throws.
     *
     * <p>Returns silently when there is nothing to revoke (no authentication,
     * no authorized client, or no refresh token). Logs and swallows any
     * Keycloak-side or transport failure so the user's local session can
     * still be invalidated by the downstream handlers.
     *
     * @param request         the inbound {@code POST /api/logout} request
     * @param response        the response (unused — we don't write to it)
     * @param authentication  the principal being logged out, or {@code null}
     *                        if Spring's filter chain found no
     *                        {@code SecurityContext} (anonymous logout)
     */
    @Override
    public void logout(HttpServletRequest request, HttpServletResponse response,
                       Authentication authentication) {
        if (authentication == null) {
            return;
        }
        OAuth2AuthorizedClient client = authorizedClientRepository.loadAuthorizedClient(
                CLIENT_REGISTRATION_ID, authentication, request);
        if (client == null) {
            log.debug("No OAuth2AuthorizedClient for principal={}; skipping Keycloak logout",
                    authentication.getName());
            return;
        }
        OAuth2RefreshToken refreshToken = client.getRefreshToken();
        if (refreshToken == null) {
            log.debug("No refresh token for principal={}; skipping Keycloak logout",
                    authentication.getName());
            return;
        }
        ClientRegistration registration = clientRegistrationRepository
                .findByRegistrationId(CLIENT_REGISTRATION_ID);
        if (registration == null) {
            log.warn("ClientRegistration '{}' not found; skipping Keycloak logout",
                    CLIENT_REGISTRATION_ID);
            return;
        }

        String endSessionUrl = endSessionUrl(registration);
        String clientAssertion = buildClientAssertion(registration);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", registration.getClientId());
        form.add("client_assertion_type", JWT_BEARER_ASSERTION_TYPE);
        form.add("client_assertion", clientAssertion);
        form.add("refresh_token", refreshToken.getTokenValue());

        try {
            restClient.post()
                    .uri(endSessionUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    // Accept + User-Agent are not strictly required by Keycloak,
                    // but some upstream WAFs reject form posts to
                    // /protocol/openid-connect/logout without them, which would
                    // silently fall into the catch block and leave the SSO
                    // session alive — exactly the bug this handler exists to
                    // prevent. Set both explicitly.
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.USER_AGENT, "boatapp-bff")
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Keycloak SSO session terminated for principal={}", authentication.getName());
        } catch (RestClientResponseException ex) {
            // Typically 400 if the refresh token is already revoked / expired.
            // Either way: nothing more we can do; let the local session be cleared.
            log.warn("Keycloak logout returned {} for principal={} — clearing local session anyway",
                    ex.getStatusCode().value(), authentication.getName());
        } catch (RuntimeException ex) {
            // Genuinely unexpected — pass the throwable so the stack trace lands
            // in logs at WARN. JCE misconfig or an NPE in token serialization
            // would otherwise be diagnosable only from the message string.
            log.warn("Keycloak logout failed for principal={} — clearing local session anyway",
                    authentication.getName(), ex);
        }
    }

    /**
     * Resolve Keycloak's end-session URL from the {@link ClientRegistration}.
     *
     * <p>Prefers the OIDC discovery metadata key {@code end_session_endpoint}
     * (auto-populated by Spring's {@code IssuerIssuedJwtDecoder} when the
     * registration uses {@code issuer-uri}), and falls back to the
     * Keycloak-conventional path {@code <issuer>/protocol/openid-connect/logout}
     * for registrations built by hand without discovery (e.g. test fixtures).
     */
    private String endSessionUrl(ClientRegistration registration) {
        Object endpoint = registration.getProviderDetails()
                .getConfigurationMetadata()
                .get(END_SESSION_ENDPOINT_KEY);
        if (endpoint instanceof String s && !s.isBlank()) {
            return s;
        }
        Object issuer = registration.getProviderDetails()
                .getConfigurationMetadata()
                .get("issuer");
        if (issuer instanceof String s && !s.isBlank()) {
            return s + END_SESSION_PATH_SUFFIX;
        }
        throw new IllegalStateException(
                "ClientRegistration '" + CLIENT_REGISTRATION_ID
                        + "' lacks both end_session_endpoint and issuer metadata");
    }

    /**
     * Build the {@code private_key_jwt} client assertion for Keycloak's
     * token-endpoint (per RFC 7523 §3): a short-lived RS256-signed JWT whose
     * audience is the IdP's <em>token endpoint</em> URL — not the end-session
     * URL. Keycloak accepts both in practice, but RFC 7523 §3 mandates the
     * token endpoint and stricter IdPs reject other audiences.
     */
    private String buildClientAssertion(ClientRegistration registration) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(registration.getClientId())
                .subject(registration.getClientId())
                .audience(registration.getProviderDetails().getTokenUri())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ASSERTION_VALIDITY_SECONDS)))
                .build();
        SignedJWT signed = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(bffSigningJwk.getKeyID())
                        .build(),
                claims);
        try {
            signed.sign(new RSASSASigner(bffSigningJwk.toPrivateKey()));
        } catch (JOSEException ex) {
            throw new IllegalStateException(
                    "Failed to sign client_assertion JWT for Keycloak logout", ex);
        }
        return signed.serialize();
    }
}
