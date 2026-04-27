# Outputs from the keyvault module.
#
# Secret VALUES are intentionally never exported. The app-service module
# references secrets via @Microsoft.KeyVault(SecretUri=…) placeholders that
# Azure resolves server-side using each Web App's MI.

output "id" {
  description = "Resource ID of the Key Vault. Used by the app-service module to scope the Key Vault Secrets User role assignments."
  value       = azurerm_key_vault.this.id
}

output "vault_uri" {
  description = "Vault URI (e.g. https://boatapp-staging-kv.vault.azure.net/). Forms the prefix of every @Microsoft.KeyVault reference embedded in app_settings."
  value       = azurerm_key_vault.this.vault_uri
}

output "password_secrets_versions" {
  description = "Map of secret name → current version. Used by the db-bootstrap trigger map so role bootstrap re-runs whenever a password is rotated. Versions are GUIDs, not secret material — intentionally not marked sensitive so a `terraform plan` clearly shows which secret rotated."
  value = {
    for k, s in azurerm_key_vault_secret.passwords : k => s.version
  }
}

output "bff_signing_key_versionless_secret_id" {
  description = "Versionless ID of the bff-signing-key secret. The BFF Container App mounts it as a secret-volume PEM file at /mnt/secrets/."
  sensitive   = true
  value       = azurerm_key_vault_secret.bff_signing_key.versionless_id
}
