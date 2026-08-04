#!/usr/bin/env bash
# frontend-conventions.sh — PreToolUse (Write|Edit) on frontend/src.
#
# Measured against all 290 non-test frontend sources: every rule sits at zero
# existing violations except the raw-colour one, which has 2 (a hardcoded collab
# cursor colour, and vendor brand hexes on NotificationsPage that arguably should
# stay literal). See .claude/hooks/README.md for the measurement procedure.
#
# Two rules block (once ACCESSFLOW_HOOKS_ENFORCE=1): a JWT in web storage and
# dangerouslySetInnerHTML/eval. Both are security rules from CLAUDE.md, both are
# trivially avoidable, and both are invisible in review once merged.
#
# See .claude/patterns/{frontend-page,frontend-form}.md

set -uo pipefail
. "$(dirname "$0")/_common.sh"
hook_read_target "$@"

case "$REL" in
  frontend/src/*.ts|frontend/src/*.tsx|frontend/src/*.css) ;;
  *) exit 0 ;;
esac
# Tests, generated types, locale data, mocks, and the theme/style token sources are exempt.
case "$REL" in
  *.test.*|*.spec.*|*/__tests__/*|*.d.ts|frontend/src/locales/*|frontend/src/mocks/*) exit 0 ;;
esac
[ -z "$CONTENT" ] && exit 0

# Strip whole-line comments so prose does not trip the greps.
CODE="$(printf '%s' "$CONTENT" | grep -vE '^[[:space:]]*(//|\*|/\*)' || true)"
# NB: capture, never `grep -q` in a pipeline — under `set -o pipefail` the early
# exit SIGPIPEs the producer and fails the whole pipeline.
has() { local o; o="$(printf '%s' "$CODE" | grep -E "$1" || true)"; [ -n "$o" ]; }

IS_CONFIG=0; case "$REL" in frontend/src/config/*) IS_CONFIG=1 ;; esac
IS_CLIENT=0; case "$REL" in frontend/src/api/client.ts|frontend/src/sw.ts) IS_CLIENT=1 ;; esac
IS_TOKENS=0; case "$REL" in frontend/src/theme/*|frontend/src/styles/*|*/statusColors.ts|*/riskColors.ts|*/engineModes.ts|*/antdTheme.ts) IS_TOKENS=1 ;; esac

# ---- BLOCK: a JWT in web storage ---------------------------------------------
if has '(localStorage|sessionStorage)\.(set|get)Item' \
   && has '(localStorage|sessionStorage)[^;]*(token|jwt|access|Token|JWT|Access)'; then
  block "JWT in localStorage/sessionStorage — XSS-exfiltratable. The access token lives in memory (authStore); the refresh token is an HttpOnly; Secure; SameSite=Strict cookie the frontend never reads. (CLAUDE.md → Frontend non-negotiables)"
fi

# ---- BLOCK: HTML/script injection --------------------------------------------
has 'dangerouslySetInnerHTML' && block "dangerouslySetInnerHTML — forbidden. Read-only SQL panels use CodeMirror with a string value, not raw HTML."
has '[^a-zA-Z.]eval\(|new Function\(' && block "eval / new Function — forbidden, and blocked by the backend's CSP anyway."

# ---- WARN --------------------------------------------------------------------
has '\bas any\b'                    && warn "\`as any\` — strict:true is load-bearing; fix the type. API shapes belong in src/types/api.ts."
has '@ts-(ignore|expect-error)'     && warn "@ts-ignore / @ts-expect-error — fix the type instead."
has 'process\.env'                  && warn "process.env is not available in the browser bundle — use src/config/runtimeConfig.ts."

# import.meta.env.{PROD,DEV,MODE,SSR,BASE_URL} are Vite BUILD flags, not app config, and are
# fine anywhere. Filter them out with a second pass — grep -E has no negative lookahead, and
# an inline (?!...) silently errors (never fires) instead of matching.
if [ "$IS_CONFIG" = 0 ]; then
  ENVUSE="$(printf '%s' "$CODE" | grep -E 'import\.meta\.env' \
            | grep -vE 'import\.meta\.env\.(PROD|DEV|MODE|SSR|BASE_URL)\b' || true)"
  [ -n "$ENVUSE" ] && warn "import.meta.env outside src/config/ — use getApiBaseUrl()/getWsUrl() from src/config/runtimeConfig.ts, otherwise a deploy-time runtime-config.js override has no effect. (Vite's PROD/DEV/MODE flags are fine.)"
fi

[ "$IS_CLIENT" = 0 ] && has '[^.a-zA-Z_]fetch\(' \
  && warn "bare fetch() — all requests go through src/api/client.ts, which owns withCredentials, the baseURL and the 401 refresh interceptor."

# Raw colour literals, but only the ones that are actually violations. Measured:
# a naive rule fires on 15 files, nearly all legitimate — `var(--af-token, #fallback)`
# fallbacks and neutral rgba(0,0,0,a) shadows/scrims. Strip both, plus .css files
# (component stylesheets legitimately carry shadows), before matching.
if [ "$IS_TOKENS" = 0 ]; then
  case "$REL" in
    *.css) ;;
    *)
      COLORS="$(printf '%s' "$CODE" \
        | sed -E 's/var\(--[A-Za-z0-9_-]+,[^)]*\)//g' \
        | sed -E 's/rgba?\([[:space:]]*(0[[:space:]]*,[[:space:]]*0[[:space:]]*,[[:space:]]*0|255[[:space:]]*,[[:space:]]*255[[:space:]]*,[[:space:]]*255)[^)]*\)//g' \
        | grep -E '#[0-9a-fA-F]{6}([^0-9a-fA-F]|$)|rgba?\([[:space:]]*[0-9]' || true)"
      [ -n "$COLORS" ] && warn "raw colour literal — use an --af-* token (src/utils/antdTheme.ts); status/risk colours live in src/utils/{statusColors,riskColors}.ts. Found: $(printf '%s' "$COLORS" | head -1 | sed 's/^[[:space:]]*//' | cut -c1-70)"
      ;;
  esac
fi

has 'onError:[[:space:]]*\(\)[[:space:]]*=>' \
  && warn "onError with no \`err\` parameter discards the server's ProblemDetail detail, which is already localized. Use showApiError(message, err, builder) — see .claude/patterns/frontend-page.md."

has '\{[[:space:]]*value:[[:space:]]*.[A-Z_]{3,}.,[[:space:]]*label:[[:space:]]*.[A-Z_]{3,}.[[:space:]]*\}' \
  && warn "inlined enum label — use enumOptions(VALUES, label, t) and the helpers in src/utils/enumLabels.ts so the visible text is translated."

has 'target="_blank"' && ! has 'rel="noopener' \
  && warn 'target="_blank" without rel="noopener noreferrer"'

hook_finish "frontend-conventions" "Reference: .claude/patterns/frontend-page.md"
