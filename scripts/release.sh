#!/usr/bin/env bash
#
# scripts/release.sh — Cut a release of the current SNAPSHOT.
#
# What it does, in order:
#   1.  Read the current <revision> from the root pom.xml (single source of
#       truth for every Java artifact in this repo).
#   2.  Strip "-SNAPSHOT" to derive the release version (or override via --as).
#   3.  Update <revision> to the release version, commit
#       "chore(release): vX.Y.Z" and create an annotated git tag vX.Y.Z.
#   4.  Bump <revision> to the next snapshot (auto-incremented patch by
#       default; --next overrides), commit "chore(version): start ...".
#   5.  Push the branch + tag atomically (--atomic), then `gh release create
#       vX.Y.Z` which fires deploy-production.yml on the release:published
#       event.
#
# If push or gh release fails the script rolls back to the pre-release HEAD
# and deletes the local tag, so you never end up half-released.
#
# Usage:
#   scripts/release.sh                         # 0.1.0-SNAPSHOT → release v0.1.0, next 0.1.1-SNAPSHOT
#   scripts/release.sh --next 0.2.0            #                                  next 0.2.0-SNAPSHOT
#   scripts/release.sh --as 0.1.5              # release v0.1.5 (override the SNAPSHOT base), next 0.1.6-SNAPSHOT
#   scripts/release.sh --as 1.0.0 --next 1.1.0 # release v1.0.0,                     next 1.1.0-SNAPSHOT
#   scripts/release.sh --dry-run               # print every step, no writes / pushes
#
# Re-running a deploy on the SAME release (without a new bump) is a separate
# operation — see README "Restarting a release deploy". TL;DR:
#   gh workflow run deploy-production.yml --ref main -f tag=v0.1.0
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT_POM="${REPO_ROOT}/pom.xml"

# Help text. Kept in a heredoc (rather than sed -n on this script's header)
# so reordering the docstring above does not silently truncate `--help`.
usage() {
    cat <<'EOF'
scripts/release.sh — Cut a release of the current SNAPSHOT.

Usage:
  scripts/release.sh                         # patch bump
  scripts/release.sh --next 0.2.0            # explicit next snapshot (minor/major)
  scripts/release.sh --as 0.1.5              # release a different version than the SNAPSHOT base
  scripts/release.sh --as 1.0.0 --next 1.1.0 # both
  scripts/release.sh --dry-run [--next …] [--as …]
  scripts/release.sh -h | --help

The current <revision> in the root pom.xml is the single source of truth.
Defaults: release = <revision> with -SNAPSHOT stripped; next snapshot = patch+1.
EOF
    exit "${1:-0}"
}

log()  { printf '▸ %s\n' "$*"; }
warn() { printf '⚠ %s\n' "$*" >&2; }
die()  { printf '✗ %s\n' "$*" >&2; exit 1; }

# ── Args ──────────────────────────────────────────────────────────────────
NEXT_ARG=""
AS_ARG=""
DRY_RUN=0

while [ $# -gt 0 ]; do
    case "$1" in
        --next)     NEXT_ARG="${2:?--next requires a value (e.g. 0.2.0)}"; shift 2 ;;
        --as)       AS_ARG="${2:?--as requires a value (e.g. 0.1.5)}";    shift 2 ;;
        --dry-run)  DRY_RUN=1; shift ;;
        -h|--help)  usage 0 ;;
        *)          warn "unknown argument: $1"; usage 1 ;;
    esac
done

# ── Helpers ───────────────────────────────────────────────────────────────

# Read the current <revision> from the root pom. We grep on the first
# occurrence inside <properties>; the root pom only declares <revision> once
# (this is the discipline — never add a second declaration in a profile),
# so the head -n1 is unambiguous. Trims surrounding whitespace.
current_revision() {
    sed -n 's|^[[:space:]]*<revision>\([^<]*\)</revision>.*|\1|p' "$ROOT_POM" | head -n1 | tr -d '[:space:]'
}

# Validate the *general* shape (X.Y.Z, optionally with -SUFFIX). Used on the
# user-facing inputs --as and --next.
valid_version() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]]
}

# Validate a *plain* SemVer triple (no suffix). Used for the bump_patch
# input so 1.2.3-rc1 cannot reach `$((patch + 1))` and explode at runtime
# under set -e.
plain_semver() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
}

# Auto-bump the patch component. 0.1.0 → 0.1.1, 1.2.9 → 1.2.10. Refuses
# pre-release suffixes — pass --next explicitly in that case.
bump_patch() {
    local v="$1"
    plain_semver "$v" || die "cannot patch-bump '${v}' (has a suffix?). Use --next X.Y.Z explicitly."
    IFS='.' read -r major minor patch <<<"$v"
    printf '%s.%s.%s' "$major" "$minor" "$((patch + 1))"
}

# In-place rewrite of <revision> in the root pom. macOS sed and GNU sed
# disagree on -i; the temp-file dance works on both.
set_revision() {
    local new="$1"
    local tmp
    tmp="$(mktemp)"
    sed "s|<revision>[^<]*</revision>|<revision>${new}</revision>|" "$ROOT_POM" > "$tmp"
    if ! grep -q "<revision>${new}</revision>" "$tmp"; then
        rm -f "$tmp"
        die "set_revision: failed to write <revision>${new}</revision> into $ROOT_POM"
    fi
    mv "$tmp" "$ROOT_POM"
}

# Run a command (or print it under --dry-run). Direct argv expansion — NO
# eval. Callers pass the command as separate words; commit messages and
# other quoted strings stay intact regardless of their contents.
run() {
    if [ "$DRY_RUN" -eq 1 ]; then
        printf '   [dry-run] %s\n' "$*"
    else
        "$@"
    fi
}

# ── Preflight ─────────────────────────────────────────────────────────────
[ -f "$ROOT_POM" ] || die "root pom.xml not found at $ROOT_POM"

if ! command -v gh >/dev/null; then
    die "gh CLI not installed — needed to create the GitHub release that fires deploy-production.yml"
fi

if [ "$DRY_RUN" -eq 0 ] && ! gh auth status >/dev/null 2>&1; then
    die "gh CLI not authenticated — run 'gh auth login' first"
fi

cd "$REPO_ROOT"

if ! git diff --quiet || ! git diff --cached --quiet; then
    die "working tree is dirty — commit or stash first"
fi

CURRENT_BRANCH="$(git branch --show-current)"
case "$CURRENT_BRANCH" in
    main|staging) ;;
    *) die "releases must be cut from 'main' or 'staging' (current: ${CURRENT_BRANCH})" ;;
esac

# Refuse to release a stale local branch — the released JAR would not contain
# the latest commits the team thinks it does. Only fast-forwardness is
# required: being ahead of origin is fine (the new commits go out with the
# release).
if [ "$DRY_RUN" -eq 0 ]; then
    git fetch --quiet origin "$CURRENT_BRANCH" || die "failed to fetch origin/${CURRENT_BRANCH}"
    if ! git merge-base --is-ancestor "origin/${CURRENT_BRANCH}" HEAD; then
        die "local '${CURRENT_BRANCH}' is behind origin/${CURRENT_BRANCH} — pull first"
    fi
fi

CURRENT="$(current_revision)"
[ -n "$CURRENT" ] || die "could not read <revision> from $ROOT_POM"
log "current revision: ${CURRENT}"

if [[ "$CURRENT" != *-SNAPSHOT ]]; then
    die "current <revision> '${CURRENT}' is not a SNAPSHOT — refusing to release a non-snapshot"
fi

# ── Derive release version + next snapshot ────────────────────────────────
RELEASE="${AS_ARG:-${CURRENT%-SNAPSHOT}}"
valid_version "$RELEASE" || die "release version '${RELEASE}' is not a valid SemVer triple"

NEXT_BASE="${NEXT_ARG:-$(bump_patch "$RELEASE")}"
valid_version "$NEXT_BASE" || die "next version '${NEXT_BASE}' is not a valid SemVer triple"
NEXT_SNAP="${NEXT_BASE}-SNAPSHOT"

TAG="v${RELEASE}"

if git rev-parse "refs/tags/${TAG}" >/dev/null 2>&1; then
    die "tag ${TAG} already exists — pick a different version with --as, or delete the tag"
fi

log "release version:  ${RELEASE}  →  tag ${TAG}"
log "next snapshot:    ${NEXT_SNAP}"
[ "$DRY_RUN" -eq 1 ] && warn "DRY RUN — no writes or pushes will happen"

# ── Cut the release ───────────────────────────────────────────────────────
ORIG_HEAD="$(git rev-parse HEAD)"

# 1. Release commit + tag.
if [ "$DRY_RUN" -eq 1 ]; then
    printf '   [dry-run] set <revision> to %s in %s\n' "$RELEASE" "$ROOT_POM"
else
    set_revision "$RELEASE"
fi
run git add "$ROOT_POM"
run git commit -m "chore(release): ${TAG}"
run git tag -a "${TAG}" -m "Release ${TAG}"

# 2. Next-snapshot commit.
if [ "$DRY_RUN" -eq 1 ]; then
    printf '   [dry-run] set <revision> to %s in %s\n' "$NEXT_SNAP" "$ROOT_POM"
else
    set_revision "$NEXT_SNAP"
fi
run git add "$ROOT_POM"
run git commit -m "chore(version): start ${NEXT_SNAP}"

# 3. Push branch + tag atomically — `--atomic` makes the receiving end
#    accept both refs or neither, so a tag-protection rejection (or a race
#    with a concurrent branch push) cannot leave the remote half-released.
if [ "$DRY_RUN" -eq 1 ]; then
    printf '   [dry-run] git push --atomic origin %s %s\n' "$CURRENT_BRANCH" "$TAG"
    printf '   [dry-run] gh release create %s --generate-notes --title %s\n' "$TAG" "$TAG"
    log "dry-run complete — no changes were made"
    exit 0
fi

if ! git push --atomic origin "$CURRENT_BRANCH" "$TAG"; then
    warn "atomic push failed — neither ref reached the remote"
    # `git reset --hard` is the project's "destructive" verb but it's
    # justified here: ORIG_HEAD was captured a few seconds ago on a
    # verified-clean tree by this same script, so the only commits being
    # discarded are the two commits the script itself just made. There is
    # no concurrent in-flight work to lose. Verify with `git ls-remote
    # origin` before re-running.
    git tag -d "${TAG}" || true
    git reset --hard "$ORIG_HEAD"
    die "push failed; local repo restored to ${ORIG_HEAD}"
fi

# 4. GitHub release — this is what fires deploy-production.yml.
if ! gh release create "${TAG}" --generate-notes --title "${TAG}"; then
    warn "gh release create failed — the tag was pushed but no release was published"
    warn "fix the underlying issue and re-run:"
    warn "    gh release create ${TAG} --generate-notes --title ${TAG}"
    exit 1
fi

log "✅ Released ${TAG}. deploy-production.yml will fire on release:published."
log "   To re-run the deploy on this same tag (no new release):"
log "     gh workflow run deploy-production.yml --ref main -f tag=${TAG}"
