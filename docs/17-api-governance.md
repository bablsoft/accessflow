# 17 — API Access Governance (AF-500)

AccessFlow governs **database** access at query time. **API Access Governance** extends the same
review → approval → audit model to **outbound API calls** — REST, SOAP, GraphQL, and gRPC — so a
team can register an API target, share controlled access with users, force calls through review, and
get a full audit of which APIs are called and by whom.

It lives in the `apigov` Spring Modulith module (`com.bablsoft.accessflow.apigov`) and deliberately
**reuses existing primitives** (review state machine, AI analyzer + rate limiter, `ColumnMasker`,
routing policy, audit log, credential encryption, JIT/break-glass permission model) rather than
reinventing them.

> **Delivery status.** This chapter currently documents the **foundation**: connector management,
> schema ingestion + operation catalog, and per-user permissions ("share with team"). The
> governed-call **pipeline** (submit → AI risk → routing → human review → guarded execution with
> response masking + snapshot, text-to-API, break-glass, scheduled execution) is the larger half of
> the epic and lands in a follow-up; its sections here are marked _(planned)_.

---

## Module layout

```
com.bablsoft.accessflow.apigov/
├── api/         # ApiProtocol, ApiAuthMethod, ApiSchemaType, ApiOperation, views, commands,
│                # ApiConnectorAdminService, ApiSchemaService, exceptions (JDK + project types only)
└── internal/
    ├── DefaultApiConnectorAdminService, DefaultApiSchemaService
    ├── persistence/{entity,repo}/   # ApiConnectorEntity, ApiSchemaEntity,
    │                                 # ApiConnectorUserPermissionEntity + repos
    ├── schema/      # ApiSchemaParser SPI + OpenApi/GraphQl/Proto/Wsdl parsers + SchemaParserRegistry
    ├── client/      # ApiConnectorProber (test-connection); per-protocol execution clients (planned)
    └── web/         # ApiConnectorController, ApiSchemaController, DTOs, exception handler, audit writer
```

Cross-module references (`organization_id`, `review_plan_id`, `ai_config_id`, `created_by`,
`user_id`) are **bare UUID columns**, not JPA relationships — matching the `access` module — so an
apigov row survives after the referenced aggregate is deleted. The `apigov.api` package imports only
JDK + project types (enforced by `ApiPackageDependencyTest`).

---

## 1. Connectors + authentication

An admin registers an **API connector**: `name` (unique per org), `protocol`
(`REST`/`SOAP`/`GRAPHQL`/`GRPC`), `base_url`, optional `default_headers`, per-call `timeout_ms`,
`tls_verify`, and an `auth_method`
(`NONE`/`API_KEY`/`BEARER_TOKEN`/`BASIC`/`OAUTH2_CLIENT_CREDENTIALS`/`CUSTOM_HEADER`/`MTLS`). The
auth secret is supplied as a `credentials` map, serialized and **AES-256-GCM encrypted** via
`core.api.CredentialEncryptionService`, stored in `auth_credentials_encrypted`, `@JsonIgnore`, and
**never returned** — read views expose only `authMethod` + a `hasCredentials` flag.

Governance config per connector: `review_plan_id`, `ai_analysis_enabled` + `ai_config_id`,
`text_to_api_enabled`, `require_review_reads` / `require_review_writes` (map safe vs mutating methods
to review), and `max_response_bytes` (response-size cap).

**Test connection** (`POST /api-connectors/{id}/test`) probes reachability without invoking an
operation: an HTTP GET to the base URL for REST/SOAP/GraphQL (any HTTP response = reachable), a TCP
connect for gRPC. No connector auth is injected.

CRUD is admin-gated and org-scoped; every mutation writes an audit row
(`API_CONNECTOR_CREATED`/`_UPDATED`/`_DELETED`).

## 2. Schema ingestion & operation catalog

An admin uploads a schema document (`POST /api-connectors/{id}/schemas`) with a `schema_type`
(`OPENAPI`/`WSDL`/`GRAPHQL_SDL`/`GRPC_PROTO`) and `rawContent`. It is parsed **immediately** into a
normalized `ApiOperation` catalog — `operationId`, `verb`, `path`, `summary`, and a **read/write
classification** (`write`) so the same review plans and permissions apply uniformly:

- **read** = `GET`/`HEAD`/`OPTIONS`, GraphQL `query`, name-heuristic reads (get/list/describe/…)
- **write** = `POST`/`PUT`/`PATCH`/`DELETE`, GraphQL `mutation`, gRPC unary writes, SOAP mutating ops

Parsers live behind the `ApiSchemaParser` SPI, dispatched by `SchemaParserRegistry`:

| Type | Parser | Implementation |
|------|--------|----------------|
| `OPENAPI` | `OpenApiSchemaParser` | swagger-parser (`io.swagger.parser.v3:swagger-parser`), JSON or YAML, OpenAPI 2/3 |
| `GRAPHQL_SDL` | `GraphQlSchemaParser` | lightweight SDL extractor (root `Query`/`Mutation` fields) |
| `GRPC_PROTO` | `ProtoSchemaParser` | lightweight `.proto` extractor (`service`/`rpc` → `service.method`) |
| `WSDL` | `WsdlSchemaParser` | JDK DOM (XXE-hardened); `portType` `<operation>` elements |

> The GraphQL/proto/WSDL parsers are dependency-free (no graphql-java / wire / wsdl4j) to keep the
> build offline-reproducible; they cover the common document shapes. Replacing them with the full
> grammar libraries is a drop-in change behind the SPI if richer request/response schema extraction
> is needed.

The catalog is cached on `api_schemas.parsed_operations` and re-parsed on each upload. Invalid
documents are rejected `422 API_SCHEMA_PARSE_ERROR`. The editor reads it via
`GET /api-connectors/{id}/operations`.

## 3. Sharing connectivity with the team

`api_connector_user_permissions` grants a user access to a connector: `can_read` (safe methods),
`can_write` (mutating), `can_break_glass`, `expires_at` (JIT), `allowed_operations` (an operation-id
subset), and `restricted_response_fields` (dot-paths masked in that user's responses). Admins manage
grants under `/api-connectors/{id}/permissions`; non-admins only see connectors they're granted.

## 4. Governed-call pipeline _(planned)_

`api_requests` mirrors `query_requests` and reuses the `query_status` lifecycle
(`PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTED` + reject/timeout/fail/cancel). A submitted call
is validated against the schema when present (else accepted free-form, always routed to review),
classified read/write, AI-risk-scored (async, rate-limited, fail-safe — reusing
`AiAnalyzerStrategy`), routed (`AUTO_APPROVE`/`AUTO_REJECT`/`REQUIRE_APPROVALS`/`ESCALATE`), and —
once approved — executed by a per-protocol client that injects connector auth, caps the response at
`max_response_bytes`, masks `restricted_response_fields` recursively by dot-path via `ColumnMasker`,
and records an immutable response snapshot. **Self-approval is forbidden.** Break-glass and
scheduled execution mirror the query path. **Text-to-API** turns plain English into a concrete call
draft, enabled only for connectors that have a parsed schema.

---

## Audit & notifications

Connector/schema/permission mutations write tamper-evident `audit_log` rows via
`audit.api.AuditLogService`: `API_CONNECTOR_CREATED`/`_UPDATED`/`_DELETED`, `API_SCHEMA_UPLOADED`/
`_DELETED`, `API_PERMISSION_GRANTED`/`_REVOKED` (resource type `API_CONNECTOR`). Request-pipeline
audit actions and notification event types land with the pipeline follow-up.
