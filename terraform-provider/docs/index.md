---
page_title: "AccessFlow Provider"
description: |-
  Manage AccessFlow database access governance resources declaratively.
---

# AccessFlow Provider

The AccessFlow provider manages [AccessFlow](https://accessflow.bablsoft.com/) — the
open-source database access governance platform — declaratively through its REST API. Use it to
provision datasources, review plans, routing/row-security/masking policies, AI configs, and
notification channels as code, with the same authoritative-upsert semantics as the built-in
`bootstrap` GitOps reconciler.

It works with both [OpenTofu](https://opentofu.org) (`tofu`) and Terraform (`terraform`).

## Authentication

The provider authenticates with an AccessFlow **API key** (the `af_`-prefixed token) sent as
`Authorization: ApiKey <key>`. The key inherits the permissions of its owning user, so use an
admin (or admin-role service account). For CI/GitOps, bootstrap a dedicated service-account key
declaratively — see the AccessFlow docs (`docs/16-iac.md`).

## Example Usage

{{tffile "examples/provider/provider.tf"}}

## Configuration Reference

Both arguments may instead be supplied via environment variables:

- `endpoint` → `ACCESSFLOW_ENDPOINT`
- `api_key` → `ACCESSFLOW_API_KEY`

{{ .SchemaMarkdown | trimspace }}
