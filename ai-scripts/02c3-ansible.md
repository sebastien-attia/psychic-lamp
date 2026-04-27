<!--
  SUPERSEDED 2026-04-27: Ansible was removed from the deploy. The Keycloak
  realm is now applied directly by the `keycloak-config` job in
  .github/workflows/deploy-{staging,production}.yml. The `demo` seed user
  IS imported in every environment (overriding the local-intg-only stance
  described below) — see infra/keycloak/realm.users.demo.yaml SECURITY
  NOTE for the rationale.
-->
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

  <role>You are a senior DevOps engineer writing Ansible playbooks for application configuration management.</role>

  <context>
    <project>The Boat App — Ansible configuration management for Azure</project>
    <infrastructure>Azure Container Apps provisioned by Terraform (from Step 2C.2)</infrastructure>
    <purpose>Ansible handles application-level configuration that Terraform doesn't: env var injection, SSL setup, database migrations, Keycloak realm config, health verification.</purpose>
    <scope>only modify files under infra/ansible/</scope>
  </context>

  <instructions>
    <step order="1">
      Create Ansible project structure:
      ```
      infra/ansible/
      ├── ansible.cfg              # Ansible configuration
      ├── requirements.yml         # Galaxy collections (azure.azcollection, community.postgresql, community.docker)
      ├── inventory/
      │   ├── staging.yml          # Staging inventory
      │   └── production.yml       # Production inventory
      ├── group_vars/
      │   ├── all.yml              # Shared variables
      │   ├── staging.yml          # Staging-specific vars
      │   └── production.yml       # Production-specific vars
      ├── playbooks/
      │   ├── deploy.yml               # Full deployment playbook
      │   ├── bootstrap-db-roles.yml   # Create per-DB roles + ownership (one-time per env)
      │   ├── configure-keycloak.yml   # Keycloak realm setup
      │   ├── run-migrations.yml       # Database migrations (BFF + Business Service, two runs)
      │   ├── health-check.yml         # Post-deploy verification
      │   └── rollback.yml             # Rollback to previous version
      ├── roles/
      │   ├── app-config/          # Application configuration
      │   ├── ssl-setup/           # SSL/TLS certificates
      │   └── monitoring/          # Monitoring setup
      └── vault/
          └── secrets.yml.example  # Ansible Vault example
      ```
    </step>
    <step order="2">
      Create playbooks/deploy.yml (main deployment playbook):
      - Pre-tasks: verify Azure CLI is authenticated via OIDC federation
        (`az account show` succeeds without a stored service-principal secret),
        verify Terraform outputs exist (incl. `liquibase_job_names`).
      - Pre-flight: assert the runner is inside the target VNet (ping the
        Flexible Server's private FQDN, 1-second timeout). Fail loudly if not —
        bootstrap-db-roles and run-migrations both require in-VNet reachability.
      - Task: import bootstrap-db-roles.yml (creates the 3 per-DB roles + ownership
        on the Flexible Server — idempotent, safe to re-run; see step 3b).
      - Role: app-config → inject environment variables into Container Apps
      - Task: build and push Docker images to ACR (via `az acr login`, no admin creds):
        - bff image (3-stage build: Node frontend → JDK BFF → JRE runtime)
        - business-service image (2-stage build: JDK → JRE runtime)
      - Task: update Container App revisions with new image tags (bff + business-service)
      - Task: invoke the Liquibase ACA Jobs (import run-migrations.yml — starts
        `azurerm_container_app_job.liquibase["bff"]` and `.["business_service"]`
        via `az containerapp job start`, polls `job execution show` to terminal).
      - Task: configure Keycloak realm (if first deploy)
      - Post-tasks: run health checks, notify on success/failure
      Orchestration order: bootstrap-db-roles → app-config → build/push → update
      Container Apps → run-migrations (ACA Jobs) → configure-keycloak → health-check.
      Tags: [bootstrap, build, push, deploy, migrate, verify] for selective execution.
      Explicitly NOT used: `az container create` (ACI), any `runner_public_ip`
      variable, any `az postgres flexible-server firewall-rule create` task.
    </step>
    <step order="3">
      Create roles/app-config/:
      - tasks/main.yml:
        - Read secrets from Azure Key Vault (postgres-admin-password,
          bff-db-password, business-db-password, keycloak-db-password,
          keycloak-admin-password, bff-signing-key PEM).

          The vault has `public_network_access_enabled = false` and a deny-by-default
          network ACL (see 02c2 step 6), so the Ansible runner MUST be reachable
          over the private endpoint in the `keyvault` subnet. Two supported paths:

          * Preferred — **self-hosted runner inside the VNet** (e.g. a small
            Container Apps Job or a dedicated VM in the `container-apps` subnet).
            The runner's managed identity is granted `Key Vault Secrets User`
            on the vault (declared in Terraform via `consumer_principal_ids`,
            key `ansible_runner`). Secret reads happen over the private endpoint;
            no public traffic to *.vault.azure.net at all.

          * Acceptable for a POC — **GitHub-hosted runner + AzureServices bypass**.
            The `bypass = "AzureServices"` on the vault's network_acls lets the
            runner's `az keyvault secret show` calls succeed through the Azure
            control plane as long as it authenticates via `azure/login@v2` OIDC.
            The CI federated identity needs `Key Vault Secrets User` on the vault.
            Document the trade-off: control-plane reads leave the VNet boundary.

          Use `azure_rm_keyvaultsecret_info` (azure.azcollection) with
          `auth_source: auto` so the module picks up the OIDC-federated az
          context — do NOT pass `client_id` / `client_secret` arguments.
        - NO keycloak-client-secret is read or rendered — the BFF authenticates to
          Keycloak via private_key_jwt; the signing key PEM is mounted into the
          bff Container App as a secret-volume at /mnt/secrets/bff-signing-key.pem
          (configured in Terraform — Ansible only sets the env vars that point at it).
        - Set Container App environment variables per service (one DB/role per service):
          - bff Container App (DB = bff_session, role = bff):
              SPRING_PROFILES_ACTIVE, BUSINESS_SERVICE_URL,
              DATABASE_URL (= jdbc_urls["bff_session"]),
              DATABASE_USERNAME (= "bff"),
              DATABASE_PASSWORD (= bff-db-password from KV),
              KEYCLOAK_ISSUER_URI, KEYCLOAK_CLIENT_ID,
              BFF_SIGNING_KEY_PATH (=/mnt/secrets/bff-signing-key.pem),
              BFF_SIGNING_KEY_ID (default "bff-key-1")
          - business-service Container App (DB = boatapp, role = business_service):
              SPRING_PROFILES_ACTIVE,
              DATABASE_URL (= jdbc_urls["boatapp"]),
              DATABASE_USERNAME (= "business_service"),
              DATABASE_PASSWORD (= business-db-password from KV),
              KEYCLOAK_ISSUER_URI
          - keycloak Container App (DB = keycloak, role = keycloak):
              KC_DB (= "postgres"),
              KC_DB_URL (= jdbc_urls["keycloak"]),
              KC_DB_USERNAME (= "keycloak"),
              KC_DB_PASSWORD (= keycloak-db-password from KV),
              KC_HOSTNAME, KC_PROXY_HEADERS, KC_HTTP_ENABLED,
              KC_HEALTH_ENABLED, KC_METRICS_ENABLED,
              KEYCLOAK_ADMIN, KEYCLOAK_ADMIN_PASSWORD
        - Configure custom domain and SSL on the bff app if applicable
          (business-service has internal ingress only; no public domain)
      - templates/:
        - env.j2 → environment variable template (one block per service).
          NOTE: No VITE_* variables — the Vue SPA is baked into the BFF image
          at bff/src/main/resources/static/ and served by Spring, so there is
          no runtime frontend container to configure.
      - defaults/main.yml → default values
    </step>
    <step order="3b">
      Create playbooks/bootstrap-db-roles.yml.

      Purpose: once per environment, connect to the Azure PostgreSQL Flexible Server
      as the server-level administrator and create the three per-DB application roles
      (bff, business_service, keycloak). Transfer ownership of each database to its
      role and revoke PUBLIC CONNECT so roles cannot reach each other's databases.

      Idempotent — `community.postgresql.postgresql_user/_owner/_privs` modules
      detect current state and only change what differs. Safe to re-run.

      **Network reachability — runner MUST be inside the VNet.**
      The Flexible Server is VNet-integrated with private access only; there is
      no public endpoint. The transient `az postgres flexible-server firewall-rule
      create` fallback used in earlier drafts is explicitly rejected — every
      open-then-close window is a detection-evasion surface and contradicts
      the deny-by-default posture we now apply to Key Vault (02c2 step 6).

      Supported runners:
      * A self-hosted GitHub runner deployed in the `container-apps` subnet
        (small VM scale set or a dedicated Container App — document the choice
        in `infra/ansible/README.md`).
      * An Azure Container Apps Job that invokes this playbook (same ACA
        Environment as the application jobs, same MI pattern).

      The runner's managed identity is granted `Key Vault Secrets User` on the
      vault (via `consumer_principal_ids["ansible_runner"]` — 02c2 step 6) and
      reads `postgres-admin-password` over the Key Vault private endpoint
      before opening the PG connection. No public IP, no transient firewall
      rule, no `runner_public_ip` variable.

      Playbook structure:
      ```yaml
      - name: Bootstrap per-DB roles on Azure PostgreSQL Flexible Server
        hosts: localhost
        connection: local
        gather_facts: false
        vars:
          pg_host:           "{{ terraform_outputs.postgres_fqdn }}"
          pg_admin_user:     "{{ vault_postgres_admin_user }}"
          pg_admin_password: "{{ vault_postgres_admin_password }}"
        tasks:
          - name: Create per-DB application roles
            community.postgresql.postgresql_user:
              login_host:     "{{ pg_host }}"
              login_user:     "{{ pg_admin_user }}"
              login_password: "{{ pg_admin_password }}"
              db: postgres
              name:     "{{ item.name }}"
              password: "{{ item.password }}"
              role_attr_flags: LOGIN
            loop:
              - { name: bff,              password: "{{ vault_bff_db_password }}" }
              - { name: business_service, password: "{{ vault_business_db_password }}" }
              - { name: keycloak,         password: "{{ vault_keycloak_db_password }}" }
            no_log: true

          - name: Transfer database ownership to per-service roles
            community.postgresql.postgresql_owner:
              login_host:     "{{ pg_host }}"
              login_user:     "{{ pg_admin_user }}"
              login_password: "{{ pg_admin_password }}"
              db: postgres
              new_owner: "{{ item.owner }}"
              obj_name:  "{{ item.db }}"
              obj_type:  database
            loop:
              - { db: bff_session, owner: bff }
              - { db: boatapp,     owner: business_service }
              - { db: keycloak,    owner: keycloak }

          - name: Revoke PUBLIC CONNECT on each application database
            community.postgresql.postgresql_privs:
              login_host:     "{{ pg_host }}"
              login_user:     "{{ pg_admin_user }}"
              login_password: "{{ pg_admin_password }}"
              db: "{{ item }}"
              type: database
              privs: CONNECT
              roles: PUBLIC
              state: absent
            loop: [bff_session, boatapp, keycloak]

          - name: Grant CONNECT to each database's owner role
            community.postgresql.postgresql_privs:
              login_host:     "{{ pg_host }}"
              login_user:     "{{ pg_admin_user }}"
              login_password: "{{ pg_admin_password }}"
              db: "{{ item.db }}"
              type: database
              privs: CONNECT
              roles: "{{ item.role }}"
            loop:
              - { db: bff_session, role: bff }
              - { db: boatapp,     role: business_service }
              - { db: keycloak,    role: keycloak }
      ```

      Dependencies: add `community.postgresql` (and `community.docker` for
      configure-keycloak) to `infra/ansible/requirements.yml`:
      ```yaml
      collections:
        - name: azure.azcollection
        - name: community.postgresql
        - name: community.docker
      ```
    </step>
    <step order="4">
      Create playbooks/configure-keycloak.yml.

      Design: delegate realm/client/user reconciliation to
      **adorsys/keycloak-config-cli** (run as a one-shot Docker task), consuming
      the same source-of-truth YAML used by local-intg (`infra/keycloak/realm.yaml`,
      authored in 02c1-docker.md step 0). This removes the drift that used to exist
      between local-intg's JSON import and staging/prod's `community.general.keycloak_*`
      tasks, and keeps us decoupled from Ansible-collection lag behind new Keycloak
      features.

      Playbook structure:
      ```yaml
      - name: Apply Keycloak realm config (same YAML for all envs)
        hosts: localhost
        connection: local
        gather_facts: false
        vars:
          keycloak_admin_password: "{{ vault_keycloak_admin_password }}"
          demo_password: "{{ vault_demo_password | default('') }}"
          bff_base_url: "{{ 'https://' ~ bff_public_fqdn }}"
          bff_jwks_url: "{{ 'https://' ~ bff_internal_fqdn ~ '/.well-known/jwks.json' }}"
        tasks:
          - name: Wait for Keycloak /health/ready
            ansible.builtin.uri:
              url: "https://{{ keycloak_fqdn }}/health/ready"
              status_code: 200
            register: kc_ready
            until: kc_ready.status == 200
            retries: 30
            delay: 5

          - name: Apply realm.yaml via keycloak-config-cli (idempotent)
            community.docker.docker_container:
              name: "keycloak-config-cli-{{ ansible_date_time.epoch }}"
              image: docker.io/adorsys/keycloak-config-cli:latest-26.6.1
              cleanup: true
              detach: false
              volumes:
                - "{{ playbook_dir }}/../keycloak:/config:ro"
              env:
                KEYCLOAK_URL: "https://{{ keycloak_fqdn }}"
                KEYCLOAK_USER: "{{ vault_keycloak_admin_user | default('admin') }}"
                KEYCLOAK_PASSWORD: "{{ keycloak_admin_password }}"
                KEYCLOAK_AVAILABILITYCHECK_ENABLED: "true"
                KEYCLOAK_AVAILABILITYCHECK_TIMEOUT: "120s"
                IMPORT_FILES_LOCATIONS: "/config/realm.yaml"
                IMPORT_VAR_SUBSTITUTION_ENABLED: "true"
                # Variables referenced by realm.yaml
                BFF_BASE_URL: "{{ bff_base_url }}"
                BFF_JWKS_URL: "{{ bff_jwks_url }}"
                DEMO_PASSWORD: "{{ demo_password }}"
            register: kc_config_result

          - name: Fail if config-cli exited non-zero
            ansible.builtin.fail:
              msg: "keycloak-config-cli failed: {{ kc_config_result }}"
            when: kc_config_result.container.State.ExitCode != 0
      ```

      Notes:
      - Same YAML drives local-intg (compose sidecar) and staging/prod (this task).
      - Idempotent: config-cli does create-or-update and detects drift.
      - No `community.general.keycloak_*` modules anywhere — they lag server
        releases and duplicate the declarative intent.
      - `DEMO_PASSWORD` is sourced from the Ansible vault (staging) and omitted
        in prod (realm.yaml's demo user can be conditionally elided per-env by
        committing a prod-specific overlay YAML if needed; default is: same
        realm.yaml everywhere, `demo` user disabled in `production.env`).
      - `infra/keycloak/env/staging.env` and `production.env` (authored in
        02c1-docker.md step 0) hold the URL values this task resolves via
        `{{ bff_public_fqdn }}` / `{{ keycloak_fqdn }}` — Terraform outputs from
        02c2-terraform.md.
    </step>
    <step order="5">
      Create playbooks/run-migrations.yml.

      Purpose: run Liquibase TWICE, once per application-owned database:
        1. BFF changelog (`classpath:db/changelog/db.changelog-master.yaml` in
           the bff image) against `jdbc_urls["bff_session"]`, as role `bff`.
        2. Business Service changelog (`classpath:db/changelog/db.changelog-master.yaml`
           in the business-service image) against `jdbc_urls["boatapp"]`, as
           role `business_service`.

      Execution model: **Azure Container Apps Jobs** (`azurerm_container_app_job`)
      declared in Terraform — see 02c2 step 7b. The jobs live in the same ACA
      Environment as the long-running apps, so they share: VNet integration,
      private-endpoint reachability to Key Vault and PostgreSQL, system-assigned
      managed identity, and `AcrPull` on the ACR. No ACI, no Kubernetes Job,
      no standalone Liquibase container, no password handling in Ansible.

      Keycloak is NOT migrated here; it manages its own schema at startup.

      Playbook structure:
      ```yaml
      - name: Apply Liquibase changelogs via ACA Jobs
        hosts: localhost
        connection: local
        gather_facts: false
        vars:
          jobs:
            - { key: bff,              name: "{{ terraform_outputs.liquibase_job_names.bff }}" }
            - { key: business_service, name: "{{ terraform_outputs.liquibase_job_names.business_service }}" }
        tasks:
          - name: Start Liquibase job ({{ item.key }})
            ansible.builtin.command:
              cmd: >-
                az containerapp job start
                  --name {{ item.name }}
                  --resource-group {{ resource_group }}
                  --output json
            register: job_start
            loop: "{{ jobs }}"

          - name: Poll job execution until terminal state
            ansible.builtin.command:
              cmd: >-
                az containerapp job execution show
                  --name {{ item.item.name }}
                  --resource-group {{ resource_group }}
                  --job-execution-name {{ (item.stdout | from_json).name }}
                  --output json
            register: job_exec
            until: (job_exec.stdout | from_json).properties.status in ['Succeeded','Failed','Degraded']
            retries: 60
            delay: 10
            loop: "{{ job_start.results }}"
            loop_control:
              label: "{{ item.item.key }}"

          - name: Fail deploy if any Liquibase job did not Succeed
            ansible.builtin.fail:
              msg: >-
                Liquibase job {{ item.item.item.key }} ended in state
                {{ (item.stdout | from_json).properties.status }} —
                see ACA Environment logs for details.
            when: (item.stdout | from_json).properties.status != 'Succeeded'
            loop: "{{ job_exec.results }}"
            loop_control:
              label: "{{ item.item.item.key }}"
      ```

      Notes:
      - The `az containerapp job start` call returns the execution name on
        stdout (`properties.name`); we capture it and poll until
        `properties.status` leaves the in-progress set.
      - Passwords never appear in Ansible — the job's MI pulls the Key Vault
        secret directly (declared in Terraform step 7b).
      - Rollback support: rely on Liquibase's own `DATABASECHANGELOG` table
        for checksums. For a POC, record the current HEAD checksum pre-apply
        (`liquibase history` via a debug job) and surface it in the deploy
        summary; the rollback playbook replays `liquibase rollback` against
        the recorded tag, again as an ACA Job invocation.
    </step>
    <step order="6">
      Create playbooks/health-check.yml:
      - Check bff (external URL): GET /actuator/health → status UP, GET / → HTTP 200 (SPA index)
      - Check business-service (from within Container Apps env, or via bff passthrough if exposed): /actuator/health → status UP
      - Check Keycloak: GET /health/ready → HTTP 200
      - Check database connectivity (via business-service actuator)
      - Retry with exponential backoff (max 5 attempts, 10s intervals)
      - Report results in summary
    </step>
    <step order="7">
      Create playbooks/rollback.yml:
      - Revert Container App to previous revision
      - Optionally rollback database migration
      - Run health checks after rollback
    </step>
    <step order="8">
      Create vault/secrets.yml.example:
      - Document all required secrets
      - Instructions for encrypting with ansible-vault
      - Example: ansible-vault encrypt vault/secrets.yml
    </step>
  </instructions>

  <verification>
    Run the phase's verification script — it runs `ansible-playbook --syntax-check`
    on every playbook (and ansible-lint if installed), confirms required
    playbooks exist, configure-keycloak sets `use.jwks.url` (private_key_jwt),
    and vault has no plaintext secrets:
    ```bash
    ai-scripts/checks/02c3/run.sh .
    ```
    All `fail` items must be green before `<commit>`.
    Human-only checks live in `ai-scripts/checks/02c3/human.md`.
  </verification>

  <commit>
    ```bash
    git add -A
    git commit -m "infra: Ansible playbooks for Azure configuration management

    - deploy.yml: full deployment pipeline
    - bootstrap-db-roles.yml: per-DB roles + ownership on PG Flexible Server (one-time per env)
    - configure-keycloak.yml: idempotent realm setup
    - run-migrations.yml: Liquibase database migrations (BFF → bff_session, Business Service → boatapp)
    - health-check.yml: post-deploy verification
    - rollback.yml: revert to previous version
    - Roles: app-config, ssl-setup, monitoring
    - Ansible Vault for secrets management
    - Inventory for staging and production
    - Key Vault reads over the private endpoint (runner inside VNet) or via
      AzureServices bypass with OIDC-federated az context — no client secrets"
    ```
  </commit>
</task>
