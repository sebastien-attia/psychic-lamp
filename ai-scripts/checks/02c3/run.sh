#!/usr/bin/env bash
# Phase 02c3 — Ansible playbooks for Azure
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02c3" "Ansible"

ANS_DIR="infra/ansible"
if [ ! -d "${ANS_DIR}" ]; then
  fail "${ANS_DIR}/ directory missing"
  check_summary; exit 1
fi
cd "${ANS_DIR}" || exit 1

# Required playbooks
for pb in deploy.yml configure-keycloak.yml run-migrations.yml health-check.yml rollback.yml; do
  if [ -f "playbooks/${pb}" ]; then
    pass "playbook present: ${pb}"
  else
    fail "playbook missing: ${pb}"
  fi
done

if command -v ansible-playbook >/dev/null; then
  for pb in playbooks/*.yml; do
    [ -f "${pb}" ] || continue
    run_check "syntax-check: ${pb##*/}" -- ansible-playbook --syntax-check "${pb}"
  done
else
  warn "ansible-playbook not installed — skipping syntax-check"
fi

if command -v ansible-lint >/dev/null; then
  run_check "ansible-lint" -- ansible-lint playbooks/
else
  info "ansible-lint not installed — skipping"
fi

# Security: no plaintext secrets in playbooks/roles
hits="$(grep -rInE '(password|secret):\s*[A-Za-z0-9@#\$%!]{6,}' \
         playbooks/ roles/ 2>/dev/null | grep -v 'vault_\|{{' | grep -v '^\s*#')"
if [ -z "${hits}" ]; then
  pass "no plaintext secret values in playbooks/roles"
else
  fail "plaintext secret value(s) detected:"
  printf '%s\n' "${hits}" | sed 's/^/      /'
fi

# Vault example present
if [ -f vault/secrets.yml.example ] || [ -f group_vars/vault.yml.example ]; then
  pass "vault example file committed"
else
  warn "no vault example file — users won't know which secrets to supply"
fi

# configure-keycloak.yml must delegate to keycloak-config-cli, not use community.general.keycloak_*
if [ -f playbooks/configure-keycloak.yml ]; then
  if grep -qE 'adorsys/keycloak-config-cli' playbooks/configure-keycloak.yml; then
    pass "configure-keycloak.yml uses adorsys/keycloak-config-cli (canonical YAML)"
  else
    fail "configure-keycloak.yml does not run adorsys/keycloak-config-cli — migrate off community.general.keycloak_*"
  fi

  if grep -qE 'IMPORT_FILES_LOCATIONS:[[:space:]]*.*/realm\.yaml' playbooks/configure-keycloak.yml; then
    pass "configure-keycloak.yml imports realm.yaml (source of truth)"
  else
    fail "configure-keycloak.yml missing IMPORT_FILES_LOCATIONS → /config/realm.yaml"
  fi

  if grep -qE 'IMPORT_VAR_SUBSTITUTION_ENABLED:[[:space:]]*"?true' playbooks/configure-keycloak.yml; then
    pass "IMPORT_VAR_SUBSTITUTION_ENABLED=true (per-env URLs resolved)"
  else
    fail "IMPORT_VAR_SUBSTITUTION_ENABLED not enabled — realm.yaml placeholders won't resolve"
  fi

  # Must NOT use the deprecated community.general.keycloak_* modules
  if grep -qE 'community\.general\.keycloak_(client|realm|user|group|role)' playbooks/configure-keycloak.yml; then
    fail "community.general.keycloak_* module still referenced — replaced by keycloak-config-cli"
  else
    pass "no community.general.keycloak_* modules (consolidated on config-cli)"
  fi
fi

# Inventory files
for env in staging production; do
  if [ -f "inventory/${env}.yml" ] || [ -f "inventory/${env}" ]; then
    pass "inventory: ${env}"
  else
    warn "inventory missing: ${env}"
  fi
done

# run-migrations.yml must invoke Azure Container Apps Jobs — not ACI, not docker run
if [ -f playbooks/run-migrations.yml ]; then
  if grep -qE 'az\s+containerapp\s+job\s+(start|execution)' playbooks/run-migrations.yml; then
    pass "run-migrations.yml invokes Azure Container Apps Jobs"
  else
    fail "run-migrations.yml missing `az containerapp job start` — migrations must run as ACA Jobs"
  fi
  if grep -qE 'az\s+container\s+create|azurerm_container_group' playbooks/run-migrations.yml; then
    fail "run-migrations.yml still references ACI (`az container create`) — retired in favor of ACA Jobs"
  else
    pass "run-migrations.yml has no ACI references"
  fi
fi

# bootstrap-db-roles.yml must NOT open transient PG firewall rules
if [ -f playbooks/bootstrap-db-roles.yml ]; then
  if grep -qE 'flexible-server\s+firewall-rule\s+create' playbooks/bootstrap-db-roles.yml; then
    fail "bootstrap-db-roles.yml opens a transient PG firewall rule — runner must live inside the VNet instead"
  else
    pass "bootstrap-db-roles.yml has no transient PG firewall rule"
  fi
  if grep -qE 'runner_public_ip' playbooks/bootstrap-db-roles.yml; then
    fail "bootstrap-db-roles.yml references runner_public_ip — deny-by-default posture requires in-VNet runner"
  fi
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
