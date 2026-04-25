#!/usr/bin/env bash
# Phase 4b — CI/CD hardening (SHA-pin, cosign+SLSA, Trivy, CodeQL, gitleaks,
#                              tfplan-artifact, DT gate, branch protection).
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "4b" "CI/CD hardening"

WF_DIR=".github/workflows"
ACT_DIR=".github/actions"
if [ ! -d "${WF_DIR}" ]; then
  fail "${WF_DIR}/ missing — run phase 4 first"
  check_summary; exit 1
fi

# ── 1. SHA-pinning: every `uses:` must point at a 40-hex-char SHA ───────────
SHA_FAILS=0
SHA_TOTAL=0
while IFS= read -r -d '' f; do
  while IFS= read -r line; do
    SHA_TOTAL=$((SHA_TOTAL + 1))
    # Permitted: `uses: ./...` (local composite action), `uses: docker://...`
    case "${line}" in
      *"uses:"*"./"*|*"uses:"*"docker://"*) continue ;;
    esac
    if ! printf '%s' "${line}" | grep -qE '@[0-9a-f]{40}\b'; then
      SHA_FAILS=$((SHA_FAILS + 1))
      printf '      %s — %s\n' "${f}" "$(printf '%s' "${line}" | sed 's/^[[:space:]]*//')"
    fi
  done < <(grep -hE '^[[:space:]]*-?[[:space:]]*uses:[[:space:]]*[^[:space:]]+' "${f}" || true)
done < <(find "${WF_DIR}" "${ACT_DIR}" -type f \( -name '*.yml' -o -name '*.yaml' \) -print0 2>/dev/null)

if [ "${SHA_TOTAL}" -eq 0 ]; then
  warn "no \`uses:\` declarations found — nothing to SHA-pin (did phase 4 run?)"
elif [ "${SHA_FAILS}" -eq 0 ]; then
  pass "all ${SHA_TOTAL} \`uses:\` declarations pinned to a 40-char SHA"
else
  fail "${SHA_FAILS}/${SHA_TOTAL} \`uses:\` lines NOT SHA-pinned (see above)"
fi

# ── 2. Concurrency control on deploy workflows ──────────────────────────────
for wf in deploy-staging deploy-production; do
  WF_FILE="${WF_DIR}/${wf}.yml"
  [ -f "${WF_FILE}" ] || { warn "${WF_FILE} missing — skipping concurrency check"; continue; }
  if grep -qE '^concurrency:' "${WF_FILE}"; then
    pass "${wf}.yml declares top-level concurrency:"
    if grep -qE 'cancel-in-progress:[[:space:]]*false' "${WF_FILE}"; then
      pass "${wf}.yml concurrency: cancel-in-progress=false (safe for tf apply)"
    else
      fail "${wf}.yml concurrency: cancel-in-progress must be false (mid-apply cancel = inconsistent Azure)"
    fi
  else
    fail "${wf}.yml missing top-level concurrency: group"
  fi
done

# ── 3. Image signing + SLSA provenance in deploy workflows ──────────────────
for wf in deploy-staging deploy-production; do
  WF_FILE="${WF_DIR}/${wf}.yml"
  [ -f "${WF_FILE}" ] || continue
  if grep -qE 'sigstore/cosign-installer' "${WF_FILE}"; then
    pass "${wf}.yml installs sigstore/cosign-installer"
  else
    fail "${wf}.yml missing sigstore/cosign-installer step"
  fi
  if grep -qE 'cosign[[:space:]]+sign' "${WF_FILE}"; then
    pass "${wf}.yml runs \`cosign sign\`"
  else
    fail "${wf}.yml missing \`cosign sign\` step"
  fi
  if grep -qE 'actions/attest-build-provenance' "${WF_FILE}"; then
    pass "${wf}.yml generates SLSA provenance via actions/attest-build-provenance"
  else
    fail "${wf}.yml missing actions/attest-build-provenance step"
  fi
  if grep -qE 'attestations:[[:space:]]*write' "${WF_FILE}"; then
    pass "${wf}.yml declares attestations: write permission"
  else
    fail "${wf}.yml missing \`attestations: write\` permission"
  fi
done

# ── 4. Trivy container scan in CI ────────────────────────────────────────────
CI_FILE="${WF_DIR}/ci.yml"
if [ -f "${CI_FILE}" ]; then
  if grep -qE 'aquasecurity/trivy-action' "${CI_FILE}"; then
    pass "ci.yml runs aquasecurity/trivy-action (container scan)"
  else
    fail "ci.yml missing aquasecurity/trivy-action — container vulnerability scan absent"
  fi
  if grep -qE "severity:[[:space:]]*['\"]?HIGH,CRITICAL" "${CI_FILE}"; then
    pass "ci.yml Trivy severity: HIGH,CRITICAL"
  else
    warn "ci.yml Trivy severity threshold not HIGH,CRITICAL"
  fi
  if grep -qE 'codeql-action/upload-sarif' "${CI_FILE}"; then
    pass "ci.yml uploads Trivy SARIF to GitHub Security tab"
  else
    fail "ci.yml missing SARIF upload — Trivy findings won't reach Security tab"
  fi
else
  fail "ci.yml missing — run phase 4 first"
fi

# ── 5. CodeQL workflow (or Semgrep fallback) ─────────────────────────────────
if [ -f "${WF_DIR}/codeql.yml" ]; then
  pass "codeql.yml present"
  if grep -qE 'java-kotlin' "${WF_DIR}/codeql.yml" && grep -qE 'javascript-typescript' "${WF_DIR}/codeql.yml"; then
    pass "codeql.yml matrix covers java-kotlin + javascript-typescript"
  else
    fail "codeql.yml missing java-kotlin or javascript-typescript in matrix"
  fi
  if grep -qE 'queries:.*security-extended' "${WF_DIR}/codeql.yml"; then
    pass "codeql.yml runs security-extended query suite"
  else
    warn "codeql.yml not running security-extended (default query pack only)"
  fi
elif [ -f "${CI_FILE}" ] && grep -qE 'returntocorp/semgrep|semgrep-action' "${CI_FILE}"; then
  pass "Semgrep fallback configured in ci.yml (private repo without GHAS)"
else
  fail "neither codeql.yml nor Semgrep fallback in ci.yml — source-SAST gap"
fi

# ── 6. Secret scanning (gitleaks) ────────────────────────────────────────────
if [ -f "${CI_FILE}" ] && grep -qE 'gitleaks/gitleaks-action' "${CI_FILE}"; then
  pass "ci.yml runs gitleaks-action (secret scan)"
  if [ -f .gitleaks.toml ]; then
    pass ".gitleaks.toml allowlist committed"
  else
    warn ".gitleaks.toml not committed — using default config (may produce noise on .env.example)"
  fi
else
  fail "ci.yml missing gitleaks-action — secret scanning absent"
fi

# ── 7. Terraform plan-as-artifact (split plan/apply) ─────────────────────────
for wf in deploy-staging deploy-production; do
  WF_FILE="${WF_DIR}/${wf}.yml"
  [ -f "${WF_FILE}" ] || continue
  if grep -qE 'terraform[[:space:]]+plan[[:space:]]+-out=' "${WF_FILE}" \
     && grep -qE 'actions/upload-artifact' "${WF_FILE}" \
     && grep -qE 'actions/download-artifact' "${WF_FILE}" \
     && grep -qE 'terraform[[:space:]]+apply[[:space:]]+(-input=false[[:space:]]+)?-auto-approve[[:space:]]+tfplan' "${WF_FILE}"; then
    pass "${wf}.yml: terraform plan persisted as artifact and applied verbatim"
  else
    fail "${wf}.yml: terraform plan/apply not split via artifact (expect plan -out, upload-artifact, download-artifact, apply tfplan.binary)"
  fi
done

# ── 8. Dependency-Track gate composite action ───────────────────────────────
if [ -f "${ACT_DIR}/dtrack-gate/action.yml" ]; then
  pass ".github/actions/dtrack-gate/action.yml exists"
  if grep -qE '/api/v1/violation/project' "${ACT_DIR}/dtrack-gate/action.yml"; then
    pass "dtrack-gate polls /api/v1/violation/project (policy gate, not just upload)"
  else
    fail "dtrack-gate does NOT query violations — it's a receipt, not a gate"
  fi
else
  fail ".github/actions/dtrack-gate/action.yml missing — DT remains a receipt, not a gate"
fi

# ── 9. Branch protection: settings.yml + bootstrap wiring ───────────────────
if [ -f .github/settings.yml ]; then
  pass ".github/settings.yml present (branch protection source of truth)"
  for ctx in lint sca-scan secret-scan build-bff build-business-service build-frontend container-scan e2e-tests; do
    if grep -qE "^[[:space:]]+-[[:space:]]+${ctx}([[:space:]]|$)" .github/settings.yml; then
      pass "settings.yml requires context: ${ctx}"
    else
      warn "settings.yml does not require context: ${ctx}"
    fi
  done
else
  fail ".github/settings.yml missing — branch protection not codified"
fi

if [ -f ai-scripts/00d-bootstrap-azure.sh ]; then
  if grep -qE 'apply_branch_protection' ai-scripts/00d-bootstrap-azure.sh; then
    pass "00d-bootstrap-azure.sh wires apply_branch_protection()"
  else
    fail "00d-bootstrap-azure.sh missing apply_branch_protection() — settings.yml is documentation only"
  fi
fi

# ── 10. Dependabot ───────────────────────────────────────────────────────────
if [ -f .github/dependabot.yml ]; then
  pass ".github/dependabot.yml present"
  for eco in github-actions maven npm; do
    if grep -qE "package-ecosystem:[[:space:]]*${eco}" .github/dependabot.yml; then
      pass "dependabot covers ${eco}"
    else
      fail "dependabot missing ecosystem: ${eco}"
    fi
  done
  # Both Maven modules
  bff_count=$(grep -cE 'directory:[[:space:]]*"?/bff"?' .github/dependabot.yml || true)
  bs_count=$(grep -cE 'directory:[[:space:]]*"?/business-service"?' .github/dependabot.yml || true)
  if [ "${bff_count:-0}" -ge 1 ] && [ "${bs_count:-0}" -ge 1 ]; then
    pass "dependabot covers both Maven modules (bff + business-service)"
  else
    fail "dependabot missing one of the Maven modules (bff=${bff_count}, business-service=${bs_count})"
  fi
else
  fail ".github/dependabot.yml missing"
fi

# ── 11. Code-review policy survives this phase (shared) ─────────────────────
check_review_policy

check_summary
