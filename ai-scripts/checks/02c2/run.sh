#!/usr/bin/env bash
# Phase 02c2 — Terraform modules for Azure
set -u
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${HERE}/../_lib.sh"

WORK_DIR="${1:-.}"
cd "${WORK_DIR}" || { fail "work dir ${WORK_DIR} missing"; check_summary; exit 1; }
check_init "02c2" "Terraform"

TF_DIR="infra/terraform"
if [ ! -d "${TF_DIR}" ]; then
  fail "${TF_DIR}/ directory missing"
  check_summary; exit 1
fi

cd "${TF_DIR}" || exit 1

if command -v terraform >/dev/null; then
  run_check "terraform fmt -check -recursive" -- terraform fmt -check -recursive
  # init needs a backend — skip, just validate
  run_check "terraform validate" -- bash -c 'terraform init -backend=false >/dev/null 2>&1 && terraform validate'
else
  warn "terraform CLI not available — skipping validate"
fi

# tflint optional
if command -v tflint >/dev/null; then
  run_check "tflint" -- tflint --recursive
else
  info "tflint not installed — static analysis skipped"
fi

# Required modules
for mod in networking database container-registry container-apps keyvault; do
  if [ -d "modules/${mod}" ]; then
    pass "module present: ${mod}"
  else
    fail "module missing: ${mod}"
  fi
done

# Environment configs
for env in staging production; do
  if [ -d "environments/${env}" ]; then
    pass "environment config: ${env}"
  else
    fail "environment config missing: ${env}"
  fi
done

# Security: no literal secrets; sensitive vars use var.* references
hits="$(grep -rInE '(password|secret)\s*=\s*"[A-Za-z0-9@#\$%!]{6,}"' \
         . --include='*.tf' 2>/dev/null | grep -v 'var\.' | grep -v '^\s*#')"
if [ -z "${hits}" ]; then
  pass "no secret literals in .tf files"
else
  fail "secret literal(s) in .tf files:"
  printf '%s\n' "${hits}" | sed 's/^/      /'
fi

# Remote state / backend
if grep -rq 'backend "azurerm"' . 2>/dev/null; then
  pass "remote state backend (azurerm) configured"
else
  warn "no azurerm remote backend found — state will live locally"
fi

# Switzerland North region
if grep -rqE 'switzerlandnorth|switzerland_north' . 2>/dev/null; then
  pass "Switzerland North region referenced"
else
  warn "Switzerland North not referenced (project convention for Geneva proximity)"
fi

# No shared client_secret variable
if grep -rqE '^\s*variable\s+"keycloak_client_secret"' . 2>/dev/null; then
  fail "keycloak_client_secret variable declared — auth must use private_key_jwt (bff_signing_key)"
else
  pass "no keycloak_client_secret variable (private_key_jwt enforced)"
fi

# ── Keycloak Container App: prod command must be `start`, not `start-dev` ───
if grep -rqE 'keycloak/keycloak:26\.[6-9]|keycloak/keycloak:(2[7-9]|[3-9][0-9])' . --include='*.tf' 2>/dev/null; then
  pass "Keycloak image pinned to 26.6+ in Terraform"
elif grep -rqE 'keycloak/keycloak:' . --include='*.tf' 2>/dev/null; then
  fail "Keycloak image pin in Terraform is stale (<26.6) — bump to 26.6.1 or newer"
else
  warn "no Keycloak image reference in .tf files — check module declaration"
fi

if grep -rqE '"start-dev"' . --include='*.tf' 2>/dev/null; then
  fail "Keycloak Container App uses start-dev — production must use \"start\" (optimized)"
fi
if grep -rqE 'command\s*=\s*\[\s*"start"' . --include='*.tf' 2>/dev/null; then
  pass "Keycloak Container App command = start (production mode)"
else
  fail "Keycloak Container App command not explicit — must be command = [\"start\", \"--optimized\"]"
fi

# Required KC_* env vars on the Keycloak Container App
for kv in KC_HOSTNAME KC_PROXY_HEADERS KC_HTTP_ENABLED KC_HEALTH_ENABLED KC_METRICS_ENABLED KC_DB; do
  if grep -rqE "name\s*=\s*\"${kv}\"|${kv}\s*=" . --include='*.tf' 2>/dev/null; then
    pass "Keycloak env var declared: ${kv}"
  else
    fail "Keycloak env var missing: ${kv}"
  fi
done

# ── Database module: 3 databases on 1 Flexible Server instance ─────────────
if [ -d modules/database ]; then
  if grep -rqE 'resource\s+"azurerm_postgresql_flexible_server"' modules/database --include='*.tf' 2>/dev/null; then
    pass "modules/database declares an azurerm_postgresql_flexible_server"
  else
    fail "modules/database must declare exactly one azurerm_postgresql_flexible_server"
  fi
  if grep -rqE 'resource\s+"azurerm_postgresql_flexible_server_database"' modules/database --include='*.tf' 2>/dev/null; then
    pass "modules/database declares azurerm_postgresql_flexible_server_database resources"
  else
    fail "modules/database must declare azurerm_postgresql_flexible_server_database resources"
  fi
  for dbname in bff_session boatapp keycloak; do
    if grep -rqE "\"${dbname}\"" modules/database --include='*.tf' 2>/dev/null; then
      pass "database declared in Terraform: ${dbname}"
    else
      fail "database missing in Terraform module: ${dbname}"
    fi
  done
else
  fail "modules/database directory missing"
fi

# ── Container Registry: admin MUST be disabled; pulls via managed identity ─
if [ -d modules/container-registry ]; then
  if grep -rqE 'admin_enabled\s*=\s*true' modules/container-registry --include='*.tf' 2>/dev/null; then
    fail "ACR admin_enabled = true — POC baseline requires admin_enabled = false (managed-identity pulls only)"
  elif grep -rqE 'admin_enabled\s*=\s*false' modules/container-registry --include='*.tf' 2>/dev/null; then
    pass "ACR admin_enabled = false"
  else
    fail "ACR admin_enabled not explicitly set in modules/container-registry/ — must be false"
  fi

  if grep -rqE 'output\s+"(admin_username|admin_password)"' modules/container-registry --include='*.tf' 2>/dev/null; then
    fail "modules/container-registry exports admin_username / admin_password — remove both outputs"
  else
    pass "modules/container-registry does not export ACR admin credentials"
  fi

  # AcrPull role assignment must exist (either in container-registry or container-apps module)
  if grep -rqE 'role_definition_name\s*=\s*"AcrPull"' . --include='*.tf' 2>/dev/null; then
    pass "AcrPull role assignment declared (MI-based image pulls)"
  else
    fail "no AcrPull role assignment — Container Apps cannot pull images from ACR via managed identity"
  fi

  # AcrPush for the CI federated identity
  if grep -rqE 'role_definition_name\s*=\s*"AcrPush"' . --include='*.tf' 2>/dev/null; then
    pass "AcrPush role assignment declared (CI OIDC pushes without admin creds)"
  else
    warn "no AcrPush role assignment — CI must push via Entra ID OIDC, not ACR admin"
  fi
fi

# Container Apps `registry { }` blocks must use managed-identity auth, not passwords
if [ -d modules/container-apps ]; then
  if grep -rqE 'password_secret_name\s*=' modules/container-apps --include='*.tf' 2>/dev/null; then
    fail "modules/container-apps registry block uses password_secret_name — must use identity = \"system\""
  fi
  if grep -rqE 'identity\s*=\s*"system"' modules/container-apps --include='*.tf' 2>/dev/null; then
    pass "Container Apps registry block uses identity = \"system\" (MI-based pull)"
  else
    fail "Container Apps registry block missing `identity = \"system\"` — MI pull not wired"
  fi

  # Liquibase migrations must run as ACA Jobs (not ACI, not plain containers)
  if grep -rqE 'resource\s+"azurerm_container_app_job"' modules/container-apps --include='*.tf' 2>/dev/null; then
    pass "modules/container-apps declares azurerm_container_app_job (Liquibase)"
  else
    fail "modules/container-apps must declare azurerm_container_app_job resources for Liquibase migrations"
  fi
  for target in bff business_service; do
    if grep -rqE "\"${target}\"\s*=" modules/container-apps --include='*.tf' 2>/dev/null \
       || grep -rqE "liquibase-?(${target}|${target//_/-})" modules/container-apps --include='*.tf' 2>/dev/null; then
      pass "Liquibase job target declared: ${target}"
    else
      fail "Liquibase job target missing: ${target}"
    fi
  done
fi

# No ACI anywhere in the Terraform tree — migrations moved to ACA Jobs
if grep -rqE 'resource\s+"azurerm_container_group"' . --include='*.tf' 2>/dev/null; then
  fail "azurerm_container_group (ACI) resource declared — migrations must use ACA Jobs, not ACI"
else
  pass "no ACI (azurerm_container_group) resources — Jobs-only posture"
fi

# ── Key Vault: four DB-related secrets ─────────────────────────────────────
if [ -d modules/keyvault ]; then
  for secret in postgres-admin-password bff-db-password business-db-password keycloak-db-password; do
    if grep -rqE "name\s*=\s*\"${secret}\"|\"${secret}\"" modules/keyvault --include='*.tf' 2>/dev/null; then
      pass "Key Vault secret declared: ${secret}"
    else
      fail "Key Vault secret missing: ${secret}"
    fi
  done

  # Authorization model: Azure RBAC, not access policies
  if grep -rqE 'enable_rbac_authorization\s*=\s*true' modules/keyvault --include='*.tf' 2>/dev/null; then
    pass "Key Vault enable_rbac_authorization = true"
  else
    fail "Key Vault must set enable_rbac_authorization = true (access policies are legacy)"
  fi
  if grep -rqE '^\s*access_policy\s*\{' modules/keyvault --include='*.tf' 2>/dev/null; then
    fail "Key Vault has access_policy { } blocks — remove them, RBAC is authoritative"
  else
    pass "Key Vault has no legacy access_policy { } blocks"
  fi

  # Network access: deny by default, private endpoint present
  if grep -rqE 'public_network_access_enabled\s*=\s*false' modules/keyvault --include='*.tf' 2>/dev/null; then
    pass "Key Vault public_network_access_enabled = false"
  else
    fail "Key Vault public_network_access_enabled must be false"
  fi
  if grep -rqE 'default_action\s*=\s*"Deny"' modules/keyvault --include='*.tf' 2>/dev/null; then
    pass "Key Vault network_acls default_action = Deny"
  else
    fail "Key Vault network_acls must default_action = \"Deny\""
  fi
  if grep -rqE 'resource\s+"azurerm_private_endpoint"' modules/keyvault --include='*.tf' 2>/dev/null \
     && grep -rqE 'subresource_names\s*=\s*\[\s*"vault"\s*\]' modules/keyvault --include='*.tf' 2>/dev/null; then
    pass "Key Vault private endpoint declared (subresource = vault)"
  else
    fail "Key Vault private endpoint missing — expected azurerm_private_endpoint with subresource \"vault\""
  fi

  # RBAC role assignments on the vault
  if grep -rqE 'role_definition_name\s*=\s*"Key Vault Secrets User"' modules/keyvault --include='*.tf' 2>/dev/null; then
    pass "Key Vault Secrets User role assignment present (consumer MIs)"
  else
    fail "missing Key Vault Secrets User role assignment — apps cannot read secrets via MI"
  fi
  if grep -rqE 'role_definition_name\s*=\s*"Key Vault Secrets Officer"' modules/keyvault --include='*.tf' 2>/dev/null; then
    pass "Key Vault Secrets Officer role assignment present (CI writes secrets)"
  else
    warn "no Key Vault Secrets Officer role assignment — `terraform apply` cannot write secret material"
  fi
fi

# ── Networking: Key Vault private DNS zone must exist ──────────────────────
if [ -d modules/networking ]; then
  if grep -rqE 'privatelink\.vaultcore\.azure\.net' modules/networking --include='*.tf' 2>/dev/null; then
    pass "privatelink.vaultcore.azure.net private DNS zone declared"
  else
    fail "networking module missing privatelink.vaultcore.azure.net private DNS zone"
  fi
fi

# ── Code-review policy survives this phase (shared) ────────────────────────
check_review_policy

check_summary
