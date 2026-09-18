#!/usr/bin/env bash
# Report the post-deploy outcome of an AccessFlow deployment request. Requires curl + jq.
set -euo pipefail

: "${AF_ENDPOINT:?accessflow-url is required}"
: "${AF_API_KEY:?api-key is required}"

base="${AF_ENDPOINT%/}/api/v1"
auth=(-H "Authorization: ApiKey ${AF_API_KEY}" -H "X-AccessFlow-CI: true")
out="${GITHUB_OUTPUT:-/dev/stdout}"

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

# This step runs under `if: always()` — when the gate step failed before creating a request
# there is nothing to report, and failing here would only bury the real failure.
if [ -z "${AF_REQUEST_ID:-}" ]; then
  echo "::warning::No request-id — the deployment gate never created a request; nothing to report."
  exit 0
fi

outcome="${AF_OUTCOME:-}"
if [ -z "$outcome" ]; then
  case "${AF_JOB_STATUS:-}" in
    success) outcome="SUCCEEDED" ;;
    failure) outcome="FAILED" ;;
    cancelled)
      echo "Job was cancelled — the deployment did not finish; nothing to report."
      exit 0
      ;;
    *)
      echo "::error::Pass job-status (\${{ job.status }}) or an explicit outcome (SUCCEEDED / FAILED / ROLLED_BACK)"
      exit 1
      ;;
  esac
fi
case "$outcome" in
  SUCCEEDED | FAILED | ROLLED_BACK) ;;
  *)
    echo "::error::Invalid outcome '$outcome' — must be SUCCEEDED, FAILED or ROLLED_BACK"
    exit 1
    ;;
esac

body="$(jq -n \
  --arg o "$outcome" \
  --arg d "${AF_DETAIL:-}" \
  '{outcome: $o} + (if $d == "" then {} else {detail: $d} end)')"

# A 429 (per-identity API-key rate limit, #873) is retried honouring Retry-After until
# retry-timeout elapses; every other failure is immediately fatal, as before.
deadline=$(( SECONDS + retry_timeout_s ))
while :; do
  hdr="$(mktemp)"
  resp="$(curl -sS -X POST "${auth[@]}" -H 'Content-Type: application/json' \
    -d "$body" -D "$hdr" -w $'\n%{http_code}' "${base}/deployment-requests/${AF_REQUEST_ID}/outcome")" \
    || { rm -f "$hdr"; echo "::error::AccessFlow API unreachable while reporting the outcome"; exit 1; }
  code="${resp##*$'\n'}"
  payload="${resp%$'\n'*}"
  retry_after="$(sed -n -E 's/^[Rr]etry-[Aa]fter:[[:space:]]*([0-9]+)[[:space:]]*$/\1/p' "$hdr" | tail -n1)"
  rm -f "$hdr"
  if [ "$code" = "429" ] && [ "$SECONDS" -lt "$deadline" ]; then
    delay="${retry_after:-$retry_fallback_s}"
    remaining=$(( deadline - SECONDS ))
    if [ "$delay" -gt "$remaining" ]; then delay="$remaining"; fi
    echo "  rate limited (HTTP 429), retrying in ${delay}s…"
    sleep "$delay"
    continue
  fi
  break
done

if [ "$code" = "200" ]; then
  status="$(jq -r '.status' <<<"$payload")"
  echo "status=$status" >>"$out"
  echo "Outcome $outcome reported for deployment request $AF_REQUEST_ID (status: $status)."
  exit 0
fi

err="$(jq -r '.error // empty' <<<"$payload" 2>/dev/null || true)"
if [ "$code" = "409" ] && [ "$err" = "DEPLOYMENT_REQUEST_INVALID_STATE" ]; then
  # The request never reached EXECUTED (rejected, timed out, cancelled) — the gate already
  # failed the pipeline, so there is no outcome to record.
  echo "::warning::Deployment request $AF_REQUEST_ID was never executed; nothing to report."
  exit 0
fi
echo "::error::AccessFlow API POST ${base}/deployment-requests/${AF_REQUEST_ID}/outcome returned HTTP ${code}" >&2
jq -r '"  \(.title // "error"): \(.detail // .message // .)"' <<<"$payload" >&2 2>/dev/null \
  || echo "  $payload" >&2
exit 1
