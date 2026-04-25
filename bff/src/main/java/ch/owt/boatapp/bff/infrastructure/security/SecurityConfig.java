package ch.owt.boatapp.bff.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * BFF security configuration for non-dev profiles
 * ({@code local-intg}, {@code staging}, {@code prod}).
 *
 * <p>Configures an OAuth2 session-based login against Keycloak. Sessions are
 * stored server-side in PostgreSQL via Spring Session JDBC (configured in
 * {@code application.yml}) — the browser only ever sees the opaque
 * {@code SESSION} cookie. The OAuth2 access token is forwarded as a
 * {@code Bearer} header on outbound calls to the Business Service by an
 * interceptor wired in {@code BffConfig}.
 *
 * <p>CSRF uses a non-HttpOnly cookie ({@code XSRF-TOKEN}) plus the
 * {@link SpaCsrfTokenRequestHandler} so the SPA's {@code fetch} /
 * {@code axios} can read the cookie and echo it in {@code X-XSRF-TOKEN} on
 * unsafe requests.
 *
 * <p>401 handling is split: AJAX requests under {@code /api/**} get the
 * {@link RestAuthenticationEntryPoint} RFC 9457 envelope, full-page
 * navigations fall through to {@code oauth2Login}'s default redirect so the
 * browser kicks off the Authorization Code flow.
 *
 * <p>Logout invalidates the HTTP session, deletes the {@code SESSION} and
 * {@code XSRF-TOKEN} cookies, then triggers Keycloak's RP-initiated logout
 * via {@link OidcClientInitiatedLogoutSuccessHandler}.
 */
@Configuration
@Profile("!dev")
public class SecurityConfig {

    private final RestAuthenticationEntryPoint apiAuthenticationEntryPoint;

    /**
     * @param apiAuthenticationEntryPoint emits the RFC 9457 401 envelope for
     *                                    requests under {@code /api/**}
     */
    public SecurityConfig(RestAuthenticationEntryPoint apiAuthenticationEntryPoint) {
        this.apiAuthenticationEntryPoint = apiAuthenticationEntryPoint;
    }

    /**
     * Build the OAuth2 + session security filter chain.
     *
     * @param http                          Spring's filter-chain builder
     * @param clientRegistrationRepository  registry of OAuth2 clients (used
     *                                      by the OIDC logout handler to
     *                                      look up the {@code keycloak}
     *                                      provider's end-session endpoint)
     * @return the configured {@link SecurityFilterChain} bean
     * @throws Exception if the underlying configurers reject the configuration
     *                   (Spring's API contract; not thrown in practice)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   ClientRegistrationRepository clientRegistrationRepository)
            throws Exception {
        SpaCsrfTokenRequestHandler csrfHandler = new SpaCsrfTokenRequestHandler();

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                PathPatternRequestMatcher.withDefaults().matcher("/"),
                                PathPatternRequestMatcher.withDefaults().matcher("/index.html"),
                                PathPatternRequestMatcher.withDefaults().matcher("/assets/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/favicon.ico"),
                                PathPatternRequestMatcher.withDefaults().matcher("/.well-known/jwks.json"),
                                PathPatternRequestMatcher.withDefaults().matcher("/actuator/health"),
                                PathPatternRequestMatcher.withDefaults().matcher("/actuator/health/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui.html"),
                                PathPatternRequestMatcher.withDefaults().matcher("/v3/api-docs/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/login/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/oauth2/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/logout")
                        ).permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(login -> {})
                .logout(logout -> logout
                        // Limit the logout filter to POST so that an unauthenticated
                        // browser hitting /logout via a stale link does not trigger
                        // an OIDC redirect just to log out a non-existent session.
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/logout"))
                        .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrationRepository))
                        .invalidateHttpSession(true)
                        .deleteCookies("SESSION", "XSRF-TOKEN"))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                apiAuthenticationEntryPoint,
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**")));

        return http.build();
    }

    /**
     * Build the OIDC RP-initiated logout success handler.
     *
     * <p>After local logout the user is redirected to Keycloak's end-session
     * endpoint, which clears the IDP session and bounces the browser back to
     * the BFF root ({@code "{baseUrl}"}).
     */
    private OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler(
            ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        handler.setPostLogoutRedirectUri("{baseUrl}");
        return handler;
    }
}
