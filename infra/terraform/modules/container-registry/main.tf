# Container registry module — Azure Container Registry with admin disabled
# and zero long-lived credentials.
#
# - admin_enabled = false       → no static username/password is ever issued.
# - AcrPull role assignments    → each Container App's system-assigned MI
#                                 pulls images via Entra ID token exchange.
# - AcrPush role assignment     → GitHub Actions pushes via OIDC federation
#                                 (no PAT, no service-principal secret).
#
# RBAC is co-located with the registry it controls; the role assignments
# inside this module reference principal_ids supplied by the container_apps
# module's output. The registry resource itself does not depend on
# container_apps, so Terraform's resource graph stays acyclic — only the
# role-assignment resources gate on container_apps's MIs being created.

locals {
  # ACR names must be 5-50 chars, lowercase alphanumeric only — strip hyphens.
  registry_name = replace("${var.project_name}${var.environment}acr", "-", "")
}

resource "azurerm_container_registry" "this" {
  name                = local.registry_name
  resource_group_name = var.resource_group_name
  location            = var.location
  sku                 = var.sku

  # Managed-identity pulls + OIDC pushes only — no admin user, ever.
  admin_enabled = false

  tags = var.tags
}

# AcrPull — each consumer's system-assigned MI gets read access.
resource "azurerm_role_assignment" "acr_pull" {
  for_each = var.consumer_principal_ids

  scope                = azurerm_container_registry.this.id
  role_definition_name = "AcrPull"
  principal_id         = each.value
}

# AcrPush — the CI federated identity gets write access.
resource "azurerm_role_assignment" "acr_push" {
  scope                = azurerm_container_registry.this.id
  role_definition_name = "AcrPush"
  principal_id         = var.ci_push_principal_id
}
