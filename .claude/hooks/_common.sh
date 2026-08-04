#!/usr/bin/env bash
# _common.sh — shared plumbing for the AccessFlow PreToolUse hooks.
#
# Sourced, never executed directly. Provides:
#   hook_read_target   -> sets TARGET (abs path), REL (repo-relative), CONTENT
#   hook_repo_root     -> sets REPO_ROOT
#   warn / block       -> accumulate findings
#   hook_finish <name> -> prints and exits 0 (warn) or 1 (block)
#
# Stdin contract: JSON {tool_input:{file_path, content|new_string}}. A bare $1
# path is honoured for back-compat and for manual testing.
#
# ENFORCEMENT: blocks are advisory until ACCESSFLOW_HOOKS_ENFORCE=1. Ship a rule
# warn-only, measure its false-positive rate on real work, then turn it on. To
# bypass every hook for one session, put "hooks": {} in
# .claude/settings.local.json (local settings win over the tracked settings.json).

hook_repo_root() {
  REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
}

hook_read_target() {
  local stdin_json=""
  [ ! -t 0 ] && stdin_json="$(cat)"
  TARGET=""; CONTENT=""
  if [ -n "$stdin_json" ] && command -v jq >/dev/null 2>&1; then
    TARGET="$(printf '%s' "$stdin_json"  | jq -r '.tool_input.file_path // empty' 2>/dev/null)"
    CONTENT="$(printf '%s' "$stdin_json" | jq -r '.tool_input.content // .tool_input.new_string // empty' 2>/dev/null)"
  fi
  [ -z "$TARGET" ] && TARGET="${1:-}"
  [ -z "$TARGET" ] && exit 0
  hook_repo_root
  REL="${TARGET#"$REPO_ROOT"/}"
}

WARNINGS=""
BLOCKS=""
warn()  { WARNINGS="${WARNINGS}  - ${1}"$'\n'; }
block() { BLOCKS="${BLOCKS}  - ${1}"$'\n'; }

# hook_finish <hook-name> [reference line]
hook_finish() {
  local name="$1" ref="${2:-}"
  local enforce="${ACCESSFLOW_HOOKS_ENFORCE:-0}"

  if [ -n "$BLOCKS" ] && [ "$enforce" = "1" ]; then
    { printf 'BLOCKED by %s: %s\n\n%s\n' "$name" "$REL" "$BLOCKS"
      [ -n "$WARNINGS" ] && printf 'Also (non-blocking):\n\n%s\n' "$WARNINGS"
      [ -n "$ref" ] && printf '%s\n' "$ref"
    } >&2
    exit 1
  fi

  if [ -n "$BLOCKS" ] || [ -n "$WARNINGS" ]; then
    { printf '%s — %s\n\n' "$name" "$REL"
      [ -n "$BLOCKS" ] && printf 'WOULD BLOCK (advisory until ACCESSFLOW_HOOKS_ENFORCE=1):\n\n%s\n' "$BLOCKS"
      [ -n "$WARNINGS" ] && printf '%s\n' "$WARNINGS"
      [ -n "$ref" ] && printf '%s\n' "$ref"
    } >&2
  fi
  exit 0
}
