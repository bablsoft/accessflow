#!/usr/bin/env bash
# Offline tests for the provision-datasource / run-query action scripts. Stubs curl with
# fake-curl.sh (canned responses per call, call log) and asserts exit codes, GITHUB_OUTPUT
# contents, and call sequences — no network, no backend. Run locally with:
#   bash .github/actions/tests/query-actions-test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=harness.sh
. "$here/harness.sh"
provision_script="$here/../provision-datasource/provision-datasource.sh"
query_script="$here/../run-query/run-query.sh"

export AF_ENDPOINT="http://accessflow.test"
export AF_API_KEY="af_test_key"
export AF_NAME="ci-db"
export AF_DB_TYPE="POSTGRESQL"
export AF_DATASOURCE_ID="22222222-2222-2222-2222-222222222222"
export AF_SQL="SELECT 1"
export AF_TIMEOUT_SECONDS="30"
export AF_POLL_INTERVAL_SECONDS="0"

# ---------------------------------------------------------------- provision-datasource

scenario "provision-creates-when-absent"
resp 1 200 '{"content":[{"id":"other","name":"not-it"}]}'
resp 2 201 '{"id":"ds-1","name":"ci-db"}'
AF_HOST="db.internal" AF_PORT="5432" AF_AI_ANALYSIS_ENABLED="false" run "$provision_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "create calls" 1 "$(calls 'POST .*/datasources$')"
assert_eq "update calls" 0 "$(calls 'PUT ')"
assert_output "id=ds-1"
assert_body_field 2 '.name' "ci-db"
assert_body_field 2 '.db_type' "POSTGRESQL"
assert_body_field 2 '.ssl_mode' "DISABLE"
assert_body_field 2 '.host' "db.internal"
assert_body_field 2 '.port' "5432"
assert_body_field 2 '.ai_analysis_enabled' "false"

scenario "provision-updates-when-present"
resp 1 200 '{"content":[{"id":"ds-2","name":"ci-db"}]}'
resp 2 200 '{"id":"ds-2","name":"ci-db"}'
run "$provision_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "update calls" 1 "$(calls 'PUT .*/datasources/ds-2$')"
assert_eq "create calls" 0 "$(calls 'POST ')"
assert_output "id=ds-2"

scenario "provision-4xx-fails-with-problem-detail"
resp 1 200 '{"content":[]}'
resp 2 422 '{"title":"Unprocessable","detail":"ai_config_id is required when AI analysis is enabled"}'
run "$provision_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "returned HTTP 422"
assert_log_contains "ai_config_id is required"

# --- 429 handling (#873): every call retries a rate limit and honours Retry-After. A "retrying in
# 1s" line proves the header beat the 5s fallback.

scenario "provision-429-on-lookup-honours-retry-after"
resp 1 429 '{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED","retryAfterSeconds":1}'
hdr 1 'Retry-After: 1' 'Content-Type: application/problem+json'
resp 2 200 '{"content":[]}'
resp 3 201 '{"id":"ds-3","name":"ci-db"}'
run "$provision_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "lookup calls" 2 "$(calls 'GET .*/datasources\?size=100$')"
assert_log_contains "rate limited (HTTP 429), retrying GET in 1s"
assert_output "id=ds-3"

scenario "provision-429-on-create-retries-the-same-body"
resp 1 200 '{"content":[]}'
resp 2 429 '{"title":"Too Many Requests"}'
hdr 2 'retry-after: 1'
resp 3 201 '{"id":"ds-4","name":"ci-db"}'
AF_USERNAME="reader" run "$provision_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "create calls" 2 "$(calls 'POST .*/datasources$')"
assert_log_contains "rate limited (HTTP 429), retrying POST in 1s"
assert_body_field 3 '.username' "reader"
assert_output "id=ds-4"

scenario "provision-429-without-retry-after-caps-at-the-deadline"
resp 1 200 '{"content":[{"id":"ds-5","name":"ci-db"}]}'
resp 2 429 '{"title":"Too Many Requests"}'
resp 3 200 '{"id":"ds-5","name":"ci-db"}'
AF_RETRY_TIMEOUT="1s" run "$provision_script"
assert_eq "exit code" 0 "$run_rc"
# No header → the 5s fallback, capped at the 1s left before the retry-timeout deadline.
assert_log_contains "rate limited (HTTP 429), retrying PUT in 1s"
assert_log_not_contains "retrying PUT in 5s"
assert_output "id=ds-5"

scenario "provision-429-past-retry-timeout-fails"
resp default 429 '{"title":"Too Many Requests","detail":"limit is 1 per minute"}'
hdr 1 'Retry-After: 1'
AF_RETRY_TIMEOUT="1s" run "$provision_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "returned HTTP 429"
assert_log_contains "limit is 1 per minute"
assert_eq "create calls" 0 "$(calls 'POST ')"

scenario "provision-invalid-retry-timeout-fails-fast"
AF_RETRY_TIMEOUT="soon" run "$provision_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "Invalid retry-timeout 'soon'"
assert_eq "curl calls" 0 "$(calls '.')"

# ---------------------------------------------------------------- run-query

scenario "query-approved-then-executed"
resp 1 202 '{"id":"q-1","status":"PENDING_AI"}'
resp 2 200 '{"id":"q-1","status":"PENDING_REVIEW"}'
resp 3 200 '{"id":"q-1","status":"APPROVED"}'
resp 4 200 '{"id":"q-1","status":"EXECUTED"}'
AF_JUSTIFICATION="nightly check" run "$query_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "execute calls" 1 "$(calls 'POST .*/queries/q-1/execute$')"
assert_output "query-id=q-1"
assert_output "status=EXECUTED"
assert_body_field 1 '.datasource_id' "$AF_DATASOURCE_ID"
assert_body_field 1 '.sql' "$AF_SQL"
assert_body_field 1 '.justification' "nightly check"

scenario "query-scheduled-runs-itself"
resp 1 202 '{"id":"q-2","status":"PENDING_AI"}'
resp 2 200 '{"id":"q-2","status":"APPROVED","scheduled_for":"2026-10-01T00:00:00Z"}'
resp 3 200 '{"id":"q-2","status":"EXECUTED"}'
run "$query_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "execute calls" 0 "$(calls 'execute')"
assert_log_contains "(scheduled)"
assert_output "status=EXECUTED"

scenario "query-rejected-fails"
resp 1 202 '{"id":"q-3","status":"PENDING_AI"}'
resp 2 200 '{"id":"q-3","status":"REJECTED"}'
run "$query_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "terminal status REJECTED"
assert_output "status=REJECTED"

scenario "query-timeout-fails"
resp 1 202 '{"id":"q-4","status":"PENDING_AI"}'
resp default 200 '{"id":"q-4","status":"PENDING_REVIEW"}'
AF_TIMEOUT_SECONDS="2" AF_POLL_INTERVAL_SECONDS="1" run "$query_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "Timed out after 2s"
assert_output "status=PENDING_REVIEW"

scenario "query-submit-422-fails-with-problem-detail"
resp 1 422 '{"title":"Unprocessable","detail":"SQL parse error near FORM"}'
run "$query_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "returned HTTP 422"
assert_log_contains "SQL parse error near FORM"

# --- 429 handling (#873) at all three beats: submit, poll, execute. With
# AF_POLL_INTERVAL_SECONDS=0, a "retrying in 1s" line proves the header beat the fixed interval.

scenario "query-submit-429-honours-retry-after"
resp 1 429 '{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED"}'
hdr 1 'Retry-After: 1'
resp 2 202 '{"id":"q-5","status":"PENDING_AI"}'
resp 3 200 '{"id":"q-5","status":"EXECUTED"}'
run "$query_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "submit calls" 2 "$(calls 'POST .*/queries$')"
assert_log_contains "rate limited (HTTP 429), retrying POST in 1s"
assert_body_field 2 '.sql' "$AF_SQL"
assert_output "query-id=q-5"
assert_output "status=EXECUTED"

scenario "query-poll-429-honours-retry-after"
resp 1 202 '{"id":"q-6","status":"PENDING_AI"}'
resp 2 429 '{"title":"Too Many Requests"}'
hdr 2 'retry-after: 1'
resp 3 200 '{"id":"q-6","status":"EXECUTED"}'
run "$query_script"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "rate limited (HTTP 429), retrying GET in 1s"
assert_output "status=EXECUTED"

scenario "query-execute-429-retries-the-execute-once-approved"
resp 1 202 '{"id":"q-7","status":"PENDING_AI"}'
resp 2 200 '{"id":"q-7","status":"APPROVED"}'
resp 3 429 '{"title":"Too Many Requests"}'
hdr 3 'Retry-After: 1'
resp 4 200 '{"id":"q-7","status":"EXECUTED"}'
run "$query_script"
assert_eq "exit code" 0 "$run_rc"
assert_eq "execute calls" 2 "$(calls 'POST .*/queries/q-7/execute$')"
assert_eq "status polls" 1 "$(calls 'GET .*/queries/q-7$')"
assert_log_contains "rate limited (HTTP 429), retrying POST in 1s"
assert_output "status=EXECUTED"

scenario "query-429-without-retry-after-uses-the-poll-interval"
resp 1 202 '{"id":"q-8","status":"PENDING_AI"}'
resp 2 429 '{"title":"Too Many Requests"}'
resp 3 200 '{"id":"q-8","status":"EXECUTED"}'
run "$query_script"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "rate limited (HTTP 429), retrying GET in 0s"
assert_output "status=EXECUTED"

scenario "query-retry-after-is-capped-by-the-timeout"
resp 1 202 '{"id":"q-9","status":"PENDING_AI"}'
resp 2 429 '{"title":"Too Many Requests"}'
hdr 2 'Retry-After: 600'
resp default 429 '{"title":"Too Many Requests","detail":"still limited"}'
AF_TIMEOUT_SECONDS="2" run "$query_script"
assert_eq "exit code" 1 "$run_rc"
# SECONDS is whole-second granular, so the capped delay is 1s or 2s — never the header's 600s.
assert_log_contains "rate limited (HTTP 429), retrying GET in "
assert_log_not_contains "retrying GET in 600s"
assert_log_contains "returned HTTP 429"
assert_eq "execute calls" 0 "$(calls 'execute')"

scenario "query-submit-429-past-the-timeout-fails"
resp default 429 '{"title":"Too Many Requests","detail":"limit is 1 per minute"}'
hdr 1 'Retry-After: 1'
AF_TIMEOUT_SECONDS="1" run "$query_script"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "returned HTTP 429"
assert_log_contains "limit is 1 per minute"

report "query action script"
