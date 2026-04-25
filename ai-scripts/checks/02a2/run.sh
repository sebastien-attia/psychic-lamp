#!/usr/bin/env bash
# Phase 02a2 — Domain layer (pure Java) + JPA adapters + Liquibase migrations
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02a2" "Backend Domain"

# ── Core: compile both services ─────────────────────────────────────────────
for svc in bff business-service; do
  [ -x "${svc}/mvnw" ] && run_check "${svc}: compile" -- bash -c "cd ${svc} && ./mvnw -q compile"
done

# ── Security of architecture: domain must be pure Java ──────────────────────
domain_dir="business-service/src/main/java"
if [ -d "${domain_dir}" ]; then
  # Check domain.model and domain.port.* for no Spring/Jakarta/JPA imports
  violations="$(grep -rEn 'import (org\.springframework|jakarta\.persistence|jakarta\.validation)' \
                  "${domain_dir}"/ch*/*/domain/model \
                  "${domain_dir}"/ch*/*/domain/port \
                  2>/dev/null || true)"
  if [ -z "${violations}" ]; then
    pass "business-service domain is pure Java (no Spring/Jakarta imports)"
  else
    fail "business-service domain has Spring/Jakarta imports:"
    printf '%s\n' "${violations}" | sed 's/^/      /'
  fi

  # JPA entities must live in adapter.out.persistence, NOT domain.model
  if grep -rq '@Entity' "${domain_dir}"/ch*/*/domain/model 2>/dev/null; then
    fail "@Entity annotation found in domain.model — must live in adapter.out.persistence"
  else
    pass "@Entity not in domain.model"
  fi
  if find "${domain_dir}" -type d -path '*/adapter/out/persistence*' 2>/dev/null | grep -q .; then
    if grep -rq '@Entity' "${domain_dir}"/ch*/*/adapter/out/persistence 2>/dev/null; then
      pass "JPA @Entity classes in adapter.out.persistence"
    else
      warn "adapter.out.persistence exists but no @Entity classes found"
    fi
  fi
else
  fail "business-service/src/main/java not found"
fi

# ── Liquibase migrations ────────────────────────────────────────────────────
cl_dir="business-service/src/main/resources/db/changelog"
if [ -d "${cl_dir}" ]; then
  pass "liquibase changelog directory present"
  master=""
  for m in db.changelog-master.yaml db.changelog-master.yml db.changelog-master.xml; do
    [ -f "${cl_dir}/${m}" ] && master="${cl_dir}/${m}"
  done
  if [ -n "${master}" ]; then
    pass "master changelog: ${master##*/}"
    for table in app_user boats boat_audit; do
      if grep -iq "${table}" "${cl_dir}"/*.yaml "${cl_dir}"/*.yml "${cl_dir}"/*.xml 2>/dev/null; then
        pass "liquibase covers table: ${table}"
      else
        fail "liquibase does not cover table: ${table}"
      fi
    done
  else
    fail "master changelog missing"
  fi

  # Sequential numbering (001-, 002-, …) — warn if not
  nonseq="$(find "${cl_dir}" -maxdepth 1 -type f \( -name '*.yaml' -o -name '*.yml' -o -name '*.xml' \) \
             | grep -v master | grep -vE '/[0-9]{3}-' | wc -l)"
  if [ "${nonseq}" -gt 0 ]; then
    warn "${nonseq} changelog file(s) lack 'NNN-' prefix (sequential numbering recommended)"
  else
    pass "changelog files use sequential 'NNN-' prefix"
  fi
else
  fail "liquibase changelog directory missing"
fi

# ── Liquibase dependency in pom ─────────────────────────────────────────────
if grep -q 'liquibase-core' business-service/pom.xml 2>/dev/null; then
  pass "liquibase-core declared in business-service/pom.xml"
else
  fail "liquibase-core dependency missing in business-service/pom.xml"
fi
if grep -q 'liquibase-core' bff/pom.xml 2>/dev/null; then
  pass "liquibase-core declared in bff/pom.xml"
else
  fail "liquibase-core dependency missing in bff/pom.xml (BFF owns its own bff_session Liquibase)"
fi

# ── BFF Liquibase (owns bff_session DB — SPRING_SESSION tables) ─────────────
bff_cl_dir="bff/src/main/resources/db/changelog"
if [ -d "${bff_cl_dir}" ]; then
  pass "BFF liquibase changelog directory present"
  bff_master=""
  for m in db.changelog-master.yaml db.changelog-master.yml db.changelog-master.xml; do
    [ -f "${bff_cl_dir}/${m}" ] && bff_master="${bff_cl_dir}/${m}"
  done
  if [ -n "${bff_master}" ]; then
    pass "BFF master changelog: ${bff_master##*/}"
    if grep -riq 'spring_session' "${bff_cl_dir}" 2>/dev/null; then
      pass "BFF liquibase covers table: spring_session"
    else
      fail "BFF liquibase does not cover SPRING_SESSION tables"
    fi
  else
    fail "BFF master changelog missing"
  fi
else
  fail "BFF liquibase changelog directory missing (bff/src/main/resources/db/changelog/)"
fi

# ── SPRING_SESSION must NOT live under business-service (ownership moved) ───
if grep -riq 'spring_session' business-service/src/main/resources/db/changelog 2>/dev/null; then
  fail "SPRING_SESSION tables found in business-service changelog — must live in BFF (bff_session DB)"
else
  pass "business-service changelog does not contain SPRING_SESSION (correct: BFF-owned)"
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
