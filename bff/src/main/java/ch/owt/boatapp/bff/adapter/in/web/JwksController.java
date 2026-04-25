package ch.owt.boatapp.bff.adapter.in.web;

import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the BFF's signing key at
 * {@code /.well-known/jwks.json} as a Nimbus {@code JWKSet}.
 *
 * <p>Keycloak fetches this JWKS to verify the {@code client_assertion} JWT
 * that the BFF signs when authenticating with {@code private_key_jwt} (no
 * shared client secret on the wire). {@code SecurityConfig} must
 * {@code permitAll} this path.
 *
 * <p>The endpoint body is added in step 02a4 once {@code BffConfig} exposes
 * the {@code RSAKey} bean.
 */
@RestController
public class JwksController {
}
