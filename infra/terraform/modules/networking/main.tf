# Networking module — resource group, VNet, three subnets, NSGs, and the
# private DNS zones used by the Flexible Server VNet integration and the
# Key Vault private endpoint.
#
# Subnet layout:
#   container-apps  10.0.1.0/24  delegated to Microsoft.App/environments
#   database        10.0.2.0/24  delegated to Microsoft.DBforPostgreSQL/flexibleServers
#   keyvault        10.0.3.0/24  hosts the KV private endpoint NIC
#                                (private_endpoint_network_policies disabled)
#
# Two private DNS zones are created here because both PostgreSQL Flexible
# Server (vault.privatelink) and Key Vault (vaultcore.privatelink) require
# private DNS resolution to terminate inside the VNet, and we want zone
# ownership co-located with the VNet that links them.

locals {
  name_prefix = "${var.project_name}-${var.environment}"
}

# ── Resource group ─────────────────────────────────────────────────────────
resource "azurerm_resource_group" "this" {
  name     = "${local.name_prefix}-rg"
  location = var.location
  tags     = var.tags
}

# ── Virtual network ────────────────────────────────────────────────────────
resource "azurerm_virtual_network" "this" {
  name                = "${local.name_prefix}-vnet"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  address_space       = [var.address_space]
  tags                = var.tags
}

# ── Subnets ────────────────────────────────────────────────────────────────
resource "azurerm_subnet" "container_apps" {
  name                 = "container-apps"
  resource_group_name  = azurerm_resource_group.this.name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [var.container_apps_subnet_cidr]

  delegation {
    name = "aca-delegation"
    service_delegation {
      name = "Microsoft.App/environments"
      # Azure persists `subnets/join/action` for Microsoft.App/environments
      # delegations (the canonical action per current ARM docs). The
      # previous `subnets/action` value caused a perpetual subnet diff at
      # every plan, which Terraform then tried to "fix" — and the resulting
      # subnet modification was the silent driver of the ACA-env replace
      # cascade that broke the staging deploy on commit 09385a9.
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_subnet" "database" {
  name                 = "database"
  resource_group_name  = azurerm_resource_group.this.name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [var.database_subnet_cidr]

  delegation {
    name = "pg-flex-delegation"
    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_subnet" "keyvault" {
  name                 = "keyvault"
  resource_group_name  = azurerm_resource_group.this.name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [var.keyvault_subnet_cidr]

  # Required so the private endpoint NIC is allowed to attach. Azure used to
  # auto-disable NSG policies on PE NICs; the azurerm v4 provider requires
  # this toggle to be explicit.
  private_endpoint_network_policies = "Disabled"
}

# ── NSGs ───────────────────────────────────────────────────────────────────
# Each subnet gets a deny-all-by-default NSG plus the minimum allow rules.
# Container Apps and Flexible Server both manage their own platform-level
# rules, so the NSGs intentionally do not over-specify allow rules and rely
# on the implicit AllowVnetInBound / AllowAzureLoadBalancerInBound.

resource "azurerm_network_security_group" "container_apps" {
  name                = "${local.name_prefix}-aca-nsg"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = var.tags
}

resource "azurerm_subnet_network_security_group_association" "container_apps" {
  subnet_id                 = azurerm_subnet.container_apps.id
  network_security_group_id = azurerm_network_security_group.container_apps.id
}

resource "azurerm_network_security_group" "database" {
  name                = "${local.name_prefix}-db-nsg"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = var.tags
}

resource "azurerm_subnet_network_security_group_association" "database" {
  subnet_id                 = azurerm_subnet.database.id
  network_security_group_id = azurerm_network_security_group.database.id
}

resource "azurerm_network_security_group" "keyvault" {
  name                = "${local.name_prefix}-kv-nsg"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  tags                = var.tags
}

resource "azurerm_subnet_network_security_group_association" "keyvault" {
  subnet_id                 = azurerm_subnet.keyvault.id
  network_security_group_id = azurerm_network_security_group.keyvault.id
}

# ── Private DNS zones ──────────────────────────────────────────────────────
# Flexible Server private DNS zone — the Flexible Server resource takes a
# private_dns_zone_id input and registers its own A records on creation.
resource "azurerm_private_dns_zone" "postgres" {
  name                = "privatelink.postgres.database.azure.com"
  resource_group_name = azurerm_resource_group.this.name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  name                  = "${local.name_prefix}-pg-link"
  resource_group_name   = azurerm_resource_group.this.name
  private_dns_zone_name = azurerm_private_dns_zone.postgres.name
  virtual_network_id    = azurerm_virtual_network.this.id
  registration_enabled  = false
  tags                  = var.tags
}

# Key Vault private DNS zone — bound to the private_dns_zone_group of the
# KV private endpoint in the keyvault module.
resource "azurerm_private_dns_zone" "keyvault" {
  name                = "privatelink.vaultcore.azure.net"
  resource_group_name = azurerm_resource_group.this.name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "keyvault" {
  name                  = "${local.name_prefix}-kv-link"
  resource_group_name   = azurerm_resource_group.this.name
  private_dns_zone_name = azurerm_private_dns_zone.keyvault.name
  virtual_network_id    = azurerm_virtual_network.this.id
  registration_enabled  = false
  tags                  = var.tags
}
