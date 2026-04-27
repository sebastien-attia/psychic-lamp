# Outputs from the db-bootstrap module.

output "id" {
  description = "ID of the null_resource that ran the bootstrap. Useful as a depends_on target so workloads only start AFTER the roles exist."
  value       = null_resource.bootstrap.id
}
