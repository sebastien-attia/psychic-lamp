# Inputs for the keyvault module.

variable "project_name" {
  description = "Project slug. Combined with environment to form the vault name (lowercase alphanumeric only — hyphens stripped)."
  type        = string
}

variable "environment" {
  description = "Deployment environment, embedded in the vault name."
  type        = string
}

variable "location" {
  description = "Azure region for the vault."
  type        = string
}

variable "resource_group_name" {
  description = "Resource group that holds the vault."
  type        = string
}

# ── Secret payloads ───────────────────────────────────────────────────────
variable "postgres_admin_password" {
  description = "Server-level Flexible Server administrator password. Stored as the postgres-admin-password secret."
  type        = string
  sensitive   = true
}

variable "bff_db_password" {
  description = "Password for the bff PostgreSQL role. Stored as the bff-db-password secret."
  type        = string
  sensitive   = true
}

variable "business_db_password" {
  description = "Password for the business_service PostgreSQL role. Stored as the business-db-password secret."
  type        = string
  sensitive   = true
}

variable "keycloak_db_password" {
  description = "Password for the keycloak PostgreSQL role. Stored as the keycloak-db-password secret."
  type        = string
  sensitive   = true
}

variable "keycloak_admin_password" {
  description = "Initial Keycloak admin password (bootstrap only). Stored as the keycloak-admin-password secret."
  type        = string
  sensitive   = true
}

# ── RBAC ──────────────────────────────────────────────────────────────────
variable "tf_apply_principal_id" {
  description = "Object ID of the Entra ID identity that runs `terraform apply` from CI. Granted Key Vault Secrets Officer so Terraform can write the secret material the apps later read."
  type        = string
}

# ── Misc ──────────────────────────────────────────────────────────────────
variable "soft_delete_retention_days" {
  description = "Soft-delete retention window. 7 is the minimum on Standard SKU."
  type        = number
  default     = 7
}

variable "tags" {
  description = "Tags applied to every resource created by this module."
  type        = map(string)
  default     = {}
}
