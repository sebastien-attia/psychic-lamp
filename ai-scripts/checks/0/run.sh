#!/usr/bin/env bash
# Phase 0 — Bootstrap (hexagonal skeleton + CLAUDE.md + rules + .env.example)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "0" "Bootstrap"

# ── Structure: hexagonal package trees for both services ────────────────────
for svc in bff business-service; do
  for pkg in domain/model adapter/in/web adapter/out/persistence infrastructure/config; do
    # BFF has no persistence/domain in the same shape; check leniently
    if [ -d "${svc}/src/main/java" ] && find "${svc}/src/main/java" -type d -path "*/${pkg}" 2>/dev/null | grep -q .; then
      pass "${svc}: ${pkg} package present"
    else
      warn "${svc}: ${pkg} package missing (OK if BFF and pkg is domain.* or persistence.*)"
    fi
  done
done

# ── CLAUDE.md describes two-service + hexagonal + 4 profiles ────────────────
if [ -f CLAUDE.md ]; then
  if grep -qE 'BFF.*Business Service|Business Service.*BFF' CLAUDE.md; then
    pass "CLAUDE.md references both BFF and Business Service"
  else
    fail "CLAUDE.md does not describe the BFF + Business Service split"
  fi
  if grep -qE 'hexagonal|ports.?and.?adapters' CLAUDE.md; then
    pass "CLAUDE.md mentions hexagonal architecture"
  else
    warn "CLAUDE.md does not mention hexagonal architecture"
  fi
  for env in dev local-intg staging prod; do
    if grep -q "${env}" CLAUDE.md; then
      pass "CLAUDE.md mentions profile: ${env}"
    else
      warn "CLAUDE.md does not mention profile: ${env}"
    fi
  done
else
  fail "CLAUDE.md missing"
fi

# ── .claude/rules/ path-scoped rules ────────────────────────────────────────
if [ -d .claude/rules ]; then
  count="$(find .claude/rules -maxdepth 1 -name '*.md' | wc -l)"
  if [ "${count}" -ge 3 ]; then
    pass ".claude/rules/ contains ${count} rule files"
  else
    warn ".claude/rules/ contains only ${count} rule files (expected BFF + business + frontend + infra + testing + openapi + git)"
  fi
else
  fail ".claude/rules/ directory missing"
fi

# ── .env.example with Keycloak confidential client placeholders ─────────────
if [ -f .env.example ]; then
  if grep -qE 'KEYCLOAK' .env.example; then
    pass ".env.example references Keycloak"
  else
    fail ".env.example does not reference Keycloak"
  fi
  # Security: .env.example must have placeholders, not real secrets
  if grep -qE '^[A-Z_]+=[A-Za-z0-9+/=]{20,}' .env.example; then
    warn ".env.example looks like it contains real secret-length values — use placeholders"
  else
    pass ".env.example uses placeholder values (no long secret-like tokens)"
  fi
else
  fail ".env.example missing"
fi

# ── .gitignore must cover generated/secret paths ────────────────────────────
if [ -f .gitignore ]; then
  missing=()
  grep -qE '^\.env$|^\*\.env$|/\.env$'   .gitignore || missing+=('.env')
  grep -qE 'target/?|/target'            .gitignore || missing+=('target/')
  grep -qE 'generated[-/]sources|generated/api' .gitignore || missing+=('generated sources')
  grep -qE '\*\.pem|\.pem$'              .gitignore || missing+=('*.pem')
  if [ ${#missing[@]} -eq 0 ]; then
    pass ".gitignore covers .env, target/, generated sources, *.pem"
  else
    fail ".gitignore missing entries: ${missing[*]}"
  fi
else
  fail ".gitignore missing"
fi

# ── Code-reviewer subagent + CLAUDE.md policy section ──────────────────────
AGENT_FILE=".claude/agents/code-reviewer.md"
if [ -f "${AGENT_FILE}" ]; then
  if grep -q '^name: code-reviewer$' "${AGENT_FILE}" \
     && grep -q '^tools: Read, Grep, Glob, Bash$' "${AGENT_FILE}"; then
    pass "${AGENT_FILE} present with correct frontmatter"
  else
    fail "${AGENT_FILE} present but missing required frontmatter (name: code-reviewer, tools: Read, Grep, Glob, Bash)"
  fi
  if grep -q '## Review dimensions' "${AGENT_FILE}" \
     && grep -q 'Documentation (mandatory check)' "${AGENT_FILE}"; then
    pass "code-reviewer agent body includes review dimensions + mandatory docs check"
  else
    fail "code-reviewer agent body is missing the Review dimensions / mandatory docs section"
  fi
else
  fail "${AGENT_FILE} missing — bootstrap should write the code-reviewer subagent"
fi

if [ -f CLAUDE.md ] && grep -q '^## Code review policy' CLAUDE.md; then
  pass "CLAUDE.md contains 'Code review policy' section"
else
  fail "CLAUDE.md missing 'Code review policy' section (Project conventions addendum)"
fi

if [ -f .claude/rules/code-review.md ] && grep -q '@code-reviewer' .claude/rules/code-review.md; then
  pass ".claude/rules/code-review.md present (auto-loaded for every file)"
else
  fail ".claude/rules/code-review.md missing — auto-load reminder will not fire"
fi

# ── Reproducibility: git working tree clean after bootstrap ─────────────────
if git rev-parse --git-dir >/dev/null 2>&1; then
  if [ -z "$(git status --porcelain)" ]; then
    pass "git working tree clean after bootstrap"
  else
    warn "git working tree has uncommitted changes after bootstrap"
  fi
  if git log --oneline -1 >/dev/null 2>&1; then
    pass "initial git commit exists"
  else
    fail "no initial git commit"
  fi
else
  fail "not a git repository"
fi

check_summary
