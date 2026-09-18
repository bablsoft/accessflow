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

timeout_s="${AF_TIMEOUT_SECONDS:-300}"
interval="${AF_POLL_INTERVAL_SECONDS:-5}"
# One wall-clock budget for the whole run: submission, the status polls and the execute call all
# share it, so a rate-limited call (HTTP 429, #873) is retried honouring Retry-After until it
# elapses instead of failing the job on the first 429.
deadline=$(( SECONDS + timeout_s ))

# retry_delay RETRY_AFTER — seconds to sleep before the next attempt: the server's Retry-After when
# it sent one (capped at the time left before the deadline, so a long window ends in the normal
# timeout path instead of an over-long sleep), otherwise the fixed poll interval.
retry_delay() {
  local remaining=$(( deadline - SECONDS ))
  if [ -n "${1:-}" ]; then
    if [ "$1" -lt "$remaining" ]; then echo "$1"; else echo $(( remaining > 0 ? remaining : 0 )); fi
  else
    echo "$interval"
  fi
}

# request METHOD URL [BODY] — captures body + HTTP status, retries a 429 (per-identity API-key rate
# limit) honouring Retry-After while the deadline allows, and prints the RFC 9457 ProblemDetail on
# any other >=400 before failing, instead of a bare `curl --fail` exit code. A 429 is answered by
# the rate-limit filter ahead of the controller, so re-sending an execute call is safe.
request() {
  local method="$1" url="$2" data="${3:-}" resp code payload hdr retry_after delay
  while :; do
    hdr="$(mktemp)"
    if [ -n "$data" ]; then
      resp="$(curl -sS -X "$method" "${auth[@]}" -H 'Content-Type: application/json' \
        -d "$data" -D "$hdr" -w $'\n%{http_code}' "$url")" || { rm -f "$hdr"; return 1; }
    else
      resp="$(curl -sS -X "$method" "${auth[@]}" -D "$hdr" -w $'\n%{http_code}' "$url")" \
        || { rm -f "$hdr"; return 1; }
    fi
    code="${resp##*$'\n'}"
    payload="${resp%$'\n'*}"
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
    jq -r '"  \(.title // "error"): \(.detail // .message // .)"' <<<"$payload" >&2 2>/dev/null \
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
echo "::error::Timed out after ${timeout_s}s waiting for query $query_id (last status: $status)"
exit 1
