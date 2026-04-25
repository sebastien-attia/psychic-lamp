#!/usr/bin/env bash
# Phase 02b3 — Frontend boat list (paginated + search + loading/error/empty states)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02b3" "Frontend Boat List"

cd frontend 2>/dev/null || { fail "frontend/ directory missing"; check_summary; exit 1; }

if command -v npm >/dev/null; then
  run_check "npm run type-check" -- npm run type-check
  run_check "npm run build"      -- npm run build
fi

# ── List UX: pagination, search, skeleton, empty, error ─────────────────────
for token in pagination search skeleton empty error; do
  if grep -rqi "${token}" src/ 2>/dev/null; then
    pass "UX token present: ${token}"
  else
    warn "UX token missing: ${token} (list should cover pagination/search/skeleton/empty/error)"
  fi
done

# ── Debounced search ────────────────────────────────────────────────────────
if grep -rqE 'debounce|useDebounce' src/ 2>/dev/null; then
  pass "search input is debounced"
else
  warn "no debounce utility referenced — search may fire on every keystroke"
fi

# ── URL query-string sync (page/size/search) ────────────────────────────────
if grep -rqE 'router\.(push|replace)\(.*query' src/ 2>/dev/null \
   || grep -rqE 'useRoute\(\)\.query' src/ 2>/dev/null; then
  pass "URL query params synced to list state"
else
  warn "list state not synced to URL — refresh loses pagination/search"
fi

info "cross-service integration — deferred to phase 3 (e2e)"

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
