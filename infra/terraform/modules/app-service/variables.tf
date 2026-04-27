# Inputs for the app-service module.
#
# Hosts the BFF, business-service, and Keycloak as three Linux Web Apps for
# Containers on a shared App Service Plan. No VNet, no private endpoints —
# inter-service traffic goes over the public internet, secured by JWT (BFF
# already speaks https://business.../actuator + Bearer token; Keycloak's
# OIDC discovery is public by design).

variable "project_name" {
  description = "Project slug, used to name the App Service Plan and Web Apps."
  type        = string
}

variable "environment" {
  description = "Deployment environment, embedded in resource names and used as SPRING_PROFILES_ACTIVE."
  type        = string
}

variable "location" {
  description = "Azure region for the App Service Plan and its Web Apps."
  type        = string
}

variable "resource_group_name" {
  description = "Resource group that holds the plan + Web Apps."
  type        = string
}

variable "service_plan_sku" {
  description = "SKU name for the shared Linux App Service Plan that hosts the BFF, business-service, AND Keycloak. P0v3 (1 vCPU / 4 GiB) is the minimum that comfortably fits two Spring Boot JVMs + Keycloak (which itself runs ~1.5 GiB resident); B2 (3.5 GiB) routinely OOMs under load. Bump to P1v3 if observability shows sustained pressure."
  type        = string
  default     = "P0v3"
}

variable "acr_login_server" {
  description = "ACR login server (e.g. boatappstagingacr.azurecr.io). Web Apps pull images using their system-assigned MIs."
  type        = string
}

variable "acr_id" {
  description = "Resource ID of the ACR. Required to scope the AcrPull role assignments granted to the Web Apps' system-assigned MIs."
  type        = string
}

variable "keyvault_id" {
  description = "Resource ID of the Key Vault. Required to scope the Key Vault Secrets User role assignments granted to the Web Apps' system-assigned MIs (which resolve @Microsoft.KeyVault references)."
  type        = string
}

variable "keyvault_uri" {
  description = "Vault URI (e.g. https://boatapp-staging-kv.vault.azure.net/) used to construct @Microsoft.KeyVault(SecretUri=...) references in app settings."
  type        = string
}

variable "jdbc_urls" {
  description = "Map of database name → JDBC URL. Keys must include bff_session, boatapp, keycloak."
  type        = map(string)
}

variable "postgres_fqdn" {
  description = "FQDN of the PostgreSQL Flexible Server. Exposed to the apps as POSTGRES_FQDN (used by the Spring profile YAML to build the JDBC URL)."
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
  description = "Tag of the upstream quay.io/keycloak/keycloak image to deploy. The Keycloak Web App pulls this image directly from quay.io — no custom build, no ACR push. Pinned by default to the same version used by the local-intg compose stack."
  type        = string
  default     = "26.6.1"
}

variable "bff_signing_key_id" {
  description = "kid claim baked into the BFF's JWK and exposed at /.well-known/jwks.json."
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
