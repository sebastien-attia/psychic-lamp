package ch.owt.boatapp.bff.integration;

import ch.owt.boatapp.bff.TestcontainersConfiguration;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
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
import org.springframework.test.web.servlet.MvcResult;

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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Proves the BFF preserves the system-wide RFC 9457 envelope contract on
 * upstream failures:
 *
 * <ul>
 *   <li>4xx with an upstream RFC 9457 body → passed through byte-identical
 *       (status, body, content-type). The Business Service is the trust
 *       boundary that owns 400/404/409/422/428 today, so the BFF must not
 *       re-wrap them.</li>
 *   <li>5xx without an RFC 9457 body → rewritten by
 *       {@link ch.owt.boatapp.bff.infrastructure.web.ScgUpstreamFailureFilter}
 *       to a 502 envelope with type
 *       {@code https://boatapp.owt.ch/problems/upstream-failure}. This
 *       replaces the deleted {@code BffUpstreamFailureIntegrationTest},
 *       which asserted the same contract over the old
 *       {@code RestClientResponseException} interceptor path.</li>
 *   <li>5xx WITH an RFC 9457 body → passed through (the BS may legitimately
 *       emit a 5xx envelope from its own registry; rewriting it would lose
 *       fidelity).</li>
 * </ul>
 */
@SpringBootTest(properties = "spring.config.import=classpath:application-routes.yml")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ScgErrorEnvelopePassThroughTest {

    private static final String SYNTHETIC_TOKEN = buildSyntheticJwt();

    private static HttpServer upstream;
    private static int upstreamPort;

    /** Per-test stub: status + content-type + body returned by the embedded server. */
    private static final AtomicInteger STUB_STATUS = new AtomicInteger();
    private static final AtomicReference<String> STUB_CONTENT_TYPE = new AtomicReference<>();
    private static final AtomicReference<String> STUB_BODY = new AtomicReference<>();

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
        upstream.createContext("/api/v1/boats", ScgErrorEnvelopePassThroughTest::handle);
        upstream.start();
        upstreamPort = upstream.getAddress().getPort();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop(0);
        }
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
            tmp = Files.createTempFile("bff-err-signing-key", ".pem",
                    PosixFilePermissions.asFileAttribute(EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException ex) {
            tmp = Files.createTempFile("bff-err-signing-key", ".pem");
        }
        Files.writeString(tmp, pem);
        tmp.toFile().deleteOnExit();
        registry.add("bff.signing-key.path", tmp::toString);
    }

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
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, SYNTHETIC_TOKEN,
                Instant.now(), Instant.now().plus(5, ChronoUnit.MINUTES));
        when(authorizedClientManager.authorize(any()))
                .thenReturn(new OAuth2AuthorizedClient(keycloak, "test-user", token));
    }

    /**
     * Upstream emits a 422 RFC 9457 envelope (mimicking a domain validation
     * failure on the Business Service). The BFF must forward it byte-identical:
     * same status, same {@code application/problem+json} content type, same
     * body string.
     */
    @Test
    @WithMockUser(username = "test-user")
    void upstream422ProblemDetailIsForwardedUnchanged() throws Exception {
        String upstreamBody = """
                {
                  "type": "https://boatapp.owt.ch/problems/validation",
                  "title": "Request validation failed",
                  "status": 422,
                  "detail": "Boat name already exists",
                  "instance": "/api/v1/boats",
                  "messages": [
                    {"severity": "ERROR", "code": "boat.name.duplicate", "message": "Boat name already exists", "field": "name"}
                  ]
                }""";
        stub(422, "application/problem+json", upstreamBody);

        MvcResult result = mockMvc.perform(get("/api/v1/boats")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(422);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getContentAsString()).isEqualToIgnoringWhitespace(upstreamBody);
    }

    /**
     * Upstream emits a 404 RFC 9457 envelope (BoatNotFoundException). Same
     * pass-through guarantee as 422.
     */
    @Test
    @WithMockUser(username = "test-user")
    void upstream404ProblemDetailIsForwardedUnchanged() throws Exception {
        String upstreamBody = """
                {"type":"https://boatapp.owt.ch/problems/not-found","title":"Boat not found",
                 "status":404,"instance":"/api/v1/boats/abc"}""";
        stub(404, "application/problem+json", upstreamBody);

        MvcResult result = mockMvc.perform(get("/api/v1/boats")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getContentAsString()).isEqualToIgnoringWhitespace(upstreamBody);
    }

    /**
     * Upstream emits 500 with a plain-text or HTML body — the BFF must
     * rewrite to a 502 RFC 9457 envelope and never leak the upstream body.
     * The {@code type} URI must come from the {@code ProblemTypes} registry.
     */
    @Test
    @WithMockUser(username = "test-user")
    void upstream500WithoutProblemDetailIsRewrittenAs502UpstreamFailure() throws Exception {
        stub(500, "text/plain", "internal whoops");

        MvcResult result = mockMvc.perform(get("/api/v1/boats")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"type\":\"https://boatapp.owt.ch/problems/upstream-failure\"")
                .contains("\"status\":502")
                .contains("\"instance\":\"/api/v1/boats\"")
                .doesNotContain("internal whoops");
    }

    /**
     * Upstream emits 503 with no body at all (e.g. connection reset before
     * write). Same rewrite as 500.
     */
    @Test
    @WithMockUser(username = "test-user")
    void upstream503WithEmptyBodyIsRewrittenAs502UpstreamFailure() throws Exception {
        stub(503, "text/plain", "");

        MvcResult result = mockMvc.perform(get("/api/v1/boats")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getContentAsString())
                .contains("\"type\":\"https://boatapp.owt.ch/problems/upstream-failure\"");
    }

    /**
     * Upstream emits 500 WITH a valid {@code application/problem+json} body.
     * The BFF must NOT rewrite it — the BS may legitimately surface a 5xx
     * envelope from its own registry, and rewriting would lose fidelity.
     */
    @Test
    @WithMockUser(username = "test-user")
    void upstream500WithProblemDetailIsForwardedUnchanged() throws Exception {
        String upstreamBody = """
                {"type":"https://boatapp.owt.ch/problems/internal","title":"Internal error",
                 "status":500,"instance":"/api/v1/boats"}""";
        stub(500, "application/problem+json", upstreamBody);

        MvcResult result = mockMvc.perform(get("/api/v1/boats")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(result.getResponse().getContentAsString()).isEqualToIgnoringWhitespace(upstreamBody);
    }

    private static void stub(int status, String contentType, String body) {
        STUB_STATUS.set(status);
        STUB_CONTENT_TYPE.set(contentType);
        STUB_BODY.set(body);
    }

    private static void handle(HttpExchange exchange) throws IOException {
        byte[] body = STUB_BODY.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", STUB_CONTENT_TYPE.get());
        // Use 0 as "no body" length convention — JDK's HttpExchange treats <0
        // as chunked, 0 as no-body. For an empty stub body, send no payload.
        exchange.sendResponseHeaders(STUB_STATUS.get(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

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
