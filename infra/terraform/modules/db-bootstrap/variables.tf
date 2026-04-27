# Inputs for the db-bootstrap module.
#
# Replaces the former Container App Job (modules/container-apps's
# bootstrap-db-roles) with a single null_resource that runs psql from
# the Terraform-runner machine. The runner is on the database firewall
# allowlist for the duration of `terraform apply` (added by
# modules/database via runner_ip_addresses).

variable "postgres_fqdn" {
  description = "FQDN of the PostgreSQL Flexible Server."
  type        = string
}

variable "admin_username" {
  description = "Server-level Flexible Server administrator login."
  type        = string
}

variable "admin_password" {
  description = "Server-level administrator password."
  type        = string
  sensitive   = true
}

variable "bff_db_password" {
  description = "Password to set on the bff role (DB bff_session)."
  type        = string
  sensitive   = true
}

variable "business_db_password" {
  description = "Password to set on the business_service role (DB boatapp)."
  type        = string
  sensitive   = true
}

variable "keycloak_db_password" {
  description = "Password to set on the keycloak role (DB keycloak)."
  type        = string
  sensitive   = true
}

variable "trigger_dependencies" {
  description = "Map of arbitrary strings whose hash drives the null_resource trigger. Pass database/server resource IDs and the password sensitive_hash from the keyvault module so role bootstrap re-runs whenever any of them change."
  type        = map(string)
  default     = {}
}
