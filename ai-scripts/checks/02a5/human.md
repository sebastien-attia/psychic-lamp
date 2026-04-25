# Human checks — Phase 02a5 Backend Tests
□ Open the coverage report(s) — domain/service classes are covered above the threshold.
□ ArchUnit rules are a superset of the hexagonal contract (no Spring in domain, no JPA in BFF, no @Transactional on controllers, @ExceptionHandler logs).
□ No @Disabled / @Ignore / commented-out tests.
□ Running `./mvnw verify` a second time is fast (test caches) and still green.
