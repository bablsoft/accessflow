#!/usr/bin/env bash
# Submit a query to AccessFlow and poll until it reaches a terminal status. Requires curl + jq.
set -euo pipefail

: "${AF_ENDPOINT:?endpoint is required}"
: "${AF_API_KEY:?api-key is required}"
: "${AF_DATASOURCE_ID:?datasource-id is required}"
: "${AF_SQL:?sql is required}"

base="${AF_ENDPOINT%/}/api/v1"
# Mark this submission as CI-originated so context-aware routing policies (AF-446) can treat it
# accordingly instead of failing closed.
auth=(-H "Authorization: ApiKey ${AF_API_KEY}" -H "X-AccessFlow-CI: true")
out="${GITHUB_OUTPUT:-/dev/stdout}"

# request METHOD URL [BODY] — captures body + HTTP status and prints the RFC 9457 ProblemDetail
# on a >=400 before failing, instead of a bare `curl --fail` exit code.
request() {
  local method="$1" url="$2" data="${3:-}" resp code
  if [ -n "$data" ]; then
    resp="$(curl -sS -X "$method" "${auth[@]}" -H 'Content-Type: application/json' \
      -d "$data" -w $'\n%{http_code}' "$url")"
  else
    resp="$(curl -sS -X "$method" "${auth[@]}" -w $'\n%{http_code}' "$url")"
  fi
  code="${resp##*$'\n'}"
  local payload="${resp%$'\n'*}"
  if [ "$code" -ge 400 ]; then
    echo "::error::AccessFlow API ${method} ${url} returned HTTP ${code}" >&2
    jq -r '"  \(.title // "error"): \(.detail // .message // .)"' <<<"$payload" 2>/dev/null >&2 \
      || echo "  $payload" >&2
    return 1
  fi
  printf '%s' "$payload"
}

body="$(jq -n \
  --arg ds "$AF_DATASOURCE_ID" \
  --arg sql "$AF_SQL" \
  --arg j "${AF_JUSTIFICATION:-}" \
  '{datasource_id: $ds, sql: $sql} + (if $j == "" then {} else {justification: $j} end)')"

submit="$(request POST "${base}/queries" "$body")"
query_id="$(jq -r '.id' <<<"$submit")"
if [ -z "$query_id" ] || [ "$query_id" = "null" ]; then
  echo "::error::Query submission failed: $submit"
  exit 1
fi
echo "query-id=$query_id" >>"$out"
echo "Submitted query $query_id; awaiting terminal status…"

deadline=$(( SECONDS + ${AF_TIMEOUT_SECONDS:-300} ))
interval="${AF_POLL_INTERVAL_SECONDS:-5}"
status="UNKNOWN"
triggered=0
while [ "$SECONDS" -lt "$deadline" ]; do
  detail="$(request GET "${base}/queries/${query_id}")"
  status="$(jq -r '.status' <<<"$detail")"
  case "$status" in
    EXECUTED)
      echo "status=$status" >>"$out"
      echo "Query $query_id executed successfully."
      exit 0
      ;;
    APPROVED)
      # Approval authorizes the query; execution is a separate, deliberate step. Trigger it once
      # (a scheduled query — scheduled_for set — runs itself, so only execute immediate ones).
      scheduled_for="$(jq -r '.scheduled_for // empty' <<<"$detail")"
      if [ "$triggered" = "0" ] && [ -z "$scheduled_for" ]; then
        echo "  approved — triggering execution…"
        exec_resp="$(request POST "${base}/queries/${query_id}/execute")"
        triggered=1
        exec_status="$(jq -r '.status' <<<"$exec_resp")"
        echo "status=$exec_status" >>"$out"
        if [ "$exec_status" = "EXECUTED" ]; then
          echo "Query $query_id executed successfully."
          exit 0
        fi
        echo "::error::Query $query_id execution ended in status $exec_status"
        exit 1
      fi
      echo "  status=$status (scheduled), waiting ${interval}s…"
      sleep "$interval"
      ;;
    REJECTED | FAILED | TIMED_OUT | CANCELLED)
      echo "status=$status" >>"$out"
      echo "::error::Query $query_id ended in terminal status $status"
      exit 1
      ;;
    *)
      echo "  status=$status, waiting ${interval}s…"
      sleep "$interval"
      ;;
  esac
done

echo "status=$status" >>"$out"
echo "::error::Timed out after ${AF_TIMEOUT_SECONDS:-300}s waiting for query $query_id (last status: $status)"
exit 1
