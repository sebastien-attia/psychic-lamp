# Outputs from the database module.

output "id" {
  description = "Resource ID of the Flexible Server. Used by the db-bootstrap module's trigger map so role bootstrap re-runs when the server is replaced."
  value       = azurerm_postgresql_flexible_server.this.id
}

output "fqdn" {
  description = "Fully-qualified domain name of the Flexible Server. Reachable from any IP on the firewall allowlist."
  value       = azurerm_postgresql_flexible_server.this.fqdn
}

output "admin_username" {
  description = "Server-level administrator login. Re-emitted so the db-bootstrap module can hand it to psql alongside the admin password."
  value       = azurerm_postgresql_flexible_server.this.administrator_login
}

output "jdbc_urls" {
  description = "Map of database name → JDBC URL (with sslmode=require) for every logical database on this Flexible Server. Consumed by the app-service module to populate DATABASE_URL / KC_DB_URL on the workload apps."
  value = {
    for db in local.databases :
    db => "jdbc:postgresql://${azurerm_postgresql_flexible_server.this.fqdn}:5432/${db}?sslmode=require"
  }
}

output "database_names" {
  description = "Set of logical database names provisioned on the server."
  value       = local.databases
}
