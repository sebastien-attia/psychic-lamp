# Boat App — Azure Terraform

Greenfield Terraform that provisions the full Boat App stack on Azure for
two environments (`staging`, `production`):

| Component | Resource |
|-----------|----------|
| Network | VNet (10.0.0.0/16) + 3 subnets + 2 private DNS zones |
| Database | PostgreSQL Flexible Server v17 (B_Standard_B1ms) + 3 logical DBs |
| Registry | Azure Container Registry (Basic, **admin disabled**) |
| Compute | Azure Container Apps Environment + 3 apps + 2 Liquibase Jobs |
| Secrets | Azure Key Vault (RBAC, **public access disabled**, private endpoint) |

There are **no long-lived credentials anywhere**:

- ACR pulls happen via each Container App's system-assigned managed
  identity (`AcrPull` role).
- ACR pushes happen from CI via Entra ID OIDC federation (`AcrPush` role).
- Apps read Key Vault secrets via their managed identity (`Key Vault
  Secrets User` role) over the vault's private endpoint.
- BFF ↔ Keycloak auth uses RFC 7523 `private_key_jwt`. The signing
  keypair is generated in-module by `tls_private_key` and stored as the
  `bff-signing-key` secret. **There is no shared `keycloak_client_secret`
  variable.**

## Prerequisites

The following must exist **before** you run `terraform apply` for the
first time. None of these are managed by this repo — they are out-of-band
bootstrap that lands in `ai-scripts/00d-bootstrap-azure.sh` (phase 04).

1. **Azure subscription** with permission to create resource groups,
   Key Vaults, Container Apps, ACR, Flexible Servers, and Entra ID role
   assignments.
2. **Terraform state storage account.** A storage account + container
   referenced by the per-environment `backend.tf`. Recommended layout:
   ```
   resource_group_name  = "boatapp-tfstate-rg"
   storage_account_name = "boatapptfstate"
   container_name       = "tfstate"
   key                  = "<environment>.tfstate"
   ```
3. **Entra ID app registration with federated credentials.** One Entra ID
   application that GitHub Actions logs into via OIDC. Capture its object
   ID (the **service principal** object ID, not the application ID):
   ```bash
   az ad app create --display-name "boatapp-cicd"
   APP_ID=$(az ad app list --display-name boatapp-cicd --query '[0].appId' -o tsv)
   az ad sp create --id "${APP_ID}"
   SP_OBJECT_ID=$(az ad sp show --id "${APP_ID}" --query id -o tsv)
   ```
   Add a federated credential for each protected branch / environment
   (`subject = repo:<org>/<repo>:environment:staging`, etc.). Then grant
   the SP `Contributor` on the subscription (Terraform plan/apply needs
   broad write access). The `SP_OBJECT_ID` value goes into:
   - `ci_push_principal_id`  (granted `AcrPush` on the registry)
   - `tf_apply_principal_id` (granted `Key Vault Secrets Officer` on the vault)

## Layout

```
infra/terraform/
├── providers.tf                  # azurerm ~> 4.x + backend "azurerm" (partial)
├── variables.tf                  # all root inputs (see comments per-var)
├── outputs.tf                    # bff_fqdn, keycloak_fqdn, …
├── main.tf                       # wires the 5 child modules
├── terraform.tfvars.example      # copy + fill in
├── modules/
│   ├── networking/               # RG + VNet + subnets + private DNS zones
│   ├── database/                 # Flexible Server + 3 DBs
│   ├── container-registry/       # ACR + AcrPull/AcrPush role assignments
│   ├── container-apps/           # ACA Env + 3 apps + 2 Liquibase Jobs
│   └── keyvault/                 # KV + secrets + role assignments
└── environments/
    ├── staging/                  # backend.tf + main.tf + terraform.tfvars
    └── production/
```

## Plan & apply

```bash
cd infra/terraform/environments/staging

terraform init                 # configures the azurerm backend
terraform plan -out staging.plan
terraform apply staging.plan
```

Sensitive values (`*_password`, `*_principal_id`) should be supplied via
environment variables instead of being committed to `terraform.tfvars`:

```bash
export TF_VAR_postgres_admin_password='…'
export TF_VAR_bff_db_password='…'
export TF_VAR_business_db_password='…'
export TF_VAR_keycloak_db_password='…'
export TF_VAR_keycloak_admin_password='…'
export TF_VAR_ci_push_principal_id='<sp-object-id>'
export TF_VAR_tf_apply_principal_id='<sp-object-id>'
```

## First-apply caveat (RBAC propagation)

`AcrPull` (registry) and `Key Vault Secrets User` (vault) are granted
to each Container App's system-assigned managed identity *after* the
app itself is created. Both RBAC writes complete in the same
`terraform apply`, but Entra ID role propagation is eventually
consistent: image pulls and Key Vault secret references can fail for
30–90 s on the first apply before recovering automatically. No second
`terraform apply` is required.

## Post-apply (Ansible — phase 02c3)

`terraform apply` does **not** create per-DB application roles, apply
the Liquibase changelogs, or load the Keycloak realm. Those are
orchestrated by `infra/ansible/` (next phase):

- `bootstrap-db-roles.yml` — creates `bff`, `business_service`,
  `keycloak` PostgreSQL roles using the server-level admin from Key
  Vault.
- `run-liquibase-jobs.yml` — `az containerapp job start` against
  `module.container_apps.liquibase_job_names[…]`.
- `configure-keycloak.yml` — `keycloak-config-cli` against the
  Keycloak admin endpoint with `infra/keycloak/realm.yaml`.

## State file is sensitive

The state file contains the `bff-signing-key` PEM and the password
material that flows through Key Vault writes. The Azure Blob backend
must enforce HTTPS, RBAC-restricted access, and at-rest encryption
(default on storage account) — review the storage account ACL before
the first apply.

## Verification

```bash
ai-scripts/checks/02c2/run.sh .
```
