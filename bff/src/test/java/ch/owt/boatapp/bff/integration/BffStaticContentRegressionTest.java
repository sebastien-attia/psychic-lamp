package ch.owt.boatapp.bff.integration;

import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard: after the SCG migration the BFF must NOT serve the SPA
 * static bundle. The SPA leaves the jar — it is hosted by Vite in
 * dev / local-intg and by Azure Static Web Apps in staging / prod. If a
 * future refactor accidentally re-introduces the {@code resources/static}
 * splice from the old Dockerfile, this test fails on the first build.
 *
 * <p>Direct contract:
 * <ul>
 *   <li>Static SPA paths ({@code /}, {@code /index.html},
 *       {@code /assets/foo.js}) must NOT return 2xx with HTML content. The
 *       precise non-2xx is implementation-detail (today they yield 401 from
 *       Spring Security's {@code anyRequest().authenticated()}, since they
 *       are no longer in the {@code permitAll} list); what matters for the
 *       regression is that no static handler picks them up.</li>
 *   <li>Surviving BFF-local endpoints ({@code /.well-known/jwks.json},
 *       {@code /actuator/health}, {@code /api/me}) must continue to respond
 *       2xx with the expected shape.</li>
 * </ul>
 */
class BffStaticContentRegressionTest extends BffIntegrationTestBase {

    /**
     * SPA root must NOT be served by the BFF. Asserts the response is not
     * a 2xx — anything else (401, 404, 302) proves no static-resource
     * handler matched.
     */
    @Test
    @WithAnonymousUser
    void spaRootIsNotServedByBff() throws Exception {
        MvcResult result = mockMvc.perform(get("/")).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("BFF must not serve the SPA root '/' — SPA hosted by Vite/SWA")
                .matches(s -> s < 200 || s >= 300, "must NOT be 2xx");
    }

    /**
     * Specific check for {@code /index.html} — the most common path a
     * regression would re-expose by accidentally re-adding the
     * {@code resources/static/} splice.
     */
    @Test
    @WithAnonymousUser
    void indexHtmlIsNotServedByBff() throws Exception {
        MvcResult result = mockMvc.perform(get("/index.html")).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("BFF must not serve /index.html — SPA hosted by Vite/SWA")
                .matches(s -> s < 200 || s >= 300, "must NOT be 2xx");
    }

    /**
     * Sample fingerprinted asset path — what Vite emits in production builds.
     * The exact name does not matter; the path prefix is what matters.
     */
    @Test
    @WithAnonymousUser
    void assetsPathIsNotServedByBff() throws Exception {
        MvcResult result = mockMvc.perform(get("/assets/app.123abc.js")).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("BFF must not serve /assets/** — SPA hosted by Vite/SWA")
                .matches(s -> s < 200 || s >= 300, "must NOT be 2xx");
    }

    /**
     * The JWKS endpoint is the BFF's contribution to the OAuth2 trust
     * relationship with Keycloak — it must remain public and serve a valid
     * JWK Set even after the SCG migration trimmed the {@code permitAll}
     * matcher list.
     */
    @Test
    @WithAnonymousUser
    void jwksEndpointReturns200WithKeySet() throws Exception {
        MvcResult result = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andReturn();

        // Body sanity: at least one key, with a kid (so Keycloak can pin it).
        assertThat(result.getResponse().getContentAsString())
                .contains("\"kid\"")
                .contains("\"use\":\"sig\"");
    }

    /**
     * The actuator health endpoint must remain reachable for Container Apps
     * liveness / readiness probes.
     */
    @Test
    @WithAnonymousUser
    void actuatorHealthReturns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    /**
     * {@code /api/me} is BFF-local (not proxied via SCG) and must continue
     * to respond. With a non-OIDC authenticated principal (here a mock user)
     * the controller's {@code instanceof OidcUser} check is false → returns
     * the dev-fallback payload defined by {@code AuthController.devUser()}.
     * {@code @WithMockUser} bypasses the security filter chain so the
     * controller is reached.
     */
    @Test
    @WithMockUser
    void apiMeReturnsDevFallbackForNonOidcPrincipal() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.id").value("99999999-9999-9999-9999-999999999999"))
                .andExpect(jsonPath("$.username").value("dev-user"))
                .andExpect(jsonPath("$.email").value("dev@example.com"));
    }

    /**
     * {@code /api/me} requires authentication on the {@code /api/**} matcher
     * tree — anonymous calls must surface as 401 with the RFC 9457 envelope
     * from {@code RestAuthenticationEntryPoint}, NOT as a dev-fallback 200.
     */
    @Test
    @WithAnonymousUser
    void apiMeReturns401ForAnonymous() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }
}
