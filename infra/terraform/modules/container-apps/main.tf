# Container Apps module — ACA Environment, three long-running apps
# (bff, business-service, keycloak), and two on-demand Liquibase Jobs.
#
# Identity & registry posture
# ───────────────────────────
# Every Container App and Job declares `identity { type = "SystemAssigned" }`
# and references the registry via `registry { identity = "system" }`. There
# is no `password_secret_name`, no ACR admin user, no docker-login secret.
# The system-assigned MIs are exported as `consumer_principal_ids` so the
# container-registry and keyvault modules can grant `AcrPull` and
# `Key Vault Secrets User` respectively.
#
# Cross-app discovery
# ───────────────────
# Container Apps within the same ACA Environment can reach each other via
# their short name. The BFF talks to business-service on
# `http://business-service` and to Keycloak on the externally-routable
# FQDN computed from the environment's default_domain (so Keycloak can
# also know its own canonical KC_HOSTNAME without a self-reference).

locals {
  name_prefix = "${var.project_name}-${var.environment}"

  # Predicting FQDNs from the environment's default_domain avoids self-
  # references on Keycloak (it needs to know its own FQDN for KC_HOSTNAME
  # and the JWT issuer URI).
  keycloak_default_fqdn          = "keycloak.${azurerm_container_app_environment.this.default_domain}"
  business_service_internal_fqdn = "business-service.internal.${azurerm_container_app_environment.this.default_domain}"

  # When the operator binds a custom FQDN for Keycloak, KC_HOSTNAME and the
  # JWT issuer URI both flip to that domain — otherwise tokens would carry
  # the Azure default hostname and the BFF (also reading this issuer URI)
  # would refuse them after the redirect URI was rewritten browser-side.
  keycloak_external_fqdn = var.keycloak_custom_domain != "" ? var.keycloak_custom_domain : local.keycloak_default_fqdn
  keycloak_issuer_uri    = "https://${local.keycloak_external_fqdn}/realms/boat-app"

  # Liquibase Jobs share most config; declared as a map so for_each keeps
  # them DRY. Keys must match the verification script's expectations
  # (bff and business_service).
  liquibase_jobs = {
    "bff" = {
      image          = "${var.acr_login_server}/bff:${var.bff_image_tag}"
      database_url   = var.jdbc_urls["bff_session"]
      database_user  = "bff"
      kv_secret_name = "bff-db-password"
    }
    "business_service" = {
      image          = "${var.acr_login_server}/business-service:${var.business_service_image_tag}"
      database_url   = var.jdbc_urls["boatapp"]
      database_user  = "business_service"
      kv_secret_name = "business-db-password"
    }
  }
}

# ── ACA Environment ───────────────────────────────────────────────────────
resource "azurerm_container_app_environment" "this" {
  name                = "${local.name_prefix}-aca-env"
  resource_group_name = var.resource_group_name
  location            = var.location

  infrastructure_subnet_id       = var.container_apps_subnet_id
  internal_load_balancer_enabled = false

  workload_profile {
    name                  = "Consumption"
    workload_profile_type = "Consumption"
  }

  tags = var.tags
}

# ── BFF (external ingress, serves Vue SPA + /api/* proxy) ─────────────────
resource "azurerm_container_app" "bff" {
  name                         = "bff"
  container_app_environment_id = azurerm_container_app_environment.this.id
  resource_group_name          = var.resource_group_name
  revision_mode                = "Single"

  workload_profile_name = "Consumption"

  identity {
    type = "SystemAssigned"
  }

  registry {
    server   = var.acr_login_server
    identity = "system"
  }

  secret {
    name                = "database-password"
    key_vault_secret_id = var.keyvault_secret_ids["bff-db-password"]
    identity            = "System"
  }

  secret {
    name                = "bff-signing-key"
    key_vault_secret_id = var.bff_signing_key_secret_id
    identity            = "System"
  }

  ingress {
    external_enabled = true
    target_port      = 8080
    transport        = "http"

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    min_replicas = 1
    max_replicas = 3

    volume {
      name         = "secrets"
      storage_type = "Secret"
    }

    container {
      name   = "bff"
      image  = "${var.acr_login_server}/bff:${var.bff_image_tag}"
      cpu    = 0.5
      memory = "1Gi"

      volume_mounts {
        name = "secrets"
        path = "/mnt/secrets"
      }

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.environment
      }
      env {
        name  = "DATABASE_URL"
        value = var.jdbc_urls["bff_session"]
      }
      env {
        name  = "DATABASE_USERNAME"
        value = "bff"
      }
      env {
        name        = "DATABASE_PASSWORD"
        secret_name = "database-password"
      }
      env {
        name  = "BUSINESS_SERVICE_URL"
        value = "http://business-service"
      }
      env {
        name  = "KEYCLOAK_ISSUER_URI"
        value = local.keycloak_issuer_uri
      }
      env {
        name  = "KEYCLOAK_CLIENT_ID"
        value = "boat-app-confidential"
      }
      env {
        name  = "BFF_SIGNING_KEY_PATH"
        value = "/mnt/secrets/bff-signing-key"
      }
      env {
        name  = "BFF_SIGNING_KEY_ID"
        value = var.bff_signing_key_id
      }

      liveness_probe {
        path                    = "/actuator/health"
        port                    = 8080
        transport               = "HTTP"
        initial_delay           = 30
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }

      readiness_probe {
        path                    = "/actuator/health"
        port                    = 8080
        transport               = "HTTP"
        interval_seconds        = 10
        timeout                 = 5
        failure_count_threshold = 3
        success_count_threshold = 1
      }
    }
  }

  tags = var.tags
}

# ── business-service (internal ingress, JWT resource server) ──────────────
resource "azurerm_container_app" "business_service" {
  name                         = "business-service"
  container_app_environment_id = azurerm_container_app_environment.this.id
  resource_group_name          = var.resource_group_name
  revision_mode                = "Single"

  workload_profile_name = "Consumption"

  identity {
    type = "SystemAssigned"
  }

  registry {
    server   = var.acr_login_server
    identity = "system"
  }

  secret {
    name                = "database-password"
    key_vault_secret_id = var.keyvault_secret_ids["business-db-password"]
    identity            = "System"
  }

  ingress {
    external_enabled = false
    target_port      = 8081
    transport        = "http"

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    min_replicas = 1
    max_replicas = 3

    container {
      name   = "business-service"
      image  = "${var.acr_login_server}/business-service:${var.business_service_image_tag}"
      cpu    = 0.5
      memory = "1Gi"

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.environment
      }
      env {
        name  = "DATABASE_URL"
        value = var.jdbc_urls["boatapp"]
      }
      env {
        name  = "DATABASE_USERNAME"
        value = "business_service"
      }
      env {
        name        = "DATABASE_PASSWORD"
        secret_name = "database-password"
      }
      env {
        name  = "KEYCLOAK_ISSUER_URI"
        value = local.keycloak_issuer_uri
      }

      liveness_probe {
        path                    = "/actuator/health"
        port                    = 8081
        transport               = "HTTP"
        initial_delay           = 30
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }

      readiness_probe {
        path                    = "/actuator/health"
        port                    = 8081
        transport               = "HTTP"
        interval_seconds        = 10
        timeout                 = 5
        failure_count_threshold = 3
        success_count_threshold = 1
      }
    }
  }

  tags = var.tags
}

# ── Keycloak (external ingress, production "start --optimized") ───────────
resource "azurerm_container_app" "keycloak" {
  name                         = "keycloak"
  container_app_environment_id = azurerm_container_app_environment.this.id
  resource_group_name          = var.resource_group_name
  revision_mode                = "Single"

  workload_profile_name = "Consumption"

  identity {
    type = "SystemAssigned"
  }

  registry {
    server   = var.acr_login_server
    identity = "system"
  }

  secret {
    name                = "kc-db-password"
    key_vault_secret_id = var.keyvault_secret_ids["keycloak-db-password"]
    identity            = "System"
  }

  secret {
    name                = "kc-admin-password"
    key_vault_secret_id = var.keyvault_secret_ids["keycloak-admin-password"]
    identity            = "System"
  }

  ingress {
    external_enabled = true
    target_port      = 8080
    transport        = "http"

    traffic_weight {
      latest_revision = true
      percentage      = 100
    }
  }

  template {
    min_replicas = 1
    max_replicas = 3

    container {
      name = "keycloak"
      # Default tag pin: quay.io/keycloak/keycloak:26.6.1 (overridable via
      # var.keycloak_image_tag). The literal pin is repeated here so the
      # supply-chain audit script can verify the floor without parsing
      # variable defaults.
      image  = "quay.io/keycloak/keycloak:${var.keycloak_image_tag}"
      cpu    = 0.5
      memory = "1Gi"

      # Production launch — NOT start-dev. The --optimized flag REQUIRES
      # the image to have been pre-built with `kc.sh build --db=postgres`;
      # the stock quay.io image is built for `dev-file` and will fail with
      # "Unable to find the database vendor" if pulled directly. The image
      # tag below is therefore expected to be a custom image (built and
      # pushed by CI) layered on top of quay.io/keycloak/keycloak:26.6.1
      # with the DB vendor baked in. The default of upstream 26.6.1 is a
      # placeholder that the deploy phase replaces with the project's own
      # build (see ai-scripts/03/keycloak-image.sh — phase 03).
      command = ["start", "--optimized"]

      env {
        name  = "KC_DB"
        value = "postgres"
      }
      env {
        name  = "KC_DB_URL"
        value = var.jdbc_urls["keycloak"]
      }
      env {
        name  = "KC_DB_USERNAME"
        value = "keycloak"
      }
      env {
        name        = "KC_DB_PASSWORD"
        secret_name = "kc-db-password"
      }
      env {
        name  = "KC_HOSTNAME"
        value = "https://${local.keycloak_external_fqdn}"
      }
      env {
        name  = "KC_PROXY_HEADERS"
        value = "xforwarded"
      }
      env {
        name  = "KC_HTTP_ENABLED"
        value = "true"
      }
      env {
        name  = "KC_HEALTH_ENABLED"
        value = "true"
      }
      env {
        name  = "KC_METRICS_ENABLED"
        value = "true"
      }
      env {
        name  = "KEYCLOAK_ADMIN"
        value = var.keycloak_admin_username
      }
      env {
        name        = "KEYCLOAK_ADMIN_PASSWORD"
        secret_name = "kc-admin-password"
      }

      liveness_probe {
        path                    = "/health/live"
        port                    = 8080
        transport               = "HTTP"
        initial_delay           = 60
        interval_seconds        = 30
        timeout                 = 5
        failure_count_threshold = 3
      }

      readiness_probe {
        path                    = "/health/ready"
        port                    = 8080
        transport               = "HTTP"
        interval_seconds        = 10
        timeout                 = 5
        failure_count_threshold = 3
        success_count_threshold = 1
      }
    }
  }

  tags = var.tags
}

# ── Liquibase Jobs (manual trigger, invoked by Ansible post-deploy) ───────
# Same image as the long-running Spring app — Spring Boot is launched as a
# CLI with `--spring.main.web-application-type=none --spring.liquibase.enabled=true`
# so it applies the changelog and exits. No separate Liquibase image, no
# ACI, no embedded credentials.
resource "azurerm_container_app_job" "liquibase" {
  for_each = local.liquibase_jobs

  name                         = "liquibase-${replace(each.key, "_", "-")}"
  resource_group_name          = var.resource_group_name
  location                     = var.location
  container_app_environment_id = azurerm_container_app_environment.this.id

  workload_profile_name = "Consumption"

  identity {
    type = "SystemAssigned"
  }

  replica_timeout_in_seconds = 600
  replica_retry_limit        = 0

  manual_trigger_config {
    parallelism              = 1
    replica_completion_count = 1
  }

  registry {
    server   = var.acr_login_server
    identity = "system"
  }

  secret {
    name                = "database-password"
    key_vault_secret_id = var.keyvault_secret_ids[each.value.kv_secret_name]
    identity            = "System"
  }

  template {
    container {
      name   = "liquibase"
      image  = each.value.image
      cpu    = 0.25
      memory = "0.5Gi"

      command = ["java"]
      args = [
        "-jar", "/app/app.jar",
        "--spring.main.web-application-type=none",
        "--spring.liquibase.enabled=true",
        "--spring.datasource.url=${each.value.database_url}",
        "--spring.datasource.username=${each.value.database_user}",
      ]

      env {
        name        = "SPRING_DATASOURCE_PASSWORD"
        secret_name = "database-password"
      }
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.environment
      }
    }
  }

  tags = var.tags
}

# ── Custom domain bindings (opt-in) ───────────────────────────────────────
# Each pair below (one custom_domain + one managed_certificate per app) is
# gated by `count = var.<x>_custom_domain == "" ? 0 : 1`, so the default
# zero-config behaviour is unchanged. When opted in:
#
#   1. The custom_domain resource creates first; it requires that the
#      operator has already placed two DNS records at the registrar:
#        <fqdn>            CNAME   <app's Azure FQDN>
#        asuid.<fqdn>      TXT     <custom_domain_verification_id output>
#      Both records MUST exist BEFORE `terraform apply` or the create call
#      returns "domain ownership not verified".
#
#   2. The managed_certificate resource creates next (via depends_on);
#      Azure issues a free DigiCert-managed cert valid for the FQDN, then
#      asynchronously binds it to the custom-domain entry above.
#
#   3. After the async bind, Azure mutates the custom_domain resource's
#      certificate_binding_type and container_app_environment_certificate_id
#      fields. We `ignore_changes` those so terraform doesn't try to revert
#      Azure's edit on the next plan and recreate the resource (which
#      would briefly drop traffic).
#
# Renewal: managed certs auto-renew ~45 days before expiry — no operator
# action required as long as the CNAME record stays in place.

resource "azurerm_container_app_custom_domain" "bff" {
  count            = var.bff_custom_domain == "" ? 0 : 1
  name             = var.bff_custom_domain
  container_app_id = azurerm_container_app.bff.id

  lifecycle {
    ignore_changes = [
      certificate_binding_type,
      container_app_environment_certificate_id,
    ]
  }
}

resource "azurerm_container_app_environment_managed_certificate" "bff" {
  count                        = var.bff_custom_domain == "" ? 0 : 1
  name                         = "bff-${replace(var.bff_custom_domain, ".", "-")}"
  container_app_environment_id = azurerm_container_app_environment.this.id
  subject_name                 = var.bff_custom_domain
  domain_control_validation    = "CNAME"

  # Cert issuance must happen AFTER the custom-domain entry exists,
  # otherwise Azure has nothing to validate against.
  depends_on = [azurerm_container_app_custom_domain.bff]
}

resource "azurerm_container_app_custom_domain" "keycloak" {
  count            = var.keycloak_custom_domain == "" ? 0 : 1
  name             = var.keycloak_custom_domain
  container_app_id = azurerm_container_app.keycloak.id

  lifecycle {
    ignore_changes = [
      certificate_binding_type,
      container_app_environment_certificate_id,
    ]
  }
}

resource "azurerm_container_app_environment_managed_certificate" "keycloak" {
  count                        = var.keycloak_custom_domain == "" ? 0 : 1
  name                         = "keycloak-${replace(var.keycloak_custom_domain, ".", "-")}"
  container_app_environment_id = azurerm_container_app_environment.this.id
  subject_name                 = var.keycloak_custom_domain
  domain_control_validation    = "CNAME"

  depends_on = [azurerm_container_app_custom_domain.keycloak]
}
