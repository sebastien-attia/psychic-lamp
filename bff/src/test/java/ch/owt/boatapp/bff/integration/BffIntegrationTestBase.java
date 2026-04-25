package ch.owt.boatapp.bff.integration;

import ch.owt.boatapp.bff.TestcontainersConfiguration;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.EnumSet;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base class for BFF Spring Boot integration tests.
 *
 * <p>Boots the full BFF context against a shared PostgreSQL Testcontainer
 * (declared by {@link TestcontainersConfiguration}, wired via
 * {@code @ServiceConnection} for {@code SPRING_SESSION} storage) plus a
 * WireMock server that stands in for the upstream Business Service.
 *
 * <p>WireMock is started in a static initializer (BEFORE
 * {@link DynamicPropertySource} fires) so its dynamic port is known at the
 * time {@code business-service.url} is registered. The
 * {@link WireMockServer} is JVM-scoped — one instance shared across every
 * test class, stopped via a JVM shutdown hook to avoid port-leak warnings.
 *
 * <p>{@code wiremock-standalone} is used (not {@code wiremock-spring-boot})
 * because the shaded jar relocates its internal Jetty so the project's own
 * transitive Jetty deps cannot conflict.
 *
 * <p>An ephemeral RSA signing key is generated per test class — no PEM
 * material lives under {@code src/test/resources/} — so
 * {@code BffConfig.bffSigningJwk} can be created on context refresh.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class BffIntegrationTestBase {

    /**
     * JVM-shared WireMock instance for the BFF→Business-Service stub. Started
     * eagerly in this static block (before any {@code @DynamicPropertySource}
     * runs) and stopped via a JVM shutdown hook. Subclasses interact with it
     * directly via {@link #wireMock}.
     *
     * <p>Sharing a single instance across test classes is safe under
     * Surefire's default model ({@code forkCount=1}, {@code reuseForks=true},
     * single test thread). If test parallelism is ever enabled
     * ({@code parallel=classes} or {@code forkCount>1}), revisit this
     * sharing — concurrent test classes would race on stub state even with
     * the per-test {@link #resetWireMock()} reset.
     */
    protected static final WireMockServer wireMock;

    static {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        Runtime.getRuntime().addShutdownHook(new Thread(wireMock::stop));
    }

    @Autowired
    protected MockMvc mockMvc;

    /** Reset stubs and recorded requests before every test. */
    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    /**
     * Bind {@code business-service.url} to the WireMock instance's base URL
     * and provide an ephemeral RSA signing-key path for
     * {@code BffConfig.bffSigningJwk}. Both must be registered before the
     * Spring context refreshes; {@code @DynamicPropertySource} fires early
     * enough.
     *
     * @param registry Spring's runtime property registry
     * @throws Exception if RSA generation or temp-file IO fails
     */
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) throws Exception {
        registry.add("business-service.url", () -> "http://localhost:" + wireMock.port());

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                        .replaceAll("(.{64})", "$1\n")
                + "\n-----END PRIVATE KEY-----\n";
        Path tmp;
        try {
            tmp = Files.createTempFile("bff-it-signing-key", ".pem",
                    PosixFilePermissions.asFileAttribute(EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException ex) {
            tmp = Files.createTempFile("bff-it-signing-key", ".pem");
        }
        Files.writeString(tmp, pem);
        tmp.toFile().deleteOnExit();
        registry.add("bff.signing-key.path", tmp::toString);
    }
}
