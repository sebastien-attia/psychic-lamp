#!/usr/bin/env bash
# Phase 1 — OpenAPI 3.0 contract (Boat CRUDL + auth endpoints)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "1" "OpenAPI Contract"

SPEC=""
for p in contracts/openapi.yaml contracts/openapi.yml openapi.yaml openapi.yml; do
  [ -f "${p}" ] && { SPEC="${p}"; break; }
done

if [ -z "${SPEC}" ]; then
  fail "OpenAPI spec not found (looked in contracts/openapi.y?ml, openapi.y?ml)"
  check_summary; exit 1
fi
pass "OpenAPI spec present at ${SPEC}"

# Parseable YAML
if command -v python3 >/dev/null && python3 -c "import yaml" 2>/dev/null; then
  run_check "YAML parses" -- python3 -c "import yaml,sys; yaml.safe_load(open('${SPEC}'))"
else
  warn "python3+pyyaml unavailable — skipping YAML parse"
fi

# Redocly lint (best-effort)
if command -v npx >/dev/null; then
  if npx --yes @redocly/cli lint "${SPEC}" >/dev/null 2>&1; then
    pass "redocly lint passes"
  else
    warn "redocly lint reported issues (rerun manually: npx @redocly/cli lint ${SPEC})"
  fi
else
  info "npx not present — skipping redocly lint"
fi

# Required endpoints
for ep in '/api/v1/boats' '/api/v1/boats/{id}' '/api/me'; do
  if grep -Eq "^\s+['\"]?${ep//\//\\/}['\"]?:" "${SPEC}"; then
    pass "endpoint declared: ${ep}"
  else
    fail "endpoint missing: ${ep}"
  fi
done

# CRUD verbs
for verb in get post put delete; do
  if grep -Eq "^\s+${verb}:" "${SPEC}"; then
    pass "verb declared: ${verb}"
  else
    fail "verb missing: ${verb}"
  fi
done

# Boat schema fields
for field in id name description createdAt version; do
  if grep -Eq "^\s+${field}:" "${SPEC}"; then
    pass "Boat field declared: ${field}"
  else
    fail "Boat field missing: ${field}"
  fi
done

# Validation constraints
if grep -qE 'maxLength:\s*64' "${SPEC}"; then
  pass "name maxLength 64 declared"
else
  warn "no maxLength: 64 found (Boat.name constraint)"
fi
if grep -qE 'maxLength:\s*256' "${SPEC}"; then
  pass "description maxLength 256 declared"
else
  warn "no maxLength: 256 found (Boat.description constraint)"
fi

# ETag / If-Match / status codes (RFC 9457 requires 400 + 500 across the board)
grep -qE '[Ee][Tt]ag' "${SPEC}"     && pass "ETag header present"     || fail "ETag header missing"
grep -qE '[Ii]f-[Mm]atch' "${SPEC}" && pass "If-Match header present" || fail "If-Match header missing"
grep -qE "^\s+'?400'?:"    "${SPEC}" && pass "400 Bad Request declared" || fail "400 Bad Request response missing (syntactic validation / malformed JSON)"
grep -qE "^\s+'?409'?:"    "${SPEC}" && pass "409 Conflict response"   || fail "409 Conflict response missing"
grep -qE "^\s+'?428'?:"    "${SPEC}" && pass "428 Precondition Required declared" || warn "428 not declared (optional but recommended)"
grep -qE "^\s+'?422'?:"    "${SPEC}" && pass "422 validation declared" || fail "422 validation response missing (domain/semantic errors)"
grep -qE "^\s+'?404'?:"    "${SPEC}" && pass "404 not-found declared"  || warn "404 not declared"
grep -qE "^\s+'?500'?:"    "${SPEC}" && pass "500 Internal Server Error declared" || fail "500 response missing — every operation must declare it"

# RFC 9457 compliance
grep -qE 'application/problem\+json' "${SPEC}" && pass "application/problem+json media type used" || fail "application/problem+json not declared anywhere — errors must use RFC 9457 media type"
grep -qE 'RFC 9457|rfc9457|rfc 9457' "${SPEC}" && pass "RFC 9457 referenced in spec" || warn "No RFC 9457 reference in spec description/schemas"
grep -qE 'ProblemDetail' "${SPEC}"             && pass "ProblemDetail schema referenced" || fail "ProblemDetail schema missing"
grep -qE 'boatapp\.owt\.ch/problems/validation' "${SPEC}" && pass "problem-type URI registry present (validation)" || fail "no problem-type URI registry (expected https://boatapp.owt.ch/problems/validation etc.)"
if grep -qE 'ValidationErrorResponse' "${SPEC}"; then
  fail "ValidationErrorResponse still present — must be removed under RFC 9457 (use ProblemDetail.messages extension)"
else
  pass "ValidationErrorResponse removed (unified ProblemDetail shape)"
fi
if grep -qE "['\"]?about:blank['\"]?" "${SPEC}"; then
  fail "about:blank appears in spec — RFC 9457 forbids it when extension members are present (we always have instance/messages)"
else
  pass "no about:blank in spec"
fi
grep -qE '[Cc]ontent-[Ll]anguage' "${SPEC}" && pass "Content-Language header declared on error responses" || warn "Content-Language header not declared on error responses"
# Severity enum must be ERROR | WARNING | INFO (no WARN)
if grep -qE '-\s*WARN([^I]|$)' "${SPEC}"; then
  fail "Severity enum contains WARN — must be WARNING"
else
  pass "Severity enum uses WARNING (not WARN)"
fi

# Session-based (no Bearer scheme) per project convention
if grep -qE 'scheme:\s*bearer' "${SPEC}"; then
  fail "Bearer scheme declared — contract is session-based, remove 'scheme: bearer'"
else
  pass "no Bearer scheme in contract (session-based)"
fi

# Pagination parameters
for param in page size sort; do
  if grep -Eq "name:\s*${param}" "${SPEC}"; then
    pass "pagination param declared: ${param}"
  else
    warn "pagination param missing: ${param}"
  fi
done

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
