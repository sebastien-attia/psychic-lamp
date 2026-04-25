#!/usr/bin/env bash
# Phase 02a6 — Supply-chain security gates (SpotBugs+FindSecBugs, CycloneDX, Dependency-Track)
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02a6" "Backend Security Gates"

# ── Plugin coordinates declared in both poms ────────────────────────────────
for svc in bff business-service; do
  POM="${svc}/pom.xml"
  if [ ! -f "${POM}" ]; then
    fail "${POM} missing"
    continue
  fi

  # SpotBugs + FindSecBugs
  if grep -q '<artifactId>spotbugs-maven-plugin</artifactId>' "${POM}"; then
    pass "${svc}: spotbugs-maven-plugin declared"
  else
    fail "${svc}: spotbugs-maven-plugin missing in ${POM}"
  fi
  if grep -q '<artifactId>findsecbugs-plugin</artifactId>' "${POM}"; then
    pass "${svc}: findsecbugs-plugin declared"
  else
    fail "${svc}: findsecbugs-plugin missing in ${POM}"
  fi

  # CycloneDX SBOM
  if grep -q '<artifactId>cyclonedx-maven-plugin</artifactId>' "${POM}"; then
    pass "${svc}: cyclonedx-maven-plugin declared"
  else
    fail "${svc}: cyclonedx-maven-plugin missing in ${POM}"
  fi

  # Dependency-Track upload
  if grep -q '<artifactId>dependency-track-maven-plugin</artifactId>' "${POM}"; then
    pass "${svc}: dependency-track-maven-plugin declared"
  else
    fail "${svc}: dependency-track-maven-plugin missing in ${POM}"
  fi

  # Default skip flag — must be true for local builds not to contact DT
  if grep -qE '<dtrack\.skip>\s*true\s*</dtrack\.skip>' "${POM}"; then
    pass "${svc}: dtrack.skip defaults to true (local-safe)"
  else
    fail "${svc}: dtrack.skip must default to true in <properties>"
  fi
done

# ── SpotBugs filter files present and well-formed ───────────────────────────
for svc in bff business-service; do
  INC="${svc}/spotbugs-security-include.xml"
  EXC="${svc}/spotbugs-exclude-generated.xml"

  if [ -f "${INC}" ] && grep -q '<Category name="SECURITY"/>' "${INC}"; then
    pass "${svc}: SECURITY-only include filter present"
  else
    fail "${svc}: ${INC} missing or does not restrict to <Category name=\"SECURITY\"/>"
  fi

  if [ -f "${EXC}" ] && grep -q 'generated' "${EXC}"; then
    pass "${svc}: generated-code exclude filter present"
  else
    fail "${svc}: ${EXC} missing or does not exclude generated/** classes"
  fi
done

# ── Build gates actually execute ────────────────────────────────────────────
for svc in bff business-service; do
  if [ ! -x "${svc}/mvnw" ]; then
    fail "${svc}/mvnw missing"
    continue
  fi

  # verify runs SpotBugs (goal: check) — must pass
  run_check "${svc}: mvn verify (SpotBugs+FindSecBugs green)" -- \
    bash -c "cd ${svc} && ./mvnw -q verify"

  # package writes the CycloneDX BOMs
  run_check "${svc}: mvn package (CycloneDX SBOM generated)" -- \
    bash -c "cd ${svc} && ./mvnw -q package -DskipTests"

  BOM_JSON="${svc}/target/bom.json"
  BOM_XML="${svc}/target/bom.xml"
  if [ -s "${BOM_JSON}" ] && grep -q '"bomFormat"\s*:\s*"CycloneDX"' "${BOM_JSON}"; then
    pass "${svc}: target/bom.json is a valid CycloneDX BOM"
    if command -v jq >/dev/null 2>&1; then
      COMPONENTS="$(jq -r '.components | length' "${BOM_JSON}" 2>/dev/null || echo 0)"
      info "${svc}: BOM declares ${COMPONENTS} dependency components"
    fi
  else
    fail "${svc}: ${BOM_JSON} missing or not a CycloneDX BOM"
  fi
  [ -s "${BOM_XML}" ] && pass "${svc}: target/bom.xml present" \
                     || fail "${svc}: ${BOM_XML} missing"
done

# ── OSV convenience profile wired ───────────────────────────────────────────
for svc in bff business-service; do
  if grep -qE '<id>\s*osv\s*</id>' "${svc}/pom.xml" 2>/dev/null \
     && grep -q 'osv-scanner' "${svc}/pom.xml" 2>/dev/null; then
    pass "${svc}: 'osv' profile wraps osv-scanner via exec-maven-plugin"
  else
    warn "${svc}: no 'osv' profile — local devs cannot run \`./mvnw -Posv verify\`"
  fi
done

# ── .env.example documents DTRACK_* secrets ─────────────────────────────────
if grep -qE 'DTRACK_URL' .env.example 2>/dev/null \
   && grep -qE 'DTRACK_API_KEY' .env.example 2>/dev/null; then
  pass ".env.example documents DTRACK_URL + DTRACK_API_KEY"
else
  fail ".env.example must mention DTRACK_URL and DTRACK_API_KEY (commented, staging/prod only)"
fi

# ── CLAUDE.md has the new Security build gates section ──────────────────────
if grep -q 'Security build gates' CLAUDE.md 2>/dev/null; then
  pass "CLAUDE.md: 'Security build gates' section present"
else
  fail "CLAUDE.md: missing 'Security build gates' section"
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
