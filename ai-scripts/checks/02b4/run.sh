#!/usr/bin/env bash
# Phase 02b4 — Frontend CRUD pages (create/edit/delete + 409 conflict)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02b4" "Frontend CRUD"

cd frontend 2>/dev/null || { fail "frontend/ directory missing"; check_summary; exit 1; }

if command -v npm >/dev/null; then
  run_check "npm run type-check" -- npm run type-check
  run_check "npm run build"      -- npm run build
fi

# ── If-Match header on PUT ──────────────────────────────────────────────────
if grep -rqE "[Ii]f-[Mm]atch" src/ 2>/dev/null; then
  pass "If-Match header used on updates"
else
  fail "If-Match header not referenced — optimistic locking won't work"
fi

# ── 409 handled ─────────────────────────────────────────────────────────────
if grep -rqE '409|CONFLICT' src/ 2>/dev/null; then
  pass "409 Conflict handled in UI"
else
  fail "409 Conflict not handled — stale writes will silently succeed or show generic error"
fi

# ── zod (or similar) validation schema ─────────────────────────────────────
if grep -qE '"zod"|"yup"|"valibot"' package.json 2>/dev/null; then
  pass "form validation library declared"
else
  warn "no form validation library (zod/yup/valibot) declared"
fi

# ── Headless UI Dialog for destructive confirm ─────────────────────────────
if grep -rqE '@headlessui/vue|HeadlessUI|Dialog' src/ 2>/dev/null; then
  pass "Headless UI Dialog used for confirmation flows"
else
  warn "no Headless UI Dialog reference — delete confirm may lack focus trap"
fi

# ── Toast notifications ─────────────────────────────────────────────────────
if grep -rqi 'toast' src/ 2>/dev/null; then
  pass "toast notifications referenced"
else
  warn "no toast notifications — user may not see success/error feedback"
fi

info "cross-service integration — deferred to phase 3 (e2e)"

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
