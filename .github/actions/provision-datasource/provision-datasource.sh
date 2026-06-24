#!/usr/bin/env bash
# Idempotently create or update an AccessFlow datasource. Requires curl + jq.
set -euo pipefail

: "${AF_ENDPOINT:?endpoint is required}"
: "${AF_API_KEY:?api-key is required}"
: "${AF_NAME:?name is required}"
: "${AF_DB_TYPE:?db-type is required}"

base="${AF_ENDPOINT%/}/api/v1"
auth=(-H "Authorization: ApiKey ${AF_API_KEY}" -H "X-AccessFlow-CI: true")

# request METHOD URL [BODY] — performs the call, captures body + HTTP status, and on a >=400
# prints the RFC 9457 ProblemDetail (title/detail) before failing, so errors are debuggable
# instead of surfacing as a bare `curl --fail` exit code. Echoes the response body on success.
request() {
  local method="$1" url="$2" data="${3:-}" out code
  if [ -n "$data" ]; then
    out="$(curl -sS -X "$method" "${auth[@]}" -H 'Content-Type: application/json' \
      -d "$data" -w $'\n%{http_code}' "$url")"
  else
    out="$(curl -sS -X "$method" "${auth[@]}" -w $'\n%{http_code}' "$url")"
  fi
  code="${out##*$'\n'}"
  local body="${out%$'\n'*}"
  if [ "$code" -ge 400 ]; then
    echo "::error::AccessFlow API ${method} ${url} returned HTTP ${code}" >&2
    jq -r '"  \(.title // "error"): \(.detail // .message // .)"' <<<"$body" 2>/dev/null >&2 \
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
