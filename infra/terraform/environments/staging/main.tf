# Staging environment composition root.
#
# Thin wrapper around the root module that sets staging-specific image
# tags. Sensitive values (passwords, principal IDs) come from
# environment-bound TF_VAR_* env vars in CI, NEVER from terraform.tfvars.

terraform {
  required_version = ">= 1.9"
}

module "boatapp" {
  source = "../.."

  environment  = "staging"
  location     = "switzerlandnorth"
  project_name = "boatapp"

  # ── Sensitive (TF_VAR_*) ──────────────────────────────────────────────
  postgres_admin_password = var.postgres_admin_password
  bff_db_password         = var.bff_db_password
  business_db_password    = var.business_db_password
  keycloak_db_password    = var.keycloak_db_password
  keycloak_admin_password = var.keycloak_admin_password
  ci_push_principal_id    = var.ci_push_principal_id
  tf_apply_principal_id   = var.tf_apply_principal_id

  # ── Image tags ────────────────────────────────────────────────────────
  bff_image_tag              = var.bff_image_tag
  business_service_image_tag = var.business_service_image_tag
  keycloak_image_tag         = var.keycloak_image_tag

  # ── Custom domains (opt-in; default "" keeps the Azure FQDN-only setup)
  bff_custom_domain      = var.bff_custom_domain
  keycloak_custom_domain = var.keycloak_custom_domain
}

# ── Variable pass-through (kept here so each env declares its own surface)
variable "postgres_admin_password" {
  description = "Server-level Flexible Server administrator password."
  type        = string
  sensitive   = true
}

variable "bff_db_password" {
  description = "Password for the bff PostgreSQL role."
  type        = string
  sensitive   = true
}

variable "business_db_password" {
  description = "Password for the business_service PostgreSQL role."
  type        = string
  sensitive   = true
}

variable "keycloak_db_password" {
  description = "Password for the keycloak PostgreSQL role."
  type        = string
  sensitive   = true
}

variable "keycloak_admin_password" {
  description = "Initial Keycloak admin password."
  type        = string
  sensitive   = true
}

variable "ci_push_principal_id" {
  description = "Object ID of the Entra ID app that pushes images from CI (granted AcrPush)."
  type        = string
}

variable "tf_apply_principal_id" {
  description = "Object ID of the Entra ID app that runs `terraform apply` (granted Key Vault Secrets Officer)."
  type        = string
}

variable "bff_image_tag" {
  description = "BFF image tag to deploy."
  type        = string
  default     = "latest"
}

variable "business_service_image_tag" {
  description = "Business-service image tag to deploy."
  type        = string
  default     = "latest"
}

variable "keycloak_image_tag" {
  description = "Keycloak upstream image tag."
  type        = string
  default     = "26.6.1"
}

variable "bff_custom_domain" {
  description = "Optional custom FQDN for the staging BFF (e.g. app-staging.example.com). Empty = keep the Azure FQDN only."
  type        = string
  default     = ""
}

variable "keycloak_custom_domain" {
  description = "Optional custom FQDN for the staging Keycloak (e.g. auth-staging.example.com). Empty = keep the Azure FQDN only."
  type        = string
  default     = ""
}

# ── Re-export root outputs ────────────────────────────────────────────────
output "bff_fqdn" {
  description = "External FQDN of the BFF Container App."
  value       = module.boatapp.bff_fqdn
}

output "keycloak_fqdn" {
  description = "External FQDN of the Keycloak Container App."
  value       = module.boatapp.keycloak_fqdn
}

output "business_service_internal_fqdn" {
  description = "Internal FQDN of the business-service Container App."
  value       = module.boatapp.business_service_internal_fqdn
}

output "postgres_fqdn" {
  description = "FQDN of the PostgreSQL Flexible Server."
  value       = module.boatapp.postgres_fqdn
}

output "acr_login_server" {
  description = "Login server of the Container Registry."
  value       = module.boatapp.acr_login_server
}

output "keyvault_uri" {
  description = "URI of the Key Vault."
  value       = module.boatapp.keyvault_uri
}

output "liquibase_job_names" {
  description = "Map of Liquibase ACA Job names."
  value       = module.boatapp.liquibase_job_names
}

output "bff_custom_domain_verification_id" {
  description = "Token to publish as the value of `asuid.<bff_custom_domain>` TXT record before turning on bff_custom_domain."
  value       = module.boatapp.bff_custom_domain_verification_id
}

output "keycloak_custom_domain_verification_id" {
  description = "Token to publish as the value of `asuid.<keycloak_custom_domain>` TXT record before turning on keycloak_custom_domain."
  value       = module.boatapp.keycloak_custom_domain_verification_id
}
