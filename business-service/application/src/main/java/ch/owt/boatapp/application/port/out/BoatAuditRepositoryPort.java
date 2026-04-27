package ch.owt.boatapp.application.port.out;

import ch.owt.boatapp.domain.model.BoatAudit;

/**
 * Outbound port the domain uses to append {@link BoatAudit} rows.
 *
 * <p>INSERT-ONLY — the implementation must reject updates / deletes. The
 * port deliberately exposes only {@link #save(BoatAudit)} so the absence of
 * an update / delete API is enforced at the type level.
 */
public interface BoatAuditRepositoryPort {

    /**
     * Append a new audit row.
     *
     * <p>Preconditions the caller must satisfy:
     * <ul>
     *   <li>An {@code AppUser} row whose id matches
     *       {@link BoatAudit#getPerformedByUserId()} must already exist —
     *       the adapter resolves it as a JPA reference for the FK
     *       {@code boat_audit.performed_by_user_id → app_user(id)}, and the
     *       INSERT will fail at flush time otherwise. The web adapter (added
     *       in step 02a4) syncs the user from the JWT claims before invoking
     *       any boat use-case, satisfying this precondition.</li>
     *   <li>The call must run inside an open persistence context (i.e. a
     *       {@code @Transactional} boundary). The adapter uses
     *       {@code getReferenceById} on {@code AppUser} which returns a lazy
     *       proxy; dereferencing it outside a transaction throws.</li>
     * </ul>
     *
     * @param audit the audit record to append (must have {@code id == null})
     * @return the persisted audit row, including its database-generated id
     */
    BoatAudit save(BoatAudit audit);
}
