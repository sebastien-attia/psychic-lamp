# Outputs from the db-bootstrap module.

output "ids" {
  description = "Map of role name → null_resource id of the bootstrap that ran for that role. Useful as a depends_on target so workloads only start AFTER the roles exist."
  value       = { for k, r in null_resource.bootstrap : k => r.id }
}
