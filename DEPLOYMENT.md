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
        ├── Resource Group: boat-app-tfstate-rg
        │   └── Storage Account → Blob Containers (staging, production)   ← Terraform state
        ├── Resource Group: boat-app-staging-rg                           ← created by Terraform
        │   ├── ACR (shared)            ← Docker images
        │   ├── Key Vault               ← TF_VAR_* passwords
        │   ├── Flexible PostgreSQL     ← bff_session, boatapp, keycloak
        │   ├── VNet + Private DNS
        │   └── Container App Environment
        │       ├── bff Container App
        │       ├── business-service Container App
        │       └── keycloak Container App
        ├── Resource Group: boat-app-production-rg                        ← same shape
        └── Entra ID App Registration "boat-app-ci"
            └── Service Principal  ← roles: Contributor, User Access Admin
                                   ← federated to GitHub via OIDC
```

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
    ├── Pull Requests           → fire ci.yml + CodeQL + terraform-plan
    ├── Releases (on main)      → fire deploy-production.yml
    ├── Repository secrets      ← AZURE_CLIENT_ID, AZURE_TENANT_ID, …
    ├── Repository variables    ← ACR_NAME, PROJECT, LOCATION
    ├── Environments
    │   ├── staging             ← TF_VAR_* secrets, no approval
    │   └── production          ← TF_VAR_* secrets + Required Reviewer
    └── Actions workflows (.github/workflows/)
        ├── ci.yml
        ├── deploy-staging.yml
        ├── deploy-production.yml
        ├── codeql.yml
        └── terraform-plan.yml
```

A push to `staging` fires `deploy-staging.yml`; publishing a Release on
`main` fires `deploy-production.yml`. Both authenticate to Azure via
OIDC — no long-lived `AZURE_CLIENT_SECRET` ever exists.

### Cross-product bridge

Three contact points couple the two products:

1. **OIDC federation** — GitHub mints, Azure validates. Trust is keyed
   by the federated credential's *subject* (e.g.
   `repo:you/repo:environment:production`).
2. **Azure Container Registry (ACR)** — GitHub pushes images, Azure
   Container Apps pull them.
3. **Terraform state blob** — both `terraform-plan.yml` (PR) and
   `deploy-*.yml` (apply) read/write the same state file in the
   bootstrap-created storage account.

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

| Variable    | Value source           | Used by                             |
|-------------|------------------------|-------------------------------------|
| `ACR_NAME`  | Derived from sub-hash  | Image push/pull in deploy workflows |
| `PROJECT`   | `--project` flag       | Terraform variable + naming         |
| `LOCATION`  | `--location` flag      | Terraform variable                  |

### Table 4 — GitHub environment-level secrets (you set MANUALLY)

Set these once per environment (`staging` and `production`). The
bootstrap script intentionally does **not** generate
database/admin passwords — they belong to you. Generate values with
`openssl rand -base64 32`.

| Secret                            | Stored in Azure as | Purpose                       |
|-----------------------------------|--------------------|-------------------------------|
| `TF_VAR_postgres_admin_password`  | Key Vault secret   | Flexible Server admin login   |
| `TF_VAR_bff_db_password`          | Key Vault secret   | BFF's per-DB Postgres role    |
| `TF_VAR_business_db_password`     | Key Vault secret   | Business Service per-DB role  |
| `TF_VAR_keycloak_db_password`     | Key Vault secret   | Keycloak's per-DB role        |
| `TF_VAR_keycloak_admin_password`  | Key Vault secret   | Keycloak master admin         |

### Table 5 — Optional GitHub secrets (skip if unused)

| Secret              | When to set it                          | If unset                                  |
|---------------------|-----------------------------------------|-------------------------------------------|
| `DTRACK_URL`        | You have a Dependency-Track instance    | Workflow silently skips DT upload + gate  |
| `DTRACK_API_KEY`    | Same                                    | Same                                      |
| `GITLEAKS_LICENSE`  | Repo lives in a GitHub Organization     | Personal-account repos: skipped silently  |

### Table 6 — Azure runtime variables (informational only)

These are derived by Terraform from Key Vault secrets and injected into
the Container Apps' environment at runtime. **You do not set them
manually.** Listed here so they are not a surprise when you read
`application-staging.yml` / `application-prod.yml`:

`POSTGRES_FQDN`, `BFF_DB_USER`, `BFF_DB_PASSWORD`, `BUSINESS_DB_USER`,
`BUSINESS_DB_PASSWORD`, `KEYCLOAK_DB_USER`, `KEYCLOAK_DB_PASSWORD`,
`KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_ISSUER_URI`, `KEYCLOAK_CLIENT_ID`,
`BFF_SIGNING_KEY_PATH`, `BFF_SIGNING_KEY_ID`, `BUSINESS_SERVICE_URL`.

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
| 3 Container Apps, idle, 0.25 vCPU / 0.5 GB each | $30         |
| Azure Container Registry, Basic tier            | $5          |
| Key Vault, standard, < 10k ops                  | < $1        |
| Storage Account (tfstate), < 1 GB               | < $1        |
| **Total per environment**                       | **≈ $60–80**|

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

### A.4 Set the per-environment Terraform passwords

The bootstrap script does *not* generate these — they belong to you.
The snippet below seeds all five secrets in **both** `staging` and
`production` in one go. Replace the `REPO=` value with your own
`owner/repo` slug. Works in both bash and zsh; uses an array instead
of backslash line-continuations so a stray copy-paste whitespace
cannot break it.

```bash
REPO=<owner>/<repo>           # e.g. sebastien-attia/psychic-lamp

SECRETS=(
  TF_VAR_postgres_admin_password
  TF_VAR_bff_db_password
  TF_VAR_business_db_password
  TF_VAR_keycloak_db_password
  TF_VAR_keycloak_admin_password
)

for env in staging production; do
  for name in "${SECRETS[@]}"; do
    # `gh secret set` reads from stdin when --body is omitted.
    # Do NOT use `--body -` — that stores the literal string "-".
    openssl rand -base64 32 \
      | gh secret set "$name" --env "$env" --repo "$REPO"
  done
done
```

Store the generated values out-of-band (your password manager) — Azure
Key Vault will hold the canonical copy after the first
`terraform apply`, but you may need them for a manual psql session.

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
  `codeql.yml` workflow includes a Semgrep-OSS fallback and skips
  silently when GHAS is unavailable; expect the CodeQL job marked
  "skipped", not failed.
- **Required Reviewers on Environments** — paid (GitHub Pro and
  above). See the §A.5 caveat for workarounds.
- **Secret scanning + Push protection** — paid (GHAS).

All three become **free** the moment you flip the repo to public
(§C.7).

### B.4 Note: `GITLEAKS_LICENSE`

Only required on GitHub Organization repos. On a personal-account
repo the gitleaks job runs without a license — no action needed.

### B.5 How a build is triggered

| Git action                          | Workflow(s)                               | Effect                                                                                |
|-------------------------------------|-------------------------------------------|---------------------------------------------------------------------------------------|
| Open PR to `main` or `staging`      | `ci.yml`, `codeql.yml`, `terraform-plan.yml` | Lint, build, SAST, SBOM, SCA, container scan, e2e; terraform plan posted as PR comment |
| Push to `main`                      | `ci.yml`                                  | Same as above (no deploy)                                                             |
| Push to `staging`                   | `deploy-staging.yml`                      | Build + push images to ACR, terraform apply, Ansible deploy, smoke tests              |
| Publish a GitHub Release on `main`  | `deploy-production.yml`                   | Production version of the staging flow, gated by the Required-Reviewer rule (§A.5)    |

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

You should see Terraform create the `boat-app-staging-rg` resource
group, three Container Apps (bff / business-service / keycloak), and
an Azure Static Web App with a linked-backend pointing at the BFF
Container App. The SWA hostname (where the SPA is served) and the
BFF's Container App FQDN (consumed only by the SWA, not by browsers
post-cutover) are printed in the deploy summary.

> **Cutover note:** before SWA is wired the BFF Container App
> ingress can stay `external = true` (so you can hit the BFF
> directly during smoke tests). Once SWA's linked-backend is
> active and the SPA loads through `https://<swa-hostname>/`,
> flip the BFF ingress to `external = false` so all browser
> traffic must traverse SWA. The change is a single Terraform
> diff and is reversible.

### B.7 Bind a custom domain (your own DNS) to the BFF and/or Keycloak

Out of the box each environment is reachable only at the random Azure
FQDN `<app>.<rand>.<region>.azurecontainerapps.io`. To publish under
your own domain (e.g. `app.example.com`) with a free Azure-managed
TLS cert, follow this **strict order** — the cert issuance fails if
DNS is not in place first.

#### Step 1 — Deploy once with no custom domain

This is what `B.6` already does. After it succeeds, the BFF and
Keycloak each have a stable Azure FQDN that does not change for the
life of the Container App.

#### Step 2 — Read the DNS values from terraform

```bash
cd infra/terraform/environments/staging         # or .../production
terraform output bff_fqdn                       # e.g. bff.nicemoss-1234.switzerlandnorth.azurecontainerapps.io
terraform output bff_custom_domain_verification_id   # e.g. ABCDE1234567890...
# repeat for keycloak_* if you want a custom auth subdomain too
```

The verification ID is **per-Container-App** and stable; it never
changes unless the Container App itself is destroyed and recreated.

#### Step 3 — Create two records at your DNS provider

Pick a subdomain you control (e.g. `app.example.com` for the BFF —
**not the apex** unless your provider supports CNAME-flattening /
ALIAS / ANAME, since Azure requires a CNAME). Add **both** records:

| Type  | Name (host)                | Value                                              | TTL   |
|-------|----------------------------|----------------------------------------------------|-------|
| CNAME | `app.example.com`          | `<bff_fqdn from step 2>`                           | 300 s |
| TXT   | `asuid.app.example.com`    | `<bff_custom_domain_verification_id from step 2>`  | 300 s |

The `asuid.` prefix proves to Azure that you own the domain. Repeat
for `auth.example.com` (CNAME → `<keycloak_fqdn>`,
TXT `asuid.auth.example.com` → `<keycloak_custom_domain_verification_id>`)
if you want a custom Keycloak hostname too.

Wait for DNS to propagate. Verify with:

```bash
dig +short CNAME app.example.com
dig +short TXT   asuid.app.example.com
```

Both must return the values you set — Azure only checks them once
during cert issuance, and a stale answer fails the apply.

#### Step 4 — Set the variable and re-apply

For staging, append to whatever you pass to terraform (in CI, this
goes in the `TF_VAR_*` env vars on the `staging` GitHub Environment):

```bash
export TF_VAR_bff_custom_domain="app-staging.example.com"
# Optional — only set if you ALSO created the auth.* DNS records
export TF_VAR_keycloak_custom_domain="auth-staging.example.com"

terraform -chdir=infra/terraform/environments/staging apply
```

Terraform creates the custom-domain binding and triggers Azure-managed
cert issuance in one apply. Cert provisioning is async and usually
completes within 1–3 minutes; check status with:

```bash
az containerapp hostname list --name bff --resource-group boat-app-staging-rg \
  --query "[?name=='app-staging.example.com'].{state:bindingType, cert:certificateId}" -o table
```

`bindingType` flips from `Disabled` to `SniEnabled` once the cert is
bound. Visit `https://app-staging.example.com` — you should see the
Vue SPA served over HTTPS with a valid Microsoft-issued cert.

#### Step 5 (only if you set keycloak_custom_domain) — re-run Ansible

Setting `keycloak_custom_domain` flips `KC_HOSTNAME` and the JWT
issuer URI to the new domain. The BFF's redirect URI registered on
the `boat-app-confidential` Keycloak client must therefore be
re-published from the new BFF URL. Re-run the Ansible
`configure-keycloak` playbook (or the equivalent CI step) so the
client config is refreshed.

#### Production — same flow, separate DNS records

Use a different subdomain per environment (e.g.
`app-staging.example.com` vs `app.example.com`). Each environment
has its own Container App with its own verification ID, so the TXT
records cannot be shared.

#### What "no fixed IP" means in practice

Azure Container Apps Consumption profile has no static public IP —
inbound traffic hits Azure's anycast frontend, which can resolve to
different IPs over time. The CNAME stays valid because the FQDN
itself is stable; only the IPs behind it move. If you need a literal
A record (apex domain on a registrar without ALIAS, or an
allowlist that takes IPs), you have to put **Azure Front Door** or
**Application Gateway** in front of the Container Apps — out of
scope for this runbook.

#### Renewal

Azure-managed certs auto-renew ~45 days before expiry as long as the
CNAME record stays in place. No operator action and no terraform
re-apply needed. If the CNAME is removed, the next renewal fails and
the cert lapses.

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
2. **Require status checks to pass before merging** — pin the
   check names from `ci.yml` (lint, build-bff, build-business-service,
   build-frontend, container-scan, e2e). PRs that bypass CI cannot
   merge.
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
| Container Apps return 503 right after the first deploy                 | Revision is still starting. Wait ~3 minutes, then `az containerapp logs show -n <app> -g boat-app-staging-rg --follow`.                    |
| CodeQL job marked "skipped" on a private repo                          | Expected — GHAS is paid on private. The Semgrep-OSS fallback covers basic SAST. Resolves itself when you flip to public (§C.7).            |
| `production` Environment has no "Required reviewers" option in the UI  | You are on the free personal plan; Required Reviewers is paid on private repos. See §A.5 — upgrade to Pro, flip to public, or fall back to a tag-pattern restriction in "Deployment branches and tags". |
| Release tag created but `deploy-production.yml` did not run            | Required Reviewers (§A.5) is not configured *and* the tag does not match the trigger pattern. Confirm the Release is published, not draft. |
