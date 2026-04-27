# Production environment composition root.
#
# Mirrors the staging composition but with stricter image-tag defaults:
# production deploys must pin to an immutable tag (typically the git SHA
# or a semver tag), never `latest`. CI is expected to override
# bff_image_tag / business_service_image_tag / keycloak_image_tag on
# each apply.

terraform {
  required_version = ">= 1.9"
}

module "boatapp" {
  source = "../.."

  environment  = "production"
  location     = "switzerlandnorth"
  project_name = "boatapp"

  # Production sustains higher load than staging — keep the SKU lever in
  # case the burstable tier becomes inadequate.
  service_plan_sku = var.service_plan_sku

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

  # ── Optional firewall extras ─────────────────────────────────────────
  additional_firewall_ips = var.additional_firewall_ips
}

# ── Variable pass-through ────────────────────────────────────────────────
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
  description = "Object ID of the Entra ID app that pushes images from CI."
  type        = string
}

variable "tf_apply_principal_id" {
  description = "Object ID of the Entra ID app that runs `terraform apply`."
  type        = string
}

variable "bff_image_tag" {
  description = "BFF image tag to deploy. Production should pin to an immutable tag (git SHA / semver), never `latest`."
  type        = string
}

variable "business_service_image_tag" {
  description = "Business-service image tag to deploy. Production should pin to an immutable tag."
  type        = string
}

variable "keycloak_image_tag" {
  description = "Tag of the upstream quay.io/keycloak/keycloak image. Production pins to a specific version."
  type        = string
  default     = "26.6.1"
}

variable "service_plan_sku" {
  description = "App Service Plan SKU. P0v3 fits the three workloads; bump to P1v3 for sustained production load."
  type        = string
  default     = "P0v3"
}

variable "additional_firewall_ips" {
  description = "Optional extra IPs allowlisted on the database (e.g. CI runner)."
  type        = map(string)
  default     = {}
}

# ── Re-export root outputs ────────────────────────────────────────────────
output "bff_fqdn" {
  description = "Public FQDN of the BFF Web App."
  value       = module.boatapp.bff_fqdn
}

output "business_service_fqdn" {
  description = "Public FQDN of the business-service Web App."
  value       = module.boatapp.business_service_fqdn
}

output "keycloak_fqdn" {
  description = "Public FQDN of the Keycloak Web App."
  value       = module.boatapp.keycloak_fqdn
}

output "bff_app_name" {
  description = "Resource name of the BFF Web App."
  value       = module.boatapp.bff_app_name
}

output "business_service_app_name" {
  description = "Resource name of the business-service Web App."
  value       = module.boatapp.business_service_app_name
}

output "keycloak_app_name" {
  description = "Resource name of the Keycloak Web App."
  value       = module.boatapp.keycloak_app_name
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

output "resource_group_name" {
  description = "Resource group name."
  value       = module.boatapp.resource_group_name
}
