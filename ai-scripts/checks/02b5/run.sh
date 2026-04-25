#!/usr/bin/env bash
# Phase 02b5 — Frontend polish (dark mode + WCAG AA + i18n EN/FR + component tests)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02b5" "Frontend Polish"

cd frontend 2>/dev/null || { fail "frontend/ directory missing"; check_summary; exit 1; }

if command -v npm >/dev/null; then
  run_check "npm run type-check" -- npm run type-check
  run_check "npm run build"      -- npm run build
  if grep -q '"test"' package.json; then
    run_check "npm run test" -- npm test -- --run
  else
    warn "no \"test\" script declared in package.json"
  fi
  if grep -q '"lint"' package.json; then
    run_check "npm run lint" -- npm run lint
  else
    warn "no \"lint\" script declared"
  fi
fi

# ── Dark mode ───────────────────────────────────────────────────────────────
if grep -rqE "dark:(bg|text|border)" src/ 2>/dev/null; then
  pass "dark: Tailwind variants used"
else
  fail "no dark: Tailwind variants — dark mode not styled"
fi
if grep -rqE "darkMode:" tailwind.config.* 2>/dev/null; then
  pass "tailwind dark mode configured"
else
  warn "tailwind.config.* does not explicitly set darkMode"
fi

# ── i18n EN + FR ────────────────────────────────────────────────────────────
for locale in en fr; do
  if find src -path '*locales*' -name "${locale}.*" 2>/dev/null | grep -q .; then
    pass "i18n locale file present: ${locale}"
  else
    fail "i18n locale missing: ${locale}"
  fi
done

# ── Security: no console.log in src/ ───────────────────────────────────────
logs="$(grep -rInE '^\s*console\.(log|debug)' src/ 2>/dev/null | wc -l)"
if [ "${logs}" -eq 0 ]; then
  pass "no console.log / console.debug in src/"
else
  warn "${logs} console.log/debug statements in src/"
fi

# ── Accessibility hints ────────────────────────────────────────────────────
if grep -rqE 'aria-(label|describedby|live|hidden)' src/ 2>/dev/null; then
  pass "ARIA attributes used"
else
  warn "no ARIA attributes found in src/"
fi
if grep -rqE 'focus-visible|focus:ring' src/ 2>/dev/null; then
  pass "focus-visible / focus:ring styles used"
else
  warn "no focus-visible / focus:ring styles"
fi

info "cross-service integration — deferred to phase 3 (e2e)"

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
