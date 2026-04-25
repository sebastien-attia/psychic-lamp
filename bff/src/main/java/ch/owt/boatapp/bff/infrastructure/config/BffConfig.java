package ch.owt.boatapp.bff.infrastructure.config;

import org.springframework.context.annotation.Configuration;

/**
 * BFF infrastructure wiring.
 *
 * <p>Hosts:
 * <ul>
 *   <li>the Nimbus {@code RSAKey} bean built from the PEM at {@code bff.signing-key.path}
 *       (used to sign the {@code client_assertion} JWT for {@code private_key_jwt}
 *       and exposed as a {@code JWKSet} by {@link ch.owt.boatapp.bff.adapter.in.web.JwksController});</li>
 *   <li>the {@code BusinessServiceClient} {@code @HttpExchange} bean built from
 *       the OpenAPI-generated interface;</li>
 *   <li>the token-forwarding {@code RestClient} interceptor that injects the
 *       OAuth2 {@code Bearer} access token into every outbound call.</li>
 * </ul>
 *
 * <p>Bean methods are added in step 02a4.
 */
@Configuration
public class BffConfig {
}
