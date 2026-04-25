# Inputs for the networking module.

variable "project_name" {
  description = "Short project slug used as a prefix for every resource name."
  type        = string
}

variable "environment" {
  description = "Deployment environment (staging / production). Drives resource naming."
  type        = string
}

variable "location" {
  description = "Azure region in which the resource group, VNet, and DNS zones live."
  type        = string
}

variable "address_space" {
  description = "Top-level VNet CIDR. The three subnets are sized as /24 carve-outs."
  type        = string
  default     = "10.0.0.0/16"
}

variable "container_apps_subnet_cidr" {
  description = "Subnet for the Container Apps Environment (delegated to Microsoft.App/environments)."
  type        = string
  default     = "10.0.1.0/24"
}

variable "database_subnet_cidr" {
  description = "Subnet for the PostgreSQL Flexible Server (delegated to Microsoft.DBforPostgreSQL/flexibleServers)."
  type        = string
  default     = "10.0.2.0/24"
}

variable "keyvault_subnet_cidr" {
  description = "Subnet that hosts the Key Vault private endpoint NIC. Has private_endpoint_network_policies disabled."
  type        = string
  default     = "10.0.3.0/24"
}

variable "tags" {
  description = "Tags applied to every resource created by this module."
  type        = map(string)
  default     = {}
}
