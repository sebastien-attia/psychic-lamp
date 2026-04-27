#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# The Boat App — Master Orchestrator
#
# Every step launches Claude Code in PLAN MODE (--permission-mode plan).
# Claude first analyzes and proposes a plan. You review, then approve execution.
#
# Usage: ./ai-scripts/run-phase.sh <step>
# ============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'
BOLD='\033[1m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROMPTS_DIR="${SCRIPT_DIR}"

print_header() {
  echo ""
  echo -e "${BLUE}╔══════════════════════════════════════════════════════════╗${NC}"
  echo -e "${BLUE}║${NC} ${BOLD}$1${NC}"
  echo -e "${BLUE}╚══════════════════════════════════════════════════════════╝${NC}"
  echo ""
}

print_step() { echo -e "  ${CYAN}▸${NC} $1"; }
print_success() { echo -e "  ${GREEN}✓${NC} $1"; }
print_warning() { echo -e "  ${YELLOW}⚠${NC} $1"; }

# ── print_phase_intro <phase> ───────────────────────────────────────────────
# Prints a yellow box describing what the phase's prompt + script will produce
# and which automated check will run afterwards. Sourced from the prompts and
# shell scripts in ai-scripts/ — keep in sync when those change.
print_phase_intro() {
  local phase="$1"
  local source artefacts invariant verify
  case "${phase}" in
    0)
      source="ai-scripts/00-bootstrap.sh (shell, no LLM)"
      artefacts=(
        "Hexagonal trees for bff/ + business-service/, frontend/, infra/, contracts/"
        ".claude/{rules,settings.json,hooks}, .env.example, .gitignore, .editorconfig"
        "Initial CLAUDE.md (2 services, 4 profiles, hexagonal rules)"
        "git init on main + initial commit"
      )
      invariant="No client_secret in .env.example — Keycloak auth = private_key_jwt (RFC 7523)."
      verify="ai-scripts/checks/0/run.sh — CLAUDE.md & rules present, .env.example safe, hexagonal tree"
      ;;
    0b)
      source="ai-scripts/00c-initializr.sh (shell, no LLM)"
      artefacts=(
        "Fetches bff + business-service from start.spring.io in parallel"
        "Spring Boot 4.0.6, Java 25, jar packaging, Maven wrapper"
        "BFF deps: web, security, oauth2-client, session-jdbc, data-jdbc, liquibase, postgresql, testcontainers"
        "BS  deps: web, data-jpa, oauth2-resource-server, validation, liquibase, postgresql, testcontainers"
        "Renames Initializr's application.properties → application.yml"
      )
      invariant="Idempotent — skips a service if its pom.xml already exists."
      verify="ai-scripts/checks/0b/run.sh — pom Boot/Java versions, jar packaging, starter list"
      ;;
    1)
      source="ai-scripts/01-openapi-contract.md  →  contracts/openapi.yaml"
      artefacts=(
        "Boat (id UUID, name 1-64, description ≤256, createdAt, version) + User schema"
        "Tags: BusinessService (/api/v1/**), User (/api/me, BFF-only)"
        "ETag/If-Match concurrency (409/428); RFC 9457 ProblemDetail envelope on every error"
        "Severity enum ERROR | WARNING | INFO; Content-Language header on errors"
      )
      invariant="No Bearer scheme in the contract; no about:blank — every error has a problem-type URI."
      verify="ai-scripts/checks/1/run.sh — YAML parses, redocly lint, required fields/headers present"
      ;;
    02a1)
      source="ai-scripts/02a1-backend-scaffold.md"
      artefacts=(
        "openapi-generator-maven-plugin in BOTH poms, filtered to tag=BusinessService"
        "BFF: spring-http-interface client (apiNameSuffix=Client); BS: interfaceOnly=true"
        "4 profiles per service: application{,-dev,-local-intg,-staging,-prod}.yml"
        "Empty Liquibase master changelogs (db.changelog-master.yaml) per service"
      )
      invariant="useBeanValidation=true on both generators; no client_secret in any application-*.yml."
      verify="ai-scripts/checks/02a1/run.sh — generate-sources + compile, profiles + generated classes"
      ;;
    02a2)
      source="ai-scripts/02a2-backend-domain.md"
      artefacts=(
        "Pure-Java domain in business-service: Boat, AppUser, BoatAudit, BoatId, UserId, ServiceResponse"
        "Inbound + outbound ports as records/interfaces; PageResult<T> (no Spring Page)"
        "Persistence adapter: JPA entities + hand-written @Component mappers + repository adapters"
        "Liquibase: BFF (SPRING_SESSION); BS (APP_USER, BOATS, BOAT_AUDIT)"
      )
      invariant="business-service/.../domain/ contains ZERO Spring or Jakarta imports — ArchUnit will enforce in 02a5."
      verify="ai-scripts/checks/02a2/run.sh — domain purity grep, changelog inventory, entity/mapper presence"
      ;;
    02a3)
      source="ai-scripts/02a3-backend-service.md"
      artefacts=(
        "BS: BoatDomainService + BoatApplicationService (@Transactional bridge) + BoatController (impl. generated interface)"
        "BFF: BoatBffService (NOT @Transactional) + hand-written BoatController + AuthController (/api/me)"
        "GlobalExceptionHandler in BOTH services: RFC 9457, Content-Language, JakartaCodeTranslator"
        "Audit: insert-only BoatAudit row on every CREATE/UPDATE/DELETE"
      )
      invariant="BFF controller does NOT import BusinessServiceClient (ArchUnit enforced); 5xx upstream → 502 from BFF."
      verify="ai-scripts/checks/02a3/run.sh — service/controller wiring, audit hook, error envelope"
      ;;
    02a4)
      source="ai-scripts/02a4-backend-auth.md"
      artefacts=(
        "BS: ResourceServerSecurityConfig (JWT, !dev) + DevSecurityConfig (permitAll, dev)"
        "BFF: SecurityConfig (oauth2Login + session JDBC + cookie-CSRF) + DevSecurityConfig"
        "BffConfig: RSAKey bean, JwtClientAuthenticationParametersConverter, JwksController @ /.well-known/jwks.json"
        "infra/keycloak/realm.yaml — clientAuthenticatorType=client-jwt, use.jwks.url=true, demo user"
      )
      invariant="BFF↔Keycloak uses RFC 7523 private_key_jwt — there is NO shared client_secret anywhere."
      verify="ai-scripts/checks/02a4/run.sh — docker compose -f docker-compose.dev.yml up + curl /api/v1/boats → 200"
      ;;
    02a5)
      source="ai-scripts/02a5-backend-tests.md"
      artefacts=(
        "BS ArchUnit: domain pure, no @Transactional on controllers, onion architecture"
        "BS integration: jwt() post-processor (NO Keycloak container), DevModeTest (permitAll)"
        "BFF ArchUnit: no JPA, no @Transactional, controller→service→client chain enforced"
        "BFF integration: PostgreSQL + KeycloakContainer + WireMock; token refresh, CSRF, SPRING_SESSION"
      )
      invariant="BS tests use jwt() — no Keycloak container in BS test classpath."
      verify="ai-scripts/checks/02a5/run.sh — ./mvnw verify green for BOTH services"
      ;;
    02a6)
      source="ai-scripts/02a6-backend-security.md"
      artefacts=(
        "SpotBugs 4.9.8.3 + FindSecBugs 1.14.0 (SECURITY filter, generated/** excluded) on verify"
        "CycloneDX 2.9.1 → target/bom.json + bom.xml on package"
        "Dependency-Track 1.11.0 upload on deploy (skip=true default; CI overrides)"
        "'osv' Maven profile (PATH-resolved); CI workflow pins google/osv-scanner-action@v2.3.5"
      )
      invariant="Plugins live in BOTH poms; .env.example documents DTRACK_URL + DTRACK_API_KEY (commented)."
      verify="ai-scripts/checks/02a6/run.sh — plugins declared, mvn verify+package green, bom.json valid"
      ;;
    02b1)
      source="ai-scripts/02b1-frontend-scaffold.md"
      artefacts=(
        "Vue 3 + TypeScript + Vite + Tailwind + Headless UI; Pinia (setup syntax); Vue Router 4"
        "openapi-generator-cli (typescript-axios) → src/services/api-client/generated/"
        "Vite proxy modes: dev (→ :8081), local-intg (→ BFF :8080)"
        "Axios instance with withCredentials:true + CSRF cookie/header"
      )
      invariant="ZERO OAuth client libraries (no oidc-client-ts / oauth4webapi) — session cookie only."
      verify="ai-scripts/checks/02b1/run.sh — generate:api + type-check + build pass, no oauth libs, lockfile committed"
      ;;
    02b2)
      source="ai-scripts/02b2-frontend-auth.md"
      artefacts=(
        "Pinia authStore: fetchUser, login → /oauth2/authorization/keycloak, logout → /api/logout"
        "Router guard reading isAuthenticated; redirect-on-401 interceptor + toast"
        "Session-expiry handling kept invisible to component code"
      )
      invariant="No tokens are ever read or stored on the client — the BFF owns them."
      verify="ai-scripts/checks/02b2/run.sh — authStore present, guards installed, no token storage in src/"
      ;;
    02b3)
      source="ai-scripts/02b3-frontend-list.md"
      artefacts=(
        "Components: Skeleton, EmptyState, ErrorState, Pagination, SearchInput, BoatCard"
        "BoatListPage responsive (1/2/3 cols mobile-first)"
        "Debounced search 300ms + URL sync of page/size/search/sort"
        "boatStore.fetchBoats(page, size, search) drives the list"
      )
      invariant="Loading + empty + error states are explicit components — never bare spinners or null views."
      verify="ai-scripts/checks/02b3/run.sh — components scaffold, list/store wired, URL sync present"
      ;;
    02b4)
      source="ai-scripts/02b4-frontend-crud.md"
      artefacts=(
        "BoatForm: Zod schema (name 1-64, description ≤256), char counters, friendly error messages"
        "Pages: Create / Detail / Edit; DeleteConfirmDialog (Headless UI)"
        "If-Match header on update; 409 conflict surfaces a re-fetch prompt"
        "Toast notifications; ≥44px touch targets; no horizontal scroll at 375px"
      )
      invariant="ETag round-trip: GET stores it, PUT sends If-Match — losing the ETag is a bug."
      verify="ai-scripts/checks/02b4/run.sh — CRUD pages, Zod validation, If-Match wiring, dialog accessibility"
      ;;
    02b5)
      source="ai-scripts/02b5-frontend-polish.md"
      artefacts=(
        "Dark mode toggle (Headless UI Switch, cookie + system pref)"
        "WCAG AA: 4.5:1 contrast, focus-visible, ARIA, skip-to-main, heading order"
        "i18n EN + FR — locale files in src/i18n/*.json, dynamic Content-Language"
        "Vitest + Vue Test Utils unit tests for the new behaviours"
      )
      invariant="i18n keys live in JSON only; no hard-coded user-facing strings in components."
      verify="ai-scripts/checks/02b5/run.sh — dark mode toggle, locale files, axe-clean components"
      ;;
    02c1)
      source="ai-scripts/02c1-docker.md"
      artefacts=(
        "infra/keycloak/realm.yaml (keycloak-config-cli format, env placeholders)"
        "bff/Dockerfile (3-stage Node 22 → JDK 25 → JRE 25); business-service/Dockerfile (2-stage)"
        "docker-compose.yml — local-intg (4 svc: postgres:17, keycloak:26.6.1 + config sidecar, bff, BS)"
        "docker-compose.dev.yml — dev (2 svc: postgres-dev, business-service-dev)"
        "infra/postgres/init/01-init-dbs.sh — 3 DBs, 3 LOGIN roles, REVOKE PUBLIC CONNECT"
      )
      invariant="Frontend has NO container — built into bff/src/main/resources/static/ and baked into the BFF image."
      verify="ai-scripts/checks/02c1/run.sh — both compose files validate; keycloak-config sidecar gated on healthy"
      ;;
    02c2)
      source="ai-scripts/02c2-terraform.md"
      artefacts=(
        "5 modules: networking (VNet 10.0.0.0/16 + private DNS), database (PG Flex B_Standard_B1ms v17, 3 DBs)"
        "container-registry (Basic, RBAC, admin=false), container-apps (bff external, BS internal, keycloak external)"
        "keyvault (Standard, RBAC, public_network_access=false, private endpoint)"
        "Liquibase ACA Jobs (bff + BS) — Spring Boot CLI invocation"
        "Region: Switzerland North; remote state in Azure Blob (set up by 0d)"
      )
      invariant="No service principal secrets in Terraform — auth via OIDC federated credentials provisioned in 0d."
      verify="ai-scripts/checks/02c2/run.sh — terraform fmt + validate, module presence, no plaintext secrets"
      ;;
    02c3)
      source="ai-scripts/02c3-ansible.md"
      artefacts=(
        "deploy.yml: bootstrap-db-roles → app-config → build/push → update Apps → migrations → keycloak → health"
        "configure-keycloak.yml: adorsys/keycloak-config-cli:latest-26.6.1 applies infra/keycloak/realm.yaml"
        "run-migrations.yml: az containerapp job start (Liquibase Jobs from 02c2), polls terminal status"
        "Inventories: staging.yml, production.yml"
      )
      invariant="Runner MUST be VNet-resident — NEVER open postgres firewall transiently. NO community.general.keycloak_* modules."
      verify="ai-scripts/checks/02c3/run.sh — ansible-lint, playbook structure, no firewall-rule shortcuts"
      ;;
    3)
      source="ai-scripts/03-e2e-tests.md  (docker compose up runs first)"
      artefacts=(
        "Playwright + TypeScript; baseURL http://localhost:8080"
        "Helpers: loginAsDemo (real Keycloak browser flow), createBoatViaAPI, deleteAllBoatsViaAPI, waitForHealthy"
        "Specs: auth, boat-list, boat-crud, accessibility (@axe-core/playwright), responsive"
        "Global setup performs health checks before any spec runs"
      )
      invariant="E2E uses the REAL stack — no mocked Keycloak, no API stubbing."
      verify="ai-scripts/checks/3/run.sh — playwright config, spec coverage, axe wiring, HTML report emitted"
      ;;
    4)
      source="ai-scripts/04-cicd.md   (prereq: ./ai-scripts/00d-bootstrap-azure.sh has been run)"
      artefacts=(
        "ci.yml: lint + OSV-Scanner v2.3.5 (HIGH+CRITICAL fail) + build (SpotBugs+FindSecBugs) + SBOM + e2e"
        "deploy-staging.yml: push to staging branch → auto deploy, no approval (images :staging + :staging-\${sha})"
        "deploy-production.yml: GitHub Release → manual deploy with reviewer (images :latest, :\${tag}, :production)"
        "terraform-plan.yml on infra PRs"
      )
      invariant="ALL Azure auth via OIDC federated credentials — zero long-lived AZURE_* admin secrets in repo."
      verify="ai-scripts/checks/4/run.sh — workflows present + parse, OIDC permissions declared, no hardcoded secrets"
      ;;
    4b)
      source="ai-scripts/04b-cicd-hardening.md   (prereq: phase 4 has been run)"
      artefacts=(
        "All \`uses:\` SHA-pinned (Dependabot bumps via tag comment)"
        "Concurrency groups on staging/production deploys (cancel-in-progress=false)"
        "cosign keyless OIDC sign + actions/attest-build-provenance (SLSA L3)"
        "Trivy SARIF on built images, fails HIGH+CRITICAL with fix"
        "CodeQL workflow (java-kotlin + javascript-typescript), weekly + PR; Semgrep fallback for private-without-GHAS"
        "gitleaks secret scan on every push/PR + .gitleaks.toml allowlist"
        "Terraform plan-as-artifact: plan job uploads tfplan, apply job downloads + applies verbatim"
        "Dependency-Track as pre-deploy gate (.github/actions/dtrack-gate/)"
        ".github/settings.yml + 00d-bootstrap-azure.sh apply_branch_protection()"
        ".github/dependabot.yml for actions + maven (×2) + npm"
      )
      invariant="Zero \`@v<digit>\` action pins remain — only \`@<sha> # v<...>\`. Production deploys cannot run an un-attested image."
      verify="ai-scripts/checks/4b/run.sh — SHA-pinning, signing/scanning/protection, tfplan-as-artifact, DT gate"
      ;;
    5)
      source="ai-scripts/05-documentation.md"
      artefacts=(
        "README.md — developer setup (local-intg + dev), architecture, stack, tests, API docs (Swagger UI)"
        "USER_GUIDE.md — end-user walkthrough (login demo/demo123, CRUD, troubleshooting)"
        "AI_USAGE.md — tools used, representative prompts verbatim, validation, what was NOT delegated"
      )
      invariant="No TODO/FIXME/XXX/console.log in committed code; no leaked secrets."
      verify="ai-scripts/checks/5/run.sh — required docs present, sections complete, source clean of debug noise"
      ;;
    *)
      return 0
      ;;
  esac

  echo -e "${YELLOW}┌──────────────────────────────────────────────────────────┐${NC}"
  echo -e "${YELLOW}│${NC}  ${BOLD}What this phase will do${NC}"
  echo -e "${YELLOW}│${NC}"
  echo -e "${YELLOW}│${NC}  ${BOLD}Source:${NC}  ${source}"
  echo -e "${YELLOW}│${NC}  ${BOLD}Produces:${NC}"
  for a in "${artefacts[@]}"; do
    echo -e "${YELLOW}│${NC}    • ${a}"
  done
  if [ -n "${invariant}" ]; then
    echo -e "${YELLOW}│${NC}  ${BOLD}Invariant:${NC} ${invariant}"
  fi
  echo -e "${YELLOW}│${NC}  ${BOLD}Verify:${NC}    ${verify}"
  echo -e "${YELLOW}└──────────────────────────────────────────────────────────┘${NC}"
  echo ""
}

# ── run_checkpoint <phase-id> <work-dir> ────────────────────────────────────
# Executes ai-scripts/checks/<phase-id>/run.sh against <work-dir>, renders
# a two-section human checkpoint box (AUTOMATED + HUMAN), and waits for
# Enter. Hard failures (severity=fail) trigger a HARD-FAIL banner; soft
# warnings (severity=warn) are shown but don't block. Set FORCE=1 to
# proceed past hard failures (not recommended).
run_checkpoint() {
  local phase="$1"
  local work_dir="${2:-${PROJECT_ROOT}}"
  local checks_dir="${SCRIPT_DIR}/checks/${phase}"
  local runner="${checks_dir}/run.sh"
  local human="${checks_dir}/human.md"

  if [ ! -x "$runner" ]; then
    print_warning "No checks/${phase}/run.sh — skipping automated verification."
    echo ""
    echo -e "${YELLOW}┌──────────────────────────────────────────────────────────┐${NC}"
    echo -e "${YELLOW}│${NC}  ${BOLD}🛑 HUMAN CHECKPOINT — phase ${phase}${NC}"
    echo -e "${YELLOW}│${NC}  No automated verification script for phase ${phase}."
    echo -e "${YELLOW}│${NC}  Press Enter when verified, or Ctrl+C to abort."
    echo -e "${YELLOW}└──────────────────────────────────────────────────────────┘${NC}"
    read -r
    return 0
  fi

  # Run the verification script; results are tee'd into a tmp file that
  # the box re-reads so output appears live AND in the summary.
  local result_file
  result_file="$(mktemp -t "boat-check-${phase}.XXXXXX")"
  trap 'rm -f "${result_file}"' RETURN
  echo ""
  local rc=0
  CHECK_RESULT_FILE="${result_file}" bash "${runner}" "${work_dir}" || rc=$?

  local fail_count
  fail_count="$(grep -c '^fail	' "${result_file}" 2>/dev/null || echo 0)"

  echo ""
  if [ "${fail_count}" -gt 0 ]; then
    echo -e "${RED}┌──────────────────────────────────────────────────────────┐${NC}"
    echo -e "${RED}│${NC}  ${BOLD}❌ HARD-FAIL${NC} — ${fail_count} automated check(s) failed"
    echo -e "${RED}│${NC}  Fix the issues above before proceeding."
    echo -e "${RED}│${NC}  Re-run with FORCE=1 to override (not recommended)."
    echo -e "${RED}└──────────────────────────────────────────────────────────┘${NC}"
    if [ "${FORCE:-0}" != "1" ]; then
      echo ""
      echo -e "${RED}Aborting phase ${phase}.${NC}"
      exit 1
    fi
    echo -e "${YELLOW}FORCE=1 set — proceeding despite failures.${NC}"
  fi

  echo ""
  echo -e "${YELLOW}┌──────────────────────────────────────────────────────────┐${NC}"
  echo -e "${YELLOW}│${NC}  ${BOLD}🛑 HUMAN CHECKPOINT — phase ${phase}${NC}"
  echo -e "${YELLOW}│${NC}"
  echo -e "${YELLOW}│${NC}  ${BOLD}AUTOMATED CHECKS${NC} (from checks/${phase}/run.sh):"
  if [ -s "${result_file}" ]; then
    local sev msg glyph color
    while IFS=$'\t' read -r sev msg; do
      case "$sev" in
        pass) glyph='✓'; color="${GREEN}" ;;
        warn) glyph='⚠'; color="${YELLOW}" ;;
        fail) glyph='✗'; color="${RED}" ;;
        info) glyph='·'; color="${CYAN}" ;;
        *)    glyph='?'; color="${NC}" ;;
      esac
      printf '%b│%b    %b%s%b %s\n' "${YELLOW}" "${NC}" "${color}" "${glyph}" "${NC}" "${msg}"
    done < "${result_file}"
  else
    echo -e "${YELLOW}│${NC}    (no results captured)"
  fi
  echo -e "${YELLOW}│${NC}"

  if [ -f "${human}" ]; then
    echo -e "${YELLOW}│${NC}  ${BOLD}HUMAN TO VERIFY${NC} (from checks/${phase}/human.md):"
    while IFS= read -r line; do
      [ -z "${line}" ] && continue
      case "${line}" in \#*) continue ;; esac
      echo -e "${YELLOW}│${NC}    ${line}"
    done < "${human}"
  else
    echo -e "${YELLOW}│${NC}  ${BOLD}HUMAN TO VERIFY${NC}: (no human.md for phase ${phase})"
  fi
  echo -e "${YELLOW}│${NC}"
  echo -e "${YELLOW}│${NC}  Press Enter when verified, or Ctrl+C to abort."
  echo -e "${YELLOW}└──────────────────────────────────────────────────────────┘${NC}"
  read -r
  rm -f "${result_file}"
  trap - RETURN
  return 0
}

# ── Run Claude Code in PLAN MODE ────────────────────────────────────────────
run_claude() {
  local work_dir="$1"
  local prompt_file="$2"
  local description="$3"

  print_step "Directory:  ${work_dir}"
  print_step "Prompt:     ${prompt_file}"
  print_step "Task:       ${description}"
  print_step "Mode:       ${BOLD}PLAN MODE${NC} (read-only first, then execute on approval)"
  echo ""

  if [ ! -d "${work_dir}" ]; then
    echo -e "${RED}ERROR: Directory ${work_dir} does not exist.${NC}"
    exit 1
  fi
  if [ ! -f "${prompt_file}" ]; then
    echo -e "${RED}ERROR: Prompt file ${prompt_file} not found.${NC}"
    exit 1
  fi

  cd "${work_dir}"
  echo -e "${CYAN}Launching Claude Code in Plan Mode...${NC}"
  echo ""

  # --permission-mode plan: Claude reads/analyzes first, proposes a plan,
  # then you approve before any file modifications happen.
  # The CLI takes the prompt as a positional arg, not a --prompt-file flag.
  claude --permission-mode plan "$(cat "${prompt_file}")"
}

case "${1:-help}" in

  # ═══════════════════════════ PHASE 0 ══════════════════════════════════════
  0|setup)
    print_header "PHASE 0 — Environment Setup"
    print_phase_intro 0
    bash "${SCRIPT_DIR}/00-bootstrap.sh"
    run_checkpoint 0 "${PROJECT_ROOT}"
    print_success "Phase 0 complete. Next: ./ai-scripts/run-phase.sh 0b"
    ;;

  # ═══════════════════════════ PHASE 0b ═════════════════════════════════════
  0b|initializr)
    print_header "PHASE 0b — Spring Initializr scaffold (bff + business-service)"
    print_phase_intro 0b
    bash "${SCRIPT_DIR}/00c-initializr.sh"
    run_checkpoint 0b "${PROJECT_ROOT}"
    print_success "Phase 0b complete. Next: ./ai-scripts/run-phase.sh 1"
    ;;

  # ═══════════════════════════ PHASE 0d (one-off pre-CI/CD) ═════════════════
  0d|azure-bootstrap)
    print_header "PHASE 0d — Azure + GitHub OIDC Bootstrap (one-off)"
    cat <<'EOF'
  ┌──────────────────────────────────────────────────────────┐
  │  What this phase will do                                 │
  │                                                          │
  │  Source:   ai-scripts/00d-bootstrap-azure.sh (shell)     │
  │  Produces:                                               │
  │    • Entra ID app + service principal                    │
  │    • 5 OIDC federated credentials (main / staging /      │
  │      PR / env-staging / env-production)                  │
  │    • Subscription Contributor + User Access Admin roles  │
  │    • Storage account + blob containers for Terraform     │
  │      remote state (staging, production)                  │
  │    • gh secret/variable set for AZURE_* / TF_STATE_* /   │
  │      ACR_NAME / PROJECT / LOCATION                       │
  │  Invariant: NO admin secrets land in the repo — Azure    │
  │             auth is OIDC-federated only.                 │
  │  Verify:   manual — enable "Required reviewers" on the   │
  │            production GitHub Environment (no API).       │
  └──────────────────────────────────────────────────────────┘
EOF
    echo ""
    shift || true
    bash "${SCRIPT_DIR}/00d-bootstrap-azure.sh" "$@"
    print_success "Phase 0d complete. Next: ./ai-scripts/run-phase.sh 1 (or skip to 4 if contract+code exist)"
    ;;

  # ═══════════════════════════ PHASE 1 ══════════════════════════════════════
  1|contract)
    print_header "PHASE 1 — OpenAPI Contract"
    print_phase_intro 1
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/01-openapi-contract.md" \
      "Generate OpenAPI 3.0 Boat CRUDL contract"
    run_checkpoint 1 "${PROJECT_ROOT}"
    print_success "Phase 1 complete. Next: ./ai-scripts/run-phase.sh 02a1"
    ;;

  # ═══════════════════════════ PHASE 2A — Backend ═══════════════════════════
  02a1)
    print_header "2A.1 — Backend: Scaffold (hexagonal + 4 profiles)"
    print_phase_intro 02a1
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02a1-backend-scaffold.md" \
      "Scaffold Spring Boot 4 with hexagonal architecture + dev/local-intg/staging/prod profiles"
    run_checkpoint 02a1 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02a2"
    ;;
  02a2)
    print_header "2A.2 — Backend: Domain layer (pure Java, no Spring)"
    print_phase_intro 02a2
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02a2-backend-domain.md" \
      "Domain model + ports (pure Java) + persistence adapters + Liquibase"
    run_checkpoint 02a2 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02a3"
    ;;
  02a3)
    print_header "2A.3 — Backend: Service + Controller + Audit"
    print_phase_intro 02a3
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02a3-backend-service.md" \
      "Use cases, web adapter (controller), ETag, audit, user sync"
    run_checkpoint 02a3 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02a4"
    ;;
  02a4)
    print_header "2A.4 — Backend: Auth (session + dev bypass)"
    print_phase_intro 02a4
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02a4-backend-auth.md" \
      "oauth2Login, session cookie, CSRF, dev profile bypass, Keycloak realm"
    run_checkpoint 02a4 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02a5"
    ;;
  02a5)
    print_header "2A.5 — Backend: Tests (ArchUnit + integration)"
    print_phase_intro 02a5
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02a5-backend-tests.md" \
      "ArchUnit hexagonal tests + integration tests"
    run_checkpoint 02a5 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02a6"
    ;;
  02a6)
    print_header "2A.6 — Backend: Security gates (SAST / SBOM / SCA / DT upload)"
    print_phase_intro 02a6
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02a6-backend-security.md" \
      "SpotBugs+FindSecBugs (SAST) + CycloneDX (SBOM) + Dependency-Track upload + OSV profile"
    run_checkpoint 02a6 "${PROJECT_ROOT}"
    print_success "Backend track done. Next: ./ai-scripts/run-phase.sh 02b1"
    ;;

  # ═══════════════════════════ PHASE 2B — Frontend ══════════════════════════
  02b1)
    print_header "2B.1 — Frontend: Scaffold"
    print_phase_intro 02b1
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02b1-frontend-scaffold.md" \
      "Vue 3 + Headless UI + session auth (no OAuth lib)"
    run_checkpoint 02b1 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02b2"
    ;;
  02b2)
    print_header "2B.2 — Frontend: Auth UX"
    print_phase_intro 02b2
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02b2-frontend-auth.md" \
      "authStore, login redirect, logout, session expiry"
    run_checkpoint 02b2 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02b3"
    ;;
  02b3)
    print_header "2B.3 — Frontend: Boat list"
    print_phase_intro 02b3
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02b3-frontend-list.md" \
      "Paginated list + search + loading/error states"
    run_checkpoint 02b3 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02b4"
    ;;
  02b4)
    print_header "2B.4 — Frontend: CRUD pages"
    print_phase_intro 02b4
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02b4-frontend-crud.md" \
      "Create, edit, detail, delete + 409 conflict"
    run_checkpoint 02b4 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02b5"
    ;;
  02b5)
    print_header "2B.5 — Frontend: Polish"
    print_phase_intro 02b5
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02b5-frontend-polish.md" \
      "Dark mode, WCAG AA, i18n EN/FR"
    run_checkpoint 02b5 "${PROJECT_ROOT}"
    print_success "Frontend track done. Next: ./ai-scripts/run-phase.sh 02c1"
    ;;

  # ═══════════════════════════ PHASE 2C — Infra ═════════════════════════════
  02c1)
    print_header "2C.1 — Docker Compose — dev (2 svc) + local-intg (4 svc)"
    print_phase_intro 02c1
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02c1-docker.md" \
      "Docker Compose: docker-compose.dev.yml (dev) + docker-compose.yml (local-intg)"
    run_checkpoint 02c1 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02c2"
    ;;
  02c2)
    print_header "2C.2 — Terraform (5 modules, ACA, Liquibase Jobs, OIDC)"
    print_phase_intro 02c2
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02c2-terraform.md" \
      "Azure Terraform modules"
    run_checkpoint 02c2 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 02c3"
    ;;
  02c3)
    print_header "2C.3 — Ansible (orchestrated deploy.yml, VNet-resident runner)"
    print_phase_intro 02c3
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/02c3-ansible.md" \
      "Ansible playbooks for Azure"
    run_checkpoint 02c3 "${PROJECT_ROOT}"
    print_success "Infra track done. Next: ./ai-scripts/run-phase.sh 3"
    ;;

  # ═══════════════════════════ PHASES 3-5 ═══════════════════════════════════
  3|e2e)
    print_header "PHASE 3 — E2E Tests"
    print_phase_intro 3
    cd "${PROJECT_ROOT}" && docker compose up -d 2>/dev/null || true
    sleep 15
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/03-e2e-tests.md" \
      "Playwright E2E tests"
    run_checkpoint 3 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 4"
    ;;

  4|cicd)
    print_header "PHASE 4 — CI/CD"
    print_phase_intro 4
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/04-cicd.md" \
      "GitHub Actions CI/CD → Azure"
    run_checkpoint 4 "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 4b"
    ;;

  4b|cicd-hardening)
    print_header "PHASE 4b — CI/CD Hardening"
    print_phase_intro 4b
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/04b-cicd-hardening.md" \
      "Hardening: SHA-pin + cosign + SLSA + Trivy + CodeQL + gitleaks + tfplan-artifact + DT gate + branch protection"
    run_checkpoint 4b "${PROJECT_ROOT}"
    print_success "Next: ./ai-scripts/run-phase.sh 5"
    ;;

  5|docs)
    print_header "PHASE 5 — Documentation"
    print_phase_intro 5
    run_claude "${PROJECT_ROOT}" "${PROMPTS_DIR}/05-documentation.md" \
      "README + AI_USAGE.md"
    run_checkpoint 5 "${PROJECT_ROOT}"
    echo -e "  ${GREEN}${BOLD}🎉 THE BOAT APP IS COMPLETE!${NC}"
    echo "  Push to GitHub → Send link to OWT"
    ;;

  help|*)
    echo ""
    echo -e "${BOLD}The Boat App — Master Orchestrator${NC}"
    echo -e "  Every step runs Claude Code in ${BOLD}PLAN MODE${NC}."
    echo -e "  All phases run from the project root: ${PROJECT_ROOT}"
    echo ""
    echo -e "${BOLD}Usage:${NC} ./ai-scripts/run-phase.sh <step>"
    echo -e "        ${BOLD}FORCE=1${NC} ./ai-scripts/run-phase.sh <step>   (override hard-fail at a checkpoint — not recommended)"
    echo ""
    echo "  0          Setup (hexagonal structure + 4-env profiles)"
    echo "  0b         Spring Initializr scaffold (bff + business-service: Boot 4.0.6, Java 25)"
    echo "  0d         Azure + GitHub OIDC bootstrap (one-off, before phase 4)"
    echo "  1          OpenAPI contract"
    echo ""
    echo -e "  ${BOLD}Phase 2${NC} — run sequentially in a single terminal:"
    echo "    02a1..02a6   Backend (bff + business-service, incl. security gates)"
    echo "    02b1..02b5   Frontend"
    echo "    02c1..02c3   Infra (docker / terraform / ansible)"
    echo ""
    echo "  3 (e2e)    4 (cicd)    4b (cicd-hardening)    5 (docs)"
    echo ""
    echo -e "  ${BOLD}Helper script (not a phase):${NC}"
    echo "    ./ai-scripts/00b-generate-bff-key.sh   Generate the BFF RSA signing key"
    echo "                                           (PEM at ./infra/docker/keys/bff-signing-key.pem)"
    echo "                                           Required for local-intg integration tests (02a4/02a5)."
    echo ""
    ;;
esac
