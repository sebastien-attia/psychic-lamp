package ch.owt.boatapp.bff.infrastructure.security;

import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.client.RestClient;

/**
 * BFF security configuration for non-dev profiles
 * ({@code local-intg}, {@code staging}, {@code prod}).
 *
 * <p>Configures an OAuth2 session-based login against Keycloak. Sessions are
 * stored server-side in PostgreSQL via Spring Session JDBC (configured in
 * {@code application.yml}) — the browser only ever sees the opaque
 * {@code SESSION} cookie. Outbound proxying to the Business Service is
 * handled by Spring Cloud Gateway: routes are declared in
 * {@code application-routes.yml} and the {@code TokenRelay} filter forwards
 * the user's access token (resolved via the
 * {@code OAuth2AuthorizedClientManager} bean defined in {@code BffConfig})
 * as a {@code Bearer} header on every upstream call.
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
 * <p>Logout terminates the user's Keycloak SSO session server-to-server (via
 * {@link KeycloakServerSideLogoutHandler}) before invalidating the HTTP
 * session and deleting the {@code SESSION} and {@code XSRF-TOKEN} cookies.
 * No browser redirect to Keycloak is required, which avoids the
 * Chromium/Brave cookie-policy hazards that break the classic OIDC
 * RP-initiated logout flow on plain-HTTP {@code local-intg}.
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
     * @param keycloakLogoutHandler         server-to-server logout call to
     *                                      Keycloak; runs before the session
     *                                      is invalidated so the refresh
     *                                      token (stored in the session) is
     *                                      still readable
     * @return the configured {@link SecurityFilterChain} bean
     * @throws Exception if the underlying configurers reject the configuration
     *                   (Spring's API contract; not thrown in practice)
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            KeycloakServerSideLogoutHandler keycloakLogoutHandler,
            CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        SpaCsrfTokenRequestHandler csrfHandler = new SpaCsrfTokenRequestHandler();

        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                // SPA static surface (`/`, `/index.html`, `/assets/**`,
                                // `/favicon.ico`) is intentionally NOT in this list —
                                // the SPA leaves the BFF jar after the SCG migration.
                                // In dev / local-intg the SPA is served by Vite at :5173;
                                // in staging / prod it is hosted on Azure Static Web Apps.
                                // Hitting these paths on the BFF must 404, and
                                // `BffStaticContentRegressionTest` enforces that.
                                PathPatternRequestMatcher.withDefaults().matcher("/.well-known/jwks.json"),
                                PathPatternRequestMatcher.withDefaults().matcher("/actuator/health"),
                                PathPatternRequestMatcher.withDefaults().matcher("/actuator/health/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui.html"),
                                PathPatternRequestMatcher.withDefaults().matcher("/v3/api-docs/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/login/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/oauth2/**"),
                                PathPatternRequestMatcher.withDefaults().matcher("/logout"),
                                PathPatternRequestMatcher.withDefaults().matcher("/api/logout")
                        ).permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(login -> {})
                .logout(logout -> logout
                        // Limit the logout filter to POST so an unauthenticated
                        // browser hitting /logout via a stale link does not
                        // trigger a redirect just to log out a non-existent
                        // session. The SPA posts to /api/logout (its axios
                        // baseURL is /api), so that's the path we match.
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, "/api/logout"))
                        // Keycloak SSO session terminated server-to-server,
                        // BEFORE Spring's SecurityContextLogoutHandler
                        // invalidates the HTTP session — otherwise the
                        // refresh token (session-scoped) would be gone.
                        .addLogoutHandler(keycloakLogoutHandler)
                        // Return 204 instead of redirecting: the SPA's
                        // auth.ts already does window.location.assign('/')
                        // after the POST, so a server-side redirect would
                        // just be a wasted round-trip.
                        .logoutSuccessHandler(
                                new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT))
                        .invalidateHttpSession(true)
                        .deleteCookies("SESSION", "XSRF-TOKEN"))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfHandler))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                apiAuthenticationEntryPoint,
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**")));

        return http.build();
    }

    /**
     * Wire the {@link KeycloakServerSideLogoutHandler} bean.
     *
     * <p>Reuses {@code bffSigningJwk} (defined in {@code BffConfig}) so the
     * {@code client_assertion} JWT is signed with the same key Keycloak
     * already trusts via the JWKS endpoint at {@code /.well-known/jwks.json}.
     * The {@link RestClient} is built fresh (no auto-applied interceptors) to
     * keep the logout call independent of the BFF's Business-Service
     * interceptor chain.
     *
     * @param authorizedClientRepository    per-principal authorized-client
     *                                      storage (Spring Session JDBC) —
     *                                      source of the refresh token
     * @param clientRegistrationRepository  resolves the {@code keycloak}
     *                                      client registration to extract the
     *                                      end-session URL and token-endpoint
     *                                      audience
     * @param bffSigningJwk                 the BFF's RSA signing key
     * @return the configured {@link KeycloakServerSideLogoutHandler}
     */
    @Bean
    public KeycloakServerSideLogoutHandler keycloakServerSideLogoutHandler(
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            ClientRegistrationRepository clientRegistrationRepository,
            RSAKey bffSigningJwk) {
        return new KeycloakServerSideLogoutHandler(
                authorizedClientRepository,
                clientRegistrationRepository,
                bffSigningJwk,
                RestClient.builder().build());
    }

    /**
     * Build the {@link CookieCsrfTokenRepository} used to issue and validate the
     * {@code XSRF-TOKEN} cookie consumed by the SPA.
     *
     * <p>Three attributes are set explicitly rather than left to Spring's
     * defaults so the cookie's behavior does not silently depend on
     * proxy-header forwarding or browser-implicit defaults:
     * <ul>
     *   <li>{@code SameSite=Lax} — the SPA POSTs to {@code /api/logout} on the
     *       same origin, so {@code Lax} is sufficient and avoids the
     *       {@code SameSite=None;Secure} requirement that breaks plain-HTTP
     *       {@code local-intg}.</li>
     *   <li>{@code Path=/} — guarantees the cookie is sent on every request
     *       under the BFF root, including {@code /api/logout}, regardless of
     *       any future path-based scoping by Spring.</li>
     *   <li>{@code Secure} — driven by {@code server.servlet.session.cookie.secure}
     *       so the XSRF cookie's transport-security policy mirrors the SESSION
     *       cookie's per-profile setting (false on local-intg HTTP, true on
     *       staging/prod HTTPS). Without this we would silently fall back to
     *       {@code request.isSecure()}, which depends on
     *       {@code X-Forwarded-Proto} being honored end-to-end.</li>
     * </ul>
     *
     * @param secure value of {@code server.servlet.session.cookie.secure} —
     *               applied to the XSRF cookie so both cookies share the same
     *               transport-security policy
     * @return the configured CSRF token repository
     */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .sameSite("Lax")
                .path("/")
                .secure(secure));
        return repository;
    }
}
