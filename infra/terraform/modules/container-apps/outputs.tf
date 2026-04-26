# Outputs from the container-apps module.
#
# `consumer_principal_ids` is the bridge to the container-registry and
# keyvault modules: it carries the system-assigned MIs of every Container
# App and Liquibase Job so RBAC can be granted at the registry / vault.

output "environment_id" {
  description = "ACA Environment resource ID."
  value       = azurerm_container_app_environment.this.id
}

output "environment_default_domain" {
  description = "Default domain assigned to the ACA Environment (e.g. nicemoss-12345678.switzerlandnorth.azurecontainerapps.io). External-app FQDNs follow `<app>.<default_domain>`; internal-app FQDNs follow `<app>.internal.<default_domain>`."
  value       = azurerm_container_app_environment.this.default_domain
}

output "bff_fqdn" {
  description = "Externally-routable FQDN of the BFF Container App."
  value       = azurerm_container_app.bff.ingress[0].fqdn
}

output "business_service_internal_fqdn" {
  description = "Internal-only FQDN of the business-service Container App. Reachable from the BFF inside the ACA Environment."
  value       = azurerm_container_app.business_service.ingress[0].fqdn
}

output "keycloak_fqdn" {
  description = "Externally-routable FQDN of the Keycloak Container App."
  value       = azurerm_container_app.keycloak.ingress[0].fqdn
}

output "liquibase_job_names" {
  description = "Map of logical service name → Liquibase ACA Job name. Ansible triggers these via `az containerapp job start --name <value>` after each image deploy."
  value       = { for k, v in azurerm_container_app_job.liquibase : k => v.name }
}

output "bff_custom_domain_verification_id" {
  description = "Per-app verification token issued by Azure. Use as the value of the `asuid.<bff_custom_domain>` TXT record at the DNS provider before binding the custom domain. Stable for the life of the Container App."
  value       = azurerm_container_app.bff.custom_domain_verification_id
}

output "keycloak_custom_domain_verification_id" {
  description = "Per-app verification token for the Keycloak custom domain. Use as the value of the `asuid.<keycloak_custom_domain>` TXT record."
  value       = azurerm_container_app.keycloak.custom_domain_verification_id
}

output "consumer_principal_ids" {
  description = "Map keyed by logical service name → system-assigned managed-identity principal ID. Consumed by the container-registry module (AcrPull) and the keyvault module (Key Vault Secrets User)."
  value = merge(
    {
      bff              = azurerm_container_app.bff.identity[0].principal_id
      business_service = azurerm_container_app.business_service.identity[0].principal_id
      keycloak         = azurerm_container_app.keycloak.identity[0].principal_id
    },
    {
      for k, v in azurerm_container_app_job.liquibase :
      "liquibase_${k}" => v.identity[0].principal_id
    },
  )
}
