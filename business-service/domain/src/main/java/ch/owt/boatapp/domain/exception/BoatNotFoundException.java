package ch.owt.boatapp.domain.exception;

import ch.owt.boatapp.domain.model.BoatId;

/**
 * Thrown by use-cases when a requested boat does not exist.
 *
 * <p>Pure-Java unchecked exception — no Spring, no Jakarta. The web adapter's
 * exception handler (added in step 02a3) maps this to HTTP 404 with the
 * problem type {@code https://boatapp.owt.ch/problems/not-found} per the
 * RFC 9457 registry in {@code .claude/rules/validation-and-errors.md}.
 */
public class BoatNotFoundException extends RuntimeException {

    private final BoatId boatId;

    /**
     * @param boatId identifier of the boat that was not found (never {@code null})
     */
    public BoatNotFoundException(BoatId boatId) {
        super("Boat not found: " + boatId.value());
        this.boatId = boatId;
    }

    /** @return the identifier of the boat that was not found */
    public BoatId getBoatId() {
        return boatId;
    }
}
