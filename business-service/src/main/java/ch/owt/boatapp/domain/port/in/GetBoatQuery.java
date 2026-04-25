package ch.owt.boatapp.domain.port.in;

import ch.owt.boatapp.domain.model.BoatId;

/**
 * Query record carrying the identifier of a single boat to fetch.
 *
 * <p>Pure-Java record. The use-case throws
 * {@link ch.owt.boatapp.domain.exception.BoatNotFoundException} if no boat
 * matches.
 *
 * @param id identifier of the boat to fetch
 */
public record GetBoatQuery(BoatId id) {
}
