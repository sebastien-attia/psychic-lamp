# Key Vault module — RBAC-only authorization, public endpoint, and the six
# secrets the workload needs.
#
# Authorization model
# ───────────────────
# `enable_rbac_authorization = true` is the only auth path; no
# `access_policy { }` blocks. The role assignments at the bottom of this
# file are the sole grants on the vault. The Web Apps' system-assigned MIs
# are granted Key Vault Secrets User by the app-service module after the
# vault and the apps both exist.
#
# Network access
# ──────────────
# `public_network_access_enabled = true` + `network_acls.default_action =
# "Allow"`. App Service resolves @Microsoft.KeyVault references over the
# public Azure backbone using the Web App's MI; no VNet path is required.
# This is the simplification the rest of this redesign is built around.
#
# BFF signing key
# ───────────────
# The BFF authenticates to Keycloak with a signed JWT client_assertion
# (RFC 7523 / private_key_jwt). The keypair is generated in-module via
# tls_private_key and the PEM is piped straight into a Key Vault secret —
# the unencrypted PEM never lands in .tfvars or in any output. There is
# no shared keycloak_client_secret variable in this design.

locals {
  # KV names: 3-24 chars, alphanumerics + hyphens, must start with a letter.
  vault_name = substr(replace("${var.project_name}-${var.environment}-kv", "_", ""), 0, 24)
}

data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "this" {
  name                = local.vault_name
  location            = var.location
  resource_group_name = var.resource_group_name
  tenant_id           = data.azurerm_client_config.current.tenant_id

  sku_name = "standard"

  rbac_authorization_enabled = true

  public_network_access_enabled = true

  soft_delete_retention_days = var.soft_delete_retention_days
  purge_protection_enabled   = true

  # No network_acls block — the RBAC layer (Key Vault Secrets User on the
  # Web Apps' MIs) is the access boundary; default network access is
  # unrestricted so App Service can resolve @Microsoft.KeyVault references
  # over the Azure backbone without bypass tricks.

  tags = var.tags
}

# ── BFF private_key_jwt signing keypair ───────────────────────────────────
# RSA 2048 generated in Terraform; private PEM goes straight into a secret.
resource "tls_private_key" "bff_signing" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

# ── Role assignments ──────────────────────────────────────────────────────
# The TF apply identity needs Secrets Officer to write the secret payloads
# below. depends_on on the secrets enforces ordering.
resource "azurerm_role_assignment" "kv_secrets_officer" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = var.tf_apply_principal_id
}

# ── Secrets ───────────────────────────────────────────────────────────────
# expiration_date is intentionally NOT set: driving it from `timestamp()`
# triggers a no-op rewrite on every plan, and a hard-coded date drifts.
# Soft-delete + purge_protection cover accidental loss.
locals {
  password_secrets = {
    "postgres-admin-password" = var.postgres_admin_password
    "bff-db-password"         = var.bff_db_password
    "business-db-password"    = var.business_db_password
    "keycloak-db-password"    = var.keycloak_db_password
    "keycloak-admin-password" = var.keycloak_admin_password
  }
}

resource "azurerm_key_vault_secret" "passwords" {
  for_each = local.password_secrets

  name         = each.key
  value        = each.value
  key_vault_id = azurerm_key_vault.this.id
  content_type = "password"

  tags = var.tags

  depends_on = [azurerm_role_assignment.kv_secrets_officer]
}

resource "azurerm_key_vault_secret" "bff_signing_key" {
  name         = "bff-signing-key"
  value        = tls_private_key.bff_signing.private_key_pem
  key_vault_id = azurerm_key_vault.this.id
  content_type = "application/x-pem-file"

  tags = var.tags

  depends_on = [azurerm_role_assignment.kv_secrets_officer]
}
