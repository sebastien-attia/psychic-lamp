package ch.owt.boatapp.domain.port.in;

import ch.owt.boatapp.domain.exception.BoatNotFoundException;
import ch.owt.boatapp.domain.model.Boat;
import ch.owt.boatapp.domain.model.PageResult;
import ch.owt.boatapp.domain.model.ServiceResponse;

/**
 * Inbound port for boat lifecycle operations (list, get, create, update, delete).
 *
 * <p>Pure-Java interface — adapters depend on this interface, not on the
 * domain-service implementation. Mutations that may fail validation
 * ({@link #createBoat}, {@link #updateBoat}) return {@link ServiceResponse}
 * so the bridge layer can translate validation failures into HTTP 422 inside
 * a transaction; reads / deletes throw domain exceptions directly.
 */
public interface ManageBoatsUseCase {

    /**
     * List boats matching the paging / sorting / search criteria carried by
     * the query.
     *
     * @param query the paging, sorting and search inputs
     * @return a page envelope holding the matching boats and pagination metadata
     */
    PageResult<Boat> listBoats(ListBoatsQuery query);

    /**
     * Fetch a single boat by identifier.
     *
     * @param query the query carrying the boat identifier
     * @return the matching boat
     * @throws BoatNotFoundException if no boat with that identifier exists
     */
    Boat getBoat(GetBoatQuery query) throws BoatNotFoundException;

    /**
     * Create a new boat after running syntactic + semantic validators.
     *
     * @param command the new boat's name, description and performer
     * @return a success response carrying the persisted boat, or a failure
     *         response carrying validation messages
     */
    ServiceResponse<Boat> createBoat(CreateBoatCommand command);

    /**
     * Update an existing boat after running syntactic + semantic validators.
     *
     * @param command the boat identifier, new fields, expected version and performer
     * @return a success response carrying the updated boat, or a failure
     *         response carrying validation messages
     * @throws BoatNotFoundException if no boat with that identifier exists
     */
    ServiceResponse<Boat> updateBoat(UpdateBoatCommand command) throws BoatNotFoundException;

    /**
     * Delete a boat and append a {@link ch.owt.boatapp.domain.model.AuditAction#DELETED}
     * audit row holding its last-known state.
     *
     * @param command the boat identifier and the performer
     * @throws BoatNotFoundException if no boat with that identifier exists
     */
    void deleteBoat(DeleteBoatCommand command) throws BoatNotFoundException;
}
