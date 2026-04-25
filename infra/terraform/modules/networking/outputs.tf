# Outputs from the networking module.

output "resource_group_name" {
  description = "Name of the project's resource group; every other module places resources inside it."
  value       = azurerm_resource_group.this.name
}

output "location" {
  description = "Azure region of the resource group; re-emitted for convenience so siblings do not need a second var.location reference."
  value       = azurerm_resource_group.this.location
}

output "vnet_id" {
  description = "ID of the project virtual network."
  value       = azurerm_virtual_network.this.id
}

output "container_apps_subnet_id" {
  description = "ID of the subnet delegated to Microsoft.App/environments. Used by the Container Apps Environment and the Key Vault network ACL."
  value       = azurerm_subnet.container_apps.id
}

output "database_subnet_id" {
  description = "ID of the subnet delegated to Microsoft.DBforPostgreSQL/flexibleServers."
  value       = azurerm_subnet.database.id
}

output "keyvault_subnet_id" {
  description = "ID of the subnet that hosts the Key Vault private endpoint NIC."
  value       = azurerm_subnet.keyvault.id
}

output "postgres_private_dns_zone_id" {
  description = "ID of the privatelink.postgres.database.azure.com zone. Passed to the Flexible Server's private_dns_zone_id."
  value       = azurerm_private_dns_zone.postgres.id
}

output "keyvault_private_dns_zone_id" {
  description = "ID of the privatelink.vaultcore.azure.net zone. Passed to the Key Vault private endpoint's private_dns_zone_group."
  value       = azurerm_private_dns_zone.keyvault.id
}
