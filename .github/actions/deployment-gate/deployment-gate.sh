#!/usr/bin/env bash
# Submit a deployment request to AccessFlow, poll the fail-closed deployment gate until
# releasable, then confirm execution. Requires curl + jq.
set -euo pipefail

: "${AF_ENDPOINT:?accessflow-url is required}"
: "${AF_API_KEY:?api-key is required}"
: "${AF_PIPELINE_ID:?pipeline-id is required}"
: "${AF_VERSION:?version is required}"
: "${AF_ENVIRONMENT:?environment is required}"

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

timeout_s="$(to_seconds "${AF_WAIT_TIMEOUT:-30m}" wait-timeout)"
interval_s="$(to_seconds "${AF_POLL_INTERVAL:-15s}" poll-interval)"

# attempt METHOD URL [BODY] — performs the call and prints "<http_code>\n<body>" without failing,
# so the poll loop can decide per-code (the gate is fail-closed: transient 5xx/network errors are
# retried until the deadline, while a 404 or 4xx fails the job). Code 000 = curl itself failed.
attempt() {
  local method="$1" url="$2" data="${3:-}" resp
  if [ -n "$data" ]; then
    resp="$(curl -sS -X "$method" "${auth[@]}" -H 'Content-Type: application/json' \
      -d "$data" -w $'\n%{http_code}' "$url")" || { printf '000\n'; return 0; }
  else
    resp="$(curl -sS -X "$method" "${auth[@]}" -w $'\n%{http_code}' "$url")" || { printf '000\n'; return 0; }
  fi
  printf '%s\n%s' "${resp##*$'\n'}" "${resp%$'\n'*}"
}

# problem MESSAGE BODY — emit a workflow error plus the RFC 9457 ProblemDetail, so failures are
# debuggable instead of surfacing as a bare exit code.
problem() {
  echo "::error::$1" >&2
  jq -r '"  \(.title // "error"): \(.detail // .message // .)"' <<<"$2" 2>/dev/null >&2 \
    || echo "  $2" >&2
}

# Build the trigger body, including only the optional fields that are set.
body="$(jq -n \
  --arg p "$AF_PIPELINE_ID" \
  --arg env "$AF_ENVIRONMENT" \
  --arg v "$AF_VERSION" \
  --arg run "${AF_RUN_ID:-}" \
  '{pipeline_id: $p, environment: $env, version: $v}
   + (if $run == "" then {} else {external_run_id: $run} end)')"

add_str() { if [ -n "${2:-}" ]; then body="$(jq --arg v "$2" ". + {\"$1\": \$v}" <<<"$body")"; fi; }

add_str commit_sha "${AF_COMMIT_SHA:-}"
add_str artifact_ref "${AF_ARTIFACT_REF:-}"
add_str run_url "${AF_RUN_URL:-}"
add_str justification "${AF_JUSTIFICATION:-}"
add_str scheduled_for "${AF_SCHEDULED_FOR:-}"
if [ "${AF_BREAK_GLASS:-false}" = "true" ]; then
  body="$(jq '. + {break_glass: true}' <<<"$body")"
fi
if [ -n "${AF_METADATA_FILE:-}" ]; then
  if [ ! -f "$AF_METADATA_FILE" ]; then
    echo "::error::metadata-file '$AF_METADATA_FILE' does not exist"
    exit 1
  fi
  if ! metadata="$(jq -ec 'if type == "object" then . else error("not an object") end' \
      "$AF_METADATA_FILE" 2>/dev/null)"; then
    echo "::error::metadata-file '$AF_METADATA_FILE' is not a JSON object"
    exit 1
  fi
  body="$(jq --argjson m "$metadata" '. + {metadata: $m}' <<<"$body")"
fi

resp="$(attempt POST "${base}/deployment-requests" "$body")"
code="$(head -n1 <<<"$resp")"
submit="$(tail -n +2 <<<"$resp")"
if [ "$code" = "000" ] || [ "$code" -ge 400 ]; then
  problem "Deployment request submission failed (HTTP ${code})" "$submit"
  exit 1
fi
request_id="$(jq -r '.id' <<<"$submit")"
if [ -z "$request_id" ] || [ "$request_id" = "null" ]; then
  echo "::error::Deployment request submission failed: $submit"
  exit 1
fi
echo "request-id=$request_id" >>"$out"
status="$(jq -r '.status' <<<"$submit")"
risk="$(jq -r '.ai_risk_level // empty' <<<"$submit")"
if [ "$code" = "200" ]; then
  # 200 (not 202) means the (pipeline, environment, version, external_run_id) tuple already
  # existed — the trigger is idempotent, so a re-run re-attaches instead of duplicating.
  echo "Re-attached to existing deployment request $request_id (status: $status); awaiting releasable gate…"
else
  echo "Submitted deployment request $request_id; awaiting releasable gate…"
fi

# finish STATUS EXIT_CODE [MESSAGE] — write the outputs and end the step.
finish() {
  echo "status=$1" >>"$out"
  if [ -n "$risk" ]; then echo "ai-risk-level=$risk" >>"$out"; fi
  if [ -n "${3:-}" ]; then
    if [ "$2" = "0" ]; then echo "$3"; else echo "::error::$3"; fi
  fi
  exit "$2"
}

deadline=$(( SECONDS + timeout_s ))
while [ "$SECONDS" -lt "$deadline" ]; do
  resp="$(attempt GET "${base}/deployment-gate?request_id=${request_id}")"
  code="$(head -n1 <<<"$resp")"
  gate="$(tail -n +2 <<<"$resp")"
  if [ "$code" = "000" ] || [ "$code" -ge 500 ]; then
    echo "  gate unreachable (HTTP ${code}), retrying in ${interval_s}s…"
    sleep "$interval_s"
    continue
  fi
  if [ "$code" = "404" ]; then
    # The gate is fail-closed: an unknown or invisible request is never releasable.
    problem "Deployment gate answered 404 for request ${request_id} — fail closed, not releasable" "$gate"
    finish "$status" 1
  fi
  if [ "$code" -ge 400 ]; then
    problem "Deployment gate returned HTTP ${code} for request ${request_id}" "$gate"
    finish "$status" 1
  fi

  status="$(jq -r '.status' <<<"$gate")"
  releasable="$(jq -r '.releasable' <<<"$gate")"
  new_risk="$(jq -r '.ai_risk_level // empty' <<<"$gate")"
  if [ -n "$new_risk" ]; then risk="$new_risk"; fi

  case "$status" in
    REJECTED | TIMED_OUT | CANCELLED | FAILED)
      finish "$status" 1 "Deployment request $request_id ended in terminal status $status"
      ;;
    EXECUTED)
      # Someone (a concurrent run, or a previous attempt of this one) already confirmed.
      finish "$status" 0 "Deployment request $request_id already confirmed as executed."
      ;;
  esac

  if [ "$releasable" = "true" ]; then
    resp="$(attempt POST "${base}/deployment-requests/${request_id}/confirm-execution")"
    ccode="$(head -n1 <<<"$resp")"
    confirm="$(tail -n +2 <<<"$resp")"
    if [ "$ccode" = "200" ]; then
      finish EXECUTED 0 "Deployment request $request_id confirmed — gate open, proceeding."
    fi
    if [ "$ccode" = "409" ]; then
      err="$(jq -r '.error // empty' <<<"$confirm")"
      # `currentStatus` is a ProblemDetail map property and stays camelCase on the wire,
      # unlike every regular response field.
      current="$(jq -r '.currentStatus // empty' <<<"$confirm")"
      if [ "$err" = "DEPLOYMENT_NOT_RELEASABLE" ]; then
        # A freeze window opened (or a schedule appeared) between the poll and the confirm —
        # the gate simply closed again, so keep waiting.
        echo "  releasability changed before confirmation, re-polling in ${interval_s}s…"
        sleep "$interval_s"
        continue
      fi
      if [ "$current" = "EXECUTED" ]; then
        finish EXECUTED 0 "Deployment request $request_id was confirmed concurrently."
      fi
      problem "confirm-execution conflicted for request ${request_id}" "$confirm"
      finish "$status" 1
    fi
    if [ "$ccode" = "000" ] || [ "$ccode" -ge 500 ]; then
      # If the server executed the confirm before the connection dropped, the next gate poll
      # reports EXECUTED and succeeds; otherwise we just confirm again.
      echo "  confirm-execution unreachable (HTTP ${ccode}), retrying in ${interval_s}s…"
      sleep "$interval_s"
      continue
    fi
    problem "confirm-execution returned HTTP ${ccode} for request ${request_id}" "$confirm"
    finish "$status" 1
  fi

  waiting="  status=$status"
  approvals="$(jq -r 'if .approvals then "\(.approvals.granted)/\(.approvals.required)" else empty end' <<<"$gate")"
  if [ -n "$approvals" ]; then waiting+=" approvals=$approvals"; fi
  if [ "$(jq -r '.frozen' <<<"$gate")" = "true" ]; then
    waiting+=" frozen=true"
    freeze_reason="$(jq -r '.freeze_reason // empty' <<<"$gate")"
    if [ -n "$freeze_reason" ]; then waiting+=" (${freeze_reason})"; fi
  fi
  scheduled_for="$(jq -r '.scheduled_for // empty' <<<"$gate")"
  if [ -n "$scheduled_for" ]; then waiting+=" scheduled_for=$scheduled_for"; fi
  echo "$waiting, waiting ${interval_s}s…"
  sleep "$interval_s"
done

finish "$status" 1 "Timed out after ${AF_WAIT_TIMEOUT:-30m} waiting for deployment request $request_id (last status: $status)"
