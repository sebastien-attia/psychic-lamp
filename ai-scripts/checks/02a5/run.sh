#!/usr/bin/env bash
# Phase 02a5 — ArchUnit + integration tests (Testcontainers, jwt(), WireMock)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02a5" "Backend Tests"

# ── Core: mvn verify on both services (this is the real gate) ───────────────
for svc in bff business-service; do
  if [ -x "${svc}/mvnw" ]; then
    run_check "${svc}: mvn verify (all tests green)" -- \
      bash -c "cd ${svc} && ./mvnw -q verify"
  else
    fail "${svc}/mvnw missing"
  fi
done

# ── ArchUnit tests exist ────────────────────────────────────────────────────
for svc in bff business-service; do
  if find "${svc}/src/test/java" -name '*ArchitectureTest.java' 2>/dev/null | grep -q .; then
    pass "${svc}: ArchUnit test present"
  else
    fail "${svc}: no *ArchitectureTest.java found"
  fi
done

# ── Testcontainers usage ───────────────────────────────────────────────────
if grep -rq '@Testcontainers\|PostgreSQLContainer\|KeycloakContainer' \
     business-service/src/test/java bff/src/test/java 2>/dev/null; then
  pass "Testcontainers used for integration tests"
else
  warn "no Testcontainers references in test sources"
fi

# Business-service tests should use jwt() post-processor (no real Keycloak)
if grep -rq 'jwt()' business-service/src/test/java 2>/dev/null; then
  pass "business-service tests use jwt() post-processor (no real Keycloak needed)"
else
  warn "business-service tests may start real Keycloak — prefer jwt() post-processor"
fi

# BFF tests should use WireMock + Testcontainers Keycloak
if grep -rq 'WireMock\|@WireMockTest' bff/src/test/java 2>/dev/null; then
  pass "BFF tests use WireMock for business-service stub"
else
  warn "BFF tests don't reference WireMock — integration may hit a real business-service"
fi

# ── Observability: testcontainer cleanup (heuristic) ────────────────────────
# Check there's no testcontainers.reuse=true leaking long-lived containers unintentionally
# This is a soft heuristic; we just info it.
if grep -rq 'withReuse(true)' business-service/src/test/java bff/src/test/java 2>/dev/null; then
  info "Testcontainers reuse enabled — make sure TC_REUSABLE is set in .testcontainers.properties for dev"
fi

# ── Coverage threshold (jacoco presence) ────────────────────────────────────
for svc in bff business-service; do
  if grep -q 'jacoco' "${svc}/pom.xml" 2>/dev/null; then
    pass "${svc}: jacoco plugin declared"
  else
    warn "${svc}: no jacoco plugin — coverage not measured"
  fi
done

# ── RFC 9457 / unified validation test coverage ─────────────────────────────
for svc in bff business-service; do
  TST="${svc}/src/test/java"
  [ -d "${TST}" ] || continue

  # ArchUnit rule blocking jakarta.validation in domain (business-service only; BFF has no domain)
  if [ "${svc}" = "business-service" ]; then
    if grep -rqE 'domain_must_not_import_jakarta_validation|jakarta\.validation\.\.' "${TST}" 2>/dev/null; then
      pass "${svc}: ArchUnit rule forbids jakarta.validation in domain"
    else
      fail "${svc}: missing ArchUnit rule forbidding jakarta.validation in domain"
    fi
  fi

  # Tests must assert the RFC 9457 envelope (populated type + Content-Language)
  if grep -rqE 'application/problem\+json' "${TST}" 2>/dev/null; then
    pass "${svc}: tests assert application/problem+json"
  else
    fail "${svc}: no test asserts application/problem+json — RFC 9457 envelope unverified"
  fi
  if grep -rqE '[Cc]ontent-[Ll]anguage' "${TST}" 2>/dev/null; then
    pass "${svc}: tests assert Content-Language header"
  else
    fail "${svc}: no test asserts Content-Language header"
  fi
  if grep -rqE 'boatapp\.owt\.ch/problems/validation' "${TST}" 2>/dev/null; then
    pass "${svc}: tests assert problem-type URI (registry, not about:blank)"
  else
    fail "${svc}: no test asserts the problem-type URI from the registry"
  fi

  # Assert the three new 400 paths are exercised
  for token in 'field.required' 'field.size.invalid' 'request.body.malformed'; do
    if grep -rqF "${token}" "${TST}" 2>/dev/null; then
      pass "${svc}: test covers application code '${token}'"
    else
      fail "${svc}: no test references application code '${token}'"
    fi
  done
done

# BFF must assert that its own @Valid short-circuits the upstream call
if grep -rq 'verify\s*(\s*exactly\s*(\s*0\s*)' bff/src/test/java 2>/dev/null \
   || grep -rqE 'verify.*(exactly\(0\)|never\(\))' bff/src/test/java 2>/dev/null; then
  pass "BFF: a test asserts WireMock received zero upstream calls on syntactic failure"
else
  warn "BFF: no test asserts 'WireMock received zero calls' on 400 — BFF short-circuit unverified"
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
