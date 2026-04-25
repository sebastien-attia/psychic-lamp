# Human checks — Phase 02a2 Backend Domain
□ Skim the domain model types (Boat, AppUser, BoatAudit) — they use only `java.*`, domain enums, and ValueObjects. No framework leakage.
□ The JPA entity classes mirror the domain model but are separate types (mapped via MapStruct or manual mapping), not shared.
□ Liquibase changelogs are INSERT-ONLY for boat_audit (no UPDATE/DELETE).
□ SPRING_SESSION migration lives under `bff/src/main/resources/db/changelog/` and is applied against the `bff_session` database (owned by role `bff`). It MUST NOT live under business-service.
