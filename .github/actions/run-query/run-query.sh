#!/usr/bin/env bash
# Submit a query to AccessFlow and poll until it reaches a terminal status. Requires curl + jq.
set -euo pipefail

: "${AF_ENDPOINT:?endpoint is required}"
: "${AF_API_KEY:?api-key is required}"
: "${AF_DATASOURCE_ID:?datasource-id is required}"
: "${AF_SQL:?sql is required}"

base="${AF_ENDPOINT%/}/api/v1"
auth=(-H "Authorization: ApiKey ${AF_API_KEY}")
out="${GITHUB_OUTPUT:-/dev/stdout}"

body="$(jq -n \
  --arg ds "$AF_DATASOURCE_ID" \
  --arg sql "$AF_SQL" \
  --arg j "${AF_JUSTIFICATION:-}" \
  '{datasource_id: $ds, sql: $sql} + (if $j == "" then {} else {justification: $j} end)')"

# Mark this submission as CI-originated so context-aware routing policies (AF-446) can treat it
# accordingly instead of failing closed.
submit="$(curl -fsS -X POST "${auth[@]}" \
  -H 'Content-Type: application/json' -H 'X-AccessFlow-CI: true' \
  -d "$body" "${base}/queries")"
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
while [ "$SECONDS" -lt "$deadline" ]; do
  detail="$(curl -fsS "${auth[@]}" "${base}/queries/${query_id}")"
  status="$(jq -r '.status' <<<"$detail")"
  case "$status" in
    EXECUTED)
      echo "status=$status" >>"$out"
      echo "Query $query_id executed successfully."
      exit 0
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
