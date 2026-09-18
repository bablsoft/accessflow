#!/usr/bin/env bash
# Offline tests for the GitLab and Azure Pipelines CI templates. The inline bash bodies are
# extracted from the YAML (extract-template-scripts.py, PyYAML) and run against the same
# fake-curl double as the action scripts — the templates are otherwise only YAML-parsed, and
# a linter cannot see into a `script:` string, so this is also where they get linted. Run
# locally with:
#   bash .github/actions/tests/ci-templates-test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=harness.sh
. "$here/harness.sh"

scripts="$tmp/templates"
python3 "$here/extract-template-scripts.py" "$scripts" >/dev/null
if command -v shellcheck >/dev/null; then
  # SC2064: `trap "rm -f $hf"` expands now on purpose — the GitLab bodies run under bash -c '…'
  # and can never contain a single quote. SC2015/SC2016: deliberate one-line idioms there too.
  shellcheck -s bash -e SC2064,SC2015,SC2016 "$scripts"/*.sh
  echo "shellcheck: extracted template scripts are clean"
else
  echo "shellcheck not on PATH — skipping the lint of the extracted template scripts"
fi

export ACCESSFLOW_ENDPOINT="http://accessflow.test"
export ACCESSFLOW_API_KEY="af_test_key"
export AF_PIPELINE_ID="11111111-1111-1111-1111-111111111111"
export AF_VERSION="2.4.1"
export AF_ENVIRONMENT="production"
export AF_WAIT_TIMEOUT="30s"
export AF_POLL_INTERVAL="0s"
export AF_RETRY_TIMEOUT="2m"
export AF_NAME="ci-db"
export AF_DB_TYPE="POSTGRESQL"
export AF_DATASOURCE_ID="22222222-2222-2222-2222-222222222222"
export AF_SQL="SELECT 1"
export AF_TIMEOUT_SECONDS="30"
export AF_POLL_INTERVAL_SECONDS="0"
# GitLab predefined variables the deployment job reads; Azure gets the same through AF_RUN_*.
export CI_PIPELINE_ID="4242"
export CI_PIPELINE_URL="http://gitlab.test/pipelines/4242"
export CI_COMMIT_SHA="abc1234"
export AF_RUN_ID="4242"
export AF_RUN_URL="http://azure.test/builds/4242"

confirm_calls() { calls 'POST .*confirm-execution'; }

# ---------------------------------------------------------------- GitLab: provision / run-query

scenario "gitlab-provision-creates-and-retries-a-429-lookup"
resp 1 429 '{"title":"Too Many Requests","error":"SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED"}'
hdr 1 'Retry-After: 1'
resp 2 200 '{"content":[]}'
resp 3 201 '{"id":"ds-1","name":"ci-db"}'
AF_HOST="db.internal" AF_PORT="5432" AF_AI_ANALYSIS_ENABLED="false" run "$scripts/gitlab-provision-datasource.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "lookup calls" 2 "$(calls 'GET .*/datasources\?size=100$')"
assert_eq "create calls" 1 "$(calls 'POST .*/datasources$')"
assert_log_contains "rate limited (HTTP 429), retrying GET in 1s"
assert_log_contains "Datasource ci-db provisioned: ds-1"
assert_body_field 3 '.name' "ci-db"
assert_body_field 3 '.host' "db.internal"
assert_body_field 3 '.port' "5432"
assert_body_field 3 '.ai_analysis_enabled' "false"

scenario "gitlab-provision-updates-and-retries-a-429-put"
resp 1 200 '{"content":[{"id":"ds-2","name":"ci-db"}]}'
resp 2 429 '{"title":"Too Many Requests"}'
resp 3 200 '{"id":"ds-2","name":"ci-db"}'
AF_RETRY_TIMEOUT="1s" run "$scripts/gitlab-provision-datasource.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "update calls" 2 "$(calls 'PUT .*/datasources/ds-2$')"
# No header → the 5s fallback, capped at the 1s left before the retry-timeout deadline.
assert_log_contains "rate limited (HTTP 429), retrying PUT in 1s"
assert_log_contains "Datasource ci-db provisioned: ds-2"

scenario "gitlab-provision-429-past-retry-timeout-fails"
resp default 429 '{"title":"Too Many Requests","detail":"limit is 1 per minute"}'
hdr 1 'Retry-After: 1'
AF_RETRY_TIMEOUT="1s" run "$scripts/gitlab-provision-datasource.sh"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "failed HTTP 429"
assert_log_contains "limit is 1 per minute"

scenario "gitlab-provision-4xx-fails"
resp 1 200 '{"content":[]}'
resp 2 422 '{"title":"Unprocessable","detail":"ai_config_id is required"}'
run "$scripts/gitlab-provision-datasource.sh"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "failed HTTP 422"
assert_log_contains "ai_config_id is required"

scenario "gitlab-query-approved-then-executed"
resp 1 202 '{"id":"q-1","status":"PENDING_AI"}'
resp 2 200 '{"id":"q-1","status":"PENDING_REVIEW"}'
resp 3 200 '{"id":"q-1","status":"APPROVED"}'
resp 4 200 '{"id":"q-1","status":"EXECUTED"}'
AF_JUSTIFICATION="nightly" run "$scripts/gitlab-run-query.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "execute calls" 1 "$(calls 'POST .*/queries/q-1/execute$')"
assert_log_contains "Query q-1 executed"
assert_body_field 1 '.datasource_id' "$AF_DATASOURCE_ID"
assert_body_field 1 '.sql' "$AF_SQL"
assert_body_field 1 '.justification' "nightly"

scenario "gitlab-query-429-at-submit-poll-and-execute"
resp 1 429 '{"title":"Too Many Requests"}'
hdr 1 'Retry-After: 1'
resp 2 202 '{"id":"q-2","status":"PENDING_AI"}'
resp 3 429 '{"title":"Too Many Requests"}'
hdr 3 'retry-after: 1'
resp 4 200 '{"id":"q-2","status":"APPROVED"}'
resp 5 429 '{"title":"Too Many Requests"}'
hdr 5 'Retry-After: 1'
resp 6 200 '{"id":"q-2","status":"EXECUTED"}'
run "$scripts/gitlab-run-query.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "submit calls" 2 "$(calls 'POST .*/queries$')"
assert_eq "execute calls" 2 "$(calls 'POST .*/queries/q-2/execute$')"
assert_log_contains "rate limited (HTTP 429), retrying POST in 1s"
assert_log_contains "rate limited (HTTP 429), retrying GET in 1s"
assert_log_contains "Query q-2 executed"

scenario "gitlab-query-rejected-fails"
resp 1 202 '{"id":"q-3","status":"PENDING_AI"}'
resp 2 200 '{"id":"q-3","status":"REJECTED"}'
run "$scripts/gitlab-run-query.sh"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "Query q-3 ended: REJECTED"

scenario "gitlab-query-429-past-the-timeout-fails"
resp default 429 '{"title":"Too Many Requests","detail":"limit is 1 per minute"}'
hdr 1 'Retry-After: 1'
AF_TIMEOUT_SECONDS="1" run "$scripts/gitlab-run-query.sh"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "failed HTTP 429"
assert_log_contains "limit is 1 per minute"

# ---------------------------------------------------------------- GitLab: deployment gate / outcome

scenario "gitlab-gate-releasable-confirms-and-publishes-dotenv"
resp 1 202 '{"id":"req-1","status":"PENDING_AI"}'
resp 2 200 '{"request_id":"req-1","status":"PENDING_REVIEW","releasable":false}'
resp 3 200 '{"request_id":"req-1","status":"APPROVED","releasable":true}'
resp 4 200 '{"id":"req-1","status":"EXECUTED"}'
run "$scripts/gitlab-deployment-gate.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 1 "$(confirm_calls)"
assert_eq "dotenv artifact" "AF_REQUEST_ID=req-1" "$(cat "$MOCK_DIR/accessflow-deployment.env")"
assert_body_field 1 '.pipeline_id' "$AF_PIPELINE_ID"
assert_body_field 1 '.external_run_id' "$CI_PIPELINE_ID"
assert_body_field 1 '.run_url' "$CI_PIPELINE_URL"
assert_body_field 1 '.commit_sha' "$CI_COMMIT_SHA"

scenario "gitlab-gate-404-fails-closed"
resp 1 202 '{"id":"req-2","status":"PENDING_AI"}'
resp 2 404 '{"title":"Not Found"}'
run "$scripts/gitlab-deployment-gate.sh"
assert_eq "exit code" 1 "$run_rc"
assert_eq "confirm-execution calls" 0 "$(confirm_calls)"
assert_log_contains "fail closed"

scenario "gitlab-gate-429-at-submit-poll-and-confirm"
resp 1 429 '{"title":"Too Many Requests"}'
hdr 1 'Retry-After: 1'
resp 2 202 '{"id":"req-3","status":"PENDING_AI"}'
resp 3 429 '{"title":"Too Many Requests"}'
hdr 3 'Retry-After: 1'
resp 4 200 '{"request_id":"req-3","status":"APPROVED","releasable":true}'
resp 5 429 '{"title":"Too Many Requests"}'
hdr 5 'Retry-After: 1'
resp 6 200 '{"request_id":"req-3","status":"APPROVED","releasable":true}'
resp 7 200 '{"id":"req-3","status":"EXECUTED"}'
run "$scripts/gitlab-deployment-gate.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 2 "$(confirm_calls)"
assert_log_contains "rate limited (HTTP 429), retrying submission in 1s"
assert_log_contains "rate limited (HTTP 429), retrying in 1s"
assert_log_contains "confirm rate limited (HTTP 429), retrying in 1s"
assert_log_contains "gate open"

scenario "gitlab-gate-rejected-fails"
resp 1 202 '{"id":"req-4","status":"PENDING_AI"}'
resp 2 200 '{"request_id":"req-4","status":"REJECTED","releasable":false}'
run "$scripts/gitlab-deployment-gate.sh"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "ended: REJECTED"

export AF_REQUEST_ID="req-1"

scenario "gitlab-outcome-429-then-200"
resp 1 429 '{"title":"Too Many Requests"}'
hdr 1 'Retry-After: 1'
resp 2 200 '{"id":"req-1","status":"EXECUTED","outcome":"SUCCEEDED"}'
AF_OUTCOME="SUCCEEDED" run "$scripts/gitlab-deployment-outcome.sh"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "rate limited (HTTP 429), retrying in 1s"
assert_log_contains "Outcome SUCCEEDED reported for req-1"
assert_body_field 2 '.outcome' "SUCCEEDED"

scenario "gitlab-outcome-never-executed-is-benign"
resp 1 409 '{"title":"Conflict","error":"DEPLOYMENT_REQUEST_INVALID_STATE"}'
AF_OUTCOME="FAILED" run "$scripts/gitlab-deployment-outcome.sh"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "never executed"

scenario "gitlab-outcome-without-request-id-skips"
AF_REQUEST_ID="" AF_OUTCOME="SUCCEEDED" run "$scripts/gitlab-deployment-outcome.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "curl calls" 0 "$(calls '.')"

# ---------------------------------------------------------------- Azure: deployment gate / outcome

scenario "azure-gate-releasable-confirms-and-sets-variable"
resp 1 202 '{"id":"req-5","status":"PENDING_AI"}'
resp 2 200 '{"request_id":"req-5","status":"APPROVED","releasable":false,"frozen":true}'
resp 3 200 '{"request_id":"req-5","status":"APPROVED","releasable":true}'
resp 4 200 '{"id":"req-5","status":"EXECUTED"}'
AF_BREAK_GLASS="False" run "$scripts/azure-deployment-gate.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 1 "$(confirm_calls)"
assert_log_contains "##vso[task.setvariable variable=accessflowRequestId]req-5"
assert_body_field 1 '.external_run_id' "$AF_RUN_ID"
assert_body_field 1 '.run_url' "$AF_RUN_URL"
assert_body_field 1 '.break_glass // "absent"' "absent"

scenario "azure-gate-429-at-submit-poll-and-confirm"
resp 1 429 '{"title":"Too Many Requests"}'
hdr 1 'Retry-After: 1'
resp 2 202 '{"id":"req-6","status":"PENDING_AI"}'
resp 3 429 '{"title":"Too Many Requests"}'
hdr 3 'Retry-After: 1'
resp 4 200 '{"request_id":"req-6","status":"APPROVED","releasable":true}'
resp 5 429 '{"title":"Too Many Requests"}'
hdr 5 'Retry-After: 1'
resp 6 200 '{"request_id":"req-6","status":"APPROVED","releasable":true}'
resp 7 200 '{"id":"req-6","status":"EXECUTED"}'
run "$scripts/azure-deployment-gate.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "confirm-execution calls" 2 "$(confirm_calls)"
assert_log_contains "rate limited (HTTP 429), retrying submission in 1s"
assert_log_contains "rate limited (HTTP 429), retrying in 1s"
assert_log_contains "confirm rate limited (HTTP 429), retrying in 1s"

scenario "azure-gate-404-fails-closed"
resp 1 202 '{"id":"req-7","status":"PENDING_AI"}'
resp 2 404 '{"title":"Not Found"}'
run "$scripts/azure-deployment-gate.sh"
assert_eq "exit code" 1 "$run_rc"
assert_log_contains "##vso[task.logissue type=error]"
assert_log_contains "fail closed"

scenario "azure-outcome-429-then-200-maps-job-status"
resp 1 429 '{"title":"Too Many Requests"}'
hdr 1 'Retry-After: 1'
resp 2 200 '{"id":"req-1","status":"EXECUTED","outcome":"FAILED"}'
AGENT_JOBSTATUS="Failed" run "$scripts/azure-deployment-outcome.sh"
assert_eq "exit code" 0 "$run_rc"
assert_log_contains "rate limited (HTTP 429), retrying in 1s"
assert_log_contains "Outcome FAILED reported for req-1"
assert_body_field 2 '.outcome' "FAILED"

scenario "azure-outcome-unexpanded-macro-skips"
# Azure leaves the macro literal when the gate step never set the variable.
# shellcheck disable=SC2016
AF_REQUEST_ID='$(accessflowRequestId)' AGENT_JOBSTATUS="Succeeded" run "$scripts/azure-deployment-outcome.sh"
assert_eq "exit code" 0 "$run_rc"
assert_eq "curl calls" 0 "$(calls '.')"
assert_log_contains "never created a request"

report "CI template script"
