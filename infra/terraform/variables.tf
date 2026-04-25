# Root-level input variables for the Boat App Azure deployment.
#
# Sensitive values (admin/role passwords) are passed through to the Key
# Vault module and stored there as azurerm_key_vault_secret resources;
# they are never written to .tfvars committed in the repo. The PEM used
# by the BFF for private_key_jwt is generated in-module via tls_private_key
# rather than ingested as a variable, so it never lands in state files
# beyond the unavoidable Key Vault secret payload.
#
# There is no keycloak_client_secret variable: the BFF authenticates to
# Keycloak via signed JWT client_assertion (RFC 7523), and Keycloak fetches
# the public half from the BFF's /.well-known/jwks.json endpoint.

variable "environment" {
  description = "Deployment environment. Drives resource naming and is propagated as the SPRING_PROFILES_ACTIVE / managed-by tag value."
  type        = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment must be either \"staging\" or \"production\"."
  }
}

variable "location" {
  description = "Azure region. Defaults to Switzerland North (closest region to the Geneva delivery centre)."
  type        = string
  default     = "switzerlandnorth"
}

variable "project_name" {
  description = "Short project slug used as a prefix for every resource name. Lowercase, hyphens allowed; max 12 chars to stay within Azure name limits when concatenated."
  type        = string
  default     = "boatapp"
}

variable "azure_subscription_id" {
  description = "Azure subscription ID. Optional — when null, falls back to the ARM_SUBSCRIPTION_ID environment variable (preferred in CI)."
  type        = string
  default     = null
}

# ── Database admin (server-level Flexible Server administrator_login) ──────
variable "postgres_admin_username" {
  description = "Server-level administrator login on the PostgreSQL Flexible Server. Used ONLY by the Ansible bootstrap-db-roles playbook to create per-DB application roles; the running apps never bind with this account."
  type        = string
  default     = "pgadmin"
}

variable "postgres_admin_password" {
  description = "Password for the Flexible Server administrator_login. Stored as the postgres-admin-password Key Vault secret."
  type        = string
  sensitive   = true
}

# ── Per-DB application role passwords ──────────────────────────────────────
variable "bff_db_password" {
  description = "Password for the bff PostgreSQL role. Stored as the bff-db-password Key Vault secret and injected as DATABASE_PASSWORD on the BFF Container App."
  type        = string
  sensitive   = true
}

variable "business_db_password" {
  description = "Password for the business_service PostgreSQL role. Stored as the business-db-password Key Vault secret and injected as DATABASE_PASSWORD on the business-service Container App and the business_service Liquibase Job."
  type        = string
  sensitive   = true
}

variable "keycloak_db_password" {
  description = "Password for the keycloak PostgreSQL role. Stored as the keycloak-db-password Key Vault secret and injected as KC_DB_PASSWORD on the Keycloak Container App."
  type        = string
  sensitive   = true
}

# ── Keycloak admin (initial bootstrap only) ────────────────────────────────
variable "keycloak_admin_username" {
  description = "Initial Keycloak admin user. Used only for first-start bootstrap; rotate via keycloak-config-cli after the realm is applied."
  type        = string
  default     = "kcadmin"
}

variable "keycloak_admin_password" {
  description = "Initial Keycloak admin password. Stored as the keycloak-admin-password Key Vault secret."
  type        = string
  sensitive   = true
}

# ── BFF private_key_jwt signing ────────────────────────────────────────────
variable "bff_signing_key_id" {
  description = "kid claim baked into the BFF's JWK and exposed at /.well-known/jwks.json. Keycloak's boat-app-confidential client uses use.jwks.url=true to fetch the public half."
  type        = string
  default     = "bff-key-1"
}

# ── Entra ID identities for CI / Terraform ────────────────────────────────
variable "ci_push_principal_id" {
  description = "Object ID of the Entra ID app used by GitHub Actions for image pushes. Granted AcrPush on the registry. Sourced from `az ad app show --id <AZURE_CLIENT_ID>`. Same identity as tf_apply_principal_id in this baseline."
  type        = string
}

variable "tf_apply_principal_id" {
  description = "Object ID of the Entra ID app that runs `terraform apply` from CI. Granted Key Vault Secrets Officer to write secret material. Typically the same identity as ci_push_principal_id."
  type        = string
}

variable "additional_kv_consumer_principal_ids" {
  description = "Optional extra principal IDs (e.g. ansible_runner, an operator break-glass identity) granted Key Vault Secrets User. Merged with the system-assigned MIs of the Container Apps and Liquibase Jobs."
  type        = map(string)
  default     = {}
}

# ── Container image tags ──────────────────────────────────────────────────
variable "bff_image_tag" {
  description = "Tag of the BFF image to deploy (pulled from the project's ACR)."
  type        = string
  default     = "latest"
}

variable "business_service_image_tag" {
  description = "Tag of the business-service image to deploy (pulled from the project's ACR)."
  type        = string
  default     = "latest"
}

variable "keycloak_image_tag" {
  description = "Tag of the upstream quay.io/keycloak/keycloak image. Pinned to 26.6.1 to match the local-intg Docker Compose stack."
  type        = string
  default     = "26.6.1"
}
