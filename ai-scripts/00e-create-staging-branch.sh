#!/usr/bin/env bash
# Creates the long-lived `staging` branch that triggers
# .github/workflows/deploy-staging.yml on every push.
#
# Idempotent:
#   - if `staging` already exists on origin → no-op (just confirms tracking).
#   - if it exists locally but not on origin → pushes it.
#   - otherwise creates it from the current origin/main tip and pushes.
#
# Does NOT switch your working branch. Run from any branch with a clean tree.
#
# Usage:
#   ./ai-scripts/00e-create-staging-branch.sh
#   BASE=main REMOTE=origin ./ai-scripts/00e-create-staging-branch.sh
set -euo pipefail

REMOTE="${REMOTE:-origin}"
BASE="${BASE:-main}"
BRANCH="${BRANCH:-staging}"

# Refuse to operate on a dirty tree — creating staging from a half-staged
# state would surprise the user and may push unrelated WIP if they later
# `git push staging`.
if [[ -n "$(git status --porcelain)" ]]; then
  echo "✗ Working tree is dirty. Commit or stash, then re-run." >&2
  git status --short >&2
  exit 1
fi

echo "▸ Fetching ${REMOTE}…"
git fetch --quiet "${REMOTE}"

# Case 1: already on the remote → nothing to do.
if git ls-remote --exit-code --heads "${REMOTE}" "${BRANCH}" >/dev/null 2>&1; then
  echo "▸ ${REMOTE}/${BRANCH} already exists — no-op."
  # Make sure a local tracking ref exists so `git push` from staging works
  # without re-specifying upstream every time.
  if ! git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
    git branch --quiet --track "${BRANCH}" "${REMOTE}/${BRANCH}"
    echo "▸ Created local tracking branch ${BRANCH} → ${REMOTE}/${BRANCH}."
  fi
  exit 0
fi

# Case 2: local-only branch → push it and set upstream.
if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
  echo "▸ Local ${BRANCH} exists but not on ${REMOTE}. Pushing…"
  git push --set-upstream "${REMOTE}" "${BRANCH}"
  exit 0
fi

# Case 3: branch doesn't exist anywhere → create from latest origin/BASE.
# We deliberately base off ${REMOTE}/${BASE} (not local ${BASE}) so the
# staging branch starts from what's actually on the remote, not whatever
# uncommitted-but-tracked work the user has locally.
echo "▸ Creating ${BRANCH} from ${REMOTE}/${BASE}…"
git branch --quiet "${BRANCH}" "${REMOTE}/${BASE}"
git push --set-upstream "${REMOTE}" "${BRANCH}"
echo "✓ ${REMOTE}/${BRANCH} created at $(git rev-parse --short "${REMOTE}/${BASE}")."
echo "  Pushes to this branch trigger .github/workflows/deploy-staging.yml."
