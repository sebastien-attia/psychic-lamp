package ch.owt.boatapp.bff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Initializr-style sanity test: verifies the Spring context wires cleanly
 * under {@code @Profile("!dev")} (the default when no profile is active).
 *
 * <p>{@link BffConfig#bffSigningJwk(Path, String)} reads a PKCS#8 PEM at
 * bean-creation time, so the test must register a key file BEFORE the
 * context refreshes — {@link DynamicPropertySource} fires early enough.
 * We generate the key per test run (rather than committing a fixture PEM)
 * so no private-key material lives under {@code src/test/resources/}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BffApplicationTests {

    /**
     * Generate an ephemeral RSA key, write it as a PKCS#8 PEM to a temp
     * file and bind {@code bff.signing-key.path} to that path so
     * {@code BffConfig.bffSigningJwk} can read it on context refresh.
     *
     * @param registry Spring's runtime property registry
     * @throws Exception if RSA generation or temp-file IO fails (the
     *                   {@code KeyPairGenerator} / {@code Files} APIs
     *                   declare checked exceptions)
     */
    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded())
                        .replaceAll("(.{64})", "$1\n")
                + "\n-----END PRIVATE KEY-----\n";
        // Restrict the temp file to owner-only (0600) so the ephemeral RSA
        // material is not world-readable on multi-tenant CI runners. POSIX
        // perms are best-effort: on filesystems that don't support them
        // (e.g. tmpfs without ACLs, Windows) the call falls back silently.
        Path tmp;
        try {
            tmp = Files.createTempFile("bff-test-signing-key", ".pem",
                    PosixFilePermissions.asFileAttribute(EnumSet.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException ex) {
            tmp = Files.createTempFile("bff-test-signing-key", ".pem");
        }
        Files.writeString(tmp, pem);
        tmp.toFile().deleteOnExit();
        registry.add("bff.signing-key.path", tmp::toString);
    }

    /**
     * Smoke test: the application context refreshes successfully.
     */
    @Test
    void contextLoads() {
    }
}
