#!/usr/bin/env bash
# Phase 3 — Playwright E2E tests
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "3" "E2E Tests"

if [ ! -d frontend ]; then
  fail "frontend/ missing"
  check_summary; exit 1
fi

cd frontend

# playwright config + spec files
if [ -f playwright.config.ts ] || [ -f playwright.config.js ]; then
  pass "playwright config present"
else
  fail "playwright config missing"
fi

specs="$(find . -path ./node_modules -prune -o -name '*.spec.ts' -print 2>/dev/null | wc -l)"
if [ "${specs}" -ge 3 ]; then
  pass "${specs} E2E spec file(s) found"
else
  warn "only ${specs} spec file(s) — expected auth, list, CRUD, accessibility, responsive"
fi

# Run tests
if command -v npx >/dev/null; then
  run_check "npx playwright test" -- npx playwright test
else
  fail "npx not available"
fi

# Report artifact
if [ -d playwright-report ]; then
  pass "playwright HTML report generated"
else
  warn "no playwright-report directory (use --reporter=html)"
fi

# axe / accessibility inside E2E
if grep -rq '@axe-core\|axe-playwright' . --include='*.ts' 2>/dev/null; then
  pass "E2E includes axe-core accessibility checks"
else
  warn "E2E does not reference axe-core — WCAG violations will not be caught"
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
