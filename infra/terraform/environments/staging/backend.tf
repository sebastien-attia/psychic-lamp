# Remote state config for the staging environment.
#
# Concrete values are supplied via `terraform init -backend-config=...`
# in CI; the placeholders below must be replaced before a local apply.
# The state file contains the bff-signing-key PEM and password material —
# the storage account must enforce HTTPS, RBAC, and at-rest encryption.

terraform {
  backend "azurerm" {
    resource_group_name  = "boatapp-tfstate-rg"
    storage_account_name = "boatapptfstate"
    container_name       = "tfstate"
    key                  = "staging.tfstate"
  }
}
