#!/usr/bin/env bash
# Phase 0b — Spring Initializr scaffold (bff + business-service: Boot 4.0.6, Java 25, jar)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "0b" "Initializr"

for svc in bff business-service; do
  pom="${svc}/pom.xml"
  if [ ! -f "${pom}" ]; then
    fail "${pom} missing"
    continue
  fi

  if grep -q '<version>4.0.6</version>' "${pom}"; then
    pass "${svc}: spring-boot-starter-parent 4.0.6"
  else
    fail "${svc}: Spring Boot version drift (expected 4.0.6)"
  fi

  if grep -qE '<java.version>25</java.version>' "${pom}"; then
    pass "${svc}: Java 25"
  else
    fail "${svc}: Java version drift (expected 25)"
  fi

  if grep -q '<packaging>jar</packaging>' "${pom}" || ! grep -q '<packaging>' "${pom}"; then
    pass "${svc}: jar packaging (or default)"
  else
    fail "${svc}: packaging is not jar"
  fi

  if [ -x "${svc}/mvnw" ]; then
    pass "${svc}: mvnw executable"
  else
    fail "${svc}: mvnw missing or not executable"
  fi

  if [ -f "${svc}/src/main/resources/application.yml" ]; then
    pass "${svc}: application.yml present (YAML)"
  elif [ -f "${svc}/src/main/resources/application.properties" ]; then
    fail "${svc}: application.properties found — convert to application.yml"
  else
    fail "${svc}: no application config"
  fi
done

# Expected starters per service (leniently)
if [ -f bff/pom.xml ]; then
  for starter in spring-boot-starter-web spring-boot-starter-security spring-boot-starter-oauth2-client spring-boot-starter-actuator; do
    grep -q "${starter}" bff/pom.xml \
      && pass "bff: ${starter}" \
      || warn "bff: ${starter} missing (expected from Initializr)"
  done
fi
if [ -f business-service/pom.xml ]; then
  for starter in spring-boot-starter-web spring-boot-starter-data-jpa spring-boot-starter-oauth2-resource-server spring-boot-starter-actuator; do
    grep -q "${starter}" business-service/pom.xml \
      && pass "business-service: ${starter}" \
      || warn "business-service: ${starter} missing (expected from Initializr)"
  done
fi

# Security: no secrets in pom or application.yml at this point
for f in bff/pom.xml business-service/pom.xml \
         bff/src/main/resources/application*.yml business-service/src/main/resources/application*.yml; do
  [ -f "${f}" ] || continue
  if grep -qE 'client[-_]secret:[[:space:]]*[A-Za-z0-9]{8,}' "${f}"; then
    fail "${f}: client_secret literal found"
  fi
done

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
