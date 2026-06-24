# 16 — Infrastructure as Code (Terraform / OpenTofu & CI Actions)

AccessFlow ships first-class IaC tooling (AF-452) so a Terraform/GitOps pipeline can manage
governance resources declaratively over the REST API, complementing the env-driven
[`bootstrap` module](09-deployment.md#bootstrap-configuration):

- a **Terraform / OpenTofu provider** (`bablsoft/accessflow`) for datasources, review plans,
  routing / row-security / masking policies, AI configs, and notification channels;
- reusable **GitHub Actions** and a **GitLab CI template** that wrap common operations (provision a
  datasource, submit and await a query) for pipelines.

Everything authenticates with an AccessFlow **API key** (`Authorization: ApiKey <af_…>`). The key
inherits its owner's permissions, so use an admin (or admin-role **service account**).

---

## Authentication: bootstrap a service account

A pipeline needs credentials without an interactive login. The `bootstrap` module can seed a
**service account** — an API-key-only user (password login disabled) whose raw key you supply from
a Secret (only its hash is stored). The key is upserted by `(user, api_key_name)` and rotated in
place when it changes — the same authoritative-upsert semantics as the rest of bootstrap.

```yaml
# Helm values (see charts/accessflow/examples/values-bootstrap-service-account.yaml)
bootstrap:
  enabled: true
  organization: { name: Acme }
  admin: { email: admin@acme.com, displayName: Admin, passwordSecretRef: { name: af-secrets, key: admin-password } }
  serviceAccounts:
    - email: terraform@acme.com
      displayName: Terraform CI
      role: ADMIN                 # default ADMIN — declarative CRUD needs admin
      apiKeyName: terraform
      apiKeySecretRef: { name: af-secrets, key: ci-api-key }   # value is the af_-prefixed token
      # apiKeyExpiresAt: "2027-01-01T00:00:00Z"   # optional; never expires when omitted
```

Or via env (relaxed binding):
`ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS_0_{EMAIL,DISPLAY_NAME,ROLE,API_KEY_NAME,API_KEY,API_KEY_EXPIRES_AT}`.
The upsert is audited (`API_KEY_CREATED` / `API_KEY_UPDATED`, `metadata.source=BOOTSTRAP`); the raw
key never appears in the audit log. You can also mint a key interactively at
`POST /api/v1/me/api-keys`.

---

## Terraform / OpenTofu provider

Source of truth: [`terraform-provider/`](../terraform-provider/) in this monorepo. Works with both
OpenTofu (`tofu`) and Terraform (`terraform`).

```hcl
terraform {
  required_providers {
    accessflow = { source = "bablsoft/accessflow" }
  }
}

provider "accessflow" {
  endpoint = "https://accessflow.example.com" # or ACCESSFLOW_ENDPOINT
  api_key  = var.accessflow_api_key           # or ACCESSFLOW_API_KEY
}

resource "accessflow_review_plan" "standard" {
  name                    = "standard"
  requires_ai_review      = true
  requires_human_approval = true
  min_approvals_required  = 1
  auto_approve_reads      = true
}

resource "accessflow_datasource" "prod" {
  name           = "prod-postgres"
  db_type        = "POSTGRESQL"
  host           = "postgres.prod.internal"
  port           = 5432
  database_name  = "app"
  username       = "af_reader"
  password       = var.prod_db_password # write-only
  ssl_mode       = "REQUIRE"
  review_plan_id = accessflow_review_plan.standard.id
}

resource "accessflow_routing_policy" "block_deletes" {
  name      = "block-deletes"
  priority  = 100
  action    = "AUTO_REJECT"
  condition = jsonencode({ type = "query_type", any_of = ["DELETE"] })
}
```

### Resources & data sources

| Resource | Notes |
|---|---|
| `accessflow_datasource` | Create/read/update/delete + import by UUID |
| `accessflow_review_plan` | Nested `approvers` + `notify_channels` |
| `accessflow_routing_policy` | `condition` is the typed tree as a JSON string |
| `accessflow_row_security_policy` | Nested under a datasource; import `datasource_id/policy_id` |
| `accessflow_masking_policy` | Nested under a datasource; import `datasource_id/policy_id` |
| `accessflow_ai_config` | `api_key` write-only |
| `accessflow_notification_channel` | `config` map; `channel_type` immutable (forces replacement) |

Data sources: `accessflow_datasource`, `accessflow_review_plan` (look up by `id`).

The provider drives the **existing** REST endpoints (`/datasources`, `/review-plans`,
`/admin/routing-policies`, `/admin/ai-configs`, `/admin/notification-channels`, and the nested
`/datasources/{id}/{row-security,masking}-policies`) — no AccessFlow-specific endpoints were added.
Idempotency comes from Terraform state (create → store UUID → read/update/delete by id), matching
the bootstrap reconciler's authoritative-upsert intent.

### Write-only secrets

`password`, `api_key`, and notification-channel `config` values are never returned by the API. The
provider marks them `sensitive` and applies changes, but **cannot detect drift** on them — a manual
change in the UI won't show in `plan`. Treat the HCL as the source of truth and rotate by changing
the value.

### Local development

```bash
cd terraform-provider
make build       # go build
make test        # unit tests (no live stack)
make testacc     # acceptance tests — needs ACCESSFLOW_ENDPOINT + ACCESSFLOW_API_KEY
make docs        # regenerate docs/ via tfplugindocs (needs terraform/tofu on PATH)
```

To try an unreleased build, use a [CLI dev override](https://opentofu.org/docs/cli/config/config-file/#development-overrides)
pointing `bablsoft/accessflow` at the `go install`-ed binary.

---

## Publishing to the registry (operator runbook)

The OpenTofu registry (and registry.terraform.io) ingest **GitHub Releases from a repo named
`NAMESPACE/terraform-provider-NAME`** — a monorepo subdirectory cannot be ingested. So the provider
is developed here and **mirrored to a dedicated `bablsoft/terraform-provider-accessflow` repo** on
release. The mirror is release-output only — never hand-edit it.

**One-time setup (operator):**
1. Create the public repo `bablsoft/terraform-provider-accessflow` (empty).
2. Generate a GPG key for signing; add the **public** key to your OpenTofu registry account.
3. In the **mirror** repo, add secrets `GPG_PRIVATE_KEY` and `PASSPHRASE` (consumed by the mirror's
   committed `.github/workflows/release.yml` → GoReleaser).
4. In **this** repo, add secret `MIRROR_REPO_TOKEN` (a PAT with write access to the mirror).

**Each release:**
1. Push a tag `terraform-provider-vX.Y.Z` to this repo. The
   [`release-terraform-provider.yml`](../.github/workflows/release-terraform-provider.yml) workflow
   git-subtree-splits `terraform-provider/` and pushes it (plus `vX.Y.Z`) to the mirror.
2. The mirror's release workflow runs GoReleaser, producing
   `terraform-provider-accessflow_<VER>_<OS>_<ARCH>.zip`, `_SHA256SUMS`, `_SHA256SUMS.sig`, and
   `_manifest.json`, attached to a GitHub Release.
3. **First release only:** submit the provider via the OpenTofu registry **issue form** (and,
   optionally, registry.terraform.io) and register the GPG key. The submitter must be a **public**
   member of the `bablsoft` org. Subsequent releases are picked up automatically.

> Run `make docs` and commit the generated `docs/` in the `terraform-provider/` directory before
> the first tag so the registry shows per-resource documentation.

---

## CI Actions & GitLab template

Located in [`.github/actions/`](../.github/actions/) and
[`ci-templates/`](../ci-templates/). See [`ci-templates/README.md`](../ci-templates/README.md).

**GitHub** (composite, referenceable from any repo):

```yaml
- id: ds
  uses: bablsoft/accessflow/.github/actions/provision-datasource@v1
  with:
    endpoint: https://accessflow.example.com
    api-key: ${{ secrets.ACCESSFLOW_API_KEY }}
    name: prod-postgres
    db-type: POSTGRESQL
    host: postgres.prod.internal
    port: "5432"
    database-name: app
    username: af_reader
    password: ${{ secrets.PROD_DB_PASSWORD }}
    ssl-mode: REQUIRE
- uses: bablsoft/accessflow/.github/actions/run-query@v1
  with:
    endpoint: https://accessflow.example.com
    api-key: ${{ secrets.ACCESSFLOW_API_KEY }}
    datasource-id: ${{ steps.ds.outputs.id }}
    sql: "SELECT count(*) FROM orders"
```

**GitLab** — `include:` `ci-templates/gitlab/accessflow.gitlab-ci.yml`, then `extends:`
`.accessflow_provision_datasource` / `.accessflow_run_query` with the `AF_*` variables.

`provision-datasource` is idempotent (look up by name → create or update). `run-query` submits with
an `X-AccessFlow-CI: true` header (so context-aware routing policies recognise the CI origin) and
waits for a terminal status, failing the step on anything other than `EXECUTED`. Pair with an
`AUTO_APPROVE` routing policy for unattended execution.
