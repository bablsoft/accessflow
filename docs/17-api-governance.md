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
    ├── schema/      # ApiSchemaParser SPI + OpenApi/GraphQl/Proto/Wsdl/Postman parsers + SchemaParserRegistry
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
to review), and `max_response_bytes` (per-connector response-size cap, default 10 MiB; the effective
cap is the min of this and the system-wide `ACCESSFLOW_APIGOV_MAX_RESPONSE_BYTES` ceiling).

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
(`OPENAPI`/`WSDL`/`GRAPHQL_SDL`/`GRPC_PROTO`/`POSTMAN_COLLECTION`) and `rawContent`. It is parsed **immediately** into a
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
| `POSTMAN_COLLECTION` | `PostmanCollectionParser` | Collection v2.x JSON tree (#612); folders flattened, examples inferred, credentials + scripts stripped |

> The GraphQL/proto/WSDL/Postman parsers are dependency-free (no graphql-java / wire / wsdl4j) to keep
> the build offline-reproducible; they cover the common document shapes. Replacing them with the full
> grammar libraries is a drop-in change behind the SPI if richer request/response schema extraction
> is needed.

A parser returns `apigov.api.ParsedApiSchema` — the operation list plus two optional extras a format
may carry: `detectedAuthMethod` (the auth scheme the document declares) and `sanitizedContent` (a
redacted document to persist in place of the upload). Only the Postman parser populates them today;
the other four return `null` for both.

**Postman collections (#612).** A Collection v2.x export is a first-class schema source for teams
with no OpenAPI document. Folders flatten into a slugified deterministic `operationId`
(`billing/invoices/create-invoice`); collection `variable[]` values are substituted and every
remaining `{{var}}` — plus Postman's `:id` form — becomes a `{var}` path template, leaving `base_url`
to the admin. Because Postman stores **examples, not schemas**, `requestSchema`/`responseSchema` are
*inferred* from the saved example bodies (`JsonShapeInferrer`) and are correspondingly less precise
— the upload UI says so. Two hard security rules: the collection's declared auth **type** is read
(surfaced as `api_schemas.detected_auth_method`) but **no credential value is ever persisted**, and
`event` blocks — arbitrary pre-request/test JavaScript — are dropped entirely. Since `raw_content`
stores the document verbatim, the parser returns a redacted copy (credential arrays and `event`
blocks removed) as `sanitizedContent`, and that is what gets stored. A v1 export is rejected `422`
with a pointer to Postman's v2.1 export; documents over 5 MiB or 2000 requests are rejected too.

The catalog is cached on `api_schemas.parsed_operations` and re-parsed on each upload. Invalid
documents are rejected `422 API_SCHEMA_PARSE_ERROR`. The editor reads it via
`GET /api-connectors/{id}/operations`.

## 3. Sharing connectivity with the team

`api_connector_user_permissions` grants a user access to a connector: `can_read` (safe methods),
`can_write` (mutating), `can_break_glass`, `expires_at` (JIT), `allowed_operations` (an operation-id
subset), and `restricted_response_fields` (dot-paths masked in that user's responses). Admins manage
grants under `/api-connectors/{id}/permissions`; non-admins only see connectors they're granted. An
existing grant is editable in place via `PUT /api-connectors/{id}/permissions/{permissionId}` (same
fields, minus `userId`) — capabilities, expiry, allowed operations, and masked fields can be changed
without revoking and re-creating, preserving the grant's `created_by`/`created_at` provenance.

**Self-service JIT grants (AF-567).** Besides admin grants, a user can *request* time-boxed
connector access through the shared access-request flow (`POST /access-requests` with
`connector_id` — see [docs/04-api-spec.md → Access Request Endpoints](04-api-spec.md) and
[docs/05-backend.md → JIT time-bound access requests](05-backend.md)). Reviewer eligibility resolves
through the connector's `review_plan_id`; on final approval the request materialises as an
`api_connector_user_permissions` row (`expires_at = now + requested_duration`, `can_read`/`can_write`
and the requested `allowed_operations` only — never break-glass or response-field restrictions),
visible on the connector's permissions tab alongside admin-granted rows and revoked automatically by
`AccessGrantExpiryJob` (or early via `POST /admin/access-requests/{id}/revoke`). A standing
(non-expiring) direct grant is never clobbered — approval fails 409; an existing time-boxed direct
grant is replaced in place by the `(connector_id, user_id)` upsert (its `restricted_response_fields`
are cleared — the same JIT-replaces-JIT semantics as datasource grants). Group grants are never
considered or touched. `EffectiveApiConnectorPermissionResolver` treats JIT rows exactly like
admin-granted expiring rows (excluded once past `expires_at`).

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
   default headers, caps the response at min(`max_response_bytes`, system `max-response-bytes` ceiling)
   with a UTF-8-safe cut, masks the caller's `restricted_response_fields` recursively by dot-path via
   `ColumnMasker`, stores the full masked response snapshot for download, and records EXECUTED /
   FAILED. The detail view returns a bounded inline preview (`response_preview_bytes`); the full body
   is fetched via `GET /api-requests/{id}/response`.

**Break-glass** (`submission_reason=EMERGENCY_ACCESS`, gated by `can_break_glass`) force-approves and
executes immediately, opens a mandatory retro-review in `break_glass_events` (apigov publishes a
synchronous `ApiBreakGlassExecutedEvent`; the workflow module's `ApiBreakGlassReviewListener` calls
`workflow.api.BreakGlassService.openApiBreakGlassReview` in the same transaction — event-based so
apigov never depends on workflow, which would close an access → apigov → workflow module cycle,
AF-567), writes a prominent
`API_REQUEST_BREAK_GLASS_EXECUTED` audit row, and fans out to org admins. **Scheduled execution**:
`ApiRequestRunJob` fires APPROVED requests at `scheduled_for`; `ApiRequestTimeoutJob` auto-rejects
(TIMED_OUT) requests that sit in PENDING_REVIEW past `accessflow.apigov.review-timeout`. Both are
`@SchedulerLock`-guarded.

**Text-to-API** (`POST /api/v1/api-requests/generate`) turns plain English into a concrete call
draft via `ApiCallAnalyzer.generateApiCall`, enabled only for connectors with a parsed schema and
`text_to_api_enabled`. A debounced risk preview is at `POST /api/v1/api-requests/analyze`.

## 5. Masking & classification (AF-518)

Connector-level governance of API responses, mirroring datasource dynamic masking (AF-381) and
data-classification tagging (AF-447), adapted to non-tabular bodies. Because a response field isn't a
column, a mask/tag targets it four ways via the `api_masking_matcher_type` enum:

- `SCHEMA_FIELD` — a field of a parsed schema operation (`operation_id` + `field_ref`); resolved to a
  JSON dot-path (the field reference is treated as the response path).
- `JSON_PATH` — a dot-path into the JSON body (e.g. `user.email`), descending through arrays; a path
  landing on a sub-tree masks every leaf beneath it.
- `XML_PATH` — an XPath into an XML/SOAP body, evaluated with an XXE-hardened parser (same
  `DocumentBuilderFactory` setup as `WsdlSchemaParser`).
- `REGEX` — a regular expression over a JSON or text body; the first capturing group (or the whole
  match when there is none) is masked.

**Masking policies** (`api_connector_masking_policy`, admin CRUD under
`/api-connectors/{id}/masking-policies`) carry a `MaskingStrategy` + `strategy_params` and
`reveal_to_roles`/`reveal_to_group_ids`/`reveal_to_user_ids`. `ApiConnectorMaskingResolutionService`
resolves the policies that *apply* to a submitter (a requester in any reveal list sees the unmasked
value — same precedence as the SQL path, resolved via `core.api.UserQueryService` +
`UserGroupService`). `ApiExecutionService` resolves them on `execute()`/`executeInline()`, **merges**
the result with the legacy per-permission `restricted_response_fields` (FULL masks, back-compat), and
`ApiResponseMasker.mask(body, contentType, masks)` applies them — JSON-tree masks to JSON bodies,
XPath masks to XML bodies, regex over whatever remains — reusing `core.api.ColumnMasker.apply`. The
body is masked **once**, before the snapshot is stored, so the raw value never persists. Applied
policy ids are recorded on the `API_REQUEST_EXECUTED` audit metadata.

**Classification tags** (`api_connector_classification_tag`, admin CRUD under
`/api-connectors/{id}/classification-tags`) tag a field (`operation_id` + `field_ref` + matcher) with
PII/PCI/PHI/GDPR/FINANCIAL/SENSITIVE. Tagging auto-derives a masking policy from
`ApiConnectorClassificationDefaults` (PII/GDPR/FINANCIAL→PARTIAL(visible_suffix=4), PCI/PHI→FULL,
SENSITIVE→HASH; idempotent), and `ApiConnectorClassificationRiskBooster` raises the apigov AI
analyzer's risk for calls to a classified operation (PCI/PHI +30, FINANCIAL +20, PII/GDPR +15,
SENSITIVE +10 — strongest weight, clamped, never lowers the LLM verdict; fully fail-safe).
`GET /api-connectors/{id}/classification-tags/derivation-preview` previews the aggregated review
posture + masking suggestions (suggested, never auto-applied).

---

## 6. Dynamic variables (AF-613)

A connector may declare named variables — rows in `api_connector_variables` — evaluated per request
and substituted into header values, the path, query values and the body via `{{name}}` placeholders.
This is what makes vendor contracts requiring a computed value per call governable: request signing
(HMAC), nonces, timestamps, correlation ids, idempotency keys, digests. A submitter cannot
hand-compute a signature for a request a reviewer will approve hours later, and a timestamp would be
stale by execution time.

**Kinds.** `CONSTANT`, `UUID`, `TIMESTAMP` (ISO-8601 or a `DateTimeFormatter` pattern at UTC),
`EPOCH_MILLIS`, `RANDOM_HEX` (1–256 secure-random bytes), `HASH` (SHA-256 / MD5), `HMAC`
(HMAC-SHA256 / HMAC-SHA512 keyed with an encrypted shared secret), `ENCODE`. Encodings: `HEX`
(lowercase), `BASE64`, `BASE64URL` (unpadded, the RFC 7515 shape vendors specify).

**Evaluation context.** Inside an expression: `{{request.method}}`, `{{request.path}}`,
`{{request.query}}` (canonical — keys sorted and percent-encoded, so a signature over it
reproduces), `{{request.body}}`, `{{request.headers.<Name>}}` (case-insensitive), and `{{var.x}}` for
another variable. **Every `request.*` value describes the request before substitution** — the
motivating vendor scheme signs a body that still contains its own `{{signature}}` placeholder, and
resolving post-substitution would compute a digest the vendor rejects.

**Pipeline order.** Auth headers resolved (including a freshly minted OAuth2 token) → variables
evaluated in dependency order → substitution → send. The seam sits in
`ApiExecutionService.executeCall`, after `buildHeaders`, so an expression can sign the finished
`Authorization` value. The OAuth2 401 retry re-resolves from scratch: a nonce must not be replayed
and a signature over the stale token would only fail again.

**Ordering and cycles.** References form a DAG; `ApiVariableGraph` topologically sorts it with the
repository's `(sort_order, created_at, id)` order as a total tie-break, so independent variables
evaluate in a stable, operator-controlled sequence. Cycles and dangling `{{var.x}}` references are
**configuration errors caught at save time** (422), not runtime failures — the resolver re-runs the
sort defensively but should never trip it.

**The motivating example, in the model.** The submitter's body carries `"HMAC": "{{signature}}"`;
`signature` is `kind=HMAC`, `algorithm=HMAC_SHA256`, `encoding=HEX`, with the shared key stored
encrypted and `expression={{request.headers.Authorization}}{{request.body}}`. At resolution time
`{{request.body}}` still contains the literal placeholder, exactly as the vendor's step 2 requires;
the executor substitutes the digest back into the body and sends.

**Targets.** A variable may instead carry `target: header:<Name>` or `query:<name>`, applied after
substitution, for vendors that want the value in a fixed header. There is deliberately no whole-body
target — replacing an entire body with one value is never what an operator means, and partial-body
injection needs a JSON pointer.

**Per-request overrides.** A variable marked `overridable` may be given a value per request. This
needs `can_override_variables` on the connector grant; a secret-bearing variable can never be
overridable (service check plus a database CHECK constraint); an override is an opaque literal that
is never itself expanded as a template, so it cannot become a path into another variable's value.
Dependents still recompute over the overridden value. Overrides are persisted and shown to reviewers,
so an approval covers exactly what will execute. Grouped requests (AF-501) do not accept overrides.

**Not a scripting engine.** Evaluation is template substitution plus this fixed function set only —
no expression language, no `eval`, no user-supplied code — mirroring the engine plugins' rejection of
server-side scripting. See [docs/07-security.md](07-security.md) for the full secret-handling,
redaction and CRLF rules.

---

## Audit & notifications

Every connector/schema/permission/request action writes a tamper-evident `audit_log` row via
`audit.api.AuditLogService`: `API_CONNECTOR_CREATED`/`_UPDATED`/`_DELETED`, `API_SCHEMA_UPLOADED`/
`_DELETED`, `API_PERMISSION_GRANTED`/`_REVOKED`, `API_CONNECTOR_VARIABLE_CREATED`/`_UPDATED`/
`_DELETED`/`API_CONNECTOR_VARIABLES_REORDERED` (AF-613; metadata carries the variable name, kind and
flags — never the expression or the secret),
`API_CONNECTOR_MASKING_POLICY_CREATED`/`_UPDATED`/`_DELETED`,
`API_CONNECTOR_CLASSIFICATION_TAG_ADDED`/`_REMOVED` (resource `API_CONNECTOR`), and
`API_REQUEST_SUBMITTED`/`_APPROVED`/`_REJECTED`/`_EXECUTED`/`_CANCELLED`/`_BREAK_GLASS_EXECUTED`
(resource `API_REQUEST`) — delivering the full audit of which APIs are called, how, and by whom.

Notifications add `API_REQUEST_SUBMITTED`/`_APPROVED`/`_EXECUTED`/`_FAILED` event types. The
notifications module's `ApiNotificationListener` consumes `ApiRequestReadyForReviewEvent` (alert
reviewers + admins) and `ApiRequestDecidedEvent` (alert the submitter; break-glass executions alert
admins), delivered as in-app + chat notifications (Slack / Discord / Teams / Telegram).
