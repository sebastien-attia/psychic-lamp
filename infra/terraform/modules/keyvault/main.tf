# Key Vault module — RBAC-only authorization, public access disabled,
# private endpoint, and the six secrets the workload needs.
#
# Authorization model
# ───────────────────
# `enable_rbac_authorization = true` is the only auth path; no
# `access_policy { }` blocks. The role assignments at the bottom of this
# file are the sole grants that exist on the vault.
#
# Network access
# ──────────────
# `public_network_access_enabled = true` + `network_acls.default_action =
# "Allow"`. The control-plane endpoint is reachable from any IP; RBAC +
# Entra ID is the only auth gate (no anonymous access). This matches the
# deploy workflow's "no VNet runner" decision: GitHub-hosted runners on
# public IPs would otherwise get 403 ForbiddenByConnection when writing
# secrets at apply time. A private-endpoint-only posture is still the
# right end state — flip both values back to false / "Deny" once a
# self-hosted runner inside the VNet (or a private GitHub runner pool)
# is wired up. The private endpoint is still provisioned below so apps
# inside the ACA Environment resolve via VNet.
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

  enable_rbac_authorization = true

  public_network_access_enabled = true

  soft_delete_retention_days = var.soft_delete_retention_days
  purge_protection_enabled   = true

  network_acls {
    # Network-layer filter is open; auth is enforced exclusively by RBAC
    # (every grant is in this module's role-assignment block at the bottom).
    # Flip default_action to "Deny" and add ip_rules / VNet-runner subnet
    # once we have a private apply path.
    default_action             = "Allow"
    bypass                     = "AzureServices"
    ip_rules                   = []
    virtual_network_subnet_ids = []
  }

  tags = var.tags
}

# ── Private endpoint ──────────────────────────────────────────────────────
resource "azurerm_private_endpoint" "kv" {
  name                = "${local.vault_name}-pe"
  location            = var.location
  resource_group_name = var.resource_group_name
  subnet_id           = var.keyvault_subnet_id

  private_service_connection {
    name                           = "kv"
    private_connection_resource_id = azurerm_key_vault.this.id
    subresource_names              = ["vault"]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = "kv-dns"
    private_dns_zone_ids = [var.keyvault_private_dns_zone_id]
  }

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
# below. Grant it BEFORE any azurerm_key_vault_secret resources are
# evaluated — depends_on on the secrets enforces that ordering.
resource "azurerm_role_assignment" "kv_secrets_officer" {
  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = var.tf_apply_principal_id
}

# Each consumer's MI gets read-only access (Secrets User = read + list).
resource "azurerm_role_assignment" "kv_secrets_user" {
  for_each = var.consumer_principal_ids

  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = each.value
}

# ── Secrets ───────────────────────────────────────────────────────────────
# expiration_date is intentionally NOT set: driving it from `timestamp()`
# triggers a no-op rewrite on every plan, and a hard-coded date drifts.
# Soft-delete + purge_protection cover accidental loss; rotation is owned
# by Ansible (and a future rotation_policy when the POC graduates).
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
