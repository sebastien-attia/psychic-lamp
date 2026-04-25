# Outputs from the database module.

output "id" {
  description = "Resource ID of the Flexible Server."
  value       = azurerm_postgresql_flexible_server.this.id
}

output "fqdn" {
  description = "Fully-qualified domain name of the Flexible Server. Resolves privately through the privatelink.postgres.database.azure.com zone."
  value       = azurerm_postgresql_flexible_server.this.fqdn
}

output "admin_username" {
  description = "Server-level administrator login. Re-emitted so the root module can hand it to Ansible alongside the Key Vault secret reference for the password."
  value       = azurerm_postgresql_flexible_server.this.administrator_login
}

output "jdbc_urls" {
  description = "Map of database name → JDBC URL (with sslmode=require) for every logical database on this Flexible Server. Consumed by the Container Apps module to populate DATABASE_URL / KC_DB_URL on the workload apps."
  value = {
    for db in local.databases :
    db => "jdbc:postgresql://${azurerm_postgresql_flexible_server.this.fqdn}:5432/${db}?sslmode=require"
  }
}

output "database_names" {
  description = "Set of logical database names provisioned on the server."
  value       = local.databases
}
