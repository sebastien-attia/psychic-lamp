# GitHub Environments — Boat App CI/CD

This document is a **reference** for the secrets, variables, and protection
rules required by the workflows under `.github/workflows/`. The bulk of this
configuration is produced automatically by
`./ai-scripts/00d-bootstrap-azure.sh`; this file describes what the script
produces, what you have to add manually, and why.

If you are setting up CI/CD for the first time, run the bootstrap script
first, then come back here for the manual steps.

```bash
./ai-scripts/00d-bootstrap-azure.sh
```

---

## Environments

| Environment  | Trigger                                              | Manual approval | Tags pushed to ACR                                |
|--------------|------------------------------------------------------|-----------------|---------------------------------------------------|
| `staging`    | push to the `staging` branch                         | none            | `staging`, `staging-<short-sha>`                  |
| `production` | publishing a GitHub Release on `main`                | required reviewer (1) | `<release-tag>`, `latest`, `production`     |

Both environments are created (empty) by `00d-bootstrap-azure.sh`. The
**production reviewer rule** must be added manually — GitHub's REST API does
not expose required-reviewer configuration outside Enterprise, so the
bootstrap script flags it at the end of its summary.

To set the reviewer:

1. Go to `Settings → Environments → production`.
2. Under **Deployment protection rules**, enable *Required reviewers*.
3. Add at least one reviewer (typically the platform owner).
4. Save.

---

## Repository-level secrets (set by `00d-bootstrap-azure.sh`)

| Secret                       | Source                                | Used by                                           |
|------------------------------|---------------------------------------|---------------------------------------------------|
| `AZURE_CLIENT_ID`            | Entra ID app `appId`                  | every workflow that calls `azure/login@v2`        |
| `AZURE_TENANT_ID`            | Entra ID tenant                       | `azure/login@v2`                                  |
| `AZURE_SUBSCRIPTION_ID`      | Subscription                          | `azure/login@v2`                                  |
| `TF_STATE_RESOURCE_GROUP`    | Storage-account RG                    | `terraform init -backend-config=...`              |
| `TF_STATE_STORAGE_ACCOUNT`   | Storage-account name                  | `terraform init -backend-config=...`              |

These are at the **repository** scope (visible to every workflow run on the
default branch). None of them carry direct access to Azure on their own —
the federated identity has to vouch for the run via OIDC before any of
them become useful.

## Repository-level variables (set by `00d-bootstrap-azure.sh`)

| Variable    | Example value | Used by                              |
|-------------|---------------|--------------------------------------|
| `ACR_NAME`  | `boatappstagingacr` | `az acr login --name ${{ vars.ACR_NAME }}` |
| `PROJECT`   | `boatapp`     | logging / tagging                    |
| `LOCATION`  | `switzerlandnorth` | downstream Terraform reference  |

`vars` (not `secrets`) because none of these are sensitive.

---

## Environment-scoped secrets (must be set MANUALLY)

These secrets exist **per environment** because their values differ between
staging and production. Add them at `Settings → Environments → <env> →
Environment secrets` for both `staging` and `production`.

### Terraform sensitive inputs

The Terraform module declares them as `sensitive = true`; passing them as
`TF_VAR_*` env vars (rather than `-var` flags) keeps the values out of the
run logs.

| Secret                              | Purpose                                                       |
|-------------------------------------|---------------------------------------------------------------|
| `TF_VAR_postgres_admin_password`    | PostgreSQL Flexible Server administrator (provisioning only). |
| `TF_VAR_bff_db_password`            | Per-DB password for the `bff` PostgreSQL role.                |
| `TF_VAR_business_db_password`       | Per-DB password for the `business_service` role.              |
| `TF_VAR_keycloak_db_password`       | Per-DB password for the `keycloak` role.                      |
| `TF_VAR_keycloak_admin_password`    | Initial Keycloak admin password.                              |

Generate strong values once per environment, store them in your password
manager **and** in the GitHub environment secret. The Terraform state is
encrypted at rest in the state storage account, but nothing recovers
these passwords if you lose them.

### Dependency-Track governance

| Secret           | Purpose                                                              |
|------------------|----------------------------------------------------------------------|
| `DTRACK_URL`     | HTTPS URL of the Dependency-Track API (e.g. `https://dt.example.org`). |
| `DTRACK_API_KEY` | API key with `BOM_UPLOAD` and `PROJECT_CREATION_UPLOAD` permissions. |

Keep the staging and production values **separate** so the DT projects
stay isolated. `deploy-staging.yml` and `deploy-production.yml` resolve
these from the matching environment scope at run time.

---

## Federated identity (OIDC) — what the bootstrap creates

`00d-bootstrap-azure.sh` creates an Entra ID application + service
principal with the following federated credentials:

| Subject                                                 | Used by                                     |
|---------------------------------------------------------|---------------------------------------------|
| `repo:<owner>/<repo>:ref:refs/heads/main`               | direct pushes to `main` (rare)              |
| `repo:<owner>/<repo>:ref:refs/heads/staging`            | `deploy-staging.yml`                        |
| `repo:<owner>/<repo>:pull_request`                      | `terraform-plan.yml`                        |
| `repo:<owner>/<repo>:environment:staging`               | `deploy-staging.yml` job that uses `environment: staging`     |
| `repo:<owner>/<repo>:environment:production`            | `deploy-production.yml` job that uses `environment: production` |

The same script grants the SP these role assignments:

- **Subscription scope**: `Contributor` and `User Access Administrator` —
  required so `terraform apply` can create resource groups, container
  apps, the Postgres Flexible Server, and (because the modules use
  managed-identity role assignments inside) the role definitions
  themselves. If you tighten this in the future, the minimum set for
  this project is:
    - `Container Apps Contributor`
    - `Key Vault Secrets Officer` (Key Vault scope)
    - `Storage Blob Data Contributor` (TF state container scope)
    - `User Access Administrator` (or a custom role allowing role
      assignments) for the per-app managed-identity bindings
- **Storage account (TF state)**: `Storage Blob Data Contributor` so the
  `azurerm` backend can read and write the state blobs.
- **ACR (created by Terraform on first apply)**: `AcrPush` — granted by
  the `modules/container-registry` Terraform module via
  `var.ci_push_principal_id`. Until the first apply succeeds, `docker
  push` will fail; the bootstrap can't pre-create the role assignment
  because the ACR doesn't exist yet. This is expected.

The `ci_push_principal_id` and `tf_apply_principal_id` Terraform inputs
are resolved at workflow time via `az ad sp show --id $AZURE_CLIENT_ID
--query id`, so you do not need to store the SP object ID as a separate
secret.

---

## Non-goals (explicitly NOT secrets)

The workflows fail closed on these — the Phase 4 verification gate
(`ai-scripts/checks/4/run.sh`) greps for them and treats their presence
as a blocking error.

| Anti-secret                            | Why it's banned                                              |
|----------------------------------------|--------------------------------------------------------------|
| `ACR_ADMIN_USERNAME`, `ACR_ADMIN_PASSWORD` | ACR admin is disabled on the registry. Every push uses an OIDC-federated short-lived token via `az acr login`. |
| `ACR_USERNAME`, `ACR_PASSWORD`         | Same reason — there are no long-lived registry credentials.  |
| `registry-username`, `registry-password` (action inputs) | Same — the `docker-build-push` composite action does not accept them. |
| `AZURE_CLIENT_SECRET`                  | OIDC federation replaces it. Adding a client secret would re-introduce the long-lived-credential rotation problem the federation exists to solve. |
| `AZURE_PASSWORD`                       | Same.                                                        |

If a future workflow needs registry access from a system that genuinely
cannot federate (e.g. a third-party CI), generate a scope-bound token
via `az acr token create` rather than turning admin back on.

---

## How to release to production

The flow is intentionally manual — no auto-promote-from-staging. The
release tag is the unit of accountability.

1. Merge the change to `main` via PR.
2. Open the repository on GitHub → **Releases** → **Draft a new release**.
3. Choose **Tag** → *create new tag* (e.g. `v1.2.0`) → target `main`.
4. Write release notes (or use **Generate release notes** — autogenerated
   notes are fine for routine releases).
5. Click **Publish release**.

The published-release event fires `deploy-production.yml`. The workflow
re-runs CI from the tagged commit, builds the images, pushes them to
ACR with `:v1.2.0`, `:latest`, `:production`, applies Terraform with the
release tag pinned, runs the Ansible post-deploy, smoke-tests the
production BFF, uploads the BOMs to the production Dependency-Track
project, and finally annotates the release body with a deployment
timestamp.

A required reviewer must approve the deployment from the **Environments**
tab on the workflow run page before any of the above happens.
