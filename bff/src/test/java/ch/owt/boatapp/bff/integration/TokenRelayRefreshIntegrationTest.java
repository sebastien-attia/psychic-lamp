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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Proves the SCG {@code TokenRelay} filter consults the
 * {@link OAuth2AuthorizedClientManager} on every request — so when the
 * manager rotates the access token (its refresh-token provider transparently
 * fires when the cached token has expired), the upstream sees the NEW
 * Bearer on the next call.
 *
 * <p>Strategy: stub the manager to return access-token A on the first
 * {@code authorize(...)} call and access-token B on the second. Drive two
 * sequential authenticated requests through the SCG route and assert the
 * embedded HTTP/1.1 upstream observed two different Bearer headers in
 * order. This isolates the BFF behaviour ("re-resolve the token per
 * request") from the OAuth2 token-rotation machinery — the latter is
 * already covered by Spring Security's own tests and exercised end-to-end
 * by the Playwright suite against a real Keycloak with
 * {@code accessTokenLifespan=5s}.
 *
 * <p>Why a Java-native {@link HttpServer} instead of the WireMock instance
 * shared by {@link BffIntegrationTestBase}: see the rationale on
 * {@link TokenRelayIntegrationTest}.
 */
@SpringBootTest(properties = "spring.config.import=classpath:application-routes.yml")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TokenRelayRefreshIntegrationTest {

    private static final String TOKEN_A = buildSyntheticJwt("token-A");
    private static final String TOKEN_B = buildSyntheticJwt("token-B");

    /** Captured upstream requests — populated by the embedded HTTP/1.1 server. */
    private static final List<String> RECORDED_AUTH_HEADERS = new CopyOnWriteArrayList<>();

    private static HttpServer upstream;
    private static int upstreamPort;

    @MockitoBean
    private OAuth2AuthorizedClientManager authorizedClientManager;

    @Autowired
    private ClientRegistrationRepository registrations;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newSingleThreadExecutor());
        upstream.createContext("/api/v1/boats", TokenRelayRefreshIntegrationTest::handleAndRecord);
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
        RECORDED_AUTH_HEADERS.clear();
    }

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
            tmp = Files.createTempFile("bff-refresh-signing-key", ".pem",
                    PosixFilePermissions.asFileAttribute(EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException ex) {
            tmp = Files.createTempFile("bff-refresh-signing-key", ".pem");
        }
        Files.writeString(tmp, pem);
        tmp.toFile().deleteOnExit();
        registry.add("bff.signing-key.path", tmp::toString);
    }

    /**
     * Stub the manager so it returns access-token A on call 1 and
     * access-token B on call 2. Production code does this transparently via
     * {@code OAuth2AuthorizedClientProviderBuilder.refreshToken(...)} — the
     * test only needs to prove TokenRelay re-resolves per request rather
     * than caching the first token for the life of the session.
     */
    @BeforeEach
    void seedRotatingAuthorizedClient() {
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

        when(authorizedClientManager.authorize(any()))
                .thenReturn(authorizedClientWith(keycloak, TOKEN_A))
                .thenReturn(authorizedClientWith(keycloak, TOKEN_B));
    }

    /**
     * Two sequential authenticated requests must each consult the manager
     * and forward the Bearer the manager returned that round. The upstream
     * observes the rotation in order: first call carries TOKEN_A, second
     * carries TOKEN_B. If TokenRelay cached the first authorized client we
     * would observe TOKEN_A twice — the precise regression this test
     * guards against.
     */
    @Test
    @WithMockUser(username = "test-user")
    void successiveRequestsCarryRotatedBearerTokens() throws Exception {
        mockMvc.perform(get("/api/v1/boats"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        mockMvc.perform(get("/api/v1/boats"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));

        assertThat(RECORDED_AUTH_HEADERS)
                .as("upstream must observe rotated Bearer tokens in order")
                .containsExactly("Bearer " + TOKEN_A, "Bearer " + TOKEN_B);
        verify(authorizedClientManager, times(2)).authorize(any());
    }

    private static OAuth2AuthorizedClient authorizedClientWith(ClientRegistration keycloak, String tokenValue) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                tokenValue,
                Instant.now(),
                Instant.now().plus(5, ChronoUnit.MINUTES));
        return new OAuth2AuthorizedClient(keycloak, "test-user", accessToken);
    }

    private static void handleAndRecord(HttpExchange exchange) throws IOException {
        Headers headers = exchange.getRequestHeaders();
        RECORDED_AUTH_HEADERS.add(headers.getFirst("Authorization"));

        byte[] body = "{\"content\":[],\"totalElements\":0}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String buildSyntheticJwt(String marker) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://test.keycloak.local/realms/boat-app")
                .audience("boat-app-confidential")
                .subject("test-user")
                .jwtID(marker)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .build();
        return new PlainJWT(claims).serialize();
    }
}
