# Root module: wires the five child modules together for one environment.
#
# Module ordering and dependency notes
# ────────────────────────────────────
# 1. resource_group     — the bare RG that holds every resource below.
# 2. database           — Flexible Server (public + AllowAllAzure firewall rule)
#                          with three logical DBs.
# 3. container_registry — ACR resource + AcrPush role for CI.
# 4. keyvault           — KV + secrets + tf_apply Officer role.
# 5. app_service        — App Service Plan + 3 Web Apps (bff, business-service,
#                          keycloak). Grants AcrPull and Key Vault Secrets User
#                          to each Web App's MI.
# 6. db_bootstrap       — Runs psql via local-exec to create per-DB roles + grants.
#                          depends_on module.database so the firewall rule is in
#                          place before the local-exec connects.
#
# Cross-module reference shape (acyclic)
# ──────────────────────────────────────
#   app_service  ← database.jdbc_urls + database.postgres_fqdn
#   app_service  ← keyvault.vault_uri + keyvault.id
#   app_service  ← container_registry.login_server + container_registry.id
#   db_bootstrap ← database.fqdn + keyvault.password_secrets_versions
#
# Web Apps will fail to connect to Postgres on first boot until
# db_bootstrap completes — a 30–90 s settling window that the deploy
# workflow's smoke test absorbs.

locals {
  common_tags = {
    project     = "boat-app"
    environment = var.environment
    managed_by  = "terraform"
  }

  resource_group_name = "${var.project_name}-${var.environment}-rg"
}

resource "azurerm_resource_group" "this" {
  name     = local.resource_group_name
  location = var.location
  tags     = local.common_tags
}

module "database" {
  source = "./modules/database"

  project_name        = var.project_name
  environment         = var.environment
  location            = var.location
  resource_group_name = azurerm_resource_group.this.name
  admin_username      = var.postgres_admin_username
  admin_password      = var.postgres_admin_password

  # The AllowAllAzureServicesAndResourcesWithinAzureIps firewall rule
  # (always created inside the module) covers App Service Web Apps and
  # Microsoft-hosted GitHub runners. additional_firewall_ips is reserved
  # for ad-hoc operator workstation access.
  additional_firewall_ips = var.additional_firewall_ips

  tags = local.common_tags
}

module "container_registry" {
  source = "./modules/container-registry"

  project_name         = var.project_name
  environment          = var.environment
  location             = var.location
  resource_group_name  = azurerm_resource_group.this.name
  ci_push_principal_id = var.ci_push_principal_id

  # The Web Apps' MIs receive AcrPull from the app-service module instead
  # of being threaded through here, so this map is empty by design.
  consumer_principal_ids = {}

  tags = local.common_tags
}

module "keyvault" {
  source = "./modules/keyvault"

  project_name        = var.project_name
  environment         = var.environment
  location            = var.location
  resource_group_name = azurerm_resource_group.this.name

  # Secret payloads
  postgres_admin_password = var.postgres_admin_password
  bff_db_password         = var.bff_db_password
  business_db_password    = var.business_db_password
  keycloak_db_password    = var.keycloak_db_password
  keycloak_admin_password = var.keycloak_admin_password

  # RBAC
  tf_apply_principal_id = var.tf_apply_principal_id

  tags = local.common_tags
}

module "app_service" {
  source = "./modules/app-service"

  project_name        = var.project_name
  environment         = var.environment
  location            = var.location
  resource_group_name = azurerm_resource_group.this.name
  service_plan_sku    = var.service_plan_sku

  acr_login_server = module.container_registry.login_server
  acr_id           = module.container_registry.id

  keyvault_id  = module.keyvault.id
  keyvault_uri = module.keyvault.vault_uri

  jdbc_urls     = module.database.jdbc_urls
  postgres_fqdn = module.database.fqdn

  bff_image_tag              = var.bff_image_tag
  business_service_image_tag = var.business_service_image_tag
  keycloak_image_tag         = var.keycloak_image_tag

  bff_signing_key_id      = var.bff_signing_key_id
  keycloak_admin_username = var.keycloak_admin_username

  tags = local.common_tags
}

module "db_bootstrap" {
  source = "./modules/db-bootstrap"

  postgres_fqdn        = module.database.fqdn
  admin_username       = var.postgres_admin_username
  admin_password       = var.postgres_admin_password
  bff_db_password      = var.bff_db_password
  business_db_password = var.business_db_password
  keycloak_db_password = var.keycloak_db_password

  # Re-run when password versions change (rotation) or the server is
  # replaced.
  trigger_dependencies = merge(
    {
      server_id = module.database.id
    },
    module.keyvault.password_secrets_versions,
  )

  # Bootstrap psql must run AFTER the firewall rule that lets the runner
  # in (the AllowAllAzure rule, created inside module.database). Without
  # this depends_on, Terraform might parallelise the local-exec ahead of
  # the firewall rule on a stack-replace.
  depends_on = [module.database]
}
