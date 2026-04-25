# Outputs from the keyvault module.
#
# Secret VALUES are intentionally never exported. The Container Apps
# module binds secrets via `key_vault_secret_id` (which Azure resolves
# server-side using the app's MI), and Ansible reads secrets via
# `az keyvault secret show` over the private endpoint.

output "id" {
  description = "Resource ID of the Key Vault. Used by sibling modules to scope role assignments."
  value       = azurerm_key_vault.this.id
}

output "vault_uri" {
  description = "Vault URI (e.g. https://boatapp-staging-kv.vault.azure.net/). Resolves privately through the privatelink.vaultcore.azure.net zone."
  value       = azurerm_key_vault.this.vault_uri
}

output "secret_ids" {
  description = "Map of secret name → versioned secret ID. Container Apps consume these via the `secret { key_vault_secret_id = … }` block; Ansible references them by name."
  sensitive   = true
  value = merge(
    { for k, s in azurerm_key_vault_secret.passwords : k => s.id },
    { (azurerm_key_vault_secret.bff_signing_key.name) = azurerm_key_vault_secret.bff_signing_key.id },
  )
}

output "bff_signing_key_secret_id" {
  description = "Versioned ID of the bff-signing-key secret. The BFF Container App mounts it as a secret-volume PEM file at /mnt/secrets/."
  sensitive   = true
  value       = azurerm_key_vault_secret.bff_signing_key.id
}
