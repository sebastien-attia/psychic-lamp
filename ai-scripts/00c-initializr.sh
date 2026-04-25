#!/usr/bin/env bash
# Scaffolds both Spring Boot services (bff, business-service) from the official
# Spring Initializr at start.spring.io. Pinned to:
#   - type=maven-project, language=java, packaging=jar
#   - bootVersion=4.0.6, javaVersion=25
#   - config format: application.yml (renamed from Initializr's .properties output)
#
# Idempotent: if a service's pom.xml already exists, that service is skipped.
#
# Usage:
#   ./ai-scripts/00c-initializr.sh
#   SPRING_INITIALIZR_URL=https://my-mirror/ ./ai-scripts/00c-initializr.sh
set -euo pipefail

INITIALIZR_URL="${SPRING_INITIALIZR_URL:-https://start.spring.io}"
BOOT_VERSION="4.0.6"
JAVA_VERSION="25"
GROUP_ID="ch.owt.boatapp"

# session-jdbc: JDBC-backed Spring Session (persisted in bff_session DB / SPRING_SESSION table).
# The bare `session` ID was removed from Initializr metadata — store requires an explicit backend.
# session-jdbc transitively pulls spring-jdbc (JdbcTemplate), so an explicit data-jdbc
# starter is intentionally NOT included: it would add Spring Data Commons + a repository
# scanner, opening the door to persistence concerns the BFF must not host
# (see .claude/rules/bff-java.md — "No JPA, no domain logic").
# validation: Jakarta Bean Validation (hibernate-validator). REQUIRED on BOTH services:
#   the BFF is itself a REST adapter at the trust boundary to the browser and MUST run
#   @Valid on its inbound DTOs (syntactic → 400). See .claude/rules/validation-and-errors.md.
BFF_DEPS="web,validation,security,oauth2-client,actuator,session-jdbc,liquibase,postgresql,lombok,testcontainers"
BS_DEPS="web,data-jpa,validation,oauth2-resource-server,security,actuator,postgresql,liquibase,lombok,testcontainers"

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Spring Initializr scaffold — bff + business-service     ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo "▸ Initializr:   ${INITIALIZR_URL}"
echo "▸ Spring Boot:  ${BOOT_VERSION}"
echo "▸ Java:         ${JAVA_VERSION}"
echo "▸ Packaging:    jar (Maven project, YAML config)"
echo ""

# ── fetch_service <artifactId> <packageName> <deps-csv> ────────────────────
fetch_service() {
  local artifact="$1"
  local pkg="$2"
  local deps="$3"
  local target="${PROJECT_ROOT}/${artifact}"

  if [[ -f "${target}/pom.xml" ]]; then
    echo "▸ ${artifact}/pom.xml already exists — refusing to overwrite."
    return 0
  fi

  mkdir -p "${target}"
  local zip
  zip="$(mktemp -t "${artifact}-initializr.XXXXXX.zip")"

  echo "▸ Fetching ${artifact} from Initializr (deps: ${deps})..."
  curl -fsSL -G "${INITIALIZR_URL}/starter.zip" \
    --data-urlencode "type=maven-project" \
    --data-urlencode "language=java" \
    --data-urlencode "bootVersion=${BOOT_VERSION}" \
    --data-urlencode "javaVersion=${JAVA_VERSION}" \
    --data-urlencode "packaging=jar" \
    --data-urlencode "groupId=${GROUP_ID}" \
    --data-urlencode "artifactId=${artifact}" \
    --data-urlencode "name=${artifact}" \
    --data-urlencode "packageName=${pkg}" \
    --data-urlencode "dependencies=${deps}" \
    -o "${zip}"

  unzip -q -o "${zip}" -d "${target}"
  rm -f "${zip}"

  # Initializr emits application.properties — rename to application.yml per project standard.
  local props="${target}/src/main/resources/application.properties"
  local yml="${target}/src/main/resources/application.yml"
  if [[ -f "${props}" ]]; then
    mv "${props}" "${yml}"
  elif [[ ! -f "${yml}" ]]; then
    : > "${yml}"
  fi

  # Sanity-check the output.
  local main_class="${target}/src/main/java/${pkg//.//}/$(tr '[:lower:]' '[:upper:]' <<<"${artifact:0:1}")${artifact:1}Application.java"
  # Artifact contains a hyphen for business-service → Initializr strips it in the class name.
  # Fall back to a wildcard check to survive naming variants.
  [[ -f "${target}/pom.xml" ]] || { echo "FAIL: ${artifact}/pom.xml missing after unzip"; exit 1; }
  [[ -x "${target}/mvnw" ]]    || { echo "FAIL: ${artifact}/mvnw missing or not executable"; exit 1; }
  [[ -f "${yml}" ]]            || { echo "FAIL: ${artifact} application.yml missing"; exit 1; }
  if ! find "${target}/src/main/java" -name '*Application.java' -print -quit | grep -q .; then
    echo "FAIL: ${artifact} main *Application.java not found"
    exit 1
  fi
  echo "  ✓ ${artifact} scaffolded"
}

fetch_service "bff"              "ch.owt.boatapp.bff" "${BFF_DEPS}" &
PID_BFF=$!
fetch_service "business-service" "ch.owt.boatapp"     "${BS_DEPS}"  &
PID_BS=$!

wait "${PID_BFF}"
wait "${PID_BS}"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  ✓ Initializr scaffold complete.                         ║"
echo "║    Next: ./ai-scripts/run-phase.sh 1                     ║"
echo "╚══════════════════════════════════════════════════════════╝"
