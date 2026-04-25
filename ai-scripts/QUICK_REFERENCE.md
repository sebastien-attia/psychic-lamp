# The Boat App — Quick Reference

## Usage

```bash
./ai-scripts/run-phase.sh 02a2              # launches Claude Code in PLAN MODE automatically
FORCE=1 ./ai-scripts/run-phase.sh 02a2      # override a hard-fail at the checkpoint (not recommended)
./ai-scripts/run-phase.sh help              # full phase list
```

Every step uses `claude --permission-mode plan --prompt-file <prompt>`.
Claude analyzes first (read-only), proposes a plan, you review, then approve execution.
Before the prompt is launched, `print_phase_intro` prints a yellow box describing
exactly what the prompt + check script will produce — read it before approving.

## Full Flow

```
Phase 0    ./ai-scripts/run-phase.sh 0            bootstrap (hexagonal skeleton, CLAUDE.md, rules)
Phase 0b   ./ai-scripts/run-phase.sh 0b           Spring Initializr scaffold (Boot 4.0.6, Java 25)
           ./ai-scripts/00b-generate-bff-key.sh   ⟵ helper (not a phase): RSA PEM for BFF private_key_jwt.
                                                    Required before running 02a4/02a5 integration tests
                                                    or `docker compose up` in local-intg.
Phase 0d   ./ai-scripts/run-phase.sh 0d           Azure+GitHub OIDC bootstrap (one-off, pre-CI/CD)
Phase 1    ./ai-scripts/run-phase.sh 1            OpenAPI contract

Phase 2 — run sequentially from the project root:
  Backend   run-phase.sh 02a1 → 02a2 → 02a3 → 02a4 → 02a5 → 02a6
  Frontend  run-phase.sh 02b1 → 02b2 → 02b3 → 02b4 → 02b5
  Infra     run-phase.sh 02c1 → 02c2 → 02c3

Phase 3    ./ai-scripts/run-phase.sh 3            E2E tests
Phase 4    ./ai-scripts/run-phase.sh 4            CI/CD baseline (OIDC, SAST/SBOM/SCA, e2e)
Phase 4b   ./ai-scripts/run-phase.sh 4b           CI/CD hardening (cosign + SLSA + Trivy + CodeQL + branch protection)
Phase 5    ./ai-scripts/run-phase.sh 5            docs → 🎉
```

## Key Decisions

| Decision | Choice |
|----------|--------|
| Architecture | **Strict hexagonal** in business-service; BFF is a thin proxy. ArchUnit enforced. DTOs + BFF client + BS controller interface are **generated** from `contracts/openapi.yaml` (openapi-generator-maven-plugin). |
| Service count | **2** Spring Boot services: BFF (:8080, OAuth2 session + SPA) + Business Service (:8081, JWT resource server + JPA) |
| Auth — BFF | **Session cookie** (HttpOnly/Secure/SameSite), Keycloak **confidential** client, CSRF enabled |
| Auth — BFF ↔ Keycloak | **`private_key_jwt`** (signed JWT client_assertion, RFC 7523). NO shared client_secret. BFF publishes public JWKS at `/.well-known/jwks.json`; Keycloak fetches it (`use.jwks.url=true`). |
| Auth — Business Service | **Stateless JWT Bearer** (spring-oauth2-resource-server), no session, no CSRF |
| Token forwarding | BFF attaches Bearer access_token via RestClient interceptor; refresh handled by DefaultOAuth2AuthorizedClientManager |
| Dev mode | **Auth bypassed** (Business Service only, permitAll), PostgreSQL (not H2), no Keycloak, no BFF |
| Environments | **4**: dev, local-intg, staging, prod |
| User table | **APP_USER** synced from JWT claims on each request (Business Service), FK from BoatAudit |
| Audit | **BoatAudit** INSERT-ONLY, FK→APP_USER |
| Concurrency | **@Version + ETag/If-Match** (on JPA entity only, not domain model) |
| DB migrations | **Liquibase** YAML — each service ships its own `db/changelog/db.changelog-master.yaml` (introduced in `02a2`). Per-DB ownership is enforced operationally via the per-DB LOGIN roles created in `02c1`/`02c3`: BFF role can only touch `bff_session` (SPRING_SESSION), `business_service` role can only touch `boatapp` (APP_USER/BOATS/BOAT_AUDIT). Keycloak manages its own schema. |
| PostgreSQL topology | **1 instance, 3 isolated databases** (`bff_session`, `boatapp`, `keycloak`) with 3 dedicated LOGIN roles (`bff`, `business_service`, `keycloak`) — each role owns exactly one DB, PUBLIC CONNECT revoked |
| Frontend auth | **Zero OAuth code** — session cookie + CSRF via Axios, `/oauth2/authorization/keycloak` redirect to BFF |
| Frontend deployment | **Baked into BFF image** at `bff/src/main/resources/static/` — no separate frontend container |
| Docker — local-intg | **4 services**: bff + business-service + postgres + keycloak |
| Docker — dev | **2 services**: business-service-dev + postgres-dev |
| CI/CD staging | Auto on push to **staging** branch (no approval) |
| CI/CD prod | **GitHub Release** on main (manual, requires reviewer approval) |
| Claude Code | **Plan Mode** (`--permission-mode plan`) on every step |
| Human checkpoints | Every phase runs `ai-scripts/checks/<phase>/run.sh` + displays `checks/<phase>/human.md`. A `fail` line aborts unless `FORCE=1`. |
| Keycloak version | **`quay.io/keycloak/keycloak:26.6.1`** (Quarkus distribution) in compose, Testcontainers, and Terraform. Prod Container App runs `start --optimized` with `KC_HOSTNAME` / `KC_PROXY_HEADERS` / `KC_HEALTH_ENABLED` / `KC_METRICS_ENABLED`. |
| Keycloak config | **Single YAML** at `infra/keycloak/realm.yaml` (keycloak-config-cli format, env placeholders). Applied by `adorsys/keycloak-config-cli:latest-26.6.1` in all envs: compose sidecar locally, Ansible `docker_container` task in staging/prod. No `community.general.keycloak_*` modules, no `--import-realm`. |
| Security build gates | **SAST**: `spotbugs-maven-plugin:4.9.8.3` + `findsecbugs-plugin:1.14.0` on `verify` (SECURITY-only filter, excludes generated code). **SBOM**: `cyclonedx-maven-plugin:2.9.1` on `package` → `target/bom.json`+`bom.xml`. **SCA (CI)**: `google/osv-scanner-action@v2.3.5`, fail on HIGH+CRITICAL. **SCA (local)**: `osv` Maven profile via `exec-maven-plugin` — PATH-resolved (`./mvnw -Posv verify` runs whatever `osv-scanner` is on PATH; not version-pinned). **Governance**: `dependency-track-maven-plugin:1.11.0` uploads BOM on `deploy` (skip=true by default; CI overrides `-Ddtrack.skip=false`). |
| Pipeline hardening (phase 4b) | **SLSA Build L3 provenance** + **cosign keyless signing** (Sigstore Rekor) on every BFF/BS image. **Trivy** image scan blocks on HIGH+CRITICAL with fix available (`ignore-unfixed=true`). **CodeQL** (`java-kotlin` + `javascript-typescript`) weekly + on PR; **Semgrep OSS fallback** for private-without-GHAS. **gitleaks** secret scan on every push/PR with `.gitleaks.toml` allowlist. **Terraform plan-as-artifact**: `plan` job uploads `tfplan.binary`; `apply` job downloads + applies verbatim across the environment-approval gate (no re-plan in `apply`). **Dependency-Track is a gate**, not just a receipt — pre-deploy poll on FAIL-severity violations via `.github/actions/dtrack-gate/`. **All `uses:` SHA-pinned** with trailing tag comment so Dependabot can still bump them. **Concurrency groups** on deploy workflows (`cancel-in-progress=false` — never cancel a half-applied terraform). **Branch protection** declared in `.github/settings.yml`, applied idempotently by `apply_branch_protection()` in `00d-bootstrap-azure.sh`. **Dependabot** on github-actions + `bff/` Maven + `business-service/` Maven + `frontend/` npm. |

## Hexagonal Package Map

### BFF (`bff/src/main/java/ch/owt/boatapp/bff/`)

```
bff/
├── adapter/
│   ├── in/web/                    ← Spring @RestController (HTTP adapter)
│   │   ├── BoatController         (proxies to BoatBffService)
│   │   ├── AuthController         (/api/me, session info)
│   │   └── dto/generated/         (openapi-generator output — do not edit)
│   └── out/client/generated/      ← BusinessServiceClient (@HttpExchange, generated)
└── infrastructure/
    ├── service/                   BoatBffService (thin orchestrator, NO @Transactional)
    ├── security/                  SecurityConfig (OAuth2 session + CSRF), BffSecurityHelper
    └── config/                    BeanConfig (RestClient with Bearer interceptor)
```

### Business Service (`business-service/src/main/java/ch/owt/boatapp/`)

```
business-service/
├── domain/                        ← PURE JAVA (ArchUnit enforced: NO Spring/Jakarta)
│   ├── model/                     Boat, AppUser, BoatAudit, BoatId, UserId, PageResult
│   ├── port/
│   │   ├── in/                    ManageBoatsUseCase, GetUserUseCase + Command/Query records
│   │   └── out/                   BoatRepositoryPort, AppUserRepositoryPort, ...
│   └── service/                   BoatDomainService, UserDomainService
├── adapter/
│   ├── in/web/                    ← Spring @RestController — JWT resource server
│   │   ├── BoatController         (implements BusinessServiceApi)
│   │   ├── generated/             (BusinessServiceApi interface, openapi-generator output)
│   │   ├── dto/generated/         (DTOs, openapi-generator output — do not edit)
│   │   └── mapper/                (web ↔ domain)
│   └── out/persistence/           ← Spring Data JPA (implements domain.port.out)
│       ├── entity/                JPA @Entity (separate from domain model)
│       ├── mapper/                (JPA entity ↔ domain record — hand-written @Component)
│       └── repository/            JpaRepository + RepositoryAdapter
└── infrastructure/
    ├── config/                    BeanConfig (wires pure-Java domain beans)
    ├── security/                  ResourceServerSecurityConfig (JWT), DevSecurityConfig (permitAll)
    └── service/                   BoatApplicationService (@Service @Transactional bridge layer)
```

## 4 Environments

| Profile | BFF | Business Service | Keycloak | Database | Docker |
|---------|-----|-----------------|----------|----------|--------|
| dev | Not started | :8081, permitAll | Not needed | PostgreSQL :5432 | docker-compose.dev.yml (2 svc) |
| local-intg | :8080 (full oauth2Login) | :8081 (JWT RS) | Docker :8180 | PostgreSQL Docker | docker-compose.yml (4 svc) |
| staging | Azure Container App (external) | Azure Container App (internal) | Azure Container App | Azure PostgreSQL Flexible | Auto on push to staging |
| prod | Azure Container App (external) | Azure Container App (internal) | Azure Container App | Azure PostgreSQL Flexible | On GitHub Release (manual) |
