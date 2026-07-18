# Terraform / OpenTofu provider for AccessFlow

The official provider for [AccessFlow](https://accessflow.bablsoft.com/), the open-source
database & API access governance platform. Manage datasources, review plans, routing / row-security /
masking policies, AI configs, and notification channels declaratively through the AccessFlow REST
API. Works with both [OpenTofu](https://opentofu.org) and Terraform.

Published at
[registry.terraform.io/providers/bablsoft/accessflow](https://registry.terraform.io/providers/bablsoft/accessflow/latest)
(and the OpenTofu registry as `bablsoft/accessflow`).

```hcl
terraform {
  required_providers {
    accessflow = {
      source = "bablsoft/accessflow"
    }
  }
}

provider "accessflow" {
  endpoint = "https://accessflow.example.com" # or ACCESSFLOW_ENDPOINT
  api_key  = var.accessflow_api_key           # or ACCESSFLOW_API_KEY
}
```

## Resources & data sources

| Resource | Purpose |
|---|---|
| `accessflow_datasource` | A governed database connection |
| `accessflow_review_plan` | AI + multi-stage human approval policy |
| `accessflow_routing_policy` | Attribute-based routing (auto-approve/reject/escalate) |
| `accessflow_row_security_policy` | Row-level predicate (nested under a datasource) |
| `accessflow_masking_policy` | Column masking strategy (nested under a datasource) |
| `accessflow_ai_config` | AI analyzer configuration |
| `accessflow_notification_channel` | Email / Slack / Webhook / … channel |

Data sources: `accessflow_datasource`, `accessflow_review_plan` (look up by id).

## Source layout & releases

**This directory is the source of truth.** It lives inside the AccessFlow monorepo under
`terraform-provider/`. On a `terraform-provider-vX.Y.Z` tag, a CI workflow git-subtree-splits this
directory into the dedicated **`bablsoft/terraform-provider-accessflow`** repository (which the
OpenTofu and HashiCorp registries require — they ingest a repo named `terraform-provider-<name>`)
and tags it `vX.Y.Z`; that repo's release workflow runs GoReleaser to publish GPG-signed binaries.
Do not hand-edit the mirror repo.

See `docs/16-iac.md` in the main repository for the full publishing runbook.

## Development

```bash
make build           # go build ./...
make test            # unit tests (no live stack needed)
make testacc         # acceptance tests — needs ACCESSFLOW_ENDPOINT + ACCESSFLOW_API_KEY
make lint            # golangci-lint
make docs            # regenerate docs/ via tfplugindocs
```

Write-only secrets (`password`, `api_key`, notification `config` values) are never returned by the
API, so the provider applies changes to them but cannot detect drift — mark them `sensitive` and
expect no refresh.
