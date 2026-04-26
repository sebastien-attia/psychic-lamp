package ch.owt.boatapp.bff.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * BFF dev-profile fallback security configuration.
 *
 * <p>The BFF is not normally started in {@code dev}: the frontend dev proxy
 * talks straight to the Business Service on {@code :8081}. This class only
 * exists so that ad-hoc {@code SPRING_PROFILES_ACTIVE=dev} BFF runs do not
 * crash on the missing {@code keycloak} OAuth2 client registration.
 *
 * <p>The filter chain {@code permitAll}s every request, runs stateless
 * (no session, no Spring Session JDBC schema needed) and disables CSRF.
 *
 * <p>Note: {@code OAuth2ClientAutoConfiguration} is NOT excluded here.
 * Boot's autoconfiguration only fails when {@code oauth2Login} is activated,
 * which we never do in dev — so the dev profile boots cleanly without a
 * stub Keycloak registration.
 */
@Configuration
@Profile("dev")
public class DevSecurityConfig {

    /**
     * Build the permit-all filter chain used in dev mode.
     *
     * @param http Spring's filter-chain builder
     * @return the configured {@link SecurityFilterChain} bean
     * @throws Exception if the underlying configurers reject the configuration
     *                   (Spring's API contract; not thrown in practice)
     */
    @Bean
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
