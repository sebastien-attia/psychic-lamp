# Inputs for the database module.

variable "project_name" {
  description = "Project slug, used to name the Flexible Server."
  type        = string
}

variable "environment" {
  description = "Deployment environment, embedded in the server name."
  type        = string
}

variable "location" {
  description = "Azure region. Must match the resource group."
  type        = string
}

variable "resource_group_name" {
  description = "Resource group that holds the Flexible Server."
  type        = string
}

variable "admin_username" {
  description = "Server-level administrator login. Used by the db-bootstrap module to create per-DB application roles; the running apps never bind with this account."
  type        = string
}

variable "admin_password" {
  description = "Password for the Flexible Server administrator_login."
  type        = string
  sensitive   = true
}

variable "postgres_version" {
  description = "PostgreSQL major version. Pinned to 17 for parity with the local-intg Docker Compose stack."
  type        = string
  default     = "17"
}

variable "sku_name" {
  description = "Flexible Server SKU. B_Standard_B1ms is the cheapest burstable tier — adequate for the POC; bump to GP_Standard_D2s_v3 for prod-grade workloads."
  type        = string
  default     = "B_Standard_B1ms"
}

variable "storage_mb" {
  description = "Allocated storage in MiB. 32768 (32 GiB) is the lowest storage class offered for B-series Flexible Servers."
  type        = number
  default     = 32768
}

variable "backup_retention_days" {
  description = "Days of point-in-time backup retained. 7 is the minimum on Flexible Server."
  type        = number
  default     = 7
}

variable "additional_firewall_ips" {
  description = "Optional ad-hoc IPv4 addresses (operator workstation, break-glass psql) granted database access. One firewall rule per entry. Microsoft-hosted runners and App Service Web Apps are already covered by the AllowAllAzureServicesAndResourcesWithinAzureIps rule and do NOT need to be listed here."
  type        = map(string)
  default     = {}
}

variable "tags" {
  description = "Tags applied to every resource created by this module."
  type        = map(string)
  default     = {}
}
