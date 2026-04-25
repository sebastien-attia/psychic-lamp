#!/usr/bin/env bash
# Phase 02b2 — Frontend auth UX (authStore + 401 redirect + logout)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02b2" "Frontend Auth"

cd frontend 2>/dev/null || { fail "frontend/ directory missing"; check_summary; exit 1; }

# ── Core: build + type-check ────────────────────────────────────────────────
if command -v npm >/dev/null; then
  run_check "npm run type-check" -- npm run type-check
  run_check "npm run build"      -- npm run build
fi

# ── authStore, fetchUser, login, logout wired ───────────────────────────────
if grep -rqE 'defineStore\(["'\'']auth' src/ 2>/dev/null \
   || grep -rqE 'authStore|useAuthStore' src/ 2>/dev/null; then
  pass "authStore present"
else
  fail "authStore (Pinia) not found in src/"
fi

for fn in fetchUser login logout; do
  if grep -rq "${fn}" src/ 2>/dev/null; then
    pass "auth flow references: ${fn}"
  else
    warn "auth flow missing reference: ${fn}"
  fi
done

# ── GET /api/me on app mount ────────────────────────────────────────────────
if grep -rqE '/api/me' src/ 2>/dev/null; then
  pass "/api/me endpoint wired"
else
  fail "/api/me not referenced in src/ (needed for session bootstrap)"
fi

# ── 401 interceptor redirects to login ──────────────────────────────────────
if grep -rqE 'status\s*===?\s*401' src/ 2>/dev/null; then
  pass "401 handling present"
else
  warn "no 401 handling found — session expiry won't redirect to login"
fi

# ── Security: NO tokens persisted in localStorage / sessionStorage ──────────
if grep -rqnE '(localStorage|sessionStorage)\.\w+\(.*(access_?token|id_?token|refresh_?token)' src/ 2>/dev/null; then
  fail "tokens referenced for localStorage/sessionStorage — must stay in session cookie only"
else
  pass "no tokens persisted in browser storage"
fi

# ── Contract: generated client wired into authStore ─────────────────────────
if grep -rqE 'api-client/generated' src/ 2>/dev/null; then
  pass "generated API client imported from src/"
else
  warn "generated API client not imported — authStore may use hand-rolled fetch"
fi

info "cross-service integration (frontend↔BFF login) — deferred to phase 3 (e2e)"

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
