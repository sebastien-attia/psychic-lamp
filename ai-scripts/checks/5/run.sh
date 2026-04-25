#!/usr/bin/env bash
# Phase 5 — Documentation (README, AI_USAGE, USER_GUIDE)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "5" "Documentation"

# Required files
for f in README.md AI_USAGE.md; do
  if [ -f "${f}" ]; then
    pass "${f} present"
  else
    fail "${f} missing"
  fi
done
for f in USER_GUIDE.md docs/USER_GUIDE.md; do
  [ -f "${f}" ] && { pass "user guide: ${f}"; break; }
done

# README sections (best-effort, case-insensitive)
if [ -f README.md ]; then
  for sec in 'Architecture' 'Setup' 'Test' 'Deploy'; do
    if grep -qiE "^#+\s*${sec}" README.md; then
      pass "README section: ${sec}"
    else
      warn "README missing section: ${sec}"
    fi
  done
fi

# No TODOs / FIXMEs / console.log in committed code
total_todos="$(grep -rInE 'TODO|FIXME|XXX' --include='*.ts' --include='*.vue' --include='*.java' \
                 --include='*.yml' --include='*.tf' \
                 --exclude-dir=node_modules --exclude-dir=target --exclude-dir=generated \
                 . 2>/dev/null | wc -l)"
if [ "${total_todos}" -eq 0 ]; then
  pass "no TODO/FIXME/XXX markers in committed code"
else
  warn "${total_todos} TODO/FIXME/XXX markers — remove before shipping"
fi

if grep -rInE '^\s*console\.(log|debug)' --include='*.ts' --include='*.vue' \
      --exclude-dir=node_modules --exclude-dir=generated \
      . 2>/dev/null | grep -v '// eslint' >/dev/null; then
  warn "console.log/debug present in committed TS/Vue code"
else
  pass "no console.log/debug in committed TS/Vue code"
fi

# Swagger UI / OpenAPI referenced in docs
if grep -rqE 'openapi|swagger' README.md AI_USAGE.md docs/ 2>/dev/null; then
  pass "docs reference the OpenAPI contract"
else
  warn "docs don't reference OpenAPI / Swagger"
fi

# Broken relative links (best-effort)
broken=0
if [ -f README.md ]; then
  while read -r link; do
    [ -z "${link}" ] && continue
    # skip http(s), anchors, mailto
    case "${link}" in http*|'#'*|mailto:*) continue ;; esac
    case "${link}" in /*) target=".${link}" ;; *) target="${link}" ;; esac
    if [ ! -e "${target}" ]; then
      broken=$((broken+1))
    fi
  done < <(grep -oE '\]\([^)]+\)' README.md | sed 's/](\(.*\))/\1/')
  if [ "${broken}" -eq 0 ]; then
    pass "README has no broken relative links"
  else
    warn "${broken} broken relative link(s) in README"
  fi
fi

# LICENSE / .env.example safe to commit
[ -f LICENSE ] || [ -f LICENSE.md ] && pass "LICENSE committed" || warn "no LICENSE file"

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
