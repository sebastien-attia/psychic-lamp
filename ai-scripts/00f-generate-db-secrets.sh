#!/usr/bin/env bash
# Generate fresh random secrets for the staging, production, and
# production-bootstrap GitHub environments. Run ONCE per repo, then
# re-run only when rotating.
#
# Each invocation overwrites the existing values — the script is
# destructive in the sense that previously stored secrets cannot be
# recovered. Re-run on a rotation cadence; the next `terraform apply`
# in each environment will pick up the new values.
#
# Secrets created (per environment, in `staging`, `production`, and
# `production-bootstrap`):
#   TF_VAR_postgres_admin_password
#   TF_VAR_bff_db_password
#   TF_VAR_business_db_password
#   TF_VAR_keycloak_db_password
#   TF_VAR_keycloak_admin_password
#
# `production-bootstrap` mirrors `production`'s VALUES (same secret in
# both envs). The bootstrap environment is the unprotected sibling env
# that production's early Terraform jobs claim, so env-scoped secrets
# resolve there without widening the secret scope to the whole repository.
# Mirroring is necessary because env-scoped secrets cannot be shared
# across envs and Terraform validates the variable surface even on a
# targeted apply, so all `TF_VAR_*` must resolve in every job.
#
# Usage:
#   ./ai-scripts/00f-generate-db-secrets.sh --repo <owner>/<repo> [--dry-run]
#
# Dependencies: gh CLI >= 2.40, openssl.
# Authentication: `gh auth login` BEFORE running.
#
# Note on `gh secret set`: when --body is omitted the value is read
# from stdin. Do NOT pass `--body -` — that stores the literal string
# "-" (see commit 202e236).

set -euo pipefail

# ─── Argument parsing ──────────────────────────────────────────────────
REPO=""
DRY_RUN=0
# Each entry is `<primary>:<mirror1>[ <mirror2>...]`. The primary env
# gets a freshly generated value; every mirror in the same group gets
# the SAME value written to it, so the bootstrap and apply jobs see
# identical secrets. Keep each apply/bootstrap environment pair in the
# same group — diverging the values would break the bootstrap job the
# moment Terraform tries to plan against the full root module
# (validation reads vars before honoring -target).
ENV_GROUPS=(
  "staging:"
  "production:production-bootstrap"
)

# Print the script's header comment (lines 2-25) as usage, then exit.
# Arg $1: exit code (default 1).
usage() {
  sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-1}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)    REPO="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown flag: $1" >&2; usage ;;
  esac
done

[[ -z "${REPO}" ]] && { echo "--repo <owner/name> is required" >&2; usage; }

# ─── Dependency checks ─────────────────────────────────────────────────
for bin in gh openssl; do
  command -v "${bin}" >/dev/null || { echo "Missing dependency: ${bin}" >&2; exit 2; }
done

gh auth status >/dev/null 2>&1 || { echo "Run \`gh auth login\` first." >&2; exit 2; }

# Smoke-test repo access — surfaces missing scopes / SSO authorization
# now, instead of mid-loop with a 403 from `gh secret set`.
gh api "repos/${REPO}" >/dev/null 2>&1 \
  || { echo "Cannot read ${REPO}: missing token scope or repo not found." >&2; exit 2; }

# Flatten ENV_GROUPS into the full list of envs that must exist
# before any `gh secret set` runs. `00d-bootstrap-azure.sh` creates
# `staging`, `production`, and `production-bootstrap` — running 00f
# against a fresh repo without 00d would otherwise 404 mid-loop and
# leave the secret store half-populated.
ALL_ENVS=()
for group in "${ENV_GROUPS[@]}"; do
  primary="${group%%:*}"
  mirrors="${group#*:}"
  ALL_ENVS+=("${primary}")
  IFS=' ' read -ra mirror_arr <<<"${mirrors}"
  for m in "${mirror_arr[@]}"; do
    [[ -n "${m}" ]] && ALL_ENVS+=("${m}")
  done
done
for env in "${ALL_ENVS[@]}"; do
  gh api "repos/${REPO}/environments/${env}" >/dev/null 2>&1 \
    || { echo "Environment '${env}' does not exist on ${REPO}. Run 00d-bootstrap-azure.sh first." >&2; exit 2; }
done

# ─── Secret generation ─────────────────────────────────────────────────
SECRETS=(
  TF_VAR_postgres_admin_password
  TF_VAR_bff_db_password
  TF_VAR_business_db_password
  TF_VAR_keycloak_db_password
  TF_VAR_keycloak_admin_password
)

echo "▸ repo         : ${REPO}"
echo "▸ env groups   : ${ENV_GROUPS[*]}"
echo "▸ secrets      :"
printf '    - %s\n' "${SECRETS[@]}"
[[ "${DRY_RUN}" == "1" ]] && echo "▸ mode         : dry-run (no secrets will be written)"
echo

for group in "${ENV_GROUPS[@]}"; do
  primary="${group%%:*}"
  mirrors="${group#*:}"
  group_envs=("${primary}")
  IFS=' ' read -ra mirror_arr <<<"${mirrors}"
  for m in "${mirror_arr[@]}"; do
    [[ -n "${m}" ]] && group_envs+=("${m}")
  done
  echo "▸ env group: ${group_envs[*]}  (one shared value per secret)"
  for name in "${SECRETS[@]}"; do
    if [[ "${DRY_RUN}" == "1" ]]; then
      echo "  [dry-run] would set ${name} in: ${group_envs[*]}"
      continue
    fi
    # Generate the value ONCE per secret per group, then write the
    # same value to every env in the group. Splitting envs to a
    # mid-loop call (rather than rerunning `openssl rand` per env)
    # is what keeps the apply/bootstrap environment pairs in lockstep.
    #
    # `openssl rand -hex 32` gives 256 bits of entropy in a URL-safe
    # alphabet (no `+`, `/`, `=`). These secrets land in JDBC URLs
    # (`spring.datasource.url`) and Keycloak DB env vars where `+` is
    # decoded to space and `/` is a path separator — base64 would
    # break ~25% of generated values intermittently.
    val=$(openssl rand -hex 32)
    for env in "${group_envs[@]}"; do
      # `gh secret set` reads from stdin when --body is omitted.
      # Do NOT use `--body -` — that stores the literal string "-".
      printf '%s' "${val}" \
        | gh secret set "${name}" --env "${env}" --repo "${REPO}"
    done
    echo "  ✓ ${name}"
  done
done

echo
if [[ "${DRY_RUN}" == "1" ]]; then
  echo "✓ Dry-run complete. No secrets were written."
else
  echo "✓ Done. ${#SECRETS[@]} secrets written to ${#ALL_ENVS[@]} environments."
fi
