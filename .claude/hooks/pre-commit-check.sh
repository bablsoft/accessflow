#!/usr/bin/env bash
# pre-commit-check.sh — PreToolUse (Bash), fired only when the command contains `git commit`.
#
# Text-only checks over the staged set. Deliberately NO compilation: `mvn verify` is minutes and
# belongs to the agent's explicit verify step, not to every commit.
#
# This is the "same commit set" backstop. AccessFlow has several rules of the form "change A and
# B together"; each is invisible until CI, and some (the engine pin) leave the repo in a state
# where the connector catalog points at a jar whose hash does not match.
#
# See .claude/patterns/README.md

set -uo pipefail
. "$(dirname "$0")/_common.sh"
hook_repo_root
cd "$REPO_ROOT" || exit 0
REL="staged changes"

STAGED="$(git diff --cached --name-only --diff-filter=ACMR 2>/dev/null || true)"
[ -z "$STAGED" ] && exit 0
staged() { printf '%s\n' "$STAGED" | grep -qE "$1"; }

# ---- BLOCK: things that must never be committed -------------------------------
staged '^\.claude/settings\.local\.json$' \
  && block ".claude/settings.local.json is staged — it is per-developer and gitignored. Unstage it: git restore --staged .claude/settings.local.json"
staged '(^|/)\.env$|(^|/)\.env\.[a-z]+$' \
  && block "a .env file is staged — secrets do not belong in the repo"
staged '\.(pem|p12|jks|key)$' \
  && block "a key/certificate file is staged ($(printf '%s\n' "$STAGED" | grep -E '\.(pem|p12|jks|key)$' | head -1))"

# ---- WARN: same-commit-set parity ---------------------------------------------

# i18n: a new backend key must land in all six locale files.
if staged '^backend/src/main/resources/i18n/messages\.properties$'; then
  NEWKEYS="$(git diff --cached -U0 -- backend/src/main/resources/i18n/messages.properties \
             | grep -E '^\+[a-z]' | sed 's/^+//;s/=.*//' || true)"
  if [ -n "$NEWKEYS" ]; then
    for loc in de es fr hy ru zh_CN; do
      F="backend/src/main/resources/i18n/messages_${loc}.properties"
      MISSING=""
      while IFS= read -r k; do
        [ -z "$k" ] && continue
        grep -qE "^${k}[[:space:]]*=" "$F" 2>/dev/null || MISSING="${MISSING}${k} "
      done <<EOF
$NEWKEYS
EOF
      [ -n "$MISSING" ] && warn "messages_${loc}.properties is missing: ${MISSING}— MessagesParityTest will fail"
    done
  fi
fi

# i18n: same rule on the frontend side.
if staged '^frontend/src/locales/en\.json$'; then
  for loc in de es fr hy ru zh-CN; do
    staged "^frontend/src/locales/${loc}\.json$" \
      || warn "frontend/src/locales/en.json is staged but ${loc}.json is not — locales.parity.test.ts will fail"
  done
fi

# Engine version bump and its SHA pin are one commit, always.
for pom in $(printf '%s\n' "$STAGED" | grep -E '^engines/[^/]+/pom\.xml$' || true); do
  id="$(printf '%s' "$pom" | cut -d/ -f2)"
  if git diff --cached -U0 -- "$pom" | grep -qE '^\+.*<version>'; then
    staged "^connectors/${id}/connector\.json$" \
      || warn "engines/${id}/pom.xml has a version change but connectors/${id}/connector.json is not staged — re-pin url/fileName/sha256 in the same commit or check-engine-pins.mjs fails CI"
  fi
done

# Website edits carry their sitemap bump.
staged '^website/.*\.html$' && ! staged '^website/sitemap\.xml$' \
  && warn "website/*.html is staged without website/sitemap.xml — bump <lastmod> (and the JSON-LD dateModified) for every page you touched"

# A migration that adds an enum value needs its sidecar.
for sql in $(printf '%s\n' "$STAGED" | grep -E '^backend/src/main/resources/db/migration/V[0-9]+__.*\.sql$' || true); do
  if git show ":$sql" 2>/dev/null | grep -qiE 'ALTER[[:space:]]+TYPE.*ADD[[:space:]]+VALUE'; then
    staged "^${sql}\.conf$" || [ -f "${sql}.conf" ] \
      || warn "$(basename "$sql") adds an enum value but ${sql##*/}.conf is not staged — Flyway needs executeInTransaction=false"
  fi
done

# Duplicate migration versions in one commit.
DUP="$(printf '%s\n' "$STAGED" | grep -oE 'db/migration/V[0-9]+__' | sed 's/.*V\([0-9]*\)__/\1/' | sort | uniq -d || true)"
[ -n "$DUP" ] && warn "two staged migrations share version V${DUP}"

# New user-facing frontend flow without an e2e spec.
staged '^frontend/src/(pages|components)/' && ! staged '^e2e/tests/' \
  && warn "frontend pages/components staged with no e2e/tests/ change — the default is to add or update a spec (.claude/patterns/e2e-spec.md)"

# Branch naming.
BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '')"
case "$BRANCH" in
  main|HEAD) warn "committing directly on $BRANCH — cut a feature/AF-<n>-<slug> branch first" ;;
  feature/AF-*|fix/AF-*|chore/AF-*|hotfix/AF-*|dependabot/*) ;;
  *) warn "branch '$BRANCH' does not match {feature,fix,chore,hotfix}/AF-<n>-<description>" ;;
esac

hook_finish "pre-commit-check" "Reference: .claude/patterns/README.md"
