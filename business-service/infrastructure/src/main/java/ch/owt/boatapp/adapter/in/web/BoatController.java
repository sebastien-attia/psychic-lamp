package ch.owt.boatapp.adapter.in.web;

import ch.owt.boatapp.adapter.in.web.dto.generated.BoatCreateRequest;
import ch.owt.boatapp.adapter.in.web.dto.generated.BoatResponse;
import ch.owt.boatapp.adapter.in.web.dto.generated.BoatUpdateRequest;
import ch.owt.boatapp.adapter.in.web.dto.generated.PageBoatResponse;
import ch.owt.boatapp.adapter.in.web.generated.BusinessServiceApi;
import ch.owt.boatapp.adapter.in.web.mapper.BoatCommandMapper;
import ch.owt.boatapp.adapter.in.web.mapper.BoatWebMapper;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.model.ServiceResponse;
import ch.owt.boatapp.infrastructure.security.SecurityHelper;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

/**
 * Business Service inbound REST adapter for {@code /api/v1/boats/**}.
 *
 * <p>Stateless — the {@code Bearer} JWT is validated upstream by Spring
 * Security; this class never opens a transaction (the
 * {@link BoatTransactionalGateway} owns {@code @Transactional}). The gateway
 * lives next to the controller so the {@code application} Maven module can
 * stay pure Java with zero Spring/Jakarta deps.
 *
 * <p>Implements the OpenAPI-generated {@link BusinessServiceApi} interface,
 * so request mappings, path/query/header bindings and {@code @Valid}
 * annotations on request bodies are inherited from the spec — any drift
 * between {@code contracts/openapi.yaml} and the controller is a compile
 * error. {@link Validated} is repeated on the implementation to keep
 * method-level constraint validation (path / query parameters) firing even
 * if a future codegen change drops the annotation from the interface.
 */
@RestController
@Validated
public class BoatController implements BusinessServiceApi {

    private final BoatTransactionalGateway boatTransactionalGateway;
    private final SecurityHelper securityHelper;
    private final MessageSource messageSource;

    /**
     * @param boatTransactionalGateway transactional gateway to the boat use-case
     * @param securityHelper           resolves the current authenticated user
     * @param messageSource            i18n source for advisory message texts on 2xx envelopes
     */
    public BoatController(BoatTransactionalGateway boatTransactionalGateway,
                          SecurityHelper securityHelper,
                          MessageSource messageSource) {
        this.boatTransactionalGateway = boatTransactionalGateway;
        this.securityHelper = securityHelper;
        this.messageSource = messageSource;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns 200 OK with a paginated wrapper. Page/size/sort defaults are
     * applied by the generated interface ({@code page=0, size=10,
     * sort=createdAt,desc}); range constraints ({@code page >= 0},
     * {@code 1 <= size <= 100}) fire via Bean Validation → 400.
     */
    @Override
    public ResponseEntity<PageBoatResponse> listBoats(Integer page, Integer size, String sort, String search) {
        PageResult<Boat> result = boatTransactionalGateway.listBoats(
                BoatCommandMapper.toListQuery(page, size, sort, search));
        return ResponseEntity.ok(BoatWebMapper.toPage(result));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns 200 OK with the boat and an {@code ETag} header carrying the
     * current version (bare integer per contract).
     */
    @Override
    public ResponseEntity<BoatResponse> getBoat(UUID id) {
        Boat boat = boatTransactionalGateway.getBoat(BoatCommandMapper.toGetQuery(id));
        return ResponseEntity.ok()
                .eTag(String.valueOf(boat.version()))
                .body(BoatWebMapper.toResponse(boat));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns 201 Created with a {@code Location} header pointing at the
     * new resource and an {@code ETag} header carrying the initial version.
     * The body carries the persisted boat plus any non-blocking advisories
     * (`WARNING` / `INFO`) the domain emitted, surfaced as the optional
     * {@code messages} field on {@link BoatResponse}.
     */
    @Override
    public ResponseEntity<BoatResponse> createBoat(BoatCreateRequest boatCreateRequest) {
        UUID userId = securityHelper.getCurrentAppUserId();
        ServiceResponse<Boat> result = boatTransactionalGateway.createBoat(
                BoatCommandMapper.toCreateCommand(boatCreateRequest, userId));
        Boat boat = result.data();
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(boat.id())
                .toUri();
        Locale locale = LocaleContextHolder.getLocale();
        return ResponseEntity.created(location)
                .eTag(String.valueOf(boat.version()))
                .body(BoatWebMapper.toResponse(boat, result.messages(), messageSource, locale));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns 200 OK with the updated boat and a refreshed {@code ETag}
     * header. The body carries the boat plus any non-blocking advisories on
     * the optional {@code messages} field. The {@code If-Match} header is
     * parsed as a bare {@code Long} (per {@code contracts/openapi.yaml}); a
     * malformed value surfaces as a 400 via the global handler. A version
     * mismatch surfaces as 409; a missing {@code If-Match} as 428.
     */
    @Override
    public ResponseEntity<BoatResponse> updateBoat(UUID id, String ifMatch, BoatUpdateRequest boatUpdateRequest) {
        Long expectedVersion = parseIfMatch(ifMatch);
        UUID userId = securityHelper.getCurrentAppUserId();
        ServiceResponse<Boat> result = boatTransactionalGateway.updateBoat(
                BoatCommandMapper.toUpdateCommand(id, expectedVersion, boatUpdateRequest, userId));
        Boat boat = result.data();
        Locale locale = LocaleContextHolder.getLocale();
        return ResponseEntity.ok()
                .eTag(String.valueOf(boat.version()))
                .body(BoatWebMapper.toResponse(boat, result.messages(), messageSource, locale));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns 204 No Content. The audit row capturing the deleted state
     * is appended inside the same transaction as the delete itself.
     */
    @Override
    public ResponseEntity<Void> deleteBoat(UUID id) {
        UUID userId = securityHelper.getCurrentAppUserId();
        boatTransactionalGateway.deleteBoat(BoatCommandMapper.toDeleteCommand(id, userId));
        return ResponseEntity.noContent().build();
    }

    /**
     * Parse the {@code If-Match} header as a bare integer version. Strips a
     * surrounding pair of double quotes if present — defensive against
     * clients that send the RFC 7232 quoted entity-tag form
     * ({@code "3"}) instead of the bare-integer form mandated by the contract.
     *
     * @throws MalformedIfMatchException if the header value is not a valid
     *         {@code Long} after trimming and unquoting; the global handler
     *         maps this to HTTP 400 with code {@code field.format.invalid}
     */
    private static Long parseIfMatch(String ifMatch) {
        String trimmed = ifMatch.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '"'
                && trimmed.charAt(trimmed.length() - 1) == '"') {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException ex) {
            throw new MalformedIfMatchException(ifMatch, ex);
        }
    }
}
