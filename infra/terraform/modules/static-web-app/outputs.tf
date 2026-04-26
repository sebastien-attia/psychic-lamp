output "static_web_app_id" {
  description = "Resource ID of the Static Web App."
  value       = azurerm_static_web_app.this.id
}

output "default_host_name" {
  description = "SWA default hostname (https://<this>/) — the canonical browser entrypoint for the SPA + BFF in staging / prod."
  value       = azurerm_static_web_app.this.default_host_name
}

output "api_key" {
  description = "Deployment API key consumed by Azure/static-web-apps-deploy@v1 in CI to push frontend/dist. Sensitive — surface via GitHub environment secret, never log."
  value       = azurerm_static_web_app.this.api_key
  sensitive   = true
}
