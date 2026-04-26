package ch.owt.boatapp.adapter.in.web.mapper;

import ch.owt.boatapp.adapter.in.web.dto.generated.BoatCreateRequest;
import ch.owt.boatapp.adapter.in.web.dto.generated.BoatUpdateRequest;
import ch.owt.boatapp.domain.model.BoatId;
import ch.owt.boatapp.domain.model.UserId;
import ch.owt.boatapp.application.port.in.CreateBoatCommand;
import ch.owt.boatapp.application.port.in.DeleteBoatCommand;
import ch.owt.boatapp.application.port.in.GetBoatQuery;
import ch.owt.boatapp.application.port.in.ListBoatsQuery;
import ch.owt.boatapp.application.port.in.UpdateBoatCommand;

import java.util.Locale;
import java.util.UUID;

/**
 * Hand-written DTO → domain command/query mapper.
 *
 * <p>The project deliberately uses no MapStruct (see
 * {@code .claude/rules/business-service-java.md}) — mapping logic is small,
 * readable, and stays close to the controller. Wrapping raw {@code UUID}
 * values into {@link BoatId} / {@link UserId} value objects here ensures
 * their compact-constructor invariants fire even when the caller bypasses
 * the REST adapter's Bean Validation gate.
 */
public final class BoatCommandMapper {

    /**
     * Map a {@link BoatCreateRequest} body and the current user id into a
     * domain {@link CreateBoatCommand}.
     *
     * @param dto           the inbound create request (already Bean-validated)
     * @param currentUserId the authenticated {@code AppUser.id} (resolved by
     *                      {@code SecurityHelper.getCurrentAppUserId()})
     * @return the domain command ready to hand to the use-case
     */
    public static CreateBoatCommand toCreateCommand(BoatCreateRequest dto, UUID currentUserId) {
        return new CreateBoatCommand(dto.getName(), dto.getDescription(), new UserId(currentUserId));
    }

    /**
     * Map a {@link BoatUpdateRequest} body, the path id, the {@code If-Match}
     * header and the current user id into a domain {@link UpdateBoatCommand}.
     *
     * @param pathId        the boat id from the request path
     * @param ifMatch       the boat version supplied via the {@code If-Match}
     *                      request header (bare integer per contract)
     * @param dto           the inbound update request (already Bean-validated)
     * @param currentUserId the authenticated {@code AppUser.id}
     * @return the domain command ready to hand to the use-case
     */
    public static UpdateBoatCommand toUpdateCommand(UUID pathId, Long ifMatch,
                                                    BoatUpdateRequest dto, UUID currentUserId) {
        return new UpdateBoatCommand(new BoatId(pathId), dto.getName(), dto.getDescription(),
                ifMatch, new UserId(currentUserId));
    }

    /**
     * Build a {@link GetBoatQuery} from the path id.
     *
     * @param pathId the boat id from the request path
     * @return the domain query
     */
    public static GetBoatQuery toGetQuery(UUID pathId) {
        return new GetBoatQuery(new BoatId(pathId));
    }

    /**
     * Build a {@link DeleteBoatCommand} from the path id and current user id.
     *
     * @param pathId        the boat id from the request path
     * @param currentUserId the authenticated {@code AppUser.id}
     * @return the domain command
     */
    public static DeleteBoatCommand toDeleteCommand(UUID pathId, UUID currentUserId) {
        return new DeleteBoatCommand(new BoatId(pathId), new UserId(currentUserId));
    }

    /**
     * Build a {@link ListBoatsQuery} from the controller's request params.
     *
     * <p>Parses the Spring-Data {@code field,dir} sort form (e.g.
     * {@code "createdAt,desc"}); falls back to {@code createdAt}/{@code desc}
     * when {@code sort} is {@code null}, blank or malformed. Whitespace
     * around the separator is tolerated. The {@code search} value is passed
     * through as-is — the domain treats {@code null}/blank as "no filter".
     *
     * @param page   zero-based page index (already validated by Bean
     *               Validation: {@code page >= 0})
     * @param size   page size (already validated: {@code 1 <= size <= 100})
     * @param sort   sort directive in {@code field,dir} form; may be
     *               {@code null} or blank
     * @param search free-text filter; may be {@code null} or blank
     * @return the domain query
     */
    public static ListBoatsQuery toListQuery(int page, int size, String sort, String search) {
        String sortBy = "createdAt";
        String sortDir = "desc";
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",", 2);
            String parsedField = parts[0].trim();
            if (!parsedField.isEmpty()) {
                sortBy = parsedField;
            }
            if (parts.length == 2) {
                // Locale.ROOT keeps the lowercase deterministic across JVM
                // locales (Turkish 'I' would otherwise become 'ı', not 'i').
                String parsedDir = parts[1].trim().toLowerCase(Locale.ROOT);
                if ("asc".equals(parsedDir) || "desc".equals(parsedDir)) {
                    sortDir = parsedDir;
                }
            }
        }
        return new ListBoatsQuery(page, size, sortBy, sortDir, search);
    }

    private BoatCommandMapper() {
        // utility class — not instantiable
    }
}
