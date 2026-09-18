#!/usr/bin/env bash
# Offline tests for the deployment-gate / deployment-outcome action scripts. Stubs curl with
# fake-curl.sh (canned responses per call, call log) and asserts exit codes, GITHUB_OUTPUT
# contents, and call sequences — no network, no backend. Run locally with:
#   bash .github/actions/tests/deployment-gate-test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=harness.sh
. "$here/harness.sh"
gate_script="$here/../deployment-gate/deployment-gate.sh"
outcome_script="$here/../deployment-outcome/deployment-outcome.sh"

export AF_ENDPOINT="http://accessflow.test"
export AF_API_KEY="af_test_key"
export AF_PIPELINE_ID="11111111-1111-1111-1111-111111111111"
export AF_VERSION="2.4.1"
export AF_ENVIRONMENT="production"
export AF_RUN_ID="4242"
export AF_RUN_URL="http://ci.test/runs/4242"
export AF_WAIT_TIMEOUT="30s"
export AF_POLL_INTERVAL="0s"

confirm_calls() { calls 'POST .*confirm-execution'; }
submit_calls() { calls 'POST .*/deployment-requests$'; }

scenario "releasable-on-third-poll-confirms-once"
resp 1 202 '{"id":"req-1","status":"PENDING_AI"}'
resp 2 200 '{"request_id":"req-1","status":"PENDING_REVIEW","releasable":false,"approvals":{"required":1,"granted":0}}'
resp 3 200 '{"request_id":"req-1","status":"APPROVED","releasable":false,"frozen":true,"freeze_reason":"weekend freeze","approvals":{"required":1,"granted":1}}'
resp 4 200 '{"request_id":"req-1","status":"APPROVED","releasable":true,"ai_risk_level":"LOW","approvals":{"required":1,"granted":1}}'
resp 5 200 '{"id":"req-1","status":"EXECUTED"}'
printf '{"changelog": "fix things"}' >"$MOCK_DIR/metadata.json"
AF_COMMIT_SHA="abc1234" AF_JUSTIFICATION="ship it" AF_METADATA_FILE="$MOCK_DIR/metadata.json" \
  run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 1 "$(confirm_calls)"
assert_output "request-id=req-1"
assert_output "status=EXECUTED"
assert_output "ai-risk-level=LOW"
assert_log_contains "weekend freeze"
# The submit body must use the snake_case wire names the backend binds (SNAKE_CASE Jackson).
assert_body_field 1 '.pipeline_id' "$AF_PIPELINE_ID"
assert_body_field 1 '.environment' "$AF_ENVIRONMENT"
assert_body_field 1 '.version' "$AF_VERSION"
assert_body_field 1 '.external_run_id' "$AF_RUN_ID"
assert_body_field 1 '.run_url' "$AF_RUN_URL"
assert_body_field 1 '.commit_sha' "abc1234"
assert_body_field 1 '.justification' "ship it"
assert_body_field 1 '.metadata.changelog' "fix things"
assert_body_field 1 '.break_glass // "absent"' "absent"

scenario "rejected-fails-without-confirming"
resp 1 202 '{"id":"req-2","status":"PENDING_AI"}'
resp 2 200 '{"request_id":"req-2","status":"PENDING_REVIEW","releasable":false}'
resp 3 200 '{"request_id":"req-2","status":"REJECTED","releasable":false}'
run "$gate_script"
assert_eq "exit code" 1 "$run_rc"
assert_eq "confirm-execution calls" 0 "$(confirm_calls)"
assert_output "status=REJECTED"
assert_log_contains "terminal status REJECTED"

scenario "timeout-fails"
resp 1 202 '{"id":"req-3","status":"PENDING_AI"}'
resp default 200 '{"request_id":"req-3","status":"PENDING_REVIEW","releasable":false}'
AF_WAIT_TIMEOUT="2s" AF_POLL_INTERVAL="1s" run "$gate_script"
assert_eq "exit code" 1 "$run_rc"
assert_eq "confirm-execution calls" 0 "$(confirm_calls)"
assert_log_contains "Timed out after 2s"

scenario "gate-404-fails-closed"
resp 1 202 '{"id":"req-4","status":"PENDING_AI"}'
resp 2 404 '{"title":"Not Found","detail":"deployment request not found"}'
run "$gate_script"
assert_eq "exit code" 1 "$run_rc"
assert_eq "confirm-execution calls" 0 "$(confirm_calls)"
assert_log_contains "fail closed"
# The ProblemDetail is echoed under the error line so the failure is debuggable from the log.
assert_log_contains "Not Found: deployment request not found"

scenario "5xx-blip-is-retried"
resp 1 202 '{"id":"req-5","status":"PENDING_AI"}'
resp 2 502 '<html>bad gateway</html>'
resp 3 "EXIT 7"
resp 4 200 '{"request_id":"req-5","status":"APPROVED","releasable":true,"ai_risk_level":"MEDIUM"}'
resp 5 200 '{"id":"req-5","status":"EXECUTED"}'
run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 1 "$(confirm_calls)"
assert_output "status=EXECUTED"
assert_output "ai-risk-level=MEDIUM"

scenario "not-releasable-confirm-race-repolls"
resp 1 202 '{"id":"req-6","status":"PENDING_AI"}'
resp 2 200 '{"request_id":"req-6","status":"APPROVED","releasable":true}'
resp 3 409 '{"title":"Conflict","error":"DEPLOYMENT_NOT_RELEASABLE","currentStatus":"APPROVED"}'
resp 4 200 '{"request_id":"req-6","status":"APPROVED","releasable":false,"frozen":true,"freeze_reason":"incident freeze"}'
resp 5 200 '{"request_id":"req-6","status":"APPROVED","releasable":true}'
resp 6 200 '{"id":"req-6","status":"EXECUTED"}'
run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 2 "$(confirm_calls)"
assert_output "status=EXECUTED"

scenario "concurrent-confirm-counts-as-success"
resp 1 202 '{"id":"req-7","status":"PENDING_AI"}'
resp 2 200 '{"request_id":"req-7","status":"APPROVED","releasable":true}'
resp 3 409 '{"title":"Conflict","error":"DEPLOYMENT_REQUEST_INVALID_STATE","currentStatus":"EXECUTED"}'
run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_output "status=EXECUTED"

scenario "idempotent-replay-reports-executed"
resp 1 200 '{"id":"req-8","status":"EXECUTED"}'
resp 2 200 '{"request_id":"req-8","status":"EXECUTED","releasable":false}'
run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 0 "$(confirm_calls)"
assert_log_contains "Re-attached to existing deployment request req-8"
assert_output "status=EXECUTED"

# --- 429 handling (#873): every call site retries a rate limit and honours Retry-After. With
# AF_POLL_INTERVAL=0s, a "retrying in 1s" line proves the header beat the fixed interval.

scenario "submit-429-honours-retry-after"
resp 1 429 '{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED","retryAfterSeconds":1}'
hdr 1 'Retry-After: 1' 'Content-Type: application/problem+json'
resp 2 202 '{"id":"req-9","status":"PENDING_AI"}'
resp 3 200 '{"request_id":"req-9","status":"APPROVED","releasable":true,"ai_risk_level":"LOW"}'
resp 4 200 '{"id":"req-9","status":"EXECUTED"}'
run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "submission calls" 2 "$(submit_calls)"
assert_eq "confirm-execution calls" 1 "$(confirm_calls)"
assert_log_contains "rate limited (HTTP 429), retrying submission in 1s"
assert_output "request-id=req-9"
assert_output "status=EXECUTED"

scenario "gate-poll-429-honours-retry-after"
resp 1 202 '{"id":"req-10","status":"PENDING_AI"}'
resp 2 429 '{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED"}'
hdr 2 'retry-after: 1'
resp 3 200 '{"request_id":"req-10","status":"APPROVED","releasable":true}'
resp 4 200 '{"id":"req-10","status":"EXECUTED"}'
run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 1 "$(confirm_calls)"
assert_log_contains "rate limited (HTTP 429), retrying in 1s"
assert_output "status=EXECUTED"

scenario "confirm-429-repolls-and-confirms-again"
resp 1 202 '{"id":"req-11","status":"PENDING_AI"}'
resp 2 200 '{"request_id":"req-11","status":"APPROVED","releasable":true}'
resp 3 429 '{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED"}'
hdr 3 'Retry-After: 1'
resp 4 200 '{"request_id":"req-11","status":"APPROVED","releasable":true}'
resp 5 200 '{"id":"req-11","status":"EXECUTED"}'
run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 2 "$(confirm_calls)"
assert_log_contains "confirm-execution rate limited (HTTP 429), retrying in 1s"
assert_output "status=EXECUTED"

scenario "gate-429-without-retry-after-uses-the-poll-interval"
resp 1 202 '{"id":"req-12","status":"PENDING_AI"}'
resp 2 429 '{"title":"Too Many Requests"}'
resp 3 200 '{"request_id":"req-12","status":"APPROVED","releasable":true}'
resp 4 200 '{"id":"req-12","status":"EXECUTED"}'
run "$gate_script"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "rate limited (HTTP 429), retrying in 0s"
assert_output "status=EXECUTED"

scenario "retry-after-is-capped-by-the-deadline"
resp 1 202 '{"id":"req-13","status":"PENDING_AI"}'
resp 2 429 '{"title":"Too Many Requests"}'
hdr 2 'Retry-After: 600'
resp default 200 '{"request_id":"req-13","status":"PENDING_REVIEW","releasable":false}'
AF_WAIT_TIMEOUT="2s" run "$gate_script"
assert_eq "exit code" 1 "$run_rc"
# SECONDS is whole-second granular, so the capped delay is 1s or 2s — never the header's 600s.
assert_log_contains "rate limited (HTTP 429), retrying in "
assert_log_not_contains "retrying in 600s"
assert_log_contains "Timed out after 2s"
assert_eq "confirm-execution calls" 0 "$(confirm_calls)"

scenario "submit-429-past-the-deadline-fails"
resp default 429 '{"title":"Too Many Requests","detail":"limit is 1 per minute"}'
hdr 1 'Retry-After: 1'
AF_WAIT_TIMEOUT="1s" run "$gate_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "Deployment request submission failed (HTTP 429)"
assert_log_contains "limit is 1 per minute"
assert_eq "confirm-execution calls" 0 "$(confirm_calls)"

export AF_REQUEST_ID="req-1"

scenario "outcome-success-maps-job-status"
resp 1 200 '{"id":"req-1","status":"EXECUTED","outcome":"SUCCEEDED"}'
AF_JOB_STATUS="success" run "$outcome_script"
assert_eq "exit code" 0 "$run_rc"
assert_output "status=EXECUTED"
assert_log_contains "Outcome SUCCEEDED reported"

scenario "outcome-never-executed-is-benign"
resp 1 409 '{"title":"Conflict","error":"DEPLOYMENT_REQUEST_INVALID_STATE","currentStatus":"REJECTED"}'
AF_JOB_STATUS="failure" run "$outcome_script"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "never executed"

scenario "outcome-429-then-200-honours-retry-after"
resp 1 429 '{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED"}'
hdr 1 'Retry-After: 1'
resp 2 200 '{"id":"req-1","status":"EXECUTED","outcome":"SUCCEEDED"}'
AF_JOB_STATUS="success" run "$outcome_script"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "rate limited (HTTP 429), retrying in 1s"
assert_log_contains "Outcome SUCCEEDED reported"
assert_output "status=EXECUTED"

scenario "outcome-429-past-retry-timeout-fails"
resp default 429 '{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED"}'
AF_RETRY_TIMEOUT="1s" AF_JOB_STATUS="success" run "$outcome_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "returned HTTP 429"
assert_log_contains "Too Many Requests"

scenario "outcome-conflict-fails"
resp 1 409 '{"title":"Conflict","error":"DEPLOYMENT_OUTCOME_CONFLICT"}'
AF_OUTCOME="ROLLED_BACK" run "$outcome_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "HTTP 409"

scenario "outcome-without-request-id-skips"
AF_REQUEST_ID="" AF_JOB_STATUS="success" run "$outcome_script"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "never created a request"

scenario "outcome-cancelled-job-skips"
AF_JOB_STATUS="cancelled" run "$outcome_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "curl calls" 0 "$(grep -c '' "$MOCK_DIR/calls.log" 2>/dev/null || echo 0)"

report "deployment action script"
