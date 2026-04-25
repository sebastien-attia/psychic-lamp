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
  description = "Azure region — must match the VNet."
  type        = string
}

variable "resource_group_name" {
  description = "Resource group that holds the Flexible Server."
  type        = string
}

variable "database_subnet_id" {
  description = "Delegated subnet for the Flexible Server VNet integration."
  type        = string
}

variable "postgres_private_dns_zone_id" {
  description = "Private DNS zone (privatelink.postgres.database.azure.com) used for VNet-internal name resolution of the server's FQDN."
  type        = string
}

variable "admin_username" {
  description = "Server-level administrator login. Used by Ansible bootstrap-db-roles only — never by the running applications."
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

variable "tags" {
  description = "Tags applied to every resource created by this module."
  type        = map(string)
  default     = {}
}
