# Inputs for the container-registry module.

variable "project_name" {
  description = "Project slug. Combined with environment to form the registry name (lowercase alphanumeric only — hyphens and underscores stripped)."
  type        = string
}

variable "environment" {
  description = "Deployment environment, embedded in the registry name."
  type        = string
}

variable "location" {
  description = "Azure region for the registry."
  type        = string
}

variable "resource_group_name" {
  description = "Resource group that holds the registry."
  type        = string
}

variable "sku" {
  description = "ACR SKU. Basic is sufficient for the POC; Standard/Premium are only needed for geo-replication, zone redundancy, or private link, none of which apply here."
  type        = string
  default     = "Basic"

  validation {
    condition     = contains(["Basic", "Standard", "Premium"], var.sku)
    error_message = "sku must be one of Basic, Standard, or Premium."
  }
}

variable "consumer_principal_ids" {
  description = "Map of logical service name → managed-identity object ID. Each entry receives an AcrPull role assignment scoped to this registry. Keys typically: bff, business_service, keycloak, liquibase_bff, liquibase_business_service."
  type        = map(string)
  default     = {}
}

variable "ci_push_principal_id" {
  description = "Object ID of the Entra ID app used by GitHub Actions for image pushes. Granted AcrPush on the registry."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource created by this module."
  type        = map(string)
  default     = {}
}
