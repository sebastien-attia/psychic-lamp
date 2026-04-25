# Outputs from the container-registry module.
#
# By design this module exports NO admin credentials — admin_enabled is
# false on the registry, and pulls/pushes rely on Entra ID role
# assignments. Adding admin_username / admin_password outputs would
# require flipping admin_enabled to true and is rejected by the phase
# verification script.

output "id" {
  description = "Resource ID of the registry. Used by sibling modules to scope role assignments to it."
  value       = azurerm_container_registry.this.id
}

output "login_server" {
  description = "Hostname (e.g. boatappstagingacr.azurecr.io) used to tag/push/pull images. Consumed by the Container Apps module's `registry { server = … }` blocks and by `az acr login --name`."
  value       = azurerm_container_registry.this.login_server
}

output "name" {
  description = "Bare registry name (without the .azurecr.io suffix). Useful for `az acr login --name <value>`."
  value       = azurerm_container_registry.this.name
}
