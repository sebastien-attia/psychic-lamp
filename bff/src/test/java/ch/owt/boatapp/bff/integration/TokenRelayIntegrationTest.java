package ch.owt.boatapp.bff.integration;

import ch.owt.boatapp.bff.TestcontainersConfiguration;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Proves the SCG {@code TokenRelay} filter wired in {@code application-routes.yml}
 * actually forwards the user's access token to the upstream Business Service.
 *
 * <p>Strategy: replace the live {@code OAuth2AuthorizedClientManager} with a
 * Mockito stub that returns a pre-built {@link OAuth2AuthorizedClient} carrying
 * a synthetic JWT, then drive an authenticated request through the SCG route
 * and assert the embedded HTTP/1.1 server received exactly one upstream call
 * with {@code Authorization: Bearer <synthetic-jwt>}.
 *
 * <p>Why a Java-native {@link HttpServer} instead of the shared WireMock
 * instance from {@link BffIntegrationTestBase}: Spring Cloud Gateway MVC's
 * {@code RestClientProxyExchange} uses Spring's {@code RestClient}, which on
 * Java 25 / Spring Boot 4.0 negotiates HTTP/2 cleartext (h2c) against
 * plaintext upstreams. WireMock 3.3.1's standalone server's HTTP/2 handling
 * resets streams in this combination ("Received RST_STREAM: Stream
 * cancelled"), masking real bugs as transport noise. The JDK's bundled
 * {@link HttpServer} is HTTP/1.1-only, has no dependency, and cleanly
 * isolates the BFF behaviour we actually want to assert. Other tests
 * (TokenRelayRefreshIntegrationTest, ScgErrorEnvelopePassThroughTest) follow
 * the same pattern.
 *
 * <p>The full Keycloak loop (real issuer, real signing, refresh-token
 * rotation across the wire) is exercised by the Playwright E2E suite against
 * {@code make up}; what this test catches is "TokenRelay didn't fire" — the
 * regression that breaks every authenticated boat call in one move.
 */
@SpringBootTest(properties = "spring.config.import=classpath:application-routes.yml")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TokenRelayIntegrationTest {

    private static final String SYNTHETIC_TOKEN_VALUE = buildSyntheticJwt();

    /** Captured upstream requests — populated by the embedded HTTP/1.1 server. */
    private static final List<RecordedRequest> RECORDED = new CopyOnWriteArrayList<>();

    private static HttpServer upstream;
    private static int upstreamPort;

    @MockitoBean
    private OAuth2AuthorizedClientManager authorizedClientManager;

    @Autowired
    private ClientRegistrationRepository registrations;

    @Autowired
    private MockMvc mockMvc;

    /**
     * Start a JDK HTTP/1.1 server before the Spring context loads so its port
     * can be bound to {@code business-service.url} via
     * {@link DynamicPropertySource}.
     */
    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newSingleThreadExecutor());
        upstream.createContext("/api/v1/boats", TokenRelayIntegrationTest::handleAndRecord);
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @BeforeEach
    void resetRecorded() {
        RECORDED.clear();
    }

    /**
     * Bind {@code business-service.url} to the embedded server's port and
     * supply an ephemeral RSA signing key for {@code BffConfig.bffSigningJwk}.
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("business-service.url", () -> "http://127.0.0.1:" + upstreamPort);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                        .replaceAll("(.{64})", "$1\n")
                + "\n-----END PRIVATE KEY-----\n";
        Path tmp;
        try {
            tmp = Files.createTempFile("bff-tr-signing-key", ".pem",
                    PosixFilePermissions.asFileAttribute(EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException ex) {
            tmp = Files.createTempFile("bff-tr-signing-key", ".pem");
        }
        Files.writeString(tmp, pem);
        tmp.toFile().deleteOnExit();
        registry.add("bff.signing-key.path", tmp::toString);
    }

    /**
     * Wire the Mockito stub so {@code TokenRelayFilterFunctions.getBean(OAuth2AuthorizedClientManager.class)}
     * gets a manager whose {@code authorize(...)} returns a fixed authorized
     * client carrying the synthetic JWT.
     */
    @BeforeEach
    void seedAuthorizedClient() {
        ClientRegistration keycloak = registrations.findByRegistrationId("keycloak");
        if (keycloak == null) {
            keycloak = ClientRegistration.withRegistrationId("keycloak")
                    .clientId("test-client")
                    .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                    .authorizationUri("http://localhost/kc/auth")
                    .tokenUri("http://localhost/kc/token")
                    .build();
        }

        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                SYNTHETIC_TOKEN_VALUE,
                Instant.now(),
                Instant.now().plus(5, ChronoUnit.MINUTES));

        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(keycloak, "test-user", accessToken);
        when(authorizedClientManager.authorize(any())).thenReturn(client);
    }

    /**
     * Authenticated request flows through SCG → TokenRelay attaches the
     * synthetic Bearer → embedded server records the upstream hit with that
     * exact header.
     */
    @Test
    @WithMockUser(username = "test-user")
    void authenticatedRequestForwardsBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/boats"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        assertThat(RECORDED).hasSize(1);
        RecordedRequest received = RECORDED.get(0);
        assertThat(received.path).isEqualTo("/api/v1/boats");
        assertThat(received.authorization).isEqualTo("Bearer " + SYNTHETIC_TOKEN_VALUE);
        verify(authorizedClientManager).authorize(any());
    }

    /**
     * Anonymous request must NEVER reach the upstream. Spring Security's
     * {@code anyRequest().authenticated()} gate fires first → 401 from
     * {@code RestAuthenticationEntryPoint}, the SCG filter chain is skipped,
     * the {@code OAuth2AuthorizedClientManager} stub is never consulted, and
     * the embedded server records zero upstream traffic.
     */
    @Test
    @WithAnonymousUser
    void anonymousRequestNeverReachesUpstream() throws Exception {
        mockMvc.perform(get("/api/v1/boats"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(401))
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith("application/problem+json"));

        assertThat(RECORDED).isEmpty();
        verify(authorizedClientManager, never()).authorize(any());
    }

    private static void handleAndRecord(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getRequestHeaders();
        RECORDED.add(new RecordedRequest(
                exchange.getRequestURI().getPath(),
                headers.getFirst("Authorization")));

        byte[] body = "{\"content\":[],\"totalElements\":0}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record RecordedRequest(String path, String authorization) {}

    /**
     * Build a structurally valid JWT (unsigned plain JWT — {@code alg=none})
     * to stand in for a real Keycloak access token. The embedded server only
     * inspects the full string in the Authorization header; the real token's
     * cryptographic envelope is exercised by
     * {@code KeycloakOAuthFlowIntegrationTest} and the Playwright E2E suite.
     */
    private static String buildSyntheticJwt() {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://test.keycloak.local/realms/boat-app")
                .audience("boat-app-confidential")
                .subject("test-user")
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .build();
        return new PlainJWT(claims).serialize();
    }
}
