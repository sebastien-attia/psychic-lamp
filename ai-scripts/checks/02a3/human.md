# Human checks — Phase 02a3 Backend Service + BFF Client
□ BoatController returns 201 + Location + ETag on POST, 200 + ETag on GET, 409 on stale PUT.
□ Audit records are created INSIDE the same transaction as the write (read the service code).
□ GlobalExceptionHandler has an SLF4J logger field and logs each handled exception.
□ BFF service delegates to the generated client — no business logic leaks into the BFF.
