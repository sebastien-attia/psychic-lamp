# Database module — one PostgreSQL Flexible Server hosting three logical
# databases (bff_session, boatapp, keycloak).
#
# Per-database application roles (`bff`, `business_service`, `keycloak`) are
# NOT created here; they are bootstrapped post-apply by the Ansible
# `bootstrap-db-roles.yml` playbook (phase 02c3) using the server-level
# administrator_login. This split keeps Terraform out of role/grant
# management and lets Ansible idempotently re-grant when migrations rotate.
#
# SSL: `require_secure_transport=ON` is the Flexible Server default on v17;
# clients must use sslmode=require, which the jdbc_urls output below
# encodes verbatim.

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

  delegated_subnet_id = var.database_subnet_id
  private_dns_zone_id = var.postgres_private_dns_zone_id

  public_network_access_enabled = false

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
