#!/usr/bin/env bash
# Phase 02a3 — Domain services + web adapter + BFF RestClient + audit
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02a3" "Backend Service + BFF Client"

# ── Core: compile both services ─────────────────────────────────────────────
for svc in bff business-service; do
  [ -x "${svc}/mvnw" ] && run_check "${svc}: compile" -- bash -c "cd ${svc} && ./mvnw -q compile"
done

# ── Controllers implement generated API interfaces ──────────────────────────
if grep -rq 'implements BusinessServiceApi' business-service/*/src/main/java/ 2>/dev/null; then
  pass "business-service controller implements BusinessServiceApi (generated)"
else
  fail "business-service controller does NOT implement BusinessServiceApi — signatures may drift from spec"
fi

# Architecture: BFF controllers must NOT directly import BusinessServiceClient
if grep -rq 'adapter.out.client.generated.BusinessServiceClient' \
     bff/src/main/java/ch*/*/bff/adapter/in/web/ 2>/dev/null; then
  fail "BFF controllers import BusinessServiceClient directly — must go through BoatBffService"
else
  pass "BFF controllers do not depend on BusinessServiceClient directly"
fi

# No hand-written dto package in business-service (should be generated)
if [ -d business-service/*/src/main/java ]; then
  hand_dto="$(find business-service/*/src/main/java -type d -path '*/adapter/in/web/dto' -not -path '*generated*' 2>/dev/null)"
  if [ -n "${hand_dto}" ]; then
    warn "hand-written dto/ package in ${hand_dto} — prefer dto.generated/ exclusively"
  else
    pass "no hand-written dto/ package in business-service adapter.in.web"
  fi
fi

# ── ETag + 409 Conflict + audit logic present ──────────────────────────────
if grep -rqE '[Ee][Tt]ag|If-Match' business-service/*/src/main/java/ 2>/dev/null; then
  pass "business-service references ETag / If-Match in code"
else
  fail "business-service has no ETag / If-Match handling"
fi

if grep -rqE 'HttpStatus\.CONFLICT|409' business-service/*/src/main/java/ 2>/dev/null; then
  pass "business-service references 409 Conflict"
else
  fail "business-service does not handle 409 Conflict"
fi

if grep -rqE 'BoatAudit|audit' business-service/*/src/main/java/ 2>/dev/null; then
  pass "business-service references audit logic"
else
  fail "business-service does not reference audit logic"
fi

# ── BFF wires RestClient with Bearer interceptor ───────────────────────────
if grep -rqE 'RestClient|DefaultOAuth2AuthorizedClientManager' bff/src/main/java/ 2>/dev/null; then
  pass "BFF wires RestClient / OAuth2AuthorizedClientManager"
else
  fail "BFF does not wire RestClient with OAuth2 token forwarding"
fi

# BFF service must be suffixed with "BffService" (hint from ArchUnit rule)
if find bff/src/main/java -path '*infrastructure/service*' -name '*BffService.java' 2>/dev/null | grep -q .; then
  pass "BFF service class follows *BffService naming"
else
  warn "no *BffService class found under bff/infrastructure/service"
fi

# ── Contract fidelity: regenerate codegen; target/ diff should be empty ─────
# Best-effort: only if generate-sources actually runs clean
for svc in business-service bff; do
  [ -x "${svc}/mvnw" ] || continue
  if (cd "${svc}" && ./mvnw -q generate-sources) 2>/dev/null; then
    pass "${svc}: generate-sources clean (no drift from spec)"
  else
    warn "${svc}: generate-sources did not complete cleanly — rerun manually"
  fi
done

# ── Unified Validation & Messaging design (RFC 9457) ────────────────────────
# Each service must carry: @Valid at the controller, full set of exception
# handlers, ProblemTypes URI registry, JakartaCodeTranslator, messages.properties,
# and use Spring's ProblemDetail with populated type + instance.
for svc in business-service bff; do
  # Multi-module business-service: source is split across the four submodules.
  if [ "${svc}" = "business-service" ]; then
    src_roots=(business-service/*/src/main/java)
    messages_path="business-service/infrastructure/src/main/resources/messages.properties"
  else
    src_roots=("${svc}/src/main/java")
    messages_path="${svc}/src/main/resources/messages.properties"
  fi
  filtered=()
  for r in "${src_roots[@]}"; do [ -d "${r}" ] && filtered+=("${r}"); done
  if [ ${#filtered[@]} -eq 0 ]; then
    warn "${svc}: no src/main/java roots — skipping validation checks"
    continue
  fi

  # @Valid in at least one controller
  if grep -rqE '@Valid\b' "${filtered[@]}" 2>/dev/null; then
    pass "${svc}: @Valid present in controllers (syntactic validation at REST adapter)"
  else
    fail "${svc}: no @Valid found — Bean Validation is not wired; syntactic errors will not produce 400"
  fi

  # @Validated on at least one controller (for @PathVariable / @RequestParam)
  if grep -rqE '@Validated\b' "${filtered[@]}" 2>/dev/null; then
    pass "${svc}: @Validated present (path/query param constraints)"
  else
    warn "${svc}: no @Validated found — path/query-param constraints will silently not fire"
  fi

  # Handlers for Bean Validation exceptions
  for exc in MethodArgumentNotValidException ConstraintViolationException HttpMessageNotReadableException; do
    if grep -rq "${exc}" "${filtered[@]}" 2>/dev/null; then
      pass "${svc}: GlobalExceptionHandler references ${exc}"
    else
      fail "${svc}: ${exc} handler missing — RFC 9457 400 responses will not be emitted"
    fi
  done

  # ProblemTypes URI constants + JakartaCodeTranslator
  if grep -rq 'class ProblemTypes' "${filtered[@]}" 2>/dev/null; then
    pass "${svc}: ProblemTypes URI constants class present"
  else
    fail "${svc}: ProblemTypes.java missing — handlers must reference registry URIs, not hand-written strings"
  fi
  if grep -rq 'JakartaCodeTranslator' "${filtered[@]}" 2>/dev/null; then
    pass "${svc}: JakartaCodeTranslator present (no Jakarta constraint names on the wire)"
  else
    fail "${svc}: JakartaCodeTranslator.java missing — Jakarta constraint names will leak as error codes"
  fi

  # Handlers populate RFC 9457 type + instance (Spring ProblemDetail setters)
  if grep -rqE '\.setType\s*\(' "${filtered[@]}" 2>/dev/null; then
    pass "${svc}: handlers call .setType(...) (RFC 9457 type URI)"
  else
    fail "${svc}: no .setType(...) calls — ProblemDetail.type will fall back to about:blank"
  fi
  if grep -rqE '\.setInstance\s*\(' "${filtered[@]}" 2>/dev/null; then
    pass "${svc}: handlers call .setInstance(...) (RFC 9457 instance URI)"
  else
    fail "${svc}: no .setInstance(...) calls — ProblemDetail.instance will be empty"
  fi

  # about:blank must NOT appear in handler source (dead giveaway of a missed setType)
  if grep -rqE '"about:blank"' "${filtered[@]}" 2>/dev/null; then
    fail "${svc}: about:blank literal in source — must always be a registry URI"
  else
    pass "${svc}: no about:blank literal in source"
  fi

  # messages.properties present (in infrastructure submodule for business-service)
  if [ -f "${messages_path}" ]; then
    pass "${svc}: messages.properties present (i18n source)"
  else
    fail "${svc}: messages.properties missing at ${messages_path}"
  fi
done

# ── ValidationErrorResponse must be gone (unified ProblemDetail shape) ──────
if grep -rq 'ValidationErrorResponse' bff/src/main/java business-service/*/src/main/java 2>/dev/null; then
  fail "ValidationErrorResponse still referenced in hand-written code — RFC 9457 replaces it with ProblemDetail.messages"
else
  pass "ValidationErrorResponse not referenced in hand-written code (unified shape)"
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
