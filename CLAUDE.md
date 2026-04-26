# The Boat App

A Vue 3 SPA + Spring Cloud Gateway BFF + Spring Boot Business Service with strict hexagonal architecture.

## Architecture

```
Browser
  │
  │   dev / local-intg: Vite (:5173)        staging / prod: Azure Static Web Apps
  │   (serves SPA, proxies API)             (serves SPA, linked-backend → BFF)
  ▼
BFF — Spring Cloud Gateway Server Web MVC (port 8080)
  │   OAuth2 client, server-side session, CSRF, TokenRelay filter
  │   Bearer <access_token>
  ▼
Business Service (port 8081) — JWT resource server, domain logic, persistence
  │
  ▼
PostgreSQL (1 instance, 3 isolated DBs: bff_session, boatapp, keycloak — one role per DB)
```

### BFF (bff/)
Spring Cloud Gateway Server Web MVC (servlet flavor, SCG 5.0.1, Spring Cloud 2025.1.1). No domain logic, no JPA, no outbound HTTP-Interface client. Routes are declarative.
- `infrastructure.security.SecurityConfig` — OAuth2 session login (Keycloak `private_key_jwt`), CSRF cookie, Keycloak server-side logout. Profile `!dev`.
- `infrastructure.config.BffConfig` — wires `bffSigningJwk`, the `private_key_jwt` token-response clients, and `OAuth2AuthorizedClientManager`. SCG's `TokenRelayFilterFunctions` resolves the manager per request via `getBean` to forward the user's access token on every upstream call.
- `infrastructure.web.ScgUpstreamFailureFilter` — rewrites upstream 5xx without an RFC 9457 body to a 502 `upstream-failure` envelope. Upstream 4xx (and 5xx with RFC 9457 body) are passed through byte-identical.
- `infrastructure.web.Http11RestClientCustomizer` — pins the JDK HttpClient backing every outbound `RestClient` to HTTP/1.1, avoiding the h2c-stream-cancelled hazard.
- `adapter.in.web` — BFF-LOCAL endpoints only: `AuthController` (`/api/me`), `JwksController` (`/.well-known/jwks.json`), `RestAuthenticationEntryPoint`, `GlobalExceptionHandler`. NO controller for boats — proxying lives in `application-routes.yml`.
- `application-routes.yml` — single SCG route table: `Path=/api/v1/boats/{*subpath}` → `${business-service.url}` with `TokenRelay=keycloak`. Imported by each non-dev profile YAML.

### Business Service (business-service/)
Pure hexagonal service. Validates JWT Bearer tokens.
- `domain.model` — pure Java domain objects (Boat, AppUser, BoatAudit). NO Spring annotations.
- `domain.port.in` — inbound port interfaces (use cases). Pure Java.
- `domain.port.out` — outbound port interfaces (repository contracts). Pure Java.
- `domain.service` — use case implementations. Pure Java. Only depends on domain.model + ports.
- `adapter.in.web` — Spring @RestController (REST adapter IN). JWT resource server.
- `adapter.out.persistence` — Spring Data JPA (adapter OUT). Implements domain.port.out.
- `infrastructure.config` — BeanConfig (wires pure-Java domain beans).
- `infrastructure.security` — ResourceServerSecurityConfig (JWT), DevSecurityConfig (dev bypass).
- `infrastructure.service` — BoatApplicationService (@Service @Transactional, bridge layer).

**Rule: domain.* packages must have ZERO Spring/Jakarta imports. ArchUnit enforces this.**

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
docker compose up                              # postgres + keycloak + business-service + bff (SCG) + frontend (Vite)
# browser → http://localhost:5173
```

The `frontend` service runs Vite in `local-intg` mode and proxies `/api`, `/oauth2`, `/login`, `/logout` to the BFF over the compose network. The host-run path (`cd frontend && npm run dev:intg`) still works for IDE / debugger integration — it falls back to `http://localhost:8080` when `VITE_BFF_TARGET` is unset.

The SPA is NOT baked into the BFF image. In dev / local-intg it is served by Vite; in staging / prod it is hosted on Azure Static Web Apps (Bring-Your-Own-Backend → BFF Container App).

## Build & verify

```bash
cd frontend && npm run build
cd bff && ./mvnw verify             # JaCoCo gate skipped by default; CI sets -Djacoco.check.skip=false
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
- BFF auth: session-based (HttpOnly cookie), confidential Keycloak client, token forwarding via SCG `TokenRelay` filter (declared in `application-routes.yml`)
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
