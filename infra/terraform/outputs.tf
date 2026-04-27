# Root-level outputs.
#
# These outputs are consumed by:
#   - Operators inspecting the deployment (`terraform output`).
#   - GitHub Actions workflows in 04 which need the ACR login server to
#     tag-and-push images and the Web App names to set the image tag and
#     restart on a new release.
#
# Sensitive material (admin passwords, the bff-signing-key PEM) is NEVER
# exported; it lives in Key Vault and is consumed in-cluster via
# @Microsoft.KeyVault references.

output "resource_group_name" {
  description = "Name of the resource group that contains every resource provisioned by this stack."
  value       = azurerm_resource_group.this.name
}

output "bff_fqdn" {
  description = "Public FQDN of the BFF Web App. The Vue SPA + /api/* proxy both serve from here."
  value       = module.app_service.bff_fqdn
}

output "business_service_fqdn" {
  description = "Public FQDN of the business-service Web App. Reached by the BFF over HTTPS, secured by JWT Bearer validation."
  value       = module.app_service.business_service_fqdn
}

output "keycloak_fqdn" {
  description = "Public FQDN of the Keycloak Web App. Used by browsers (auth code flow) and the BFF (issuer URI / private_key_jwt audience)."
  value       = module.app_service.keycloak_fqdn
}

output "bff_app_name" {
  description = "Resource name of the BFF Web App. Used by `az webapp config container set` to roll a new image tag."
  value       = module.app_service.bff_app_name
}

output "business_service_app_name" {
  description = "Resource name of the business-service Web App."
  value       = module.app_service.business_service_app_name
}

output "keycloak_app_name" {
  description = "Resource name of the Keycloak Web App."
  value       = module.app_service.keycloak_app_name
}

output "postgres_fqdn" {
  description = "FQDN of the PostgreSQL Flexible Server. Used by ad-hoc psql sessions from an allowlisted IP."
  value       = module.database.fqdn
}

output "postgres_admin_username" {
  description = "Server-level administrator login on the Flexible Server (the password lives in Key Vault as postgres-admin-password)."
  value       = module.database.admin_username
}

output "acr_login_server" {
  description = "Login server hostname of the Azure Container Registry. CI uses this with `az acr login --name` (OIDC-federated AcrPush)."
  value       = module.container_registry.login_server
}

output "keyvault_uri" {
  description = "Vault URI used by App Service @Microsoft.KeyVault references and `az keyvault secret show` from operators."
  value       = module.keyvault.vault_uri
}
