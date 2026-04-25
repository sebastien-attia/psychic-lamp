package ch.owt.boatapp.domain.exception;

import ch.owt.boatapp.domain.model.BoatId;

/**
 * Thrown by {@code BoatDomainService.updateBoat} when the caller-supplied
 * {@code expectedVersion} does not match the current persisted version of
 * the boat — the optimistic-lock guard.
 *
 * <p>Pure-Java unchecked exception (deliberately distinct from
 * {@link java.util.ConcurrentModificationException} and from
 * {@code jakarta.persistence.OptimisticLockException}). The web adapter's
 * exception handler maps both this class and {@code OptimisticLockException}
 * to HTTP 409 with the problem type
 * {@code https://boatapp.owt.ch/problems/concurrency-conflict}; the JPA
 * exception remains a defense-in-depth guard at the persistence layer.
 */
public class ConcurrentModificationException extends RuntimeException {

    private final BoatId boatId;
    private final Long expectedVersion;
    private final Long actualVersion;

    /**
     * @param boatId          identifier of the boat whose update was rejected
     * @param expectedVersion version supplied by the caller (from the {@code If-Match} header)
     * @param actualVersion   current persisted version on the server
     */
    public ConcurrentModificationException(BoatId boatId, Long expectedVersion, Long actualVersion) {
        super("Concurrent modification on boat " + boatId.value()
                + ": expected version " + expectedVersion
                + " but current version is " + actualVersion);
        this.boatId = boatId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    /** @return identifier of the boat whose update was rejected */
    public BoatId getBoatId() {
        return boatId;
    }

    /** @return the version supplied by the caller (from {@code If-Match}) */
    public Long getExpectedVersion() {
        return expectedVersion;
    }

    /** @return the current persisted version on the server */
    public Long getActualVersion() {
        return actualVersion;
    }
}
