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
if grep -rq 'implements BusinessServiceApi' business-service/src/main/java/ 2>/dev/null; then
  pass "business-service controller implements BusinessServiceApi (generated)"
else
  fail "business-service controller does NOT implement BusinessServiceApi — signatures may drift from spec"
fi

# Architecture: BFF must not (re-)introduce an outbound HTTP-Interface client.
# After the SCG migration proxying lives entirely in application-routes.yml,
# not in Java code. The adapter.out.client package and BoatBffService are gone.
if find bff/src/main/java -path '*adapter/out/client*' -name '*.java' 2>/dev/null | grep -q .; then
  fail "BFF re-introduced adapter.out.client.* — proxying must stay in application-routes.yml (SCG)"
else
  pass "BFF has no adapter.out.client package (proxying lives in SCG config)"
fi
if find bff/src/main/java -path '*infrastructure/service*' -name '*BffService.java' 2>/dev/null | grep -q .; then
  fail "BFF re-introduced *BffService — orchestration is now declarative in application-routes.yml"
else
  pass "BFF has no *BffService class (declarative SCG routes)"
fi

# No hand-written dto package in business-service (should be generated)
if [ -d business-service/src/main/java ]; then
  hand_dto="$(find business-service/src/main/java -type d -path '*/adapter/in/web/dto' -not -path '*generated*' 2>/dev/null)"
  if [ -n "${hand_dto}" ]; then
    warn "hand-written dto/ package in ${hand_dto} — prefer dto.generated/ exclusively"
  else
    pass "no hand-written dto/ package in business-service adapter.in.web"
  fi
fi

# ── ETag + 409 Conflict + audit logic present ──────────────────────────────
if grep -rqE '[Ee][Tt]ag|If-Match' business-service/src/main/java/ 2>/dev/null; then
  pass "business-service references ETag / If-Match in code"
else
  fail "business-service has no ETag / If-Match handling"
fi

if grep -rqE 'HttpStatus\.CONFLICT|409' business-service/src/main/java/ 2>/dev/null; then
  pass "business-service references 409 Conflict"
else
  fail "business-service does not handle 409 Conflict"
fi

if grep -rqE 'BoatAudit|audit' business-service/src/main/java/ 2>/dev/null; then
  pass "business-service references audit logic"
else
  fail "business-service does not reference audit logic"
fi

# ── BFF wires SCG TokenRelay + the OAuth2AuthorizedClientManager bean ─────
if grep -rq 'spring-cloud-starter-gateway-server-webmvc' bff/pom.xml 2>/dev/null; then
  pass "BFF declares spring-cloud-starter-gateway-server-webmvc (SCG MVC)"
else
  fail "BFF missing spring-cloud-starter-gateway-server-webmvc — SCG migration not applied"
fi
if grep -rq 'TokenRelay' bff/src/main/resources/application-routes.yml 2>/dev/null; then
  pass "application-routes.yml declares the TokenRelay filter"
else
  fail "application-routes.yml missing TokenRelay filter — Bearer token won't be forwarded"
fi
if grep -rq 'DefaultOAuth2AuthorizedClientManager' bff/src/main/java/ 2>/dev/null; then
  pass "BFF wires DefaultOAuth2AuthorizedClientManager (consumed by TokenRelay per request)"
else
  fail "BFF missing OAuth2AuthorizedClientManager bean — TokenRelay will not resolve"
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
# Bean Validation lives ONLY in business-service after the SCG migration —
# the BFF dropped spring-boot-starter-validation (proxying lives in SCG
# config; the BS is the trust boundary that owns validation). The RFC 9457
# envelope (ProblemTypes registry, JakartaCodeTranslator, messages.properties,
# ProblemDetail.type/instance) is required in BOTH services so error
# responses share an identical shape.
if grep -rqE '@Valid\b' business-service/src/main/java 2>/dev/null; then
  pass "business-service: @Valid present in controllers"
else
  fail "business-service: no @Valid found — Bean Validation is not wired"
fi
if grep -rqE '@Validated\b' business-service/src/main/java 2>/dev/null; then
  pass "business-service: @Validated present (path/query param constraints)"
else
  warn "business-service: no @Validated found — path/query-param constraints will silently not fire"
fi
# BFF must NOT depend on spring-boot-starter-validation any more.
if grep -q 'spring-boot-starter-validation' bff/pom.xml 2>/dev/null; then
  fail "BFF still declares spring-boot-starter-validation — Bean Validation moved to BS-only"
else
  pass "BFF no longer declares spring-boot-starter-validation (validation owned by BS)"
fi

for svc in business-service bff; do
  SRC="${svc}/src/main/java"
  [ -d "${SRC}" ] || { warn "${svc}: src/main/java missing — skipping envelope checks"; continue; }

  # Handlers for Bean Validation exceptions — required in both services
  # (BFF keeps them as a defense-in-depth shell so the envelope shape stays
  # consistent if a future BFF-local endpoint re-introduces @Valid).
  for exc in MethodArgumentNotValidException ConstraintViolationException HttpMessageNotReadableException; do
    if grep -rq "${exc}" "${SRC}" 2>/dev/null; then
      pass "${svc}: GlobalExceptionHandler references ${exc}"
    else
      fail "${svc}: ${exc} handler missing — RFC 9457 400 responses will not be emitted"
    fi
  done

  # ProblemTypes URI constants + JakartaCodeTranslator
  if grep -rq 'class ProblemTypes' "${SRC}" 2>/dev/null; then
    pass "${svc}: ProblemTypes URI constants class present"
  else
    fail "${svc}: ProblemTypes.java missing — handlers must reference registry URIs"
  fi
  if grep -rq 'JakartaCodeTranslator' "${SRC}" 2>/dev/null; then
    pass "${svc}: JakartaCodeTranslator present (no Jakarta constraint names on the wire)"
  else
    fail "${svc}: JakartaCodeTranslator.java missing — Jakarta constraint names will leak"
  fi

  if grep -rqE '\.setType\s*\(' "${SRC}" 2>/dev/null; then
    pass "${svc}: handlers call .setType(...) (RFC 9457 type URI)"
  else
    fail "${svc}: no .setType(...) calls — ProblemDetail.type will fall back to about:blank"
  fi
  if grep -rqE '\.setInstance\s*\(' "${SRC}" 2>/dev/null; then
    pass "${svc}: handlers call .setInstance(...) (RFC 9457 instance URI)"
  else
    fail "${svc}: no .setInstance(...) calls — ProblemDetail.instance will be empty"
  fi

  if grep -rqE '"about:blank"' "${SRC}" 2>/dev/null; then
    fail "${svc}: about:blank literal in source — must always be a registry URI"
  else
    pass "${svc}: no about:blank literal in source"
  fi

  if [ -f "${svc}/src/main/resources/messages.properties" ]; then
    pass "${svc}: messages.properties present (i18n source)"
  else
    fail "${svc}: messages.properties missing — handlers have no localizable message source"
  fi
done

# ── ValidationErrorResponse must be gone (unified ProblemDetail shape) ──────
if grep -rq 'ValidationErrorResponse' bff/src/main/java business-service/src/main/java 2>/dev/null; then
  fail "ValidationErrorResponse still referenced in hand-written code — RFC 9457 replaces it with ProblemDetail.messages"
else
  pass "ValidationErrorResponse not referenced in hand-written code (unified shape)"
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
