variable "project_name" {
  description = "Project short name used as the resource-name prefix (e.g. 'boatapp')."
  type        = string
}

variable "environment" {
  description = "Environment slug appended to resource names (e.g. 'staging', 'prod')."
  type        = string
}

variable "resource_group_name" {
  description = "Resource group in which to create the Static Web App."
  type        = string
}

variable "location" {
  description = "Azure region for the Static Web App AND the linked-backend region. Must be one of the SWA-supported regions (e.g. 'westeurope', 'eastus2')."
  type        = string
  default     = "westeurope"
}

variable "bff_container_app_id" {
  description = "Resource ID of the BFF Container App that the SWA linked-backend forwards /api/*, /oauth2/*, /login/*, /logout, /.well-known/*, /actuator/* to."
  type        = string
}

variable "app_settings" {
  description = "Optional SWA app settings. Useful for runtime config that the SPA can read via /api/(SWA-managed-functions); empty by default for this project (we have no SWA-managed functions)."
  type        = map(string)
  default     = {}
}

variable "tags" {
  description = "Resource tags."
  type        = map(string)
  default     = {}
}
