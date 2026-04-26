# Provider and backend configuration for the Boat App Azure deployment.
#
# - Pinned to azurerm >= 4.69 to ensure feature parity across the modules
#   (Container Apps Jobs, Key Vault RBAC mode, PostgreSQL Flexible Server
#   private DNS integration). 4.69 is the floor because that release added
#   azurerm_container_app_environment_managed_certificate, which the
#   container-apps module uses for custom domain TLS.
# - This file is consumed as a child module from environments/<env>/.
#   The backend MUST be declared in the root module (the env directory),
#   not here — Terraform silently ignores backend blocks in child modules
#   and emits a "Backend configuration ignored" warning. Each
#   environments/<env>/backend.tf owns its own `backend "azurerm"` block.
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
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
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
