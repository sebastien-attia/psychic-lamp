package ch.owt.boatapp.domain.model;

/**
 * The kind of mutation recorded in a {@link BoatAudit} row.
 *
 * <p>Pure-Java enum: persisted as {@code STRING} so renames are an explicit
 * migration, and added values do not silently shift ordinals.
 */
public enum AuditAction {

    /** A boat was created. */
    CREATED,

    /** A boat's mutable fields were updated. */
    UPDATED,

    /** A boat was deleted; the audit row holds the last-known state. */
    DELETED
}
