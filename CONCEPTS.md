# Concepts — Plain-English Glossary

A quick reference for every Microsoft Azure and GitHub concept used in
[`DEPLOYMENT.md`](DEPLOYMENT.md). Each entry follows the same shape:

- **Analogy** — one sentence, no jargon.
- **Big picture** — what contains it, what it contains.
- **Used in** — the section of `DEPLOYMENT.md` where you first meet it.
- **Docs** — official link.

## Microsoft Azure

### Tenant

- **Analogy** — your organisation's "address book" of identities.
- **Big picture** — every Azure subscription belongs to exactly one
  tenant; every user, group, and Service Principal lives inside a
  tenant. A free Microsoft account auto-creates a personal tenant on
  first Azure sign-up.
- **Used in** — §A.3 (the bootstrap script reads
  `az account show --query tenantId`).
- **Docs** — <https://learn.microsoft.com/entra/fundamentals/whatis>

### Subscription

- **Analogy** — a billing container; a "folder of resources you pay
  for".
- **Big picture** — a tenant can hold many subscriptions (e.g. one
  per cost centre, or one per environment); every Azure resource
  lives inside exactly one subscription.
- **Used in** — §A.1 (`--subscription` flag on the bootstrap).
- **Docs** —
  <https://learn.microsoft.com/azure/cost-management-billing/manage/>

### Resource Group

- **Analogy** — a labelled box that groups related resources. Delete
  the box → delete everything in it.
- **Big picture** — every Azure resource lives in exactly one
  resource group within a subscription. Used both for lifecycle
  management and for region anchoring.
- **Used in** — bootstrap creates `boat-app-tfstate-rg`; Terraform
  creates per-env RGs.
- **Docs** —
  <https://learn.microsoft.com/azure/azure-resource-manager/management/manage-resource-groups-portal>

### Region

- **Analogy** — the physical datacentre your resources run in.
- **Big picture** — every resource has a region; latency, residency
  rules, and pricing all depend on it. This stack defaults to
  `switzerlandnorth`.
- **Used in** — `--location` flag.
- **Docs** — <https://azure.microsoft.com/explore/global-infrastructure/geographies/>

### Microsoft Entra ID (formerly Azure AD)

- **Analogy** — the identity provider; the "login system" of Azure.
- **Big picture** — a tenant-level service that issues identities
  (users, groups, App Registrations, Service Principals) and the
  tokens that prove who you are.
- **Used in** — §A.3 (the bootstrap creates an App Registration
  here).
- **Docs** — <https://learn.microsoft.com/entra/identity/>

### App Registration

- **Analogy** — the *definition* of an application's identity: its
  name, the sign-in flows it supports, and which external systems
  may federate into it.
- **Big picture** — lives inside Entra ID. One App Registration
  manifests as one Service Principal per consenting tenant.
- **Used in** — §A.3 (bootstrap creates `boat-app-ci`).
- **Docs** —
  <https://learn.microsoft.com/entra/identity-platform/quickstart-register-app>

### Service Principal

- **Analogy** — the runtime "user account" a robot logs in as. The
  thing role assignments are granted to.
- **Big picture** — the local materialisation of an App Registration
  inside *your* tenant. Granting `Contributor` on a subscription =
  granting it to this Service Principal.
- **Used in** — §A.3 (gets `Contributor` + `User Access
  Administrator` on the subscription).
- **Docs** —
  <https://learn.microsoft.com/entra/identity-platform/app-objects-and-service-principals>

### OIDC Federated Credential

- **Analogy** — a written promise that says "I trust the bearer of a
  GitHub-Actions token whose subject matches `repo:owner/name:ref:
  refs/heads/staging` to log in as this Service Principal — no
  password required".
- **Big picture** — replaces long-lived `AZURE_CLIENT_SECRET` values
  from older patterns. The GitHub runner mints a short-lived OIDC
  token on every job; Entra ID validates the signature against the
  GitHub OIDC issuer and the federated credential subject.
- **Used in** — bootstrap creates 5 federated credentials (one each
  for `main` push, `staging` push, PRs, `staging` environment,
  `production` environment).
- **Docs** —
  <https://learn.microsoft.com/entra/workload-id/workload-identity-federation>

### RBAC role

- **Analogy** — a labelled badge that says "the holder is allowed to
  do X on Y". X is the action set, Y is the scope.
- **Big picture** — Azure RBAC = scope × role × principal. Scopes
  nest: management group ⊃ subscription ⊃ resource group ⊃
  resource. Roles used here: `Contributor`,
  `User Access Administrator`, `Storage Blob Data Contributor`,
  `AcrPush`, `Key Vault Secrets Officer`.
- **Used in** — §A.3.
- **Docs** —
  <https://learn.microsoft.com/azure/role-based-access-control/overview>

### Storage Account and Blob Container

- **Analogy** — a storage account is a billing-level "drive";
  a blob container is a folder inside it; blobs are files.
- **Big picture** — bootstrap creates one storage account with two
  containers (`staging`, `production`) used as the **Terraform
  remote state** backend, plus blob versioning to recover from a
  corrupt apply.
- **Used in** — §A.3.
- **Docs** —
  <https://learn.microsoft.com/azure/storage/blobs/storage-blobs-introduction>

### Terraform remote state

- **Analogy** — the file Terraform writes to remember "what
  infrastructure currently exists" so the next run can compute a
  diff, not start from scratch.
- **Big picture** — lives in the blob container above so every CI
  run shares the same state and can be locked against concurrent
  applies. Workflows reference it via the
  `TF_STATE_STORAGE_ACCOUNT` and `TF_STATE_RESOURCE_GROUP` secrets.
- **Used in** — §A.3 (bootstrap), all deploy workflows.
- **Docs** —
  <https://developer.hashicorp.com/terraform/language/state/remote>

### Azure Container Registry (ACR)

- **Analogy** — Docker Hub, but private and scoped to your
  subscription.
- **Big picture** — stores the BFF and Business Service container
  images; Container Apps pull from it via a managed identity. Image
  tags include the commit SHA, `latest`, and (for releases) the
  release tag.
- **Used in** — every push from `deploy-staging.yml` and
  `deploy-production.yml`.
- **Docs** —
  <https://learn.microsoft.com/azure/container-registry/container-registry-intro>

### Azure Container Apps + Container App Environment

- **Analogy** — managed serverless containers (Kubernetes runs
  underneath, but you never see it).
- **Big picture** — each *Container App* hosts one workload; all
  Container Apps in an environment share a VNet, log workspace, and
  egress IP. The boat-app deploys 3 Container Apps (bff,
  business-service, keycloak) plus 2 one-shot Liquibase migration
  *Jobs*.
- **Used in** — Terraform `modules/container-apps`.
- **Docs** —
  <https://learn.microsoft.com/azure/container-apps/overview>

### Azure Database for PostgreSQL — Flexible Server

- **Analogy** — managed PostgreSQL: Azure runs the server, you own
  the schema.
- **Big picture** — one Flexible Server hosts the three logical
  databases (`bff_session`, `boatapp`, `keycloak`) over a private
  endpoint inside the VNet — no public internet path.
- **Used in** — Terraform `modules/database`.
- **Docs** —
  <https://learn.microsoft.com/azure/postgresql/flexible-server/overview>

### Azure Key Vault

- **Analogy** — a managed safe for secrets, certificates, and keys.
- **Big picture** — stores the five `TF_VAR_*` passwords; Container
  Apps mount them as **secret references** in their environment at
  runtime, so the secret never appears in plaintext config. RBAC
  controls who can read versus write.
- **Used in** — §A.4 + Terraform `modules/keyvault`.
- **Docs** —
  <https://learn.microsoft.com/azure/key-vault/general/overview>

### VNet + Private DNS Zone

- **Analogy** — a private network for your Azure resources, plus a
  private "phonebook" so they can reach each other by name.
- **Big picture** — keeps PostgreSQL traffic off the public
  internet; the Container Apps environment plugs into the same VNet,
  the database has a private endpoint, and the private DNS zone
  resolves the database FQDN to that private IP.
- **Used in** — Terraform `modules/networking`.
- **Docs** — <https://learn.microsoft.com/azure/virtual-network/>

## GitHub

### Personal account vs. Organization

- **Analogy** — a personal account is "your locker"; an organisation
  is "a shared filing cabinet" with multiple members and finer
  controls.
- **Big picture** — repos billed and owned by either. Several
  features (`GITLEAKS_LICENSE`, classic branch protection
  variants, some required-reviewer rules) behave differently between
  the two.
- **Used in** — §B (this how-to assumes a *personal account*,
  private repo).
- **Docs** —
  <https://docs.github.com/get-started/learning-about-github/types-of-github-accounts>

### Repository

- **Analogy** — your code plus its history, Issues, PRs, Actions
  runs, and settings.
- **Big picture** — owned by either an account or an org; can be
  private or public; has its own secrets, variables, environments,
  and protection rules.
- **Used in** — §B.1.
- **Docs** —
  <https://docs.github.com/repositories/creating-and-managing-repositories/about-repositories>

### Branch (`main`, `staging`)

- **Analogy** — a named pointer to a commit; a "lane" of history.
- **Big picture** — this repo uses two long-lived branches: `main`
  (default, the source for production releases) and `staging` (push
  here to deploy to the staging environment).
- **Used in** — workflow triggers in §B.5.
- **Docs** —
  <https://docs.github.com/get-started/quickstart/github-glossary#branch>

### Pull Request

- **Analogy** — a proposal to merge one branch into another, with
  review and CI built in.
- **Big picture** — opening a PR triggers `ci.yml`, CodeQL, and
  `terraform-plan.yml`; the diff is verified before merge.
- **Used in** — §B.5.
- **Docs** —
  <https://docs.github.com/pull-requests/collaborating-with-pull-requests/proposing-changes-to-your-work-with-pull-requests/about-pull-requests>

### Release

- **Analogy** — a version-tagged "milestone" with notes attached;
  publishable via UI or `gh release create`.
- **Big picture** — publishing a release fires
  `deploy-production.yml`. Releases are immutable: the workflow
  pins its checkout to the release tag, so the build matches the
  artefacts users get.
- **Used in** — §B.5.
- **Docs** —
  <https://docs.github.com/repositories/releasing-projects-on-github/about-releases>

### Actions / Workflow / Job / Step

- **Analogy** — Actions is the CI engine; a *workflow* is one YAML
  recipe; a *job* is a parallel track; a *step* is a single command.
- **Big picture** — this repo has five workflows: `ci.yml`,
  `deploy-staging.yml`, `deploy-production.yml`, `codeql.yml`,
  `terraform-plan.yml`.
- **Used in** — §B.
- **Docs** —
  <https://docs.github.com/actions/learn-github-actions/understanding-github-actions>

### Repo secret vs. Repo variable vs. Environment secret

- **Analogy** — secrets are sealed envelopes (encrypted, masked in
  logs); variables are sticky notes (plaintext); environment-scoped
  versions of either only land on the desk of jobs that work in that
  environment.
- **Big picture** — repo-level secrets/variables are visible to any
  workflow run; environment-level secrets are only injected when a
  job declares `environment: staging` (or `production`). This is
  why `TF_VAR_*` passwords are environment-scoped — so the
  Required-Reviewer gate also gates secret access.
- **Used in** — Tables 2–5 in §2.
- **Docs** —
  <https://docs.github.com/actions/security-guides/encrypted-secrets>

### Environment (`staging`, `production`)

- **Analogy** — a labelled deployment target with its own secrets
  and approval rules.
- **Big picture** — each Environment can require reviewers, restrict
  which branches can deploy to it, and add a wait timer. Production
  uses Required Reviewers; staging is unrestricted.
- **Used in** — §A.5 (turn on Required Reviewers on `production`).
- **Docs** —
  <https://docs.github.com/actions/deployment/targeting-different-environments/using-environments-for-deployment>

### Branch protection (classic)

- **Analogy** — a fence around a branch that demands a PR review,
  passing CI, and signed commits before any change can land.
- **Big picture** — repo-level rules attached by branch name. The
  bootstrap script applies these from `.github/settings.yml`.
  Superseded by *Rulesets* for new use cases, but still the simplest
  way to require status checks.
- **Used in** — §C.1.
- **Docs** —
  <https://docs.github.com/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches>

### Ruleset (modern, supports "Restrict creations")

- **Analogy** — branch protection on steroids — same restrictions,
  plus the ability to forbid *creating* or *updating* branches at
  all unless you are on the bypass list.
- **Big picture** — Rulesets target patterns (`refs/heads/*`,
  `refs/tags/v*`) rather than single branches and survive a
  visibility flip from private to public unchanged.
- **Used in** — §C.2 (the rule that ensures only YOU can create or
  modify branches, even after going public).
- **Docs** —
  <https://docs.github.com/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets>

### CODEOWNERS file + required code-owner review

- **Analogy** — a "who is on the hook for this folder" registry.
- **Big picture** — a `CODEOWNERS` file at the repo root maps path
  globs to GitHub users/teams. Combined with branch protection's
  "Require review from Code Owners", changes inside an owned path
  must be approved by the owner. Useful even on a one-person repo —
  it scales when you start inviting collaborators.
- **Used in** — §C.1 (mentioned as an optional next step).
- **Docs** —
  <https://docs.github.com/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners>

### Tag protection

- **Analogy** — the same fence as branch protection, but around tags
  (especially release tags).
- **Big picture** — without it, anyone with push access could create
  a `v1.2.3` tag that fires `deploy-production.yml`. With it,
  only your user (the bypass list) can create or delete tags
  matching `v*`.
- **Used in** — §C.3.
- **Docs** —
  <https://docs.github.com/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/configuring-tag-protection-rules>

### Push protection + Secret scanning

- **Analogy** — a turnstile that searches your `git push` for known
  token shapes and rejects the push *before* the secret reaches
  GitHub.
- **Big picture** — Secret scanning is a passive scan of history;
  Push protection is an active gate. Both are **free on public
  repos** (paid via GHAS on private). Turn them on the day you
  flip the repo to public.
- **Used in** — §C.7.
- **Docs** —
  <https://docs.github.com/code-security/secret-scanning/about-secret-scanning>

### Signed commits (GPG / SSH)

- **Analogy** — a wax seal on each commit proving it came from the
  holder of your key, not from someone who stole an access token.
- **Big picture** — set once with
  `git config --global user.signingkey <key-id>` +
  `git config --global commit.gpgsign true`; combine with branch
  protection's "Require signed commits" so only signed commits can
  reach `main`.
- **Used in** — §C.1.4.
- **Docs** —
  <https://docs.github.com/authentication/managing-commit-signature-verification>

### `pull_request_target` (anti-pattern warning)

- **Analogy** — the workflow trigger that runs *with the base
  branch's secrets* against an unreviewed PR diff. Dangerous on
  public repos because a fork can pull-request a malicious workflow
  edit and steal your tokens.
- **Big picture** — none of the five workflows in this repo use it
  (verified via `grep -r pull_request_target .github/workflows`).
  Keep it that way; if you ever need it, audit the body for any
  `actions/checkout` of the PR head.
- **Used in** — §C.5 (called out so you know what *not* to add).
- **Docs** —
  <https://securitylab.github.com/research/github-actions-preventing-pwn-requests/>

### OIDC token from the GitHub runner

- **Analogy** — a short-lived passport the runner mints on demand,
  signed by GitHub. Azure trusts it because of the federated
  credential created in §A.3.
- **Big picture** — replaces long-lived cloud credentials. The
  token's "subject" claim encodes the repo, branch, and environment,
  so a leaked token from one branch cannot deploy from another.
- **Used in** — every `azure/login` step in deploy workflows.
- **Docs** —
  <https://docs.github.com/actions/deployment/security-hardening-your-deployments/about-security-hardening-with-openid-connect>

### GitHub Advanced Security (GHAS)

- **Analogy** — the paid bundle that turns on CodeQL,
  push protection, and secret scanning on **private** repos.
- **Big picture** — **free for public repos**. On a private
  personal repo, the committed `codeql.yml` workflow falls back to
  Semgrep-OSS automatically when GHAS is unavailable. Flipping to
  public makes the full GHAS feature set free.
- **Used in** — §B.3 (private state) and §C.7 (public flip).
- **Docs** —
  <https://docs.github.com/get-started/learning-about-github/about-github-advanced-security>

### `gh` CLI

- **Analogy** — the official command-line client for GitHub.
- **Big picture** — lets the bootstrap script set secrets, create
  Environments, and apply protection rules without you opening the
  web UI. Authenticated once with `gh auth login`.
- **Used in** — §1 (prerequisites) and throughout.
- **Docs** — <https://cli.github.com/>
