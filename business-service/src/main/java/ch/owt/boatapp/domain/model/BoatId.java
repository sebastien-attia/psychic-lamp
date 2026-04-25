package ch.owt.boatapp.domain.model;

import java.util.UUID;

/**
 * Strongly-typed wrapper around a boat's {@link UUID} identifier.
 *
 * <p>Provides compile-time type safety across port boundaries (a method
 * cannot accidentally take a {@link UserId} where a {@link BoatId} was
 * meant) and rejects obvious invariant violations at construction time —
 * even when the caller bypassed the REST adapter's Bean Validation gate.
 */
public record BoatId(UUID value) {

    /**
     * Compact constructor — rejects {@code null} and the nil UUID
     * ({@code 00000000-0000-0000-0000-000000000000}), which almost always
     * indicates an uninitialised value rather than a real identifier.
     *
     * @throws IllegalArgumentException if {@code value} is null or the nil UUID
     */
    public BoatId {
        if (value == null) {
            throw new IllegalArgumentException("BoatId.value must not be null");
        }
        if (value.equals(new UUID(0L, 0L))) {
            throw new IllegalArgumentException("BoatId.value must not be the nil UUID");
        }
    }
}
