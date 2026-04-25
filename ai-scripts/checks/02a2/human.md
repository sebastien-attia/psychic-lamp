# Human checks — Phase 02a2 Backend Domain
□ Skim the domain model types (Boat, AppUser, BoatAudit) — they are Java records, use only `java.*`, domain enums, and ValueObjects. No framework leakage. No setters anywhere on domain.*.
□ The JPA entity classes mirror the domain record but are separate, mutable types — mapping is done by hand-written `@Component` mappers in `adapter/out/persistence/mapper/`. No MapStruct, no annotation processor.
□ Updates rebuild a fresh record (e.g. `BoatDomainService.updateBoat` constructs `new Boat(...)`) — no setter calls on domain types.
□ Liquibase changelogs are INSERT-ONLY for boat_audit (no UPDATE/DELETE).
□ SPRING_SESSION migration lives under `bff/src/main/resources/db/changelog/` and is applied against the `bff_session` database (owned by role `bff`). It MUST NOT live under business-service.
