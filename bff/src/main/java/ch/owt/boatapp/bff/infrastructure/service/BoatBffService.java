package ch.owt.boatapp.bff.infrastructure.service;

import ch.owt.boatapp.bff.adapter.in.web.dto.generated.BoatCreateRequest;
import ch.owt.boatapp.bff.adapter.in.web.dto.generated.BoatResponse;
import ch.owt.boatapp.bff.adapter.in.web.dto.generated.BoatUpdateRequest;
import ch.owt.boatapp.bff.adapter.in.web.dto.generated.PageBoatResponse;
import ch.owt.boatapp.bff.adapter.out.client.generated.BusinessServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Thin orchestration layer between the BFF web adapter and the OpenAPI-generated
 * {@link BusinessServiceClient} HTTP-Interface client.
 *
 * <p>Has NO {@code @Transactional} annotation and NO domain logic — it only
 * forwards requests, with the OAuth2 {@code access_token} attached as a
 * {@code Bearer} header by the {@code RestClient} interceptor configured in
 * {@code BffConfig}. ArchUnit forbids {@code @Transactional} anywhere in
 * the BFF.
 *
 * <p>Every method returns the {@link ResponseEntity} produced by the
 * upstream call unchanged so that headers ({@code Location}, {@code ETag})
 * and the HTTP status code propagate to the browser verbatim. The BFF
 * controller then forwards that {@link ResponseEntity} to the client without
 * inspection.
 */
@Service
public class BoatBffService {

    private static final Logger log = LoggerFactory.getLogger(BoatBffService.class);

    private final BusinessServiceClient businessServiceClient;

    /**
     * @param businessServiceClient the OpenAPI-generated {@code @HttpExchange}
     *                              client wired by {@code BffConfig}
     */
    public BoatBffService(BusinessServiceClient businessServiceClient) {
        this.businessServiceClient = businessServiceClient;
    }

    /**
     * Forward {@code GET /api/v1/boats} to the upstream Business Service.
     *
     * @param page   zero-based page index
     * @param size   page size (1..100)
     * @param sort   sort directive in Spring Data {@code field,dir} form
     * @param search free-text filter; {@code null} means no filter
     * @return the upstream {@link ResponseEntity} unchanged
     */
    public ResponseEntity<PageBoatResponse> listBoats(Integer page, Integer size, String sort, String search) {
        log.info("BFF→BS listBoats page={} size={} sort={} search={}", page, size, sort, search);
        return businessServiceClient.listBoats(page, size, sort, search);
    }

    /**
     * Forward {@code GET /api/v1/boats/{id}} to the upstream Business Service.
     *
     * @param id boat identifier
     * @return the upstream {@link ResponseEntity} (carrying the {@code ETag} header)
     */
    public ResponseEntity<BoatResponse> getBoat(UUID id) {
        log.info("BFF→BS getBoat id={}", id);
        return businessServiceClient.getBoat(id);
    }

    /**
     * Forward {@code POST /api/v1/boats} to the upstream Business Service.
     *
     * @param request the create payload (already Bean-validated by the BFF controller)
     * @return the upstream {@link ResponseEntity} (carrying {@code Location} and {@code ETag})
     */
    public ResponseEntity<BoatResponse> createBoat(BoatCreateRequest request) {
        // Log the name length, never the value: even though "boat name" is not a
        // credential, the project's logging rule forbids INFO-level logging of
        // user-controlled free-text fields.
        int nameLen = request.getName() != null ? request.getName().length() : 0;
        log.info("BFF→BS createBoat nameLen={}", nameLen);
        return businessServiceClient.createBoat(request);
    }

    /**
     * Forward {@code PUT /api/v1/boats/{id}} to the upstream Business Service,
     * carrying the client-supplied {@code If-Match} header for optimistic locking.
     *
     * @param id      boat identifier
     * @param ifMatch the boat version supplied via the inbound {@code If-Match} header
     * @param request the update payload (already Bean-validated by the BFF controller)
     * @return the upstream {@link ResponseEntity} (carrying the refreshed {@code ETag})
     */
    public ResponseEntity<BoatResponse> updateBoat(UUID id, String ifMatch, BoatUpdateRequest request) {
        log.info("BFF→BS updateBoat id={} ifMatch={}", id, ifMatch);
        return businessServiceClient.updateBoat(id, ifMatch, request);
    }

    /**
     * Forward {@code DELETE /api/v1/boats/{id}} to the upstream Business Service.
     *
     * @param id boat identifier
     * @return the upstream {@link ResponseEntity} (typically 204 No Content)
     */
    public ResponseEntity<Void> deleteBoat(UUID id) {
        log.info("BFF→BS deleteBoat id={}", id);
        return businessServiceClient.deleteBoat(id);
    }
}
