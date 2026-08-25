# Generic deployment-gate integration (curl)

The raw API sequence behind the GitHub / GitLab / Azure wrappers, for any other CI system
(Jenkins, CircleCI, Bitbucket Pipelines, …). All calls authenticate with an AccessFlow API key —
`Authorization: ApiKey <af_...>` (or `X-API-Key: <af_...>`) — and should send
`X-AccessFlow-CI: true`. Responses use `snake_case` keys; keys with null values are omitted, so
extract with `jq -r '.field // empty'`.

Fail-closed contract: treat a gate **404**, any non-retryable error, a terminal status
(`REJECTED` / `TIMED_OUT` / `CANCELLED` / `FAILED`) or your own wall-clock timeout as **not
releasable — fail the pipeline**. Only `releasable: true` followed by a confirmed execution is a
green light; `status: APPROVED` alone is not (the request may be frozen or scheduled). Transient
5xx / network errors may be retried until your deadline.

## 1. Submit the deployment request

Idempotent on `(pipeline_id, environment, version, external_run_id)` — pass your CI system's
run/build id as `external_run_id` so a re-run re-attaches (HTTP 200) instead of duplicating
(HTTP 202 on first create).

```bash
request_id=$(curl -fsS -X POST \
  -H "Authorization: ApiKey $ACCESSFLOW_API_KEY" \
  -H "X-AccessFlow-CI: true" \
  -H "Content-Type: application/json" \
  -d '{
        "pipeline_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "environment": "production",
        "version": "2.4.1",
        "external_run_id": "'"$BUILD_ID"'",
        "run_url": "'"$BUILD_URL"'",
        "commit_sha": "'"$COMMIT_SHA"'",
        "metadata": {"changelog": "…"}
      }' \
  "$ACCESSFLOW_URL/api/v1/deployment-requests" | jq -r '.id')
```

`pipeline_id` is the deployment pipeline's UUID (AccessFlow admin page or Terraform output) —
the trigger API does not resolve names. Optional fields: `artifact_ref`, `justification`,
`scheduled_for` (ISO-8601 deferral), `break_glass: true` (emergency bypass — requires a
`can_break_glass` grant and an environment with `allow_break_glass`; no admin bypass).

## 2. Poll the deployment gate

Capture the HTTP status code alongside the body — the decision tree below branches on both, so
do **not** use `curl -f` (it discards the body and collapses every ≥400 into exit 22):

```bash
resp=$(curl -sS -w $'\n%{http_code}' \
  -H "Authorization: ApiKey $ACCESSFLOW_API_KEY" \
  -H "X-AccessFlow-CI: true" \
  "$ACCESSFLOW_URL/api/v1/deployment-gate?request_id=$request_id")
code="${resp##*$'\n'}"; gate="${resp%$'\n'*}"
# gate = {"request_id":"…","status":"APPROVED","releasable":true,
#         "approvals":{"required":2,"granted":2},"frozen":false,"ai_risk_level":"LOW"}
```

Loop (e.g. every 15 s, up to 30 min):

- `releasable == true` → go to step 3.
- `status` in `REJECTED` / `TIMED_OUT` / `CANCELLED` / `FAILED` → **fail the pipeline**.
- `status == EXECUTED` → already confirmed (a previous attempt of this run) → proceed to deploy.
- HTTP 404 → **fail the pipeline** (unknown or invisible request — the gate never answers
  "releasable" for something it cannot see).
- Anything else (`PENDING_AI`, `PENDING_REVIEW`, approved-but-frozen, approved-but-scheduled) →
  keep polling; `frozen` / `freeze_reason` / `scheduled_for` explain the wait.

## 3. Confirm execution

Acknowledge that the pipeline is proceeding — this flips the request to `EXECUTED` and is
idempotent: re-confirming an already-`EXECUTED` request answers a plain 200. Capture the status
code here too, because the 409s must be told apart:

```bash
resp=$(curl -sS -X POST -w $'\n%{http_code}' \
  -H "Authorization: ApiKey $ACCESSFLOW_API_KEY" \
  -H "X-AccessFlow-CI: true" \
  "$ACCESSFLOW_URL/api/v1/deployment-requests/$request_id/confirm-execution")
code="${resp##*$'\n'}"; body="${resp%$'\n'*}"
```

- `200` → confirmed, run your deployment.
- `409` with `error: "DEPLOYMENT_NOT_RELEASABLE"` → the gate closed again between your poll and
  the confirm (e.g. a freeze window opened) — go back to step 2.
- `409` with `error: "DEPLOYMENT_REQUEST_INVALID_STATE"` and `currentStatus: "EXECUTED"` (that
  key is camelCase) → a concurrent run confirmed first — treat as success.
- Anything else → fail the pipeline.

## 4. Report the outcome

Always report, even on failure (run this in your pipeline's finally/post block). Again without
`curl -f`, since a specific 409 is benign here:

```bash
resp=$(curl -sS -X POST -w $'\n%{http_code}' \
  -H "Authorization: ApiKey $ACCESSFLOW_API_KEY" \
  -H "X-AccessFlow-CI: true" \
  -H "Content-Type: application/json" \
  -d '{"outcome": "SUCCEEDED"}' \
  "$ACCESSFLOW_URL/api/v1/deployment-requests/$request_id/outcome")
code="${resp##*$'\n'}"; body="${resp%$'\n'*}"
```

- `outcome`: `SUCCEEDED`, `FAILED` (also flips the request status to `FAILED`), or
  `ROLLED_BACK` (on a review-required environment this opens a rollback follow-up review).
  Optional `detail` (≤ 4000 chars).
- Idempotent: repeating the same outcome answers 200; a *different* outcome answers HTTP 409
  `DEPLOYMENT_OUTCOME_CONFLICT` — a real inconsistency, surface it.
- HTTP 409 `DEPLOYMENT_REQUEST_INVALID_STATE` means the request never reached `EXECUTED`
  (the gate rejected or timed out) — nothing to report; do not fail your post block on it.

## Secret handling

Store the API key as your CI system's masked/protected secret. Never enable shell tracing
(`set -x`) around these calls, and never interpolate the key anywhere except the
`Authorization` header.
