package ch.owt.boatapp.bff.adapter.in.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Publishes the public half of the BFF's signing key at
 * {@code /.well-known/jwks.json} as a Nimbus {@code JWKSet}.
 *
 * <p>Keycloak fetches this JWKS to verify the {@code client_assertion} JWT
 * that the BFF signs when authenticating with {@code private_key_jwt} (no
 * shared client secret on the wire). {@code SecurityConfig} {@code permitAll}s
 * this path because the issuer must be able to fetch it without
 * authenticating to the BFF.
 *
 * <p><strong>Public-only.</strong> The bean injected here is the full
 * {@code RSAKey} (private + public material), but the response body is
 * obtained via {@link RSAKey#toPublicJWK()} → {@link JWKSet#toJSONObject()}
 * so private parameters ({@code d}, {@code p}, {@code q}, {@code dp},
 * {@code dq}, {@code qi}) NEVER reach the wire. The phase-02a4 verification
 * script greps this file for those tokens and fails the build if they appear.
 */
@RestController
public class JwksController {

    private final RSAKey signingKey;

    /**
     * @param signingKey the BFF's signing key (private + public). Only the
     *                   public half is ever serialized by this controller.
     */
    public JwksController(RSAKey signingKey) {
        this.signingKey = signingKey;
    }

    /**
     * Return the BFF's public JWK as an RFC 7517 {@code JWKSet} JSON document.
     *
     * <p>{@code Cache-Control: public, max-age=600} lets Keycloak (and any
     * other RFC 8414 / 7517 consumer) cache the JWKS for 10 minutes —
     * avoiding a hot dependency from Keycloak back to the BFF on every
     * {@code client_assertion} validation. Key rotation invalidates the
     * cache on {@code kid} mismatch, so this header is safe.
     *
     * @return a {@code JWKSet} containing exactly one public-only JWK
     */
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(new JWKSet(signingKey.toPublicJWK()).toJSONObject());
    }
}
