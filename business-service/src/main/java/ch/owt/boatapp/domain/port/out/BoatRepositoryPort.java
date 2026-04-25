package ch.owt.boatapp.domain.port.out;

/**
 * Outbound port the domain uses to load and persist {@link ch.owt.boatapp.domain.model.Boat}
 * aggregates.
 *
 * <p>Implemented in {@code adapter.out.persistence} by a Spring Data JPA
 * adapter; the domain stays unaware of JPA. Method signatures are added in
 * step 02a3.
 */
public interface BoatRepositoryPort {
}
