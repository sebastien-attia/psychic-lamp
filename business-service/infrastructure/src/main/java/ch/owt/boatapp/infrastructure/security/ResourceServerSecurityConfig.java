package ch.owt.boatapp.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Business Service security configuration for non-dev profiles.
 *
 * <p>Configures the service as a stateless OAuth2 resource server: every
 * request must carry a {@code Bearer} JWT issued by Keycloak; the JWKS is
 * auto-fetched from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
 * (or {@code jwk-set-uri} in tests). No session, no CSRF, no OAuth2 client.
 *
 * <p>401 responses are committed by {@link RestAuthenticationEntryPoint},
 * which writes an RFC 9457 {@code ProblemDetail} body matching every other
 * 4xx response. The default {@code BearerTokenAuthenticationEntryPoint} is
 * therefore overridden on both the {@code oauth2ResourceServer} configurer
 * (for token-validation failures) and the top-level {@code exceptionHandling}
 * configurer (for anonymous-access rejections on protected paths).
 *
 * <p>Public paths (no auth): {@code /actuator/health[/**]},
 * {@code /swagger-ui/**}, {@code /v3/api-docs/**}. Everything else requires
 * a valid JWT.
 */
@Configuration
@Profile("!dev")
public class ResourceServerSecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;

    /**
     * @param authenticationEntryPoint emits the RFC 9457 401 envelope when
     *                                 the request is unauthenticated or
     *                                 carries an invalid JWT
     */
    public ResourceServerSecurityConfig(RestAuthenticationEntryPoint authenticationEntryPoint) {
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    /**
     * Build the resource-server security filter chain.
     *
     * @param http Spring's filter-chain builder
     * @return the configured {@link SecurityFilterChain} bean
     * @throws Exception if the underlying configurers reject the configuration
     *                   (Spring's API contract; not thrown in practice with
     *                   the stock JWT setup)
     */
    @Bean
    public SecurityFilterChain resourceServerFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
