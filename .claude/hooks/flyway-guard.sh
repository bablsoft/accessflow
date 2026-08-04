#!/usr/bin/env bash
# flyway-guard.sh — PreToolUse (Write|Edit) on backend/src/main/resources/db/migration/.
#
# AccessFlow's one truly irreversible edit. Flyway stores a checksum per applied
# migration: changing a file that already shipped makes every existing deployment
# fail at startup, with no rollback. "Already shipped" == the file exists at the
# merge-base with the default branch; a migration you created on this branch is
# absent there, so iterating on it is fine.
#
# See .claude/patterns/jpa-entity-migration.md

set -uo pipefail
. "$(dirname "$0")/_common.sh"
hook_read_target "$@"

case "$REL" in
  backend/src/main/resources/db/migration/*) ;;
  *) exit 0 ;;
esac

MIGDIR="backend/src/main/resources/db/migration"
cd "$REPO_ROOT" || exit 0
BASENAME="$(basename "$REL")"

next_version() {
  local max
  max=$(ls "$MIGDIR" 2>/dev/null | sed -n 's/^V\([0-9]\{1,\}\)__.*/\1/p' | sort -rn | head -1)
  echo $(( ${max:-0} + 1 ))
}

# ---- Rule 1 (BLOCK): the file already exists at the merge-base -----------------
BASE=""
for ref in origin/main main origin/HEAD; do
  if git rev-parse --verify --quiet "$ref" >/dev/null 2>&1; then
    BASE="$(git merge-base HEAD "$ref" 2>/dev/null || true)"
    [ -n "$BASE" ] && break
  fi
done
# No merge-base (detached HEAD, shallow clone, no origin) -> fall through to warn.
if [ -n "$BASE" ] && git cat-file -e "$BASE:$REL" 2>/dev/null; then
  block "$BASENAME is a RELEASED migration — it exists at the merge-base with the default branch, so it has been applied in real environments. Flyway checksums applied migrations; editing this file makes every existing deployment fail at startup, with no rollback. Write a new forward migration instead: $MIGDIR/V$(next_version)__<snake_case_description>.sql"
fi

# ---- Rules 2-5 (WARN): shape of a new migration -------------------------------
case "$BASENAME" in
  *.sql.conf)
    printf '%s' "$CONTENT" | grep -q 'executeInTransaction=false' \
      || warn "a .sql.conf sidecar normally exists only to set executeInTransaction=false"
    ;;
  V*__*.sql) ;;
  *) warn "filename '$BASENAME' does not match V{n}__{snake_case}.sql (double underscore)" ;;
esac

if [ "${BASENAME##*.}" = "sql" ]; then
  MAX=$(ls "$MIGDIR" 2>/dev/null | sed -n 's/^V\([0-9]\{1,\}\)__.*/\1/p' | sort -rn | head -1)
  MINE=$(printf '%s' "$BASENAME" | sed -n 's/^V\([0-9]\{1,\}\)__.*/\1/p')
  if [ -n "$MINE" ] && [ -n "$MAX" ] && [ "$MINE" -le "$MAX" ] && [ ! -f "$MIGDIR/$BASENAME" ]; then
    warn "V$MINE collides with an existing version (highest is V$MAX) — use V$((MAX+1))"
  fi

  if [ -n "$CONTENT" ]; then
    if printf '%s' "$CONTENT" | grep -qiE 'ALTER[[:space:]]+TYPE.*ADD[[:space:]]+VALUE' \
       && [ ! -f "$MIGDIR/${BASENAME}.conf" ]; then
      warn "ALTER TYPE ... ADD VALUE requires a sidecar ${BASENAME}.conf containing executeInTransaction=false — Postgres cannot add an enum value inside a transaction, and Flyway wraps migrations in one (see V126__add_databricks_db_type.sql.conf)"
    fi
    if printf '%s' "$CONTENT" | grep -qiE 'ADD[[:space:]]+COLUMN' \
       && ! printf '%s' "$CONTENT" | grep -qiE 'DEFAULT|NULL'; then
      warn "ADD COLUMN with neither DEFAULT nor NULL — breaks zero-downtime deploys (fails on a non-empty table, and old pods still insert without the column)"
    fi
    if ! printf '%s' "$CONTENT" | head -1 | grep -qE '^[[:space:]]*--'; then
      warn "first line should be a '-- <issue>: <what and why>' comment (see V129__create_discovery.sql)"
    fi
  fi
fi

hook_finish "flyway-guard" "Reference: .claude/patterns/jpa-entity-migration.md"
