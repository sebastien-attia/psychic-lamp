package ch.owt.boatapp.testsupport;

import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Test helpers that mint a {@link RequestPostProcessor} carrying a fake JWT,
 * for use with {@code MockMvc} integration / slice tests of the Business
 * Service.
 *
 * <p>Spring Security's {@link SecurityMockMvcRequestPostProcessors#jwt()}
 * injects a {@code JwtAuthenticationToken} directly into the
 * {@code SecurityContext}, bypassing the real {@code JwtDecoder} and any
 * Keycloak instance. The Business Service is a stateless OAuth2 resource
 * server, so this is sufficient for every test — there is no session to
 * maintain and no token to refresh.
 *
 * <p>Lives in the {@code infrastructure} test-jar so both
 * {@code @SpringBootTest} integration tests in {@code bootstrap} and
 * {@code @WebMvcTest} slice tests in {@code infrastructure} share the same
 * helper.
 */
public final class JwtTestSupport {

    /** Default value for the JWT {@code sub} claim — used as Keycloak ID. */
    public static final String SUB = "11111111-1111-1111-1111-111111111111";

    /** Default value for the JWT {@code preferred_username} claim. */
    public static final String USERNAME = "demo";

    /** Default value for the JWT {@code email} claim. */
    public static final String EMAIL = "demo@example.test";

    /** Default value for the JWT {@code given_name} claim. */
    public static final String GIVEN_NAME = "Demo";

    /** Default value for the JWT {@code family_name} claim. */
    public static final String FAMILY_NAME = "User";

    private JwtTestSupport() {}

    /**
     * Build a {@link RequestPostProcessor} carrying a fake JWT with the
     * standard demo-user claims ({@link #SUB}, {@link #USERNAME},
     * {@link #EMAIL}, {@link #GIVEN_NAME}, {@link #FAMILY_NAME}).
     *
     * @return a post-processor for use in {@code mockMvc.perform(...).with(...)}
     */
    public static RequestPostProcessor mockJwt() {
        return mockJwt(SUB, USERNAME, EMAIL, GIVEN_NAME, FAMILY_NAME);
    }

    /**
     * Build a {@link RequestPostProcessor} carrying a fake JWT with custom
     * claim values. Useful when a test needs to assert that
     * {@code AppUser.keycloakId} (derived from {@code sub}) tracks the JWT.
     *
     * @param sub      the {@code sub} claim — becomes {@code AppUser.keycloakId}
     * @param username the {@code preferred_username} claim
     * @param email    the {@code email} claim
     * @param given    the {@code given_name} claim
     * @param family   the {@code family_name} claim
     * @return a post-processor for use in {@code mockMvc.perform(...).with(...)}
     */
    public static RequestPostProcessor mockJwt(String sub, String username,
                                               String email, String given, String family) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j
                .subject(sub)
                .claim("preferred_username", username)
                .claim("email", email)
                .claim("given_name", given)
                .claim("family_name", family));
    }
}
