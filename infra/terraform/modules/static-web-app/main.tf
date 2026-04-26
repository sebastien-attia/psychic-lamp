# Azure Static Web App + linked-backend module.
#
# Hosts the Vue SPA and links it to the BFF Container App via SWA's
# Bring-Your-Own-Backend mechanism. The SWA edge serves /index.html and
# /assets/** (with SPA history-mode fallback), and routes API + auth paths
# (/api/*, /oauth2/*, /login/*, /logout, /.well-known/*, /actuator/*) to
# the linked-backend BFF Container App. Browsers see a single first-party
# origin, so the BFF's SESSION + XSRF-TOKEN cookies stay first-party.
#
# Region: linked-backend requires a Standard SKU SWA (Free does not
# support BYOB). Allowed SWA regions are limited; we default to West Europe
# but keep it as a variable so consumers can override.

terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 4.0.0"
    }
  }
}

resource "azurerm_static_web_app" "this" {
  name                = "${var.project_name}-${var.environment}-spa"
  resource_group_name = var.resource_group_name
  location            = var.location

  # Standard SKU is required for `azurerm_static_web_app_custom_domain`,
  # private-endpoint support, and (most importantly here) the
  # linked-backend feature consumed by `azurerm_static_web_app_linked_backend`
  # below. Free SKU would force every browser API call through a public BFF
  # endpoint instead.
  sku_tier = "Standard"
  sku_size = "Standard"

  app_settings = var.app_settings

  tags = var.tags
}

# Bring-Your-Own-Backend wiring — the SWA forwards requests matching the
# `routes[]` in `frontend/staticwebapp.config.json` to this Container App.
# Once the link is active, point the BFF Container App's ingress to
# `external = false` so all browser traffic must traverse SWA.
resource "azurerm_static_web_app_linked_backend" "bff" {
  static_web_app_id = azurerm_static_web_app.this.id
  backend_resource_id = var.bff_container_app_id
  region            = var.location
}
