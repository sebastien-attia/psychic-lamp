package ch.owt.boatapp.bff.adapter.in.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards HTML5-history SPA routes to {@code /index.html} so the Vue router
 * picks them up on a hard navigation (e.g. opening
 * {@code https://app/boats/abc-123} in a new tab) rather than producing a
 * 404 from Spring's static-resource handler.
 *
 * <p>Mappings catch dotless paths only — anything containing a dot
 * (e.g. {@code /index.html}, {@code /assets/app.123.css},
 * {@code /favicon.ico}) is left to the static-resource handler. API routes
 * ({@code /api/**}, {@code /actuator/**}, {@code /.well-known/jwks.json},
 * {@code /swagger-ui/**}, {@code /v3/api-docs/**},
 * {@code /oauth2/**}, {@code /login/**}, {@code /logout}) are mapped by
 * other controllers / Spring Security filters and take precedence by
 * specificity, so this controller never intercepts them.
 *
 * <p>Until the Vue build is dropped into {@code src/main/resources/static/},
 * forwarded requests still 404 — same as today.
 */
@Controller
public class SpaForwardController {

    /**
     * Forward dotless paths up to three segments deep to {@code /index.html}.
     *
     * <p>Three levels are enough for the canonical SPA route shapes
     * ({@code /boats}, {@code /boats/{id}}, {@code /boats/{id}/edit});
     * deeper routes can be added as the SPA grows.
     *
     * @return Spring view name that triggers a server-side forward
     */
    @GetMapping(value = {
            "/",
            "/{p1:[^.]*}",
            "/{p1:[^.]*}/{p2:[^.]*}",
            "/{p1:[^.]*}/{p2:[^.]*}/{p3:[^.]*}"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
