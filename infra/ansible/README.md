# Ansible — Boat App Azure configuration management

Ansible handles the application-level configuration that Terraform deliberately
doesn't: per-DB role bootstrap on the PostgreSQL Flexible Server, image build
and push to ACR, Container App revision updates, Liquibase changelog application
via ACA Jobs, idempotent Keycloak realm import, post-deploy health verification,
and rollback to the previous revision.

Terraform (under `infra/terraform/`) provisions everything immutable; Ansible
fills in the day-1 and day-2 reconciliation.

---

## Prerequisites

### 1. Runner network reachability

Every playbook here connects to Key Vault and the PostgreSQL Flexible Server.
Both are private — Key Vault has `public_network_access_enabled = false` with
deny-by-default ACLs, the Flexible Server is VNet-integrated with no public
endpoint. The runner therefore **must be reachable inside the target VNet**.

Two supported runner topologies:

* **Self-hosted runner inside the VNet (preferred).** A small VM scale set
  or a dedicated Container Apps Job in the `container-apps` subnet. The
  runner's managed identity is granted `Key Vault Secrets User` on the
  vault, so all secret reads happen over the private endpoint and never
  leave the VNet boundary. Terraform wires the grant via
  `additional_kv_consumer_principal_ids = { ansible_runner = "<MI principal id>" }`
  in the per-env tfvars.

* **GitHub-hosted runner + AzureServices bypass (POC only).** Authenticates
  via `azure/login@v2` OIDC; control-plane reads succeed because the vault's
  network ACL has `bypass = "AzureServices"`. Acceptable for a POC; document
  the trade-off (control-plane reads cross the Azure boundary) and migrate
  to a self-hosted runner before going to production.

The pre-flight task in `playbooks/deploy.yml` resolves the PostgreSQL private
FQDN with `getent hosts` — public DNS doesn't return the 10.x address, so a
successful resolve is the canonical signal that the runner is in-VNet.

**Runner type contract.** The deploy playbook relies on:
* A reachable Docker daemon (`docker info` is run as a pre-flight) — needed
  by `roles/app-config/tasks/build-and-push.yml` and
  `playbooks/configure-keycloak.yml`. A bare Container-Apps-Job-based runner
  does NOT have Docker; pick a VM-scale-set or VM runner with Docker
  installed and the runner user in the `docker` group.
* `az` CLI logged in via OIDC federation. The `azure_rm_keyvaultsecret_info`
  module is configured with `auth_source: cli`, so it consumes the same
  context.

### 2. Azure CLI authentication

The runner must be logged in via OIDC federation **before** invoking any
playbook. No client secrets are stored on disk.

```bash
# CI:
az login --service-principal -u "$AZURE_CLIENT_ID" -t "$AZURE_TENANT_ID" \
         --federated-token "$AZURE_FEDERATED_TOKEN"

# Local dev (interactive):
az login
```

`playbooks/deploy.yml`'s pre-flight verifies the session with `az account show`.

### 3. Galaxy collections

```bash
ansible-galaxy collection install -r requirements.yml
```

### 4. Vault secrets

Copy and encrypt the secrets template (one-time per env):

```bash
cp vault/secrets.yml.example vault/secrets.yml
# Fill in vault_bff_db_password, vault_business_db_password, vault_keycloak_db_password.
ansible-vault encrypt vault/secrets.yml
```

Application-runtime secrets (postgres-admin-password, bff-db-password,
business-db-password, keycloak-db-password, keycloak-admin-password,
bff-signing-key) live in Azure Key Vault and are read at runtime by
`roles/app-config/tasks/read-secrets.yml` — they are NOT in `vault/secrets.yml`.

### 5. Terraform outputs

The playbooks load every infrastructure value from `terraform output -json`
against `infra/terraform/`. Run `terraform apply` first; the playbook fails
fast if any required output is missing.

---

## Invocation

```bash
# Full deploy to staging:
IMAGE_TAG=$(git rev-parse --short HEAD) \
  ansible-playbook -i inventory/staging.yml playbooks/deploy.yml \
                   --vault-password-file ~/.config/boatapp-vault-pw

# Incremental — promote just an image (skip bootstrap, skip migrations):
ansible-playbook -i inventory/staging.yml playbooks/deploy.yml \
                 --tags "build,push,deploy,verify"

# Bootstrap only (one-time per env after the first `terraform apply`):
ansible-playbook -i inventory/staging.yml playbooks/bootstrap-db-roles.yml

# Rollback (interactive, requires typing "rollback"):
ansible-playbook -i inventory/production.yml playbooks/rollback.yml
```

Tags supported by `deploy.yml`: `bootstrap`, `build`, `push`, `deploy`,
`migrate`, `verify`. Special tag `always` runs the pre-flight regardless of
the selection.

---

## Known drift to fix later

`bff/src/main/resources/application-prod.yml` reads database connection
details from `${POSTGRES_FQDN}` + `${BFF_DB_NAME}` + `${BFF_DB_USER}` +
`${BFF_DB_PASSWORD}`, while Terraform's Container App env block at
`infra/terraform/modules/container-apps/main.tf:129-164` declares
`DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD`. These are
parallel naming schemes for the same connection.

`roles/app-config/tasks/update-{bff,business}.yml` currently sets BOTH
sets so the deploy works end-to-end without touching either side. When the
drift is resolved (either Terraform migrates to `POSTGRES_FQDN`/`*_DB_*` or
Spring's `application-prod.yml` migrates to `DATABASE_URL`), drop the
duplicate half from those task files.

---

## What this layer is explicitly NOT

* Not an ACI runner. Migrations execute as `azurerm_container_app_job`
  resources declared by Terraform; Ansible only invokes
  `az containerapp job start`. No `az container create` calls anywhere.
* Not a runner-public-IP scheme. There is no `runner_public_ip` variable
  and no `az postgres flexible-server firewall-rule create` task — the
  deny-by-default network posture is identical for Key Vault and PostgreSQL.
* Not the source of truth for env-specific URLs and resource names. Those
  live in `terraform output -json` and are loaded as facts at runtime.
