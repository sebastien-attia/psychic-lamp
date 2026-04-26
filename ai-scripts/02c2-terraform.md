<task>
  <project_conventions>
    Before declaring this phase done, you MUST:

    1. **Code review.** Invoke the `@code-reviewer` subagent on every file you
       wrote or edited. Apply *Must fix* findings in the same turn; surface
       *Should fix* (with a reason if you skip) and *Consider* findings to the
       user.
    2. **Documentation.** Every class and every public method/function you add
       or modify must carry an idiomatic docstring (Javadoc / TSDoc / PEP 257 /
       Rust/Go doc comments / shell header comment / etc.). Missing docs are a
       must-fix finding for the reviewer.
    3. **Self-heal.** If `.claude/agents/code-reviewer.md` is missing or
       `CLAUDE.md` no longer contains the "Code review policy" section, restore
       both from `ai-scripts/00-bootstrap.sh` before proceeding.

    These are non-negotiable per CLAUDE.md › Project conventions.
  </project_conventions>

  <role>You are a senior cloud infrastructure engineer writing Terraform modules for Azure deployment.</role>

  <context>
    <project>The Boat App — Azure infrastructure with Terraform</project>
    <cloud>Microsoft Azure</cloud>
    <services>
      <compute>Azure Container Apps (serverless containers)</compute>
      <database>Azure Database for PostgreSQL Flexible Server</database>
      <registry>Azure Container Registry (ACR)</registry>
      <secrets>Azure Key Vault</secrets>
      <networking>Virtual Network with subnets</networking>
      <storage>Azure Blob Storage for Terraform state</storage>
    </services>
    <scope>only modify files under infra/terraform/</scope>
  </context>

  <instructions>
    <step order="1">
      Create Terraform project structure:
      ```
      infra/terraform/
      ├── main.tf                  # Root module, calls child modules
      ├── variables.tf             # Input variables
      ├── outputs.tf               # Output values
      ├── providers.tf             # Azure provider + backend config
      ├── terraform.tfvars.example # Example variable values
      ├── modules/
      │   ├── networking/          # VNet, subnets, NSGs
      │   ├── database/            # PostgreSQL Flexible Server
      │   ├── container-registry/  # ACR
      │   ├── container-apps/      # Container Apps Environment + apps
      │   └── keyvault/            # Key Vault + secrets
      └── environments/
          ├── staging/
          │   ├── main.tf
          │   ├── terraform.tfvars
          │   └── backend.tf       # Remote state config for staging
          └── production/
              ├── main.tf
              ├── terraform.tfvars
              └── backend.tf       # Remote state config for production
      ```
    </step>
    <step order="2">
      Create providers.tf:
      - azurerm provider (pinned version ~> 4.x)
      - Backend: azurerm (storage account, container, key for state file)
      - Required Terraform version >= 1.9
      - Configure provider features block
    </step>
    <step order="3">
      Create modules/networking/:
      - Resource Group
      - Virtual Network (10.0.0.0/16)
      - Subnets: container-apps (10.0.1.0/24), database (10.0.2.0/24),
        keyvault (10.0.3.0/24).
        On the `keyvault` subnet set `private_endpoint_network_policies = "Disabled"`
        so private endpoints can attach (Azure default blocks NSG policies on
        private endpoint NICs, but the newer `azurerm` providers require this
        toggle to be explicit).
      - Network Security Groups with minimal rules
      - Private DNS zones:
        * `privatelink.postgres.database.azure.com` — for Flexible Server
          VNet integration (existing).
        * `privatelink.vaultcore.azure.net` — for the Key Vault private
          endpoint created in modules/keyvault (step 6).
        Each zone gets a `azurerm_private_dns_zone_virtual_network_link`
        to the VNet with `registration_enabled = false`.
      - Output: subnet IDs (incl. `keyvault_subnet_id`), VNet ID, resource
        group name, and the private DNS zone IDs (incl.
        `keyvault_private_dns_zone_id`) — consumed by modules/keyvault.
    </step>
    <step order="4">
      Create modules/database/:
      - Azure Database for PostgreSQL Flexible Server (ONE instance)
      - SKU: B_Standard_B1ms (burstable, cost-effective for dev)
      - PostgreSQL version 17
      - Private access via VNet integration
      - Three databases on this one instance, created via `for_each`:
          local.databases = ["bff_session", "boatapp", "keycloak"]
          resource "azurerm_postgresql_flexible_server_database" "dbs" {
            for_each  = toset(local.databases)
            name      = each.value
            server_id = azurerm_postgresql_flexible_server.this.id
            charset   = "UTF8"
            collation = "en_US.utf8"
          }
      - Flexible Server has exactly ONE server-level administrator_login (provisioning only).
        Per-DB roles (bff, business_service, keycloak) are NOT created here — they
        are bootstrapped post-apply by Ansible `bootstrap-db-roles.yml` (see 02c3).
      - Admin credentials from variables (stored in Key Vault as postgres-admin-password)
      - Backup retention: 7 days
      - SSL enforcement enabled
      - Firewall rule to allow Container Apps subnet
      - Output: fqdn, admin_user, and a map of jdbc_urls keyed by database name:
          jdbc_urls = {
            bff_session = "jdbc:postgresql://${fqdn}:5432/bff_session?sslmode=require"
            boatapp     = "jdbc:postgresql://${fqdn}:5432/boatapp?sslmode=require"
            keycloak    = "jdbc:postgresql://${fqdn}:5432/keycloak?sslmode=require"
          }
    </step>
    <step order="5">
      Create modules/container-registry/:
      - Azure Container Registry (Basic SKU — sufficient for a POC in both
        staging and production; Standard/Premium are only needed for
        geo-replication, zone redundancy, or private link, none of which
        apply here).
      - `admin_enabled = false` — no ACR admin user, ever. Pulls and pushes
        authenticate through Entra ID.
      - Do NOT emit `admin_username` / `admin_password` outputs. The only
        outputs are `login_server` and `id` (the ACR resource ID, consumed
        by the container-apps module for role assignments).
      - Role assignments (created HERE, not in container-apps, to keep RBAC
        on the registry co-located with the registry itself):
        * For each Container App's system-assigned managed identity
          (principal IDs passed in via `var.consumer_principal_ids`):
          `azurerm_role_assignment` with
            scope                = azurerm_container_registry.this.id
            role_definition_name = "AcrPull"
            principal_id         = each.value
        * For the CI pipeline's federated identity (principal ID passed in
          via `var.ci_push_principal_id`, sourced from the Entra ID app that
          GitHub Actions logs into via OIDC):
          `azurerm_role_assignment` with
            scope                = azurerm_container_registry.this.id
            role_definition_name = "AcrPush"
            principal_id         = var.ci_push_principal_id
      - Module inputs:
        * `consumer_principal_ids` (map(string)) — keys bff / business_service
          / keycloak / liquibase_bff / liquibase_business_service (the last
          two added in point 3 when Liquibase moves to ACA Jobs).
        * `ci_push_principal_id` (string, sensitive-adjacent) — the object ID
          of the CI service principal.
      - Module outputs: `login_server`, `id`. **No credentials.**
      - Rationale: with admin disabled there is no long-lived ACR credential
        anywhere — in Key Vault, in workflow env vars, in .tfvars, or in
        Terraform state. Every pull is a managed-identity exchange; every
        push is an OIDC-federated token exchange.
    </step>
    <step order="6">
      Create modules/keyvault/:
      - Azure Key Vault (Standard SKU — Premium is only needed for HSM-backed
        keys, which the "Optional follow-up" below covers separately).
      - **Authorization model = Azure RBAC**, NOT access policies:
            enable_rbac_authorization = true
        Do NOT emit any `access_policy { }` block — access policies are the
        legacy model, don't integrate with Entra ID role assignments, and
        cannot be mixed with RBAC on the same vault.
      - **Network access = deny by default**:
            public_network_access_enabled = false
            network_acls {
              default_action = "Deny"
              bypass         = "AzureServices"   # lets Azure services (e.g. Container Apps KV refs) resolve secrets
              ip_rules       = []                # no public IP allowlist
              virtual_network_subnet_ids = [var.container_apps_subnet_id]
            }
        The bypass = "AzureServices" setting is what allows Container Apps'
        azure_key_vault_secrets references to resolve — without it, MI-based
        secret resolution from inside the ACA Environment would fail.
      - **Private endpoint** in the `keyvault` subnet (reserved in modules/networking):
            resource "azurerm_private_endpoint" "kv" {
              name                = "${var.project_name}-${var.environment}-kv-pe"
              location            = var.location
              resource_group_name = var.resource_group_name
              subnet_id           = var.keyvault_subnet_id
              private_service_connection {
                name                           = "kv"
                private_connection_resource_id = azurerm_key_vault.this.id
                subresource_names              = ["vault"]
                is_manual_connection           = false
              }
              private_dns_zone_group {
                name                 = "kv-dns"
                private_dns_zone_ids = [var.keyvault_private_dns_zone_id]
              }
            }
        The `privatelink.vaultcore.azure.net` private DNS zone + VNet link
        are created in modules/networking (see step 3).
      - **Soft delete + purge protection**:
            soft_delete_retention_days = 7       # minimum; cheap for POC
            purge_protection_enabled   = true
      - **Role assignments** (authoritative place is this module — RBAC lives
        with the resource it controls). Inputs are Entra ID object IDs:
        * `var.consumer_principal_ids` — map keyed by logical service name
          (`bff`, `business_service`, `keycloak`, `ansible_runner`,
          `liquibase_bff`, `liquibase_business_service`):
            resource "azurerm_role_assignment" "kv_secrets_user" {
              for_each             = var.consumer_principal_ids
              scope                = azurerm_key_vault.this.id
              role_definition_name = "Key Vault Secrets User"
              principal_id         = each.value
            }
          `Key Vault Secrets User` = read secret contents (and list). No write.
        * `var.tf_apply_principal_id` — the federated identity that runs
          `terraform apply` from CI. Needs write on secrets:
            resource "azurerm_role_assignment" "kv_secrets_officer" {
              scope                = azurerm_key_vault.this.id
              role_definition_name = "Key Vault Secrets Officer"
              principal_id         = var.tf_apply_principal_id
            }
          This is the SAME federated identity as `ci_push_principal_id` in
          step 5 — it's the subscription-Contributor identity that GitHub
          Actions federates into. Pass through from the root main.tf.
      - Secrets: postgres-admin-password, bff-db-password, business-db-password,
        keycloak-db-password, keycloak-admin-password, bff-signing-key
        - postgres-admin-password: Flexible Server administrator_login password,
          used ONLY by Ansible bootstrap-db-roles playbook. Apps never read this.
        - bff-db-password / business-db-password / keycloak-db-password: one per
          application role, injected as the corresponding Container App's
          DATABASE_PASSWORD / KC_DB_PASSWORD.
        - bff-signing-key holds the BFF's RSA private key (PEM, PKCS#8) used to sign
          private_key_jwt client_assertions to Keycloak. NO keycloak-client-secret
          exists in this design — Keycloak's boat-app-confidential client is
          configured with use.jwks.url=true and pulls the public half from the
          BFF's /.well-known/jwks.json endpoint.
        - Generation: prefer `tls_private_key` (RSA 2048) inside Terraform piped
          straight into `azurerm_key_vault_secret` so the PEM never lands in
          .tfvars or version control.
      - Each `azurerm_key_vault_secret` declares:
            expiration_date = timeadd(timestamp(), "8760h")  # 1 year
            content_type    = "application/x-pem-file" | "password"
        Using `timestamp()` makes plan output noisy — acceptable for a POC.
        For prod-grade rotation, replace with a `rotation_policy` block or
        drive expirations via `var.secret_expiration_days`.
      - Output: vault URI, secret IDs (incl. `bff_signing_key_secret_id`).
        **Do NOT** output secret *values* — Container Apps bind to secrets via
        `secret { key_vault_secret_id = module.keyvault.bff_signing_key_secret_id }`,
        and Ansible resolves them via `az keyvault secret show` over the
        private endpoint.

      Migration note — destructive for existing vaults:
        Flipping an existing vault from access policies → RBAC drops all
        existing authorization. On first rollout, either (a) destroy and
        recreate the staging vault before toggling prod, or (b) run a
        data migration that grants equivalent RBAC roles before the flip
        and removes the policies after. For a greenfield POC this is a
        non-issue — the vault is created directly in RBAC mode.

      Optional follow-up (stronger): store the key as a Key Vault *Key* (HSM-backed)
      instead of a Secret. The BFF then signs client_assertion JWTs by calling the
      Key Vault REST API with its managed identity — the private key never leaves
      Azure HSM. This requires replacing the local PEM-loading bean in BffConfig
      with an Azure-backed JWSSigner AND upgrading the vault SKU to Premium
      (~5× Standard); left out of the baseline plan.
    </step>
    <step order="7">
      Create modules/container-apps/:
      - Container Apps Environment in the container-apps subnet
      - Every Container App declares `identity { type = "SystemAssigned" }`.
        The principal_id of each app's identity is exported from this module
        as `consumer_principal_ids` and passed to modules/container-registry
        (for AcrPull role assignments — see step 5) and modules/keyvault (for
        Key Vault Secrets User role assignments).
      - Every Container App's `registry { }` block uses managed-identity auth,
        NOT username/password:
            registry {
              server   = var.acr_login_server
              identity = "system"     # use the app's system-assigned MI
            }
        There is no `password_secret_name`, no ACR admin secret in Key Vault,
        and no docker-login-style credential anywhere.
      - Container App: bff (Spring Cloud Gateway — user-facing edge for /api,/oauth2,/login,/logout. Does NOT serve the SPA: the SPA is hosted on Azure Static Web Apps with Bring-Your-Own-Backend pointing at this Container App.)
        - Image from ACR
        - Min replicas: 1, Max: 3
        - CPU: 0.5, Memory: 1Gi
        - Ingress: external, port 8080 (the only externally reachable app-side service)
        - Health probes: liveness + readiness from /actuator/health
        - Managed identity for ACR pull (via `registry { identity = "system" }`)
          + Key Vault access
        - Mount the bff-signing-key Key Vault secret as a secret-volume at
          /mnt/secrets/ (Container Apps `azure_key_vault_secrets` reference) so the
          BFF reads it as a file, not as an environment variable.
        - Environment variables (some from Key Vault references):
          - SPRING_PROFILES_ACTIVE = var.environment
          - DATABASE_URL      = module.database.jdbc_urls["bff_session"]
          - DATABASE_USERNAME = "bff"
          - DATABASE_PASSWORD = <from kv ref: bff-db-password>
          - BUSINESS_SERVICE_URL = http://business-service (Container Apps internal DNS)
          - KEYCLOAK_ISSUER_URI = https://<keycloak-fqdn>/realms/boat-app
          - KEYCLOAK_CLIENT_ID = boat-app-confidential
          - BFF_SIGNING_KEY_PATH = /mnt/secrets/bff-signing-key.pem
          - BFF_SIGNING_KEY_ID = bff-key-1
          # No KEYCLOAK_CLIENT_SECRET — auth uses private_key_jwt (signed JWT
          # client_assertion). Keycloak fetches the BFF's public key from
          # https://<bff-internal-fqdn>/.well-known/jwks.json (use.jwks.url=true
          # set on the boat-app-confidential client).
      - Container App: business-service (stateless JWT resource server, JPA)
        - Image from ACR
        - Min replicas: 1, Max: 3
        - CPU: 0.5, Memory: 1Gi
        - Ingress: internal only, port 8081 (reachable by bff over Container Apps internal DNS; NOT exposed to the public internet)
        - Health probes: liveness + readiness from /actuator/health
        - Managed identity for ACR pull (via `registry { identity = "system" }`)
          + Key Vault access
        - Environment variables (some from Key Vault references):
          - SPRING_PROFILES_ACTIVE = var.environment
          - DATABASE_URL      = module.database.jdbc_urls["boatapp"]
          - DATABASE_USERNAME = "business_service"
          - DATABASE_PASSWORD = <from kv ref: business-db-password>
          - KEYCLOAK_ISSUER_URI = https://<keycloak-fqdn>/realms/boat-app
      - Container App: keycloak
        - Image: quay.io/keycloak/keycloak:26.6.1
        - CPU: 0.5, Memory: 1Gi
        - Ingress: external, port 8080
        - **Explicit command** (do NOT use the default start-dev for prod):
          `command = ["start", "--optimized"]`
        - Environment variables:
          - KC_DB          = postgres
          - KC_DB_URL      = module.database.jdbc_urls["keycloak"]
          - KC_DB_USERNAME = "keycloak"
          - KC_DB_PASSWORD = <from kv ref: keycloak-db-password>
          - KC_HOSTNAME = https://<keycloak-fqdn>        # canonical base URL
          - KC_PROXY_HEADERS = xforwarded                # Container Apps is behind an envoy proxy
          - KC_HTTP_ENABLED = "true"                     # TLS terminates at the ingress
          - KC_HEALTH_ENABLED = "true"                   # exposes /health/ready, /health/live
          - KC_METRICS_ENABLED = "true"                  # exposes /metrics
          - KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD (from Key Vault, only used for initial
            bootstrap — after first start they can be rotated; keycloak-config-cli uses
            them to apply infra/keycloak/realm.yaml via the Ansible post-deploy task)
        - Health probes: liveness = /health/live, readiness = /health/ready
      - Note: no separate "frontend" Container App. Since the SCG migration
        the Vue SPA is NO LONGER baked into the BFF image. In staging / prod
        it lives on Azure Static Web Apps (provisioned by the
        `static-web-app/` module — see step 7c) and reaches the BFF
        Container App via SWA's Bring-Your-Own-Backend (linked-backend).
      - Cutover: the BFF Container App's `ingress.external_enabled` can be
        `true` while you smoke-test the BFF directly; flip to `false` once
        SWA is wired so all browser traffic must traverse SWA. The change
        is a single Terraform diff and is reversible.
      - Note: Realm / client configuration is NOT managed by Terraform. Terraform
        provisions the Keycloak Container App (image + DB + admin creds); the
        Ansible `configure-keycloak.yml` playbook runs keycloak-config-cli
        against the live admin endpoint to apply `infra/keycloak/realm.yaml`
        (same file used in local-intg). See 02c3-ansible.md.
      - Output: bff_fqdn (external), business_service_internal_fqdn, keycloak_fqdn
    </step>
    <step order="7b">
      Still inside modules/container-apps/, declare two `azurerm_container_app_job`
      resources. They run Liquibase against the two application databases on
      demand — invoked by Ansible as a post-deploy step (see 02c3 step 5). The
      older design ran migrations in a throwaway Azure Container Instance; that
      path is retired because ACI lives outside the ACA Environment (separate
      VNet integration, separate MI wiring, separate observability), and Jobs
      give us the same image, the same identity, and the same private-network
      reachability as the apps that run in production.

      ```hcl
      # Shared bits: same environment, same ACR, same registry block pattern.
      locals {
        liquibase_jobs = {
          bff = {
            image            = "${var.acr_login_server}/bff:${var.bff_image_tag}"
            database_url     = var.jdbc_urls["bff_session"]
            database_user    = "bff"
            db_secret_name   = "bff-db-password"
            changelog        = "classpath:db/changelog/db.changelog-master.yaml"
          }
          business_service = {
            image            = "${var.acr_login_server}/business-service:${var.business_service_image_tag}"
            database_url     = var.jdbc_urls["boatapp"]
            database_user    = "business_service"
            db_secret_name   = "business-db-password"
            changelog        = "classpath:db/changelog/db.changelog-master.yaml"
          }
        }
      }

      resource "azurerm_container_app_job" "liquibase" {
        for_each                     = local.liquibase_jobs
        name                         = "liquibase-${replace(each.key, "_", "-")}"
        container_app_environment_id = azurerm_container_app_environment.this.id
        location                     = var.location
        resource_group_name          = var.resource_group_name

        identity { type = "SystemAssigned" }

        registry {
          server   = var.acr_login_server
          identity = "system"            # same MI-based pull as the long-running apps
        }

        # Manual trigger — Ansible calls `az containerapp job start` per deploy.
        replica_timeout_in_seconds = 600
        replica_retry_limit        = 0
        manual_trigger_config {
          parallelism              = 1
          replica_completion_count = 1
        }

        # Pull the per-DB password from Key Vault via the job's MI.
        # The MI is granted `Key Vault Secrets User` through
        # var.consumer_principal_ids[liquibase_<name>] — see module/keyvault.
        secret {
          name                = "database-password"
          key_vault_secret_id = var.keyvault_secret_ids[each.value.db_secret_name]
          identity            = "System"
        }

        template {
          container {
            name    = "liquibase"
            image   = each.value.image
            cpu     = 0.25
            memory  = "0.5Gi"

            # Run Spring Boot as a CLI: apply Liquibase and exit. The
            # application image already contains the changelogs on its
            # classpath, so no separate Liquibase CLI image is needed.
            command = ["java"]
            args = [
              "-jar", "/app/app.jar",
              "--spring.main.web-application-type=none",
              "--spring.liquibase.enabled=true",
              "--spring.liquibase.change-log=${each.value.changelog}",
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
      }
      ```

      Extra export (on top of the existing step-7 outputs):
      ```hcl
      output "liquibase_job_names" {
        value = { for k, v in azurerm_container_app_job.liquibase : k => v.name }
      }
      # consumer principal IDs now include the two Liquibase job MIs —
      # the container-registry module grants them AcrPull, the keyvault
      # module grants them Key Vault Secrets User.
      output "consumer_principal_ids" {
        value = merge(
          { for k, v in azurerm_container_app.apps  : k => v.identity[0].principal_id },
          { for k, v in azurerm_container_app_job.liquibase : "liquibase_${k}" => v.identity[0].principal_id },
        )
      }
      ```

      Design notes:
      - No `azurerm_container_group` (ACI) anywhere in the module tree.
      - Keycloak still manages its OWN schema at startup — no Liquibase Job
        for Keycloak, same as before.
      - Replica retry is zero: Liquibase is not idempotent against transient
        partial failures; let Ansible decide whether to re-invoke.
      - Timeout 10 min is generous for a POC schema; bump if the changelog
        grows. A timed-out job is a `Failed` execution, surfaced in step 5
        of 02c3.
    </step>
    <step order="7c">
      Create modules/static-web-app/ to host the Vue SPA in staging /
      production. Since the SCG migration the SPA is no longer baked into
      the BFF image — it lives on Azure Static Web Apps, which front-ends
      the BFF Container App via its Bring-Your-Own-Backend (linked-backend)
      mechanism. Cookies stay first-party because SWA serves both the SPA
      and the API surface from a single hostname.

      Resources:
      ```hcl
      resource "azurerm_static_web_app" "this" {
        name                = "${var.project_name}-${var.environment}-spa"
        resource_group_name = var.resource_group_name
        location            = var.location

        # Standard SKU is REQUIRED for the linked-backend feature consumed
        # below. Free SKU would force every browser API call through a
        # public BFF endpoint instead.
        sku_tier = "Standard"
        sku_size = "Standard"

        app_settings = var.app_settings
        tags         = var.tags
      }

      # Bring-Your-Own-Backend wiring — the SWA forwards requests matching
      # the routes[] in `frontend/staticwebapp.config.json` (/api/*,
      # /oauth2/*, /login/*, /logout, /.well-known/*, /actuator/*) to this
      # Container App.
      resource "azurerm_static_web_app_linked_backend" "bff" {
        static_web_app_id   = azurerm_static_web_app.this.id
        backend_resource_id = var.bff_container_app_id
        region              = var.location
      }
      ```

      Inputs (variables.tf):
      - project_name, environment, resource_group_name (standard set).
      - location: SWA-supported Azure region (defaults to `westeurope`).
        The linked-backend region MUST match.
      - bff_container_app_id: resource ID of the BFF Container App from
        module.container_apps (output `bff_container_app_id` — add it if
        not already exposed).
      - app_settings (optional map): SWA app settings. Empty by default —
        we have no SWA-managed Functions.
      - tags (optional map).

      Outputs (outputs.tf):
      - `static_web_app_id` — for downstream wiring.
      - `default_host_name` — the canonical browser entrypoint
        (`https://<this>/`).
      - `api_key` — deployment token consumed by `Azure/static-web-apps-deploy@v1`
        in CI. Sensitive (= true) — surface via GitHub environment secret
        `AZURE_SWA_DEPLOY_TOKEN`, never log.

      Companion file: `frontend/staticwebapp.config.json` (committed in
      02b5) declares the SPA fallback (`navigationFallback.rewrite =
      /index.html`) and the API + auth route exclusions. Vite's
      `npm run build` copies it into `dist/` automatically.

      Cutover note: until the SWA linked-backend is active, leave the BFF
      Container App's ingress `external_enabled = true` so smoke tests can
      hit the BFF directly. After SWA serves the SPA successfully, flip
      ingress to `external_enabled = false` so all browser traffic must
      traverse SWA.
    </step>
    <step order="8">
      Create root main.tf that wires all modules together:
      - Calls each module with appropriate variables
      - Passes outputs between modules (e.g., subnet IDs to container-apps)
      - Wiring order (relevant to ACR-without-admin):
          1. module.container_registry — needs `consumer_principal_ids` and
             `ci_push_principal_id`; until container_apps is applied for the
             first time, `consumer_principal_ids` is empty. Solve with
             `depends_on = [module.container_apps]` on the role assignments
             only (the registry itself has no dependency), so `terraform
             apply` converges in a single run: apps come up pulling
             anonymously-denied, the role assignments land, next reconcile
             succeeds. OR declare the apps with `ignore_changes = [template[0].container[0].image]`,
             apply once with a placeholder image, then apply again with the
             real image. The former is cleaner.
          2. module.container_apps — consumes `module.container_registry.login_server`
             and `.id`; emits `consumer_principal_ids` (a map from logical
             name → each Container App's `identity[0].principal_id`).
      - Tags all resources: project=boat-app, environment=var.environment, managed-by=terraform
    </step>
    <step order="9">
      Create variables.tf with all input variables:
      - environment (staging/production)
      - location (default: switzerlandnorth — closest Azure region to Geneva)
      - project_name
      - postgres_admin_username, postgres_admin_password (sensitive)
        Server-level Flexible Server administrator_login — provisioning only.
      - bff_db_password, business_db_password, keycloak_db_password (sensitive)
        Passwords for the three per-DB application roles created by Ansible.
      - keycloak_admin_password (sensitive)
      # NO keycloak_client_secret — boat-app-confidential authenticates via
      # private_key_jwt; the keypair is generated by `tls_private_key` inside
      # this module and stored as the bff-signing-key Key Vault secret.
      - bff_signing_key_id (default "bff-key-1") — the kid baked into the JWK
      - ci_push_principal_id (string) — object ID of the Entra ID application
        that GitHub Actions federates into via OIDC. Granted `AcrPush` on the
        ACR so CI can push images without any ACR admin credential. Sourced
        from `az ad app show --id <AZURE_CLIENT_ID>` after the federated
        identity credential is created (out-of-band bootstrap — document in
        `infra/terraform/README.md`). NO `acr_admin_username` /
        `acr_admin_password` variables exist.
      - tf_apply_principal_id (string) — object ID of the identity that runs
        `terraform apply` from CI. Typically the SAME federated identity as
        `ci_push_principal_id` (one Entra ID app per pipeline). Granted
        `Key Vault Secrets Officer` on the vault so `terraform apply` can
        write secret material (`tls_private_key` → `azurerm_key_vault_secret`).
        Apps consume secrets via the `Key Vault Secrets User` role (read-only)
        — distinct role, distinct principal. NO `azure_client_secret`
        variable exists (OIDC federation replaces it).
      - Image tags (one per Container App pulling from ACR):
        - bff_image_tag
        - business_service_image_tag
        - keycloak_image_tag (usually pinned to the upstream keycloak version)
    </step>
    <step order="10">
      Create environments/staging/main.tf:
      - Reference root module with staging-specific values
      - Smaller SKUs for cost optimization
      - backend.tf with remote state in Azure Blob (staging container)
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it runs fmt -check + validate
    (+tflint if installed), confirms the 5 required modules, both environments,
    no secret literals, remote azurerm backend, Switzerland North region,
    and no keycloak_client_secret variable:
    ```bash
    ai-scripts/checks/02c2/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02c2/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "infra: Terraform modules for Azure deployment

    - Modules: networking, database, container-registry, container-apps, keyvault
    - Azure Container Apps: bff (external ingress, Spring Cloud Gateway — API + auth only; SPA is on Azure Static Web Apps with linked-backend → BFF),
      business-service (internal ingress only, JWT resource server),
      keycloak (external ingress)
    - PostgreSQL Flexible Server (1 instance, 3 isolated databases: bff_session,
      boatapp, keycloak — per-DB roles bootstrapped by Ansible, zero cross-DB access)
      with VNet integration
    - Key Vault for secrets (postgres admin, 3 per-DB app passwords, keycloak admin,
      BFF signing key PEM — no shared keycloak client secret; auth uses private_key_jwt).
      Authorization via Azure RBAC (no access policies). public_network_access=false,
      network_acls default Deny (bypass AzureServices), private endpoint in the
      keyvault subnet, privatelink.vaultcore.azure.net private DNS zone.
      Role assignments: Key Vault Secrets User to each consumer MI;
      Key Vault Secrets Officer to the CI federated identity.
    - ACR: admin_enabled=false. Pulls via each Container App's system-assigned MI
      (AcrPull role). Pushes from CI via Entra ID OIDC federation (AcrPush role).
      Zero long-lived registry credentials anywhere.
    - Environment configs: staging and production
    - Remote state in Azure Blob Storage
    - Region: Switzerland North"
    ```
  </commit>
</task>
