#!/usr/bin/env bash
# Phase 02b1 — Frontend scaffold (Vue 3 + Headless UI + session auth)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02b1" "Frontend Scaffold"

cd frontend 2>/dev/null || { fail "frontend/ directory missing"; check_summary; exit 1; }

# ── Core: generate + build + type-check ─────────────────────────────────────
if [ -f package.json ] && command -v npm >/dev/null; then
  if grep -q '"generate:api"' package.json; then
    run_check "npm run generate:api" -- npm run generate:api
  fi
  run_check "npm run type-check" -- npm run type-check
  run_check "npm run build" -- npm run build
else
  fail "frontend/package.json missing or npm not available"
fi

# ── Security: no OAuth client libraries (session-based only) ────────────────
forbidden_libs=('oidc-client-ts' '@react-oauth/google' 'oauth4webapi' 'oidc-client')
for lib in "${forbidden_libs[@]}"; do
  if grep -q "\"${lib}\"" package.json 2>/dev/null; then
    fail "forbidden OAuth library declared: ${lib}"
  fi
done
pass "no OAuth client libraries in package.json"

# ── Security: no hardcoded http://localhost URLs outside proxy config ───────
if grep -rInE 'http://localhost:(808[01]|5173)' src/ 2>/dev/null | grep -v '// proxy\|// dev' >/dev/null; then
  warn "hardcoded http://localhost URLs found in src/ — use Vite proxy instead"
else
  pass "no hardcoded localhost URLs in src/"
fi

# ── Contract: generated API client present ──────────────────────────────────
if [ -d src/services/api-client/generated ]; then
  pass "generated API client present under src/services/api-client/generated"
elif find src -type d -name generated 2>/dev/null | grep -q .; then
  pass "generated client directory present"
else
  warn "no generated API client directory found (expected after generate:api)"
fi

# ── Vite proxy for both modes ───────────────────────────────────────────────
if [ -f vite.config.ts ] || [ -f vite.config.js ]; then
  if grep -qE 'proxy' vite.config.{ts,js} 2>/dev/null; then
    pass "Vite proxy configured"
  else
    fail "Vite proxy not configured (needs /api routing to :8081 dev / :8080 intg)"
  fi
fi

# ── Axios with credentials + CSRF cookie ────────────────────────────────────
if grep -rqE 'withCredentials:\s*true' src/ 2>/dev/null; then
  pass "Axios uses withCredentials: true (session cookie support)"
else
  fail "Axios not configured with withCredentials: true — session cookie will not be sent"
fi

# ── Reproducibility: lockfile committed ────────────────────────────────────
if [ -f package-lock.json ] || [ -f pnpm-lock.yaml ] || [ -f yarn.lock ]; then
  pass "lockfile committed"
else
  fail "no lockfile — builds will not be reproducible"
fi

info "cross-service integration (frontend↔BFF) — deferred to phase 3 (e2e)"

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
