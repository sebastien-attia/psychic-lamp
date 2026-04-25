# Inputs for the container-apps module.

variable "project_name" {
  description = "Project slug, used to name the ACA Environment."
  type        = string
}

variable "environment" {
  description = "Deployment environment, embedded in the ACA Environment name and used as SPRING_PROFILES_ACTIVE on every app."
  type        = string
}

variable "location" {
  description = "Azure region — must match the VNet."
  type        = string
}

variable "resource_group_name" {
  description = "Resource group that holds the ACA Environment, the Container Apps, and the Liquibase Jobs."
  type        = string
}

variable "container_apps_subnet_id" {
  description = "Subnet ID for the ACA Environment infrastructure_subnet_id. Must be delegated to Microsoft.App/environments."
  type        = string
}

variable "acr_login_server" {
  description = "ACR login server (e.g. boatappstagingacr.azurecr.io). Container Apps pull images from this registry using their system-assigned MI."
  type        = string
}

variable "jdbc_urls" {
  description = "Map of database name → JDBC URL. Keys must include bff_session, boatapp, keycloak."
  type        = map(string)
}

variable "keyvault_secret_ids" {
  description = "Map of Key Vault secret name → versioned secret ID. Container Apps and Jobs reference these via the `secret { key_vault_secret_id = … }` block."
  type        = map(string)
}

variable "bff_signing_key_secret_id" {
  description = "Versioned ID of the bff-signing-key Key Vault secret (PEM). Mounted as a file at /mnt/secrets/bff-signing-key on the BFF Container App."
  type        = string
}

variable "bff_image_tag" {
  description = "Tag of the BFF image to deploy. The image must already be pushed to acr_login_server."
  type        = string
}

variable "business_service_image_tag" {
  description = "Tag of the business-service image to deploy."
  type        = string
}

variable "keycloak_image_tag" {
  description = "Tag of the upstream quay.io/keycloak/keycloak image to deploy."
  type        = string
}

variable "bff_signing_key_id" {
  description = "kid claim baked into the BFF's JWK. Set as BFF_SIGNING_KEY_ID env var."
  type        = string
}

variable "keycloak_admin_username" {
  description = "Initial Keycloak admin username (bootstrap only)."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource created by this module."
  type        = map(string)
  default     = {}
}
