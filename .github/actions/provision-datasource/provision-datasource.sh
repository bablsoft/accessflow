#!/usr/bin/env bash
# Idempotently create or update an AccessFlow datasource. Requires curl + jq.
set -euo pipefail

: "${AF_ENDPOINT:?endpoint is required}"
: "${AF_API_KEY:?api-key is required}"
: "${AF_NAME:?name is required}"
: "${AF_DB_TYPE:?db-type is required}"

base="${AF_ENDPOINT%/}/api/v1"
auth=(-H "Authorization: ApiKey ${AF_API_KEY}")

# Build the request body, including only the optional fields that are set.
body="$(jq -n \
  --arg name "$AF_NAME" \
  --arg db_type "$AF_DB_TYPE" \
  --arg ssl_mode "${AF_SSL_MODE:-DISABLE}" \
  '{name: $name, db_type: $db_type, ssl_mode: $ssl_mode}')"

add_str() { [ -n "${2:-}" ] && body="$(jq --arg v "$2" ". + {\"$1\": \$v}" <<<"$body")" || true; }
add_num() { [ -n "${2:-}" ] && body="$(jq --argjson v "$2" ". + {\"$1\": \$v}" <<<"$body")" || true; }

add_str host "${AF_HOST:-}"
add_num port "${AF_PORT:-}"
add_str database_name "${AF_DATABASE_NAME:-}"
add_str username "${AF_USERNAME:-}"
add_str password "${AF_PASSWORD:-}"
add_str review_plan_id "${AF_REVIEW_PLAN_ID:-}"
add_str ai_config_id "${AF_AI_CONFIG_ID:-}"

# Find an existing datasource with the same name (list is a Spring Page → .content[]).
existing="$(curl -fsS "${auth[@]}" "${base}/datasources?size=100")"
id="$(jq -r --arg n "$AF_NAME" \
  'first(((.content // .)[]? | select(.name == $n) | .id)) // empty' <<<"$existing")"

if [ -n "$id" ]; then
  echo "Updating existing datasource '$AF_NAME' ($id)"
  resp="$(curl -fsS -X PUT "${auth[@]}" -H 'Content-Type: application/json' \
    -d "$body" "${base}/datasources/${id}")"
else
  echo "Creating datasource '$AF_NAME'"
  resp="$(curl -fsS -X POST "${auth[@]}" -H 'Content-Type: application/json' \
    -d "$body" "${base}/datasources")"
  id="$(jq -r '.id' <<<"$resp")"
fi

if [ -z "$id" ] || [ "$id" = "null" ]; then
  echo "::error::Failed to resolve datasource id from response: $resp"
  exit 1
fi

echo "id=$id" >>"${GITHUB_OUTPUT:-/dev/stdout}"
echo "Datasource '$AF_NAME' provisioned: $id"
