# Root module: wires the five child modules together for one environment.
#
# Module ordering and dependency notes
# ────────────────────────────────────
# 1. networking         — RG + VNet + subnets + private DNS zones (no inputs from siblings).
# 2. database           — Flexible Server in the database subnet; uses the postgres private DNS zone from networking.
# 3. container_registry — ACR resource + AcrPush role (consumer AcrPull assignments use container_apps's MIs).
# 4. container_apps     — ACA Environment + 3 Container Apps + 2 Liquibase Jobs.
# 5. keyvault           — KV + secrets + tf_apply Officer role + consumer Secrets-User assignments.
#
# Cross-module reference shape
# ────────────────────────────
#   container_apps      references keyvault.secret_ids and container_registry.login_server
#   keyvault            references container_apps.consumer_principal_ids (for Secrets User RBAC)
#   container_registry  references container_apps.consumer_principal_ids (for AcrPull RBAC)
#
# This *looks* like a module-level loop, but it is not a Terraform graph
# cycle: the role-assignment resources that consume container_apps's MI
# principal IDs are different resources from the registry/vault that
# container_apps consumes — Terraform's graph operates on individual
# resources, not on whole modules. The image-pull / secret-resolution
# 30–90 s settling window after first apply is a runtime artifact while
# Entra ID RBAC propagates, not a Terraform-graph concern.

locals {
  common_tags = {
    project     = "boat-app"
    environment = var.environment
    managed_by  = "terraform"
  }
}

module "networking" {
  source = "./modules/networking"

  project_name = var.project_name
  environment  = var.environment
  location     = var.location
  tags         = local.common_tags
}

module "database" {
  source = "./modules/database"

  project_name                 = var.project_name
  environment                  = var.environment
  location                     = var.location
  resource_group_name          = module.networking.resource_group_name
  database_subnet_id           = module.networking.database_subnet_id
  postgres_private_dns_zone_id = module.networking.postgres_private_dns_zone_id
  admin_username               = var.postgres_admin_username
  admin_password               = var.postgres_admin_password
  tags                         = local.common_tags
}

module "container_registry" {
  source = "./modules/container-registry"

  project_name        = var.project_name
  environment         = var.environment
  location            = var.location
  resource_group_name = module.networking.resource_group_name

  # Workload + Liquibase MIs (5 entries) come back from container_apps.
  consumer_principal_ids = module.container_apps.consumer_principal_ids
  ci_push_principal_id   = var.ci_push_principal_id

  tags = local.common_tags
}

module "keyvault" {
  source = "./modules/keyvault"

  project_name                 = var.project_name
  environment                  = var.environment
  location                     = var.location
  resource_group_name          = module.networking.resource_group_name
  keyvault_subnet_id           = module.networking.keyvault_subnet_id
  keyvault_private_dns_zone_id = module.networking.keyvault_private_dns_zone_id

  # Secret payloads
  postgres_admin_password = var.postgres_admin_password
  bff_db_password         = var.bff_db_password
  business_db_password    = var.business_db_password
  keycloak_db_password    = var.keycloak_db_password
  keycloak_admin_password = var.keycloak_admin_password

  # RBAC
  consumer_principal_ids = merge(
    module.container_apps.consumer_principal_ids,
    var.additional_kv_consumer_principal_ids,
  )
  tf_apply_principal_id = var.tf_apply_principal_id

  tags = local.common_tags
}

module "container_apps" {
  source = "./modules/container-apps"

  project_name             = var.project_name
  environment              = var.environment
  location                 = var.location
  resource_group_name      = module.networking.resource_group_name
  container_apps_subnet_id = module.networking.container_apps_subnet_id

  # Registry — MI auth, no credentials in transit
  acr_login_server = module.container_registry.login_server

  # Database wiring
  jdbc_urls = module.database.jdbc_urls

  # Key Vault wiring (secret IDs are bound into the ACA `secret { }` blocks)
  keyvault_secret_ids   = module.keyvault.secret_ids
  bff_signing_key_secret_id = module.keyvault.bff_signing_key_secret_id

  # Image tags
  bff_image_tag              = var.bff_image_tag
  business_service_image_tag = var.business_service_image_tag
  keycloak_image_tag         = var.keycloak_image_tag

  # Misc
  bff_signing_key_id      = var.bff_signing_key_id
  keycloak_admin_username = var.keycloak_admin_username

  tags = local.common_tags
}
