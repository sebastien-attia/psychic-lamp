package ch.owt.boatapp.bff.adapter.in.web;

import ch.owt.boatapp.bff.adapter.in.web.dto.generated.BoatCreateRequest;
import ch.owt.boatapp.bff.adapter.in.web.dto.generated.BoatResponse;
import ch.owt.boatapp.bff.adapter.in.web.dto.generated.BoatUpdateRequest;
import ch.owt.boatapp.bff.adapter.in.web.dto.generated.PageBoatResponse;
import ch.owt.boatapp.bff.infrastructure.service.BoatBffService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * BFF inbound REST adapter for {@code /api/v1/boats/**}.
 *
 * <p>Hand-written: it does NOT {@code implement}
 * {@code BusinessServiceClient}, which is an outbound {@code @HttpExchange}
 * interface. The controller carries inbound {@code @RequestMapping}-family
 * annotations and delegates each call to {@link BoatBffService}.
 *
 * <p>The BFF acts as a trust boundary at the browser edge: every request
 * body is annotated {@code @Valid} so Bean Validation runs locally and
 * malformed payloads never reach the upstream Business Service. The
 * generated DTO classes carry {@code @NotNull} / {@code @Size} / etc.
 * because the codegen plugin is configured with
 * {@code useBeanValidation=true}. Class-level {@link Validated} enables
 * validation of {@code @PathVariable} / {@code @RequestParam} constraints.
 *
 * <p>The controller forwards the upstream {@link ResponseEntity} verbatim:
 * status, body and headers ({@code Location}, {@code ETag},
 * {@code Content-Language}) round-trip transparently.
 *
 * <p>ArchUnit forbids importing {@code BusinessServiceClient} here —
 * controllers depend on {@code infrastructure.service} only.
 */
@RestController
@RequestMapping("/api/v1/boats")
@Validated
public class BoatController {

    private final BoatBffService boatBffService;

    /**
     * @param boatBffService the BFF orchestration service (the only allowed dependency)
     */
    public BoatController(BoatBffService boatBffService) {
        this.boatBffService = boatBffService;
    }

    /**
     * Forward {@code GET /api/v1/boats}. Page/size constraints fire here
     * locally → 400 when out of range.
     *
     * @param page   zero-based page index (default 0, must be ≥ 0)
     * @param size   page size (default 10, must be 1..100)
     * @param sort   sort directive in Spring-Data form (default {@code createdAt,desc})
     * @param search optional free-text filter on {@code name} and {@code description}
     * @return the upstream response unchanged
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PageBoatResponse> listBoats(
            @RequestParam(value = "page", required = false, defaultValue = "0") @Min(0) Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "10") @Min(1) @Max(100) Integer size,
            @RequestParam(value = "sort", required = false, defaultValue = "createdAt,desc") String sort,
            @RequestParam(value = "search", required = false) String search) {
        return boatBffService.listBoats(page, size, sort, search);
    }

    /**
     * Forward {@code GET /api/v1/boats/{id}}. The upstream {@code ETag}
     * header is propagated unchanged.
     *
     * @param id boat identifier
     * @return the upstream response unchanged
     */
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BoatResponse> getBoat(@PathVariable("id") UUID id) {
        return boatBffService.getBoat(id);
    }

    /**
     * Forward {@code POST /api/v1/boats}. The body is Bean-validated
     * locally; failures surface as 400 from the BFF without ever reaching
     * the upstream.
     *
     * @param boatCreateRequest the create payload
     * @return the upstream response unchanged (201 + {@code Location} + {@code ETag} on success)
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BoatResponse> createBoat(
            @Valid @RequestBody BoatCreateRequest boatCreateRequest) {
        return boatBffService.createBoat(boatCreateRequest);
    }

    /**
     * Forward {@code PUT /api/v1/boats/{id}}. The {@code If-Match} header
     * is required (missing → 428 from the BFF) and is forwarded as-is to
     * the upstream for the optimistic-lock check (mismatch → 409).
     *
     * @param id                boat identifier
     * @param ifMatch           the boat version supplied via the inbound {@code If-Match} header
     * @param boatUpdateRequest the update payload
     * @return the upstream response unchanged (200 + refreshed {@code ETag} on success)
     */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BoatResponse> updateBoat(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "If-Match") String ifMatch,
            @Valid @RequestBody BoatUpdateRequest boatUpdateRequest) {
        return boatBffService.updateBoat(id, ifMatch, boatUpdateRequest);
    }

    /**
     * Forward {@code DELETE /api/v1/boats/{id}}.
     *
     * @param id boat identifier
     * @return the upstream response unchanged (204 No Content on success)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBoat(@PathVariable("id") UUID id) {
        return boatBffService.deleteBoat(id);
    }
}
