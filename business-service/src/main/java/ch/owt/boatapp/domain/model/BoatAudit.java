package ch.owt.boatapp.domain.model;

/**
 * Pure-Java domain model for a boat-audit record.
 *
 * <p>INSERT-ONLY: every domain mutation on a {@link Boat} appends a row
 * referencing the acting {@link AppUser} (FK on the persistence side).
 * Lives in {@code domain.model} — no Spring, no Jakarta. Fields are added
 * in step 02a3.
 */
public class BoatAudit {
}
