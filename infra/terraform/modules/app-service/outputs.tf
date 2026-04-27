# Outputs from the app-service module.
#
# No credentials are exported. ACR pulls and KV secret resolution use the
# Web Apps' system-assigned MIs (role assignments are colocated in main.tf).

output "bff_fqdn" {
  description = "Public FQDN of the BFF Web App. Browsers hit this for the SPA + /api/*."
  value       = azurerm_linux_web_app.bff.default_hostname
}

output "business_service_fqdn" {
  description = "Public FQDN of the business-service Web App. Reached by the BFF over HTTPS, secured by JWT Bearer validation."
  value       = azurerm_linux_web_app.business_service.default_hostname
}

output "keycloak_fqdn" {
  description = "Public FQDN of the Keycloak Web App. Used by browsers (auth code flow) and the BFF (issuer URI / private_key_jwt audience)."
  value       = azurerm_linux_web_app.keycloak.default_hostname
}

output "bff_app_name" {
  description = "Resource name of the BFF Web App. Used by the deploy workflow's `az webapp config container set` step to switch image tags."
  value       = azurerm_linux_web_app.bff.name
}

output "business_service_app_name" {
  description = "Resource name of the business-service Web App."
  value       = azurerm_linux_web_app.business_service.name
}

output "keycloak_app_name" {
  description = "Resource name of the Keycloak Web App."
  value       = azurerm_linux_web_app.keycloak.name
}

