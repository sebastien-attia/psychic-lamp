# Database module — one PostgreSQL Flexible Server hosting three logical
# databases (bff_session, boatapp, keycloak).
#
# Network posture
# ───────────────
# Public access is enabled. Two firewall rules control reachability:
#   1. AllowAllAzureServicesAndResourcesWithinAzureIps — the special
#      0.0.0.0 → 0.0.0.0 rule that lets any Azure-hosted service reach
#      the server. App Service Web Apps + GitHub Actions runners (which
#      run on Microsoft-hosted Azure VMs) both benefit from this rule —
#      no per-IP allowlisting, no for_each on a computed list of
#      outbound IPs that shifts on every plan-tier change.
#   2. Per-IP rules from `additional_firewall_ips` — used by operators
#      to grant a workstation ad-hoc psql access; not wired by the
#      deploy workflow.
# `require_secure_transport=on` is the Flexible Server default on v17 —
# clients must use sslmode=require, which the jdbc_urls output below
# encodes verbatim.
#
# Per-database application roles (`bff`, `business_service`, `keycloak`) are
# NOT created here; they are bootstrapped by modules/db-bootstrap which
# runs psql against this server during `terraform apply`.

locals {
  databases = ["bff_session", "boatapp", "keycloak"]

  server_name = "${var.project_name}-${var.environment}-pg"
}

resource "azurerm_postgresql_flexible_server" "this" {
  name                = local.server_name
  resource_group_name = var.resource_group_name
  location            = var.location

  version  = var.postgres_version
  sku_name = var.sku_name

  storage_mb                   = var.storage_mb
  backup_retention_days        = var.backup_retention_days
  geo_redundant_backup_enabled = false

  administrator_login    = var.admin_username
  administrator_password = var.admin_password

  public_network_access_enabled = true

  authentication {
    password_auth_enabled = true
  }

  tags = var.tags

  lifecycle {
    # zone is auto-assigned by Azure on creation; ignoring drift prevents
    # gratuitous diffs after maintenance events that may shift the AZ.
    ignore_changes = [zone]

    # Destroying the Flexible Server destroys the production data store
    # of the entire app and is recoverable only from backup (with the
    # geo_redundant_backup_enabled = false posture, that's a regional
    # event). Force the operator to lift this guard explicitly for any
    # plan that would replace the server.
    #
    # Operator override flow when this guard trips:
    #   1. Comment out the `prevent_destroy = true` line (don't delete it).
    #   2. terraform plan — confirm the destroy is the one you intended.
    #   3. terraform apply.
    #   4. Restore the line in the same commit so master is never
    #      committed in the unprotected state.
    # (Known force-new attributes: delegated_subnet_id, sku tier family
    # changes between B_*/GP_*/MO_*, version downgrade.)
    prevent_destroy = true
  }
}

resource "azurerm_postgresql_flexible_server_database" "dbs" {
  for_each = toset(local.databases)

  name      = each.value
  server_id = azurerm_postgresql_flexible_server.this.id
  charset   = "UTF8"
  collation = "en_US.utf8"
}

# ── Firewall rules ────────────────────────────────────────────────────────
# Special-cased rule (start=end=0.0.0.0) that allows any Azure-hosted
# resource to connect. Covers App Service Web Apps in this subscription as
# well as GitHub-hosted Actions runners (which run on Azure VMs and inherit
# Azure egress IPs). Without this rule, we would need per-IP allowlisting
# of App Service outbound IPs — but those IPs are computed at apply time,
# which makes a Terraform for_each impossible on first apply, AND they
# rotate when the plan SKU changes.
resource "azurerm_postgresql_flexible_server_firewall_rule" "azure_services" {
  name             = "AllowAllAzureServicesAndResourcesWithinAzureIps"
  server_id        = azurerm_postgresql_flexible_server.this.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

# Optional ad-hoc operator IPs (workstation, break-glass psql). The deploy
# workflow does NOT wire this — the AllowAllAzure rule above already covers
# Microsoft-hosted runners.
resource "azurerm_postgresql_flexible_server_firewall_rule" "extra" {
  for_each = var.additional_firewall_ips

  name             = each.key
  server_id        = azurerm_postgresql_flexible_server.this.id
  start_ip_address = each.value
  end_ip_address   = each.value
}
