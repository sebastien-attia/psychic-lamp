#!/usr/bin/env bash
# ai-scripts/checks/_lib.sh
# Shared helpers for per-phase verification scripts and run-phase.sh checkpoints.
#
# Usage inside a checks/<phase>/run.sh:
#
#   source "$(dirname "${BASH_SOURCE[0]}")/../_lib.sh"
#   check_init "02a4" "Backend Auth"
#   pass "bff compiles"
#   warn "axe scan deferred"
#   fail "client_secret appeared in BFF logs"
#   info "integration deferred to phase 3"
#   check_summary

set -u

# ── Colours (fall back to no-op if not a TTY) ────────────────────────────────
if [ -t 1 ]; then
  _RED='\033[0;31m'; _GREEN='\033[0;32m'; _YELLOW='\033[1;33m'
  _BLUE='\033[0;34m'; _CYAN='\033[0;36m'; _NC='\033[0m'; _BOLD='\033[1m'
else
  _RED=''; _GREEN=''; _YELLOW=''; _BLUE=''; _CYAN=''; _NC=''; _BOLD=''
fi

# ── Severity tallies; also written to a result file for run_checkpoint ───────
_PASS_COUNT=0
_WARN_COUNT=0
_FAIL_COUNT=0
_INFO_COUNT=0
_CHECK_PHASE=""
_CHECK_RESULT_FILE=""

# check_init <phase-id> [<phase-title>]
# Redirects structured results to $CHECK_RESULT_FILE when set by run_checkpoint,
# so run-phase.sh can re-render the lines in its yellow box.
check_init() {
  _CHECK_PHASE="${1:-unknown}"
  local title="${2:-$_CHECK_PHASE}"
  _CHECK_RESULT_FILE="${CHECK_RESULT_FILE:-}"
  if [ -n "$_CHECK_RESULT_FILE" ]; then : > "$_CHECK_RESULT_FILE"; fi
  printf '%b\n' "${_CYAN}▸ Verification — phase ${_BOLD}${title}${_NC}"
}

_emit() {
  # _emit <severity> <glyph> <color> <msg>
  local sev="$1" glyph="$2" color="$3" msg="$4"
  printf '  %b%s%b %s\n' "$color" "$glyph" "$_NC" "$msg"
  if [ -n "$_CHECK_RESULT_FILE" ]; then
    printf '%s\t%s\n' "$sev" "$msg" >> "$_CHECK_RESULT_FILE"
  fi
}

pass() { _PASS_COUNT=$((_PASS_COUNT+1)); _emit pass "✓" "$_GREEN"  "$1"; }
warn() { _WARN_COUNT=$((_WARN_COUNT+1)); _emit warn "⚠" "$_YELLOW" "$1"; }
fail() { _FAIL_COUNT=$((_FAIL_COUNT+1)); _emit fail "✗" "$_RED"    "$1"; }
info() { _INFO_COUNT=$((_INFO_COUNT+1)); _emit info "·" "$_CYAN"   "$1"; }

# run_check "<description>" -- <command> [args...]
# Runs the command; PASS on exit 0, FAIL otherwise. Stderr/stdout captured and
# printed indented on failure.
run_check() {
  local desc="$1"; shift
  [ "${1:-}" = "--" ] && shift
  local out
  if out="$("$@" 2>&1)"; then
    pass "$desc"
  else
    fail "$desc"
    printf '%b' "$out" | sed 's/^/      /'
  fi
}

# check_review_policy
# Verifies the project-wide code-review policy survived this phase:
#   - .claude/agents/code-reviewer.md exists with the right frontmatter
#   - CLAUDE.md still has the "Code review policy" section
#   - .claude/rules/code-review.md is in place (auto-load)
# Soft-warns when run from an empty workspace (no .claude/ yet) so the
# helper is safe to call before phase 0 has run.
check_review_policy() {
  # Phase-0 hasn't run yet if .claude/rules/ doesn't exist (the harness's own
  # .claude/ may exist with only settings.local.json — that's not bootstrap).
  if [ ! -d .claude/rules ]; then
    info "code-review policy: bootstrap (.claude/rules/) not present yet — skipping"
    return 0
  fi
  if [ -f .claude/agents/code-reviewer.md ] \
     && grep -q '^name: code-reviewer$' .claude/agents/code-reviewer.md; then
    pass "code-review policy: .claude/agents/code-reviewer.md present"
  else
    fail "code-review policy: .claude/agents/code-reviewer.md missing or malformed (restore via ai-scripts/00-bootstrap.sh)"
  fi
  if [ -f CLAUDE.md ] && grep -q '^## Code review policy' CLAUDE.md; then
    pass "code-review policy: CLAUDE.md '## Code review policy' section present"
  else
    fail "code-review policy: CLAUDE.md missing '## Code review policy' section (restore via ai-scripts/00-bootstrap.sh)"
  fi
  if [ -f .claude/rules/code-review.md ]; then
    pass "code-review policy: .claude/rules/code-review.md auto-load rule present"
  else
    warn "code-review policy: .claude/rules/code-review.md missing (auto-load reminder will not fire)"
  fi
}

# Exit status reflects hard failures (severity=fail). 0 = ok, 1 = hard-fail.
# Soft warnings do NOT fail the script.
check_summary() {
  printf '  %b──%b %d ok, %d warn, %d fail, %d info\n' \
    "$_CYAN" "$_NC" "$_PASS_COUNT" "$_WARN_COUNT" "$_FAIL_COUNT" "$_INFO_COUNT"
  if [ "$_FAIL_COUNT" -gt 0 ]; then return 1; fi
  return 0
}

# Resolve the path of a phase's human.md (non-automatable checklist).
# Prints the absolute path to stdout, or empty if the file is missing.
resolve_human_checklist() {
  local phase="$1"
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  local p="${here}/${phase}/human.md"
  [ -f "$p" ] && printf '%s' "$p"
}
