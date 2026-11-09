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
    ├── DefaultApiRequestReplayService   # replay a terminal request as a new submission (§8)
    ├── DefaultApiResponseDiffService,   # structural diff of two stored response snapshots (§8)
    │   ApiResponseStructuralDiffer      #   pure JSON / XML differ
    ├── SecureXmlParsers                 # the one hardened XML parser — masking and the diff
    ├── persistence/{entity,repo}/   # ApiConnectorEntity, ApiSchemaEntity,
    │                                 # ApiConnectorUserPermissionEntity + repos
    ├── schema/      # ApiSchemaParser SPI + OpenApi/GraphQl/Proto/Wsdl/Postman parsers + SchemaParserRegistry
    ├── client/      # ApiCallExecutor (REST/SOAP/GraphQL over the JDK HTTP client; gRPC not yet wired),
    │                # ApiConnectorAuthApplier, ApiConnectorProber (test-connection)
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

**Scheduled health (#894).** A connector can also be monitored on a timer: set
`healthCheckIntervalMinutes` (≥ 1) and `ApiConnectorHealthJob` runs the same probe as the test
action on that cadence, without the OAuth2 token fetch. Like the test action it checks reachability only, so any HTTP response, `5xx` included, counts as up. Each probe is kept as a history row, and
consecutive failures are counted. When the count reaches `ACCESSFLOW_APIGOV_HEALTH_FAILURE_THRESHOLD`
(default 3), the connector becomes `UNHEALTHY`. That flip is audited as `API_CONNECTOR_UNHEALTHY`
and raises one connector-down event, delivered to org admins as the `API_CONNECTOR_UNHEALTHY` notification (#895; pages PagerDuty only on the opt-in `CONNECTOR_DOWN` trigger); recovery is audited as `API_CONNECTOR_RECOVERED`. Admins read
the status and the 20 newest probes from `GET /api-connectors/{id}/health`. The manual test action
never counts toward the streak. Detail: [docs/05-backend.md](05-backend.md) → *Scheduled connector
health*.

**UI (#896).** The settings page header carries a health badge (`ApiConnectorHealthBadge`): status,
last probe time and, on click, a drawer listing the 20 recent checks. A connector without an
interval reads **Not monitored** — never a red state. The *Configuration* tab has the
`health_check_interval_minutes` field; emptying it sends `clear_health_check: true`. The connector
list shows a *Health* column (`—` while unmonitored or not yet probed). A schema loaded from a URL
takes a `refresh_interval_minutes` at upload and shows "Every N min · last refreshed …", plus the
last refresh error when there is one, with an *Auto-refresh* modal for `PUT …/refresh-policy`. The **Drift** tab
(`ApiConnectorDriftTab`, `?tab=drift`) lists findings filtered by status (`OPEN` by default) with a
*Dismiss* action and an advisory grant-impact link to the *Permissions* tab, then one card per
URL-sourced schema with *Refresh now* and its revisions. *Accept revision* sits behind a confirm that
names the operation delta and is offered on every revision except the current one. Accepting is the
only action that changes the catalog: until then the *Operations* tab keeps the current one.

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

**Catalog diff (#892, epic #890).** `internal/schema/OperationCatalogDiff` compares two
`ApiOperation` lists and returns a sorted list of `OperationDiffFinding` records — `(kind,
operationId, before, after)`, where `before` is `null` for an added operation and `after` is `null`
for a removed one. It holds no mutable state and touches no repository, clock or I/O. The
schema-refresh job (#893) builds drift findings from it — see [docs/05-backend.md](05-backend.md) → *Schema refresh & drift*.

- Operations are keyed by `operationId`. On a duplicate id the first occurrence wins; an operation
  with no id is ignored.
- An id on one side only is `OPERATION_ADDED` or `OPERATION_REMOVED`, and nothing else is reported
  for it.
- For an id on both sides, each of these is checked independently:
  - `CLASSIFICATION_FLIPPED` — `write` changed.
  - `SHAPE_CHANGED` (at most one) — `verb` or `path` changed, or a request or response shape that
    is known on **both** sides differs. Shapes are compared as JSON trees, so key order and
    whitespace do not count; if either side is not JSON they are compared as trimmed strings.
  - `DEPRECATED` — `deprecated` became `true`.
- A `null` shape means *unknown* and is never a change. OpenAPI, GraphQL and WSDL parse no shapes
  today.
- Un-deprecation and `summary` / `tags` edits produce nothing.
- Output is sorted by `operationId`, then by the `ApiSchemaDriftKind` declaration order
  (`OPERATION_ADDED`, `OPERATION_REMOVED`, `SHAPE_CHANGED`, `DEPRECATED`,
  `CLASSIFICATION_FLIPPED`).
- The diff never applies an operation filter; the caller filters both sides first.

**Catalog content hash.** `OperationCatalogDiff.contentHash` computes the value stored in
`api_schema_revisions.content_hash`, so the hash and the diff agree on what "the same catalog"
means. It applies the diff's keying rules (first occurrence per id, id-less operations dropped),
sorts by `operationId`, builds a JSON array with each operation's fields in record order and nulls
written out, and takes the SHA-256 of it in lower-case hex. Because the tree is built explicitly,
the hash does not depend on the `ObjectMapper`'s inclusion settings.

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

**Connector rate limits (#972).** `ApiConnectorRateLimiter` enforces a connector's
`calls_per_minute`, `calls_per_day` and optional `per_user_share_percent` **at execution**, inside
`ApiExecutionService` — not at submission, because a request may wait days in review, and not in the
controller, because the run job and request groups are not HTTP callers. The per-minute limit is a
token bucket (burst = `calls_per_minute`, refilled at `calls_per_minute / 60` tokens per second); the
daily quota is a fixed window per UTC day. With a share below 100 %, each submitter also gets a bucket and a
daily counter sized `ceil(limit × share / 100)`; a call must pass every configured limit. All keys
are checked and consumed by one atomic Lua script, so a call refused by the daily counter never
burns a minute token. A connector with every limit `NULL` never touches Redis.

A throttled request is not a new status: it stays `APPROVED`, gains `throttled_until` and
`throttle_count`, and `ApiRequestRunJob` retries it (trigger `quota_retry`) once `throttled_until`
passes — the synchronous execute answers `429 API_CONNECTOR_QUOTA_EXCEEDED` with the retry time and
`queuedForRetry: true`. The submitter may cancel a throttled request exactly like a scheduled one.
Only the **first** deferral of a request writes an `API_QUOTA_EXCEEDED` audit row (later ones are
`DEBUG` logs). Break-glass executions skip the limiter — a break-glass request group's API members
too. Any other request-group API member that is throttled is reported as a failed member — groups have
no retry loop. The limiter **fails open**: if Redis is
unreachable the call proceeds and a `WARN` is logged — a deliberate divergence from the AI rate
limiter, because a rate limit protects the upstream from load and is not an authorization control.

**Replay and response diff** work on a finished request: the submitter can re-submit a terminal
request as a new governed call, and an executed request's stored response can be compared with the
previous run of the same call. Both are described in [§8](#8-replay--response-diff-epic-885).

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
`/api-connectors/{id}/masking-policies`) carry a `MaskingStrategy` + `strategy_params` (every strategy
of the SQL path, including the configurable `REGEX_REPLACE` / `CONSTANT` / `NULLIFY` / `KEEP_FIRST` /
`NUMERIC_BUCKET` / `DATE_GENERALIZE` of #944, validated at save time by the same
`MaskingStrategyParamsValidator`; `NULLIFY` renders a JSON leaf as `null`, empties an XML node, and
removes the matched text of a `REGEX` matcher) and
`reveal_to_roles`/`reveal_to_group_ids`/`reveal_to_user_ids`. `ApiConnectorMaskingResolutionService`
resolves the policies that *apply* to a submitter (a requester in any reveal list sees the unmasked
value — same precedence as the SQL path, resolved via `core.api.UserQueryService` +
`UserGroupService`). `ApiExecutionService` resolves them on `execute()`/`executeInline()`, **merges**
the result with the legacy per-permission `restricted_response_fields` (FULL masks, back-compat), and
`ApiResponseMasker.mask(body, contentType, masks)` applies them — JSON-tree masks to JSON bodies,
XPath masks to XML bodies, regex over whatever remains — reusing `core.api.ColumnMasker.apply`. The
body is masked **once**, before the snapshot is stored, so the raw value never persists. Applied
policy ids are recorded on the `API_REQUEST_EXECUTED` audit metadata.

**Masking fails closed (#1108).** Policies are evaluated one at a time, and no error path returns
the raw body. If a policy that applies cannot be evaluated (an invalid XPath or regex, or an XPath
that does not select nodes, e.g. `count(//ssn)`), or the body cannot be parsed in the format its
masks target — malformed XML with `XML_PATH` masks, or malformed JSON with `JSON_PATH`/`SCHEMA_FIELD`
masks (a JSON response cut at the response-size cap is malformed by construction) — the masker
replaces the **whole** response snapshot with `***` and logs a WARN naming the policy id. A body
"looks like" JSON when its content type contains `json` or it starts with `{`/`[`, and like XML when
its content type contains `xml` or it starts with `<`. The request is not failed — the upstream call
has already executed — so it stays `EXECUTED` with the redacted snapshot; fix the policy and resubmit
the call to get a masked body. An `XML_PATH` or `REGEX` policy that cannot be evaluated is rejected
when it is saved (see below). Policies saved before that check existed, and policies derived from
classification tags, are not checked and can still reach this path.

**Field references are validated on write (#1110).** Create and update reject, with `422
ILLEGAL_API_MASKING_POLICY`, an `XML_PATH` or `REGEX` field reference the masker could not evaluate,
so a typo is caught when the policy is saved rather than showing up as redacted responses.
`ApiMaskingFieldRefValidator` evaluates an `XML_PATH` with `XPathFactory` (secure processing on) to a
node-set against an empty document. That is the same evaluation the masker runs, so it also rejects
expressions that compile but do not select nodes, such as `count(//ssn)`. It also rejects namespace
prefixes (`//ns:ssn`), because response bodies are parsed without namespace awareness and a prefixed
step never matches, and `$variables`, because the masker binds none and evaluation would throw. A
`REGEX` must compile with `java.util.regex.Pattern`. `SCHEMA_FIELD` and `JSON_PATH` references are only
checked for blank. The admin form runs the same check in the browser: the browser's XPath engine for
`XML_PATH`, and a JavaScript `RegExp` for `REGEX`, after rewriting Java-only syntax such as `(?i)` and
possessive quantifiers. The server's check is the authoritative one. Classification tags that derive
a masking policy do not run this check yet.

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

## 7. Decision trace (AF-967)

An admin debugging *why was this call escalated?* used to read several admin pages and re-run the
routing logic in their head. `POST /api/v1/admin/api-call-simulations`
(`API_CONNECTOR_MANAGE`, returns **200** — nothing is created) answers it directly, by driving the
same evaluator the live listener drives.

**The split.** `ApiReviewStateMachine` used to decide and apply in one method. Since AF-967 the
deciding half lives in `apigov.internal.ApiDecisionEvaluator` — pure, returning an `ApiDecision`
that carries the resulting status, the matched policy, the effective approval count and a
`core.api.DecisionTrace`. The state machine only *applies* it: the transition, the approval count,
the event. A simulator with its own copy of these rules would disagree with enforcement the first
time either side changed, which would make it worse than useless.

`DefaultApiCallSimulationService` delegates routing and the review requirement to that evaluator and
reconstructs the other seven stages, because production evaluates them in the synchronous submission
gate, at execution time, or in a separate submission mode. One trace covers the whole journey:

| Stage | What it reports |
|---|---|
| `CONNECTOR_GATES` | the connector exists in the org and is `active` |
| `CALL_CLASSIFICATION` | read vs write, and whether the schema operation, the REST verb or the protocol default decided it |
| `SCHEMA_VALIDATION` | the operation is in the catalog; a free-form call is `SKIP`, not a denial |
| `OPERATION_PERMISSION` | the effective connector permission (direct ∪ group), capability, `allowed_operations`, and the `QUERY_ADMIN` short-circuit |
| `ROUTING_POLICIES` | **every** enabled policy in priority order with its `matched` flag — a policy that nearly matched is usually the most useful line in a trace |
| `REVIEW_REQUIREMENT` | the connector's require-review flags folded with its review plan |
| `ELIGIBLE_REVIEWERS` | the plan's approver rules, else the `API_REQUEST_REVIEW` holders, minus the submitter |
| `RESPONSE_MASKING` | which masking rules resolve — see below |
| `BREAK_GLASS` | whether the submitter could bypass everything above. Informational |

**There is no connector-health stage**, because enforcement has none: the only reachability probe is
the admin-triggered `POST /api-connectors/{id}/test`, and submission gates on `active` and nothing
else. A health stage here would report something the pipeline never checks.

**Masking is reported per rule, not per field.** The live masker walks the real response body by
dot-path, which does not exist until the call has run, so the step lists the masking policies,
classification-derived masks and legacy `restricted_response_fields` entries that *resolve* for this
connector and caller — and says so, with the mandatory `RESPONSE_SHAPE_ABSENT` caveat. "Rule" rather
than "policy" because the list mixes the three, and a legacy restricted-field entry carries no policy
id.

**Read-only, structurally.** No `api_requests` row, no event, no notification, no AI call, and — the
one specific to this module — **no request to the governed API**. That is enforced by construction:
the service is wired only to lookup interfaces and pure evaluators, and
`DefaultApiCallSimulationServiceTest` asserts it against the service's declared field types, so a
future change that wires in a repository or an HTTP client fails the build rather than quietly
breaking the guarantee. Every call writes one `ACCESS_SIMULATION_RUN` audit row against the
connector, carrying the simulated user, the AI outcome, the risk level and the step count — and
deliberately no request path, headers or body.

Full request/response contract: [docs/04-api-spec.md](04-api-spec.md#decision-traces-for-api-calls-and-deployments-af-967).
The deployment sibling is [§11 of docs/18](18-deployment-governance.md#11-decision-trace-af-967);
the query one is the [AF-859 access explainer](05-backend.md#access-explainer-af-859).

---

## 8. Replay & response diff (epic #885)

Two things a requester wants once a call has finished: run it again, and see what changed in the
answer. Both are deliberately narrow. A replay is a **new submission** that goes back through
review; it never re-executes the old row. The diff compares the **stored, masked** snapshots; it
never calls the upstream API.

### Replay (#886)

`POST /api/v1/api-requests/{id}/replay` (empty body, `202`), implemented by
`DefaultApiRequestReplayService`:

1. **Source.** The request must exist in the caller's organization **and the caller must be its
   submitter** — anyone else, a `QUERY_VIEW_ALL` holder included, gets `404 API_REQUEST_NOT_FOUND`.
   No read surface shows the stored headers, body or form files, so letting another user replay
   would resend input they never saw (a typed API key, say) under their name. The source must be
   terminal (`EXECUTED`, `FAILED`, `REJECTED`, `TIMED_OUT`, `CANCELLED`), else
   `409 API_REQUEST_INVALID_STATE`.
2. **Operation.** When the source names an operation and the connector has a parsed schema, the
   operation must still be in the connector's **current, filtered** catalog (the AF-614 import
   filter applies), else `422 API_REPLAY_OPERATION_UNAVAILABLE`. A free-form call skips this.
3. **Overrides.** Stored variable overrides (AF-613) are carried only when the caller's effective
   grant still has `can_override_variables`, the name is still overridable on the connector, and the
   value still passes the length (`ACCESSFLOW_APIGOV_MAX_VARIABLE_VALUE_BYTES`) and control-character
   checks. Anything else is **dropped, never a failure**; the response reports
   `dropped_variable_override_count` and the UI shows it in a toast.
4. **Submit.** Connector, operation, verb, path, headers, query params and the body composition are
   handed to the ordinary `submit` path, so the caller's connector permission (read / write /
   `allowed_operations`) and the connector being active are re-checked exactly as `submit` checks
   them (a `QUERY_ADMIN` holder skips the permission check there, as on any submission), and the new request
   runs AI → routing → review like any other. The caller is the submitter (the self-approval ban
   applies), `scheduled_for` is null, `submission_reason` is `USER_SUBMITTED` even for a break-glass
   source, and the justification is a server-set, localized "Replay of API request {id}".

What is **never** copied: connector credentials and resolved dynamic variables. The command has no
field for them; they are decrypted and resolved at execution time of the new request, against the
connector as it is then. The source row is never modified.

**Provenance** is not a column. The replay writes exactly one audit row — the new request's
`API_REQUEST_SUBMITTED` — whose metadata carries `trigger=replay`, `original_api_request_id` and
`dropped_variable_override_count`. There is no separate audit action.

### Response diff (#887)

`GET /api/v1/api-requests/{id}/diff?against=previous|<uuid>`, implemented by
`DefaultApiResponseDiffService` over the pure `ApiResponseStructuralDiffer`.

**Which run is "previous".** Derived at read time, never stamped at execution: the newest
`EXECUTED` request in the organization with the same `connector_id` and the same operation key
(`operation_id`, or — for a free-form call — `operation_id IS NULL` with the same `verb` and
`request_path`), created before this one, with a stored snapshot. A caller without
`QUERY_VIEW_ALL` only ever gets one of their **own** earlier runs; REVIEWER and ADMIN match across
the organization. `against=<uuid>` names a run explicitly: it must be visible to the caller and be
an earlier `EXECUTED` run of the same connector + operation key with a snapshot, else
`422 API_RESPONSE_DIFF_INCOMPATIBLE`. Both sides follow the detail endpoint's visibility rule and
read as `404 API_REQUEST_NOT_FOUND`, never `403`. When there is nothing to compare — this request
has no stored snapshot, or no previous run exists (a first run, say) — the answer is
`404 API_RESPONSE_DIFF_NOT_AVAILABLE`, which the UI shows as the card's empty state.

**The algorithm.** The format comes from the current side's `response_content_type` (contains
`json` / `xml`), falling back to the body's shape; anything else, or a side that does not parse, is
`422 API_RESPONSE_DIFF_UNSUPPORTED`. XML is parsed by `SecureXmlParsers` — the same hardened parser
masking uses (no DOCTYPE, no external entities). Each difference is one entry `ADDED` / `REMOVED` /
`CHANGED`:

- **JSON paths** — object keys joined with `.`, array elements as `name[i]` (arrays compare by
  index), a root array as `[i]`, and `$` when the root itself changed.
- **XML paths** — one `name[i]` segment per element (the index counts same-named siblings), `@attr`
  for attributes, `#text` for an element's own text: `order[0].total[0].#text`.
- An added or removed subtree is **one** entry at its root; a JSON type change (`1` → `"1"`) is
  `CHANGED`. `before` / `after` are clipped to 1024 characters.
- At most `ACCESSFLOW_APIGOV_MAX_DIFF_ENTRIES` entries (default `500`) are returned; beyond it the
  response sets `truncated`, and the counts describe the returned entries.

```json
{
  "current_id": "uuid", "previous_id": "uuid", "format": "JSON",
  "status_code_current": 200, "status_code_previous": 200,
  "duration_ms_delta": -35, "bytes_delta": 12,
  "added_count": 1, "removed_count": 0, "changed_count": 2,
  "truncated": false, "either_response_truncated": false,
  "entries": [
    { "path": "data.items[2]", "kind": "ADDED", "before": null, "after": "{\"id\":7}" },
    { "path": "data.total", "kind": "CHANGED", "before": "2", "after": "3" },
    { "path": "data.status", "kind": "CHANGED", "before": "\"open\"", "after": "\"closed\"" }
  ]
}
```

**The masking caveat.** The snapshots are stored already masked, and the diff never re-masks or
unmasks. A masking-policy change between the two runs therefore shows up as `CHANGED` entries on the
masked paths — a value on one side, its masked form on the other — and a body that masking replaced
entirely (fail-closed redaction) is not JSON or XML, so it is `422`. A diff path is not a masking
field ref: `JSON_PATH` policies have no `[i]` segments. The diff writes no audit row.

### UI (#888)

The API request detail page shows **Replay** to the submitter of a terminal request (confirm modal,
then navigation to the new request) and, on an executed request with a snapshot, a **Compare to
previous run** card: the status-code pair, duration and size deltas, the added / removed / changed
entries, the two bounded previews side by side, and an input to compare against a specific request
id. The card says it compares masked snapshots; there is no reveal affordance.

### Out of scope

Replaying against a different connector, replaying grouped requests (`requestgroups` members are
not `api_requests` rows), order-insensitive array diffing (arrays compare by index, so a reordered
list reads as changes), and diffs of text, CSV or other non-JSON / non-XML bodies.

Full request/response contract: [docs/04-api-spec.md](04-api-spec.md#post-api-requestsidreplay--response-202)
and [the diff endpoint](04-api-spec.md#get-api-requestsiddiff--response-200).

---

## Audit & notifications

Every connector/schema/permission/request action writes a tamper-evident `audit_log` row via
`audit.api.AuditLogService`: `API_CONNECTOR_CREATED`/`_UPDATED`/`_DELETED`, `API_SCHEMA_UPLOADED`/
`_DELETED`, `API_PERMISSION_GRANTED`/`_REVOKED`, `API_CONNECTOR_VARIABLE_CREATED`/`_UPDATED`/
`_DELETED`/`API_CONNECTOR_VARIABLES_REORDERED` (AF-613; metadata carries the variable name, kind and
flags — never the expression or the secret),
`API_CONNECTOR_MASKING_POLICY_CREATED`/`_UPDATED`/`_DELETED`,
`API_CONNECTOR_CLASSIFICATION_TAG_ADDED`/`_REMOVED`, the system-attributed scheduled-health rows
`API_CONNECTOR_UNHEALTHY`/`_RECOVERED` (#894; `trigger=health`) (resource `API_CONNECTOR`), and
`API_REQUEST_SUBMITTED`/`_APPROVED`/`_REJECTED`/`_EXECUTED`/`_FAILED`/`_CANCELLED`/`_BREAK_GLASS_EXECUTED`
and `API_QUOTA_EXCEEDED` (#972; first deferral only, metadata includes `connector_id`/`limit_kind`/`retry_at`/`trigger`)
(resource `API_REQUEST`; a run by `ApiRequestRunJob` writes its `_EXECUTED`/`_FAILED` row itself,
attributed to the submitter so grant-usage intelligence still counts it, with `trigger=scheduled|quota_retry`
and the `operation_id`; a synchronous execute always writes `API_REQUEST_EXECUTED` attributed to the caller, with the outcome — `EXECUTED` or `FAILED` — in `metadata.status`, so filter on that field rather than on `API_REQUEST_FAILED` alone) — delivering the full audit of which APIs are called, how, and by whom.

Notifications add `API_REQUEST_SUBMITTED`/`_APPROVED`/`_EXECUTED`/`_FAILED` event types. The
notifications module's `ApiNotificationListener` consumes `ApiRequestReadyForReviewEvent` (alert
reviewers + admins) and `ApiRequestDecidedEvent` (alert the submitter; break-glass executions alert
admins), delivered as in-app + chat notifications (Slack / Discord / Teams / Telegram).
Three connector-scoped events go to every active org admin on every active org channel, email
included: `API_CONNECTOR_OAUTH2_TOKEN_FAILED`, `API_CONNECTOR_DRIFT` (a refresh stored a revision
with drift findings) and `API_CONNECTOR_UNHEALTHY` (the health probes crossed the failure threshold,
#895). The in-app rows link to the connector's settings page (the drift row opens its *Drift* tab,
`?tab=drift` — #896). Only `API_CONNECTOR_UNHEALTHY` can
page, through the PagerDuty `CONNECTOR_DOWN` trigger; none opens a ticket.

---

## Domain visibility (#926)

An organization declares whether it governs outbound API calls at all —
`organizations.governs_apis`, first answered in the first-run wizard (AF-898) and changeable later
by an org admin at `/admin/governance-domains`. With the flag off, the SPA stops **offering** this
module's surfaces: the **API** sub-sections under Workflow and Connections, the **API requests**
review-hub tab, and the *My recent API requests* / *API request trends* / *Pending API approvals*
dashboard widgets.

That is a discovery filter, not an access control. Every route stays registered, every
`AuthGuard` permission check is untouched, no endpoint's authorization reads the flag, and a deep
link into `/api-requests`, `/api-connectors` or `/reviews?tab=api` keeps working for anyone who
holds the permission. Turning the domain back on restores the navigation with nothing else to do.
