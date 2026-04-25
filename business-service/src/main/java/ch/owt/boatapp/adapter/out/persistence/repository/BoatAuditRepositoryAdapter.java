package ch.owt.boatapp.adapter.out.persistence.repository;

import ch.owt.boatapp.adapter.out.persistence.entity.AppUserJpaEntity;
import ch.owt.boatapp.adapter.out.persistence.entity.BoatAuditJpaEntity;
import ch.owt.boatapp.adapter.out.persistence.mapper.BoatAuditPersistenceMapper;
import ch.owt.boatapp.domain.model.BoatAudit;
import ch.owt.boatapp.domain.port.out.BoatAuditRepositoryPort;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA implementation of {@link BoatAuditRepositoryPort}.
 *
 * <p>INSERT-ONLY by contract: the port exposes only {@link #save(BoatAudit)},
 * and this adapter never issues an UPDATE or DELETE. Resolves the
 * {@code performedBy} reference via
 * {@link AppUserJpaRepository#getReferenceById(Object)} so a lazy proxy is
 * attached without a SELECT round-trip.
 */
@Repository
public class BoatAuditRepositoryAdapter implements BoatAuditRepositoryPort {

    private final BoatAuditJpaRepository auditJpaRepository;
    private final AppUserJpaRepository appUserJpaRepository;
    private final BoatAuditPersistenceMapper mapper;

    /**
     * @param auditJpaRepository   the underlying Spring Data JPA repository for audit rows
     * @param appUserJpaRepository repository used to resolve a lazy
     *                             {@link AppUserJpaEntity} reference
     * @param mapper               MapStruct mapper for entity ↔ domain conversion
     */
    public BoatAuditRepositoryAdapter(BoatAuditJpaRepository auditJpaRepository,
                                      AppUserJpaRepository appUserJpaRepository,
                                      BoatAuditPersistenceMapper mapper) {
        this.auditJpaRepository = auditJpaRepository;
        this.appUserJpaRepository = appUserJpaRepository;
        this.mapper = mapper;
    }

    @Override
    public BoatAudit save(BoatAudit audit) {
        AppUserJpaEntity performedByRef = appUserJpaRepository.getReferenceById(audit.performedByUserId());
        BoatAuditJpaEntity entity = mapper.toJpaEntity(audit, performedByRef);
        BoatAuditJpaEntity persisted = auditJpaRepository.save(entity);
        return mapper.toDomain(persisted);
    }
}
