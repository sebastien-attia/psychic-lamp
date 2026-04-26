package ch.owt.boatapp.bff.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.server.mvc.config.FilterProperties;
import org.springframework.cloud.gateway.server.mvc.config.GatewayMvcProperties;
import org.springframework.cloud.gateway.server.mvc.config.PredicateProperties;
import org.springframework.cloud.gateway.server.mvc.config.RouteProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static-config regression: assert the Spring Cloud Gateway route table is
 * loaded and shaped exactly as the SCG migration intended. The test is
 * narrow on purpose — it does NOT route an actual HTTP request (that is
 * covered by {@link TokenRelayIntegrationTest}); it inspects the parsed
 * {@link GatewayMvcProperties} bean so a YAML typo or accidental deletion
 * of {@code application-routes.yml} fails the build before any traffic is
 * served.
 *
 * <p>Inherits from {@link BffIntegrationTestBase}, which sets the
 * {@code business-service.url} to a WireMock dynamic port and provides an
 * ephemeral signing key so the BFF context loads. The {@code spring.config.import}
 * for {@code application-routes.yml} is configured at the base-class level
 * so every BFF integration test sees the routes.
 */
class ScgRouteRegistrationTest extends BffIntegrationTestBase {

    @Autowired
    private GatewayMvcProperties gatewayMvcProperties;

    /**
     * The single route we care about exists, points at
     * {@code business-service.url}, and is configured with the
     * {@code Path=/api/v1/boats/{*subpath}} predicate.
     */
    @Test
    void boatsApiRouteIsRegistered() {
        RouteProperties route = findRoute("boats-api");

        // The base class binds business-service.url to "http://localhost:<wiremock>" —
        // exact host/port don't matter; the URI must just be set.
        assertThat(route.getUri()).isNotNull();
        assertThat(route.getUri().getScheme()).isEqualTo("http");

        List<PredicateProperties> predicates = route.getPredicates();
        assertThat(predicates).hasSize(1);
        PredicateProperties path = predicates.get(0);
        assertThat(path.getName()).isEqualTo("Path");
        // Shorthand form "Path=/api/v1/boats/{*subpath}" lands the value
        // under the implicit "_genkey_0" arg (Spring Cloud's convention for
        // positional shorthand args).
        assertThat(path.getArgs().values()).contains("/api/v1/boats/{*subpath}");
    }

    /**
     * The TokenRelay filter wires the route to the
     * {@code OAuth2AuthorizedClientManager} bean for the {@code keycloak}
     * client registration. Without this filter the BFF would proxy boat
     * requests without forwarding the user's access token — a 401
     * cliff at the upstream.
     */
    @Test
    void boatsApiRouteUsesTokenRelayWithKeycloak() {
        RouteProperties route = findRoute("boats-api");

        List<FilterProperties> filters = route.getFilters();
        assertThat(filters).hasSize(1);
        FilterProperties tokenRelay = filters.get(0);
        assertThat(tokenRelay.getName()).isEqualTo("TokenRelay");
        assertThat(tokenRelay.getArgs().values()).contains("keycloak");
    }

    private RouteProperties findRoute(String id) {
        return gatewayMvcProperties.getRoutes().stream()
                .filter(r -> id.equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Route '" + id + "' not registered. Found: "
                                + gatewayMvcProperties.getRoutes().stream()
                                        .map(RouteProperties::getId)
                                        .toList()));
    }
}
