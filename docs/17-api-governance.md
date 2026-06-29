# 17 — API Access Governance (AF-500)

AccessFlow governs **database** access at query time. **API Access Governance** extends the same
review → approval → audit model to **outbound API calls** — REST, SOAP, GraphQL, and gRPC — so a
team can register an API target, share controlled access with users, force calls through review, and
get a full audit of which APIs are called and by whom.

It lives in the `apigov` Spring Modulith module (`com.bablsoft.accessflow.apigov`) and deliberately
**reuses existing primitives** (review state machine, AI analyzer + rate limiter, `ColumnMasker`,
routing policy, audit log, credential encryption, JIT/break-glass permission model) rather than
reinventing them.

> **Delivery status.** Both halves are now implemented: the **foundation** (connector management,
> schema ingestion + operation catalog, per-user permissions) and the governed-call **pipeline**
> (submit → AI risk → routing → human review → guarded execution with response masking + snapshot,
> text-to-API, break-glass, scheduled execution). The gRPC connector accepts registration, schema
> upload, and review, but **call execution** for gRPC is not yet wired (REST / SOAP / GraphQL execute
> over the JDK HTTP client); a gRPC execution attempt returns a clear error.

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
connect for gRPC. No connector auth is injected — **except** for `OAUTH2_CLIENT_CREDENTIALS`
connectors, where the test additionally performs a live token fetch so a misconfigured token
endpoint / credentials surface at setup time.

CRUD is admin-gated and org-scoped; every mutation writes an audit row
(`API_CONNECTOR_CREATED`/`_UPDATED`/`_DELETED`).

### OAuth2 token sourcing (AF-500 / #506)

When `auth_method = OAUTH2_CLIENT_CREDENTIALS`, AccessFlow obtains the outbound access token itself
rather than relying on a hand-pasted bearer token. The connector row carries the (returnable)
non-secret config — `oauth2_token_uri`, `oauth2_client_id`, `oauth2_scopes`, `oauth2_audience`,
`oauth2_username`, plus the `oauth2_grant_type` and `oauth2_client_auth` enums — and the AES-256-GCM
encrypted, `@JsonIgnore`, never-returned secrets `oauth2_client_secret_encrypted`,
`oauth2_refresh_token_encrypted`, and `oauth2_password_encrypted` (read views expose only
`oauth2_*_configured` booleans).

- **Grant types** (`oauth2_grant_type`): `CLIENT_CREDENTIALS` (M2M, default), `REFRESH_TOKEN`
  (exchange a stored long-lived refresh token), and `PASSWORD` (resource-owner — needs
  `oauth2_username` + `oauth2_password`).
- **Client authentication** (`oauth2_client_auth`): `CLIENT_SECRET_BASIC` (HTTP Basic header with
  URL-encoded `client_id:client_secret`, the RFC 6749 §2.3.1 default) or `CLIENT_SECRET_POST`
  (credentials in the form body). An optional `oauth2_audience` (Auth0-style) is added when set.
- **Caching & refresh.** `ConnectorOAuth2TokenService` posts the configured grant to
  `oauth2_token_uri` via a dedicated `apigovOAuth2RestClient`, parses `access_token` / `expires_in` /
  `token_type`, and caches the token (AES-GCM encrypted) in Redis under
  `apigov:oauth2:token:<connectorId>` with TTL = `expires_in − skew` (floored at ≥ `PT10S`; a
  fallback TTL is used when `expires_in` is absent). The token is reused across calls and refreshed
  on expiry or on a single upstream `401` (evict → re-fetch → retry once).
- **Fail-safe.** Any token-fetch failure (missing config, non-2xx, transport error, missing
  `access_token`) raises an execution error → the governed request goes `FAILED` with a clear message;
  the token, client secret, refresh token, and password are never logged, audited, or returned. A
  real network fetch writes one `API_CONNECTOR_OAUTH2_TOKEN_REFRESHED` audit row (never on a cache
  hit). Repeated consecutive failures crossing
  `accessflow.apigov.oauth2-token-failure-alert-threshold` publish an
  `API_CONNECTOR_OAUTH2_TOKEN_FAILED` notification fanned out to org admins (the connector is
  effectively down).

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

## 4. Governed-call pipeline

`api_requests` mirrors `query_requests` and reuses the `query_status` lifecycle
(`PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTED` + reject/timeout/fail/cancel). Submit
(`POST /api/v1/api-requests`, 202):

1. **Permission + classification.** Non-admins need an active connector permission with the right
   capability (read vs write); a call is classified write from its schema operation, else from the
   verb (REST mutating verbs; SOAP/GraphQL/gRPC default to write = fail-safe to review).
   `allowed_operations` scopes which operations a user may call.
2. **Schema validation.** When the connector has a schema and an `operationId` is supplied, it must
   exist in the catalog (else `422 API_REQUEST_VALIDATION_ERROR`). Free-form calls (no operationId)
   are accepted and always routed to review.
3. **AI risk.** `ApiRequestSubmittedEvent` → `ApiAnalysisListener` → `ai.api.ApiCallAnalyzer`
   (rate-limited, async), persisting into `ai_analyses` (keyed `api_request_id`) and publishing
   completed/failed/skipped. Failure escalates to human review — never blocks.
4. **Routing + review.** `ApiReviewStateMachine` applies the first matching `api_routing_policies`
   entry (`AUTO_APPROVE`/`AUTO_REJECT`/`REQUIRE_APPROVALS`/`ESCALATE`), else the connector's
   require-review flags + review plan. `ApiReviewService` records per-stage decisions;
   **the submitter can never self-approve**; decisions are idempotent.
5. **Execution.** `ApiExecutionService` runs an APPROVED call (submitter-triggered
   `POST /{id}/execute`, or the scheduled-run job at `scheduled_for`): injects connector auth +
   default headers, caps the response at `max_response_bytes`, masks the caller's
   `restricted_response_fields` recursively by dot-path via `ColumnMasker`, stores an immutable
   masked response snapshot, and records EXECUTED / FAILED.

**Break-glass** (`submission_reason=EMERGENCY_ACCESS`, gated by `can_break_glass`) force-approves and
executes immediately, opens a mandatory retro-review in `break_glass_events`
(`workflow.api.BreakGlassService.openApiBreakGlassReview`), writes a prominent
`API_REQUEST_BREAK_GLASS_EXECUTED` audit row, and fans out to org admins. **Scheduled execution**:
`ApiRequestRunJob` fires APPROVED requests at `scheduled_for`; `ApiRequestTimeoutJob` auto-rejects
(TIMED_OUT) requests that sit in PENDING_REVIEW past `accessflow.apigov.review-timeout`. Both are
`@SchedulerLock`-guarded.

**Text-to-API** (`POST /api/v1/api-requests/generate`) turns plain English into a concrete call
draft via `ApiCallAnalyzer.generateApiCall`, enabled only for connectors with a parsed schema and
`text_to_api_enabled`. A debounced risk preview is at `POST /api/v1/api-requests/analyze`.

---

## Audit & notifications

Every connector/schema/permission/request action writes a tamper-evident `audit_log` row via
`audit.api.AuditLogService`: `API_CONNECTOR_CREATED`/`_UPDATED`/`_DELETED`, `API_SCHEMA_UPLOADED`/
`_DELETED`, `API_PERMISSION_GRANTED`/`_REVOKED` (resource `API_CONNECTOR`), and
`API_REQUEST_SUBMITTED`/`_APPROVED`/`_REJECTED`/`_EXECUTED`/`_CANCELLED`/`_BREAK_GLASS_EXECUTED`
(resource `API_REQUEST`) — delivering the full audit of which APIs are called, how, and by whom.

Notifications add `API_REQUEST_SUBMITTED`/`_APPROVED`/`_EXECUTED`/`_FAILED` event types. The
notifications module's `ApiNotificationListener` consumes `ApiRequestReadyForReviewEvent` (alert
reviewers + admins) and `ApiRequestDecidedEvent` (alert the submitter; break-glass executions alert
admins), delivered as in-app + chat notifications (Slack / Discord / Teams / Telegram).
