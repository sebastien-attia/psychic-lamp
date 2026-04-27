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

## Repository-level secrets

All Terraform secrets live at **repository** scope so every job in
`deploy-staging.yml` / `deploy-production.yml` can read them without
declaring `environment:` (the production reviewer gate sits on the
`apply` job's `environment: production`, which is about *deployment*
gating, not secret access). The first five are produced by
`00d-bootstrap-azure.sh`; the rest are set manually with `gh secret set`.

| Secret                              | Source                                                    | Used by                                                   |
|-------------------------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `AZURE_CLIENT_ID`                   | Entra ID app `appId`                                      | every workflow that calls `azure/login@v2`                |
| `AZURE_TENANT_ID`                   | Entra ID tenant                                           | `azure/login@v2`                                          |
| `AZURE_SUBSCRIPTION_ID`             | Subscription                                              | `azure/login@v2`                                          |
| `TF_STATE_RESOURCE_GROUP`           | Storage-account RG                                        | `terraform init -backend-config=...`                      |
| `TF_STATE_STORAGE_ACCOUNT`          | Storage-account name                                      | `terraform init -backend-config=...`                      |
| `AZURE_CLIENT_OBJECT_ID`            | `az ad sp show --id $AZURE_CLIENT_ID --query id -o tsv`   | `TF_VAR_ci_push_principal_id` + `TF_VAR_tf_apply_principal_id` (role assignments) |
| `TF_VAR_POSTGRES_ADMIN_PASSWORD`    | strong random; saved in your password manager             | Postgres Flexible Server administrator password           |
| `TF_VAR_BFF_DB_PASSWORD`            | strong random                                             | per-DB password for the `bff` PostgreSQL role             |
| `TF_VAR_BUSINESS_DB_PASSWORD`       | strong random                                             | per-DB password for the `business_service` role           |
| `TF_VAR_KEYCLOAK_DB_PASSWORD`       | strong random                                             | per-DB password for the `keycloak` role                   |
| `TF_VAR_KEYCLOAK_ADMIN_PASSWORD`    | strong random                                             | initial Keycloak admin password                           |

None of these grant access to Azure on their own — the federated identity
has to vouch for the run via OIDC before any of them become useful.

### Setting `AZURE_CLIENT_OBJECT_ID` for the first time

The Terraform module's role assignments
(`azurerm_role_assignment.acr_push.principal_id`,
`azurerm_role_assignment.kv_secrets_officer.principal_id`) need the
**Service Principal Object ID**, not the Application Object ID — they
are different values. Resolve and store it once with:

```bash
SP_OBJECT_ID=$(az ad sp show --id "$AZURE_CLIENT_ID" --query id -o tsv)
gh secret set AZURE_CLIENT_OBJECT_ID --body "$SP_OBJECT_ID"
```

Why we don't resolve this at workflow runtime: `az ad sp show` against an
arbitrary `appId` requires `Application.Read.All` Microsoft Graph
permission, which the federated SP doesn't have by default — granting it
adds a directory-level admin consent step. Storing the resolved ID once
is the same pattern Microsoft's
[Azure-Samples/github-terraform-oidc-ci-cd](https://github.com/Azure-Samples/github-terraform-oidc-ci-cd)
uses.

### Setting the `TF_VAR_*` passwords

Generate a strong value for each, save it in your password manager, and
store it as a repository secret:

```bash
for s in TF_VAR_POSTGRES_ADMIN_PASSWORD TF_VAR_BFF_DB_PASSWORD \
         TF_VAR_BUSINESS_DB_PASSWORD TF_VAR_KEYCLOAK_DB_PASSWORD \
         TF_VAR_KEYCLOAK_ADMIN_PASSWORD; do
  gh secret set "$s"  # interactive prompt for the value
done
```

The Terraform state is encrypted at rest in the state storage account,
but nothing recovers these passwords if the password manager copy is
lost.

## Repository-level variables (set by `00d-bootstrap-azure.sh`)

| Variable    | Example value | Used by                              |
|-------------|---------------|--------------------------------------|
| `ACR_NAME`  | `boatappstagingacr` | `az acr login --name ${{ vars.ACR_NAME }}` |
| `PROJECT`   | `boatapp`     | logging / tagging                    |
| `LOCATION`  | `switzerlandnorth` | downstream Terraform reference  |

`vars` (not `secrets`) because none of these are sensitive.

---

## Environment-scoped secrets (must be set MANUALLY)

### Dependency-Track governance

`DTRACK_URL` and `DTRACK_API_KEY` are **repository-scoped** secrets so
the pre-deploy DT gate in `ci.yml` (which runs on every push and PR, in
jobs that have no `environment:` block) can read them. Project isolation
between staging and production is achieved by the per-environment
project UUID (see *Repository-level variables*) plus per-environment
project names (`boatapp-bff` vs `boatapp-bff-prod`, etc).

| Secret           | Scope          | Purpose                                                              |
|------------------|----------------|----------------------------------------------------------------------|
| `DTRACK_URL`     | repository     | HTTPS URL of the Dependency-Track API (e.g. `https://dt.example.org`). |
| `DTRACK_API_KEY` | repository     | API key with `BOM_UPLOAD`, `VIEW_PORTFOLIO`, `POLICY_VIOLATION_ANALYSIS`. |

If you genuinely need different DT instances per environment (e.g. a
production-only DT behind a stricter network), define environment-scoped
overrides at *Settings → Environments → \<env\> → Environment secrets*
with the same names — environment-scoped secrets shadow repository-scoped
ones inside any job that declares `environment:`. The `apply` job in both
deploy workflows carries the matching `environment:` so the override
takes effect for the post-deploy receipt step automatically.

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
are sourced from the `AZURE_CLIENT_OBJECT_ID` repository secret — see
*Setting `AZURE_CLIENT_OBJECT_ID` for the first time* above.

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

## Image signatures + SLSA provenance (cosign keyless)

Every image pushed by `deploy-staging.yml` or `deploy-production.yml` is
signed by the workflow's OIDC identity via `cosign --yes` and ships with
a SLSA Build L3 provenance attestation produced by
`actions/attest-build-provenance`. Both artifacts live alongside the image
in ACR.

To verify a deployed image locally (replace `<digest>` with the
`sha256:…` value from `docker inspect ${ACR}.azurecr.io/bff:staging` or
the deploy run summary):

```bash
cosign verify \
  --certificate-identity-regexp 'https://github\.com/<org>/<repo>/\.github/workflows/deploy-(staging|production)\.yml@.*' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  ${ACR_NAME}.azurecr.io/bff@<digest>
```

Replace `bff` with `business-service` for the other image. To list every
attached artifact (signature, provenance, SBOM if any), use
`cosign tree ${ACR_NAME}.azurecr.io/bff:staging`.

> **Storage cost.** cosign signatures + SLSA provenance are stored as
> additional OCI artifacts in ACR (~5–10 KB per image). They do not
> affect Container Apps pull behaviour — the runtime still resolves and
> pulls the image by tag exactly as before.

---

## Source-SAST: CodeQL or Semgrep fallback

`.github/workflows/codeql.yml` runs CodeQL on every push and PR to
`main`/`staging` plus a weekly schedule. The two analysis matrix legs
(`CodeQL / analyze (java-kotlin)` and
`CodeQL / analyze (javascript-typescript)`) are required-status contexts
on `main` per `.github/settings.yml`.

CodeQL on a **private** repository requires GitHub Advanced Security
(GHAS). If this repo is private and the org doesn't have GHAS, CodeQL
runs will fail with HTTP 403. Detect with:

```bash
OWNER_REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
gh api "/repos/${OWNER_REPO}" --jq \
  '.private and ((.security_and_analysis.advanced_security.status // "disabled") != "enabled")'
```

If the result is `true`:
1. Delete `.github/workflows/codeql.yml`.
2. Add a `semgrep` job to `.github/workflows/ci.yml` (use
   `returntocorp/semgrep-action` with `config: p/owasp-top-ten`, upload
   SARIF via `github/codeql-action/upload-sarif`, name the job
   `semgrep`).
3. Drop the two `CodeQL / analyze (...)` contexts from
   `.github/settings.yml > branches[name=main].protection.required_status_checks.contexts`
   and add `semgrep` instead.
4. Rerun `00d-bootstrap-azure.sh` to apply the new branch-protection
   contexts.

The verifier at `ai-scripts/checks/4b/run.sh` accepts either path
(CodeQL workflow OR a `returntocorp/semgrep` reference in `ci.yml`).

---

## Branch protection — source of truth

Branch protection is **APPLIED** by `ai-scripts/00d-bootstrap-azure.sh`.
`.github/settings.yml` is the **source of truth** for what the script
applies. Edit `settings.yml`, then rerun 00d to update protection rules
on github.com:

```bash
./ai-scripts/00d-bootstrap-azure.sh \
  --subscription <id> --repo <owner/repo>
```

The script reads the `branches[].protection` subtree per branch (`main`
and `staging`), injects `restrictions: null`, and PUTs the payload via
`gh api -X PUT repos/${REPO}/branches/${BRANCH}/protection`. Idempotent:
rerunning overwrites with the same payload.

`yq` is required to apply protection (the script parses `settings.yml`
with it) but the dependency is soft — first-run bootstrap before
`settings.yml` exists still works.

---

## Pre-deploy Dependency-Track gate (configuration)

`.github/actions/dtrack-gate/action.yml` runs in `ci.yml` for both Maven
modules. It uploads the BOM, polls until DT finishes analysis, then
fails the run on any unsuppressed FAIL-severity policy violation. The
post-deploy DT *receipt* (existing `dependency-track:upload-bom` Maven
goal) still runs from the deploy workflows so DT records both "what we
tried to ship" and "what we actually ran".

Two repository-level GitHub variables wire the gate:

| Variable                          | Purpose                                                      |
|-----------------------------------|--------------------------------------------------------------|
| `vars.DTRACK_PROJECT_UUID_STAGING`    | UUID of the staging DT project. The gate is skipped silently when this variable is unset (e.g. fresh fork before DT is wired). |
| `vars.DTRACK_PROJECT_UUID_PRODUCTION` | Reserved for a future production-side gate; currently unused. |

Set them with:
```bash
gh variable set DTRACK_PROJECT_UUID_STAGING --repo <owner/repo> --body <uuid>
```

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
