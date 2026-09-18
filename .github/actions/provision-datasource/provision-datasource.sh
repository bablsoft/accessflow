#!/usr/bin/env bash
# Idempotently create or update an AccessFlow datasource. Requires curl + jq.
set -euo pipefail

: "${AF_ENDPOINT:?endpoint is required}"
: "${AF_API_KEY:?api-key is required}"
: "${AF_NAME:?name is required}"
: "${AF_DB_TYPE:?db-type is required}"

base="${AF_ENDPOINT%/}/api/v1"
auth=(-H "Authorization: ApiKey ${AF_API_KEY}" -H "X-AccessFlow-CI: true")

# to_seconds VALUE NAME — parse "90" / "45s" / "30m" / "2h" into seconds.
to_seconds() {
  local v="$1" name="$2"
  if [[ "$v" =~ ^([0-9]+)([smh]?)$ ]]; then
    local n="${BASH_REMATCH[1]}"
    case "${BASH_REMATCH[2]}" in
      h) echo $(( n * 3600 )) ;;
      m) echo $(( n * 60 )) ;;
      *) echo "$n" ;;
    esac
  else
    echo "::error::Invalid ${name} '${v}' — use a number with an optional s/m/h suffix (e.g. 90, 45s, 30m, 2h)" >&2
    return 1
  fi
}

retry_timeout_s="$(to_seconds "${AF_RETRY_TIMEOUT:-2m}" retry-timeout)"
# Fallback pause between rate-limited attempts when the 429 carries no usable Retry-After.
retry_fallback_s=5
# All three calls (lookup, then create or update) share one retry budget for 429s (#873).
deadline=$(( SECONDS + retry_timeout_s ))

# retry_delay RETRY_AFTER — seconds to sleep before the next attempt: the server's Retry-After when
# it sent one, otherwise the fallback; either way capped at the time left before the deadline.
retry_delay() {
  local delay="${1:-$retry_fallback_s}" remaining=$(( deadline - SECONDS ))
  if [ "$delay" -lt "$remaining" ]; then echo "$delay"; else echo $(( remaining > 0 ? remaining : 0 )); fi
}

# request METHOD URL [BODY] — performs the call, captures body + HTTP status, retries a 429
# (per-identity API-key rate limit) honouring Retry-After while retry-timeout allows, and on any
# other >=400 prints the RFC 9457 ProblemDetail (title/detail) before failing, so errors are
# debuggable instead of surfacing as a bare `curl --fail` exit code. Echoes the response body on
# success. A 429 is answered by the rate-limit filter ahead of the controller, so re-sending the
# create is safe — nothing was persisted.
request() {
  local method="$1" url="$2" data="${3:-}" out code body hdr retry_after delay
  while :; do
    hdr="$(mktemp)"
    if [ -n "$data" ]; then
      out="$(curl -sS -X "$method" "${auth[@]}" -H 'Content-Type: application/json' \
        -d "$data" -D "$hdr" -w $'\n%{http_code}' "$url")" || { rm -f "$hdr"; return 1; }
    else
      out="$(curl -sS -X "$method" "${auth[@]}" -D "$hdr" -w $'\n%{http_code}' "$url")" \
        || { rm -f "$hdr"; return 1; }
    fi
    code="${out##*$'\n'}"
    body="${out%$'\n'*}"
    retry_after="$(sed -n -E 's/^[Rr]etry-[Aa]fter:[[:space:]]*([0-9]+)[[:space:]]*$/\1/p' "$hdr" | tail -n1)"
    rm -f "$hdr"
    if [ "$code" = "429" ] && [ "$SECONDS" -lt "$deadline" ]; then
      delay="$(retry_delay "$retry_after")"
      echo "  rate limited (HTTP 429), retrying ${method} in ${delay}s…" >&2
      sleep "$delay"
      continue
    fi
    break
  done
  if [ "$code" -ge 400 ]; then
    echo "::error::AccessFlow API ${method} ${url} returned HTTP ${code}" >&2
    jq -r '"  \(.title // "error"): \(.detail // .message // .)"' <<<"$body" >&2 2>/dev/null \
      || echo "  $body" >&2
    return 1
  fi
  printf '%s' "$body"
}

# Build the request body, including only the optional fields that are set.
body="$(jq -n \
  --arg name "$AF_NAME" \
  --arg db_type "$AF_DB_TYPE" \
  --arg ssl_mode "${AF_SSL_MODE:-DISABLE}" \
  '{name: $name, db_type: $db_type, ssl_mode: $ssl_mode}')"

add_str() { if [ -n "${2:-}" ]; then body="$(jq --arg v "$2" ". + {\"$1\": \$v}" <<<"$body")"; fi; }
add_num() { if [ -n "${2:-}" ]; then body="$(jq --argjson v "$2" ". + {\"$1\": \$v}" <<<"$body")"; fi; }
add_bool() { if [ -n "${2:-}" ]; then body="$(jq --argjson v "$2" ". + {\"$1\": \$v}" <<<"$body")"; fi; }

add_str host "${AF_HOST:-}"
add_num port "${AF_PORT:-}"
add_str database_name "${AF_DATABASE_NAME:-}"
add_str username "${AF_USERNAME:-}"
add_str password "${AF_PASSWORD:-}"
add_str review_plan_id "${AF_REVIEW_PLAN_ID:-}"
add_str ai_config_id "${AF_AI_CONFIG_ID:-}"
add_bool ai_analysis_enabled "${AF_AI_ANALYSIS_ENABLED:-}"
add_bool text_to_sql_enabled "${AF_TEXT_TO_SQL_ENABLED:-}"

# Find an existing datasource with the same name (list is a Spring Page → .content[]).
existing="$(request GET "${base}/datasources?size=100")"
id="$(jq -r --arg n "$AF_NAME" \
  'first(((.content // .)[]? | select(.name == $n) | .id)) // empty' <<<"$existing")"

if [ -n "$id" ]; then
  echo "Updating existing datasource '$AF_NAME' ($id)"
  resp="$(request PUT "${base}/datasources/${id}" "$body")"
else
  echo "Creating datasource '$AF_NAME'"
  resp="$(request POST "${base}/datasources" "$body")"
  id="$(jq -r '.id' <<<"$resp")"
fi

if [ -z "$id" ] || [ "$id" = "null" ]; then
  echo "::error::Failed to resolve datasource id from response: $resp"
  exit 1
fi

echo "id=$id" >>"${GITHUB_OUTPUT:-/dev/stdout}"
echo "Datasource '$AF_NAME' provisioned: $id"
