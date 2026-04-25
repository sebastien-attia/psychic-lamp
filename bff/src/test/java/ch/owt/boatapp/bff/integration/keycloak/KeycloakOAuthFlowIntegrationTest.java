package ch.owt.boatapp.bff.integration.keycloak;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live smoke test that proves the BFF's OAuth2 wiring talks to a real Keycloak
 * server, end-to-end across realm import → token issuance → JWT signing.
 *
 * <p>The bulk of BFF security behaviour (RFC 9457 forwarding, CSRF, session,
 * token attachment) is covered by faster MockMvc + WireMock + {@code
 * oauth2Login()} mock tests under {@code ../}. What WireMock cannot reproduce
 * — and what only a real Keycloak can validate — is that the issuer URI,
 * realm import, and JWT signing are all wired correctly. This test catches
 * regressions there and nothing else.
 *
 * <p>Realm fixture: {@code src/test/resources/boat-app-realm.json} (Keycloak
 * 26.6 enforces {@code <realm>-realm.json} naming on import). The fixture is
 * a simplified mirror of {@code infra/keycloak/realm.yaml} that swaps {@code
 * client-jwt} for {@code client-secret-basic} and enables direct access
 * grants so we can fetch a token without driving an authorization-code flow.
 * The full {@code private_key_jwt} dance against the real {@code
 * boat-app-confidential} client is exercised by the Playwright E2E suite
 * against {@code make up}.
 */
@Testcontainers
class KeycloakOAuthFlowIntegrationTest {

    private static final String REALM = "boat-app";
    private static final String CLIENT_ID = "boat-app-test";
    private static final String CLIENT_SECRET = "test-secret";
    private static final String USERNAME = "demo";
    private static final String PASSWORD = "demo123";

    /*
     * Wait for the Quarkus "Listening on:" log line instead of the
     * default HTTP probe on the request port. Keycloak 26 exposes
     * `/health/*` only on the management interface (port 9000), so the
     * library's default HttpWaitStrategy aimed at the request port
     * (8080) times out with ECONNREFUSED — which is what makes the
     * container appear "failed to start" even though it is healthy.
     */
    @Container
    static KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.6.1")
            .withRealmImportFile("/boat-app-realm.json")
            .waitingFor(Wait.forLogMessage(".*Listening on:.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    /**
     * Requests an access token via the resource-owner password grant against
     * the realm imported from {@code boat-app-realm.json}, then parses the
     * JWT and verifies the issuer + subject claims match what Keycloak
     * should have minted for the seeded {@code demo} user.
     *
     * <p>Failure modes this catches: realm import broke (no token), wrong
     * realm name in the URL (404 from Keycloak), Keycloak version
     * incompatibility with the realm JSON (5xx), JWT signing pipeline broken
     * (parse failure), claims pipeline broken (missing {@code
     * preferred_username}).
     */
    @Test
    void issues_signed_jwt_with_expected_claims_for_demo_user() throws Exception {
        String accessToken = fetchAccessTokenViaPasswordGrant();

        SignedJWT jwt = SignedJWT.parse(accessToken);
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        assertThat(claims.getIssuer())
                .isEqualTo(keycloak.getAuthServerUrl() + "/realms/" + REALM);
        assertThat(claims.getStringClaim("preferred_username")).isEqualTo(USERNAME);
        assertThat(claims.getStringClaim("azp")).isEqualTo(CLIENT_ID);
        assertThat(claims.getExpirationTime()).isAfter(claims.getIssueTime());
    }

    /**
     * POSTs to the Keycloak token endpoint with {@code grant_type=password}
     * and returns the raw access_token string from the JSON response.
     */
    private String fetchAccessTokenViaPasswordGrant() throws Exception {
        String tokenEndpoint = keycloak.getAuthServerUrl()
                + "/realms/" + REALM + "/protocol/openid-connect/token";
        String body = formEncode(Map.of(
                "grant_type", "password",
                "client_id", CLIENT_ID,
                "client_secret", CLIENT_SECRET,
                "username", USERNAME,
                "password", PASSWORD));

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()) {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(tokenEndpoint))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(resp.statusCode())
                    .as("token endpoint response: %s", resp.body())
                    .isEqualTo(200);
            return JSONObjectUtils.parse(resp.body()).get("access_token").toString();
        }
    }

    /**
     * Encodes a key/value map as {@code application/x-www-form-urlencoded}
     * with proper percent-encoding for both keys and values.
     */
    private static String formEncode(Map<String, String> kv) {
        return kv.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                        + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
