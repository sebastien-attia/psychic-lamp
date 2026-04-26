package ch.owt.boatapp.bff.infrastructure.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * BFF infrastructure wiring for the OAuth2 stack.
 *
 * <p>This configuration assembles three collaborating beans:
 * <ol>
 *   <li>{@link #bffSigningJwk(Path, String) bffSigningJwk} — loads the BFF's
 *       PKCS#8 PEM signing key from disk and exposes it as a Nimbus
 *       {@code RSAKey}. The same JWK (public half only) is republished by
 *       {@code JwksController} at {@code /.well-known/jwks.json} so that
 *       Keycloak can verify the BFF's {@code client_assertion}.</li>
 *   <li>{@link #codeTokenResponseClient(RSAKey)} and
 *       {@link #refreshTokenResponseClient(RSAKey)} — token-endpoint clients
 *       for the {@code authorization_code} and {@code refresh_token} grants.
 *       Both wrap a {@link NimbusJwtClientAuthenticationParametersConverter}
 *       so every request to Keycloak's token endpoint is authenticated with
 *       a freshly-signed {@code client_assertion} JWT
 *       ({@code private_key_jwt}) — no shared {@code client_secret} is ever
 *       sent over the wire.</li>
 *   <li>{@link #authorizedClientManager} — the BFF's
 *       {@link OAuth2AuthorizedClientManager}, wired with both providers and
 *       the {@link OAuth2AuthorizedClientRepository} (backed by Spring
 *       Session JDBC) so refresh-token rotation happens transparently
 *       across BFF restarts.</li>
 * </ol>
 *
 * <p>Outbound proxying to the Business Service is no longer wired here.
 * Spring Cloud Gateway's {@code TokenRelay} filter (declared in
 * {@code application-routes.yml}) discovers this configuration's
 * {@link OAuth2AuthorizedClientManager} bean at request time via the servlet
 * application context, attaches the current user's access token as a
 * {@code Bearer} header on the outbound request, and triggers refresh
 * transparently when the access token has expired.
 *
 * <p>Dev profile note: this configuration is gated on {@code @Profile("!dev")}
 * because the dev profile does not start the BFF and the
 * {@code bff.signing-key.path} property is not set there. In dev mode the
 * front-end Vite proxy talks straight to the Business Service.
 */
@Configuration
@Profile("!dev")
public class BffConfig {

    /**
     * Load the BFF's signing key from {@code bff.signing-key.path} (a PKCS#8
     * PEM file) and build the {@code RSAKey} used both to sign
     * {@code client_assertion} JWTs and to publish at
     * {@code /.well-known/jwks.json}.
     *
     * <p>The PEM is parsed in two steps: strip the {@code -----BEGIN/END-----}
     * armor and base64-decode the body, then derive the public key from the
     * private key's CRT parameters. Wrapping the resulting key pair into a
     * Nimbus {@code RSAKey} attaches the {@code kid}, {@code use=sig} and
     * {@code alg=RS256} metadata that Keycloak requires.
     *
     * @param keyPath filesystem path to the PKCS#8 PEM private key
     * @param kid     stable key identifier (the {@code kid} claim used by
     *                Keycloak to look up the public half in the JWKS)
     * @return the BFF's signing key as an {@code RSAKey}
     * @throws IllegalStateException if the file cannot be read, the key
     *         material is malformed, or the JCE provider lacks RSA support;
     *         the message names the offending path so deployment failures
     *         are diagnosable without reading the bean source
     */
    @Bean
    public RSAKey bffSigningJwk(@Value("${bff.signing-key.path}") Path keyPath,
                                @Value("${bff.signing-key.id}") String kid) {
        String pem;
        try {
            pem = Files.readString(keyPath);
        } catch (IOException ex) {
            throw new IllegalStateException(
                    "Cannot read BFF signing key at " + keyPath, ex);
        }
        String stripped = pem
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] der = Base64.getDecoder().decode(stripped);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey rsaPrivate =
                    (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAPublicKey rsaPublic = (RSAPublicKey) factory.generatePublic(
                    new RSAPublicKeySpec(rsaPrivate.getModulus(), rsaPrivate.getPublicExponent()));
            return new RSAKey.Builder(rsaPublic)
                    .privateKey(rsaPrivate)
                    .keyID(kid)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException
                | IllegalArgumentException | ClassCastException ex) {
            throw new IllegalStateException(
                    "BFF signing key at " + keyPath + " is not a valid PKCS#8 RSA private key", ex);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(
                    "JCE provider rejected BFF signing key at " + keyPath, ex);
        }
    }

    /**
     * Build the {@link OAuth2AccessTokenResponseClient} used for the
     * {@code authorization_code} grant during the initial OAuth2 login.
     *
     * <p>The bean is type-discovered by Spring Security's
     * {@code OAuth2LoginConfigurer} (no explicit wiring needed in
     * {@code SecurityConfig}). Adding the
     * {@link NimbusJwtClientAuthenticationParametersConverter} ensures every
     * code-for-token exchange carries a signed {@code client_assertion}.
     *
     * @param bffSigningJwk the BFF's signing key (private+public)
     * @return the configured token-response client for the auth-code grant
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> codeTokenResponseClient(
            RSAKey bffSigningJwk) {
        NimbusJwtClientAuthenticationParametersConverter<OAuth2AuthorizationCodeGrantRequest> converter =
                new NimbusJwtClientAuthenticationParametersConverter<>(reg -> bffSigningJwk);
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.addParametersConverter(converter);
        return client;
    }

    /**
     * Build the {@link OAuth2AccessTokenResponseClient} used for the
     * {@code refresh_token} grant — fired transparently by the
     * {@link OAuth2AuthorizedClientManager} whenever the access token has
     * expired.
     *
     * <p>Wired into the manager's {@link OAuth2AuthorizedClientProvider}
     * chain in {@link #authorizedClientManager}. Same {@code private_key_jwt}
     * configuration as the auth-code client.
     *
     * @param bffSigningJwk the BFF's signing key (private+public)
     * @return the configured token-response client for the refresh-token grant
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient(
            RSAKey bffSigningJwk) {
        NimbusJwtClientAuthenticationParametersConverter<OAuth2RefreshTokenGrantRequest> converter =
                new NimbusJwtClientAuthenticationParametersConverter<>(reg -> bffSigningJwk);
        RestClientRefreshTokenTokenResponseClient client = new RestClientRefreshTokenTokenResponseClient();
        client.addParametersConverter(converter);
        return client;
    }

    /**
     * Build the {@link OAuth2AuthorizedClientManager} consumed by Spring Cloud
     * Gateway's {@code TokenRelay} filter (declared in
     * {@code application-routes.yml}) to fetch — and refresh, on expiry — the
     * current user's access token before relaying it on the upstream call to
     * the Business Service. {@code TokenRelayFilterFunctions} resolves this
     * bean per request via the servlet application context, so no explicit
     * SCG wiring is required beyond the starter.
     *
     * <p>The provider chain accepts authorization-code grants (the manager
     * does not actually fetch them — login does — but the chain must allow
     * the grant or {@code authorize(...)} would reject the principal) and
     * refresh-token grants (here we wire our private_key_jwt token client).
     *
     * <p><strong>Servlet-scoped.</strong> {@link DefaultOAuth2AuthorizedClientManager}
     * looks up the per-principal authorized-client via the current servlet
     * request — calling {@code authorize(...)} from a non-request thread
     * (e.g. {@code @Async}, {@code @Scheduled}) throws
     * {@code IllegalArgumentException}. Any future background flow that
     * needs an authorized client must use
     * {@code AuthorizedClientServiceOAuth2AuthorizedClientManager} instead.
     *
     * @param registrations               OAuth2 client registry (loaded from
     *                                    {@code application-{profile}.yml})
     * @param clientRepo                  per-principal authorized-client
     *                                    storage (backed by Spring Session JDBC)
     * @param refreshTokenResponseClient  token-endpoint client for the
     *                                    refresh-token grant
     * @return the configured {@link OAuth2AuthorizedClientManager}
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository clientRepo,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenResponseClient) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken(c -> c.accessTokenResponseClient(refreshTokenResponseClient))
                .build();
        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(registrations, clientRepo);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

}
