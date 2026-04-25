package ch.owt.boatapp.domain.model;

import java.util.UUID;

/**
 * Strongly-typed wrapper around a user's {@link UUID} identifier.
 *
 * <p>Mirrors {@link BoatId} — provides compile-time type safety across port
 * boundaries and rejects obvious invariant violations at construction time.
 */
public record UserId(UUID value) {

    /**
     * Compact constructor — rejects {@code null} and the nil UUID
     * ({@code 00000000-0000-0000-0000-000000000000}).
     *
     * @throws IllegalArgumentException if {@code value} is null or the nil UUID
     */
    public UserId {
        if (value == null) {
            throw new IllegalArgumentException("UserId.value must not be null");
        }
        if (value.equals(new UUID(0L, 0L))) {
            throw new IllegalArgumentException("UserId.value must not be the nil UUID");
        }
    }
}
