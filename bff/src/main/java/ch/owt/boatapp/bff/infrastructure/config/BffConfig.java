package ch.owt.boatapp.bff.infrastructure.config;

import ch.owt.boatapp.bff.adapter.out.client.generated.BusinessServiceClient;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * BFF infrastructure wiring for the OAuth2 + token-forwarding stack.
 *
 * <p>This configuration assembles four collaborating beans:
 * <ol>
 *   <li>{@link #bffSigningJwk(String, String, String) bffSigningJwk} — loads
 *       the BFF's PKCS#8 PEM signing key from either an env-var-injected
 *       string or a file on disk and exposes it as a Nimbus {@code RSAKey}.
 *       The same JWK (public half only) is republished by
 *       {@code JwksController} at {@code /.well-known/jwks.json} so that
 *       Keycloak can verify the BFF's {@code client_assertion}.</li>
 *   <li>{@link #codeTokenResponseClient(RSAKey)} and
 *       {@link #refreshTokenResponseClient(RSAKey)} — token-endpoint clients
 *       for the {@code authorization_code} and {@code refresh_token} grants.
 *       Both wrap a {@link NimbusJwtClientAuthenticationParametersConverter}
 *       so every request to Keycloak's token endpoint is authenticated with
 *       a freshly-signed {@code client_assertion} JWT
 *       ({@code private_key_jwt}) — no shared {@code client_secret} is ever
 *       sent over the wire.</li>
 *   <li>{@link #authorizedClientManager} — the BFF's
 *       {@link OAuth2AuthorizedClientManager}, wired with both providers and
 *       the {@link OAuth2AuthorizedClientRepository} (backed by Spring
 *       Session JDBC) so refresh-token rotation happens transparently
 *       across BFF restarts.</li>
 *   <li>{@link #businessServiceRestClient} — the {@link RestClient} used to
 *       call the upstream Business Service. Its sole interceptor pulls the
 *       current user's access token from the {@link OAuth2AuthorizedClientManager}
 *       and attaches it as a {@code Bearer} header on every outbound
 *       request.</li>
 * </ol>
 *
 * <p>Dev profile note: this configuration is gated on {@code @Profile("!dev")}
 * because the dev profile does not start the BFF and the
 * {@code bff.signing-key.path} property is not set there. In dev mode the
 * front-end Vite proxy talks straight to the Business Service.
 */
@Configuration
@Profile("!dev")
public class BffConfig {

    private static final Logger log = LoggerFactory.getLogger(BffConfig.class);

    private static final String CLIENT_REGISTRATION_ID = "keycloak";

    /**
     * Load the BFF's signing key and build the {@code RSAKey} used both to
     * sign {@code client_assertion} JWTs and to publish at
     * {@code /.well-known/jwks.json}.
     *
     * <p>Two sources are supported, in this order of precedence:
     * <ol>
     *   <li>{@code bff.signing-key.pem} — the PKCS#8 PEM contents passed
     *       directly as a string. Used on Azure App Service, where the PEM
     *       lives as a Key Vault secret injected into an app setting via
     *       {@code @Microsoft.KeyVault} — App Service has no first-class
     *       file mount for KV references.</li>
     *   <li>{@code bff.signing-key.path} — filesystem path to a PKCS#8 PEM
     *       file. Used by Docker Compose (named volume) and any other host
     *       where the PEM is delivered as a file rather than an env var.</li>
     * </ol>
     *
     * <p>The PEM is parsed in two steps: strip the {@code -----BEGIN/END-----}
     * armor and base64-decode the body, then derive the public key from the
     * private key's CRT parameters. Wrapping the resulting key pair into a
     * Nimbus {@code RSAKey} attaches the {@code kid}, {@code use=sig} and
     * {@code alg=RS256} metadata that Keycloak requires.
     *
     * @param keyPem  PKCS#8 PEM contents, or empty/blank if not set
     * @param keyPath filesystem path to the PKCS#8 PEM file, or empty if not
     *                set; consulted only when {@code keyPem} is blank
     * @param kid     stable key identifier (the {@code kid} claim used by
     *                Keycloak to look up the public half in the JWKS)
     * @return the BFF's signing key as an {@code RSAKey}
     * @throws IllegalStateException if neither source is set, the file
     *         cannot be read, the key material is malformed, or the JCE
     *         provider lacks RSA support
     */
    @Bean
    public RSAKey bffSigningJwk(@Value("${bff.signing-key.pem:}") String keyPem,
                                @Value("${bff.signing-key.path:}") String keyPath,
                                @Value("${bff.signing-key.id}") String kid) {
        String pem;
        String source;
        if (!keyPem.isBlank()) {
            pem = keyPem;
            source = "bff.signing-key.pem";
        } else if (!keyPath.isBlank()) {
            source = "bff.signing-key.path=" + keyPath;
            try {
                pem = Files.readString(Path.of(keyPath));
            } catch (IOException ex) {
                throw new IllegalStateException(
                        "Cannot read BFF signing key at " + keyPath, ex);
            }
        } else {
            throw new IllegalStateException(
                    "BFF signing key not configured: set either bff.signing-key.pem"
                    + " (PEM contents, env var BFF_SIGNING_KEY_PEM) or"
                    + " bff.signing-key.path (env var BFF_SIGNING_KEY_PATH)");
        }
        log.info("BFF signing key loaded from {} (kid={})", source, kid);
        String stripped = pem
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] der = Base64.getDecoder().decode(stripped);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey rsaPrivate =
                    (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPublicKey rsaPublic = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(rsaPrivate.getModulus(), rsaPrivate.getPublicExponent()));
            return new RSAKey.Builder(rsaPublic)
                    .privateKey(rsaPrivate)
                    .keyID(kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException
                | IllegalArgumentException | ClassCastException ex) {
            throw new IllegalStateException(
                    "BFF signing key from " + source + " is not a valid PKCS#8 RSA private key", ex);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "JCE provider rejected BFF signing key from " + source, ex);
        }
    }

    /**
     * Build the {@link OAuth2AccessTokenResponseClient} used for the
     * {@code authorization_code} grant during the initial OAuth2 login.
     *
     * <p>The bean is type-discovered by Spring Security's
     * {@code OAuth2LoginConfigurer} (no explicit wiring needed in
     * {@code SecurityConfig}). Adding the
     * {@link NimbusJwtClientAuthenticationParametersConverter} ensures every
     * code-for-token exchange carries a signed {@code client_assertion}.
     *
     * @param bffSigningJwk the BFF's signing key (private+public)
     * @return the configured token-response client for the auth-code grant
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeTokenResponseClient(
            RSAKey bffSigningJwk) {
        NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest> converter =
                new NimbusJwtClientAuthenticationParametersConverter<>(reg -> bffSigningJwk);
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.addParametersConverter(converter);
        return client;
    }

    /**
     * Build the {@link OAuth2AccessTokenResponseClient} used for the
     * {@code refresh_token} grant — fired transparently by the
     * {@link OAuth2AuthorizedClientManager} whenever the access token has
     * expired.
     *
     * <p>Wired into the manager's {@link OAuth2AuthorizedClientProvider}
     * chain in {@link #authorizedClientManager}. Same {@code private_key_jwt}
     * configuration as the auth-code client.
     *
     * @param bffSigningJwk the BFF's signing key (private+public)
     * @return the configured token-response client for the refresh-token grant
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient(
            RSAKey bffSigningJwk) {
        NimbusJwtClientAuthenticationParametersConverter<OAuth2RefreshTokenGrantRequest> converter =
                new NimbusJwtClientAuthenticationParametersConverter<>(reg -> bffSigningJwk);
        RestClientRefreshTokenTokenResponseClient client = new RestClientRefreshTokenTokenResponseClient();
        client.addParametersConverter(converter);
        return client;
    }

    /**
     * Build the {@link OAuth2AuthorizedClientManager} used by the
     * Business-Service interceptor to fetch (and refresh, on expiry) the
     * current user's access token.
     *
     * <p>The provider chain accepts authorization-code grants (the manager
     * does not actually fetch them — login does — but the chain must allow
     * the grant or {@code authorize(...)} would reject the principal) and
     * refresh-token grants (here we wire our private_key_jwt token client).
     *
     * <p><strong>Servlet-scoped.</strong> {@link DefaultOAuth2AuthorizedClientManager}
     * looks up the {@link OAuth2AuthorizedClient} via the current servlet
     * request — calling {@code authorize(...)} from a non-request thread
     * (e.g. {@code @Async}, {@code @Scheduled}) throws
     * {@code IllegalArgumentException}. Any future background flow that
     * needs an authorized client must use
     * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager} instead.
     *
     * @param registrations               OAuth2 client registry (loaded from
     *                                    {@code application-{profile}.yml})
     * @param clientRepo                  per-principal authorized-client
     *                                    storage (backed by Spring Session JDBC)
     * @param refreshTokenResponseClient  token-endpoint client for the
     *                                    refresh-token grant
     * @return the configured {@link OAuth2AuthorizedClientManager}
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository clientRepo,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken(c -> c.accessTokenResponseClient(refreshTokenResponseClient))
                .build();
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, clientRepo);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /**
     * Build the {@link RestClient} used by the {@link BusinessServiceClient}
     * proxy to call the upstream Business Service.
     *
     * <p>Adds a single request interceptor: read the current
     * {@link Authentication} from the {@link SecurityContextHolder}, ask the
     * {@link OAuth2AuthorizedClientManager} for the {@code keycloak}
     * authorized client (which transparently refreshes the access token if
     * it has expired), and attach the access token as a
     * {@code Bearer} header on the outbound request.
     *
     * <p>Anonymous principals (the default Spring Security places when no
     * session exists) and missing authentications skip the lookup — the
     * upstream then rejects the request with 401, which the BFF's filter
     * chain converts to an RFC 9457 envelope. A
     * {@link ClientAuthorizationException} (typically: refresh token
     * revoked or Keycloak unreachable) is translated to
     * {@link InsufficientAuthenticationException} so
     * {@code GlobalExceptionHandler} emits the same 401 envelope as a
     * missing-session 401, rather than leaking the OAuth2 stack trace as 500.
     *
     * @param baseUrl                  base URL of the upstream Business Service
     * @param authorizedClientManager  manages access-token retrieval and
     *                                 refresh
     * @return a {@link RestClient} bound to the upstream base URL
     */
    @Bean
    public RestClient businessServiceRestClient(
            @Value("${business-service.url:http://localhost:8081}") String baseUrl,
            OAuth2AuthorizedClientManager authorizedClientManager) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication != null && !(authentication instanceof AnonymousAuthenticationToken)) {
                        OAuth2AuthorizedClient authorizedClient;
                        try {
                            authorizedClient = authorizedClientManager.authorize(
                                    OAuth2AuthorizeRequest
                                            .withClientRegistrationId(CLIENT_REGISTRATION_ID)
                                            .principal(authentication)
                                            .build());
                        } catch (ClientAuthorizationException ex) {
                            throw new InsufficientAuthenticationException(
                                    "OAuth2 token refresh failed for clientRegistrationId="
                                            + CLIENT_REGISTRATION_ID, ex);
                        }
                        if (authorizedClient != null) {
                            request.getHeaders()
                                    .setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
                        }
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * Build the OpenAPI-generated {@link BusinessServiceClient} HTTP-Interface
     * proxy backed by the configured {@link RestClient}.
     *
     * @param restClient the BFF's outbound HTTP client (with Bearer-token
     *                   interceptor)
     * @return the proxy implementing {@link BusinessServiceClient}
     */
    @Bean
    public BusinessServiceClient businessServiceClient(RestClient restClient) {
        return HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build()
                .createClient(BusinessServiceClient.class);
    }
}
