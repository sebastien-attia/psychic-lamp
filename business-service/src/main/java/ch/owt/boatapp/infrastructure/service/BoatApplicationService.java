package ch.owt.boatapp.infrastructure.service;

import ch.owt.boatapp.domain.exception.ValidationFailureException;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.model.ServiceResponse;
import ch.owt.boatapp.domain.port.in.CreateBoatCommand;
import ch.owt.boatapp.domain.port.in.DeleteBoatCommand;
import ch.owt.boatapp.domain.port.in.GetBoatQuery;
import ch.owt.boatapp.domain.port.in.ListBoatsQuery;
import ch.owt.boatapp.domain.port.in.ManageBoatsUseCase;
import ch.owt.boatapp.domain.port.in.UpdateBoatCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bridge between the inbound REST adapter and the pure-Java domain
 * {@code ManageBoatsUseCase}.
 *
 * <p>This is the only layer that owns transactions: each public method runs
 * inside a {@code @Transactional} boundary, so audit-row appends and boat
 * mutations commit (or roll back) together. The domain itself is
 * transaction-unaware.
 *
 * <p>Mutation methods inspect the domain's {@link ServiceResponse} envelope:
 * if it carries any {@link ch.owt.boatapp.domain.model.validation.Severity#ERROR
 * ERROR}-severity messages they are translated into a thrown
 * {@link ValidationFailureException}; otherwise the envelope is returned
 * as-is so any advisory ({@code WARNING} / {@code INFO}) messages reach
 * the controller and can be rendered on the 2xx response body. The
 * exception is a {@link RuntimeException}, so Spring's default rollback
 * behaviour rolls back the audit row alongside the rejected boat write.
 *
 * <p>Domain exceptions ({@code BoatNotFoundException},
 * {@code ConcurrentModificationException}) propagate as-is to the
 * {@code GlobalExceptionHandler}.
 */
@Service
@Transactional
public class BoatApplicationService {

    private static final Logger log = LoggerFactory.getLogger(BoatApplicationService.class);

    private final ManageBoatsUseCase manageBoatsUseCase;

    /**
     * @param manageBoatsUseCase the pure-Java boat use-case (wired via
     *                           {@code BeanConfig}; never {@code null})
     */
    public BoatApplicationService(ManageBoatsUseCase manageBoatsUseCase) {
        this.manageBoatsUseCase = manageBoatsUseCase;
    }

    /**
     * List boats matching the paging / sorting / search criteria. Read-only
     * — runs inside a read-only-friendly transaction.
     *
     * @param query the paging, sorting and search inputs
     * @return a domain page envelope holding the matching boats
     */
    @Transactional(readOnly = true)
    public PageResult<Boat> listBoats(ListBoatsQuery query) {
        return manageBoatsUseCase.listBoats(query);
    }

    /**
     * Fetch a single boat by identifier.
     *
     * @param query the query carrying the boat identifier
     * @return the matching boat
     * @throws ch.owt.boatapp.domain.exception.BoatNotFoundException if no boat with that identifier exists
     */
    @Transactional(readOnly = true)
    public Boat getBoat(GetBoatQuery query) {
        return manageBoatsUseCase.getBoat(query);
    }

    /**
     * Create a new boat. ERROR-severity validation findings from the domain
     * become {@link ValidationFailureException} so the {@code @Transactional}
     * boundary rolls back any partial work and the global handler maps the
     * exception to HTTP 422. Advisory ({@code WARNING} / {@code INFO})
     * findings are returned as part of the success envelope so the
     * controller can surface them in the 2xx response body.
     *
     * @param command the new boat's name, description and performer
     * @return the success envelope carrying the persisted boat plus any
     *         advisory messages emitted by the validators
     * @throws ValidationFailureException if the domain emitted at least one
     *         {@code ERROR}-severity message
     */
    public ServiceResponse<Boat> createBoat(CreateBoatCommand command) {
        ServiceResponse<Boat> response = manageBoatsUseCase.createBoat(command);
        if (response.hasErrors()) {
            log.warn("createBoat rejected: {} message(s) for performedBy={}",
                    response.messages().size(), command.performedBy().value());
            throw new ValidationFailureException(response.messages());
        }
        Boat created = response.data();
        log.info("createBoat ok: id={} performedBy={} advisory={}",
                created.id(), command.performedBy().value(), response.messages().size());
        return response;
    }

    /**
     * Update an existing boat. Same translation rules as
     * {@link #createBoat(CreateBoatCommand)}; in addition,
     * {@code BoatNotFoundException} (→ 404) and
     * {@code ConcurrentModificationException} (→ 409) propagate from the
     * domain.
     *
     * @param command the boat identifier, new fields, expected version and performer
     * @return the success envelope carrying the updated boat plus any
     *         advisory messages emitted by the validators
     * @throws ValidationFailureException if the domain emitted at least one
     *         {@code ERROR}-severity message
     */
    public ServiceResponse<Boat> updateBoat(UpdateBoatCommand command) {
        ServiceResponse<Boat> response = manageBoatsUseCase.updateBoat(command);
        if (response.hasErrors()) {
            log.warn("updateBoat rejected: {} message(s) for id={} performedBy={}",
                    response.messages().size(), command.id().value(), command.performedBy().value());
            throw new ValidationFailureException(response.messages());
        }
        Boat updated = response.data();
        log.info("updateBoat ok: id={} version={} performedBy={} advisory={}",
                updated.id(), updated.version(), command.performedBy().value(), response.messages().size());
        return response;
    }

    /**
     * Delete a boat. The audit row capturing the deleted state is written
     * inside the same transaction as the delete itself.
     *
     * @param command the boat identifier and the performer
     * @throws ch.owt.boatapp.domain.exception.BoatNotFoundException if no boat with that identifier exists
     */
    public void deleteBoat(DeleteBoatCommand command) {
        manageBoatsUseCase.deleteBoat(command);
        log.info("deleteBoat ok: id={} performedBy={}",
                command.id().value(), command.performedBy().value());
    }
}
