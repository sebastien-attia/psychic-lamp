# The Boat App

Two Spring Boot services with strict hexagonal architecture.

## Architecture

```
Browser
  │ (session cookie)
  ▼
BFF (port 8080) — OAuth2 client, session, CSRF, token forwarding
  │ Bearer <access_token>
  ▼
Business Service (port 8081) — JWT resource server, domain logic, persistence
  │
  ▼
PostgreSQL (1 instance, 3 isolated DBs: bff_session, boatapp, keycloak — one role per DB)
```

### BFF (bff/)
Thin proxy with session-based OAuth2. No domain logic, no JPA.
- `adapter.in.web` — Spring @RestController (HTTP adapter). Depends on infrastructure.service.
- `adapter.out.client` — BusinessServiceClient (Spring HTTP Interface). Calls Business Service REST API.
- `infrastructure.service` — BoatBffService (thin orchestrator). No @Transactional. Injects Bearer token.
- `infrastructure.security` — SecurityConfig (OAuth2 session, CSRF), BffSecurityHelper.

### Business Service (business-service/)
Pure hexagonal service, packaged as a four-module Maven reactor (canonical
Hombergs School A package names + School C physical Maven enforcement).
The Maven graph itself prevents domain or application from leaking into
adapters, beyond what ArchUnit can check.

```
business-service/                  ← parent POM (packaging=pom)
├── domain/                        ← business-service-domain — pure Java, ZERO Spring/Jakarta deps
├── application/                   ← business-service-application — depends on domain + spring-context + spring-tx
├── infrastructure/                ← business-service-infrastructure — depends on application + Spring web/JPA/security
└── bootstrap/                     ← business-service-bootstrap — @SpringBootApplication, runnable jar
```

Java packages (FQNs are stable across the multi-module split):
- `domain.model` — pure Java domain objects (Boat, AppUser, BoatAudit). NO Spring annotations.
- `domain.exception` — domain exceptions (BoatNotFoundException, …).
- `domain.service.validation` — pure-Java validators (SyntacticValidator, SemanticValidator).
- `application.port.in` — inbound port interfaces (use cases) and Command/Query records.
- `application.port.out` — outbound port interfaces (repository contracts).
- `application.service` — use-case implementations. `BoatApplicationService` (@Service @Transactional bridge); `BoatDomainService` and `UserDomainService` (pure-Java orchestration, wired by BeanConfig).
- `adapter.in.web` — Spring @RestController (REST adapter IN). JWT resource server.
- `adapter.out.persistence` — Spring Data JPA (adapter OUT). Implements application.port.out.
- `infrastructure.config` — BeanConfig (wires pure-Java application services as beans).
- `infrastructure.security` — ResourceServerSecurityConfig (JWT), DevSecurityConfig (dev bypass).

**Rule: `domain.*` and `application.port.*` must have ZERO Spring/Jakarta imports.** The Maven dependency graph enforces this for `domain.*` (the domain jar has no Spring deps on its classpath, so the import would not compile). ArchUnit enforces it for `application.port.*` and adds annotation-placement rules Maven cannot check (e.g. @Service only in application.service, @Repository only in adapter.out.persistence, @SpringBootApplication only at the ch.owt.boatapp root).

## Environments

| Profile | Auth | BFF | Business Service | Keycloak | Database | Use |
|---------|------|-----|-----------------|----------|----------|-----|
| `dev` | None | NOT started | Started (permitAll) | NOT required | PostgreSQL | Fast iteration |
| `local-intg` | Session + JWT | Started (:8080) | Started (:8081) | Docker | PostgreSQL Docker | Integration testing |
| `staging` | Session + JWT | Azure | Azure | Azure | Azure PostgreSQL | Pre-production |
| `prod` | Session + JWT | Azure | Azure | Azure | Azure PostgreSQL | Production |

## Dev mode (no BFF, no Keycloak)

```bash
docker compose -f docker-compose.dev.yml up   # postgres + business-service (dev profile)
cd frontend && npm run dev                     # Vite proxy → localhost:8081
```

## Local-intg (full stack)

```bash
docker compose up                              # all 4 services
cd frontend && npm run dev:intg                # Vite proxy → BFF :8080
```

## Build & verify

```bash
cd frontend && npm run build
cd bff && ./mvnw verify
cd business-service && ./mvnw verify
docker compose up
```

## Conventions

- Spring Boot 4.0.6 on Java 25, Maven jar packaging, YAML config. Both services scaffolded from Spring Initializr (`ai-scripts/00c-initializr.sh`) — extend `pom.xml` rather than rewriting.
- Business Service domain is pure Java; Spring only in adapters and infrastructure
- ArchUnit enforces hexagonal boundaries in both services on every build
- Liquibase for DB migrations (YAML changelogs) — split per service: BFF owns bff_session (SPRING_SESSION), business-service owns boatapp (APP_USER/BOATS/BOAT_AUDIT); Keycloak manages its own schema
- Boat: id (UUID), name (max 64), description (max 256), createdAt (OffsetDateTime UTC), version
- BoatAudit: INSERT-ONLY, FK to APP_USER. APP_USER synced from JWT claims on each request.
- BFF auth: session-based (HttpOnly cookie), confidential Keycloak client, token forwarding
- Business Service auth: stateless JWT Bearer token validation (spring-oauth2-resource-server)
- dev profile: no auth at all, dummy user auto-created — for fast iteration without Keycloak

## Security build gates

Three layers of supply-chain tooling run on every build:

- **SAST** — `spotbugs-maven-plugin` + `findsecbugs-plugin` fire during
  `./mvnw verify` (goal `check`, `failOnError=true`). Only the
  `SECURITY` FindBugs category is raised; OpenAPI-generated code plus
  documented framework-noise patterns are suppressed via
  `spotbugs-exclude-generated.xml` (each suppression carries a WHY
  comment).
- **SBOM** — `cyclonedx-maven-plugin` emits `target/bom.json` +
  `target/bom.xml` on `mvn package`.
- **SCA** — Primary scan runs in CI via the `google/osv-scanner-action`
  GitHub Action (wired in a later phase); it fails on `HIGH` +
  `CRITICAL` findings. Local devs can opt-in via `./mvnw -Posv verify`
  after installing the OSV-Scanner binary.
- **Governance** — `dependency-track-maven-plugin` uploads the
  CycloneDX BOM to Dependency-Track on the `deploy` phase. Both modules
  are jar-packaged with no Maven release target, so CI invokes the
  upload goal directly:
  ```bash
  ./mvnw -B package dependency-track:upload-bom \
      -Ddtrack.skip=false \
      -Ddependency-track.url="${DTRACK_URL}" \
      -Ddependency-track.apiKey="${DTRACK_API_KEY}"
  ```
  Default for local builds: `dtrack.skip=true` so a stray `mvn deploy`
  cannot reach a server. Required GitHub repository secrets (staging +
  prod environments only): `DTRACK_URL`, `DTRACK_API_KEY` — CI
  translates them into the `-D` flags above.

# Project conventions

## Code review policy

After writing or editing any source code file (and before declaring a task done), invoke the `@code-reviewer` subagent on the modified file(s).

Treat the reviewer's output as follows:
- **Must fix** findings: address them in the same turn before responding to the user.
- **Should fix** findings: address them unless there is a clear reason not to; surface that reason to the user.
- **Consider** findings: surface to the user as suggestions, do not auto-apply.

Documentation requirements are non-negotiable: every class and every public method/function must have a docstring in the language's idiomatic format. The reviewer will flag missing documentation as a must-fix finding.
