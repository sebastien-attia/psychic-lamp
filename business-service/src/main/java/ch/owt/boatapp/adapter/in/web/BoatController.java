package ch.owt.boatapp.adapter.in.web;

import org.springframework.web.bind.annotation.RestController;

/**
 * Business Service inbound REST adapter for {@code /api/v1/boats/**}.
 *
 * <p>Stateless: validates the {@code Bearer} JWT, no session, no CSRF.
 * Step 02a3 will make this class
 * {@code implements ch.owt.boatapp.adapter.in.web.generated.BusinessServiceApi}
 * (the OpenAPI-generated interface) so contract drift becomes a compile error.
 * The interface only exists after the first {@code mvnw generate-sources}, so
 * the {@code implements} clause is intentionally absent here.
 */
@RestController
public class BoatController {
}
