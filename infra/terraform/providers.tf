# Provider and backend configuration for the Boat App Azure deployment.
#
# - Pinned to azurerm >= 4.69 to keep feature parity with the modules
#   (PostgreSQL Flexible Server, Linux Web App for Containers, Key Vault
#   RBAC mode).
# - The azurerm backend block is intentionally empty; concrete state
#   storage settings are supplied per-environment via partial config in
#   environments/{staging,production}/backend.tf.
# - subscription_id is intentionally not pinned in code; it is supplied
#   either via the ARM_SUBSCRIPTION_ID env var (preferred for CI) or via
#   var.azure_subscription_id in terraform.tfvars.

terraform {
  required_version = ">= 1.9"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 4.69, < 5.0"
    }
    null = {
      source  = "hashicorp/null"
      version = "~> 3.2"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "azurerm" {
  features {
    key_vault {
      purge_soft_delete_on_destroy    = false
      recover_soft_deleted_key_vaults = true
    }
    resource_group {
      prevent_deletion_if_contains_resources = true
    }
  }

  subscription_id = var.azure_subscription_id
}

variable "azure_subscription_id" {
  description = "Azure subscription ID. Optional — when null, falls back to the ARM_SUBSCRIPTION_ID environment variable (preferred in CI)."
  type        = string
  default     = null
}
