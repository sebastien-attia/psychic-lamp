#!/usr/bin/env bash
# One-shot, idempotent Azure + GitHub bootstrap for the CI/CD pipeline.
#
# Run this ONCE per Azure tenant + per GitHub repo, before the first
# `terraform apply` or GitHub Actions deploy. It creates everything that
# Terraform cannot create for itself (chicken-and-egg bootstrap):
#
#   1. Resource provider registration on the target subscription:
#        Microsoft.Storage, Microsoft.ContainerRegistry, Microsoft.App,
#        Microsoft.OperationalInsights, Microsoft.DBforPostgreSQL,
#        Microsoft.KeyVault, Microsoft.Network, Microsoft.ManagedIdentity.
#      A brand-new subscription has every RP in NotRegistered; calling
#      a data-plane endpoint against an unregistered RP returns the
#      misleading error `SubscriptionNotFound`.
#   2. Entra ID application + service principal (the identity GitHub
#      Actions federates into via OIDC).
#   3. Federated Identity Credentials binding that app to the GitHub
#      repo — one per environment subject claim:
#        repo:<owner>/<repo>:ref:refs/heads/main
#        repo:<owner>/<repo>:ref:refs/heads/staging
#        repo:<owner>/<repo>:pull_request
#        repo:<owner>/<repo>:environment:staging
#        repo:<owner>/<repo>:environment:production
#   4. Subscription-scope role assignments:
#        Contributor                                  (infra creation)
#        User Access Administrator                    (to manage RBAC at apply-time)
#   5. Terraform-state storage account + blob containers
#      (one container per environment: staging, production).
#   6. GitHub repo/environment secrets + variables via gh CLI:
#        secrets: AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_SUBSCRIPTION_ID,
#                 TF_STATE_STORAGE_ACCOUNT, TF_STATE_RESOURCE_GROUP
#        vars:    ACR_NAME, TF_STATE_CONTAINER
#   7. Branch protection on `main` and `staging` from
#      `.github/settings.yml` (committed by phase 4b). Skipped silently
#      if `settings.yml` is absent — first-run bootstrap before phase 4b
#      is therefore still possible. Re-run 00d after phase 4b lands to
#      apply the protection rules. Requires `yq` (soft dependency).
#
# After this script succeeds, the ONLY remaining manual action is turning
# on "Required reviewers" for the `production` GitHub Environment — that
# rule has no public CLI/API equivalent. Everything else (deploys, infra
# changes, schema migrations, Keycloak config, rotations) is fully code-
# driven through Terraform + Ansible + GitHub Actions with OIDC federation.
#
# Usage:
#   ./ai-scripts/00d-bootstrap-azure.sh \
#     --subscription  <subscription-id> \
#     --repo          <github-owner/repo> \
#     --location      switzerlandnorth \
#     --project       boat-app \
#     [--app-name     boat-app-ci] \
#     [--state-rg     boat-app-tfstate-rg] \
#     [--state-sa     boatapptfstateXXXXXX] \
#     [--acr-name     boatappacrXXXXXX]
#
# Dependencies: az CLI >= 2.65, gh CLI >= 2.40, jq.
# Authentication: `az login` + `gh auth login` BEFORE running.
#
# Idempotency: every `az` / `gh` call is guarded — re-running the script
# against an already-bootstrapped tenant is a no-op.
#
# Operator-facing runbook: ../DEPLOYMENT.md (§A.3 reproduces the flag
# list above — keep both in sync when this script changes).

set -euo pipefail

# ─── Argument parsing ──────────────────────────────────────────────────
SUBSCRIPTION=""
REPO=""
LOCATION="switzerlandnorth"
PROJECT="boat-app"
APP_NAME=""
STATE_RG=""
STATE_SA=""
ACR_NAME=""

usage() {
  sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-1}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --subscription)  SUBSCRIPTION="$2"; shift 2 ;;
    --repo)          REPO="$2"; shift 2 ;;
    --location)      LOCATION="$2"; shift 2 ;;
    --project)       PROJECT="$2"; shift 2 ;;
    --app-name)      APP_NAME="$2"; shift 2 ;;
    --state-rg)      STATE_RG="$2"; shift 2 ;;
    --state-sa)      STATE_SA="$2"; shift 2 ;;
    --acr-name)      ACR_NAME="$2"; shift 2 ;;
    -h|--help)       usage 0 ;;
    *)               echo "Unknown flag: $1" >&2; usage ;;
  esac
done

[[ -z "${SUBSCRIPTION}" ]] && { echo "--subscription is required" >&2; usage; }
[[ -z "${REPO}"         ]] && { echo "--repo <owner/name> is required" >&2; usage; }

# Defaults that depend on --project
APP_NAME="${APP_NAME:-${PROJECT}-ci}"
STATE_RG="${STATE_RG:-${PROJECT}-tfstate-rg}"

# Storage account + ACR names must be globally unique and <= 24 chars, lowercase.
# Derive a short deterministic suffix from the subscription ID so re-runs land on
# the same names without the caller having to remember them.
SUBHASH=$(printf '%s' "${SUBSCRIPTION}" | shasum | cut -c1-6)
STATE_SA="${STATE_SA:-${PROJECT//-/}tfstate${SUBHASH}}"
ACR_NAME="${ACR_NAME:-${PROJECT//-/}acr${SUBHASH}}"
STATE_SA="${STATE_SA:0:24}"
ACR_NAME="${ACR_NAME:0:24}"

# ─── Dependency checks ─────────────────────────────────────────────────
# `yq` is a soft dependency: only required when .github/settings.yml is
# present and we therefore attempt to apply branch protection. Hard
# dependencies are checked unconditionally.
for bin in az gh jq shasum; do
  command -v "${bin}" >/dev/null || { echo "Missing dependency: ${bin}" >&2; exit 2; }
done

az account show >/dev/null || { echo "Run \`az login\` first." >&2; exit 2; }
gh auth status >/dev/null 2>&1 || { echo "Run \`gh auth login\` first." >&2; exit 2; }

az account set --subscription "${SUBSCRIPTION}"
TENANT_ID=$(az account show --query tenantId -o tsv)

echo "▸ subscription : ${SUBSCRIPTION}"
echo "▸ tenant       : ${TENANT_ID}"
echo "▸ repo         : ${REPO}"
echo "▸ project      : ${PROJECT}"
echo "▸ app name     : ${APP_NAME}"
echo "▸ state RG/SA  : ${STATE_RG} / ${STATE_SA}"
echo "▸ ACR name     : ${ACR_NAME}  (created later by Terraform, name pinned here so CI can reference it)"
echo

# ─── 0. Resource provider registration ─────────────────────────────────
# A brand-new Azure subscription has every resource provider in
# `NotRegistered`. Calling the storage / ACR / Container Apps / Postgres
# data-plane against an unregistered RP returns the misleading error
# `SubscriptionNotFound` (not "RP not registered"). Register every RP
# this bootstrap + the downstream Terraform stack will touch, up front.
# `az provider register` is idempotent and `--wait` blocks until the RP
# reaches `Registered` (typically <30s for fresh subs, instant for
# already-registered ones).
echo "▸ [1/7] Resource provider registration"

REQUIRED_PROVIDERS=(
  Microsoft.Storage              # tfstate storage account (this script)
  Microsoft.ContainerRegistry    # ACR (Terraform)
  Microsoft.App                  # Azure Container Apps (Terraform)
  Microsoft.OperationalInsights  # Log Analytics workspace for ACA (Terraform)
  Microsoft.DBforPostgreSQL      # Flexible Server (Terraform)
  Microsoft.KeyVault             # Key Vault (Terraform)
  Microsoft.Network              # VNet + private DNS (Terraform)
  Microsoft.ManagedIdentity      # User-assigned identities (Terraform)
)

for rp in "${REQUIRED_PROVIDERS[@]}"; do
  state=$(az provider show -n "${rp}" --query registrationState -o tsv 2>/dev/null || echo "Unknown")
  if [[ "${state}" == "Registered" ]]; then
    echo "    registered: ${rp}"
  else
    echo "    registering: ${rp}  (current state: ${state})"
    az provider register -n "${rp}" --wait >/dev/null
    echo "    registered: ${rp}"
  fi
done

# ─── 1. Entra ID application + service principal ───────────────────────
echo "▸ [2/7] Entra ID application + service principal"

APP_ID=$(az ad app list --display-name "${APP_NAME}" --query '[0].appId' -o tsv)
if [[ -z "${APP_ID}" ]]; then
  APP_ID=$(az ad app create --display-name "${APP_NAME}" --query appId -o tsv)
  echo "    created Entra ID app: ${APP_NAME} (${APP_ID})"
else
  echo "    existing Entra ID app: ${APP_NAME} (${APP_ID})"
fi

SP_OBJECT_ID=$(az ad sp list --filter "appId eq '${APP_ID}'" --query '[0].id' -o tsv)
if [[ -z "${SP_OBJECT_ID}" ]]; then
  SP_OBJECT_ID=$(az ad sp create --id "${APP_ID}" --query id -o tsv)
  echo "    created service principal (objectId=${SP_OBJECT_ID})"
else
  echo "    existing service principal (objectId=${SP_OBJECT_ID})"
fi

# ─── 2. Federated Identity Credentials ─────────────────────────────────
echo "▸ [3/7] Federated Identity Credentials (OIDC) on ${APP_NAME}"

add_fic() {
  local name="$1" subject="$2"
  local existing
  existing=$(az ad app federated-credential list --id "${APP_ID}" \
             --query "[?name=='${name}'].name" -o tsv)
  if [[ -n "${existing}" ]]; then
    echo "    exists : ${name}"
    return
  fi
  az ad app federated-credential create --id "${APP_ID}" --parameters "$(cat <<EOF
{
  "name": "${name}",
  "issuer": "https://token.actions.githubusercontent.com",
  "subject": "${subject}",
  "audiences": ["api://AzureADTokenExchange"]
}
EOF
)" >/dev/null
  echo "    created: ${name}  (subject: ${subject})"
}

add_fic "gh-main"        "repo:${REPO}:ref:refs/heads/main"
add_fic "gh-staging"     "repo:${REPO}:ref:refs/heads/staging"
add_fic "gh-pr"          "repo:${REPO}:pull_request"
add_fic "gh-env-staging" "repo:${REPO}:environment:staging"
add_fic "gh-env-prod"    "repo:${REPO}:environment:production"

# ─── 3. Subscription role assignments ──────────────────────────────────
echo "▸ [4/7] Subscription-scope role assignments for ${APP_NAME}"

assign_role() {
  local role="$1"
  if az role assignment list --assignee "${SP_OBJECT_ID}" --scope "/subscriptions/${SUBSCRIPTION}" \
       --query "[?roleDefinitionName=='${role}'] | [0]" -o tsv | grep -q .; then
    echo "    exists : ${role}"
  else
    # Retry loop: new service principals sometimes aren't queryable immediately.
    for i in 1 2 3 4 5; do
      if az role assignment create --assignee-object-id "${SP_OBJECT_ID}" \
           --assignee-principal-type ServicePrincipal \
           --role "${role}" \
           --scope "/subscriptions/${SUBSCRIPTION}" >/dev/null 2>&1; then
        echo "    created: ${role}"
        return
      fi
      sleep 5
    done
    echo "    FAILED to assign ${role} after 5 retries" >&2
    exit 3
  fi
}

# Contributor covers create/update/delete of the Terraform-managed resources.
# User Access Administrator is required because Terraform creates RBAC role
# assignments (AcrPull, AcrPush, Key Vault Secrets User/Officer) — Contributor
# alone cannot grant roles.
assign_role "Contributor"
assign_role "User Access Administrator"

# ─── 4. Terraform state storage ────────────────────────────────────────
echo "▸ [5/7] Terraform remote state (Azure Blob)"

if ! az group show -n "${STATE_RG}" >/dev/null 2>&1; then
  az group create -n "${STATE_RG}" -l "${LOCATION}" \
    --tags project="${PROJECT}" managed-by=bootstrap-script role=tfstate >/dev/null
  echo "    created RG: ${STATE_RG}"
else
  echo "    exists RG: ${STATE_RG}"
fi

if ! az storage account show -g "${STATE_RG}" -n "${STATE_SA}" >/dev/null 2>&1; then
  az storage account create \
    -g "${STATE_RG}" -n "${STATE_SA}" -l "${LOCATION}" \
    --sku Standard_LRS --kind StorageV2 \
    --min-tls-version TLS1_2 \
    --allow-blob-public-access false \
    --public-network-access Enabled \
    --tags project="${PROJECT}" managed-by=bootstrap-script role=tfstate >/dev/null
  echo "    created SA: ${STATE_SA}"
else
  echo "    exists SA: ${STATE_SA}"
fi

# Enable blob versioning so a corrupt apply can be rolled back.
az storage account blob-service-properties update \
  -g "${STATE_RG}" -n "${STATE_SA}" --enable-versioning true >/dev/null

# Grant the service principal Storage Blob Data Contributor on the SA scope
# (required to read/write state blobs with OIDC auth — control-plane
# Contributor is not enough).
SA_ID=$(az storage account show -g "${STATE_RG}" -n "${STATE_SA}" --query id -o tsv)
if az role assignment list --assignee "${SP_OBJECT_ID}" --scope "${SA_ID}" \
     --query "[?roleDefinitionName=='Storage Blob Data Contributor'] | [0]" -o tsv | grep -q .; then
  echo "    role exists: Storage Blob Data Contributor on ${STATE_SA}"
else
  az role assignment create --assignee-object-id "${SP_OBJECT_ID}" \
    --assignee-principal-type ServicePrincipal \
    --role "Storage Blob Data Contributor" --scope "${SA_ID}" >/dev/null
  echo "    role created: Storage Blob Data Contributor on ${STATE_SA}"
fi

# Two containers, one per environment — each environments/<env>/backend.tf
# points at one of these.
for container in staging production; do
  if az storage container exists \
       --account-name "${STATE_SA}" --auth-mode login -n "${container}" \
       --query exists -o tsv 2>/dev/null | grep -q true; then
    echo "    container exists: ${container}"
  else
    az storage container create \
      --account-name "${STATE_SA}" --auth-mode login -n "${container}" \
      --public-access off >/dev/null
    echo "    container created: ${container}"
  fi
done

# ─── 5. GitHub secrets + variables ─────────────────────────────────────
echo "▸ [6/7] GitHub repo secrets + variables (${REPO})"

set_repo_secret() {
  local name="$1" value="$2"
  # `--body -` is NOT a stdin sentinel for `gh secret set` — it stores the
  # literal string "-". Stdin is the default when --body is omitted, so
  # pipe the value in without the flag (printf avoids the trailing newline
  # that `echo` would attach).
  printf '%s' "${value}" | gh secret set "${name}" --repo "${REPO}"
  echo "    secret set: ${name}"
}
set_repo_var() {
  local name="$1" value="$2"
  if gh variable list --repo "${REPO}" --json name -q '.[].name' | grep -qx "${name}"; then
    gh variable set "${name}" --repo "${REPO}" --body "${value}" >/dev/null
  else
    gh variable set "${name}" --repo "${REPO}" --body "${value}" >/dev/null
  fi
  echo "    var set:    ${name}=${value}"
}

set_repo_secret "AZURE_CLIENT_ID"          "${APP_ID}"
set_repo_secret "AZURE_TENANT_ID"          "${TENANT_ID}"
set_repo_secret "AZURE_SUBSCRIPTION_ID"    "${SUBSCRIPTION}"
set_repo_secret "TF_STATE_STORAGE_ACCOUNT" "${STATE_SA}"
set_repo_secret "TF_STATE_RESOURCE_GROUP"  "${STATE_RG}"

set_repo_var    "ACR_NAME"                 "${ACR_NAME}"
set_repo_var    "PROJECT"                  "${PROJECT}"
set_repo_var    "LOCATION"                 "${LOCATION}"

# Ensure the two GitHub Environments exist. Reviewer rules on `production`
# are the one thing this script cannot do (no REST API for required_reviewers
# except on GitHub Enterprise with advanced protection rules) — flagged at
# the end.
for env in staging production; do
  if gh api "repos/${REPO}/environments/${env}" >/dev/null 2>&1; then
    echo "    environment exists: ${env}"
  else
    gh api -X PUT "repos/${REPO}/environments/${env}" >/dev/null
    echo "    environment created: ${env}"
  fi
done

# ─── 6. Branch protection (codified in .github/settings.yml) ───────────
# Source of truth: .github/settings.yml (committed by phase 4b). The file
# follows the Probot-Settings YAML shape — we only consume the
# `branches[].protection` subtree per branch and PUT it via gh api.
# Idempotent: rerunning overwrites with the same payload.
echo "▸ [7/7] Branch protection on ${REPO} (from .github/settings.yml)"

apply_branch_protection() {
  local settings=".github/settings.yml"
  if [[ ! -f "${settings}" ]]; then
    echo "    skip: ${settings} not present (run phase 4b first to create it)"
    return 0
  fi
  if ! command -v yq >/dev/null; then
    echo "    skip: yq not installed — cannot parse ${settings}" >&2
    echo "          install with: az aks install-cli or sudo apt install yq" >&2
    return 0
  fi
  local branch payload http
  for branch in main staging; do
    payload=$(yq -o=json \
      ".branches[] | select(.name == \"${branch}\") | .protection" \
      "${settings}" 2>/dev/null)
    if [[ -z "${payload}" || "${payload}" == "null" ]]; then
      echo "    skip: ${settings} has no branches[name=${branch}].protection block"
      continue
    fi
    # GitHub's protection API requires `restrictions: null` when no team
    # restrictions apply — settings.yml omits it, so inject the null here.
    payload=$(echo "${payload}" | jq '. + {restrictions: null}')
    if http=$(echo "${payload}" \
        | gh api -X PUT "repos/${REPO}/branches/${branch}/protection" \
            -H "Accept: application/vnd.github+json" \
            --input - 2>&1); then
      echo "    protected: ${branch}"
    else
      echo "    WARN: ${branch} protection failed (does the branch exist on ${REPO}?)" >&2
      printf '          %s\n' "${http}" >&2
    fi
  done
}
apply_branch_protection

# ─── Summary ───────────────────────────────────────────────────────────
cat <<EOF

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  Bootstrap complete.

  Pass these two principal IDs into Terraform (infra/terraform/environments/
  <env>/terraform.tfvars) — they are consumed by modules/container-registry
  (AcrPush), modules/keyvault (Key Vault Secrets Officer), and the storage-
  blob data role above:

    ci_push_principal_id  = "${SP_OBJECT_ID}"
    tf_apply_principal_id = "${SP_OBJECT_ID}"

  The service principal IS the federated identity — the two variables carry
  the same value by design.

  Remaining manual action (cannot be scripted via public API):
    → GitHub → repo Settings → Environments → production →
      Deployment protection rules → "Required reviewers" → add reviewer(s).

  Everything else (Terraform apply, Ansible deploys, image push, schema
  migrations, Keycloak config, secret rotation) is now code-driven through
  GitHub Actions + OIDC with zero long-lived Azure credentials.
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
EOF
