package ch.owt.boatapp.bff.support;

import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Test helpers that mint a {@link RequestPostProcessor} carrying a fake
 * authenticated OAuth2 session, for use with {@code MockMvc} integration
 * tests of the BFF.
 *
 * <p>Spring Security's
 * {@link SecurityMockMvcRequestPostProcessors#oauth2Login()} attaches an
 * {@code OAuth2AuthenticationToken} to the {@code SecurityContext} and
 * stores a fake {@code OAuth2AuthorizedClient} (with a synthetic access
 * token) keyed by the registration id. The BFF's outbound RestClient
 * interceptor (declared in {@code BffConfig}) reads that authorized client
 * via {@code OAuth2AuthorizedClientManager} and attaches the synthetic
 * access token as {@code Authorization: Bearer ...} on every upstream call.
 *
 * <p>Critically, the test {@link ClientRegistration} uses
 * {@code client_secret_basic} client authentication — NOT the production
 * {@code private_key_jwt} setup — so no real RSA signing occurs. The full
 * Keycloak round-trip is exercised separately by the {@code @Disabled}
 * {@code KeycloakOAuthFlowIntegrationTest}.
 */
public final class SessionTestSupport {

    /** Registration id used in production wiring; tests must match it. */
    public static final String REGISTRATION_ID = "keycloak";

    private SessionTestSupport() {}

    /**
     * Build a {@link RequestPostProcessor} that simulates an authenticated
     * OAuth2 session for the demo user. The returned post-processor is the
     * BFF analogue of {@code JwtTestSupport.mockJwt()}.
     *
     * @return a post-processor for use in {@code mockMvc.perform(...).with(...)}
     */
    public static RequestPostProcessor authenticatedSession() {
        return SecurityMockMvcRequestPostProcessors.oauth2Login()
                .clientRegistration(testKeycloakRegistration())
                .attributes(a -> {
                    a.put("preferred_username", "demo");
                    a.put("email", "demo@example.test");
                    a.put("given_name", "Demo");
                    a.put("family_name", "User");
                    a.put("sub", "kc-user-1");
                });
    }

    /**
     * Build a synthetic {@link ClientRegistration} matching the production
     * {@code keycloak} registration id, but with stub URIs so no HTTP traffic
     * is ever generated. The {@code clientSecret} is a placeholder — never
     * sent because the test auth flow is bypassed.
     *
     * @return a stub Keycloak {@code ClientRegistration} keyed
     *         {@value #REGISTRATION_ID}
     */
    public static ClientRegistration testKeycloakRegistration() {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId("bff-test")
                .clientSecret("ignored")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/keycloak")
                .authorizationUri("http://localhost/kc/auth")
                .tokenUri("http://localhost/kc/token")
                .userInfoUri("http://localhost/kc/userinfo")
                .userNameAttributeName("preferred_username")
                .clientName("keycloak")
                .build();
    }
}
