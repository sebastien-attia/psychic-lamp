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

# ── Log Analytics workspace ───────────────────────────────────────────────
# The ACA Environment ships console + system streams here; without a
# workspace, `az containerapp logs show` only streams live and historical
# queries return nothing — which is exactly the diagnostic gap that left
# the recent bootstrap-db-roles failure unexplained for hours. The
# workspace is co-located with the ACA env (this module owns its
# observability surface). PerGB2018 SKU + 30-day retention is the cheap
# default; our log volume is tiny.
resource "azurerm_log_analytics_workspace" "aca" {
  name                = "${local.name_prefix}-aca-logs"
  location            = var.location
  resource_group_name = var.resource_group_name
  sku                 = "PerGB2018"
  retention_in_days   = 30
  tags                = var.tags
}

# ── ACA Environment ───────────────────────────────────────────────────────
resource "azurerm_container_app_environment" "this" {
  name                = "${local.name_prefix}-aca-env"
  resource_group_name = var.resource_group_name
  location            = var.location

  # When the ACA env was first provisioned, Azure auto-generated this
  # managed RG name; not declaring it in Terraform meant every subsequent
  # plan saw `null -> "ME_..."` and tagged the diff as force-replace —
  # which, combined with the subnet-delegation drift in networking/main.tf,
  # was the actual driver of the recent destroy cascade. Declaring it
  # explicitly with the same formula Azure uses (`ME_{env}_{rg}_{location}`)
  # eliminates the diff and is portable across environments.
  infrastructure_resource_group_name = "ME_${local.name_prefix}-aca-env_${var.resource_group_name}_${var.location}"

  infrastructure_subnet_id       = var.container_apps_subnet_id
  internal_load_balancer_enabled = false

  # Wire the workspace so all Container Apps + Jobs in this env stream
  # console output to a queryable destination. Setting this attribute on
  # an existing env is an in-place update under azurerm v4 — explicitly
  # not force-new — so adding it to a live env does NOT trigger the
  # multi-minute teardown that prevent_destroy below now also blocks.
  log_analytics_workspace_id = azurerm_log_analytics_workspace.aca.id

  workload_profile {
    name                  = "Consumption"
    workload_profile_type = "Consumption"
  }

  tags = var.tags

  # Destroy-and-recreate of this resource is a 19-minute outage of the
  # entire data plane (the recent staging deploy spent 15m53s on the
  # destroy alone) and triggers a cascade of Job/App recreations that
  # has its own failure modes. Force the operator to reach for
  # `-replace=` or temporarily lift this guard for any change that
  # would do so — the spurious replacement caught here in a recent
  # versionless-secret-IDs PR was the entire reason staging went red.
  #
  # Operator override flow when this guard trips:
  #   1. Comment out the `prevent_destroy = true` line (don't delete it).
  #   2. terraform plan — confirm the destroy is the one you intended.
  #   3. terraform apply.
  #   4. Restore the line in the same commit so master is never
  #      committed in the unprotected state.
  # (Properties that force-new on this resource: infrastructure_subnet_id,
  # workload_profile composition, internal_load_balancer_enabled.)
  lifecycle {
    prevent_destroy = true
  }
}

# ── bootstrap-db-roles Job (one-shot, runs before any workload starts) ───
# Solves the chicken-and-egg where the BFF / business-service / keycloak /
# Liquibase containers all try to log in as per-app PostgreSQL roles that
# the Flexible Server doesn't yet have. Without this Job the first
# `terraform apply` aborts after ~20 min with `Operation expired` because
# every workload's readiness probe stays DOWN waiting on a role that
# doesn't exist.
#
# Mirrors the SQL of the legacy Ansible bootstrap-db-roles.yml playbook
# (kept in infra/ansible/playbooks/ for break-glass) — same role names,
# same ownership transfer, same PUBLIC revoke. Idempotent: each statement
# is guarded so re-runs after the first apply produce no diffs.
resource "azurerm_container_app_job" "bootstrap_db_roles" {
  name                         = "bootstrap-db-roles"
  resource_group_name          = var.resource_group_name
  location                     = var.location
  container_app_environment_id = azurerm_container_app_environment.this.id

  workload_profile_name = "Consumption"

  identity {
    type = "SystemAssigned"
  }

  replica_timeout_in_seconds = 300
  replica_retry_limit        = 0

  manual_trigger_config {
    parallelism              = 1
    replica_completion_count = 1
  }

  secret {
    name                = "postgres-admin-password"
    key_vault_secret_id = var.keyvault_secret_ids["postgres-admin-password"]
    identity            = "System"
  }

  secret {
    name                = "bff-db-password"
    key_vault_secret_id = var.keyvault_secret_ids["bff-db-password"]
    identity            = "System"
  }

  secret {
    name                = "business-db-password"
    key_vault_secret_id = var.keyvault_secret_ids["business-db-password"]
    identity            = "System"
  }

  secret {
    name                = "keycloak-db-password"
    key_vault_secret_id = var.keyvault_secret_ids["keycloak-db-password"]
    identity            = "System"
  }

  template {
    container {
      name   = "bootstrap-db-roles"
      image  = var.bootstrap_db_roles_image
      cpu    = 0.25
      memory = "0.5Gi"

      command = ["/bin/sh"]
      args    = ["-c", file("${path.module}/files/bootstrap-db-roles.sh")]

      env {
        name  = "PGHOST"
        value = var.postgres_fqdn
      }
      env {
        name  = "PGUSER"
        value = var.postgres_admin_username
      }
      env {
        name        = "PGPASSWORD"
        secret_name = "postgres-admin-password"
      }
      env {
        name        = "BFF_DB_PASSWORD"
        secret_name = "bff-db-password"
      }
      env {
        name        = "BUSINESS_DB_PASSWORD"
        secret_name = "business-db-password"
      }
      env {
        name        = "KEYCLOAK_DB_PASSWORD"
        secret_name = "keycloak-db-password"
      }
    }
  }

  tags = var.tags
}

# Trigger an execution of the bootstrap Job whenever any of the password
# secrets (which the Job consumes) change — including the first apply.
# `triggers_replace` causes terraform_data to be recreated, which re-runs
# the local-exec provisioner. The Job script itself is idempotent, so a
# spurious re-trigger is harmless.
#
# RBAC propagation: the Job's system-assigned MI gets `Key Vault Secrets
# User` granted by the keyvault module AFTER the Job is created. The
# grant is a sibling to this terraform_data in the resource graph, so
# Terraform may schedule it concurrently — the loop below absorbs both
# the role-assignment-still-being-issued and Entra-RBAC-still-propagating
# windows by retrying with longer waits between attempts.
resource "terraform_data" "bootstrap_db_roles_run" {
  triggers_replace = [
    var.keyvault_secret_version_ids["postgres-admin-password"],
    var.keyvault_secret_version_ids["bff-db-password"],
    var.keyvault_secret_version_ids["business-db-password"],
    var.keyvault_secret_version_ids["keycloak-db-password"],
    azurerm_container_app_job.bootstrap_db_roles.id,
  ]

  # Auth contract: this provisioner shells out to `az containerapp job
  # ...` and inherits the calling shell's Azure login. In CI that's the
  # OIDC-federated identity established by `azure/login@v2`; locally
  # the operator must run `az login` first. Terraform's azurerm
  # provider auth is NOT used here.
  provisioner "local-exec" {
    interpreter = ["/bin/bash", "-c"]
    environment = {
      JOB_NAME              = azurerm_container_app_job.bootstrap_db_roles.name
      RG_NAME               = var.resource_group_name
      LOG_ANALYTICS_WS_GUID = azurerm_log_analytics_workspace.aca.workspace_id
    }
    command = <<-EOT
      set -euo pipefail

      # Up to 3 attempts. The first attempt may race the keyvault
      # module's role-assignment apply (RBAC not yet issued); attempts
      # 2 and 3 absorb Entra propagation tail (typical: 30–90 s, max:
      # ~3 min). Total wall-clock budget: ~6 min worst case, well under
      # the 20-min ACA "Operation expired" cap.
      for attempt in 1 2 3; do
        echo ">> bootstrap-db-roles: starting execution (attempt $attempt/3)"
        EXEC=$(az containerapp job start \
          --name "$JOB_NAME" --resource-group "$RG_NAME" \
          --query name -o tsv)

        # `az containerapp job start` MUST return the execution name
        # (`<job>-<6-hex>`), not the job name. Older az CLI versions
        # returned the job — guard explicitly so we don't poll a 404
        # forever and treat the silence as "still running".
        if [ "$EXEC" = "$JOB_NAME" ] || [ -z "$EXEC" ]; then
          echo "ERROR: az returned job name (or empty) where execution name expected: '$EXEC'" >&2
          echo "Upgrade az CLI to >= 2.53 (the runner must produce execution names)." >&2
          exit 2
        fi
        echo ">> bootstrap-db-roles: execution=$EXEC"

        # Poll for terminal status. replica_timeout_in_seconds caps
        # one execution at 300 s; 36 × 10 s = 360 s gives slack for
        # the "still queuing" prefix before the replica even starts.
        STATUS=Unknown
        for i in $(seq 1 36); do
          # Azure can intermittently return 409/412 while the execution
          # record is being reconciled. Treat one bad poll as Unknown
          # rather than killing the provisioner under `set -e`; terminal
          # Failed/Canceled/Degraded statuses still break the attempt.
          # `--name` is the JOB name; `--job-execution-name` is the
          # execution name returned by `az containerapp job start`.
          # Per `az containerapp job execution show --help` (CLI 2.85)
          # and the latest Microsoft Learn docs, both are required and
          # there is no `--job-name` alias. Mirrors the working
          # invocation in infra/ansible/playbooks/run-migrations.yml
          # ("Poll job execution until terminal state").
          if ! STATUS=$(az containerapp job execution show \
            --name "$JOB_NAME" --resource-group "$RG_NAME" \
            --job-execution-name "$EXEC" --query properties.status -o tsv 2>poll.err); then
            echo "  [$i/36] $EXEC: poll failed; treating as Unknown"
            sed 's/^/    /' poll.err >&2 || true
            STATUS=Unknown
          fi
          echo "  [$i/36] $EXEC: $STATUS"
          case "$STATUS" in
            Succeeded)                 echo ">> bootstrap-db-roles: succeeded"; exit 0 ;;
            Failed|Canceled|Degraded)  break ;;
          esac
          sleep 10
        done

        echo ">> bootstrap-db-roles: attempt $attempt ended with $STATUS"
        # Dump the execution's full properties so the workflow log carries
        # the replica's container state (terminationReason / exitCode) and
        # Azure-side error message. Without this the only failure signal
        # surfaced to CI is the bare "Failed" status — leaving us blind to
        # whether the script reached psql, whether secret injection
        # worked, or whether the container ever started.
        #
        # Safe to log: ACA returns `template.containers[].env[].secretRef`
        # (the secret *name*, e.g. "postgres-admin-password") and never the
        # resolved value. Resolved secrets are materialised inside the
        # replica process; ARM never sees them.
        #
        # stdout/stderr are split (mirroring the poll above on line 253)
        # so a flaky `az` call shows as a clear non-fatal warning instead
        # of mixing into the JSON dump. `[ -n "$EXEC" ]` is defensive
        # against any future refactor of the early-exit path that might
        # leave $EXEC unset.
        if [ -n "$${EXEC:-}" ] && [ "$EXEC" != "$JOB_NAME" ]; then
          echo ">> bootstrap-db-roles: dumping execution properties for $EXEC"
          if ! az containerapp job execution show \
                --name "$JOB_NAME" --resource-group "$RG_NAME" \
                --job-execution-name "$EXEC" --query properties \
                -o json 2>diag.err | sed 's/^/    /'; then
            echo "  [diag] az dump failed (non-fatal):" >&2
            sed 's/^/    /' diag.err >&2 || true
          fi

          # Pull the replica's stdout/stderr from Log Analytics so the
          # actual psql error (e.g. "could not translate host name",
          # "password authentication failed") surfaces in CI. ACA Jobs
          # ship console output to ContainerAppConsoleLogs_CL via the
          # workspace wired into the env above. Logs typically appear
          # within 30–60 s of the container exiting; sleep then query.
          # Same stdout/stderr split + non-fatal-on-failure pattern as
          # the properties dump.
          echo ">> bootstrap-db-roles: waiting 30 s for log ingest, then querying $EXEC console logs"
          sleep 30
          KQL="ContainerAppConsoleLogs_CL"
          KQL="$KQL | where ContainerJobName_s == '$JOB_NAME' and ExecutionName_s == '$EXEC'"
          KQL="$KQL | order by TimeGenerated asc"
          KQL="$KQL | project TimeGenerated, Log_s"
          KQL="$KQL | limit 200"
          # Capture stdout separately so we can distinguish "0 rows" from
          # "az failed". 0-row case usually means ingest lag — surface that
          # explicitly so the operator knows the diagnostic itself is the
          # limiting factor, not a missing log line.
          if OUT=$(az monitor log-analytics query \
                --workspace "$LOG_ANALYTICS_WS_GUID" \
                --analytics-query "$KQL" \
                -o tsv 2>la.err); then
            if [ -z "$OUT" ]; then
              echo "  [diag] log-analytics returned 0 rows — ingest lag, missing workspace wiring, or job never wrote stdout"
            else
              printf '%s\n' "$OUT" | sed 's/^/    /'
            fi
          else
            echo "  [diag] log-analytics query failed (non-fatal — logs may not have ingested yet):" >&2
            sed 's/^/    /' la.err >&2 || true
          fi
        fi
        if [ "$attempt" -lt 3 ]; then
          echo ">> bootstrap-db-roles: waiting 60 s for RBAC propagation"
          sleep 60
        fi
      done

      echo ">> bootstrap-db-roles: all attempts exhausted" >&2
      exit 1
    EOT
  }
}

# ── BFF (external ingress, serves Vue SPA + /api/* proxy) ─────────────────
resource "azurerm_container_app" "bff" {
  name                         = "bff"
  container_app_environment_id = azurerm_container_app_environment.this.id
  resource_group_name          = var.resource_group_name
  revision_mode                = "Single"

  workload_profile_name = "Consumption"

  # Block creation until per-app PostgreSQL roles exist. Without this,
  # Spring Boot's readiness probe fails on FATAL: role "bff" does not
  # exist and the revision provisioning expires after 20 minutes.
  depends_on = [terraform_data.bootstrap_db_roles_run]

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

  depends_on = [terraform_data.bootstrap_db_roles_run]

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

  depends_on = [terraform_data.bootstrap_db_roles_run]

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
      # Pulled from the project's ACR. The image is built by the CI
      # `build-push` job from keycloak/Dockerfile, which layers
      # `kc.sh build --db=postgres` on top of the upstream
      # quay.io/keycloak/keycloak base — `start --optimized` below
      # requires that bake or it crashes with "Unable to find the
      # database vendor".
      image  = "${var.acr_login_server}/keycloak:${var.keycloak_image_tag}"
      cpu    = 0.5
      memory = "1Gi"

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

  depends_on = [terraform_data.bootstrap_db_roles_run]

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
