# Human checks — Phase 02a6 Backend Security Gates
□ Open `bff/target/spotbugsXml.xml` and `business-service/target/spotbugsXml.xml` — every raised bug is in the SECURITY category (no STYLE/PERFORMANCE noise).
□ Any false positives from SpotBugs are either suppressed with targeted `@SuppressFBWarnings` (never module-wide) or documented in the phase 02a6 commit.
□ Open `target/bom.json` on both services — verify Spring Boot 4.0.6, Hibernate, Keycloak client, MapStruct, Lombok appear at the versions you expect.
□ DTRACK_URL and DTRACK_API_KEY in `.env.example` are clearly flagged `# staging/prod only` and are NOT committed as real values.
□ CLAUDE.md "Security build gates" paragraph matches the plugin coordinates actually present in `pom.xml` (no version drift between docs and build).
□ `./mvnw -Posv verify` works on your workstation if the `osv-scanner` binary is installed (optional — CI is the authoritative gate).
