#!/usr/bin/env bash
# Phase 02c1 — Docker Compose (dev + local-intg) + Dockerfiles
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02c1" "Docker Compose"

# ── Required compose files ─────────────────────────────────────────────────
[ -f docker-compose.yml ]      && pass "docker-compose.yml present"      || fail "docker-compose.yml missing"
[ -f docker-compose.dev.yml ]  && pass "docker-compose.dev.yml present"  || fail "docker-compose.dev.yml missing"

# ── Syntactic validation ───────────────────────────────────────────────────
if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  run_check "docker compose config (local-intg)" -- docker compose config -q
  if [ -f docker-compose.dev.yml ]; then
    run_check "docker compose config (dev)" -- docker compose -f docker-compose.dev.yml config -q
  fi
else
  warn "docker compose CLI not available — skipping config validation"
fi

# ── services for local-intg: bff/business/postgres/keycloak + keycloak-config ─
if [ -f docker-compose.yml ]; then
  for svc in bff business-service postgres keycloak; do
    if grep -qE "^  ${svc}:|^  ${svc}-" docker-compose.yml; then
      pass "docker-compose.yml defines ${svc}"
    else
      fail "docker-compose.yml missing service: ${svc}"
    fi
  done

  # keycloak-config sidecar (realm config via keycloak-config-cli)
  if grep -qE '^  keycloak-config:' docker-compose.yml; then
    pass "docker-compose.yml defines keycloak-config sidecar"
    # Must depend on keycloak being healthy, must mount infra/keycloak
    if awk '/^  keycloak-config:/{f=1;next} /^  [a-z]/{f=0} f' docker-compose.yml \
        | grep -qE 'condition:[[:space:]]*service_healthy'; then
      pass "keycloak-config depends_on keycloak { condition: service_healthy }"
    else
      fail "keycloak-config must depend_on keycloak with condition: service_healthy"
    fi
    if awk '/^  keycloak-config:/{f=1;next} /^  [a-z]/{f=0} f' docker-compose.yml \
        | grep -qE 'infra/keycloak[^[:space:]]*:/config'; then
      pass "keycloak-config mounts infra/keycloak into /config"
    else
      fail "keycloak-config does not mount infra/keycloak as /config"
    fi
    if awk '/^  keycloak-config:/{f=1;next} /^  [a-z]/{f=0} f' docker-compose.yml \
        | grep -qE 'IMPORT_FILES_LOCATIONS:[[:space:]]*/config/realm\.yaml'; then
      pass "keycloak-config imports /config/realm.yaml"
    else
      fail "keycloak-config IMPORT_FILES_LOCATIONS must point at /config/realm.yaml"
    fi
  else
    fail "docker-compose.yml missing keycloak-config sidecar (replaces --import-realm)"
  fi

  # Legacy: keycloak must NOT use --import-realm any more (drift risk).
  # Strip comments first so an explanatory comment that names the flag
  # does not trip the gate.
  if sed 's/#.*$//' docker-compose.yml \
      | grep -qE 'start-dev[[:space:]]*--import-realm|--import-realm'; then
    fail "keycloak still uses --import-realm — migrate to keycloak-config-cli sidecar"
  else
    pass "keycloak no longer uses --import-realm (config-cli owns realm config)"
  fi

  # Keycloak version pin: must be 26.6.x or newer
  if grep -qE 'keycloak/keycloak:(26\.[6-9]|[3-9][0-9])' docker-compose.yml; then
    pass "Keycloak pinned to 26.6+ in docker-compose.yml"
  elif grep -qE 'keycloak/keycloak:' docker-compose.yml; then
    fail "Keycloak pin is stale (< 26.6) — bump to 26.6.1 or newer"
  fi
fi

# ── Security: no bare :latest tags (allow :latest-<version> patterns) ──────
# Adorsys keycloak-config-cli uses tags like `latest-26.6.1` — version-pinned.
# We only fail on a bare `:latest` (no trailing hyphen+version).
if grep -rIE ':latest([^-A-Za-z0-9]|$)' docker-compose*.yml 2>/dev/null | grep -v '^\s*#' >/dev/null; then
  fail "bare :latest image tag used — pin a version"
else
  pass "no bare :latest image tags in compose files (version-pinned latest-<v> is OK)"
fi

if grep -q 'healthcheck' docker-compose.yml 2>/dev/null; then
  pass "healthcheck declared in docker-compose.yml"
else
  fail "no healthcheck in docker-compose.yml — services won't wait for dependencies"
fi

# ── Dockerfiles present ────────────────────────────────────────────────────
[ -f bff/Dockerfile ]              && pass "bff/Dockerfile present"              || fail "bff/Dockerfile missing"
[ -f business-service/Dockerfile ] && pass "business-service/Dockerfile present" || fail "business-service/Dockerfile missing"

# BFF Dockerfile: 4-stage (TS codegen + Node + JDK + JRE)
if [ -f bff/Dockerfile ]; then
  stages="$(grep -cE '^FROM ' bff/Dockerfile)"
  if [ "${stages}" -ge 4 ]; then
    pass "bff/Dockerfile is multi-stage (${stages} FROM)"
  else
    fail "bff/Dockerfile has only ${stages} FROM stages (expected 4: ts-codegen + Node frontend + JDK BFF + JRE runtime — see 02c1-docker.md step 1)"
  fi

  # Toolchain consistency guard. The OpenAPI Generator CLI is a Node wrapper
  # that shells out to `java -jar`, so a `node:*-alpine` stage running
  # `npm run build` (which chains `generate:api`) will fail with
  # "/bin/sh: java: not found" at build time — the regression that added
  # this gate. The fix is a dedicated codegen stage
  # (openapitools/openapi-generator-cli) that produces the TS client, COPYed
  # into the Node stage, which then runs `npm run build:no-codegen`.
  #
  # Flat repo-wide grep (instead of a FROM-RUN pairing awk): the codegen
  # image never invokes `npm`, so any `npm run build` token in the file
  # belongs to a Node stage. This matches both shell-form
  # (`RUN npm run build`) and JSON exec-form (`RUN ["npm","run","build"]`),
  # and is robust to `FROM --platform=…` flags and digest pins on the FROM
  # line (which the prior awk-based pairing missed).
  if grep -nE '(^|[^:[:alnum:]])npm["[:space:],]+run["[:space:],]+build([^:[:alnum:]]|$)' bff/Dockerfile \
       | grep -v 'build:no-codegen' >/dev/null; then
    fail "bff/Dockerfile: 'npm run build' invoked (chains generate:api → java in the Node stage) — switch to 'npm run build:no-codegen' and add a ts-codegen stage (see 02c1-docker.md step 1)"
  else
    pass "bff/Dockerfile: no 'npm run build' token (Java-free Node stage)"
  fi

  # `--platform=…` flags and digest pins are tolerated on the codegen FROM.
  if grep -qE '^FROM ([[:space:]]*--[^[:space:]]+[[:space:]]+)*([[:alnum:].]+/)?openapitools/openapi-generator-cli' bff/Dockerfile; then
    pass "bff/Dockerfile: dedicated ts-codegen stage present (openapitools/openapi-generator-cli)"
  else
    fail "bff/Dockerfile: no openapitools/openapi-generator-cli stage — TS client must be generated outside the Node stage"
  fi
fi

# ── Codegen single-source-of-truth ─────────────────────────────────────────
# Without a shared config file the typescript-axios flag list lives in two
# places (frontend/package.json AND bff/Dockerfile) and silently drifts.
if [ -f contracts/codegen-typescript-axios.json ]; then
  pass "contracts/codegen-typescript-axios.json present (shared codegen config)"
  if grep -q 'codegen-typescript-axios.json' frontend/package.json 2>/dev/null \
     && grep -q 'codegen-typescript-axios.json' bff/Dockerfile 2>/dev/null; then
    pass "codegen config referenced by both frontend/package.json and bff/Dockerfile"
  else
    fail "contracts/codegen-typescript-axios.json exists but is not referenced by both frontend/package.json AND bff/Dockerfile — flags will drift"
  fi
else
  fail "contracts/codegen-typescript-axios.json missing — flags duplicated between npm and Docker (see 02b1-frontend-scaffold.md step 4)"
fi

# ── End-to-end build gate (the missing check) ──────────────────────────────
# Structural greps on the Dockerfile cannot prove buildability — only an
# actual `docker build` can. This is slow (~30-90s warm cache, several
# minutes cold), so opt out via SKIP_DOCKER_BUILD=1 for fast iterations.
# Phase completion REQUIRES it green; CI sets SKIP_DOCKER_BUILD=0.
if [ "${SKIP_DOCKER_BUILD:-0}" = "1" ]; then
  warn "SKIP_DOCKER_BUILD=1 — skipping 'docker build -f bff/Dockerfile .' (must be green before phase is done)"
elif command -v docker >/dev/null && [ -f bff/Dockerfile ]; then
  run_check "bff image builds end-to-end (docker build -f bff/Dockerfile .)" \
    -- docker build -q -t boatapp/bff:checks -f bff/Dockerfile .
else
  warn "docker CLI not available — skipping bff image build gate"
fi

# Non-root user in runtime stage
for df in bff/Dockerfile business-service/Dockerfile; do
  [ -f "${df}" ] || continue
  if grep -qE '^USER\s+' "${df}"; then
    pass "${df}: USER directive present (non-root)"
  else
    fail "${df}: no USER directive — containers will run as root"
  fi
done

# ── PostgreSQL init script (creates 3 DBs + 3 roles) ───────────────────────
if [ -f infra/postgres/init/01-init-dbs.sh ]; then
  pass "infra/postgres/init/01-init-dbs.sh present"
  if [ -x infra/postgres/init/01-init-dbs.sh ]; then
    pass "infra/postgres/init/01-init-dbs.sh is executable (docker-entrypoint requires +x)"
  else
    fail "infra/postgres/init/01-init-dbs.sh must be executable (chmod +x)"
  fi
  for dbname in bff_session boatapp keycloak; do
    if grep -q "DATABASE ${dbname}" infra/postgres/init/01-init-dbs.sh; then
      pass "init script creates database: ${dbname}"
    else
      fail "init script does not create database: ${dbname}"
    fi
  done
  for role in bff business_service keycloak; do
    if grep -qE "CREATE ROLE ${role}\b" infra/postgres/init/01-init-dbs.sh; then
      pass "init script creates role: ${role}"
    else
      fail "init script does not create role: ${role}"
    fi
  done
else
  fail "infra/postgres/init/01-init-dbs.sh missing — required for 3-DB bootstrap"
fi

# postgres service must mount the init directory
if grep -qE '\./infra/postgres/init:/docker-entrypoint-initdb.d' docker-compose.yml 2>/dev/null; then
  pass "docker-compose.yml mounts infra/postgres/init into postgres container"
else
  fail "docker-compose.yml must mount ./infra/postgres/init:/docker-entrypoint-initdb.d:ro on postgres"
fi
if grep -qE '\./infra/postgres/init:/docker-entrypoint-initdb.d' docker-compose.dev.yml 2>/dev/null; then
  pass "docker-compose.dev.yml mounts infra/postgres/init into postgres-dev container"
else
  fail "docker-compose.dev.yml must mount ./infra/postgres/init:/docker-entrypoint-initdb.d:ro on postgres-dev"
fi

# ── Secrets not in compose files ───────────────────────────────────────────
if grep -rE 'CLIENT_SECRET:[[:space:]]*[A-Za-z0-9]{8,}' docker-compose*.yml 2>/dev/null >/dev/null; then
  fail "client_secret literal in compose files — use Docker secret / env file"
else
  pass "no client_secret literals in compose files"
fi

# ── Makefile convenience ───────────────────────────────────────────────────
if [ -f Makefile ]; then
  for tgt in dev up down test; do
    if grep -qE "^${tgt}:" Makefile; then
      pass "Makefile target: ${tgt}"
    else
      warn "Makefile missing target: ${tgt}"
    fi
  done
else
  warn "no Makefile — convenience commands missing"
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
