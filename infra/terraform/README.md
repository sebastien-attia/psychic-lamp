# Boat App — Azure Terraform

Greenfield Terraform that provisions the Boat App stack on Azure for two
environments (`staging`, `production`):

| Component | Resource |
|-----------|----------|
| Database | PostgreSQL Flexible Server v17 (B_Standard_B1ms), public + firewall |
| Registry | Azure Container Registry (Basic, **admin disabled**) |
| Compute  | Linux App Service Plan + 3 Web Apps for Containers (bff, business-service, keycloak) |
| Secrets  | Azure Key Vault (RBAC, public endpoint, KV references resolved by App Service MI) |
| DB roles | `null_resource` + `local-exec psql` creates per-DB roles + grants on apply |

There are **no long-lived credentials anywhere**:

- ACR pulls happen via each Web App's system-assigned managed identity
  (`AcrPull` role on the registry).
- ACR pushes happen from CI via Entra ID OIDC federation (`AcrPush` role).
- Apps read Key Vault secrets via `@Microsoft.KeyVault(SecretUri=…)`
  references in `app_settings`; App Service resolves them with the Web
  App's MI (`Key Vault Secrets User` role on the vault).
- BFF ↔ Keycloak auth uses RFC 7523 `private_key_jwt`. The signing
  keypair is generated in-module by `tls_private_key` and stored as the
  `bff-signing-key` secret. **There is no shared `keycloak_client_secret`
  variable.**

## Prerequisites

The following must exist **before** `terraform apply` for the first time:

1. **Azure subscription** with permission to create resource groups,
   Key Vaults, App Service plans, ACR, Flexible Servers, and Entra ID
   role assignments.
2. **Terraform state storage account.** A storage account + container
   referenced by the per-environment `backend.tf`. Recommended layout:
   ```
   resource_group_name  = "boatapp-tfstate-rg"
   storage_account_name = "boatapptfstate"
   container_name       = "tfstate"
   key                  = "<environment>.tfstate"
   ```
3. **Entra ID app registration with federated credentials.** One Entra ID
   application that GitHub Actions logs into via OIDC. Capture its
   service principal object ID:
   ```bash
   az ad app create --display-name "boatapp-cicd"
   APP_ID=$(az ad app list --display-name boatapp-cicd --query '[0].appId' -o tsv)
   az ad sp create --id "${APP_ID}"
   SP_OBJECT_ID=$(az ad sp show --id "${APP_ID}" --query id -o tsv)
   ```
   Then grant the SP `Contributor` on the subscription. `SP_OBJECT_ID`
   goes into both `ci_push_principal_id` and `tf_apply_principal_id`.

4. **psql client on the Terraform runner.** The `db-bootstrap` module
   runs `psql` via `local-exec` to create the per-DB application roles.
   GitHub-hosted runners have it pre-installed; self-hosted runners
   need `apt-get install -y postgresql-client`.

## Layout

```
infra/terraform/
├── providers.tf                  # azurerm + null + tls (backend "azurerm" partial)
├── variables.tf                  # all root inputs (see comments per-var)
├── outputs.tf                    # bff_fqdn, keycloak_fqdn, …
├── main.tf                       # wires the 5 child modules
├── terraform.tfvars.example      # copy + fill in
├── modules/
│   ├── database/                 # Flexible Server + 3 DBs + firewall rules
│   ├── container-registry/       # ACR + AcrPush role assignment
│   ├── app-service/              # Service Plan + 3 Web Apps + AcrPull / KV User RBAC
│   ├── keyvault/                 # KV (public RBAC) + secrets + Officer role
│   └── db-bootstrap/             # null_resource + local-exec psql for roles/grants
└── environments/
    ├── staging/                  # backend.tf + main.tf
    └── production/
```

## Plan & apply

```bash
cd infra/terraform/environments/staging

terraform init                 # configures the azurerm backend
terraform plan -out staging.plan
terraform apply staging.plan
```

Sensitive values (`*_password`, `*_principal_id`) must be supplied via
environment variables — never committed to `terraform.tfvars`:

```bash
export TF_VAR_postgres_admin_password='…'
export TF_VAR_bff_db_password='…'
export TF_VAR_business_db_password='…'
export TF_VAR_keycloak_db_password='…'
export TF_VAR_keycloak_admin_password='…'
export TF_VAR_ci_push_principal_id='<sp-object-id>'
export TF_VAR_tf_apply_principal_id='<sp-object-id>'
```

## Database firewall

The Flexible Server is publicly addressable; access is gated by per-IP
firewall rules. The module accepts two inputs:

- `app_service_outbound_ips` — wired automatically from the App Service
  Plan's outbound IP list (one rule per IP).
- `additional_firewall_ips` — pass `{ "runner" = "<runner-egress-ip>" }`
  from CI for the duration of `terraform apply` so the `db-bootstrap`
  step can reach the server. The deploy workflow removes the rule at the
  end of the job.

## First-apply caveat (RBAC propagation)

`AcrPull` (registry) and `Key Vault Secrets User` (vault) are granted to
each Web App's system-assigned MI *after* the app itself is created.
Both RBAC writes complete in the same `terraform apply`, but Entra ID
role propagation is eventually consistent: image pulls and KV reference
resolution can fail for 30–90 s on the first apply before recovering
automatically. No second `terraform apply` is required.

## State file is sensitive

The state file contains the `bff-signing-key` PEM and the password
material flowing through Key Vault writes. The Azure Blob backend must
enforce HTTPS, RBAC-restricted access, and at-rest encryption.
