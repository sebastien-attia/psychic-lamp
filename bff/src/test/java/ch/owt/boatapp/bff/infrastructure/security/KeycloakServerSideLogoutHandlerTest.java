package ch.owt.boatapp.bff.infrastructure.security;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.web.client.RestClient;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KeycloakServerSideLogoutHandler}.
 *
 * <p>Drives the handler against a WireMock-stubbed Keycloak end-session
 * endpoint and asserts:
 * <ul>
 *   <li>the request shape is correct (form params, content-type) when a
 *       refresh token is present;</li>
 *   <li>the handler is a quiet no-op for the three "nothing to do" cases
 *       (null authentication, no authorized client, no refresh token);</li>
 *   <li>Keycloak-side errors and transport failures are swallowed — the
 *       handler must never derail Spring's downstream session-invalidation
 *       handlers.</li>
 * </ul>
 */
class KeycloakServerSideLogoutHandlerTest {

    private static final String REGISTRATION_ID = "keycloak";
    private static final String END_SESSION_PATH = "/realms/test/protocol/openid-connect/logout";

    private static WireMockServer wireMock;
    private static RSAKey signingJwk;

    private OAuth2AuthorizedClientRepository authorizedClientRepository;
    private ClientRegistrationRepository clientRegistrationRepository;
    private KeycloakServerSideLogoutHandler handler;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private Authentication authentication;

    @BeforeAll
    static void bootShared() throws Exception {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        signingJwk = new RSAKey.Builder((RSAPublicKey) kp.getPublic())
                .privateKey((RSAPrivateKey) kp.getPrivate())
                .keyID("test-kid")
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .build();
    }

    @AfterAll
    static void shutdownShared() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setup() {
        wireMock.resetAll();
        authorizedClientRepository = mock(OAuth2AuthorizedClientRepository.class);
        clientRegistrationRepository = mock(ClientRegistrationRepository.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("demo");
        handler = new KeycloakServerSideLogoutHandler(
                authorizedClientRepository,
                clientRegistrationRepository,
                signingJwk,
                RestClient.builder().build());
    }

    @Test
    void postsClientAssertionAndRefreshTokenWhenAuthorizedClientPresent() {
        OAuth2AuthorizedClient client = clientWithRefreshToken("rt-abc");
        ClientRegistration registration = testRegistration();
        when(clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID)).thenReturn(registration);
        when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
                .thenReturn(client);
        wireMock.stubFor(post(urlEqualTo(END_SESSION_PATH))
                .willReturn(aResponse().withStatus(204)));

        handler.logout(request, response, authentication);

        wireMock.verify(postRequestedFor(urlEqualTo(END_SESSION_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withHeader("Accept", containing("application/json"))
                .withHeader("User-Agent", containing("boatapp-bff"))
                .withRequestBody(containing("client_id=bff-client"))
                .withRequestBody(containing(
                        "client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer"))
                .withRequestBody(containing("client_assertion="))
                .withRequestBody(containing("refresh_token=rt-abc"))
                // Defensive: a future refactor that grabs the access token
                // from the OAuth2AuthorizedClient and forwards it would be
                // a confused-deputy hazard. Pin the form to NOT carry one.
                .withRequestBody(notMatching("(?s).*access_token=.*")));
    }

    @Test
    void doesNothingWhenAuthenticationIsNull() {
        handler.logout(request, response, null);

        verifyNoInteractions(authorizedClientRepository, clientRegistrationRepository);
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void doesNothingWhenAuthorizedClientIsMissing() {
        when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
                .thenReturn(null);

        handler.logout(request, response, authentication);

        verify(clientRegistrationRepository, never()).findByRegistrationId(any());
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void doesNothingWhenRefreshTokenIsMissing() {
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        when(client.getRefreshToken()).thenReturn(null);
        when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
                .thenReturn(client);

        handler.logout(request, response, authentication);

        verify(clientRegistrationRepository, never()).findByRegistrationId(any());
        wireMock.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void swallowsKeycloak4xxResponse() {
        OAuth2AuthorizedClient client = clientWithRefreshToken("expired");
        ClientRegistration registration = testRegistration();
        when(clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID)).thenReturn(registration);
        when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
                .thenReturn(client);
        wireMock.stubFor(post(urlEqualTo(END_SESSION_PATH))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid_grant\"}")));

        assertThatCode(() -> handler.logout(request, response, authentication))
                .doesNotThrowAnyException();
        wireMock.verify(postRequestedFor(urlEqualTo(END_SESSION_PATH)));
    }

    @Test
    void swallowsTransportFailure() {
        // Use WireMock's connection-reset fault to deterministically simulate
        // a transport failure (rather than relying on a closed-port heuristic
        // that can hang or behave differently across OSes / CI runners).
        OAuth2AuthorizedClient client = clientWithRefreshToken("rt-reset");
        ClientRegistration registration = testRegistration();
        when(clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID)).thenReturn(registration);
        when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
                .thenReturn(client);
        wireMock.stubFor(post(urlEqualTo(END_SESSION_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatCode(() -> handler.logout(request, response, authentication))
                .doesNotThrowAnyException();
    }

    @Test
    void clientAssertionAudienceIsTokenEndpoint() throws Exception {
        // RFC 7523 §3 mandates the token endpoint URL as the assertion
        // audience. Assert the actual claim — not just that *some* JWT was
        // sent — so a future refactor that drops or changes the audience
        // (a common mistake — Keycloak accepts both end-session and token
        // URIs in practice) is caught here.
        OAuth2AuthorizedClient client = clientWithRefreshToken("rt-aud");
        ClientRegistration registration = testRegistration();
        when(clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID)).thenReturn(registration);
        when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
                .thenReturn(client);
        wireMock.stubFor(post(urlEqualTo(END_SESSION_PATH))
                .willReturn(aResponse().withStatus(204)));

        handler.logout(request, response, authentication);

        SignedJWT assertionJwt = SignedJWT.parse(extractClientAssertion());
        JWTClaimsSet claims = assertionJwt.getJWTClaimsSet();
        assertThat(claims.getAudience()).containsExactly(wireMock.baseUrl() + "/token");
        assertThat(claims.getIssuer()).isEqualTo("bff-client");
        assertThat(claims.getSubject()).isEqualTo("bff-client");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getExpirationTime()).isAfter(claims.getIssueTime());
    }

    @Test
    void usesEndSessionEndpointFromConfigurationMetadataWhenPresent() {
        // Belt-and-braces: even if a future Spring version stops auto-
        // populating end_session_endpoint via OIDC discovery, the handler
        // still works as long as we set it on the registration manually.
        wireMock.stubFor(post(urlEqualTo("/custom/logout"))
                .willReturn(aResponse().withStatus(204)));
        ClientRegistration custom = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("bff-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                .authorizationUri(wireMock.baseUrl() + "/auth")
                .tokenUri(wireMock.baseUrl() + "/token")
                .providerConfigurationMetadata(Map.of(
                        "end_session_endpoint", wireMock.baseUrl() + "/custom/logout"))
                .clientName(REGISTRATION_ID)
                .build();
        OAuth2AuthorizedClient client = clientWithRefreshToken("rt-custom");
        when(clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID)).thenReturn(custom);
        when(authorizedClientRepository.loadAuthorizedClient(REGISTRATION_ID, authentication, request))
                .thenReturn(client);

        handler.logout(request, response, authentication);

        wireMock.verify(postRequestedFor(urlEqualTo("/custom/logout"))
                .withRequestBody(containing("refresh_token=rt-custom")));
    }

    private ClientRegistration testRegistration() {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("bff-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                .authorizationUri(wireMock.baseUrl() + "/auth")
                .tokenUri(wireMock.baseUrl() + "/token")
                .providerConfigurationMetadata(Map.of(
                        "end_session_endpoint", wireMock.baseUrl() + END_SESSION_PATH))
                .clientName(REGISTRATION_ID)
                .build();
    }

    private OAuth2AuthorizedClient clientWithRefreshToken(String tokenValue) {
        OAuth2AuthorizedClient client = mock(OAuth2AuthorizedClient.class);
        OAuth2RefreshToken refreshToken = new OAuth2RefreshToken(tokenValue, Instant.now());
        when(client.getRefreshToken()).thenReturn(refreshToken);
        return client;
    }

    /**
     * Pull the {@code client_assertion} value out of WireMock's most-recent
     * recorded form body. The body is application/x-www-form-urlencoded with
     * standard percent-encoding; we URL-decode the slice between
     * {@code client_assertion=} and the next {@code &} (or end of string).
     */
    private static String extractClientAssertion() {
        ServeEvent latest = wireMock.getAllServeEvents().get(0); // newest first
        String body = new String(latest.getRequest().getBody(), StandardCharsets.UTF_8);
        for (String pair : body.split("&")) {
            if (pair.startsWith("client_assertion=")) {
                return URLDecoder.decode(pair.substring("client_assertion=".length()),
                        StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("client_assertion not present in recorded body: " + body);
    }
}
