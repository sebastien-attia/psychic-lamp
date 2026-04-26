# The Boat App — Implementation Plan

## Architecture

```
Browser
  │ session cookie (HttpOnly)
  ▼
┌──────────────────────────────────────────────┐
│ BFF (bff/, port 8080)                        │
│  adapter.in.web (BoatController, AuthCtrl)   │
│  infrastructure.service (BoatBffService)     │
│  adapter.out.client (BusinessServiceClient)  │
│  infrastructure.security (OAuth2 + CSRF)     │
│  Serves Vue SPA static files                 │
└────────────────────┬─────────────────────────┘
                     │ Bearer <access_token>
                     ▼
┌──────────────────────────────────────────────┐
│ Business Service (business-service/, port 8081)│
│  adapter.in.web (BoatController) — JWT RS    │
│  infrastructure.service (@Transactional)     │
│  domain (PURE JAVA — ArchUnit enforced)      │
│  adapter.out.persistence (JPA)               │
└───────────────────────┬──────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────┐
│ PostgreSQL (1 instance — 3 isolated DBs)    │
│  bff_session      ← role: bff                │
│  boatapp          ← role: business_service   │
│  keycloak         ← role: keycloak           │
│  (PUBLIC CONNECT revoked — no cross-DB reach)│
└──────────────────────────────────────────────┘

Dev mode (no BFF, no Keycloak):
  Browser → Vite proxy → Business Service :8081 (permitAll) → boatapp DB

Local-intg (full stack):
  Browser → BFF :8080 ─┬→ bff_session DB (sessions)
                       ├→ Business Service :8081 → boatapp DB
                       └→ Keycloak :8180 → keycloak DB
```

## Environments

| Profile | BFF | Business Service | Keycloak | Database | Docker |
|---------|-----|-----------------|----------|----------|--------|
| `dev` | NOT started | Started (:8081, permitAll) | NOT needed | PostgreSQL :5432 | docker-compose.dev.yml |
| `local-intg` | Started (:8080) | Started (:8081, JWT RS) | Docker :8180 | PostgreSQL Docker | docker-compose.yml |
| `staging` | Azure | Azure | Azure | Azure PostgreSQL | Azure Container Apps |
| `prod` | Azure | Azure | Azure | Azure PostgreSQL | Azure Container Apps |

### Auth & Keycloak notes

> **Auth note:** BFF ↔ Keycloak uses **`private_key_jwt`** (RFC 7523 — signed
> JWT client_assertion, no shared client_secret). The BFF holds an RSA signing
> key locally and publishes the public half at `/.well-known/jwks.json`;
> Keycloak fetches it (`use.jwks.url=true` on `boat-app-confidential`).
>
> **Keycloak config-as-code:** realm, client, roles, and the demo user are
> declared in a single source-of-truth file `infra/keycloak/realm.yaml`
> (keycloak-config-cli format, env placeholders resolved at import time).
> The same file is applied in all three environments:
> - **local-intg** — `keycloak-config` compose sidecar (02c1-docker.md) runs
>   `adorsys/keycloak-config-cli:latest-26.6.1` against the compose Keycloak.
> - **staging / production** — Ansible `configure-keycloak.yml` (02c3-ansible.md)
>   runs the same image against the Azure Container App via `docker_container`.
>
> Keycloak image pinned to **`26.6.1`** (Quarkus distribution) in compose,
> Testcontainers, and Terraform. Prod Container App runs `start --optimized`
> with `KC_HOSTNAME`, `KC_PROXY_HEADERS=xforwarded`, `KC_HEALTH_ENABLED`, and
> `KC_METRICS_ENABLED`. `community.general.keycloak_*` Ansible modules are NOT
> used — they lag the server and duplicate intent already expressed in YAML.

## Execution — All steps run Claude Code in PLAN MODE

```bash
./ai-scripts/run-phase.sh <step>    # always --permission-mode plan
```

### Sequential phases

| Step | Command | What |
|------|---------|------|
| 0 | `run-phase.sh 0` | Bootstrap (bff/ + business-service/ dirs, Claude rules, CLAUDE.md) |
| 0d | `run-phase.sh 0d --subscription … --repo owner/name` (passthrough wrapper around `00d-bootstrap-azure.sh`) | **One-off Azure+GitHub bootstrap** before Phase 4 CI/CD: Entra ID app + OIDC federated credentials (main/staging/PR/envs), Subscription Contributor + User Access Administrator role assignments, Terraform remote-state storage, `gh secret/variable set` for `AZURE_*` / `TF_STATE_*` secrets and `PROJECT` / `LOCATION` variables. ACR name is owned by Terraform — not a bootstrap variable. Idempotent. Only manual follow-up: enable "Required reviewers" on the `production` GitHub Environment (no public API). |
| 1 | `run-phase.sh 1` | OpenAPI contract |

### Phase 2 (run sequentially from the project root)

| Backend | Frontend | Infra |
|---------|----------|-------|
| `run-phase.sh 02a1` scaffold BFF + Business Service | `run-phase.sh 02b1` scaffold | `run-phase.sh 02c1` docker |
| `run-phase.sh 02a2` domain (pure Java, business-service only) | `run-phase.sh 02b2` auth UX | `run-phase.sh 02c2` terraform |
| `run-phase.sh 02a3` services + web adapters + BFF client layer | `run-phase.sh 02b3` boat list | `run-phase.sh 02c3` ansible |
| `run-phase.sh 02a4` auth (BFF OAuth2 + Business Service JWT RS) | `run-phase.sh 02b4` CRUD | |
| `run-phase.sh 02a5` ArchUnit + tests (per service) | `run-phase.sh 02b5` polish | |
| `run-phase.sh 02a6` Security gates (SAST/SBOM/SCA/DT) | | |

### After phase 2

| Step | Command | What |
|------|---------|------|
| 3 | `run-phase.sh 3` | Playwright E2E tests |
| 4 | `run-phase.sh 4` | CI/CD baseline (staging auto, prod on release) |
| 4b | `run-phase.sh 4b` | CI/CD hardening: cosign+SLSA, Trivy, CodeQL, gitleaks, terraform plan-as-artifact, DT gate, SHA-pinning, concurrency, branch protection |
| 5 | `run-phase.sh 5` | README + AI_USAGE.md |

### Human checkpoints (key items)

> The items below are the **human-eyeball** checks shown at the end of each
> phase. The matching automated equivalents live in
> `ai-scripts/checks/<phase>/run.sh` and are run before the human box
> appears (`fail` aborts unless `FORCE=1`). Both lists are surfaced in the
> yellow checkpoint box `run-phase.sh` prints after each phase.

- **02a2**: `grep -r "org.springframework" business-service/src/main/java/ch/owt/boatapp/domain/` returns NOTHING
- **02a5 Business Service**: `cd business-service && ./mvnw verify` — all green, jwt() auth, no Keycloak container
- **02a5 BFF**: `cd bff && ./mvnw verify` — all green, Keycloak + WireMock containers
- **02a6 Security gates**: `cd bff && ./mvnw verify` runs SpotBugs+FindSecBugs (SECURITY category only) to green; `cd bff && ./mvnw package` emits `target/bom.json` (CycloneDX). Same for `business-service`. Dependency-Track upload skipped locally (`dtrack.skip=true`); enabled in staging/prod CI only.
- **02a4**: `docker compose -f docker-compose.dev.yml up && curl http://localhost:8081/api/v1/boats` → 200 without auth
- **3**: `docker compose up` → login demo/demo123 → BFF → Business Service → CRUD works; `npx playwright test` ALL green
- **4b Image signatures**: `cosign verify --certificate-identity-regexp 'https://github\.com/<org>/<repo>/\.github/workflows/deploy-staging\.yml@.*' --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' ${ACR}.azurecr.io/bff@sha256:<digest>` → "Verified OK" + Rekor entry
- **4b Branch protection**: `gh api repos/${OWNER}/${REPO}/branches/main/protection --jq '.required_status_checks.contexts'` lists exactly the contexts in `.github/settings.yml`
- **4b Trivy gate**: a synthetic vulnerable Dockerfile committed on a throwaway branch fails the `container-scan` job in CI

## Verification model

Every phase has a structured two-layer checkpoint:

```
ai-scripts/checks/
├── _lib.sh                 shared helpers (pass/warn/fail/info, severity, banner)
├── <phase>/run.sh          automated checks — compile, grep, curl, docker, liquibase, …
└── <phase>/human.md        non-automatable items (UX, visual, judgment)
```

`run-phase.sh` invokes `run_checkpoint <phase> <work-dir>` after Claude finishes a phase.
It runs the script, prints a two-section yellow box (AUTOMATED CHECKS + HUMAN TO VERIFY),
and blocks on Enter/Ctrl+C.

**Severity tiers** (from `_lib.sh`):

| Helper | Glyph | Meaning | Blocks flow? |
|--------|:-:|---|:-:|
| `pass "…"` | ✓ | Check passed | no |
| `warn "…"` | ⚠ | Soft issue (likely misconfiguration, nice-to-have) | no — Enter continues |
| `fail "…"` | ✗ | Hard failure (secret leak, missing required file, broken build) | **yes — phase aborts unless `FORCE=1`** |
| `info "…"` | · | Informational / deferred (e.g. "integration smoke deferred to phase 3") | no |

**Running a check standalone**:
```bash
ai-scripts/checks/02a4/run.sh .                 # from project root
CHECK_RESULT_FILE=/tmp/r.tsv ai-scripts/checks/02a4/run.sh .   # also emit tsv
```

Every prompt `.md`'s `<verification>` block now invokes its phase's `checks/<phase>/run.sh`,
so Claude self-checks the same gates the human sees at the checkpoint.
