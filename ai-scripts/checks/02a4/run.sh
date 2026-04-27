#!/usr/bin/env bash
# Phase 02a4 — Backend Auth (session + JWT + private_key_jwt)
#
# Usage: run.sh <work-dir>    (work-dir = project root)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }

check_init "02a4" "Backend Auth"

# ── Core: compile both services ─────────────────────────────────────────────
if [ -d bff ] && [ -x bff/mvnw ]; then
  run_check "bff compiles" -- bash -c 'cd bff && ./mvnw -q compile'
else
  fail "bff/ or bff/mvnw missing"
fi
if [ -d business-service ] && [ -x business-service/mvnw ]; then
  run_check "business-service compiles" -- bash -c 'cd business-service && ./mvnw -q compile'
else
  fail "business-service/ or business-service/mvnw missing"
fi

# ── Security: BFF signing-key PEM is readable only by owner ─────────────────
KEY_PATH="${BFF_SIGNING_KEY_PATH:-infra/docker/keys/bff-signing-key.pem}"
if [ -f "${KEY_PATH}" ]; then
  perms="$(stat -c '%a' "${KEY_PATH}" 2>/dev/null || stat -f '%Lp' "${KEY_PATH}")"
  if [ "${perms}" = "600" ] || [ "${perms}" = "400" ]; then
    pass "BFF signing key perms ${perms} (${KEY_PATH})"
  else
    fail "BFF signing key perms ${perms} at ${KEY_PATH} — must be 600 or 400"
  fi
else
  warn "BFF signing key not present at ${KEY_PATH} (created on first \`docker compose up\` by the bff-keygen init service; for IDE-run BFF use ai-scripts/00b-generate-bff-key.sh)"
fi

# ── Security: SecurityConfigs exist and carry the right @Profile ────────────
if grep -rq '@Profile("dev")' bff/src/main/java/ 2>/dev/null \
   || grep -rq '@Profile("dev")' business-service/*/src/main/java/ 2>/dev/null; then
  pass "Dev security config annotated @Profile(\"dev\")"
else
  warn "No @Profile(\"dev\") security config found"
fi

if grep -rq '@Profile("!dev")' business-service/*/src/main/java/ 2>/dev/null \
   || grep -rq '@Profile("!dev")' bff/src/main/java/ 2>/dev/null; then
  pass "Production security config annotated @Profile(\"!dev\")"
else
  warn "No @Profile(\"!dev\") security config found (JWT/oauth2Login may be active in dev)"
fi

# ── Security: no plaintext client_secret in sources / configs ───────────────
if grep -rIn --include='application*.yml' --include='application*.yaml' \
     -E 'client[-_]?secret:[[:space:]]*[[:alnum:]]' bff business-service 2>/dev/null \
     | grep -v '^\s*#' >/dev/null; then
  fail "client_secret literal present in application-*.yml — must use private_key_jwt"
else
  pass "No client_secret literal in application-*.yml"
fi

# ── Canonical realm.yaml present and clean ──────────────────────────────────
REALM="infra/keycloak/realm.yaml"
if [ -f "${REALM}" ]; then
  pass "canonical ${REALM} present"
  # Hard-fail on any secret-bearing field — realm.yaml is hand-authored; exports
  # must be redacted before commit (see 02a4-backend-auth.md step 4).
  if grep -InE '^[[:space:]]*(secret|salt|hashIterations|hashedSaltedValue|keystore)[[:space:]]*:' "${REALM}" >/dev/null; then
    fail "${REALM} contains secret-bearing fields (secret/salt/hashIterations/hashedSaltedValue/keystore) — redact before commit"
  else
    pass "${REALM} has no secret-bearing fields"
  fi
  # Must use client-jwt (private_key_jwt)
  if grep -q 'clientAuthenticatorType:[[:space:]]*client-jwt' "${REALM}"; then
    pass "${REALM}: boat-app-confidential uses client-jwt (private_key_jwt)"
  else
    fail "${REALM}: clientAuthenticatorType is not client-jwt"
  fi
  if grep -q 'use\.jwks\.url:[[:space:]]*"true"' "${REALM}"; then
    pass "${REALM}: use.jwks.url=true (Keycloak refetches public key)"
  else
    fail "${REALM}: use.jwks.url not set to true"
  fi
else
  warn "${REALM} not present yet — will be created in 02c1 step 0"
fi

# ── Legacy realm-export.json must NOT exist (drift risk) ────────────────────
if [ -f infra/docker/keycloak/realm-export.json ]; then
  warn "infra/docker/keycloak/realm-export.json still present — migrate to ${REALM} (single source of truth)"
fi

# ── Security: JwksController exposes public key only (no d/p/q in template) ─
JWKS_FILE="$(find bff/src/main/java -name 'JwksController.java' 2>/dev/null | head -1)"
if [ -n "${JWKS_FILE}" ]; then
  if grep -Eq '"d"|"p"|"q"|getPrivateExponent|toPrivateKey' "${JWKS_FILE}"; then
    fail "JwksController references private RSA fields (d/p/q)"
  else
    pass "JwksController exposes public JWK fields only"
  fi
else
  warn "JwksController.java not found (BFF must publish /.well-known/jwks.json)"
fi

# ── Security: JwtClientAuthenticationParametersConverter wired ──────────────
if grep -rq 'JwtClientAuthenticationParametersConverter' bff/src/main/java/ 2>/dev/null; then
  pass "BFF wires JwtClientAuthenticationParametersConverter (private_key_jwt)"
else
  fail "BFF does not reference JwtClientAuthenticationParametersConverter — client_assertion won't be sent"
fi

# ── Integration smoke: dev-mode business-service boot ───────────────────────
# Only runs if the service scaffold exists, curl is present, and :8081 is free.
if [ ! -f business-service/pom.xml ] || [ ! -x business-service/mvnw ]; then
  info "business-service not scaffolded yet — skipping dev-mode smoke"
elif ! command -v curl >/dev/null; then
  info "curl not available — skipping dev-mode smoke"
else
  if command -v lsof >/dev/null && lsof -iTCP:8081 -sTCP:LISTEN >/dev/null 2>&1; then
    info "port 8081 already in use — skipping dev-mode boot smoke"
  elif ! bash -c '(exec 3<>/dev/tcp/localhost/5432) 2>/dev/null'; then
    # Liquibase opens a JDBC connection before the Spring context finishes
    # refreshing, so dev profile cannot start without a database — even
    # though DevSecurityConfig bypasses auth. The probe runs in a fresh
    # `bash -c` so the test socket FD cannot leak into the surrounding
    # script (and from there into the spring-boot:run child).
    info "postgres not reachable on localhost:5432 — skipping dev-mode boot smoke"
    info "  → start it with: docker compose -f docker-compose.dev.yml up -d postgres-dev"
    info "  → (docker-compose.dev.yml is created in phase 02c1; then re-run this check)"
  else
    info "starting business-service (dev profile) for smoke test…"
    LOG="$(mktemp -t boat-02a4-bs.XXXXXX.log)"
    (
      cd business-service && SPRING_PROFILES_ACTIVE=dev ./mvnw -q spring-boot:run
    ) >"${LOG}" 2>&1 &
    BS_PID=$!
    # Wait up to 60s for health
    HEALTHY=0
    for _ in $(seq 1 60); do
      if curl -fsS http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
        HEALTHY=1; break
      fi
      sleep 1
    done
    if [ "${HEALTHY}" = "1" ]; then
      pass "business-service /actuator/health UP (dev profile)"
      if curl -fsS http://localhost:8081/api/v1/boats | grep -qE '^\[\]?|^\{'; then
        pass "GET /api/v1/boats returns 200 in dev (no auth required)"
      else
        warn "GET /api/v1/boats did not return a valid JSON body in dev"
      fi
      if grep -qiE 'ERROR|Exception' "${LOG}"; then
        warn "business-service startup log contains ERROR/Exception lines — review ${LOG}"
      else
        pass "business-service startup log has no ERROR/Exception lines"
      fi
    else
      fail "business-service did not become healthy on :8081 within 60s — see ${LOG}"
    fi
    kill "${BS_PID}" 2>/dev/null || true
    wait "${BS_PID}" 2>/dev/null || true
  fi
fi

# ── Integration: if local-intg compose is up, check private_key_jwt usage ───
if docker compose ps bff 2>/dev/null | grep -q 'Up\|running'; then
  if docker compose logs bff 2>/dev/null | grep -Eq 'client_assertion(_type)?='; then
    pass "BFF sent client_assertion to Keycloak (private_key_jwt)"
  else
    warn "No client_assertion seen in BFF logs — either no login yet or not configured"
  fi
  if docker compose logs bff 2>/dev/null | grep -Eiq 'client_secret=[^&]+'; then
    fail "client_secret=... observed in BFF logs — should never be on the wire"
  else
    pass "No client_secret leakage in BFF logs"
  fi
  if command -v curl >/dev/null && curl -fsS http://localhost:8080/.well-known/jwks.json 2>/dev/null \
       | grep -Eq '"d"|"p"|"q"'; then
    fail "/.well-known/jwks.json exposes private fields"
  elif command -v curl >/dev/null && curl -fsS http://localhost:8080/.well-known/jwks.json >/dev/null 2>&1; then
    pass "/.well-known/jwks.json returns public-only JWK"
  else
    info "jwks endpoint not reachable (local-intg stack not fully up) — rerun after docker compose up"
  fi
else
  info "local-intg compose not running — BFF↔Keycloak smoke deferred (run docker compose up then retry)"
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
