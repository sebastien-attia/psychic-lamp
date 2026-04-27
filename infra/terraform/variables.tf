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

variable "service_plan_sku" {
  description = "SKU name for the shared Linux App Service Plan. Default P0v3 (1 vCPU / 4 GiB) — the minimum that comfortably fits BFF + business-service + Keycloak."
  type        = string
  default     = "P0v3"
}

# ── Database admin (server-level Flexible Server administrator_login) ──────
variable "postgres_admin_username" {
  description = "Server-level administrator login on the PostgreSQL Flexible Server. Used ONLY by the db-bootstrap module to create per-DB application roles; the running apps never bind with this account."
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
  description = "Password for the bff PostgreSQL role. Stored as the bff-db-password Key Vault secret and injected as BFF_DB_PASSWORD on the BFF Web App."
  type        = string
  sensitive   = true
}

variable "business_db_password" {
  description = "Password for the business_service PostgreSQL role. Stored as the business-db-password Key Vault secret and injected as BUSINESS_DB_PASSWORD on the business-service Web App."
  type        = string
  sensitive   = true
}

variable "keycloak_db_password" {
  description = "Password for the keycloak PostgreSQL role. Stored as the keycloak-db-password Key Vault secret and injected as KC_DB_PASSWORD on the Keycloak Web App."
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
  description = "Service Principal Object ID of the Entra ID app used by GitHub Actions for image pushes. MUST come from `az ad sp show --id <AZURE_CLIENT_ID> --query id -o tsv` (the SP object ID), NOT `az ad app show` (which returns the app registration object ID — a different value, accepted silently by the role-assignment API but causing 401/403 at runtime). Granted AcrPush on the registry."
  type        = string
}

variable "tf_apply_principal_id" {
  description = "Service Principal Object ID of the Entra ID app that runs `terraform apply` from CI. Same `az ad sp show` rule as ci_push_principal_id — typically the same identity. Granted Key Vault Secrets Officer to write secret material."
  type        = string
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
  description = "Tag of the upstream quay.io/keycloak/keycloak image. The Keycloak Web App pulls this image directly from quay.io — no custom build, no ACR push."
  type        = string
  default     = "26.6.1"
}

# ── Database firewall (extra IPs) ─────────────────────────────────────────
variable "additional_firewall_ips" {
  description = "Extra IPv4 addresses (CI runner egress, operator workstation) granted access to the Flexible Server. Map of rule-name → IP. The deploy workflow adds its runner IP at the start of the job and removes it at the end."
  type        = map(string)
  default     = {}
}
