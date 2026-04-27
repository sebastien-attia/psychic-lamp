#!/usr/bin/env bash
# Phase 02a1 — Backend scaffold (hexagonal + 4 profiles + OpenAPI codegen wiring)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02a1" "Backend Scaffold"

# ── Core: generate-sources + compile ────────────────────────────────────────
for svc in bff business-service; do
  if [ ! -x "${svc}/mvnw" ]; then
    fail "${svc}/mvnw missing"
    continue
  fi
  run_check "${svc}: generate-sources + compile" -- \
    bash -c "cd ${svc} && ./mvnw -q generate-sources compile"
done

# ── Package layout (hexagonal) ─────────────────────────────────────────────
# Business Service: full hex packaged across 4 Maven submodules — search across all of them.
# BFF: partial (no domain/persistence) — single module.
bs_main_globs=(business-service/*/src/main/java)
if [ -d "${bs_main_globs[0]}" ]; then
  for pkg in domain/model application/port/in application/port/out adapter/in/web adapter/out/persistence infrastructure/config; do
    if find "${bs_main_globs[@]}" -type d -path "*/${pkg}" 2>/dev/null | grep -q .; then
      pass "business-service: ${pkg} package present"
    else
      fail "business-service: ${pkg} package missing"
    fi
  done
fi

bff="bff/src/main/java"
if [ -d "${bff}" ]; then
  for pkg in adapter/in/web infrastructure/config; do
    if find "${bff}" -type d -path "*/${pkg}" 2>/dev/null | grep -q .; then
      pass "bff: ${pkg} package present"
    else
      warn "bff: ${pkg} package missing"
    fi
  done
fi

# ── 4 profiles: application-{dev,local-intg,staging,prod}.yml ───────────────
# BFF profiles live under bff/src/main/resources; business-service profiles
# live in the bootstrap submodule (only the runnable jar packs the YAML).
for svc in bff business-service; do
  resources_root="${svc}/src/main/resources"
  [ "${svc}" = "business-service" ] && resources_root="business-service/bootstrap/src/main/resources"
  for prof in dev local-intg staging prod; do
    f="${resources_root}/application-${prof}.yml"
    if [ -f "${f}" ]; then
      pass "${svc}: application-${prof}.yml"
    else
      warn "${svc}: application-${prof}.yml missing"
    fi
  done
done

# ── Contract: OpenAPI generator outputs ────────────────────────────────────
# Business Service runs openapi-generator inside the `infrastructure`
# submodule (the adapter that owns @RestController). Output lives at
# business-service/infrastructure/target/generated-sources/openapi/.
bs_openapi_dir="business-service/infrastructure/target/generated-sources/openapi"
if [ -d "${bs_openapi_dir}" ]; then
  if find "${bs_openapi_dir}" -name 'BusinessServiceApi.java' | grep -q .; then
    pass "business-service: generated BusinessServiceApi.java"
  else
    fail "business-service: BusinessServiceApi.java not generated"
  fi
else
  warn "business-service: ${bs_openapi_dir}/ not present (run ./mvnw generate-sources)"
fi

if [ -d bff/target/generated-sources/openapi ]; then
  if find bff/target/generated-sources/openapi -name 'BusinessServiceClient.java' | grep -q .; then
    pass "bff: generated BusinessServiceClient.java"
  else
    fail "bff: BusinessServiceClient.java not generated"
  fi
else
  warn "bff: generated-sources/openapi/ not present"
fi

# ── Security: no secrets committed in application-*.yml ─────────────────────
secret_hits=0
while IFS= read -r -d '' f; do
  if grep -qE 'client[-_]secret:[[:space:]]*[A-Za-z0-9]{8,}' "${f}"; then
    fail "secret literal in ${f}"
    secret_hits=$((secret_hits+1))
  fi
done < <(find bff business-service -path '*/resources/application*.yml' -print0 2>/dev/null)
[ "${secret_hits}" -eq 0 ] && pass "no client_secret literals in application-*.yml"

# ── Bean Validation must be enabled in both openapi-generator configs ───────
for svc in bff business-service; do
  pom="${svc}/pom.xml"
  if [ ! -f "${pom}" ]; then
    fail "${svc}/pom.xml missing"
    continue
  fi
  if grep -qE '<useBeanValidation>\s*true\s*</useBeanValidation>' "${pom}"; then
    pass "${svc}/pom.xml: useBeanValidation=true (generated DTOs carry Jakarta constraints → @Valid at controller → 400)"
  else
    fail "${svc}/pom.xml: useBeanValidation must be true (unified validation design, RFC 9457 syntactic gate at adapter)"
  fi
done

# ── Initializr-fetched starters must include `validation` on BOTH services ──
# (BFF needs it too — it is itself a REST adapter at the trust boundary.)
if [ -f ai-scripts/00c-initializr.sh ]; then
  if grep -q 'BFF_DEPS=.*validation' ai-scripts/00c-initializr.sh; then
    pass "00c-initializr.sh: BFF_DEPS includes validation"
  else
    fail "00c-initializr.sh: BFF_DEPS must include 'validation' (hibernate-validator)"
  fi
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
