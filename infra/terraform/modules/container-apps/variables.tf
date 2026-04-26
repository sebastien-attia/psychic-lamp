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

variable "postgres_fqdn" {
  description = "Private FQDN of the PostgreSQL Flexible Server. Resolved inside the VNet via the privatelink.postgres.database.azure.com zone. Consumed by the bootstrap-db-roles Job."
  type        = string
}

variable "postgres_admin_username" {
  description = "Server-level administrator login on the PostgreSQL Flexible Server. Used ONLY by the bootstrap-db-roles Job to create per-DB application roles; the running apps never bind with this account."
  type        = string
}

# postgres-admin-password is read from var.keyvault_secret_ids under the
# "postgres-admin-password" key — same pattern as every other password
# secret in this module. No separate variable.

variable "bootstrap_db_roles_image" {
  description = "Image used by the bootstrap-db-roles Job. Must contain a working `psql` client. Default is the upstream postgres:17-alpine on Docker Hub — a public, unauthenticated pull."
  type        = string
  default     = "docker.io/library/postgres:17-alpine"
}

variable "keyvault_secret_ids" {
  description = "Map of Key Vault secret name → versionless secret ID. Container Apps and Jobs reference these via the `secret { key_vault_secret_id = … }` block."
  type        = map(string)
}

variable "keyvault_secret_version_ids" {
  description = "Map of Key Vault secret name → versioned secret ID. Used only as Terraform triggers so bootstrap jobs rerun when password secrets rotate."
  type        = map(string)
}

variable "bff_signing_key_secret_id" {
  description = "Versionless ID of the bff-signing-key Key Vault secret (PEM). Mounted as a file at /mnt/secrets/bff-signing-key on the BFF Container App."
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
  description = "Tag of the keycloak image to deploy. The image is built by CI from keycloak/Dockerfile (which bakes `kc.sh build --db=postgres` so `start --optimized` works) and pushed to acr_login_server."
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

variable "bff_custom_domain" {
  description = "Optional custom FQDN for the BFF (e.g. app.example.com). Empty string means use only the Azure-assigned default FQDN. Setting this creates an azurerm_container_app_custom_domain + an Azure-managed TLS certificate. DNS records (CNAME + asuid TXT) MUST exist BEFORE terraform apply or the managed-cert issuance will fail."
  type        = string
  default     = ""

  validation {
    condition     = var.bff_custom_domain == "" || can(regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)+$", var.bff_custom_domain))
    error_message = "bff_custom_domain must be a lowercase, dot-separated DNS name (e.g. app.example.com) or an empty string to disable."
  }
}

variable "keycloak_custom_domain" {
  description = "Optional custom FQDN for Keycloak (e.g. auth.example.com). Empty string means use only the Azure-assigned default FQDN. When set, KC_HOSTNAME and the JWT issuer URI both switch to this domain — the Ansible keycloak-client config MUST then be re-run so the BFF's redirect URI is registered under the new domain."
  type        = string
  default     = ""

  validation {
    condition     = var.keycloak_custom_domain == "" || can(regex("^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)+$", var.keycloak_custom_domain))
    error_message = "keycloak_custom_domain must be a lowercase, dot-separated DNS name (e.g. auth.example.com) or an empty string to disable."
  }
}
