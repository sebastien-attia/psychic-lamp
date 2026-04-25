#!/usr/bin/env bash
# Phase 4 — GitHub Actions CI/CD → Azure
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "4" "CI/CD"

WF_DIR=".github/workflows"
if [ ! -d "${WF_DIR}" ]; then
  fail "${WF_DIR}/ missing"
  check_summary; exit 1
fi

# YAML parse
if command -v python3 >/dev/null && python3 -c "import yaml" 2>/dev/null; then
  while IFS= read -r -d '' f; do
    if python3 -c "import yaml,sys; yaml.safe_load(open('${f}'))" 2>/dev/null; then
      pass "YAML parses: ${f}"
    else
      fail "YAML parse error: ${f}"
    fi
  done < <(find "${WF_DIR}" -name '*.yml' -print0 2>/dev/null)
else
  warn "python3+pyyaml unavailable — skipping YAML parse"
fi

# Required workflows
for wf in ci deploy-staging deploy-production; do
  if [ -f "${WF_DIR}/${wf}.yml" ] || [ -f "${WF_DIR}/${wf}.yaml" ]; then
    pass "workflow present: ${wf}.yml"
  else
    fail "workflow missing: ${wf}.yml"
  fi
done

# Security: OIDC federation (no long-lived Azure secrets)
if grep -rqE 'azure/login@' "${WF_DIR}"; then
  pass "azure/login action used"
fi
if grep -rqE 'id-token:\s*write|permissions:' "${WF_DIR}"; then
  pass "OIDC permissions declared"
else
  fail "no OIDC permissions — workflows likely use long-lived Azure secrets"
fi

# No hardcoded secrets
if grep -rInE 'AZURE_CLIENT_SECRET|AZURE_PASSWORD' "${WF_DIR}" 2>/dev/null | grep -v '\${{' >/dev/null; then
  fail "hardcoded Azure credentials in workflows"
else
  pass "no hardcoded Azure credentials"
fi

# ACR admin credentials must not be referenced — push is via Entra ID OIDC
if grep -rInE 'ACR_ADMIN_USERNAME|ACR_ADMIN_PASSWORD|ACR_USERNAME|ACR_PASSWORD|registry-username|registry-password' "${WF_DIR}" 2>/dev/null; then
  fail "workflows reference ACR admin credentials — must use `az acr login` via OIDC (AcrPush role)"
else
  pass "no ACR admin credentials in workflows"
fi

# Require `az acr login` somewhere in the deploy workflows
for wf in deploy-staging deploy-production; do
  WF_FILE="${WF_DIR}/${wf}.yml"
  [ -f "${WF_FILE}" ] || continue
  if grep -qE 'az\s+acr\s+login' "${WF_FILE}"; then
    pass "${wf}.yml uses `az acr login` (OIDC-federated token, no admin creds)"
  else
    fail "${wf}.yml missing `az acr login` — ACR push path not wired to OIDC"
  fi
done

# Staging auto-deploy on push to staging branch
if [ -f "${WF_DIR}/deploy-staging.yml" ] && grep -qE 'branches:.*staging' "${WF_DIR}/deploy-staging.yml"; then
  pass "staging auto-deploys on push to staging branch"
else
  warn "deploy-staging trigger not on 'staging' branch"
fi

# Production deploys on release
if [ -f "${WF_DIR}/deploy-production.yml" ] && grep -qE 'on:\s*release|types:\s*\[published\]' "${WF_DIR}/deploy-production.yml"; then
  pass "production deploys on GitHub Release"
else
  warn "deploy-production trigger not 'release: types: [published]'"
fi

# Required CI stages
if [ -f "${WF_DIR}/ci.yml" ]; then
  for job in build test; do
    if grep -qE "^\s*${job}:" "${WF_DIR}/ci.yml"; then
      pass "ci.yml job: ${job}"
    else
      warn "ci.yml missing job: ${job}"
    fi
  done

  # Supply-chain gate: OSV-Scanner (SCA) must run in CI
  if grep -qE "^\s*sca-scan:" "${WF_DIR}/ci.yml" \
     || grep -qE 'google/osv-scanner-action' "${WF_DIR}/ci.yml"; then
    pass "ci.yml runs OSV-Scanner (SCA) via google/osv-scanner-action"
  else
    fail "ci.yml missing sca-scan job — SCA gate not wired"
  fi
fi

# Dependency-Track upload (governance) must run in staging + production deploys
for wf in deploy-staging deploy-production; do
  WF_FILE="${WF_DIR}/${wf}.yml"
  [ -f "${WF_FILE}" ] || continue
  if grep -qE 'dependency-track:upload-bom|dtrack\.skip=false' "${WF_FILE}"; then
    pass "${wf}.yml uploads CycloneDX BOM to Dependency-Track"
  else
    fail "${wf}.yml missing Dependency-Track BOM upload step"
  fi
  if grep -qE 'DTRACK_URL' "${WF_FILE}" && grep -qE 'DTRACK_API_KEY' "${WF_FILE}"; then
    pass "${wf}.yml references DTRACK_URL + DTRACK_API_KEY secrets"
  else
    fail "${wf}.yml must pass DTRACK_URL and DTRACK_API_KEY from secrets"
  fi
done

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
