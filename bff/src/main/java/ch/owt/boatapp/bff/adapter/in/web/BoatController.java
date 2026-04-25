package ch.owt.boatapp.bff.adapter.in.web;

import org.springframework.web.bind.annotation.RestController;

/**
 * BFF inbound REST adapter for {@code /api/v1/boats/**}.
 *
 * <p>Delegates to {@code BoatBffService}, which proxies the call to the
 * Business Service via the generated {@code BusinessServiceClient}. Stays
 * a thin pass-through — no domain logic, no transactions.
 *
 * <p>Endpoint bodies are added in step 02a3 once the request/response DTOs
 * are generated from {@code contracts/openapi.yaml}.
 */
@RestController
public class BoatController {
}
