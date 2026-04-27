#!/usr/bin/env bash
# Generate fresh random database secrets and store them as REPOSITORY
# secrets on the GitHub repo. Run ONCE during initial setup, then re-run
# only when rotating.
#
# Each invocation overwrites the existing values — the script is
# destructive in the sense that previously stored secrets cannot be
# recovered. After rotation, the next `terraform apply` will pick up
# the new values.
#
# Secrets created (repository scope — visible to every workflow run):
#   TF_VAR_POSTGRES_ADMIN_PASSWORD
#   TF_VAR_BFF_DB_PASSWORD
#   TF_VAR_BUSINESS_DB_PASSWORD
#   TF_VAR_KEYCLOAK_DB_PASSWORD
#   TF_VAR_KEYCLOAK_ADMIN_PASSWORD
#
# Repository scope (not environment scope) is the chosen layout because
# the deploy workflows have jobs that need TF_VAR_* but cannot declare
# `environment:` (build-push pre-pushes images BEFORE the production
# reviewer gate). See .github/ENVIRONMENTS.md for the rationale.
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

# Print the script's header comment (lines 2-30) as usage, then exit.
# Arg $1: exit code (default 1).
usage() {
  sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'
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

# ─── Secret generation ─────────────────────────────────────────────────
SECRETS=(
  TF_VAR_POSTGRES_ADMIN_PASSWORD
  TF_VAR_BFF_DB_PASSWORD
  TF_VAR_BUSINESS_DB_PASSWORD
  TF_VAR_KEYCLOAK_DB_PASSWORD
  TF_VAR_KEYCLOAK_ADMIN_PASSWORD
)

echo "▸ repo    : ${REPO}"
echo "▸ scope   : repository (no --env)"
echo "▸ secrets :"
printf '    - %s\n' "${SECRETS[@]}"
[[ "${DRY_RUN}" == "1" ]] && echo "▸ mode    : dry-run (no secrets will be written)"
echo

for name in "${SECRETS[@]}"; do
  if [[ "${DRY_RUN}" == "1" ]]; then
    echo "  [dry-run] would set ${name}"
    continue
  fi
  # `gh secret set` reads from stdin when --body is omitted.
  # Do NOT use `--body -` — that stores the literal string "-".
  #
  # `openssl rand -hex 32` gives 256 bits of entropy in a URL-safe
  # alphabet (no `+`, `/`, `=`). These secrets land in JDBC URLs
  # (`spring.datasource.url`) and Keycloak DB env vars where `+` is
  # decoded to space and `/` is a path separator — base64 would
  # break ~25% of generated values intermittently.
  openssl rand -hex 32 \
    | gh secret set "${name}" --repo "${REPO}"
  echo "  ✓ ${name}"
done

echo
if [[ "${DRY_RUN}" == "1" ]]; then
  echo "✓ Dry-run complete. No secrets were written."
else
  echo "✓ Done. ${#SECRETS[@]} secrets written to ${REPO}."
  echo
  echo "Reminder: also set AZURE_CLIENT_OBJECT_ID once (not handled by this script):"
  echo "  SP_OBJECT_ID=\$(az ad sp show --id \"\$AZURE_CLIENT_ID\" --query id -o tsv)"
  echo "  gh secret set AZURE_CLIENT_OBJECT_ID --body \"\$SP_OBJECT_ID\" --repo ${REPO}"
fi
