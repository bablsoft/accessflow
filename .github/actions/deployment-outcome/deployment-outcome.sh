#!/usr/bin/env bash
# Report the post-deploy outcome of an AccessFlow deployment request. Requires curl + jq.
set -euo pipefail

: "${AF_ENDPOINT:?accessflow-url is required}"
: "${AF_API_KEY:?api-key is required}"

base="${AF_ENDPOINT%/}/api/v1"
auth=(-H "Authorization: ApiKey ${AF_API_KEY}" -H "X-AccessFlow-CI: true")
out="${GITHUB_OUTPUT:-/dev/stdout}"

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

resp="$(curl -sS -X POST "${auth[@]}" -H 'Content-Type: application/json' \
  -d "$body" -w $'\n%{http_code}' "${base}/deployment-requests/${AF_REQUEST_ID}/outcome")" \
  || { echo "::error::AccessFlow API unreachable while reporting the outcome"; exit 1; }
code="${resp##*$'\n'}"
payload="${resp%$'\n'*}"

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
jq -r '"  \(.title // "error"): \(.detail // .message // .)"' <<<"$payload" 2>/dev/null >&2 \
  || echo "  $payload" >&2
exit 1
