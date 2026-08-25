# AccessFlow CI templates

Reusable CI building blocks that wrap the AccessFlow REST API for pipelines, complementing the
[Terraform/OpenTofu provider](../terraform-provider/). All of these authenticate with an
AccessFlow **API key** (`Authorization: ApiKey <af_...>`) — bootstrap a service-account key
declaratively (see [docs/16-iac.md](../docs/16-iac.md)) and store it as a CI secret.

## GitHub Actions (composite)

Located under [`.github/actions/`](../.github/actions/) so they can be referenced from any repo:

| Action | Reference | Purpose |
|---|---|---|
| Provision datasource | `bablsoft/accessflow/.github/actions/provision-datasource@<ref>` | Idempotently create/update a datasource (by name) |
| Run query | `bablsoft/accessflow/.github/actions/run-query@<ref>` | Submit a query and wait for a terminal status |
| Deployment gate | `bablsoft/accessflow/.github/actions/deployment-gate@<ref>` | Gate a deployment on AccessFlow approval (AF-694) |
| Deployment outcome | `bablsoft/accessflow/.github/actions/deployment-outcome@<ref>` | Report the post-deploy outcome (`if: always()`) |

See [`examples/github-workflow.yml`](examples/github-workflow.yml) and
[`examples/github-deployment-workflow.yml`](examples/github-deployment-workflow.yml).

## GitLab CI templates

[`gitlab/accessflow.gitlab-ci.yml`](gitlab/accessflow.gitlab-ci.yml) exposes two `extends`-able
hidden jobs — `.accessflow_provision_datasource` and `.accessflow_run_query` — that mirror the
GitHub Actions. `include:` the file and extend a job, setting the `AF_*` variables.

See [`examples/gitlab-pipeline.yml`](examples/gitlab-pipeline.yml). The deployment-gate hidden
jobs live in a separate file — see the next section.

## Deployment gate (AF-694)

Wraps the deployment-governance machine API (epic AF-682): submit a deployment request
(idempotent on the CI run id), poll `GET /api/v1/deployment-gate` until `releasable: true`,
confirm execution, then — separately — report the outcome. The same flow is available for all
three platforms, plus a raw-`curl` walkthrough for anything else in
[`examples/generic-curl-deployment.md`](examples/generic-curl-deployment.md).

**Fail-closed contract.** A gate `404` (unknown or invisible request), any non-retryable error,
a terminal status (`REJECTED` / `TIMED_OUT` / `CANCELLED` / `FAILED`), or the wait-timeout
elapsing fails the job — nothing is ever released by default. `status: APPROVED` alone is not a
green light: the gate also folds in freeze windows (`frozen`) and deferred releases
(`scheduled_for`). Transient 5xx / network errors are retried until the deadline.

### GitHub Action inputs — `deployment-gate`

| Input | Required | Default | Meaning |
|---|---|---|---|
| `accessflow-url` | yes | — | AccessFlow base URL |
| `api-key` | yes | — | AccessFlow API key (`af_…`) |
| `pipeline-id` | yes | — | Deployment pipeline **UUID** — the trigger API does not resolve names (a trigger-only key cannot list pipelines); copy it from the admin page or Terraform output |
| `version` | yes | — | Version being deployed |
| `environment` | yes | — | Environment name within the pipeline (case-insensitive) |
| `commit-sha` | no | — | Commit SHA (pass `github.sha`) |
| `artifact-ref` | no | — | Artifact reference (image digest, …) |
| `metadata-file` | no | — | Path to a JSON *object* file with release context for the AI analyzer |
| `justification` | no | — | Submission justification |
| `scheduled-for` | no | — | ISO-8601 deferral (mutually exclusive with break-glass) |
| `break-glass` | no | `false` | Emergency bypass — needs a `can_break_glass` grant **and** `allow_break_glass` on the environment; no admin bypass |
| `wait-timeout` | no | `30m` | Wall-clock budget (`90`, `45s`, `30m`, `2h`) |
| `poll-interval` | no | `15s` | Delay between gate polls |

Outputs: `request-id`, `status` (last observed; `EXECUTED` on success), `ai-risk-level` (when
AI analysis ran). The submission passes `external_run_id=${{ github.run_id }}`, so re-running
the workflow re-attaches to the same request instead of duplicating it.

### GitHub Action inputs — `deployment-outcome`

| Input | Required | Default | Meaning |
|---|---|---|---|
| `accessflow-url` | yes | — | AccessFlow base URL |
| `api-key` | yes | — | AccessFlow API key (`af_…`) |
| `request-id` | no | — | The gate step's `request-id` output (empty ⇒ step skips) |
| `job-status` | no | — | Pass `${{ job.status }}`: success→`SUCCEEDED`, failure→`FAILED`, cancelled→skip |
| `outcome` | no | — | Explicit `SUCCEEDED` / `FAILED` / `ROLLED_BACK`; overrides `job-status` |
| `detail` | no | — | Free-form detail (≤ 4000 chars) |

Output: `status`. Run it with `if: always()` (composite actions have no post-run hook). A
request the gate never released is skipped without failing; reporting an outcome that
*conflicts* with one already recorded fails (`DEPLOYMENT_OUTCOME_CONFLICT`).

### GitLab

[`gitlab/accessflow-deployment.gitlab-ci.yml`](gitlab/accessflow-deployment.gitlab-ci.yml)
exposes `.accessflow_deployment_gate` and `.accessflow_deployment_outcome`. Variables mirror
the action inputs (`ACCESSFLOW_ENDPOINT`, `ACCESSFLOW_API_KEY`, `AF_PIPELINE_ID`, `AF_VERSION`,
`AF_ENVIRONMENT`, `AF_WAIT_TIMEOUT`, `AF_POLL_INTERVAL`, optional `AF_ARTIFACT_REF` /
`AF_JUSTIFICATION` / `AF_SCHEDULED_FOR` / `AF_METADATA_FILE` / `AF_BREAK_GLASS` / `AF_DETAIL`;
`commit_sha` and `run_url` come from `$CI_COMMIT_SHA` / `$CI_PIPELINE_URL` automatically);
`external_run_id` is `$CI_PIPELINE_ID`.
The gate job publishes `AF_REQUEST_ID` as a dotenv artifact; GitLab has no job-status input, so
the example wires one outcome job per result (`when: on_failure` for `FAILED`). See
[`examples/gitlab-deployment-pipeline.yml`](examples/gitlab-deployment-pipeline.yml). You can
pre-validate a consumer pipeline with GitLab's own CI Lint (`/-/ci/lint`).

### Azure Pipelines

[`azure/accessflow-deployment.yml`](azure/accessflow-deployment.yml) is a *step* template
(a marketplace extension is deliberately out of scope): parameters mirror the action inputs
(`accessflowUrl`, `apiKeyVariable` — the *name* of the secret variable, default
`accessflow-api-key` — `pipelineId`, `version`, `environment`, `commitSha` — default
`$(Build.SourceVersion)` — `artifactRef`, `justification`, `scheduledFor`, `metadataFile`,
`breakGlass`, `waitTimeout`, `pollInterval`, `reportOutcome`), plus `deploySteps` (a
`stepList` run between the gate and the outcome step, so the `condition: always()` outcome
report sees your deployment's job status). `external_run_id` is `$(Build.BuildId)`. See
[`examples/azure-deployment-pipeline.yml`](examples/azure-deployment-pipeline.yml).

### Service-account key & secrets

Bootstrap a service-account API key declaratively (`bootstrap.serviceAccounts` /
`ApiKeyService.importOrUpdate` — see [docs/16-iac.md](../docs/16-iac.md)) and store it as a
masked/protected CI secret. Triggering needs a per-pipeline `can_trigger` grant (admins bypass
it; break-glass has **no** admin bypass). The scripts never enable `set -x` and only ever place
the key in the `Authorization` header; on Azure the key is mapped through `env:` rather than
inlined, so it cannot leak into expanded logs.

## Notes

- Both `run-query` paths send `X-AccessFlow-CI: true` so context-aware routing policies (AF-446)
  can recognise the CI origin instead of failing closed. Pair with an `AUTO_APPROVE` routing
  policy (or appropriate review plan) for unattended execution.
- `run-query` fails the job on any non-`EXECUTED` terminal status (`REJECTED` / `FAILED` /
  `TIMED_OUT` / `CANCELLED`) or on timeout.
- The deployment wrappers also send `X-AccessFlow-CI: true` for consistency, but deployment
  routing uses its own typed conditions — pair the gate with an `AUTO_APPROVE` *deployment*
  routing policy or a review plan on the pipeline/environment.
- Scripts require `curl` and `jq` (preinstalled on GitHub-hosted runners; the GitLab and Azure
  templates install/assume them via `apk` / the ubuntu image).
