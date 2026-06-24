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

See [`examples/github-workflow.yml`](examples/github-workflow.yml).

## GitLab CI templates

[`gitlab/accessflow.gitlab-ci.yml`](gitlab/accessflow.gitlab-ci.yml) exposes two `extends`-able
hidden jobs — `.accessflow_provision_datasource` and `.accessflow_run_query` — that mirror the
GitHub Actions. `include:` the file and extend a job, setting the `AF_*` variables.

See [`examples/gitlab-pipeline.yml`](examples/gitlab-pipeline.yml).

## Notes

- Both `run-query` paths send `X-AccessFlow-CI: true` so context-aware routing policies (AF-446)
  can recognise the CI origin instead of failing closed. Pair with an `AUTO_APPROVE` routing
  policy (or appropriate review plan) for unattended execution.
- `run-query` fails the job on any non-`EXECUTED` terminal status (`REJECTED` / `FAILED` /
  `TIMED_OUT` / `CANCELLED`) or on timeout.
- Scripts require `curl` and `jq` (preinstalled on GitHub-hosted runners; the GitLab template
  installs them via `apk`).
