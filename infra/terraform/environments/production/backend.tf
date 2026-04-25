# Remote state config for the production environment.
#
# Concrete values are supplied via `terraform init -backend-config=...`
# in CI. The state file contains the bff-signing-key PEM and password
# material — the storage account must enforce HTTPS, RBAC, at-rest
# encryption, soft-delete, and (recommended) versioning.

terraform {
  backend "azurerm" {
    resource_group_name  = "boatapp-tfstate-rg"
    storage_account_name = "boatapptfstate"
    container_name       = "tfstate"
    key                  = "production.tfstate"
  }
}
