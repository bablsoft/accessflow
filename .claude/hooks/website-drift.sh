#!/usr/bin/env bash
# website-drift.sh — PreToolUse (Write|Edit) on website/**.html. Warn only.
#
# Deliberately targets ONLY the half that frontend/src/config/__tests__/websitePages.test.ts
# does not cover. That test already checks shared nav/footer, canonicals, heading
# levels, duplicate ids, dead links, description length, sitemap membership, and that
# a page's three published modified dates AGREE with each other. What no test can
# check is whether that agreed date is TODAY — a synchronized-but-stale set passes CI.
# That is what rots silently.
#
# See .claude/patterns/website-drift.md

set -uo pipefail
. "$(dirname "$0")/_common.sh"
hook_read_target "$@"

case "$REL" in
  website/*.html|website/**/*.html) ;;
  *) exit 0 ;;
esac
[ -z "$CONTENT" ] && exit 0

cd "$REPO_ROOT" || exit 0
TODAY="$(date +%F)"

# --- JSON-LD dateModified freshness -------------------------------------------
STALE="$(printf '%s' "$CONTENT" | grep -oE '"dateModified"[[:space:]]*:[[:space:]]*"[0-9]{4}-[0-9]{2}-[0-9]{2}"' \
         | grep -v "$TODAY" | head -2 || true)"
if [ -n "$STALE" ]; then
  warn "JSON-LD dateModified is not today ($TODAY): $(printf '%s' "$STALE" | tr '\n' ' ') — bump it on every page you touch. No test catches this."
fi

# --- sitemap <lastmod> freshness ----------------------------------------------
SITEMAP="website/sitemap.xml"
if [ -f "$SITEMAP" ]; then
  CANON="$(printf '%s' "$CONTENT" | grep -oE '<link[^>]+rel="canonical"[^>]+href="[^"]+"' \
           | grep -oE 'href="[^"]+"' | head -1 | sed 's/href="//;s/"$//' || true)"
  if [ -n "$CANON" ]; then
    LM="$(awk -v loc="$CANON" '
      /<url>/ {u=""; lm=""}
      /<loc>/ {gsub(/.*<loc>|<\/loc>.*/,""); u=$0}
      /<lastmod>/ {gsub(/.*<lastmod>|<\/lastmod>.*/,""); lm=$0}
      /<\/url>/ {if (u==loc) print lm}
    ' "$SITEMAP" | head -1)"
    if [ -z "$LM" ]; then
      warn "no <url> block in website/sitemap.xml matches this page's canonical ($CANON) — add one"
    elif [ "$LM" != "$TODAY" ]; then
      warn "website/sitemap.xml <lastmod> for $CANON is $LM, not today ($TODAY) — bump it"
    fi
  fi
fi

# --- deprecated / retired structured data --------------------------------------
printf '%s' "$CONTENT" | grep -qE '"@type"[[:space:]]*:[[:space:]]*"HowTo"' \
  && warn "HowTo schema was deprecated in 2023 — remove it"
printf '%s' "$CONTENT" | grep -qE '"@type"[[:space:]]*:[[:space:]]*"FAQPage"' \
  && warn "FAQPage schema — Google retired FAQ rich results for all sites in May 2026; it is dead weight"

# --- SERP snippet limit (duplicates the CI test, but at edit time) -------------
# Measure the RENDERED length: decode the entities the CI test decodes, or a
# description with an &amp; over-reports by 4 chars each and false-positives.
DESC="$(printf '%s' "$CONTENT" | grep -oE '<meta[^>]+name="description"[^>]+content="[^"]*"' \
        | sed 's/.*content="//;s/"$//' | head -1 || true)"
DESC="$(printf '%s' "$DESC" | sed 's/&amp;/\&/g; s/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g; s/&#39;/'"'"'/g')"
if [ -n "$DESC" ] && [ "${#DESC}" -gt 160 ]; then
  warn "meta description is ${#DESC} chars (>160) — Google truncates past that and substitutes its own snippet"
fi

# --- homepage link form --------------------------------------------------------
printf '%s' "$CONTENT" | grep -qE 'href="\.\./(\.\./)*index\.html"' \
  && warn 'link the homepage as "/" — "../index.html" costs a 307 redirect hop'

hook_finish "website-drift" "Reference: .claude/patterns/website-drift.md"
