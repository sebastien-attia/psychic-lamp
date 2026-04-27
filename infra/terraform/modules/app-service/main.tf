# App Service module — Linux App Service Plan + three Web Apps for Containers
# (bff, business-service, keycloak).
#
# Identity & registry
# ───────────────────
# Each Web App declares `identity { type = "SystemAssigned" }` and pulls its
# image via the system-assigned MI (AcrPull role assignment in this module).
# No ACR admin user, no docker-login secret.
#
# Secrets
# ───────
# Sensitive app settings reference Key Vault with the
# `@Microsoft.KeyVault(SecretUri=https://<vault>/secrets/<name>)` placeholder
# — App Service resolves them at app-start using the same MI and the role
# assignment granted in this module (Key Vault Secrets User).
#
# Inter-service URLs
# ──────────────────
# The BFF reaches the business-service on its public Azure FQDN, secured by
# the JWT Bearer token it already forwards. Keycloak is browser-public.
# These URLs are predicted from `azurerm_linux_web_app.<svc>.default_hostname`
# rather than hard-coded so a region or naming change propagates cleanly.

locals {
  name_prefix = "${var.project_name}-${var.environment}"

  bff_app_name      = "${local.name_prefix}-bff"
  business_app_name = "${local.name_prefix}-business"
  keycloak_app_name = "${local.name_prefix}-keycloak"

  # Predicted public FQDNs (the resources below will yield the same default
  # hostname; we use string interpolation here so cross-references in
  # `app_settings` don't introduce a Terraform graph cycle).
  bff_fqdn      = "${local.bff_app_name}.azurewebsites.net"
  business_fqdn = "${local.business_app_name}.azurewebsites.net"
  keycloak_fqdn = "${local.keycloak_app_name}.azurewebsites.net"

  keycloak_issuer_uri = "https://${local.keycloak_fqdn}/realms/boat-app"
}

# ── Shared Linux App Service Plan ─────────────────────────────────────────
resource "azurerm_service_plan" "this" {
  name                = "${local.name_prefix}-plan"
  resource_group_name = var.resource_group_name
  location            = var.location

  os_type  = "Linux"
  sku_name = var.service_plan_sku

  tags = var.tags
}

# ── BFF (public, port 8080) ───────────────────────────────────────────────
resource "azurerm_linux_web_app" "bff" {
  name                = local.bff_app_name
  resource_group_name = var.resource_group_name
  location            = azurerm_service_plan.this.location
  service_plan_id     = azurerm_service_plan.this.id

  https_only = true

  identity {
    type = "SystemAssigned"
  }

  site_config {
    always_on                                     = true
    health_check_path                             = "/actuator/health"
    container_registry_use_managed_identity       = true
    container_registry_managed_identity_client_id = null
    ftps_state                                    = "Disabled"
    minimum_tls_version                           = "1.2"

    application_stack {
      docker_image_name   = "bff:${var.bff_image_tag}"
      docker_registry_url = "https://${var.acr_login_server}"
    }
  }

  app_settings = {
    # App Service plumbing — the container exposes 8080.
    WEBSITES_PORT                       = "8080"
    DOCKER_ENABLE_CI                    = "false"
    WEBSITES_ENABLE_APP_SERVICE_STORAGE = "false"

    # Spring profile — must match the application-<profile>.yml on disk.
    SPRING_PROFILES_ACTIVE = var.environment

    # Database (resolved by application-{staging,production}.yml).
    POSTGRES_FQDN   = var.postgres_fqdn
    BFF_DB_NAME     = "bff_session"
    BFF_DB_USER     = "bff"
    BFF_DB_PASSWORD = "@Microsoft.KeyVault(SecretUri=${var.keyvault_uri}secrets/bff-db-password)"

    # Inter-service routing — public FQDN, JWT-secured.
    BUSINESS_SERVICE_URL = "https://${local.business_fqdn}"

    # Keycloak (OIDC discovery + JWT issuer).
    KEYCLOAK_ISSUER_URI = local.keycloak_issuer_uri
    KEYCLOAK_CLIENT_ID  = "boat-app-confidential"

    # private_key_jwt signing material — PEM injected directly as an env var
    # via Key Vault reference. The BFF's BffConfig.java reads either
    # bff.signing-key.pem (this env var) OR bff.signing-key.path (filesystem
    # mount, used in compose). On App Service, only PEM is set.
    BFF_SIGNING_KEY_PEM = "@Microsoft.KeyVault(SecretUri=${var.keyvault_uri}secrets/bff-signing-key)"
    BFF_SIGNING_KEY_ID  = var.bff_signing_key_id
  }

  tags = var.tags
}

# ── business-service (public, port 8081, JWT-protected) ───────────────────
resource "azurerm_linux_web_app" "business_service" {
  name                = local.business_app_name
  resource_group_name = var.resource_group_name
  location            = azurerm_service_plan.this.location
  service_plan_id     = azurerm_service_plan.this.id

  https_only = true

  identity {
    type = "SystemAssigned"
  }

  site_config {
    always_on                                     = true
    health_check_path                             = "/actuator/health"
    container_registry_use_managed_identity       = true
    container_registry_managed_identity_client_id = null
    ftps_state                                    = "Disabled"
    minimum_tls_version                           = "1.2"

    application_stack {
      docker_image_name   = "business-service:${var.business_service_image_tag}"
      docker_registry_url = "https://${var.acr_login_server}"
    }
  }

  app_settings = {
    WEBSITES_PORT                       = "8081"
    DOCKER_ENABLE_CI                    = "false"
    WEBSITES_ENABLE_APP_SERVICE_STORAGE = "false"

    SPRING_PROFILES_ACTIVE = var.environment

    POSTGRES_FQDN        = var.postgres_fqdn
    BUSINESS_DB_NAME     = "boatapp"
    BUSINESS_DB_USER     = "business_service"
    BUSINESS_DB_PASSWORD = "@Microsoft.KeyVault(SecretUri=${var.keyvault_uri}secrets/business-db-password)"

    KEYCLOAK_ISSUER_URI = local.keycloak_issuer_uri
  }

  tags = var.tags
}

# ── Keycloak (public, port 8080, pulled straight from quay.io) ────────────
# Unlike the BFF / business-service which run code from this repo, Keycloak
# is upstream's own image — no custom Dockerfile, no ACR push. App Service
# pulls the public quay.io tag directly. The `start` (not --optimized)
# entry-point is used because we don't pre-bake `kc.sh build --db=postgres`;
# the on-start build adds ~30-60 s to a cold boot but avoids maintaining
# a derivative image.
#
# Realm bootstrap: NOT automated by this module. The boat-app realm and
# boat-app-confidential client must be applied post-first-deploy by
# operators (admin UI or `keycloak-config-cli` run from a workstation).
# The desired-state YAML lives at infra/keycloak/realm.yaml.
resource "azurerm_linux_web_app" "keycloak" {
  name                = local.keycloak_app_name
  resource_group_name = var.resource_group_name
  location            = azurerm_service_plan.this.location
  service_plan_id     = azurerm_service_plan.this.id

  https_only = true

  identity {
    type = "SystemAssigned"
  }

  site_config {
    always_on           = true
    health_check_path   = "/health/ready"
    ftps_state          = "Disabled"
    minimum_tls_version = "1.2"

    application_stack {
      docker_image_name   = "keycloak/keycloak:${var.keycloak_image_tag}"
      docker_registry_url = "https://quay.io"
    }

    app_command_line = "start"
  }

  app_settings = {
    WEBSITES_PORT                       = "8080"
    DOCKER_ENABLE_CI                    = "false"
    WEBSITES_ENABLE_APP_SERVICE_STORAGE = "false"

    KC_DB          = "postgres"
    KC_DB_URL      = var.jdbc_urls["keycloak"]
    KC_DB_USERNAME = "keycloak"
    KC_DB_PASSWORD = "@Microsoft.KeyVault(SecretUri=${var.keyvault_uri}secrets/keycloak-db-password)"

    # App Service is the TLS terminator; tell Keycloak to honour the
    # X-Forwarded-* headers it injects.
    KC_HOSTNAME        = "https://${local.keycloak_fqdn}"
    KC_PROXY_HEADERS   = "xforwarded"
    KC_HTTP_ENABLED    = "true"
    KC_HTTP_PORT       = "8080"
    KC_HEALTH_ENABLED  = "true"
    KC_METRICS_ENABLED = "true"

    # App Service health probes hit the container on 127.0.0.1:8080 with a
    # `Host: localhost` header. Keycloak production-mode `start` rejects
    # any host header that doesn't match KC_HOSTNAME unless this is off.
    KC_HOSTNAME_STRICT = "false"

    KEYCLOAK_ADMIN          = var.keycloak_admin_username
    KEYCLOAK_ADMIN_PASSWORD = "@Microsoft.KeyVault(SecretUri=${var.keyvault_uri}secrets/keycloak-admin-password)"
  }

  tags = var.tags
}

# ── Role assignments ──────────────────────────────────────────────────────
# AcrPull on the registry — only the BFF and business-service need it; the
# Keycloak Web App pulls from public quay.io.
resource "azurerm_role_assignment" "acr_pull" {
  for_each = {
    bff      = azurerm_linux_web_app.bff.identity[0].principal_id
    business = azurerm_linux_web_app.business_service.identity[0].principal_id
  }

  scope                = var.acr_id
  role_definition_name = "AcrPull"
  principal_id         = each.value
}

# Key Vault Secrets User on the vault — required for App Service to resolve
# @Microsoft.KeyVault references in `app_settings`. All three apps need this
# (Keycloak pulls KC_DB_PASSWORD + KEYCLOAK_ADMIN_PASSWORD from KV).
resource "azurerm_role_assignment" "kv_secrets_user" {
  for_each = {
    bff      = azurerm_linux_web_app.bff.identity[0].principal_id
    business = azurerm_linux_web_app.business_service.identity[0].principal_id
    keycloak = azurerm_linux_web_app.keycloak.identity[0].principal_id
  }

  scope                = var.keyvault_id
  role_definition_name = "Key Vault Secrets User"
  principal_id         = each.value
}
