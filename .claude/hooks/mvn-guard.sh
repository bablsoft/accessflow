#!/usr/bin/env bash
# mvn-guard.sh — PreToolUse (Bash). This repo has no Maven wrapper; ./mvnw is
# guaranteed to fail. Blocking costs nothing and replaces a confusing
# "no such file or directory" with the correct command.
#
# Kept as a permanent regression guard: CLAUDE.md, README.md, docs/11 and the
# skills were all corrected, but the muscle memory is widespread.

set -uo pipefail

STDIN_JSON=""
[ ! -t 0 ] && STDIN_JSON="$(cat)"
CMD=""
if [ -n "$STDIN_JSON" ] && command -v jq >/dev/null 2>&1; then
  CMD="$(printf '%s' "$STDIN_JSON" | jq -r '.tool_input.command // empty' 2>/dev/null)"
fi
[ -z "$CMD" ] && CMD="${1:-}"
[ -z "$CMD" ] && exit 0

case "$CMD" in
  *./mvnw*|*mvnw\ *) ;;
  *) exit 0 ;;
esac

cat >&2 <<'EOF'
BLOCKED by mvn-guard: this repo has no Maven wrapper.

There is no ./mvnw anywhere in the tree; CI uses plain `mvn -B`. Translations:

  ./mvnw verify              ->  mvn -f backend/pom.xml verify
  ./mvnw verify -Pcoverage   ->  mvn -B -f backend/pom.xml verify -Pcoverage
  ./mvnw test -Dtest=X       ->  mvn -q -f backend/pom.xml test -Dtest=X
  ./mvnw spring-boot:run     ->  mvn -f backend/pom.xml spring-boot:run

  engines:  mvn -f backend/pom.xml install -DskipTests   # publish the plain jar first
            mvn -f engines/<id>/pom.xml clean verify
EOF
exit 1
