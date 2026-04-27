# Deployment Runbook

How to take this repository from "fresh private GitHub repo + a Microsoft
account that has never used Azure" to a green CI run, an automatic
staging deploy, and a manual-approval production release.

> Every Azure or GitHub term in **bold** on first use is defined in
> [`CONCEPTS.md`](CONCEPTS.md) — click through if a name is new to you.

## 0. Mental model

### Azure side

```
Microsoft account
└── Tenant (Entra ID directory)
    └── Subscription (billing)
        ├── Resource Group: boat-app-tfstate-rg                           ← bootstrap-script-managed
        │   └── Storage Account → Blob Container `tfstate`                ← Terraform state (one .tfstate per env key)
        ├── Resource Group: boatapp-v2-staging-rg                         ← created by Terraform (project=boatapp-v2)
        │   ├── ACR                              ← Docker images (per-env)
        │   ├── Key Vault                        ← TF_VAR_* passwords + BFF private_key_jwt PEM
        │   ├── Flexible PostgreSQL (public + AllowAllAzure firewall)
        │   │     └── DBs: bff_session, boatapp, keycloak
        │   └── Linux App Service Plan (P0v3)
        │       ├── Linux Web App: <project>-staging-bff       (public, :8080)
        │       ├── Linux Web App: <project>-staging-business  (public, :8081, JWT-protected)
        │       └── Linux Web App: <project>-staging-keycloak  (public, :8080)
        ├── Resource Group: boatapp-production-rg                         ← same shape (project=boatapp)
        └── Entra ID App Registration "boat-app-ci"
            └── Service Principal  ← roles: Contributor, User Access Admin
                                   ← federated to GitHub via OIDC
```

> **No VNet, no private endpoint.** The simplification this redesign is
> built around: Postgres is reached over the public Azure backbone,
> protected by the AllowAllAzureServicesAndResourcesWithinAzureIps
> firewall rule + SSL, and Key Vault secrets are resolved by the Web Apps'
> managed identities via `@Microsoft.KeyVault(SecretUri=...)` references.

A GitHub Actions run mints a short-lived [**OIDC token**](CONCEPTS.md#oidc-token-from-the-github-runner),
exchanges it via the [**Federated Credential**](CONCEPTS.md#oidc-federated-credential)
for an Azure access token as the [**Service Principal**](CONCEPTS.md#service-principal),
then drives Terraform to create everything inside the per-env resource
group.

### GitHub side

```
Personal account (you)
└── Private repository
    ├── Branches: main (default), staging, feature/*
    ├── Pull Requests           → fire ci.yml + codeql.yml
    ├── Releases (any branch)   → fire deploy-production.yml
    ├── Repository secrets      ← AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_CLIENT_OBJECT_ID,
    │                              TF_STATE_*, TF_VAR_* (all repo-scoped — see Tables 2–4)
    ├── Repository variables    ← ACR_NAME, TF_STATE_CONTAINER
    ├── Environments
    │   ├── staging             ← deployment target only (no env-scoped secrets)
    │   └── production          ← deployment target + Required Reviewer gate
    └── Actions workflows (.github/workflows/)
        ├── ci.yml              (push/PR to main+staging+feature/**)
        ├── codeql.yml          (push/PR to main+staging + weekly cron)
        ├── deploy-staging.yml  (push to staging)
        └── deploy-production.yml (release published / workflow_dispatch with tag input)
```

A push to `staging` fires `deploy-staging.yml`; publishing a GitHub
Release fires `deploy-production.yml` (the release's tag becomes the
immutable image tag). Both authenticate to Azure via OIDC — no
long-lived `AZURE_CLIENT_SECRET` ever exists. Terraform plan runs as the
first step inside each deploy workflow; there is no separate
`terraform-plan.yml`.

### Cross-product bridge

Three contact points couple the two products:

1. **OIDC federation** — GitHub mints, Azure validates. Trust is keyed
   by the federated credential's *subject* (e.g.
   `repo:you/repo:environment:production`).
2. **Azure Container Registry (ACR)** — GitHub pushes images, the App
   Service Web Apps pull them via their system-assigned MIs (AcrPull).
3. **Terraform state blob** — every `deploy-*.yml` run reads/writes the
   same state file in the bootstrap-created storage account (one state
   key per environment, in the `tfstate` container).

## 1. Prerequisites

Local CLIs (install before starting):

| Tool        | Minimum version | Install                                                             |
|-------------|-----------------|---------------------------------------------------------------------|
| `az`        | 2.65            | <https://learn.microsoft.com/cli/azure/install-azure-cli>           |
| `gh`        | 2.40            | <https://cli.github.com/>                                           |
| `terraform` | 1.7             | <https://developer.hashicorp.com/terraform/install>                 |
| `jq`        | any             | `apt install jq` / `brew install jq`                                |
| `yq`        | 4.x (optional)  | needed only if `.github/settings.yml` is present at bootstrap time  |

You also need:

- A Microsoft / Outlook account.
- The ability to create an Entra ID App Registration and assign
  subscription-scope roles (you have this on any subscription you own).
- Owner access to the GitHub repository.

## 2. Variables and secrets — at a glance

Read this table once before you start running commands. It tells you
what every variable is, where it lives, and who (you / the bootstrap
script / Terraform) is responsible for setting it.

### Table 1 — Local shell (you provide as CLI flags / env)

| Variable                | Where                                       | Who sets it                          | Purpose                                    |
|-------------------------|---------------------------------------------|--------------------------------------|--------------------------------------------|
| Azure subscription ID   | `--subscription` flag on bootstrap          | You                                  | Which Azure billing scope to use           |
| GitHub repo slug        | `--repo owner/name` flag                    | You                                  | Which repo gets the OIDC trust + secrets   |
| Region                  | `--location` (default `switzerlandnorth`)   | You                                  | Where Azure resources are created          |
| Project prefix          | `--project` (default `boat-app`)            | You                                  | Name prefix for RG / SA / ACR              |
| `az login` session      | local az credential cache                   | You (`az login`)                     | Lets the bootstrap script call Azure as you |
| `gh auth login` session | local gh credential cache                   | You (`gh auth login`)                | Lets the bootstrap script set GitHub secrets |

### Table 2 — GitHub repo-level secrets (auto-set by `00d-bootstrap-azure.sh`)

| Secret                     | Source                     | Used by                        |
|----------------------------|----------------------------|--------------------------------|
| `AZURE_CLIENT_ID`          | Entra ID app appId         | `azure/login` action (OIDC)    |
| `AZURE_TENANT_ID`          | Tenant ID                  | `azure/login` action           |
| `AZURE_SUBSCRIPTION_ID`    | Subscription ID            | `azure/login` + Terraform      |
| `TF_STATE_STORAGE_ACCOUNT` | Bootstrap-created SA       | Terraform backend              |
| `TF_STATE_RESOURCE_GROUP`  | Bootstrap-created RG       | Terraform backend              |

### Table 3 — GitHub repo-level variables (auto-set by bootstrap)

| Variable             | Value source           | Used by                                |
|----------------------|------------------------|----------------------------------------|
| `ACR_NAME`           | Derived from sub-hash  | Image push/pull in deploy workflows    |
| `TF_STATE_CONTAINER` | Bootstrap-created blob container (`tfstate`) | Terraform backend init |

### Table 4 — GitHub repo-level secrets you set MANUALLY (one set, used by both envs)

The bootstrap script intentionally does **not** generate
database/admin passwords — they belong to you. Generate values with
`openssl rand -base64 32`. They are stored at **repository scope** (not
environment scope), because the deploy workflows expose them via a
top-level `env:` block before any `environment:`-gated job runs; the
production manual-approval gate applies to the *deployment*, not to
secret access. See `.github/ENVIRONMENTS.md` for the full design note.

| Secret                              | Stored in Azure as | Purpose                                                                    |
|-------------------------------------|--------------------|----------------------------------------------------------------------------|
| `TF_VAR_POSTGRES_ADMIN_PASSWORD`    | Key Vault secret   | Flexible Server administrator password                                     |
| `TF_VAR_BFF_DB_PASSWORD`            | Key Vault secret   | BFF's per-DB Postgres role                                                 |
| `TF_VAR_BUSINESS_DB_PASSWORD`       | Key Vault secret   | Business Service per-DB role                                               |
| `TF_VAR_KEYCLOAK_DB_PASSWORD`       | Key Vault secret   | Keycloak's per-DB role                                                     |
| `TF_VAR_KEYCLOAK_ADMIN_PASSWORD`    | Key Vault secret   | Initial Keycloak admin password                                            |
| `AZURE_CLIENT_OBJECT_ID`            | —                  | SP **Object ID** (not appId) used by Terraform for AcrPush + Key Vault role assignments. Resolve once with `az ad sp show --id "$AZURE_CLIENT_ID" --query id -o tsv`. |

### Table 5 — Optional GitHub secrets (skip if unused)

| Secret              | When to set it                          | If unset                                  |
|---------------------|-----------------------------------------|-------------------------------------------|
| `DTRACK_URL`        | You have a Dependency-Track instance    | Workflow silently skips DT upload + gate  |
| `DTRACK_API_KEY`    | Same                                    | Same                                      |
| `GITLEAKS_LICENSE`  | Repo lives in a GitHub Organization     | Personal-account repos: skipped silently  |

### Table 6 — Azure runtime variables (informational only)

These are derived by Terraform from Key Vault secrets and injected into
each Web App's `app_settings` at runtime — sensitive values via
`@Microsoft.KeyVault(SecretUri=...)` placeholders that the App
Service's managed identity resolves on app-start. **You do not set
them manually.** Listed here so they are not a surprise when you read
`application-staging.yml` / `application-production.yml`:

`POSTGRES_FQDN`, `BFF_DB_NAME`, `BFF_DB_USER`, `BFF_DB_PASSWORD`,
`BUSINESS_DB_NAME`, `BUSINESS_DB_USER`, `BUSINESS_DB_PASSWORD`,
`KC_DB_USERNAME`, `KC_DB_PASSWORD`, `KEYCLOAK_ADMIN`,
`KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_CLIENT_ID`,
`BFF_SIGNING_KEY_PEM`, `BFF_SIGNING_KEY_ID`, `BUSINESS_SERVICE_URL`,
`SPRING_PROFILES_ACTIVE`, `WEBSITES_PORT`.

## 3. Part A — Azure account setup

You have a Microsoft / Outlook login but have never used Azure.

### A.1 Create your first Azure subscription

1. Sign in at <https://portal.azure.com> with your Microsoft account.
2. Click "Subscriptions" → "Add" → "Pay-As-You-Go".
3. Enter billing details → accept the agreement.
4. Note the **Subscription ID** that appears on the "Subscriptions"
   blade — it goes into the bootstrap script's `--subscription` flag.

> **Free Trial vs. Pay-As-You-Go**: the Free Trial gives $200 of credit
> for 30 days but caps some SKUs (notably PostgreSQL Flexible Server
> sizes). Pay-As-You-Go is the safe default for this stack.

### A.1.bis Rough monthly cost estimate per environment

Order-of-magnitude figures, Switzerland North region:

| Resource                                        | ≈ USD/month |
|-------------------------------------------------|-------------|
| Flexible PostgreSQL `B1ms`, 32 GB               | $25         |
| 1 Linux App Service Plan, P0v3 (1 vCPU / 4 GiB), shared by 3 Web Apps | $55 |
| Azure Container Registry, Basic tier            | $5          |
| Key Vault, standard, < 10k ops                  | < $1        |
| Storage Account (tfstate, shared across envs)   | < $1        |
| **Total per environment**                       | **≈ $85–95**|

So staging + production ≈ $120–160/month. Refresh with the live
calculator: <https://azure.microsoft.com/en-us/pricing/calculator/>.

### A.2 Install + authenticate the Azure CLI

```bash
# install — see https://learn.microsoft.com/cli/azure/install-azure-cli
az login                                 # opens a browser for SSO
az account set --subscription <id>       # paste the ID from A.1
```

### A.3 (One-time) Run the bootstrap script

The repo ships [`ai-scripts/00d-bootstrap-azure.sh`](ai-scripts/00d-bootstrap-azure.sh)
— an idempotent, one-shot script that creates everything Terraform
cannot create for itself (chicken-and-egg bootstrap).

```bash
gh auth login                            # browser SSO for GitHub

./ai-scripts/00d-bootstrap-azure.sh \
  --subscription <azure-subscription-id> \
  --repo         <github-owner>/<repo>   \
  --location     switzerlandnorth        \
  --project      boat-app
```

What it provisions, in three bullets:

- An Entra ID [**App Registration**](CONCEPTS.md#app-registration)
  + [**Service Principal**](CONCEPTS.md#service-principal) with **5
  [Federated Credentials](CONCEPTS.md#oidc-federated-credential)** —
  one each for `main` push, `staging` push, PRs, the `staging`
  environment, and the `production` environment. **No client secret
  is created.** Grants the SP `Contributor` +
  `User Access Administrator` on the subscription.
- The Terraform-state [**Storage Account**](CONCEPTS.md#storage-account-and-blob-container)
  with two blob containers (`staging`, `production`), blob versioning
  on, and the SP granted `Storage Blob Data Contributor`.
- All [**GitHub repo-level secrets and variables**](CONCEPTS.md#repo-secret-vs-repo-variable-vs-environment-secret)
  from Tables 2 & 3, plus the two GitHub Environments (`staging`,
  `production`).

The script is idempotent — re-running it against an already-bootstrapped
tenant is a no-op.

### A.4 Set the Terraform passwords + the SP Object ID

The bootstrap script does *not* generate the `TF_VAR_*` passwords —
they belong to you. The same secret values are used by both the
staging and production deploy workflows, so the snippet below stores
them at **repository scope** (single source of truth; the production
manual-approval gate is enforced separately by the
`environment: production` job-level setting).

```bash
REPO=<owner>/<repo>           # e.g. sebastien-attia/psychic-lamp

SECRETS=(
  TF_VAR_POSTGRES_ADMIN_PASSWORD
  TF_VAR_BFF_DB_PASSWORD
  TF_VAR_BUSINESS_DB_PASSWORD
  TF_VAR_KEYCLOAK_DB_PASSWORD
  TF_VAR_KEYCLOAK_ADMIN_PASSWORD
)

for name in "${SECRETS[@]}"; do
  # `gh secret set` reads from stdin when --body is omitted.
  # Do NOT use `--body -` — that stores the literal string "-".
  openssl rand -base64 32 \
    | gh secret set "$name" --repo "$REPO"
done
```

Store the generated values out-of-band (your password manager) — Azure
Key Vault will hold the canonical copy after the first
`terraform apply`, but you may need them for a manual psql session.

Then resolve and store the Service Principal **Object ID** (used by
Terraform for the AcrPush + Key Vault Secrets Officer role
assignments — different from the `appId` already in
`AZURE_CLIENT_ID`):

```bash
AZURE_CLIENT_ID=$(gh secret list --repo "$REPO" --json name | grep -q AZURE_CLIENT_ID && \
  az ad sp list --display-name boat-app-ci --query '[0].appId' -o tsv)
SP_OBJECT_ID=$(az ad sp show --id "$AZURE_CLIENT_ID" --query id -o tsv)
gh secret set AZURE_CLIENT_OBJECT_ID --repo "$REPO" --body "$SP_OBJECT_ID"
```

### A.5 Enable "Required reviewers" on the `production` Environment

This is the one step the bootstrap script flags as un-automatable
(GitHub has no public REST endpoint to set Required Reviewers on a
non-Enterprise account).

In the GitHub UI:

> Settings → Environments → **production** → Deployment protection
> rules → **Required reviewers** → add yourself → Save.

Without this, anyone with `write` access to your repo could publish a
Release and trigger a production deploy without approval.

> **Plan-tier caveat** — *Required reviewers* is **not available on
> free personal private repos**. If your Environments page shows only
> "Deployment branches and tags" (and no "Deployment protection rules"
> section), you are on the free plan. You have three options:
>
> 1. **Upgrade to GitHub Pro** ($4/mo, Settings → Billing & plans) —
>    unlocks Required Reviewers on the private repo.
> 2. **Flip the repo to public** (after the §C.6 pre-flight). Required
>    Reviewers, CodeQL/GHAS, secret scanning, and push protection all
>    become free at once.
> 3. **Skip the gate for now** — production deploys will still run
>    when you publish a Release, just without manual approval. As a
>    partial mitigation, set "Deployment branches and tags" to a
>    `v*` tag pattern so only release tags (which §C.3 restricts to
>    you) can deploy to `production`.

### A.6 (Optional) Wire Dependency-Track

If you have a Dependency-Track instance:

```bash
gh secret set DTRACK_URL     --repo <owner>/<repo> --body "https://dtrack.example.com"
gh secret set DTRACK_API_KEY --repo <owner>/<repo> --body "<api-key>"
```

If unset, the SBOM-upload step silently skips — CI still passes.

## 4. Part B — GitHub setup on a private personal account

### B.1 Create the private repo

```bash
gh repo create <owner>/<repo> --private --source=. --remote=origin --push
git checkout -b staging
git push -u origin staging
```

### B.2 Enable Actions

On personal accounts Actions is on by default. If you adopted
restrictive defaults, enable it: Settings → Actions → General →
"Allow all actions and reusable workflows" (or set to your preferred
allowlist).

### B.3 Note: features that are paywalled on free private repos

A free personal private repo has three notable gaps versus public or
paid:

- **CodeQL / GitHub Advanced Security** — paid. The committed
  `codeql.yml` workflow targets `language: java-kotlin` and
  `javascript-typescript`; on a private free repo the analyse step
  fails with an "Advanced Security must be enabled" error. Either
  flip the repo to public (§C.7) or remove `codeql.yml` from the
  required-status-checks list in `.github/settings.yml` until then.
- **Required Reviewers on Environments** — paid (GitHub Pro and
  above). See the §A.5 caveat for workarounds.
- **Secret scanning + Push protection** — paid (GHAS).

All three become **free** the moment you flip the repo to public
(§C.7).

### B.4 Note: `GITLEAKS_LICENSE`

Only required on GitHub Organization repos. On a personal-account
repo the gitleaks job runs without a license — no action needed.

### B.5 How a build is triggered

| Git action                                | Workflow(s)                | Effect                                                                                                       |
|-------------------------------------------|----------------------------|--------------------------------------------------------------------------------------------------------------|
| Open PR to `main` or `staging`            | `ci.yml`, `codeql.yml`     | Lint, SBOM, SCA, secret-scan, build (BFF / business-service / frontend), container scan, e2e, CodeQL         |
| Push to `main`, `staging`, or `feature/**`| `ci.yml`, `codeql.yml` (main+staging only) | Same as above (no deploy)                                                                  |
| Push to `staging`                         | `deploy-staging.yml`       | Re-runs `ci.yml`, builds + pushes `staging-<short-sha>` images to ACR, `terraform apply`, applies the Keycloak realm via `keycloak-config-cli`, smoke-tests |
| Publish a GitHub Release (any branch)     | `deploy-production.yml`    | Same flow as staging but the image tag = release tag; `apply` job is gated by the Required-Reviewer rule on the `production` Environment (§A.5) |
| Manual rerun of production                | `deploy-production.yml` (`workflow_dispatch`) | Same as above; you supply the existing git tag via the `tag` input                          |

### B.6 First-build smoke test

```bash
git commit --allow-empty -m "ci: trigger first build"
git push origin main
gh run watch                              # follow the live run
```

When `ci.yml` is green, exercise the staging deploy:

```bash
git checkout staging
git merge --ff-only main
git push origin staging
gh run watch                              # deploy-staging.yml
```

You should see Terraform create the `boatapp-v2-staging-rg` resource
group, the App Service Plan, and three Linux Web Apps. Their public
hostnames (`<project>-staging-{bff,business,keycloak}.azurewebsites.net`)
are printed in the deploy summary.

### B.7 Custom domains (not yet wired)

Out of the box each environment is reachable only at the deterministic
App Service hostname `<project>-<env>-<svc>.azurewebsites.net`
(e.g. `boatapp-v2-staging-bff.azurewebsites.net`). HTTPS is on by
default with a Microsoft-issued cert for the `*.azurewebsites.net`
wildcard, so no further action is needed for the public staging URL.

The current Terraform module (`modules/app-service`) does not yet
declare `azurerm_app_service_custom_hostname_binding` /
`azurerm_app_service_managed_certificate` resources, so binding a
custom domain like `app.example.com` is **not a one-flag operation**
today. If/when you need it, the path is:

1. Add a CNAME (`app.example.com → <bff>.azurewebsites.net`) and an
   `asuid.app.example.com` TXT record (value =
   `azurerm_linux_web_app.bff.custom_domain_verification_id`) at your
   DNS provider.
2. Extend `modules/app-service/main.tf` with the two resources
   referenced above (binding + managed cert), guarded by an optional
   `bff_custom_domain` variable so it stays opt-in per environment.
3. Re-apply.

Until that lands, treat custom domains as out of scope for this
runbook.

## 5. Part C — Lock-down

The goal: only **YOU** can modify `main`, create or modify any branch,
or publish a tag — even after the repo is flipped to public. The
bootstrap script applies the basics from `.github/settings.yml`; this
section explains them and adds what `settings.yml` cannot cover.

### C.1 Four protections on `main` and `staging`

Either via Settings → Branches → "Add branch protection rule", or
declared in `.github/settings.yml` and applied by the bootstrap
script:

1. **Require a pull request before merging**, with at least 1
   approving review. Even though you are the only author, this
   prevents accidental direct pushes from your local clone and
   forces CI to run.
2. **Require status checks to pass before merging** — pin the exact
   contexts from `.github/settings.yml`: `lint`, `sca-scan`,
   `secret-scan`, `build-bff`, `build-business-service`,
   `build-frontend`, `container-scan`, `e2e-tests`, plus the two
   CodeQL contexts (`CodeQL / analyze (java-kotlin)` and
   `CodeQL / analyze (javascript-typescript)`) on `main` only.
   `staging` mirrors `main` minus the CodeQL gate so the integration
   loop stays fast. PRs that bypass CI cannot merge.
3. **Block force-pushes** and **block branch deletion**. Force-pushes
   destroy history; deletion drops the protection rule with the
   branch.
4. **Require linear history** + **require signed commits**. Configure
   signing once on your laptop:

   ```bash
   git config --global user.signingkey <your-key-id>
   git config --global commit.gpgsign  true
   ```

### C.2 Restrict who can push and who can create branches

This is the rule that survives flipping to public. Use a **GitHub
Repository [Ruleset](CONCEPTS.md#ruleset-modern-supports-restrict-creations)**,
not a classic branch-protection rule, because rulesets support the
"Restrict creations" / "Restrict updates" targets.

Settings → Rules → Rulesets → New branch ruleset:

- **Target**: All branches (`refs/heads/*`).
- **Bypass list**: your user only.
- Enable: **Restrict creations**, **Restrict updates**,
  **Restrict deletions**, **Require a pull request before merging**.

Effect: nobody — not even an invited collaborator, not even the
contributor of a fork PR — can create, modify, or delete any branch
directly. They must open a PR you merge.

### C.3 Tag protection

Settings → Tags → New rule → pattern `v*` → restrict creation /
deletion to your user only. Stops a malicious PR from inserting a
poisoned release tag that would trigger `deploy-production.yml`.

### C.4 Restrict who can run Actions on PRs from forks

Most relevant once you flip to public. Settings → Actions →
General →

- **"Fork pull request workflows from outside collaborators"** →
  set to **"Require approval for first-time contributors who are
  new to GitHub"** at minimum, or **"Require approval for all
  outside collaborators"** to manually green-light every fork PR.
- **"Workflow permissions"** → leave at the default
  "Read repository contents and packages permissions".
- **"Allow GitHub Actions to create and approve pull requests"**
  → off.

### C.5 Secrets are already safe from forks

By GitHub's design, repository and environment secrets are **never**
exposed to workflows triggered by `pull_request` events from forks.
Only the read-only `GITHUB_TOKEN` is.

The one exception to avoid is the
[`pull_request_target`](CONCEPTS.md#pull_request_target-anti-pattern-warning)
trigger, which *does* expose secrets. **None** of the five
workflows in this repo use it — verify with:

```bash
grep -rn pull_request_target .github/workflows
# (no output expected)
```

### C.6 Pre-flight checklist BEFORE flipping to public

Do these once before you switch the visibility toggle:

1. Run `gitleaks detect --source . --redact` on the full history.
   `git log -p` ends up on the public web — including any
   accidentally committed secret. The committed `.gitleaks.toml`
   catches the obvious patterns; review any findings.
2. Audit closed/merged PRs and Issues for leaked secrets or
   internal URLs (these become public too).
3. Inspect `main` and `staging` workflow runs. If any run logs
   reveal staging URLs or stack traces with internal IPs, delete
   those runs (Actions → workflow → Run → ⋯ → Delete) before
   flipping.
4. Confirm the `production` Environment has Required Reviewers
   turned on (§A.5).
5. Optionally rotate the five `TF_VAR_*` passwords (defence in
   depth in case any leaked into a log).

### C.7 On the day you flip to public

Settings → General → Danger Zone → "Change repository visibility"
→ **Public**. Then, the same day, enable the features that
*become free* on public repos:

- **Push protection + Secret scanning** — Settings → Code security
  → Secret scanning → Enable. Push protection blocks a `git push`
  that contains a known token format before it even reaches GitHub.
- **Dependabot alerts + security updates** — already on for public
  repos by default; verify under Code security.
- **CodeQL on the free GHAS-for-public-repos plan** — the committed
  `codeql.yml` workflow already targets this. The Semgrep fallback
  can be removed, but keeping it is harmless.
- **Branch protection and Rulesets carry over unchanged** — no
  action needed.

### C.8 Day-1-after-public verification

In an incognito window, browse the repo URL and confirm:

- Issues / PRs / Releases are visible as expected.
- The Actions tab shows green.
- No `TF_VAR_*` value appears in any log (search the run logs for
  one of your generated values).
- Try `git push --force origin main` from a fresh clone — should
  be rejected by the Ruleset.

## 6. Part D — Day-2 operations

- **Roll out a new version** — open a PR to `main`, merge after CI
  is green, then publish a Release: `gh release create v1.2.3
  --generate-notes`. Approve the production deployment when prompted.
- **Rotate database passwords** — re-run the loop from §A.4 with a
  fresh `openssl rand -base64 32`, then push an empty commit to
  `staging` (or publish a new release) so the deploy workflow picks
  the new value up via Terraform → Key Vault.
- **Tear down** — `cd infra/terraform/environments/staging &&
  terraform destroy`. Repeat for production. The bootstrap-managed
  `boat-app-tfstate-rg` is safe to keep across destroys.

For the gritty internals of the pipeline, see
[`ai-scripts/04-cicd.md`](ai-scripts/04-cicd.md) and
[`ai-scripts/04b-cicd-hardening.md`](ai-scripts/04b-cicd-hardening.md).

## 7. Troubleshooting

| Symptom                                                                | Likely cause and fix                                                                                                                       |
|------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| Bootstrap step `[5/7]` fails with `ERROR: (SubscriptionNotFound)`      | A required Azure resource provider is `NotRegistered` on a brand-new subscription — the storage data-plane returns this misleading error. The bootstrap script's step `[1/7]` registers the providers it needs; if you ran an older copy of the script, pull the latest and re-run, or pre-register manually with `az provider register -n Microsoft.Storage --wait` (and `Microsoft.ContainerRegistry`, `Microsoft.App`, `Microsoft.OperationalInsights`, `Microsoft.DBforPostgreSQL`, `Microsoft.KeyVault`, `Microsoft.Network`, `Microsoft.ManagedIdentity`). The bootstrap is idempotent — just re-run after registering. |
| `AADSTS70021: No matching federated identity record found`             | The branch / environment in the GitHub run does not match a federated credential subject. Re-run the bootstrap script — it is idempotent. |
| Terraform `Error: state lease conflict`                                | Another apply is in flight, or a prior run died holding the lease. Wait, or `az storage blob lease break` on the state blob.               |
| Web Apps return 503 right after the first deploy                       | Container is still starting and Web App + Postgres are still settling after `db-bootstrap`. Wait ~90 s, then `az webapp log tail -n <app> -g boatapp-v2-staging-rg`.                              |
| CodeQL job fails with "Advanced Security must be enabled"              | Expected on a private free repo — GHAS (and therefore CodeQL) is paid on private. Either flip to public (§C.7) or remove the two CodeQL contexts from `.github/settings.yml` until you do. |
| `production` Environment has no "Required reviewers" option in the UI  | You are on the free personal plan; Required Reviewers is paid on private repos. See §A.5 — upgrade to Pro, flip to public, or fall back to a tag-pattern restriction in "Deployment branches and tags". |
| Release tag created but `deploy-production.yml` did not run            | Required Reviewers (§A.5) is not configured *and* the tag does not match the trigger pattern. Confirm the Release is published, not draft. |
