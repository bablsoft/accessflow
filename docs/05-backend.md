# 05 — Backend Architecture

## Maven Module Layout

```
accessflow/
├── accessflow-parent/            # Parent POM — dependency management, plugin config
├── accessflow-api/               # REST controllers, DTOs, OpenAPI/Swagger spec
├── accessflow-core/              # Domain entities, JPA repositories, service interfaces
├── accessflow-proxy/             # SQL proxy engine, JDBC connection pool management
├── accessflow-workflow/          # Review workflow state machine, notification fanout
├── accessflow-access/            # JIT time-bound access requests — approval, grant materialisation, expiry job
├── accessflow-ai/                # AI analyzer — OpenAI / Anthropic / Ollama / Hugging Face adapters
├── accessflow-security/          # JWT config, Spring Security, SAML 2.0 SSO
├── accessflow-notifications/     # Email (JavaMail), Slack, Webhook, Discord, Telegram, MS Teams, PagerDuty dispatchers
├── accessflow-realtime/          # WebSocket fanout of domain events to connected frontend clients
├── accessflow-audit/             # Audit log service, Spring application event publishers
├── accessflow-compliance/        # Compliance reports + signed PDF/CSV exports over query snapshots (AF-459)
├── accessflow-mcp/               # Spring AI stateless MCP server — @Tool callbacks for AI agents
└── accessflow-app/               # Spring Boot main application, Docker entrypoint
```

---

## Spring Boot Configuration

### application.yml (core)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/accessflow
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    locations: classpath:db/migration

accessflow:
  encryption-key: ${ENCRYPTION_KEY}   # 256-bit AES key for credential encryption

  jwt:
    private-key: ${JWT_PRIVATE_KEY}   # RSA-2048 PEM
    access-token-expiry: 15m
    refresh-token-expiry: 7d

  ai:
    provider: anthropic              # openai | anthropic | ollama | openai_compatible
    api-key: ${AI_API_KEY}
    model: claude-sonnet-4-20250514
    ollama-base-url: http://ollama:11434

  proxy:
    connection-timeout: 30s              # HikariCP connectionTimeout (per-pool)
    idle-timeout: 10m                    # HikariCP idleTimeout
    max-lifetime: 30m                    # HikariCP maxLifetime
    leak-detection-threshold: 0s         # 0 disables leak detection
    pool-name-prefix: accessflow-ds-     # pool name = prefix + datasource UUID
    execution:
      max-rows: 10000                    # Global ceiling for SELECT row materialization
      statement-timeout: 30s             # JDBC setQueryTimeout default
      default-fetch-size: 1000           # JDBC setFetchSize hint

  workflow:
    timeout-poll-interval: PT5M          # QueryTimeoutJob cadence (ISO-8601 duration)

  redis:
    url: ${REDIS_URL:redis://localhost:6379}
```

### SAML 2.0 SSO (DB-driven)

SAML is configured entirely from the admin UI (`/admin/saml`) — there is no
`spring.security.saml2.relyingparty.*` in `application.yml`. The flow is:

1. Browser hits `GET /api/v1/auth/saml/init/default`.
2. `DynamicRelyingPartyRegistrationRepository.findByRegistrationId("default")` builds a Spring
   Security `RelyingPartyRegistration` on demand from the active `saml_config` row. The IdP
   asserting-party metadata is bootstrapped from `idp_metadata_url`; the IdP signing cert (used
   for response verification) is decrypted from `signing_cert_pem`; the SP signing keypair (used
   to sign AuthnRequests and shipped in the SP metadata XML) comes from
   `SamlSpKeyProvider.resolve(orgId)`. The repository caches the assembled registration and
   evicts on `SamlConfigUpdatedEvent` — same pattern as `DynamicClientRegistrationRepository`,
   no application restart.
3. Spring's `Saml2WebSsoAuthenticationRequestFilter` builds the signed AuthnRequest and 302s
   the browser to the IdP SSO endpoint.
4. The IdP POSTs the signed `SAMLResponse` to `POST /api/v1/auth/saml/acs`. Spring validates
   the signature against the IdP cert and constructs a `Saml2Authentication`.
5. `SamlLoginSuccessHandler` runs: maps the assertion attributes through
   `SamlAttributeMapper` (per `saml_config.attr_email` / `attr_display_name` / `attr_role`),
   JIT-provisions the user through `UserProvisioningService.findOrProvision` with
   `AuthProviderType.SAML`, issues a one-time exchange code through `SamlExchangeCodeStore`
   (Redis, 60 s default TTL — namespace `saml:exchange:` separate from OAuth2), records
   `USER_LOGIN` audit, and 302s to `${ACCESSFLOW_SAML_FRONTEND_CALLBACK_URL}?code=<one-time-code>`.
   Failures go through `SamlLoginFailureHandler`, which maps Spring's `Saml2ErrorCodes`
   onto short codes (`SAML_SIGNATURE_INVALID`, `SAML_ASSERTION_INVALID`,
   `SAML_NOT_CONFIGURED`, etc.) and redirects with `?error=<code>`.
6. The frontend `SamlCallbackPage` POSTs the code to `/api/v1/auth/saml/exchange`.
   `SamlExchangeController` consumes the code, calls `AuthenticationService.issueForUser`
   to mint the standard JWT pair, sets the refresh-token cookie via `RefreshCookieWriter`, and
   returns the same `LoginResponse` shape as `/auth/login`.

SP keypair sourcing (`SamlSpKeyProvider`) follows a hybrid env-var override + auto-generate
fallback model. When both `ACCESSFLOW_SAML_SP_SIGNING_KEY_PEM` and
`ACCESSFLOW_SAML_SP_SIGNING_CERT_PEM` are set, those values are used verbatim. Otherwise the
provider generates a self-signed RSA-2048 keypair on first use, encrypts the private key with
`ENCRYPTION_KEY`, and persists the pair into `saml_config.sp_private_key_pem` /
`saml_config.sp_certificate_pem` so it survives restarts. A `ConcurrentHashMap`-backed per-org
lock prevents two concurrent first-time calls from racing.

`SecurityConfiguration` declares three `SecurityFilterChain` beans:

- `@Order(1)` matches the SAML `/init/**`, `/acs`, `/acs/**`, and `/metadata/**` paths and
  runs Spring's `saml2Login()` and `saml2Metadata()` configurers with
  `sessionCreationPolicy(IF_REQUIRED)` (the redirect dance needs the session for a few seconds).
- `@Order(2)` matches the OAuth2 authorize / callback paths.
- `@Order(3)` is the stateless chain that owns the rest of the API; `/api/v1/auth/saml/exchange`
  and `/api/v1/auth/saml/metadata/**` are added to its `permitAll()` list.

Account-linking model — the success handler rejects with `SAML_LOCAL_EMAIL_CONFLICT` if an
existing user with the same email is `auth_provider=LOCAL` and has a password hash; admin
must manually convert the account. See [docs/07-security.md](07-security.md).

### OAuth 2.0 / OIDC login (DB-driven)

OAuth providers are configured entirely from the admin UI (`/admin/oauth2`) — there is no
`spring.security.oauth2.client.*` in `application.yml`. The flow is:

1. Browser hits `GET /api/v1/auth/oauth2/authorize/{provider}` (one of `google`, `github`,
   `microsoft`, `gitlab`, `oidc`).
2. `DynamicClientRegistrationRepository.findByRegistrationId` builds a Spring Security
   `ClientRegistration` on demand from the matching `oauth2_config` row. For the four built-in
   providers, per-provider static metadata (auth/token/userinfo URLs, default scopes, OIDC
   flag, attribute extractors) lives in `OAuth2ProviderTemplate.TEMPLATES`. For the generic
   `OIDC` provider, `OAuth2ProviderTemplate.forEntity(entity)` builds the template from the
   row's `display_name`, `authorization_uri`, `token_uri`, `user_info_uri`, `jwk_set_uri`,
   `issuer_uri`, and attribute-name columns — `OIDC` is the only provider whose URLs are
   admin-editable. The repository caches `ClientRegistration`s by registration id and evicts
   on `OAuth2ConfigUpdatedEvent` / `OAuth2ConfigDeletedEvent` — same pattern as
   `AiAnalyzerStrategyHolder`, no application restart.
3. Spring's `OAuth2AuthorizationRequestRedirectFilter` redirects the browser to the provider.
4. The provider redirects back to `GET /api/v1/auth/oauth2/callback/{provider}`. Spring
   exchanges the code for tokens.
5. `OAuth2LoginSuccessHandler` runs: resolves email + display name via `OAuth2EmailResolver`
   (which falls back to GitHub's `/user/emails` when the primary `/user` payload omits the
   email), JIT-provisions the user through `UserProvisioningService.findOrProvision`, issues a
   one-time exchange code through `OAuth2ExchangeCodeStore` (Redis, 60 s default TTL), and
   redirects to `${ACCESSFLOW_OAUTH2_FRONTEND_CALLBACK_URL}?code=<one-time-code>`.
6. The frontend `OAuthCallbackPage` POSTs the code to `/api/v1/auth/oauth2/exchange`.
   `OAuth2ExchangeController` consumes the code, calls `AuthenticationService.issueForUser`
   to mint the standard JWT pair, sets the refresh-token cookie via `RefreshCookieWriter`, and
   returns the same `LoginResponse` shape as `/auth/login`.

The OAuth2 chain lives at `@Order(2)` in `SecurityConfiguration` (SAML claims `@Order(1)`,
the stateless API chain is `@Order(3)`). It runs Spring's `oauth2Login()` configurer with
`sessionCreationPolicy(IF_REQUIRED)` (the redirect dance needs the session for a few seconds).

Account-linking model — the success handler rejects with `OAUTH2_LOCAL_EMAIL_CONFLICT` if an
existing user with the same email is `auth_provider=LOCAL` and has a password hash; admin
must manually convert the account. See [docs/07-security.md](07-security.md).

### SSO group sync (AF-353)

After both SAML and OAuth2 logins, the success handler resolves the user's IdP group claim
values and translates them through the per-provider `group_mappings` JSONB
(`{"idp-claim-value": "<accessflow-group-uuid>"}`) into a set of AccessFlow group IDs. The
result is fed to `UserGroupService.syncIdpMemberships(userId, organizationId, groupIds)`,
which:

1. Reads existing memberships for the user.
2. Deletes `source = 'IDP'` rows that aren't in the new set.
3. Inserts new `source = 'IDP'` rows for groups not already present (skipping any that
   already exist as `source = 'MANUAL'` — manual memberships always win).

This means renaming an AccessFlow group, removing a member manually, or removing a user
from an IdP group all converge on the next login. SAML reads the multi-valued claim named in
`saml_config.attr_groups`; OAuth2 reuses the existing `OAuth2MembershipResolver` (which
already handles GitHub `/user/orgs`, GitLab / Microsoft / OIDC `groups` claim, etc.) so
allowlist checks and group sync share one resolution path. Failures during sync are logged
at ERROR but do not block the login itself.

---

## Query Proxy Engine

The proxy engine (`accessflow-proxy` module) is the heart of AccessFlow. It is the **only component** that opens JDBC connections to customer databases.

### Execution Flow (Step by Step)

> Implementation status: AF-15 landed steps 1, 2, 5 (capability bit on
> `datasource_user_permissions`), 7 (publishes `QuerySubmittedEvent`), and writes the initial
> `query_requests` row in `PENDING_AI` via `workflow.internal.web.QuerySubmissionController`.
> AF-16 added steps 6, 7-completion, and 8: the `workflow.internal.QueryReviewStateMachine`
> consumes `AiAnalysisCompletedEvent` / `AiAnalysisFailedEvent` to advance out of `PENDING_AI`,
> and `workflow.internal.web.ReviewController` exposes `/api/v1/reviews/pending`,
> `/approve`, `/reject`, `/request-changes` for human approvers. AF-247 added step 4 — AST-level
> schema/table allow-listing — by walking each parsed `Statement` with JSqlParser's
> `TablesNamesFinder` and intersecting the resulting set with the permission's
> `allowed_schemas` / `allowed_tables` columns inside `DefaultQuerySubmissionService`. The
> executor invocation (steps 9, 10, 11) ships in follow-up issues.

1. **Receive request** — `POST /api/v1/queries` hits the controller, which delegates to `QueryProxyService`.
2. **Permission check** — Load `DatasourceUserPermission` for `(user, datasource)`. Verify `can_read` / `can_write` / `can_ddl` as appropriate. Reject with 403 if no permission record exists.
3. **SQL parsing** — Parse SQL using `JSqlParser` via `SqlParserService` (`proxy/api/`). Determine `QueryType` (SELECT, INSERT, UPDATE, DELETE, DDL, OTHER). Reject unparseable SQL and stacked / multi-statement input with 422 (`InvalidSqlException` → `error: "INVALID_SQL"`). The one exception is a `BEGIN; … COMMIT;` envelope: when the parser detects leading `BEGIN`/`BEGIN WORK`/`BEGIN TRANSACTION`/`START TRANSACTION` and trailing `COMMIT`/`COMMIT WORK`/`COMMIT TRANSACTION`/`END` markers (lexically — JSqlParser 5.3 cannot itself parse `BEGIN` as a transaction-start), it strips them, re-parses the body, and requires every inner statement to be INSERT/UPDATE/DELETE. Mixing SELECT with DML, SELECT-only transactions, DDL inside the body, `ROLLBACK`/`SAVEPOINT`/nested `BEGIN`, unmatched markers, and an empty body are all rejected with distinct 422 messages. The parsed result records `transactional=true` and the list of inner statement texts so the executor can re-issue them under a single JDBC transaction.
4. **Schema allow-list check** — If `allowed_schemas` or `allowed_tables` is set on the permission, the workflow service walks the parsed JSqlParser AST with `TablesNamesFinder`, collects every referenced table into `SqlParseResult.referencedTables`, and rejects with 403 if any referenced table sits outside the allow-list. Empty/null on both columns keeps the historical "all tables permitted" behaviour. See the [Schema / table allow-list enforcement](#schema--table-allow-list-enforcement) subsection below for the match algorithm and normalisation rules.
5. **Review plan lookup** — Load the `ReviewPlan` assigned to the datasource. Determine whether AI review and/or human approval is required for this `QueryType`.
6. **Fast path** — If neither AI nor human review is required (e.g. `auto_approve_reads=true` for a SELECT), skip to step 9.
7. **AI analysis** — If `requires_ai_review=true`, publish `QuerySubmittedEvent`. The `AiAnalyzerService` picks it up asynchronously. Query status → `PENDING_AI`. When complete, status → `PENDING_REVIEW` (or `APPROVED` if no human review needed).
8. **Human approval** — If `requires_human_approval=true`, status → `PENDING_REVIEW`. Notification Dispatcher sends alerts to reviewers. System waits for decisions. Once `min_approvals_required` is met (respecting `stage` ordering), status → `APPROVED`.
9. **Execute** — Workflow orchestrator calls `QueryExecutor.execute(...)` (`proxy/api/`). The executor acquires a JDBC connection from the per-datasource pool, runs the SQL via `PreparedStatement` with `setQueryTimeout` and `setMaxRows(N+1)` (truncation detection), and dispatches by `QueryType`: `SELECT → executeQuery`, anything else → `executeLargeUpdate`. Returns a `SelectExecutionResult` (columns + rows + truncated flag) or `UpdateExecutionResult` (rows affected) — both carry `duration`. The orchestrator persists `rows_affected`, `execution_started_at`, `execution_completed_at`, `execution_duration_ms`, and `error_message` onto `query_requests`. On failure (AF-408), `error_message` holds the **verbatim driver message** (the underlying `SQLException` text, e.g. `ERROR: invalid input value for enum query_status: "PENDING"`) rather than the generic localized summary, so the detail page can surface the real cause to the submitter / reviewer; the same value is mirrored into the `QUERY_FAILED` audit metadata `error` field. The generic localized message and `sqlState` / `vendorCode` are still what the `QUERY_EXECUTION_FAILED` 422 `ProblemDetail` exposes — the raw driver text never leaks into an API error envelope.
10. **Audit** — Every status transition publishes an `AuditEvent` (Spring Application Event) consumed by `AuditLogService` and written to `audit_log`.
11. **Respond** — Status → `EXECUTED`. WebSocket event pushed to submitter. API returns execution metadata.

### Connection Pool Management

Implemented in `proxy/internal/`:

- `DatasourceConnectionPoolManager` (public API) — `DataSource resolve(UUID)`, `List<ReplicaEndpointRef> replicaEndpoints(UUID)`, `DataSource resolveReplica(UUID, UUID endpointId)`, `void evict(UUID)`, `Optional<DatasourcePoolStats> poolStats(UUID)`, and `Optional<DatasourcePoolStats> replicaPoolStats(UUID, UUID)` (AF-457). Returns Hikari pools typed as `javax.sql.DataSource` so callers stay framework-agnostic and use the standard JDBC `try-with-resources` idiom. `replicaEndpoints` lists the datasource's endpoints (id + redacted host:port label) without creating pools; `resolveReplica` lazily builds one pool per endpoint; `evict` closes the primary and every replica pool. The `*poolStats` methods read the live `HikariPoolMXBean` gauges (active / idle / waiting / total / max) for **already-cached** pools and return empty when none is cached — they never create a pool, so reading health metrics can't trigger a connection attempt against an unreachable customer DB.
- `DefaultDatasourceConnectionPoolManager` — `ConcurrentHashMap` cache (primary map + per-datasource endpoint-keyed replica map), atomic lazy creation via `compute`, `@PreDestroy` shutdown closes all pools.
- `DatasourcePoolFactory` — owns the Hikari wiring; resolves the stored credential to plaintext only here (via `SecretResolutionService`) and drops the local reference before returning.
- `DatasourcePoolEvictionListener` — `@ApplicationModuleListener` for `DatasourceConfigChangedEvent` and `DatasourceDeactivatedEvent` (both in `core/events/`); fires in a new transaction after the publisher's transaction commits. Annotation comes from `spring-modulith-events-api`.

Behavior:

- One `HikariCP` pool per active datasource, keyed by datasource id.
- Pool created lazily on first `resolve(...)`. The pool is closed and the entry removed when:
  - `evict(...)` is called (e.g. by the listener after a config-change or deactivation event).
  - The application shuts down (`@PreDestroy`).
- Per-pool config: `maximumPoolSize` from `datasource.connection_pool_size`, plus the timeouts under `accessflow.proxy.*` (`connection-timeout`, `idle-timeout`, `max-lifetime`, optional `leak-detection-threshold`).
- Customer DB credentials resolved from `password_encrypted` at pool creation time only; the local plaintext reference is dropped before `createPool` returns. Hikari retains its own copy for reconnects.
- Pool init is fail-fast: bad credentials or unreachable hosts raise `PoolInitializationException` from `resolve(...)` rather than on first `getConnection()`.

#### Datasource credential resolution (AF-448)

Every place that used to decrypt `password_encrypted` now goes through
`core.api.SecretResolutionService`: a stored value with a lowercase `vault:` / `aws:` / `azure:`
prefix is a **secret reference** fetched from the corresponding external store
(`core/internal/secrets/` — one `SecretStore` bean per provider enabled via
`accessflow.secrets.<provider>.enabled`); anything else falls back to local AES-256-GCM
decryption. The seam covers, with no engine-plugin changes:

- `DatasourcePoolFactory.buildPool(...)` — JDBC primary + replica pools (resolve carries the
  datasource + organization ids for audit context).
- `DefaultQueryEngineCatalog` — hands `secretResolutionService::resolve` as the
  `CredentialDecryptor` in `QueryEngineContext`, so all native engines (incl. `api_key` and the
  DynamoDB secret key) resolve references transparently.
- `DatasourceAdminServiceImpl` — test-connection, replica test, and JDBC schema introspection.

On write, `DatasourceAdminServiceImpl.storeCredential(...)` stores a validated reference
verbatim instead of encrypting it. Resolved values are never cached (Security rule #4); each
successful/failed external resolve publishes `SecretReferenceResolvedEvent` /
`SecretReferenceResolutionFailedEvent` (plain `@EventListener` in the audit module — pool-init
resolves happen outside a transaction, where `@ApplicationModuleListener`'s AFTER_COMMIT
delivery would drop them). Context-less engine-lane resolves are attributed to their owning
datasource(s) via `DatasourceLookupService.findByCredentialReference(...)`. Store failures
throw `SecretResolutionException` (HTTP 502 on admin paths; `PoolInitializationException`
semantics on the execution path).

Eviction events (in `core/events/`, published by `DatasourceAdminServiceImpl`):

- `DatasourceConfigChangedEvent(UUID datasourceId)` — fired from `update(...)` when any of `host`, `port`, `databaseName`, `username`, `passwordEncrypted`, `sslMode`, `connectionPoolSize`, or the read-replica endpoint list changed. Eviction closes the primary and every replica pool (and purges the SELECT result cache).
- `DatasourceDeactivatedEvent(UUID datasourceId)` — fired from `update(...)` when `active` flips `true → false`, and from `deactivate(...)` (idempotent — only when the entity was active before the call).
- `DatasourceCacheConfigChangedEvent(UUID datasourceId)` (AF-457) — fired from `update(...)` when only the result-cache settings (`result_cache_enabled` / `result_cache_ttl_seconds`) changed. Purges the datasource's cached SELECT results **without** evicting connection pools.

The proxy module reads the datasource state via `DatasourceLookupService` (`core/api/`) which returns a `DatasourceConnectionDescriptor` record — a Modulith-clean alternative to letting `proxy/internal/` reach into `core/internal/` JPA entities. The descriptor exposes `maxRowsPerQuery` so the executor can enforce per-datasource row caps without a second round trip. It also carries the ordered `readReplicas` endpoint list (`ReadReplicaEndpoint(id, jdbcUrl, username, passwordEncrypted)`), a convenience `hasReadReplica()` method, and the `resultCacheEnabled` / `resultCacheTtlSeconds` cache settings.

### Read-replica routing & load balancing (AF-457)

When a datasource has endpoints in `datasource_read_replicas`, `RoutingDataSourceResolver` (`proxy/internal/`) load-balances any query classified by `SqlParserService` as `QueryType.SELECT` **round-robin across the healthy replica endpoints** (one HikariCP pool per endpoint). INSERT/UPDATE/DELETE/DDL and transactional `BEGIN…COMMIT` batches always hit the primary, regardless of the replica configuration. Dry-runs (AF-445) route through the same resolver by their underlying `QueryType`, so a SELECT dry-run prefers a replica while a write dry-run plans on the primary.

- Replica credentials are encrypted with the same `ENCRYPTION_KEY` as the primary, decrypted only inside `DatasourcePoolFactory.createReplicaPool(...)`, and surface pool names suffixed `-replica-{n}`. When an endpoint's `username` or `password_encrypted` is `NULL`, the primary's credentials are reused — useful when the replicas accept the same service account.
- The driver class is shared with the primary: replicas must use the same engine (you cannot point a PostgreSQL primary at a MySQL replica).
- **Health / circuit breaker.** `ReplicaHealthRegistry` (`proxy/internal/`) keeps per-node, per-endpoint breaker state: a connection failure marks the endpoint DOWN for `accessflow.proxy.replica.cooldown` (default 30s), during which the load-balancer skips it; once the cooldown elapses a single half-open trial is admitted — success restores the endpoint to rotation, failure re-arms the cooldown. A failed endpoint never fails the read: the resolver moves on to the next candidate and only when **every** endpoint is down or failed does the SELECT fall back to the primary.
- **Async prober.** `ReplicaHealthProber` (`proxy/internal/`) runs `Connection.isValid(...)` against every replica pool already cached on the node every `accessflow.proxy.replica.probe-interval` (default 30s, per-endpoint timeout `probe-timeout`), feeding the registry — so recovered replicas re-enter rotation promptly and silently-degraded ones are removed before a user query fails on them. It is deliberately **not** a `@Scheduled`/`@SchedulerLock` job (the one sanctioned exception to the scheduled-job rule): ShedLock makes a job a cluster singleton, but Hikari pools and breaker state are per JVM — every node must probe its own pools. It runs on the Boot-autoconfigured virtual-thread `TaskScheduler` instead.
- On full replica-set exhaustion (every endpoint down or failed, with at least one live failure this attempt), the resolver records **one** `DATASOURCE_REPLICA_FALLBACK` audit row (action in `audit/api/AuditAction`; metadata includes the `error` message, `query_type=SELECT`, `replica_count`, and the redacted `tried_endpoints` labels), logs a `WARN`, and falls back to the primary so the query still runs. The fallback audit is recorded with `actorId=null` (system-initiated). Audit failures are swallowed. Note the changed cadence vs pre-2.2: SELECTs served by the primary while every circuit is already open do **not** re-audit — with the default cooldown, sustained downtime produces roughly one row per cooldown period instead of one per SELECT.
- Per-endpoint health (redacted host:port label, breaker state, pool gauges) is surfaced on the admin datasource-health snapshot (`replicas` array) and dashboard.
- The Hikari pool tuning under `accessflow.proxy.*` (`connection-timeout`, `idle-timeout`, `max-lifetime`, `leak-detection-threshold`) applies to every replica pool too — no separate env vars.

### SELECT result caching (AF-457)

`SelectResultCache` (`proxy/internal/`) is an opt-in, Redis-backed cache for relational SELECT results — repeated identical reads skip the customer database entirely. It is gated twice: the deployment-wide kill-switch `accessflow.proxy.cache.enabled` (default `true`) AND the per-datasource `result_cache_enabled` opt-in (default `false`). Engine-managed datasources (MongoDB & co.) are not cached.

- **Security-safe keying.** Row-level security is spliced into the SQL *before* execution and masking is applied *post-fetch*, so the cache stores the **final post-mask** result keyed by a SHA-256 over: the RLS-rewritten SQL, its bind values, the sorted restricted-column list, the sorted mask directives, and the effective row cap. Two principals with different security scopes can never share an entry — different RLS means different SQL/binds, different masks mean a different key. Value keys are `accessflow:proxy:resultcache:{datasourceId}:{sha256}`.
- **Write invalidation.** Every cached key is indexed in one Redis SET per referenced table (`accessflow:proxy:resultcache:idx:{datasourceId}:{table}`, tables normalized exactly as `SqlParserServiceImpl` produces them) plus a per-datasource `__all__` set — deterministic, cluster-safe invalidation with no `SCAN`. Any successful write through the proxy (single DML/DDL or a committed `BEGIN…COMMIT` batch) drops every entry indexed under its referenced tables; a write whose tables are unknown (empty `referencedTables`) fails safe by purging the whole datasource cache. GDPR erasure and retention-policy deletes (the `lifecycle` module) pass their target table through the same path, so erased rows never survive in cached reads. `ResultCacheEvictionListener` additionally purges the datasource on `DatasourceConfigChangedEvent`, `DatasourceDeactivatedEvent`, and `DatasourceCacheConfigChangedEvent`.
- **Guards.** SELECTs whose referenced tables are unknown (e.g. `sampleTable` previews) are never cached — no invalidation coverage, no entry. Entries whose serialized form exceeds `accessflow.proxy.cache.max-entry-bytes` (default 1 MB) are skipped. Every Redis failure is swallowed with a WARN — cache degradation never fails a query. Dry-runs never touch the cache.
- **TTL.** Per-datasource `result_cache_ttl_seconds` (1–86400), falling back to `accessflow.proxy.cache.default-ttl` (default `PT60S`). Index sets outlive their members by 60s so invalidation never misses a live entry.
- **Semantics.** Cached rows round-trip through JSON, so a hit carries JSON-natural value types — identical to what API clients receive anyway, since results are only ever serialized to JSON downstream. `duration` reflects the (near-zero) cache-hit time, and the `accessflow.query.execute` observation is tagged `cache=hit|miss|off`.

### Batch writes (AF-457)

Inside a `BEGIN…COMMIT` envelope, `BatchInsertPlanner` (`proxy/internal/`) groups **consecutive homogeneous single-row INSERTs** — same table, same column list, same arity, literal-only VALUES, and a no-op row-security rewrite — into one `PreparedStatement` executed with `addBatch()` per row and `executeLargeBatch()`, flushed every `accessflow.proxy.execution.insert-batch-chunk-size` rows (default 1000). This collapses N network round trips into ~N/chunk for bulk-load envelopes while staying 100% `PreparedStatement` (literals become bound parameters).

- Anything else in the envelope — expressions or subselects in VALUES, multi-row `VALUES (...),(...)` (already one statement/one round trip), INSERT…SELECT, UPDATE/DELETE, or a statement the RLS/soft-delete rewriter touched — stays on the existing per-statement path, in order, inside the same transaction (`autoCommit=false`, commit on success / rollback on `SQLException`).
- PostgreSQL string literals are bound with an unspecified JDBC type (`Types.OTHER`) so the server infers the column type exactly as it would for the inline literal — a plain `varchar` bind would be rejected against `uuid`/`jsonb`/enum columns (42804). Other dialects bind plainly.
- `SUCCESS_NO_INFO` batch counts are tallied as one affected row each.

### Datasource health dashboard (AF-365)

`GET /api/v1/admin/datasource-health` (controller in `security/internal/web`, ADMIN-only) returns one snapshot row per datasource in the caller's org — live pool gauges plus a trailing 24-hour aggregate of query volume, latency percentiles, and error count. The controller lives in the `security` web layer (alongside the other admin controllers) and delegates to `DatasourceHealthService` (`proxy/api/`); the `security` module already depends on `proxy/api/` (its `GlobalExceptionHandler` maps proxy exceptions), so no new module edge or cycle is introduced.

- `DefaultDatasourceHealthService` (`proxy/internal/`) assembles each page: it lists datasources via `DatasourceAdminService.listForAdmin(...)` (`core/api/`), reads live pool gauges from `DatasourceConnectionPoolManager.poolStats(...)` (same module), and aggregates query stats via `DatasourceQueryStatsLookupService` (`core/api/`).
- **Cache:** Spring's cache abstraction (`org.springframework.cache.CacheManager` / `Cache`) backed by a `CaffeineCacheManager` bean (`ProxyConfiguration`) whose `expireAfterWrite` spec is derived from `accessflow.proxy.health.cache-ttl` (default `PT30S`, env `ACCESSFLOW_PROXY_HEALTH_CACHE_TTL`) — Caffeine is the provider purely for its TTL eviction. The service reads the `datasourceHealth` cache per `(organizationId, datasourceId)` key, collects the misses, fills them all with a single batched stats query (no N+1), then `put`s each back. The org is part of the key, so snapshots are never cross-served between tenants.
- **Query aggregate:** `DefaultDatasourceQueryStatsLookupService` (`core/internal/`) delegates to a custom repository fragment (`QueryRequestStatsRepository` / `QueryRequestStatsRepositoryImpl`) that runs a native `EntityManager` query — `count(*)`, `count(*) FILTER (WHERE status = 'FAILED')`, and `percentile_cont(0.5|0.95) WITHIN GROUP (ORDER BY execution_duration_ms)` over `query_requests` where `created_at > now() - 24h`, grouped by `datasource_id`. It is a custom fragment (not a Spring Data `@Query`) because the `FILTER` / `WITHIN GROUP` syntax is rejected by the JSqlParser-based query enhancer Spring Data selects when JSqlParser is on the classpath; Hibernate passes native SQL through verbatim. The supporting index `idx_query_requests_datasource_created_at (datasource_id, created_at)` is added in `V52`.
- Pool gauges are `null` when no live pool is cached (pools are created lazily — a never-queried datasource shows "pool not initialized" on the frontend). Latency percentiles are `null` when no executed query carried a duration in the window.

### Query Execution

Implemented in `proxy/internal/`:

- `QueryExecutor` (public API in `proxy/api/`) — single method `QueryExecutionResult execute(QueryExecutionRequest)`. Pure execution primitive: input is `(datasourceId, sql, queryType, maxRowsOverride?, statementTimeoutOverride?)`; output is a sealed `QueryExecutionResult` (`SelectExecutionResult` | `UpdateExecutionResult`). Status transitions and `query_requests` writes live in the workflow orchestrator that consumes this service.
- `DefaultQueryExecutor` — `@Service`. Resolves the datasource descriptor, computes `effectiveMaxRows = min(override ?? datasource.maxRowsPerQuery, accessflow.proxy.execution.max-rows)` and `effectiveTimeout = override ?? accessflow.proxy.execution.statement-timeout`, then branches on the request's `transactional` flag:
  - Non-transactional (default):
    ```
    Connection.setReadOnly(queryType == SELECT)
    PreparedStatement.setQueryTimeout(effectiveTimeout)
    PreparedStatement.setFetchSize(min(effectiveMaxRows + 1, accessflow.proxy.execution.default-fetch-size))
    if SELECT → setMaxRows(effectiveMaxRows + 1) + executeQuery + materialize
    else      → executeLargeUpdate
    ```
    `autoCommit` is left at the HikariCP default (`true`). The `+1` row beyond the cap is read solely to mark the result `truncated=true` and is then discarded.
  - Transactional (DML batch wrapped in `BEGIN; … COMMIT;`): opens a single connection, sets `readOnly=false` and `autoCommit=false`, iterates the parser-supplied inner statements as separate `PreparedStatement`s, sums `executeLargeUpdate()` into the response's `rowsAffected`, and `commit()`s. On any `SQLException`, the connection is rolled back (with any rollback failure attached as a suppressed exception) before the translator turns the original failure into `QueryExecutionFailedException` (preserving `sqlState` / `vendorCode`). Restricted-column filtering does not apply (transactions are DML-only).
- `JdbcResultRowMapper` — converts `ResultSet` rows into JSON-friendly Java types: `null` for SQL NULL, `OffsetDateTime` for date/time/timestamp, `BigDecimal` for `NUMERIC`/`DECIMAL`, `"base64:<...>"` strings for `BYTEA`/`BLOB`, raw passthrough for PostgreSQL `JSON`/`JSONB`, `String` for PostgreSQL `UUID`, recursive mapping for `ARRAY`. Unknown types fall back to `toString()` with a `WARN` log.
- `SqlExceptionTranslator` — package-private. Maps `SQLException` → `QueryExecutionException` subclasses. SQLState `57014` (PostgreSQL cancellation), `HY008` (MySQL/ODBC cancellation), and `70100` (MySQL connection killed) become `QueryExecutionTimeoutException`; everything else becomes `QueryExecutionFailedException` with the verbatim driver message captured in `detail` (alongside `sqlState` and `vendorCode`) and a generic localized message as `getMessage()`. The lifecycle service prefers `detail` when persisting `error_message` (AF-408).

Configuration (`accessflow.proxy.execution.*`, see `application.yml` block above):

| Key | Default | Purpose |
|-----|---------|---------|
| `max-rows` | `10000` | Global ceiling for SELECT result rows. Per-datasource `maxRowsPerQuery` is clamped to this. |
| `statement-timeout` | `30s` | Default JDBC `setQueryTimeout` for every execution. |
| `default-fetch-size` | `1000` | JDBC `setFetchSize` hint to bound driver-side buffers. |
| `max-result-bytes` | `52428800` (50 MiB) | Per-result byte cap enforced during row materialization (#49). |
| `max-concurrent` | `32` | Global in-flight execution budget across all datasources (#49). |
| `acquire-timeout` | `5s` | How long an overflow execution waits for a permit before a 503 (#49). |

Exception → HTTP mapping is in `security/internal/web/GlobalExceptionHandler.java`:

| Exception | Status | `error` code |
|-----------|--------|--------------|
| `QueryExecutionTimeoutException` | 504 Gateway Timeout | `QUERY_EXECUTION_TIMEOUT` |
| `QueryExecutionFailedException` | 422 Unprocessable Entity | `QUERY_EXECUTION_FAILED` (also exposes `sqlState`, `vendorCode`) |
| `DatasourceUnavailableException` | 422 Unprocessable Entity | `DATASOURCE_UNAVAILABLE` |
| `PoolInitializationException` | 503 Service Unavailable | `POOL_INITIALIZATION_FAILED` |
| `QueryConcurrencyLimitExceededException` | 503 Service Unavailable | `QUERY_CONCURRENCY_LIMIT` |

#### Result byte cap & concurrency budget (#49)

Two heap-protection guards on top of the row cap:

- **Per-result byte cap** (`max-result-bytes`, default 50 MiB). `JdbcResultRowMapper` accumulates a
  rough per-row size estimate (`ResultByteEstimator`: string length ×2 + overhead, BigDecimal digit
  count, fixed sizes for primitives, recursive for arrays) while materializing; when the running
  total would exceed the cap it stops, marks the result `truncated=true`, and sets
  `truncatedReason="BYTE_LIMIT"` (row-cap truncation sets `"ROW_LIMIT"`; an untruncated result has
  `null`). The first row is always kept so a single oversized row still returns data. The reason is
  persisted on `query_request_results.truncated_reason` and surfaced in
  `GET /queries/{id}/results` and `GET /datasources/{id}/sample-rows`. The cap applies to the
  relational JDBC path only — engine-managed (NoSQL) datasources enforce their own `effectiveMaxRows`
  but are not byte-capped.
- **Global concurrency budget** (`max-concurrent` default 32, `acquire-timeout` default 5s).
  `ConcurrencyLimitingQueryExecutor` — the `@Primary` `QueryExecutor` decorator — holds a fair
  `Semaphore` permit around `execute()` and `sampleTable()` (both materialize rows in heap);
  `dryRun()` passes through unguarded (EXPLAIN-only, zero rows). Overflow callers block up to the
  acquire timeout, then get `QueryConcurrencyLimitExceededException` → 503
  `QUERY_CONCURRENCY_LIMIT`. Per-datasource concurrency remains HikariCP's job
  (`connectionPoolSize`) — the budget only bounds JVM-wide heap pressure. A saturation load test
  lives at `backend/load-tests/concurrency-budget.js` (k6, manual — see its README).

Out of scope for the executor itself (tracked separately): the workflow orchestrator that flips `QueryStatus` and writes execution metadata onto `query_requests`.

### Break-glass / emergency access (AF-385)

A distinct submission mode in the `workflow` module that **skips pre-approval** but compensates with heightened controls and a mandatory retrospective review. Use it when production is on fire and approvers are unavailable.

- **Gate.** A per-user/per-datasource `can_break_glass` flag on `datasource_user_permissions` (time-boxed via the existing `expires_at`). It is **required for everyone, including ADMINs** — break-glass deliberately does NOT honour the admin permission bypass that normal submission grants. `DefaultBreakGlassService.breakGlassExecute` verifies a non-null, non-expired permission with `can_break_glass=true` **and** the capability for the parsed query type **and** the table allow-list (shared with normal submission via `DatasourcePermissionChecker`); otherwise `BreakGlassNotPermittedException` (403 `BREAK_GLASS_NOT_PERMITTED`).
- **Flow.** Validate datasource (active) → quota check → parse SQL (reject `OTHER` → 422) → gate → persist the `query_requests` row as `submission_reason=EMERGENCY_ACCESS` **without publishing `QuerySubmittedEvent`** (AI analysis + human review are bypassed) → `transitionTo(PENDING_AI, APPROVED)` → insert a `break_glass_events` retro-review row (`PENDING_REVIEW`) → `QueryLifecycleService.executeBreakGlass(queryId, actorUserId)` runs the query through the **identical proxy guards** (allow-list, masking, row-security, row caps) as a normal execution, recording the audit action `QUERY_BREAK_GLASS_EXECUTED` (with `break_glass=true` metadata) instead of `QUERY_EXECUTED` → publish `BreakGlassExecutedEvent`. The whole thing runs in one transaction, so a fail-closed row-security/parse error (422) rolls back the query + event entirely. The endpoint returns **200 synchronously** (execution already ran) — unlike normal submit's 202.
- **Compensating controls.** (a) Instant fanout to **all active org admins** incl. PagerDuty — `BreakGlassExecutedEvent` is consumed by `NotificationListener.onBreakGlassExecuted`, dispatched as `NotificationEventType.BREAK_GLASS_EXECUTED` (recipients + org-wide channels mirror `AI_HIGH_RISK`; PagerDuty trigger `BREAK_GLASS`). (b) The prominent `QUERY_BREAK_GLASS_EXECUTED` audit row. (c) A **mandatory retro-review** in `break_glass_events`, surfaced on the admin "Break-glass log"; the executed query stays in its terminal `EXECUTED`/`FAILED` state and is never re-opened.
- **Retro-review.** `BreakGlassAdminService.list` (ADMIN/AUDITOR) and `acknowledge` (ADMIN only). Acknowledging transitions `PENDING_REVIEW → REVIEWED`, writes `BREAK_GLASS_REVIEWED`, and publishes `BreakGlassReviewedEvent`. The **submitter can never acknowledge their own** break-glass event (`SelfAcknowledgeNotAllowedException`, 403) — mirroring the no-self-approve invariant.
- **Persistence.** The `break_glass_events` entity + repository are **workflow-module-local** (`workflow/internal/persistence/`), like `query_snapshots`; FK UUIDs (`submitted_by`, `reviewed_by`, `organization_id`, `datasource_id`) are bare (no FK) so deleting a user never erases the forensic record, while `query_request_id` keeps a real `UNIQUE` FK as the one-event-per-query idempotency backstop.

### MongoDB engine

`db_type=MONGODB` datasources are a NoSQL document engine — not JDBC. Since AF-414 everything MongoDB-specific lives in the **`engines/mongodb/` plugin** (a standalone Maven project producing the shaded `accessflow-engine-mongodb-<version>-all.jar`, resolved on demand through the connector catalog — see "Engine-plugin SDK" below); the rest of the platform (submission, AI analysis, review workflow, audit, permissions, result storage, the `/queries/{id}/results` endpoint) is unchanged. Two clean dispatch points by `DbType` keep the SQL path untouched:

- **Validation** — the workflow layer calls `proxy.api.QueryParser.parse(query, dbType)` (impl `DefaultQueryParser`) instead of `SqlParserService` directly. Engine-managed `DbType`s (those whose connector manifest declares a non-RELATIONAL `category` — `QueryEngineCatalog.isEngineManaged(dbType)`, a metadata-only check that never downloads a plugin) route to the engine resolved from `core.api.QueryEngineCatalog`; everything else goes to the JSqlParser-backed `SqlParserService`. Both return the engine-neutral `SqlParseResult` (`referencedTables` carries collection names).
- **Execution** — `DefaultQueryExecutor.execute(...)` branches early: an engine-managed `descriptor.dbType()` delegates to `engineCatalog.engineFor(dbType).execute(...)` with the host-computed effective row cap and statement timeout.

**Query forms.** `MongoQueryParser` parses **both** supported forms into an internal `MongoCommand`, auto-detecting by leading token:
- **Shell** (leading `db.`): `db.users.find({ age: { $gt: 21 } }).limit(10).sort({ name: 1 })`, `db.orders.aggregate([…])`, `db.users.insertOne({…})`, `updateMany`/`replaceOne`/`findOneAndUpdate`, `deleteOne`/`deleteMany`, `db.users.createIndex({…})`, `db.createCollection('x')`, `db.x.drop()`, `db.x.distinct('field', {…})`, `db.x.countDocuments({…})`, and the `db.getCollection("name")…` accessor. `MongoJson` parses arguments with a relaxed Jackson reader (single quotes, unquoted keys, comments, trailing commas) and, when that fails, **falls back to MongoDB's own lenient reader** so shell extended-JSON constructors — `ObjectId(…)`, `ISODate(…)`, `new Date(…)`, `NumberLong(…)`, `NumberDecimal(…)`, `UUID(…)` and canonical `$oid`/`$date` — also parse (common in AI-generated `insertMany` drafts). The fallback yields driver-native BSON types the executor hands straight to `insertOne`/`insertMany`, and the forbidden-operator check still runs on the parsed tree.
- **JSON command** (leading `{`): the native MongoDB command document, e.g. `{ "find": "users", "filter": {…}, "limit": 5 }`, `{ "aggregate": … }`, `{ "insert": …, "documents": […] }`, `{ "update": …, "updates": [{ "q": …, "u": …, "multi": true }] }`, `{ "delete": …, "deletes": [{ "q": …, "limit": 0 }] }`, `{ "create": … }`, `{ "createIndexes": … }`, `{ "drop": … }`, `{ "findAndModify": … }`.

Each operation maps onto the existing `QueryType` (find/aggregate/count/distinct → `SELECT`; insert\* → `INSERT`; update\*/replace\*/findAndModify → `UPDATE`; delete\* → `DELETE`; createCollection/createIndex/drop\* → `DDL`), so the read/write/DDL permission model, routing-policy engine, and approval workflow apply unchanged. Server-side-JavaScript and write-exfiltration operators (`$where`, `$function`, `$accumulator`, `$out`, `$merge`) and unknown operations are rejected with `InvalidSqlException` → HTTP 422, matching the SQL engine.

**Connection management.** The plugin's `MongoClientManager` caches one native `MongoClient` per datasource (the driver pools internally); the host's `EngineEvictionListener` fans `DatasourceConfigChangedEvent`/`DatasourceDeactivatedEvent` out to `QueryEngine.evictDatasource(...)` — the document-engine analogue of `DefaultDatasourceConnectionPoolManager` + `DatasourcePoolEvictionListener`. The connection-string URI is built by the plugin's `MongoConnectionStringFactory` (shared by the query path and the connection-test / introspection path) from host/port/database/credentials/SSL; tuning via `accessflow.proxy.engines.mongodb.*` (`connect-timeout`, `server-selection-timeout`, `max-pool-size`) — the generic per-engine config lane (`EngineConfigProperties`, AF-418) bound by the host and handed verbatim to the plugin through the engine-context config map; the pre-AF-418 `ACCESSFLOW_PROXY_MONGO_*` env vars keep working as aliases via `application.yml` placeholders. A datasource with a configured read replica routes `SELECT`s with `ReadPreference.secondaryPreferred()`.

**Row-level security + masking (parity with SQL).** `MongoRowSecurityApplier` translates each matching `RowSecurityDirective` into a filter fragment (`EQUALS → {f:v}`, `IN → {f:{$in:[…]}}`, …; empty values ⇒ fail-closed deny-all) and merges it into the find/update/delete filter, prepends a `$match` stage to aggregate pipelines, or rejects an insert into a policied collection (HTTP 422). `MongoResultMapper` flattens result documents into the engine-neutral `SelectExecutionResult` (columns = ordered union of top-level fields; nested objects/arrays preserved as `Map`/`List`), applies restricted columns and column masks per value via the **shared** `ColumnMasker`, and normalizes BSON scalars (ObjectId → hex, Decimal128 → BigDecimal, Date → ISO, Binary → base64). Applied policy ids flow onto `appliedRowSecurityPolicyIds`/`appliedMaskingPolicyIds` exactly as the SQL path.

**Connection test + introspection** — `QueryEngine.testConnection` runs a `ping`; `QueryEngine.introspectSchema` lists collections and samples documents to infer fields, returning the same `DatabaseSchemaView` (schema = database, tables = collections, columns = fields, `_id` flagged primary key) used by the ER diagram, editor autocomplete, and AI schema context. `DatasourceAdminServiceImpl` dispatches both through the engine catalog for `MONGODB` datasources.

**AI / text-to-query.** Risk analysis works unchanged — the analyzer is `DbType`-generic (the type is a prompt label) and `SystemPromptRenderer.describeSchema` is engine-agnostic. Text-to-query (AF-439) **is** surfaced for MongoDB datasources: the engine-aware generation prompt drafts a MongoDB shell command (`db.users.find({…})`) or its JSON command form instead of SQL, and the draft is still submitted through the normal pipeline.

**Engine-plugin SDK (AF-414).** MongoDB has no JDK-level SPI the host could compile against (unlike JDBC's `java.sql.*`), so the engine is decoupled through AccessFlow's own SPI instead of a compile-time dependency:

- **`core.api.QueryEngine`** — `engineId()` / `initialize(QueryEngineContext)` / `parse` / `execute` / `testConnection` / `introspectSchema` / `evictDatasource` / `shutdown`, defined entirely over the engine-neutral api-pure DTOs (`SqlParseResult`, `QueryExecutionRequest`/`QueryExecutionResult`, `ConnectionTestResult`, `DatabaseSchemaView`, `DatasourceConnectionDescriptor`, `RowSecurityDirective`, `ColumnMaskDirective`) and the pure `core.api.ColumnMasker` helper, all of which now live in `core.api`. Engines throw the existing concrete exception types (`InvalidSqlException`, `QueryExecutionFailedException`/`QueryExecutionTimeoutException`, `DatasourceConnectionTestException`) so host error handling is engine-agnostic.
- **`core.api.QueryEngineContext`** replaces Spring DI for plugins: the host hands over message resolution (`EngineMessages`, backed by `MessageSource` + the calling thread's locale — the `error.mongo.*` keys stay in the host's `messages.properties` as part of the host↔plugin contract), a narrow `CredentialDecryptor`, the engine's tuning config as a string map, and the host UTC `Clock`.
- **Resolution** — `proxy.internal.driver.DefaultQueryEngineCatalog` (implements `core.api.QueryEngineCatalog`) looks up the connector manifest with a non-RELATIONAL `category` for the `DbType`, pulls the pinned plugin JAR through the same `DriverJarCache` pipeline as JDBC drivers (download → SHA-256 verify → cache under `ACCESSFLOW_DRIVER_CACHE`; `ACCESSFLOW_DRIVERS_OFFLINE` with no cached JAR fails with `OFFLINE_CACHE_MISS` exactly like a JDBC connector), loads it into an isolated `URLClassLoader` (`accessflow-engine-<id>`), and discovers the implementation via `java.util.ServiceLoader`, matched by `engineId()` against the connector id. The engine is initialized once and cached for the application lifetime.
- **Packaging** — the plugin (`engines/mongodb/`, artifact `accessflow-engine-mongodb`, its own version line) is a **self-contained shaded JAR** bundling `mongodb-driver-sync` plus a relocated Jackson and micrometer-observation (the catalog classloader does no transitive resolution); it compiles against the backend's plain JAR (`provided` scope) and ships with a reproducible build so the SHA-256 pinned in `connectors/mongodb/connector.json` is stable. CI's `engines` job and the release workflow both fail on pin drift; releases publish the JAR to the `gh-pages` branch under `engines/`. Adding another NoSQL engine is a new plugin project + a connector manifest entry (+ a `DbType` migration and frontend mode-registration data) — no changes to `DefaultQueryEngineCatalog`, the dispatchers, CI, or the release workflow (AF-418). The full engine-author guide and add-an-engine checklist live in [docs/15-engine-sdk.md](15-engine-sdk.md); see also [`engines/mongodb/README.md`](../engines/mongodb/README.md) and [docs/14-connectors.md](14-connectors.md).

### Couchbase engine

`db_type=COUCHBASE` datasources (AF-412) are the second engine-plugin connector, built on the AF-418 SDK with **zero host changes** beyond the `DbType` value: everything Couchbase-specific lives in the **`engines/couchbase/` plugin** (artifact `accessflow-engine-couchbase`, own version line, reproducible shaded JAR bundling the Couchbase Java SDK with a relocated Reactor and Jackson, pinned in `connectors/couchbase/connector.json`). The dispatchers, catalog resolution, eviction fan-out, and CI/release discovery described above apply unchanged.

**Query language.** Couchbase speaks **SQL++ (N1QL)** — SQL-shaped, so the plugin's `CouchbaseQueryParser` is a keyword classifier over a purpose-built tokenizer (`SqlPlusPlusTokenizer`: comment-stripping, string/backtick-literal aware, nesting-depth tracking) rather than a full AST. Exactly one statement per submission (trailing `;` tolerated). Classification onto `QueryType`: `SELECT` → SELECT; `INSERT`/`UPSERT` → INSERT; `UPDATE`/`MERGE` → UPDATE; `DELETE` → DELETE; `CREATE`/`DROP` of `[PRIMARY] INDEX` / `SCOPE` / `COLLECTION` → DDL. Everything else fails closed with `InvalidSqlException` → HTTP 422 — including the **`CURL()`** function (server-side exfiltration), **JavaScript UDF statements** (`CREATE`/`EXECUTE`/`DROP FUNCTION`), and **`system:*` keyspaces**, the SQL++ counterparts of the MongoDB `$where`/`$out` ban.

**Keyspaces and grants.** Every statement executes through the datasource bucket's **default-scope query context** (`database_name` = the bucket): a bare `FROM users` resolves to `<bucket>._default.users` and `referencedTables` carries `users` (matching a collection-level grant); a fully-qualified `bucket.scope.collection` path is carried verbatim — the host's allow-list matcher accepts an exact full-path grant or an `allowedSchemas` entry matching the bucket segment. CTE aliases are excluded, like the JSqlParser path.

**Row-level security + masking (parity with SQL).** `CouchbaseRowSecurityApplier` ANDs each matching `RowSecurityDirective` into the WHERE clause of a simple single-keyspace SELECT / UPDATE / DELETE — values bound as **named parameters** (`$af_rls_n`, via `QueryOptions.parameters`), never concatenated; an existing WHERE expression is parenthesized first; empty directive values become a literal `FALSE` (fail-closed deny-all). Shapes the splice cannot provably filter fail closed with `UnrewritableRowSecurityException` → HTTP 422, mirroring the SQL `RowSecurityRewriter`: CTEs, subqueries, `JOIN`/`NEST`/`UNNEST`, `USE KEYS`, set operations, multi-keyspace statements, and **MERGE** (simultaneously a join-DML and a `WHEN NOT MATCHED THEN INSERT` carrier). `INSERT`/`UPSERT` into a policied keyspace is rejected outright (MongoDB parity). `CouchbaseResultMapper` materializes rows into the engine-neutral `SelectExecutionResult` (columns = ordered union of top-level fields; `SELECT RAW` scalar rows become a `value` column; a `SELECT *` page is unwrapped from its keyspace-alias wrapper so `collection.field` masking refs match), applying restricted columns and masks per value via the **shared** `ColumnMasker` with the `collection.field` → bare-`field` precedence.

**Execution & connections.** `CouchbaseClusterManager` caches one native `Cluster` per datasource, dropped via the same `evictDatasource` fan-out. SELECTs stream through the reactive API capped at `maxRows + 1` (truncation detection without unbounded buffering) and run `readonly`; DML returns the query service's `mutationCount`; DDL returns 0. The host's effective statement timeout maps to `QueryOptions.timeout`. Connection strings: `couchbase://host:port` (`ssl_mode=DISABLE`; KV port 11210, the manifest default) or `couchbases://` (`REQUIRE` → trust-any-certificate, `VERIFY_CA` → CA validation without hostname verification, `VERIFY_FULL` → SDK defaults; TLS bootstraps on port 11207), or the datasource URL override verbatim. Tuning via `accessflow.proxy.engines.couchbase.*`: `connect-timeout` (PT10S), `wait-until-ready-timeout` (PT10S), and `scan-consistency` (`request-plus` default — reads observe mutations submitted before the query, the predictable choice for a governance proxy; `not-bounded` opts back into Couchbase's faster default).

**Connection test + introspection.** `testConnection` waits for the bucket and runs `SELECT RAW 1` through the query service (proving SQL++ can actually execute, not just KV bootstrap). `introspectSchema` maps scopes → schemas and collections → tables, samples fields with a bounded `SELECT t.* … LIMIT 50` per collection, reports the document key as the `meta().id` primary-key column, and degrades to the key column alone for collections without an index — feeding the same ER diagram, editor autocomplete, and AI schema context. Risk analysis works unchanged; text-to-query **is** offered for Couchbase datasources — the engine-aware generation prompt (AF-439) drafts a single SQL++ (N1QL) statement.

### Redis engine

`db_type=REDIS` datasources (AF-419) are the third engine-plugin connector and the first **key-value** (`category=KEY_VALUE`) one, built on the AF-418 SDK with **zero host changes** beyond the `DbType` value: everything Redis-specific lives in the **`engines/redis/` plugin** (artifact `accessflow-engine-redis`, own version line, reproducible shaded JAR bundling the [Jedis](https://github.com/redis/jedis) driver with a relocated commons-pool2 / gson / org.json, pinned in `connectors/redis/connector.json`). The dispatchers, catalog resolution, eviction fan-out, and CI/release discovery apply unchanged.

**Query language.** Redis speaks **commands**, not SQL, so the plugin's `RedisCommandParser` tokenizes a single redis-cli command (quote-aware; multi-line / multi-command input is rejected, the analogue of the SQL multi-statement ban) and matches it against a strict **allow-list** (`RedisCommand`). Classification onto `QueryType`: reads (`GET`/`MGET`/`HGETALL`/`SCAN`/`KEYS`/`TTL`/`LRANGE`/`SMEMBERS`/`ZRANGE`/…) → SELECT; conditional-create (`SETNX`/`HSETNX`/`MSETNX`/`RENAMENX`) → INSERT; modifies (`SET`/`HSET`/`LPUSH`/`EXPIRE`/`INCR`/…) → UPDATE; removals (`DEL`/`UNLINK`/`GETDEL`/`HDEL`/`SREM`/`LPOP`/…) → DELETE; admin (`FLUSHDB`) → DDL. Everything outside the allow-list fails closed with `InvalidSqlException` → HTTP 422 (`error.redis.unsupported_command`). A dedicated **forbidden** set is rejected up front with a distinct message (`error.redis.forbidden_command`) — the key-value counterpart of the MongoDB `$where` ban: server-side scripting (`EVAL`/`EVALSHA`/`SCRIPT`/`FUNCTION`/`FCALL`), blast-radius / admin (`CONFIG`, `FLUSHALL`, `SHUTDOWN`, `DEBUG`, `MIGRATE`, `CLUSTER`, `ACL`, `MODULE`, `CLIENT`, `SWAPDB`, …), replication/persistence (`REPLICAOF`, `SAVE`, …), multi-command transactions (`MULTI`/`EXEC`/…), blocking reads (`BLPOP`/…), pub/sub, and connection-state mutation (`SELECT`, `MOVE`).

**Allow-list semantics + grants.** `referencedTables` carries the key **prefix** — the text before the first `:` (`orders:*` → `orders`, `user:42` → `user`, bare `foo` → `foo`, lowercased); multi-key (`MGET`/`MSET`/`DEL`) and two-key (`COPY`/`RENAME`/`SMOVE`) commands contribute every operand's prefix, `SCAN`/`KEYS` derive the prefix from the `MATCH`/pattern argument, and a glob-only or keyless command contributes nothing (the host treats an empty set as "no tables detected" = deny, not allow). Schema allow-lists, permissions, and row-security policies therefore target a meaningful key namespace.

**Row security fails closed; masking at parity.** Row-level predicates have no meaning in a key-value model: when a `RowSecurityDirective` targets a referenced key prefix, `RedisQueryExecutor` rejects execution with `UnrewritableRowSecurityException` → HTTP 422 (`error.row_security_redis_unsupported`); a directive that targets an unreferenced prefix is ignored. **Field masking** applies to returned values via the **shared** `ColumnMasker` with the same `prefix.field` → bare-`field` precedence as the SQL/MongoDB paths — a hash (`HGETALL`) exposes its field names as columns so `session.token`-style masks redact the matching field; strings/lists/sets/zsets expose a synthetic `value` column.

**Execution & connections.** `RedisClientManager` caches one native `JedisPooled` per datasource, dropped via the same `evictDatasource` fan-out. Reads are capped at `maxRows + 1` (truncation detection); a `SCAN` returns its single cursor page and sets `truncated` when more remain. `RedisQueryExecutor` decouples result shape from `QueryType`: count/status writes return an `UpdateExecutionResult`, but **value-returning mutators** (`GETDEL`/`LPOP`/`INCR`/`APPEND`/…) return a `SelectExecutionResult` carrying the value so the popped/new value is shown. Connections: `redis://host:port` (`ssl_mode=DISABLE`; port 6379 the manifest default) or `rediss://` (any other SSL mode), the optional ACL `username`, and `database_name` as the numeric DB index (default `0`). Tuning via `accessflow.proxy.engines.redis.*`: `connect-timeout` (PT5S), `socket-timeout` (PT5S — bounds command latency), `max-pool-size` (10).

**Connection test + introspection.** `testConnection` opens a short-lived client and `PING`s. `introspectSchema` SCAN-samples a bounded number of keys (never `KEYS`), groups them by prefix into pseudo-tables, and reports hash field names (sampled via `HKEYS`) or a synthetic `value` column typed by the Redis value type (`string`/`list`/`set`/`zset`), feeding the same ER diagram and AI schema context. Schema name is `db<index>`; no primary or foreign keys.

### Cassandra engine

`db_type=CASSANDRA` datasources (AF-421) are the fourth engine-plugin connector and the first **wide-column** (`category=WIDE_COLUMN`) one, built on the AF-418 SDK: everything CQL-specific lives in the **`engines/cassandra/` plugin** (artifact `accessflow-engine-cassandra`, own version line, reproducible shaded JAR bundling the [DataStax Java driver](https://github.com/apache/cassandra-java-driver) with a **relocated Netty / Typesafe Config / HdrHistogram** — the host carries its own Netty via Lettuce and HdrHistogram via Micrometer — and a merged `reference.conf`, pinned in `connectors/cassandra/connector.json`). CQL is SQL-shaped, so the engine follows the **Couchbase** pattern (keyword classifier + WHERE-splice), not Redis. The dispatchers, catalog resolution, eviction fan-out, and CI/release discovery apply unchanged.

**One plugin, two connectors (ScyllaDB).** ScyllaDB speaks the identical CQL binary protocol, so the same JAR registers **two** `QueryEngine` providers in `META-INF/services`: `CassandraQueryEngine` (`engineId()`=`"cassandra"`) and the thin `ScyllaDbQueryEngine extends CassandraQueryEngine` (`engineId()`=`"scylladb"`). The host matches `connectorId == engineId()` when ServiceLoading, so `connectors/cassandra/connector.json` and `connectors/scylladb/connector.json` pin the **same** JAR and `DbType.SCYLLADB` exists only because the connector catalog allows one connector per non-`CUSTOM` dialect — behaviour is identical.

**Query language.** The plugin's `CqlQueryParser` tokenizes a single CQL statement (quote/comment-aware; multi-statement input is rejected, the analogue of the SQL multi-statement ban) and classifies it onto `QueryType`: `SELECT` → SELECT; `INSERT` → INSERT (incl. `IF NOT EXISTS` LWT); `UPDATE` → UPDATE (incl. `IF …` LWT); `DELETE` → DELETE; `CREATE`/`ALTER`/`DROP` of a `TABLE` / `KEYSPACE` / `INDEX` / `TYPE` / `MATERIALIZED VIEW` and `TRUNCATE` → DDL. Two constructs fail closed with distinct HTTP 422 messages: `BEGIN … BATCH` (`error.cassandra.batch_forbidden`, the multi-statement carrier) and `CREATE`/`DROP FUNCTION`/`AGGREGATE` (`error.cassandra.udf_forbidden`, server-side code — the CQL counterpart of the MongoDB `$where` ban); anything else unsupported is `error.cassandra.unsupported_statement`. `referencedTables` carries every referenced table — bare `table` (resolved against the datasource keyspace) or qualified `keyspace.table`, lowercased — for the host's allow-list.

**Row security is key-aware and fails closed; masking at parity.** CQL can only filter on key columns without `ALLOW FILTERING`, which the proxy must never silently inject. `CassandraRowSecurityApplier` resolves the target table's partition + clustering key columns from the live `CqlSession` metadata, then ANDs each matching `RowSecurityDirective` into the WHERE clause with values bound as **named parameters** (`:af_rls_n`), never concatenated — **only** when the directive's column is a key column **and** its operator is one of `=, IN, <, <=, >, >=`. A non-key column, an unsupported operator (CQL WHERE has no `!=` / `NOT IN`), or a deny-all/empty value list is rejected with `UnrewritableRowSecurityException` → HTTP 422 (`error.row_security_cassandra_unrewritable`); INSERT into a policied table is rejected outright (`error.row_security_cassandra_insert_unsupported`), Cassandra INSERT being an upsert. **Field masking** applies post-fetch via the **shared** `ColumnMasker` with the same `table.column` → bare-`column` precedence as the SQL/Couchbase paths.

**Execution & connections.** `CassandraSessionManager` caches one native `CqlSession` per datasource (the driver pools and load-balances internally), dropped via the same `evictDatasource` fan-out. Reads page at `maxRows + 1` (truncation detection); rich CQL scalars (uuid, timestamp, inet, …) are stringified for JSON-safe persistence. DML returns 1 affected row (0 for a lightweight transaction whose `IF` condition did not match); DDL returns 0. Connections: contact point from host/port (default 9042), the **required per-datasource `local_datacenter`** (the driver's load-balancing datacenter — a real wizard field, since the default load-balancing policy mandates it), the datasource keyspace (`database_name`) as the session's default keyspace, auth from `username` + decrypted password, and SSL when `ssl_mode != DISABLE` (REQUIRE encrypts without certificate verification; VERIFY_* use the JVM trust store). Tuning via `accessflow.proxy.engines.{cassandra,scylladb}.*`: `connect-timeout` (PT10S), `request-timeout` (PT10S — the host overrides it per statement with the computed statement timeout).

**Connection test + introspection.** `testConnection` opens a short-lived session and runs `SELECT release_version FROM system.local` (the CQL `SELECT 1`). `introspectSchema` reads the driver's cluster metadata (derived from `system_schema.*`), surfacing every non-system keyspace as a schema and its tables as tables, flagging partition + clustering columns as the primary key — the same key-column source the row-security applier uses. No foreign keys.

### Elasticsearch engine

`db_type=ELASTICSEARCH` datasources (AF-420) are the fifth engine-plugin connector and the first **search** (`category=SEARCH`) one, built on the AF-418 SDK: everything search-specific lives in the **`engines/elasticsearch/` plugin** (artifact `accessflow-engine-elasticsearch`, own version line, reproducible shaded JAR). It operates at the **low-level REST client** — the engine controls every header, so it sends no Elastic product-check or version-compat media type — and manipulates raw JSON, which makes the scripting ban a JSON-tree scan (the analogue of MongoDB's `$where` ban). The dispatchers, catalog resolution, eviction fan-out, and CI/release discovery apply unchanged.

**One plugin, two connectors (OpenSearch).** OpenSearch speaks the same REST API and Query DSL for the governed operations, so the same JAR registers **two** `QueryEngine` providers in `META-INF/services`: `ElasticsearchQueryEngine` (`engineId()`=`"elasticsearch"`) and the thin `OpenSearchQueryEngine extends ElasticsearchQueryEngine` (`engineId()`=`"opensearch"`). They differ only in the low-level REST client used — Elasticsearch 9.x ships on Apache HttpComponents **4** (`org.apache.http`), OpenSearch 3.x on HttpComponents **5** (`org.apache.hc`) — so the JAR bundles both stacks (each relocated separately) behind a small `SearchTransport` abstraction; everything else (parser, row security, masking, introspection) is shared. `connectors/elasticsearch/connector.json` and `connectors/opensearch/connector.json` pin the **same** JAR and `DbType.OPENSEARCH` exists only because the catalog allows one connector per non-`CUSTOM` dialect — the search-engine analogue of one Cassandra JAR serving ScyllaDB.

**Query language.** Queries are an AccessFlow JSON envelope: a single object whose first recognised command key names the operation and whose value is the target index name / pattern. `EsQueryParser` classifies onto `QueryType`: `search` / `count` (and `get` / `mget`, lowered to a `search` over an `ids` query so there is one row-security path) → SELECT; `index` / `bulk` → INSERT; `update_by_query` → UPDATE; `delete_by_query` → DELETE; `create_index` / `put_mapping` / `delete_index` → DDL. `referencedTables` carries the lowercased index name / pattern. **Forbidden anywhere in the tree** (HTTP 422): `script` / `script_fields` / `script_score` / `scripted_metric` / `runtime_mappings` (any Painless) and the cluster-level APIs, plus an index value beginning with `_` or `.` (system indices). Bulk requests may only carry index actions — update / delete bulk actions are rejected so the operation classifies cleanly as a single INSERT.

**Row security as bool.filter; masking incl. nested fields.** `EsRowSecurityApplier` wraps the user query in `{"bool":{"must":[<query>],"filter":[<clauses>]}}` (never merged, so the rewrite is provably non-widening) for search / count / update_by_query / delete_by_query: `EQUALS → term`, `NOT_EQUALS → bool.must_not term`, range operators → `range`, `IN → terms`, `NOT_IN → bool.must_not terms`, and an empty value list → `bool.must_not match_all` (fail-closed, matches nothing). A write into a policied index (`index` / `bulk`) is rejected with `UnrewritableRowSecurityException` → 422 (`error.row_security_search_insert_unsupported`), since a write cannot be filtered; DDL is unaffected. **Caveat:** `term`/`terms` only match exact **keyword** fields — a policy column on an analysed `text` field matches tokens, not the literal value (surfaced via introspection field types, the search analogue of Cassandra's key-column limit). **Field masking** applies post-fetch via the **shared** `ColumnMasker`, recursively by **dot-path** so a mask on `user.email` redacts the nested leaf while the rest of `user` stays visible; the top-level column is flagged restricted when it or any descendant has a rule.

**Execution & connections.** `SearchClientManager` caches one REST client per datasource (the client pools HTTP connections internally), dropped via the same `evictDatasource` fan-out. Search pages at `maxRows + 1` to detect truncation, clamped so `from + size` never exceeds the index `max_result_window` (10000). A search / by-query `timed_out:true` (which ES returns as HTTP 200) and a bulk `errors:true` are translated to execution exceptions so a partial result never masquerades as success. Connections: base URL from host/port (default 9200) + scheme from `ssl_mode` (a verbatim `jdbc_url_override` URL is also honoured), authenticated by HTTP basic (`username` + decrypted password) **or** an **API key** (the encrypted `api_key` field, sent as `Authorization: ApiKey`). Tuning via `accessflow.proxy.engines.{elasticsearch,opensearch}.*`: `connect-timeout` (PT10S), `socket-timeout` (PT30S).

**Connection test + introspection.** `testConnection` opens a short-lived client and issues `GET /` (the cluster-info `SELECT 1`). `introspectSchema` reads `GET <pattern>/_mapping`, surfacing every non-system index as a table and each mapped field (flattened to dot-paths) as a column, with a synthetic `_id` keyword column flagged as the primary key (the only stable identity the engines expose). No foreign keys.

### DynamoDB engine

`db_type=DYNAMODB` datasources (AF-422) are the sixth engine-plugin connector and the first **key-value** (`category=KEY_VALUE`) one, built on the AF-418 SDK: everything DynamoDB-specific lives in the **`engines/dynamodb/` plugin** (artifact `accessflow-engine-dynamodb`, own version line, reproducible shaded JAR bundling the [AWS SDK for Java v2](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/home.html) `dynamodb` client with the **url-connection HTTP client — no Netty** — the SDK's default sync/async clients and the host's Spring/Netty tree are excluded from the shade, keeping the JAR Netty-free and small; pinned in `connectors/dynamodb/connector.json`). It is the **first engine whose connection is cloud credentials + region rather than host/port**, the deliberate stress-test of the SDK's flexibility. PartiQL is SQL-shaped, so the engine follows the **Cassandra** pattern (keyword classifier + WHERE-splice), not Redis. The dispatchers, catalog resolution, eviction fan-out, and CI/release discovery apply unchanged.

**Connection model.** DynamoDB has no host/port. The existing datasource columns are remapped: `database_name` = AWS **region** (required — the SDK needs it to sign requests even against a custom endpoint), `username` = access key id, `password_encrypted` = secret access key (decrypted via `CredentialDecryptor` only at client construction, pool-init parity), and `jdbc_url_override` = an optional **custom endpoint** (DynamoDB Local / VPC endpoints; blank ⇒ the AWS regional endpoint). `DatasourceAdminServiceImpl` enforces this with a dedicated DynamoDB branch in `validateDriverChoice` (require region, allow the override, no host/port). Session tokens / STS assumed-role credentials are out of scope for v1.

**Query language.** A submission is either a single PartiQL statement or a **JSON table-management command** (it begins with `{`); `PartiQlQueryParser.isJsonCommand` dispatches. `PartiQlQueryParser` tokenizes a PartiQL statement (quote/comment-aware; multi-statement input is rejected, the analogue of the SQL multi-statement ban) and classifies onto `QueryType`: `SELECT` → SELECT; `INSERT` → INSERT; `UPDATE` → UPDATE; `DELETE` → DELETE. The JSON form (`DynamoDbDdlCommand`) wraps a single `CreateTable` / `DeleteTable` / `UpdateTable` → DDL (the common fields — key schema, attribute definitions, billing mode / provisioned throughput, and CreateTable GSIs — are mapped to the typed control-plane request; exotic fields like streams / TTL / tags are out of scope for v1). Transaction/batch verbs (`EXECUTE TRANSACTION`, `BEGIN`) fail closed with HTTP 422 (`error.dynamodb.transaction_forbidden`), the DynamoDB counterpart of the SQL batch ban. `referencedTables` carries the (case-preserved) table name — an index access (`"Table"."Index"`) resolves to its base table — for the host's allow-list.

**Row security is WHERE-splice; masking incl. nested attributes.** Unlike CQL, DynamoDB PartiQL can filter on any attribute (a non-key predicate becomes a server-side Scan filter), so `DynamoDbRowSecurityApplier` is **not** key-restricted: it ANDs each matching `RowSecurityDirective` into the WHERE clause with values bound as **positional `?` parameters** (in source order), never concatenated, supporting `=, <>, <, <=, >, >=, IN, NOT IN`. An empty value list is the **fail-closed deny-all** signal — the executor returns an empty result without touching DynamoDB (PartiQL has no safe constant-false predicate). A write into a policied table (`INSERT`) is rejected with `UnrewritableRowSecurityException` → 422 (`error.row_security_dynamodb_insert_unsupported`), since it has no WHERE clause; DDL is unaffected. **Field masking** applies post-fetch via the **shared** `ColumnMasker`, recursively by **dot-path** so a mask on `profile.ssn` redacts the nested leaf while the rest of `profile` stays visible; a bare attribute mask recurses into nested maps/lists, and the top-level column is flagged restricted when it or any descendant has a rule.

**Execution & connections.** `DynamoDbClientManager` caches one native `DynamoDbClient` per datasource (the SDK client pools HTTP connections internally), dropped via the same `evictDatasource` fan-out. PartiQL runs through `ExecuteStatement`; SELECTs page through `NextToken` capped at `maxRows + 1` (truncation detection), and items (`Map<String,AttributeValue>`) are normalised to JSON-friendly values (S→String, N→BigDecimal, BOOL→Boolean, M→Map, L→List, B→`base64:…`). DML returns 1 affected row (0 on deny-all); a JSON DDL command runs the control-plane call and returns 0. The host-computed statement timeout is applied per request via `apiCallTimeout`. Connections: region from `database_name`, static credentials from `username` + decrypted password, the optional endpoint override from `jdbc_url_override`, over the url-connection HTTP client. Tuning via `accessflow.proxy.engines.dynamodb.*`: `connect-timeout` (PT10S), `api-call-timeout` (PT30S — the host overrides it per statement with the computed statement timeout).

**Connection test + introspection.** `testConnection` opens a short-lived client and issues `ListTables` (limit 1) — the DynamoDB `SELECT 1`. `introspectSchema` lists tables, then for each reads the key schema via `DescribeTable` (partition + sort key flagged as the primary key) and samples a bounded number of items via `Scan` to derive the remaining attribute names/types (Mongo-style, since DynamoDB is schemaless beyond its keys). One schema named after the region, tables = DynamoDB tables, columns = attributes. No foreign keys.

### Neo4j engine

`db_type=NEO4J` datasources (AF-423) are the seventh engine-plugin connector and the first **graph** (`category=GRAPH`) one, built on the AF-418 SDK: everything Neo4j-specific lives in the **`engines/neo4j/` plugin** (artifact `accessflow-engine-neo4j`, own version line, reproducible shaded JAR bundling the [Neo4j Java driver](https://neo4j.com/docs/java-manual/current/) and its Bolt-connection stack with a **relocated Netty, Project Reactor and reactive-streams** under `…engine.neo4j.shaded.*`; pinned in `connectors/neo4j/connector.json`). The query language is **Cypher** over the Bolt protocol; classification, masking, fail-closed parsing, and introspection mirror the other engines, but row-level security is the one genuinely new shape — Cypher has no SQL `WHERE … FROM`, so predicates are spliced onto each `MATCH`'s `WHERE`. The dispatchers, catalog resolution, eviction fan-out, and CI/release discovery apply unchanged.

**Connection model.** Neo4j connects over Bolt. `Neo4jDriverFactory` builds the URI from host/port with the encryption encoded in the scheme from `ssl_mode` — `DISABLE` → `bolt://` (plaintext), `REQUIRE` → `bolt+ssc://` (encrypted, trust any certificate — parity with the JDBC engines' `trustServerCertificate=true`), `VERIFY_CA`/`VERIFY_FULL` → `bolt+s://` (encrypted, verify against the system trust store) — **or** uses a full URI supplied verbatim through `jdbc_url_override` (e.g. `neo4j+s://…databases.neo4j.io` for Neo4j Aura / clustered routing, the second engine after DynamoDB to allow the override on a non-`CUSTOM` dialect). `database_name` selects the Neo4j database via `SessionConfig.forDatabase(...)` and is always required; `username` + decrypted `password_encrypted` form the basic auth token (decrypted only at driver construction, pool-init parity). `DatasourceAdminServiceImpl` enforces this with a dedicated NEO4J branch in `validateDriverChoice` (require database; require host/port unless an override URI is given).

**Query language.** `CypherQueryParser` classifies a single Cypher statement (a `CypherTokenizer` token stream; multi-statement input is rejected, the analogue of the SQL multi-statement ban). Cypher is clause-based, so the query type is the strongest write clause present: `DELETE`/`DETACH DELETE`/`REMOVE` → DELETE; `CREATE`/`MERGE` → INSERT; `SET` → UPDATE; a pure `MATCH … RETURN` / `SHOW …` read → SELECT. Schema/admin commands (`CREATE`/`DROP`/`ALTER` of an INDEX / CONSTRAINT / DATABASE / ALIAS / USER / ROLE — told apart from a data `CREATE (n:Label)` by the token after the verb) → DDL. Anything that can run server-side code or exfiltrate fails closed with HTTP 422: **`LOAD CSV`** (`error.neo4j.load_csv_forbidden`, the Cypher analogue of the MongoDB `$where` ban) and **`CALL <proc>`** outside a small read-only allow-list (`db.labels`, `db.relationshipTypes`, `db.propertyKeys`, `db.schema.visualization`, `db.schema.nodeTypeProperties`, `db.schema.relTypeProperties`); a `CALL { … }` subquery is allowed. `referencedTables` carries every node **label** and **relationship type** the statement touches (lowercased) for the host's allow-list and routing globs.

**Row security is MATCH-scoped splice; masking is label-aware.** `Neo4jRowSecurityApplier` translates each `RowSecurityDirective` on a node label into a property predicate `var.prop <op> $af_rls_n` (Cypher **named parameters**, never concatenated; `=, <>, <, <=, >, >=, IN, NOT IN`) ANDed onto the `WHERE` scoped to each `MATCH` / `OPTIONAL MATCH` clause that binds a variable of that label — extending an existing `WHERE` or inserting one before the next clause. The same splice governs reads, `SET` updates, and `DELETE`s (all select through a `MATCH`). It **fails closed** with `UnrewritableRowSecurityException` → 422 (`error.row_security_neo4j_unrewritable`) on any shape it cannot provably filter: a policied label that appears only anonymously (`(:Label)`), only inside a `WHERE` predicate / pattern comprehension (no clause-level MATCH binding), or under a scalar operator with no value. A statement that `CREATE`s or `MERGE`s a policied label is rejected (`error.row_security_neo4j_insert_unsupported`) — a write cannot be filtered into existence — mirroring the INSERT-into-policied rejection elsewhere. **Field masking** applies post-fetch via the **shared** `ColumnMasker`: a `Label.property` directive redacts the `property` of any returned node/relationship whose labels include `Label` (however it is aliased), and a bare `property` directive redacts that property anywhere it appears (nested maps/lists) and any top-level scalar column of that name — erring toward masking.

**Execution & connections.** `Neo4jDriverManager` caches one native `Driver` per datasource (the driver pools and routes Bolt connections internally), dropped via the same `evictDatasource` fan-out. A statement runs in a session scoped to `database_name` with the host-computed statement timeout (`TransactionConfig.withTimeout`); SELECTs collect up to `maxRows + 1` records (truncation detection), and each value is flattened to JSON-friendly form by `Neo4jValueConverter` (nodes → maps with `_elementId`/`_labels` + properties, relationships → maps with `_type` + endpoints, paths → lists, scalars verbatim, temporal/spatial stringified, bytes → `base64:…`). Writes report the sum of the Bolt summary's node/relationship/property mutation counters; DDL returns 0. Tuning via `accessflow.proxy.engines.neo4j.*`: `connect-timeout` (PT10S), `max-connection-pool-size` (100).

**Connection test + introspection.** `testConnection` opens a short-lived driver, calls `verifyConnectivity()`, and runs `RETURN 1` against the target database — the Cypher `SELECT 1`. `introspectSchema` calls the server's own `db.schema.nodeTypeProperties()` / `db.schema.relTypeProperties()` (both in the read-only allow-list): each node label becomes a table, its sampled property keys the columns, a synthetic `_elementId` column the primary key (Neo4j's node identity); relationship types are surfaced as additional tables so the allow-list and ER diagram see the whole graph shape. One schema named for the database. Graph schema has no foreign keys. A CodeMirror Cypher highlighting pack is a frontend follow-up (the editor currently uses JS-adjacent highlighting).

### Snowflake engine

`db_type=SNOWFLAKE` datasources (AF-629) are the eighth engine-plugin connector and the first cloud **data-warehouse** (`category=WAREHOUSE`) one — a SQL-dialect engine that is nonetheless engine-managed, because Snowflake's connection and auth model (account host, key-pair JWT, billed warehouse sessions) does not fit the pooled JDBC host/port/username/password lane. Everything Snowflake-specific lives in the **`engines/snowflake/` plugin** (artifact `accessflow-engine-snowflake`, own version line, reproducible shaded JAR whose shade whitelist is just `net.snowflake:snowflake-jdbc` — the driver is itself a self-contained fat jar with internally relocated dependencies; pinned in `connectors/snowflake/connector.json`).

**Connection model.** `host` is the account host (`<account>.snowflakecomputing.com`), `database_name` the database, `username` the user; the decrypted credential column holds either a **password** or an **unencrypted PKCS#8 private-key PEM** (detected by the `-----BEGIN PRIVATE KEY` prefix and parsed into a `PrivateKey` for the driver's key-pair JWT auth; encrypted PEMs are rejected with a dedicated message). An optional `jdbc_url_override` supplies a full `jdbc:snowflake://` URL carrying warehouse / role / schema parameters. The engine instantiates the Snowflake driver **directly** (never `DriverManager`, which is unusable across the plugin's isolated classloader) and opens a **short-lived connection per request — no pool**: warehouse sessions are billed while a warehouse is resumed, governance traffic is sparse, and per-request connections also rule out session-state leakage (`USE` is rejected anyway). `DatasourceAdminServiceImpl` enforces the shape with a dedicated SNOWFLAKE branch in `validateDriverChoice` (require host + database; port unused; override allowed).

**Query language.** `SnowflakeQueryParser` is a keyword classifier over a tokenizer that understands Snowflake quoting (`'…'`, `"…"` identifiers, `$$…$$` blocks) and comments: SELECT / INSERT / UPDATE / DELETE / MERGE (→ UPDATE) / table-view-schema DDL + TRUNCATE are accepted; `CALL`, `EXECUTE IMMEDIATE`, scripting blocks (`BEGIN`/`DECLARE`), `PUT`/`GET`, `COPY INTO`, `USE`, `SHOW`, `DESCRIBE`, `GRANT`/`REVOKE`, and procedure/function/task/stream/pipe/stage DDL are rejected with distinct 422 messages, as are multi-statement input and user-supplied `?` placeholders (binds are reserved for row security).

**Row security is WHERE-splice; masking is shared.** Matching directives are ANDed into the `WHERE` clause (or a `WHERE` is inserted before `GROUP BY` / `HAVING` / `QUALIFY` / `ORDER BY` / `LIMIT`) with **positional JDBC parameters**; CTEs, subqueries, JOINs, set operators, MERGE, and multi-table shapes **fail closed** (`error.row_security_snowflake_unrewritable`), INSERT into a policied table is rejected, and an empty directive value list is a deny-all the executor short-circuits without touching Snowflake. Field masking applies post-fetch via the shared `ColumnMasker` on flat result columns; restricted columns without a directive collapse to the full mask.

**Execution, connection test + introspection.** `setMaxRows(maxRows + 1)` detects truncation and `setQueryTimeout` enforces the host-computed statement timeout; writes report `executeUpdate` counts and DDL returns 0. **Dry-run (AF-634):** `dryRun` plans the governed statement (row security spliced, positional binds) via an engine-synthesized `EXPLAIN USING TABULAR` prefix — compiled by Snowflake, never executed — over the same per-request connection path; `SnowflakeExplainPlanMapper` reads the tabular output defensively by column label (release drift degrades, never errors), mapping the aggregate `GlobalStats` row's post-pruning `bytesAssigned` to `estimatedBytesScanned` and the operator rows (partitions assigned/total, bytes, expressions) to the `QueryPlanNode` tree; DDL degrades to *unsupported* and deny-all short-circuits to a 0-row/0-byte estimate with zero Snowflake calls. `testConnection` runs `SELECT 1`; `introspectSchema` walks `DatabaseMetaData` scoped to the datasource database (`INFORMATION_SCHEMA` excluded, primary keys flagged). Tuning via `accessflow.proxy.engines.snowflake.*`: `login-timeout` (PT30S), `network-timeout` (PT60S). Test bar deviation: there is no free Snowflake emulator (LocalStack Snowflake is paid), so the plugin ships unit tests + mocked-JDBC facade tests + the shaded-jar ServiceLoader IT, without a live-server IT — documented in `engines/snowflake/README.md`.

### BigQuery engine

`db_type=BIGQUERY` datasources (AF-629) are the second `WAREHOUSE` engine, built on the **`engines/bigquery/` plugin** (artifact `accessflow-engine-bigquery`, own version line, reproducible shaded JAR bundling the `google-cloud-bigquery` HTTP/JSON client with the **entire third-party tree relocated** under `…engine.bigquery.shaded.*` — Google client/auth/HTTP, Guava, Jackson, OpenCensus/OpenTelemetry, threeten; no gRPC/Netty; pinned in `connectors/bigquery/connector.json`).

**Connection model.** Like DynamoDB, the "connection" is cloud credentials, not host/port: `database_name` holds the GCP **project id** — optionally `project.dataset` to pin a default dataset (project and dataset ids cannot contain dots, so the split is unambiguous) — and the encrypted credential column holds the **service-account key JSON** (`ServiceAccountCredentials.fromStream`). `jdbc_url_override` optionally points at a custom endpoint (the `goccy/bigquery-emulator`), in which case the client uses `NoCredentials`. Host/port/username are unused; a dedicated BIGQUERY branch in `validateDriverChoice` enforces the `project[.dataset]` shape. One cheap `BigQuery` client stub is cached per datasource and dropped via the standard `evictDatasource` fan-out.

**Query language.** `BigQueryQueryParser` classifies GoogleSQL with a tokenizer that understands both string quote styles, triple-quoted strings, and backtick identifiers (including a single backtick spanning `project.dataset.table`): SELECT / INSERT / UPDATE / DELETE / MERGE (→ UPDATE) / table-view-schema DDL + TRUNCATE are accepted; scripting (`BEGIN`, `DECLARE`, `CALL`, `EXECUTE IMMEDIATE`, `IF`/`LOOP`/`WHILE`), `EXPORT DATA`, `LOAD DATA`, `ASSERT`, `GRANT`/`REVOKE`, and procedure/function/index/model DDL are rejected with 422 (the JSON-tree analogue of the `$where` ban), as are multi-statement input and user-supplied `?` / `@name` parameter markers.

**Row security is WHERE-splice; masking is dot-path recursive.** Same fail-closed WHERE-splice as Snowflake, bound as **positional query parameters** on the `QueryJobConfiguration` (deny-all short-circuits without an API call; INSERT-into-policied rejected). Masking applies post-fetch via the shared `ColumnMasker` **recursively by dot-path** over `RECORD` fields (a `user.email` directive redacts the nested leaf; a whole-column FULL mask collapses maps/lists), mirroring the DynamoDB `MaskPlanner`.

**Execution, connection test + introspection.** Queries run as BigQuery jobs bounded by the host-computed timeout (cancel + `QueryExecutionTimeoutException` on deadline); reads page up to `maxRows + 1` rows for truncation detection; DML row counts come from the job's `QueryStatistics.getNumDmlAffectedRows()`; DDL returns 0. `introspectSchema` lists datasets (or only the pinned default dataset) → tables → schemas, flattening `RECORD` fields to dot-path columns; BigQuery has no primary keys. Tuning via `accessflow.proxy.engines.bigquery.*`: `connect-timeout` (PT10S), `read-timeout` (PT30S). The plugin's Testcontainers IT drives the full SPI against the `ghcr.io/goccy/bigquery-emulator` image.

### Databricks engine

`db_type=DATABRICKS` datasources (AF-629) are the third `WAREHOUSE` engine and the first with **no vendor driver or SDK at all**: the **`engines/databricks/` plugin** (artifact `accessflow-engine-databricks`, own version line, reproducible shaded JAR whose only bundled dependency is a **relocated Jackson**; pinned in `connectors/databricks/connector.json`) talks to the [SQL Statement Execution API](https://docs.databricks.com/api/workspace/statementexecution) with the JDK `java.net.http` client.

**Connection model.** `host` is the workspace host, the encrypted credential column holds a **personal access token** (`Authorization: Bearer`), and `jdbc_url_override` — **required**, the one non-CUSTOM dialect where it is — carries the SQL warehouse HTTP path (`/sql/1.0/warehouses/<id>`, or a full `https://…` URL whose authority then overrides the host; the last path segment is the `warehouse_id` the API needs). `database_name` optionally selects a Unity Catalog catalog. A dedicated DATABRICKS branch in `validateDriverChoice` enforces host + warehouse path. The engine holds one shared `HttpClient`; there is no per-datasource state to evict.

**Query language.** `DatabricksQueryParser` classifies Databricks SQL (Spark SQL family; backtick identifiers): SELECT / INSERT (INTO/OVERWRITE) / UPDATE / DELETE / MERGE (→ UPDATE) / table-view-schema DDL + TRUNCATE are accepted; `USE`, `SET`, `CACHE`/`UNCACHE`, `COPY INTO`, `CALL`, `MSCK`, `ANALYZE`, **`OPTIMIZE`/`VACUUM`** (maintenance ops are not governable queries), `REFRESH`, `DESCRIBE`/`SHOW`/`EXPLAIN`, `GRANT`/`REVOKE`, scripting, and function/volume/catalog/share DDL are rejected with 422, as are multi-statement input and user-supplied `?` / `:name` parameter markers (the `::` cast shorthand is not a marker).

**Row security is WHERE-splice with named parameters.** Same fail-closed splice shape (CTE / subquery / JOIN / set-op / LATERAL VIEW / multi-table → `error.row_security_databricks_unrewritable`; INSERT-into-policied rejected; empty value list → deny-all with **zero** HTTP calls), bound as the API's **named parameters** using a collision-resistant `:afp_n` prefix (a statement already containing the literal `:afp_` fails closed). Masking applies post-fetch via the shared `ColumnMasker` on flat manifest columns.

**Execution, connection test + introspection.** A statement is submitted with `wait_timeout`/`on_wait_timeout=CONTINUE`, `format=JSON_ARRAY`, `disposition=INLINE`, and `row_limit=maxRows+1`, then polled until a terminal state with the poll loop bounded by the host-computed statement timeout (`context.clock()`); on deadline the engine issues a best-effort cancel and raises the timeout error. Chunked INLINE results are followed via `next_chunk_index`; `EXTERNAL_LINKS` is out of scope for v1 (oversized-result API errors surface verbatim). DML row counts come from the API's `num_affected_rows` result when present. **Dry-run (AF-634):** `dryRun` submits the governed statement (row security spliced, named `:afp_n` parameters riding along) behind an engine-synthesized `EXPLAIN COST` prefix through the same submit/poll path — planned by Catalyst, never executed, no `row_limit`; the single string-column result becomes `rawPlan` (no structured node tree — the text grammar is not contractual) and the never-throwing `DatabricksExplainCostParser` extracts the optimized logical plan's top-level `Statistics` (`sizeInBytes` with binary-unit suffixes normalized to raw bytes → `estimatedBytesScanned`, `rowCount` → `estimatedRows`; drift degrades to nulls). DDL degrades to *unsupported*; deny-all short-circuits to a 0-row/0-byte estimate with zero HTTP calls. `testConnection` runs `SELECT 1`; `introspectSchema` queries `information_schema.tables`/`columns` through the same API, scoped to the catalog when set. Tuning via `accessflow.proxy.engines.databricks.*`: `connect-timeout` (PT10S), `wait-timeout` (PT10S, clamped to the API's 5–50 s), `poll-interval` (PT1S). The plugin's IT drives the full SPI against an in-process `com.sun.net.httpserver` stub of the API (submit / poll / cancel / chunking / failure shapes) — no container needed.

### Dynamic JDBC Driver Loading

Customer-database JDBC drivers are **not** bundled in the Spring Boot fat JAR. They are resolved per `DbType` on demand the first time a datasource of that type is used (via `POST /datasources` or its first `POST /datasources/{id}/test`). Only `org.postgresql:postgresql` ships baked in — used for AccessFlow's own internal database.

**Connector catalog.** The supported databases are described declaratively by the repo-root
[`connectors/`](../connectors/) folder (one `connector.json` manifest per connector), bundled onto
the classpath and loaded at startup by `proxy/internal/driver/ConnectorCatalog` — this replaced the
formerly-hardcoded `DriverRegistry`. Each manifest maps a connector to `{dbType, displayName, logo,
defaultPort, defaultSslMode, jdbcUrlTemplate, driverClassName}` plus a `driver` descriptor (Maven
coordinates or a direct URL + pinned SHA-256). The five dialect connectors map to first-class
`DbType` values; additional engines (e.g. ClickHouse) carry `dbType=CUSTOM`. Built-in connectors:

| Connector | DbType | Maven coordinates | Notes |
|-----------|--------|-------------------|-------|
| `postgresql` | `POSTGRESQL` | `org.postgresql:postgresql` | Bundled; already on classpath |
| `mysql` | `MYSQL` | `com.mysql:mysql-connector-j` | |
| `mariadb` | `MARIADB` | `org.mariadb.jdbc:mariadb-java-client` | |
| `oracle` | `ORACLE` | `com.oracle.database.jdbc:ojdbc11` | Oracle license terms apply |
| `mssql` | `MSSQL` | `com.microsoft.sqlserver:mssql-jdbc` | |
| `clickhouse` | `CUSTOM` | `com.clickhouse:clickhouse-jdbc:all` | New engine via the CUSTOM lane |
| `mongodb` | `MONGODB` | — (`url` artifact: `accessflow-engine-mongodb-<v>-all.jar`) | NoSQL engine plugin (AF-414), not a JDBC driver |
| `couchbase` | `COUCHBASE` | — (`url` artifact: `accessflow-engine-couchbase-<v>-all.jar`) | NoSQL engine plugin (AF-412), not a JDBC driver |
| `redis` | `REDIS` | — (`url` artifact: `accessflow-engine-redis-<v>-all.jar`) | NoSQL key-value engine plugin (AF-419), not a JDBC driver |
| `dynamodb` | `DYNAMODB` | — (`url` artifact: `accessflow-engine-dynamodb-<v>-all.jar`) | NoSQL key-value engine plugin (AF-422), not a JDBC driver; connection is cloud credentials + region |
| `cassandra` | `CASSANDRA` | — (`url` artifact: `accessflow-engine-cassandra-<v>-all.jar`) | NoSQL wide-column engine plugin (AF-421), not a JDBC driver |
| `scylladb` | `SCYLLADB` | — (the **same** cassandra plugin JAR) | Second `QueryEngine` provider in the cassandra JAR |
| `elasticsearch` | `ELASTICSEARCH` | — (`url` artifact: `accessflow-engine-elasticsearch-<v>-all.jar`) | NoSQL search engine plugin (AF-420), not a JDBC driver |
| `opensearch` | `OPENSEARCH` | — (the **same** elasticsearch plugin JAR) | Second `QueryEngine` provider in the elasticsearch JAR |
| `neo4j` | `NEO4J` | — (`url` artifact: `accessflow-engine-neo4j-<v>-all.jar`) | NoSQL graph engine plugin (AF-423), not a JDBC driver |
| `snowflake` | `SNOWFLAKE` | — (`url` artifact: `accessflow-engine-snowflake-<v>-all.jar`) | Warehouse engine plugin (AF-629); bundles the Snowflake JDBC driver but is engine-managed (per-request connections, key-pair auth) |
| `bigquery` | `BIGQUERY` | — (`url` artifact: `accessflow-engine-bigquery-<v>-all.jar`) | Warehouse engine plugin (AF-629); connection is a service-account JSON + GCP project |
| `databricks` | `DATABRICKS` | — (`url` artifact: `accessflow-engine-databricks-<v>-all.jar`) | Warehouse engine plugin (AF-629); REST Statement Execution API with a PAT |

Versions and SHA-256 checksums are pinned in the manifests and verified after every download. The
API will not accept arbitrary GAVs from callers — only catalog connectors are resolvable. See
[14-connectors.md](./14-connectors.md) for the manifest format and the install marketplace.

**Engine plugins** ride the same pipeline: connectors with a non-RELATIONAL `category` pin a shaded
`core.api.QueryEngine` plugin JAR instead of a JDBC driver; `DefaultQueryEngineCatalog` shares the
`DriverJarCache` (same cache dir, same offline policy, same checksum check) and discovers the engine
via `ServiceLoader` — see the "MongoDB engine" section above.

**Resolution flow.** On first datasource of a given `db_type`:
1. Check local cache directory `${ACCESSFLOW_DRIVER_CACHE:-/var/lib/accessflow/drivers}` for a JAR matching `{artifactId}-{version}.jar`.
2. If absent, download from `${ACCESSFLOW_DRIVERS_REPOSITORY_URL:-https://repo1.maven.org/maven2}` over HTTPS.
3. Verify SHA-256 against the registry entry. Mismatch → discard and fail closed.
4. Load into a child `URLClassLoader` scoped to that `DbType`.
5. Register with `DriverManager` via a delegating `Driver` shim so the Hikari-side `getConnection(url, props)` resolves correctly across classloaders.

Any failure in this flow bubbles as `DriverResolutionException` and surfaces on the `POST /datasources` response as HTTP 422 `DATASOURCE_DRIVER_UNAVAILABLE` (see `docs/04-api-spec.md`).

**HikariCP integration.** The resolved `Driver` instance is passed to Hikari via `setDriverClassName` together with the dedicated `URLClassLoader` (`setClassLoader`). Pool creation is otherwise unchanged.

**Configuration.**

| Variable | Purpose |
|----------|---------|
| `ACCESSFLOW_DRIVER_CACHE` | Filesystem path for cached driver JARs. Default `/var/lib/accessflow/drivers`. Mount this as a persistent volume in production. |
| `ACCESSFLOW_DRIVERS_REPOSITORY_URL` | Maven repository base URL. Default `https://repo1.maven.org/maven2`. Override for internal Nexus / Artifactory mirrors. |
| `ACCESSFLOW_DRIVERS_OFFLINE` | Boolean. When `true`, no network resolution is attempted; only the cache is consulted. For air-gapped installs the operator pre-populates the cache. |

**Security posture.**
- Allowlist only — registry entries cannot be extended via API.
- Mandatory SHA-256 verification; HTTPS-only downloads.
- Each driver lives in its own classloader; no driver code can reach beans outside the proxy engine.
- The cache directory is opened read-only by the JVM after the initial write completes.

**Operational notes.** First datasource of a never-yet-resolved type incurs a one-time download latency of roughly 1–5 s depending on driver size and network. The wizard's "test connection" step (see `docs/06-frontend.md` → DatasourceCreateWizardPage) surfaces this so admins are not surprised by a longer first call. The 5 s login timeout on `POST /datasources/{id}/test` does **not** include driver download time.

#### Admin-uploaded drivers (#94 / #142)

The bundled registry covers the five canonical engines. For everything else — community
driver forks, vendor builds, paywalled JDBC drivers, or entirely new database types — admins
upload the JAR directly via `POST /datasources/drivers` (multipart, see `docs/04-api-spec.md`).
The same primitive backs **two consumption patterns**:

1. **Override** — an uploaded driver whose `target_db_type` is one of the bundled five wins
   over the static registry **for any datasource that references it via `custom_driver_id`**.
   Other datasources of the same `db_type` continue using the bundled driver. Useful for
   running a different MariaDB driver version per datasource without org-wide side effects.
2. **Dynamic datasource** — when `target_db_type=CUSTOM`, the upload backs a `db_type=CUSTOM`
   datasource with a free-form `jdbc_url_override`. No `host`/`port`/`database_name` is stored.

**Per-driver classloader.** `DefaultDriverCatalogService` caches resolved drivers in three maps:
`Map<DbType, ResolvedDriver>` for the dialect connectors, `Map<String, ResolvedDriver>` keyed by
connector id for `CUSTOM`-dialect catalog connectors (classloader `accessflow-jdbc-connector-{id}`),
and `Map<UUID, ResolvedDriver>` keyed by `custom_jdbc_driver.id` for uploads (classloader
`accessflow-jdbc-custom-{driverId}`). `DatasourcePoolFactory` picks the lane by descriptor:
`custom_driver_id` → uploaded, else `connector_id` → catalog connector, else `db_type` → dialect.
Each driver gets its own `URLClassLoader`, so two datasources referencing different drivers — even
if both target ORACLE — load disjoint copies and cannot interfere via static state.

**Upload validation flow** (`DefaultCustomJdbcDriverService.register`):
1. Look up `(organization_id, expected_sha256)` to reject duplicates with `CUSTOM_DRIVER_DUPLICATE`.
2. Stream the upload through `CustomDriverStorage.store(...)`, computing SHA-256 inline. If the
   computed digest doesn't match `expected_sha256`, delete the temp file and throw
   `CustomDriverChecksumMismatchException`.
3. Probe-load `driver_class` in a throwaway `URLClassLoader`. The class must exist in the JAR
   and implement `java.sql.Driver`; otherwise delete the stored JAR and throw
   `CustomDriverInvalidJarException`.
4. Persist the `custom_jdbc_driver` row and publish `CustomJdbcDriverRegisteredEvent`.

**Storage layout.** JARs live at `${ACCESSFLOW_DRIVER_CACHE}/custom/{org_id}/{driver_id}.jar`,
alongside the bundled-driver cache. JARs are not encrypted — SHA-256 + admin-only RBAC are the
trust anchors. Every `resolveCustom(...)` call re-verifies SHA-256 against the persisted
descriptor before instantiating the classloader, so on-disk tampering is detected immediately.

**Pool factory branching.** `DatasourcePoolFactory.createPool` checks the descriptor:
- If `customDriverId` is set: load via `customJdbcDriverService.findById(...)` →
  `driverCatalog.resolveCustom(...)`. The thread-context classloader swap uses the per-driver
  loader.
- Else: existing bundled path.
- JDBC URL: if `jdbcUrlOverride` is non-blank, use it verbatim; else build via
  `JdbcCoordinatesFactory`.

**Deletion.** Removing an uploaded driver evicts its cached classloader and deletes the JAR
file, but the DB foreign-key constraint (`ON DELETE RESTRICT`) refuses deletion while any
datasource still references it — the service translates the violation into
`409 CUSTOM_DRIVER_IN_USE` with a `referencedBy` list.

**Multipart limits.** `spring.servlet.multipart.max-file-size=50MB` / `max-request-size=51MB`
in `application.yml`. The storage layer also enforces a 50 MB cap as a second line of defence;
exceeding it streams returns `413 CUSTOM_DRIVER_TOO_LARGE`.

### SQL Injection Prevention

- JSqlParser validates all SQL before any execution path.
- Proxy uses `PreparedStatement` exclusively — no string interpolation.
- Schema/table allow-listing validated at the AST level (not string matching).
- DDL blocked by default; requires explicit `can_ddl=true` permission.

### Schema / table allow-list enforcement

`SqlParserService` populates `SqlParseResult.referencedTables` by walking each parsed `Statement` with JSqlParser's `TablesNamesFinder`. CTE aliases are excluded automatically. Single-statement input yields the table set for that statement; a `BEGIN; …; COMMIT;` envelope yields the union across every inner statement. JSqlParser returns fully-qualified names via `Table.getFullyQualifiedName()` — `schema.table` when the writer qualified it, `table` when they didn't. The parser strips ASCII identifier quotes (`"`, `` ` ``, `[`, `]`) and ASCII-lowercases the result so admin-typed allow-list entries match user SQL regardless of quoting style.

`DefaultQuerySubmissionService.verifyAllowedTables(...)` runs after the `can_read` / `can_write` / `can_ddl` capability check. The decision is:

- If both `allowed_schemas` and `allowed_tables` are null or empty → no check (status quo "all tables permitted").
- Otherwise, for each normalised referenced table `T`:
  - allow if `T` appears verbatim in `allowed_tables`;
  - allow if `T` is `schema.table` and `schema` appears in `allowed_schemas`;
  - otherwise reject.
- Rejection throws `AccessDeniedException` → HTTP 403 (`error: FORBIDDEN`) and emits a `WARN` log with the rejected table list, the user id, and the datasource id. The localised detail uses the `error.permission.table_not_allowed` message bundle key.

Edge cases:

- **Unqualified references** (`SELECT * FROM users`) match `allowed_tables` only when the bare table name is listed without a schema prefix. An admin who set `allowed_schemas=['public']` must either add the unqualified name to `allowed_tables` or require fully-qualified SQL. The parser cannot know PostgreSQL's runtime `search_path`, so the conservative reject-by-default keeps the gate predictable.
- **Quoted mixed-case identifiers** (`"Public"."Users"`) are lowercased — case-insensitive matching is v1.0's behaviour across the board.
- **Admins** (`SubmissionInput.isAdmin=true`) bypass the entire permission lookup, including the allow-list check.

### Group-based access grants (AF-530)

`DatasourceUserPermissionLookupService.findFor(userId, datasourceId)` returns the caller's **effective**
permission: the most-permissive union of their direct `datasource_user_permissions` row and every
unexpired `datasource_group_permissions` grant for a group they belong to (group ids via
`UserGroupMembershipRepository.findGroupIdsForUser`). Booleans OR; `allowed_schemas`/`allowed_tables`
merge to their union (any contributor with no allow-list ⇒ all allowed); `restricted_columns` merges to
the **intersection** (a column is masked only when every contributing grant masks it); expired grants
contribute nothing. Because `findFor` is the single choke-point every enforcement path already reads
through (proxy dry-run/sample-data, `access` materialiser, AI analyzer, text-to-SQL, workflow
submission/lifecycle/break-glass, `requestgroups`), group grants are honoured everywhere without touching
those call sites. `findBreakGlassEligible` unions group break-glass grants the same way. JIT-access
materialisation, which manages the per-user row specifically, uses the sibling `findDirectFor` (direct
grant only, ignoring group grants). **Datasource visibility** — the list the Query Editor renders
(`GET /datasources` → `findAllVisibleToUser`), the single-datasource fetch (`GET /datasources/{id}` →
`getForUser`), and schema introspection for autocomplete (`GET /datasources/{id}/schema`) — does **not**
route through `findFor`; it is a separate `DatasourceRepository` query. That query mirrors the same rule:
a datasource is visible when the caller holds an unexpired direct grant **or** an unexpired
`datasource_group_permissions` grant for a group they belong to (AF-558). Before AF-558 it matched only
direct grants, so group-only members saw an empty picker even though every enforcement path would have
let them query. On the connector side the analogous union lives in
`EffectiveApiConnectorPermissionResolver` (see the API Access Governance section), which every scattered
connector enforcement point routes through — including the **API-editor visibility** paths
(`GET /api-connectors` → `listForUser`, `GET /api-connectors/{id}` → `getForUser`,
`GET /api-connectors/{id}/operations`), so connector list/get already honoured group grants and never
had the datasource gap. **Grouped requests** (`requestgroups`) validate every member at both draft-persist
and submit time through the group-aware `DatasourceUserPermissionLookupService.findFor` (query members)
and `ApiConnectorPermissionLookupService.findFor` (API members), so a group-only grant is sufficient to
add and run a member. Admins are granted/revoked group access via
`DatasourceAdminService.{grant,list,revoke}GroupPermission` and the `/permissions/groups` endpoints,
audited as `PERMISSION_GROUP_GRANTED` / `PERMISSION_GROUP_REVOKED` (connector side:
`API_PERMISSION_GROUP_GRANTED` / `API_PERMISSION_GROUP_REVOKED`).

### Column-level masking

When a `(user_id, datasource_id)` permission row carries `restricted_columns` (a `TEXT[]` of fully-qualified `schema.table.column` strings), SELECT result values for those columns are masked **before** rows are added to the in-memory result list — and therefore before they are serialised into `query_request_results.rows`. The raw sensitive value never lands in our database.

- Wiring: `DefaultQueryLifecycleService.execute(...)` resolves `restrictedColumns` via `DatasourceUserPermissionLookupService`, threads them into `QueryExecutionRequest`, which `DefaultQueryExecutor` forwards to `JdbcResultRowMapper.materialize(...)`.
- Matching uses `ColumnMaskResolver`, which inspects each column's `ResultSetMetaData` and applies (in priority order):
  1. Exact `schema.table.column` match (case-insensitive) when the JDBC driver populates both `getSchemaName(i)` and `getTableName(i)`.
  2. `table.column` fallback when only the table name is available.
  3. Bare `column` fallback for computed expressions, aliased outputs, and other cases where the driver omits table metadata. This errs toward over-masking, which is the secure default.
- Sentinel: a restricted cell with no policy is replaced with the literal string `"***"` (strategy `FULL`). `null` values stay `null`.
- Each `ResultColumn` returned from `materialize(...)` carries a `restricted` boolean so the API response (and the persisted `columns` JSON in `query_request_results`) tells the frontend which headers should render a "masked" marker.
- Write statements (INSERT / UPDATE / DELETE) have no result set to mask. Restrictions still surface in the AI prompt (see below) — informational only.

### Dynamic data masking policies (AF-381)

`masking_policy` rows (see [docs/03-data-model.md](03-data-model.md)) layer **per-column masking
strategies** with **conditional reveal** on top of the static `restricted_columns` masking above. This
governs *how* a visible value is rendered — distinct from column-permission enforcement (which governs
*whether* a column is accessible).

- Resolution: `DefaultQueryLifecycleService.doExecute(...)` calls `MaskingPolicyResolutionService.resolveApplicable(organizationId, datasourceId, submitterUserId)` (`core` module). It loads enabled policies for the datasource, looks up the submitter's role (user repo) and group ids (`UserGroupMembershipRepository.findGroupIdsForUser`), and returns one `ResolvedColumnMask` **per policy that applies** — i.e. the submitter is *not* revealed. Reveal is explicit only: a submitter sees the unmasked value when their role ∈ `reveal_to_roles`, their user id ∈ `reveal_to_user_ids`, or any of their group ids ∈ `reveal_to_group_ids`. There is no implicit ADMIN bypass.
- The resolved masks are mapped to `proxy.api.ColumnMaskDirective` and threaded through `QueryExecutionRequest.columnMasks` alongside `restrictedColumns`. `ColumnMaskResolver.build(...)` combines both: an explicit policy directive **wins** over the `FULL` default a bare `restricted_columns` entry would apply; among multiple matching directives the most specific level wins.
- Strategy application is the pure `ColumnMasker.apply(strategy, rawValue, params)` (`proxy.internal`): `FULL` → `***` (never reads the raw value), `PARTIAL` → keep the last N chars (`visible_suffix`, default 4; values no longer than the window mask fully), `HASH` → stable SHA-256 hex of the UTF-8 value, `EMAIL` → `j***@domain` (non-email falls back to `FULL`), `FORMAT_PRESERVING` → digits→`*`, letters→`x`, separators preserved.
- `materialize(...)` returns the set of **applied** policy ids on `SelectExecutionResult.appliedMaskingPolicyIds` (a policy that matched a result column for a non-revealed submitter). The lifecycle service records them in the `QUERY_EXECUTED` audit metadata under `applied_masking_policy_ids`. Unmasked values are never logged or stored.
- Backward compatible: a `restricted_columns` entry with no covering policy keeps today's `"***"` behaviour.

### Row-level security policies (AF-380)

`row_security_policy` rows (see [docs/03-data-model.md](03-data-model.md)) inject **per-table row
predicates** into the parsed SQL so a scoped submitter only **sees** (SELECT) or **affects**
(UPDATE/DELETE) authorised rows. This governs *which rows* are returned/affected — orthogonal to
masking (*how* a value is rendered) and the schema/table allow-list (*whether* a table is reachable).
All three compose: the allow-list is checked at submission, then masking + row-security apply at
execution.

- **Resolution** (`core` module): `DefaultQueryLifecycleService.doExecute(...)` calls
  `RowSecurityResolutionService.resolveApplicable(organizationId, datasourceId, submitterUserId)`. It
  loads enabled policies for the datasource, filters by `applies_to` targeting (empty scope = applies
  to everyone; non-empty narrows by role / group / user id — **no implicit ADMIN bypass**), and
  resolves each policy's `value_expression` to concrete bound value(s): built-ins `user.id` /
  `user.email` / `user.role` / `user.groups` (group names), or a key from the submitter's
  `users.attributes`. A `LITERAL` is used as-is. An **unresolvable** variable (missing attribute, or
  `user.groups` for a user in no groups) returns an empty value list — the fail-closed deny signal.
  Each applicable policy becomes a `proxy.api.RowSecurityDirective` threaded through
  `QueryExecutionRequest.rowSecurityPredicates`.
- **Rewrite** (`proxy.internal.RowSecurityRewriter`): re-parses the statement with JSqlParser and, for
  each top-level FROM/JOIN reference to a policied table, replaces the `Table` with a **security-barrier
  derived table** `(SELECT * FROM t WHERE <predicate>) t` (alias preserved, so self-joins each get
  their own barrier and bind). For UPDATE/DELETE the predicate is ANDed (qualified to the target) into
  the `WHERE` clause. Comparison values are bound as **JDBC parameters** (`?`) — never
  string-concatenated. Empty value lists / unresolvable variables emit an always-false `1=0`. The rewrite
  is a pure no-op when no directives apply (no re-parse, zero hot-path overhead).
- **Parameter binding** is the one place the proxy binds positional parameters. Because submitted SQL is
  fully literal, every `?` in the rewritten statement is one the rewriter injected; binds are collected
  in the same left-to-right traversal order JSqlParser deparses them (FROM before WHERE), so positional
  binding always aligns. `DefaultQueryExecutor` binds them via `setObject` before executing (single
  statement and each statement of a `BEGIN…COMMIT` batch — so DML cannot be wrapped to bypass the
  predicate).
- **Reject-to-422**: query shapes the rewriter cannot provably filter — a policied table inside a
  `UNION`/`INTERSECT`/`EXCEPT`, a CTE, a sub-select, an `INSERT … SELECT`, or an `UPDATE … FROM` /
  `DELETE … USING` join onto another policied table — raise `proxy.api.UnrewritableRowSecurityException`,
  mapped to **HTTP 422** (`error=ROW_SECURITY_UNREWRITABLE`) rather than run unfiltered. Because this is
  a client error the user can act on, `doExecute` **rethrows** it (and a parse-time `InvalidSqlException`)
  for an interactive execute so the controller returns 422; for a system-driven scheduled run there is no
  caller to surface to, so it is recorded as a `FAILED` execution instead of looping forever.
- `SelectExecutionResult` / `UpdateExecutionResult` carry `appliedRowSecurityPolicyIds`; the lifecycle
  service records them in the `QUERY_EXECUTED` audit metadata under `applied_row_security_policy_ids`. No
  row data is stored.

### Schema introspection

`DatasourceAdminService.introspectSchema(...)` opens a one-shot JDBC connection (no Hikari pool reuse) to the customer database and walks `DatabaseMetaData`:

- `getTables(catalog, null, "%", ["TABLE"])` — enumerates user tables; system schemas (`pg_catalog`, `information_schema`, `pg_toast`, `mysql`, `performance_schema`, `sys`) are filtered out per dialect.
- `getPrimaryKeys(catalog, schema, table)` — populates the `primary_key` flag on each column.
- `getColumns(catalog, schema, table, "%")` — name, type, nullability.
- `getImportedKeys(catalog, schema, table)` — populates the per-table `foreignKeys` list (`fromColumn`, `toTable`, `toColumn`). Rows whose `PKTABLE_SCHEM` is in the system-schema set are skipped. Multi-column FKs are emitted as one record per column pair. Custom JDBC drivers that don't implement `getImportedKeys` log a `WARN` and return an empty list — the frontend's "ER diagram" tab renders the empty state in that case.

The result is returned via `DatabaseSchemaView` (immutable nested records: `Schema → Table → Column` + `ForeignKey`). The web layer maps to `DatabaseSchemaResponse` for the `GET /api/v1/datasources/{id}/schema` endpoint; the AI module consumes the same view via `SystemPromptRenderer.describeSchema(...)`.

### Sample data path (AF-443)

`proxy.api.SampleDataService` returns a bounded, fully-governed sample of a single table's rows for the schema-explorer UI — an **ad-hoc read that bypasses review but not governance**. It does *not* create a `query_request`; it resolves the caller's directives and runs through the executor exactly like `DefaultQueryLifecycleService.doExecute`:

1. **Authorization + allow-list.** `DefaultSampleDataService` calls `DatasourceAdminService.introspectSchema(...)` (which enforces org + permission-row access) and validates the requested `schema`/`table` against the returned `DatabaseSchemaView`. Non-ADMINs additionally need `can_read` and the target inside their `allowed_schemas`/`allowed_tables` (same normalization as `DefaultQuerySubmissionService.verifyAllowedTables`). A miss raises `TableNotFoundException` (HTTP 404) — existence is never leaked.
2. **Directive resolution.** Restricted columns (from the permission), `ColumnMaskDirective`s (`MaskingPolicyResolutionService`), and `RowSecurityDirective`s (`RowSecurityResolutionService`) are resolved for the caller.
3. **Execution.** `QueryExecutor.sampleTable(SampleTableRequest)` enforces the row cap (`maxRowsOverride` clamped to the datasource + global `ACCESSFLOW_PROXY_EXECUTION_MAX_ROWS`) and statement timeout, then:
   - **Relational** datasources: builds `SELECT * FROM <dialect-quoted, allow-listed identifier>` (via `IdentifierQuoter`, never raw input) and runs the existing JDBC path — `RowSecurityRewriter` injects RLS, `JdbcResultRowMapper` + `ColumnMasker` mask post-fetch, JDBC `setMaxRows` caps without a dialect-specific `LIMIT`.
   - **Engine-managed** (NoSQL) datasources: delegates to the engine's `QueryEngine.sampleTable(QueryEngineSampleRequest)` (see [Engine SDK](15-engine-sdk.md)), which issues its native "read all rows from this table, capped at N" and funnels it through the same parse → row-security → mask pipeline as `execute`. Mongo `find({}).limit(N)`, Couchbase/Cassandra/DynamoDB `SELECT * FROM <keyspace/table>`, Elasticsearch `match_all`, Neo4j `MATCH (n:Label) RETURN n`. **Redis fails closed** — a key-value prefix has no per-row security meaning, so any matching `RowSecurityDirective` denies with an empty result; otherwise it SCANs the prefix and fetches values, with field masking still applied.

The result is a `SelectExecutionResult` mapped to `SampleRowsResponse` for `GET /api/v1/datasources/{id}/sample-rows` — masked columns carry the masked value only.

### Dry-run / EXPLAIN path (AF-445)

`proxy.api.QueryDryRunService` returns a **non-committing execution plan + best-effort estimated row impact** for a query — the playground/sandbox a user reaches for before formal submission (`POST /api/v1/queries/dry-run`). Like the sample path it is an **ad-hoc read that bypasses review but not governance**, creates no `query_request`, and never mutates data — every engine plans the statement (relational `EXPLAIN`, Mongo `explain`, …) but never executes it.

1. **Authorization + allow-list.** `DefaultQueryDryRunService` resolves the datasource via `DatasourceAdminService.getForUser`/`getForAdmin` (org + permission-row access; 404 on miss), parses the query through `QueryParser` (`InvalidSqlException` → 422) for the `QueryType` + `referencedTables`, and — for non-ADMINs — verifies the matching capability (`can_read`/`can_write`/`can_ddl`) and that every referenced table is inside the caller's allow-list (same normalization as `DefaultQuerySubmissionService.verifyAllowedTables`; a miss raises Spring Security `AccessDeniedException` → 403).
2. **Directive resolution.** The caller's `RowSecurityDirective`s (`RowSecurityResolutionService`) are resolved so the plan reflects the **governed** query. Column masks are irrelevant to a plan (no rows are returned) and are omitted.
3. **Planning.** `QueryExecutor.dryRun(QueryExecutionRequest)` applies the `RowSecurityRewriter`, acquires a connection via `RoutingDataSourceResolver` (SELECT dry-runs prefer the read replica; writes plan on the primary — e.g. Oracle writes its scratch `PLAN_TABLE` there), and:
   - **Relational** datasources: a per-`DbType` `DryRunPlanner` (`proxy/internal/dryrun/`) runs the dialect's non-executing EXPLAIN — PostgreSQL `EXPLAIN (FORMAT JSON)`, MySQL/MariaDB `EXPLAIN FORMAT=JSON`, Oracle `EXPLAIN PLAN FOR` + `PLAN_TABLE` (rows deleted in a `finally`), SQL Server `SET SHOWPLAN_ALL ON` — and maps it to a `QueryPlanNode` tree. `CUSTOM` JDBC has no planner and degrades gracefully.
   - **Engine-managed** datasources: delegates to `QueryEngine.dryRun(QueryEngineDryRunRequest)` (default SPI method returns *unsupported*; overridden by MongoDB `explain` queryPlanner, Couchbase / Neo4j `EXPLAIN`, Elasticsearch/OpenSearch `_validate/query?explain`, Snowflake `EXPLAIN USING TABULAR` (GlobalStats `bytesAssigned` → `estimatedBytesScanned`, per-operator partition pruning in the plan tree — AF-634), Databricks `EXPLAIN COST` via the Statement Execution API (optimized-plan `Statistics` → `estimatedBytesScanned`/`estimatedRows`, rawPlan-only — AF-634), and BigQuery via a **native dry-run job** — `JobConfiguration.dryRun=true`, validated and estimated server-side without running, reporting `totalBytesProcessed` as `estimatedBytesScanned` (AF-634). The engine applies its own row-security splice to the planned statement; deny-all short-circuits to a 0-row/0-byte estimate with zero warehouse calls; DDL degrades to *unsupported*). Redis, Cassandra/ScyllaDB, and DynamoDB inherit the default and degrade gracefully.
4. **Graceful degradation.** A `QueryDryRunResult` with `supported=false` carries a localized `unsupportedReason` (`error.dry_run.unsupported`, resolved by the host service) — the engine has no plan concept, or the operation isn't explainable (INSERT/DDL on most engines).

`QueryDryRunResult.estimatedBytesScanned` (AF-634) carries a warehouse engine's native pre-flight scan estimate in raw bytes — the direct cost signal for bytes-billed warehouses — and is `null` for engines without one; it is serialized as `estimated_bytes_scanned` and rendered as its own line in the editor's dry-run panel. The statement-timeout cap reuses `ACCESSFLOW_PROXY_EXECUTION_STATEMENT_TIMEOUT`; there is no row cap (a dry-run returns no rows). The result is mapped to `QueryDryRunResponse` by the controller in the `security` module (which already depends on `proxy`, so it can host the `/queries/dry-run` endpoint and use `JwtClaims` without a module cycle — the same arrangement as the sample-rows endpoint).

### Automatic pre-flight cost estimate (AF-624)

`proxy.api.QueryCostEstimateService` (`DefaultQueryCostEstimateService`) turns the AF-445 dry-run machinery into an **automatic, persisted, per-submission blast-radius estimate**: right after a query is submitted, the engine's non-committing plan (estimated rows, root scan type, cost, plan tree) plus — for UPDATE/DELETE — a governed, non-mutating **exact affected-row count** are computed and stored as the query's single `query_estimates` row (see [docs/03-data-model.md → query_estimates](03-data-model.md#query_estimates)).

- **Two independent triggers, safe by idempotency.** `proxy.internal.QueryCostEstimateListener` consumes `QuerySubmittedEvent` (mirroring the AI module's `AiAnalysisListener`) and runs unconditionally — reviewers and routing want the estimate regardless of `ai_analysis_enabled`. Independently, `DefaultAiAnalyzerService.analyzeSubmittedQuery` calls `estimateSubmittedQuery(id)` itself before building its prompt, so the estimate is deterministically available for `{{cost_estimate}}` no matter which trigger wins the race. The service fast-paths on an existing row and `DefaultQueryEstimateService.persist` is insert-once, so the race is harmless.
- **Computation.** The submitter's row-security directives are resolved (`RowSecurityResolutionService`) so the plan and count reflect the *governed* statement, then `QueryExecutor.dryRun` runs the existing per-dialect EXPLAIN / engine `dryRun` path. For UPDATE/DELETE, `QueryExecutor.countAffectedRows` additionally computes the exact count: **relational** datasources rewrite the parsed single-table statement into `SELECT COUNT(*) FROM <target> [WHERE …]` (`proxy.internal.dryrun.AffectedRowCounter` — join / `UPDATE … FROM` / `DELETE … USING` shapes degrade to null) and run it through the normal SELECT path (so `RowSecurityRewriter` applies); **engine-managed** datasources delegate to the `QueryEngine.countAffectedRows` SPI default method (overridden by MongoDB `countDocuments`, Couchbase SQL++ `SELECT COUNT(*)` splice, Neo4j `MATCH … RETURN count(*)`, Elasticsearch/OpenSearch `_count` — each applying its native row security and failing closed on uncountable shapes; Redis, Cassandra/ScyllaDB, DynamoDB, and the warehouse engines inherit the unsupported default).
- **Bounded.** Both calls run under the dedicated `accessflow.proxy.estimate-timeout` (`ACCESSFLOW_PROXY_ESTIMATE_TIMEOUT`, default `PT5S`) instead of the full execution statement timeout — an estimate is a best-effort signal, never worth a long lock.
- **Every path persists a row.** Success stores the full estimate; engines with no plan concept store `supported=false` + a localized `unsupported_reason` (a transactional `BEGIN…COMMIT` envelope short-circuits the same way); an unexpected error stores a `failed=true` sentinel with the message — mirroring the AI module's sentinel convention, so the frontend can always render a definitive state. Completion publishes `QueryEstimateCompletedEvent` (or `QueryEstimateFailedEvent`), which the realtime module fans out as the `query.estimate_complete` WebSocket event.
- **Consumers.** `GET /queries/{id}` embeds the row as `cost_estimate`; `QueryReviewStateMachine.buildContext` reads it live (fail-closed) for the `estimated_rows` / `scan_type` routing conditions; `DefaultAiAnalyzerService` renders it into the `{{cost_estimate}}` prompt placeholder.

### Data classification & derivation (AF-447)

`data_classification_tag` rows (see [docs/03-data-model.md](03-data-model.md)) tag tables/columns with
one or more classifications — `PII`, `PCI`, `PHI`, `GDPR`, `FINANCIAL`, `SENSITIVE` — and **auto-derive
stricter handling**, the foundation for compliance reporting. Tags are managed by
`DefaultDataClassificationService` (`core.internal`, implementing both `core.api.DataClassificationAdminService`
for CRUD/preview/reporting and `core.api.DataClassificationQueryService` for read-only consumers); the
REST surface lives in the `security` module (`DataClassificationTagController`,
`AdminDataClassificationController`), mirroring the masking-policy split.

- **Defaults registry.** `DataClassificationDefaults` (`core.internal`) maps each classification to a
  recommended masking strategy + params and a review posture (PII/GDPR/FINANCIAL → `PARTIAL`
  `visible_suffix=4`, 1 approval; PCI/PHI → `FULL`, 2 approvals; SENSITIVE → `HASH`, no mandatory human
  approval). It stays out of `core.api` because it references `MaskingStrategy` and is an implementation
  policy, not a contract.
- **Masking derivation (auto-applied).** Creating a **column-level** tag with `apply_masking` on
  idempotently calls `MaskingPolicyAdminService.create(...)` for `table_name.column_name` using the
  classification default — skipped when an enabled masking policy already covers the column. Table-level
  tags (no column) derive no masking. **Deleting a tag never removes the derived masking policy** — it
  may have been customized and silently dropping a security control is dangerous; the derivation preview
  surfaces the now-detached state.
- **Review derivation (suggested, not applied).** `previewDerivation(...)` aggregates the strictest
  posture across the datasource's tags (`requires_*` OR-ed, `min_approvals` MAX-ed) and the per-column
  masking suggestions with an `already_applied` flag. It **never mutates a review plan** — plans are
  shared across datasources, so a stricter posture is only ever a suggestion an admin applies manually.
- **AI risk hook.** `DefaultAiAnalyzerService` fetches the datasource's tags before analysis, annotates
  the schema context the LLM sees (`users(email … [PII,GDPR])`, reusing the `*RESTRICTED*` mechanism in
  `SystemPromptRenderer.describeSchema`), and after the LLM returns applies a **deterministic risk bump**
  via `ClassificationRiskBooster`: it re-parses the SQL (`proxy.api.SqlParserService`) for referenced
  tables, adds the strongest per-classification weight (PCI/PHI +30, FINANCIAL +20, PII/GDPR +15,
  SENSITIVE +10, clamped to 100) to the score, and recomputes the risk level by quartile thresholds —
  the level can only rise, never drop below the LLM's verdict. The boosted score/level is what persists
  and drives the workflow router.

### Automated sensitive-data discovery (AF-623)

The `discovery` module closes the AF-447 loop: instead of waiting for an admin to know a column is
sensitive, a scanner **finds** the sensitive columns and proposes the classification tags. It
depends only on `core.api`, `proxy.api`, `ai.api`, and `audit.api`.

- **Scan pipeline** (`DiscoveryScanService.scan`, driven by `DiscoveryScanJob` per due
  `discovery_scan_config` row or by the on-demand `POST /datasources/{id}/discovery/scan`): enumerate
  tables via `DatasourceAdminService.introspectSchemaForSystem`, read a bounded raw sample per table
  through `QueryExecutor.sampleTable` (the AF-443 path, so every engine — JDBC and plugin — is
  covered; the per-table statement timeout is the tighter `accessflow.discovery.sample-statement-timeout`),
  run the detector pipeline over each column's string values, and upsert findings. Raw sampled values
  live only on the scan method's stack — findings persist a **redacted** sample only
  (`ColumnMasker` `PARTIAL`, `visible_suffix=4`). Guards: `max-tables-per-scan` (default 200), a
  wall-clock `scan-time-budget` (default `PT10M`; exceeding either flags the run `partial`), per-table
  failures swallowed, a per-node in-flight set (cluster races are harmless — upserts are idempotent
  against the natural-key unique index), and columns already covered by an enabled masking policy or
  an existing tag are skipped.
- **Detectors** (`discovery.internal.detect`, pure classes): `EMAIL`→PII, `CREDIT_CARD` (13–19
  digits + Luhn)→PCI, `SSN` (US, never-issued ranges rejected)→PII, `IBAN` (per-country length +
  mod-97)→FINANCIAL, `PHONE`→PII. First-match-wins per value in that order (checksum detectors
  first, so a PAN never double-counts as a phone). A column needs ≥ 5 non-null string samples and a
  ≥ 30 % match ratio to produce a proposal; `confidence` is the match percentage.
- **AI pass (opt-in, fail-safe).** When `ai_classification_enabled`, columns with no regex proposal
  are sent — capped at `max-ai-tables-per-scan` (default 25) — to `ai.api.DataDiscoveryAiService`,
  which calls the org's first usable `ai_config` through the analyzer holder's freeform lane with a
  strict-JSON preamble and parses leniently (unknown columns/classifications dropped, any failure →
  empty). **Only column names, types, and `FORMAT_PRESERVING`-redacted samples reach the provider**
  — never raw values. Same fail-safe posture as the UBA anomaly summary: the AI pass can never block
  or fail a scan.
- **Worklist.** Findings land as `PENDING` rows keyed by `(column, classification, detector)`;
  rescans refresh `PENDING` rows in place and never touch decided ones. Confirming (bulk, per-row
  independent transactions like the attestation bulk path) applies the tag through
  `DataClassificationAdminService.create(..., applyMasking=true)` — deriving masking exactly like a
  manual tag — and marks the finding `CONFIRMED` (a pre-existing tag reports `TAG_CONFLICT` but
  still clears the worklist). Dismissing marks `DISMISSED`, permanently suppressing the proposal.
  Audited as `DISCOVERY_SCAN_COMPLETED` / `DISCOVERY_FINDING_CONFIRMED` / `DISCOVERY_FINDING_DISMISSED`.
- **Limitations (v1).** Detectors examine scalar string cells only (nested NoSQL documents/maps are
  skipped); stale `PENDING` findings whose data disappeared are kept for the admin to dismiss.

### Compliance reporting (AF-459)

The `compliance` module produces pre-built compliance reports and signed exports. It is a thin,
read-only module that depends only on other modules' `api` packages — `workflow.api` (snapshot period
query), `core.api` (`DataClassificationAdminService`, `DatasourceAdminService`, `UserAdminService`),
`audit.api` (`AuditLogService.record` + the new `COMPLIANCE_REPORT_EXPORTED` / `compliance_report`
enum values), and `security.api` (`ExportSignatureService`). Nothing depends back on `compliance`, so it
introduces no module cycle. (It cannot live in `audit`: `workflow` already depends on `audit.api`, so an
`audit → workflow.api` edge would cycle — hence a separate module.)

- **Data source = immutable snapshots.** Both reports run over `query_snapshots` (AF-449), not live
  query rows, so a report is a stable forensic record of what executed. `QuerySnapshotService.findForPeriod`
  (`workflow.api`, new) returns snapshots in `[from, to)` on `executed_at`, optionally scoped to a
  datasource and a `QueryType` set, capped at `accessflow.compliance.max-rows`+1 so the service can flag
  truncation. `DefaultComplianceReportService` (`compliance.internal`) validates the period
  (`InvalidReportPeriodException` → 400 `INVALID_REPORT_PERIOD` when missing/inverted/over
  `max-report-period`) and dispatches by `ComplianceReportType`.
- **Classified-access report.** `ClassificationJoiner` joins each snapshot's `referenced_tables` against
  `DataClassificationAdminService.listForOrganization(orgId)` keyed by datasource. `TableNameNormalizer`
  folds both sides (lowercase, strip quotes) and matches a schema-qualified name against a bare tag
  (and vice-versa) while never matching across different schemas. Snapshots with no classified match are
  dropped. Submitter emails are batch-resolved via `UserAdminService.findByIds`.
- **Regulatory audit-trail report.** Snapshots with `query_type ∈ {DDL, DELETE}`; approver names are
  parsed from the snapshot's embedded `review_decisions` JSON by `ReviewDecisionsParser` (tolerant —
  malformed JSON yields no approvers, never throws; JSON parsing stays in `internal`, never in `api`).
- **Signed export + audit chaining.** `DefaultComplianceExportService` renders the report
  (`CompliancePdfWriter` via Apache PDFBox, or `ComplianceCsvWriter` — its own RFC-4180 copy, since
  `audit.internal.CsvWriter` can't cross the module boundary), computes the SHA-256, signs the exact
  bytes with `ExportSignatureService` (`SHA256withRSA` over the JWT RS256 key pair), then records a
  `COMPLIANCE_REPORT_EXPORTED` audit row carrying the hash + signature. That audit write is
  **integrity-critical and propagates on failure** (the export fails) — deliberately unlike the audit
  module's best-effort `AUDIT_LOG_EXPORTED` meta-audit. The controller
  (`/api/v1/admin/compliance/*`, `hasAnyRole('AUDITOR','ADMIN')`) sets the signature / algorithm /
  content-hash response headers and exposes the verification public key at `GET /signing-certificate`.

### Result-export governance & DLP (#626)

Masking and row security govern what a user *sees*; export governance governs what *leaves*. Two
egress paths for a query's persisted result snapshot (`query_request_results`) are policy-governed:
`GET /api/v1/queries/{id}/results/export` (signed CSV/PDF download) and the results-CSV attachment
on recurring-execution emails (#627). The MCP `get_query_result` tool remains view-parity (the same
data the in-app table shows) and is deliberately not export-governed.

- **Policies** (`export_policy`, V145) live in `core` beside the masking/row-security policies:
  per-datasource rows with a `mode` (`ALLOW < WATERMARK < ROW_CAP < DENY_CLASSIFIED` — the enum
  order is load-bearing), an optional `row_cap`, optional `deny_classifications`, and the
  row-security `applies_to_*` polarity (all empty ⇒ every exporter, **no implicit ADMIN bypass**).
  CRUD via `security/internal/web/ExportPolicyController`
  (`PERM_EXPORT_POLICY_MANAGE`), resolution via `core.api.ExportPolicyResolutionService`.
- **Decision** (`compliance/internal/DefaultResultExportGovernanceService`): resolved policies are
  combined most-restrictive-wins; a `DENY_CLASSIFIED` row participates only when the result
  actually contains a matching classified column. Classification presence is computed **at export
  time** — the persisted result's column names and the snapshot's `referenced_tables` matched
  against `data_classification_tag` via `TableNameNormalizer` (table-level tags classify every
  returned column of the table; column-level tags match returned columns by name,
  case-insensitively — best-effort for document engines whose result columns are field unions).
  `WATERMARK` and `ROW_CAP` both watermark (a capped file must carry its cap provenance); no
  applicable policy ⇒ `ALLOW`, unwatermarked.
- **Export pipeline** (`compliance/internal/DefaultResultExportService`) mirrors the compliance
  export: visibility check (`QUERY_ADMIN` or submitter, 404-hiding via the immutable
  `query_snapshots` row) → decision (deny ⇒ 403 `RESULT_EXPORT_DENIED`) → row cap
  (min of the policy cap and `accessflow.compliance.result-export-max[-pdf]-rows`) → render with
  the `ResultExportWatermark` header/footer baked in (exporter email, UTC timestamp, query request
  id — CSV: single-cell first/last records; PDF: document metadata + visible stamp) → SHA-256 →
  `ExportSignatureService` sign → **fail-hard** `RESULT_EXPORTED` audit (no audit row, no
  download) → `SensitiveResultExportedEvent` when classified columns were present. Because the
  watermark is baked in before signing, stripping it invalidates the signature.
- **Email attachments**: `EmailNotificationStrategy` calls
  `compliance.api.ResultExportGovernanceService.decide` **per recipient** — deny suppresses the
  attachment (the email still delivers; results stay viewable in-app), a row cap truncates it, and
  watermark decisions stamp it. After a successful send, `recordAttachmentExport` writes a
  best-effort `RESULT_EXPORTED` row (`trigger=email_attachment`, actor = recipient) and raises the
  sensitive-export event when classified.
- **Events**: `SensitiveResultExportedEvent` is published from `compliance/events/` outside any
  transaction — the notifications bridge consumes it with a plain `@EventListener`
  (an `@ApplicationModuleListener` would silently never fire, the `QuerySnapshotListener` trap).
  The new module edge is `notifications → compliance.api/events`; nothing depends back on
  `notifications`, so no cycle.

### Personalized dashboard (AF-498)

The `dashboard` module is a self-scoped read-aggregation module: every endpoint is bound to the
authenticated caller's own data (no admin role required). Like `compliance`, it depends only on other
modules' `api` packages — `workflow.api` (`ReviewService.listPendingForReviewer`), `core.api`
(`QueryRequestLookupService` + the new self-scoped `MyQueryInsightsLookupService`), `ai.api`
(`BehaviorAnomalyLookupService` + the new self-scoped `UserBehaviorAnomalyService`), `security.api`
(`ExportSignatureService`), `audit.api` (`AuditLogService` + the new `DASHBOARD_SUMMARY_EXPORTED` /
`dashboard_summary` enum values), and — for API Access Governance (AF-500) — `apigov.api`
(`MyApiRequestInsightsLookupService`, `ApiRequestService`, `ApiReviewService`). Nothing in those modules
depends back on `dashboard`; the only new edge is `notifications → dashboard` (the digest event),
matching the existing `notifications → ai/workflow` direction — so no cycle.

- **Self-scoped reads.** `MyQueryInsightsLookupService` (`core.api`, Postgres aggregations over
  `query_requests` ⨝ `ai_analyses` filtered to `submitted_by = me`) returns day-bucketed status/risk
  trend series, per-status counts, and the user's recent non-failed analyses that carry optimization
  suggestions. `UserBehaviorAnomalyService` (`ai.api`) lists / acknowledges / dismisses the caller's
  **own** anomalies, refusing another user's rows as `AnomalyNotFoundException` (never leaked). The
  `/anomalies/mine` endpoints live on the existing badge controller in the `ai` module.
- **API request widgets (AF-500).** `MyApiRequestInsightsLookupService` (`apigov.api`, Postgres
  aggregations over `api_requests` ⨝ `ai_analyses` filtered to `submitted_by = me`) mirrors the SQL
  insights service: day-bucketed status/risk trend series (served at `/dashboard/my-api-request-trends`)
  and per-status counts. `DashboardService.summary` folds in the open-API-request count (non-terminal
  statuses), recent API requests (`ApiRequestService.list`), and the caller's pending API-approval queue
  (`ApiReviewService.listPending` — count + recent list).
- **Summary + suggestions.** `DashboardService.summary` composes the headline counts + short recent
  lists. `DashboardSuggestionService` parses each analysis's `optimizations[]` JSON, assigns a stable
  `{aiAnalysisId}:{index}` id, and joins it against `dashboard_suggestion_state` (a row exists only for
  diverged — DISMISSED/APPLIED — items; OPEN is the implicit default) so the backlog shows only OPEN items.
- **Signed weekly export.** `DefaultDashboardSummaryExportService` mirrors the compliance pipeline —
  build the week's `DashboardWeeklySummary` → render (`DashboardSummaryPdfWriter` via PDFBox /
  `DashboardSummaryCsvWriter`) → SHA-256 → `ExportSignatureService.sign` → record an integrity-critical
  `DASHBOARD_SUMMARY_EXPORTED` audit row → stamp the `X-AccessFlow-Signature` / `-Signature-Algorithm` /
  `-Content-SHA256` response headers. It defines its own `DashboardSummaryExport` record (no cross-module
  DTO reuse of `compliance.api.SignedExport`).
- **Weekly digest job.** See [§ Scheduled jobs and clustering](#scheduled-jobs-and-clustering) for
  `WeeklyDigestJob` and the transactional event publish.

---

## Review Workflow State Machine

```
                  ┌─────────────┐
   Submit ───────►│ PENDING_AI  │
                  └──────┬──────┘
                         │ AI complete
                  ┌──────▼──────────────┐
           ┌──────│   PENDING_REVIEW    │◄── (if human review required)
           │      └──────┬──────────────┘
    Reject │             │ All stage approvals received
           │      ┌──────▼──────┐
           │      │  APPROVED   │
           │      └──────┬──────┘
           │             │ Proxy executes
           │      ┌──────▼──────┐
           │      │  EXECUTED   │
           │      └─────────────┘
           │
           ├──────►  REJECTED
           ├──────►  FAILED  (execution error)
           └──────►  CANCELLED  (submitter cancels while PENDING_*)

   Recurring series (#627): the APPROVED parent never advances — RecurringQueryRunJob
   inserts child occurrence rows directly in APPROVED (submission_reason=RECURRING,
   recurring_parent_id set) and executes each through the proxy to EXECUTED / FAILED.
   Kill-switch: submitter or any QUERY_REVIEW holder cancels the parent → CANCELLED.
```

### Multi-Stage Approval

`review_plan_approvers` rows have a `stage` integer. Stage 1 approvers must all approve before stage 2 notifications are sent. The workflow service tracks current stage and advances automatically.

`review_plan.min_approvals_required` is **per stage**: each stage must collect that many `APPROVED` decisions before the next stage's approvers become current. Current-stage computation is decision-derived: `min(stage : count(APPROVED at stage) < min_approvals_required)`, scoped to the plan's approver rules.

### Implementation: AI-completion → review transition

`workflow.internal.QueryReviewStateMachine` is a Spring Modulith `@ApplicationModuleListener` consuming `AiAnalysisCompletedEvent`, `AiAnalysisFailedEvent`, and `AiAnalysisSkippedEvent` from the `core` module's events. It runs `AFTER_COMMIT` of the AI module's persistence transaction, so the `ai_analyses` row and `query_requests.ai_analysis_id` link are already visible.

Decision rules:

| Plan flag combination | Resulting status |
|-----------------------|-------------------|
| `requires_human_approval=false` | `APPROVED` (auto-approve) |
| Active pre-approving JIT grant covers the query (#582 — runs before the plan rules, after routing policies) | `APPROVED` (grant fast-path) |
| `auto_approve_reads=true` AND `query_type=SELECT` AND AI risk ∈ {LOW, MEDIUM} | `APPROVED` (fast path) |
| (default) | `PENDING_REVIEW` |
| Datasource has no review plan | `PENDING_REVIEW` (safe default) |

`AiAnalysisFailedEvent` **always** transitions to `PENDING_REVIEW`, regardless of plan flags. Auto-approve is a positive-signal shortcut; failure is a missing signal — they aren't symmetric, so an AI provider error never short-circuits human review. The AI module persists a sentinel `CRITICAL` analysis row on failure with `failed=true` and `error_message=<reason>` (added in AF-249) so the reviewer can render an "AI analysis failed" surface on `QueryDetailPage` instead of seeing a fake CRITICAL verdict. Reviewers and admins can call [`POST /queries/{id}/reanalyze`](04-api-spec.md#post-queriesidreanalyze--response-202) to re-run analysis on the failed row — the workflow service deletes the sentinel and publishes `AiReanalysisRequestedEvent`, which the AI module's listener consumes by invoking the normal `analyzeSubmittedQuery` pipeline. A `QUERY_AI_REANALYZE_REQUESTED` audit row is written from the controller on each call.

`AiAnalysisSkippedEvent` (added in AF-307) covers the case where the datasource has `ai_analysis_enabled = false`. The state machine respects `plan.requires_human_approval`: when human review is not required the query transitions `PENDING_AI → APPROVED`; otherwise (plan requires human approval, or no plan is configured) it transitions to `PENDING_REVIEW`. The fast-path `auto_approve_reads` shortcut is **never** applied — without an AI risk signal, the SELECT/low-risk shortcut cannot be evaluated. No sentinel `ai_analyses` row is persisted, so the frontend renders the analysis step as bypassed rather than failed.

### Policy-as-code routing engine (AF-379)

Routing policies are ordered, attribute-based rules that decide how a submitted query is routed **before** the default review-plan logic runs. The engine is owned by the `workflow` module and evaluated inside the same `QueryReviewStateMachine` listener, **after** AI analysis (or the skip event) and **before** reviewer fan-out:

1. `RoutingPolicyEngine` loads the org's enabled policies (org-wide + this datasource) in ascending `priority` and evaluates each `condition` against the query context (query type, referenced tables, AI risk level / score, requester role + group memberships, time-of-day / day-of-week, WHERE / LIMIT presence, transactional flag, the pre-flight cost estimate — estimated/affected rows and root scan type, read live from `query_estimates` and fail-closed when absent (AF-624) — and the client context captured at submission — source IP / CIDR, user-agent, time-since-last-approval, CI/CD origin) via `RoutingConditionEvaluator`.
2. **First match wins.** The first enabled policy whose condition matches decides the action; evaluation stops there. On **no match** the grant-covered auto-approval fast-path (#582, see the [JIT section](#grant-covered-query-auto-approval-582)) is consulted next, and only then does the query fall through to the datasource's review plan exactly as before — so **any** matching policy (AUTO_REJECT, REQUIRE_APPROVALS, ESCALATE — including anomaly-driven ones) always wins over the grant fast-path.
3. The outcome (matched policy id, action, resolved `effective_min_approvals`, reason) is persisted as a single `routing_decision` row (`RoutingDecisionService`), and surfaced on `GET /queries/{id}` as `matched_policy`.

The four `routing_action` effects:

| Action | Effect |
|--------|--------|
| `AUTO_APPROVE` | Short-circuit straight to `APPROVED`, skipping human review. |
| `AUTO_REJECT` | Short-circuit straight to `REJECTED` — a **new** `PENDING_AI → REJECTED` state-machine edge. Illegal before AF-379. |
| `REQUIRE_APPROVALS` | Force human review (`PENDING_REVIEW`) with an **absolute** minimum approvals = the policy's `required_approvals`. |
| `ESCALATE` | Force human review with effective minimum = the review plan's `min_approvals_required` + the policy's `required_approvals` delta (default delta 1). |

For `REQUIRE_APPROVALS` / `ESCALATE`, the resolved absolute count is written to `routing_decision.effective_min_approvals` and read by `DefaultReviewService` as the **per-stage minimum override** in place of the plan's `min_approvals_required` — so the routing decision, not just the plan, governs how many approvals a stage needs.

**Condition model.** The condition tree is a typed, pure-Java model (no external policy engine, no raw SQL) serialised to / from the `routing_policy.condition` JSONB by `RoutingConditionCodec`. Logical combinators (`and` / `or` / `not`) nest arbitrarily for API/bootstrap-authored policies; the UI's guided builder authors a single-level `and` / `or` of (optionally negated) leaf conditions. The wire format is documented in [docs/03-data-model.md → routing_policy](03-data-model.md#routing_policy).

**Timezone.** `time_of_day` and `day_of_week` operands are evaluated in the **server's local timezone**; `time_of_day` supports overnight wrap-around (e.g. a 22:00–06:00 window).

**Client context (AF-446).** The `source_ip`, `user_agent`, and `cicd_origin` signals are only available on the HTTP submission request, but routing runs asynchronously after AI completion — so they are captured at submission (`QuerySubmissionController`) and persisted on `query_requests` (`submitted_ip`, `submitted_user_agent`, `cicd_origin`), then read back by `QueryReviewStateMachine` when it builds the `ConditionContext`. `cicd_origin` is set when the request was authenticated via an API key (the `security.api.ApiKeyAuthentication` marker) **or** carried the `X-AccessFlow-CI` header. `time_since_last_approval` is computed at routing time as the minutes since the requester's most recent APPROVED/EXECUTED query on the same datasource (`QueryRequestLookupService.findLastApprovalInstant`). All four client-context operands **fail closed** — when the required signal is absent the leaf evaluates to `false` (the matcher in `CidrMatcher` / `GlobMatcher` returns false on a null IP / user-agent, and `time_since_last_approval` is false with no prior approval), so a permissive `AUTO_APPROVE` policy never fires on missing context; express escalation of unknown context as `not(source_ip(...))`. CIDR syntax is validated by `RoutingConditionValidator` at create / update (422 on a malformed block).

**Skip / failure paths.** On the AI-skipped path (`datasource.ai_analysis_enabled = false`) the risk-based operands (`risk_level`, `risk_score`) evaluate to **false** — there is no AI signal, so risk-gated policies simply don't match and the query continues to non-risk policies or the plan fall-through. Routing is **not** run on the AI-failure path (`AiAnalysisFailedEvent`) — a missing AI signal never feeds an automated routing decision; the query lands in `PENDING_REVIEW` for a human, consistent with the auto-approve asymmetry above.

**Audit.** Automated decisions reuse the `QUERY_APPROVED` / `QUERY_REJECTED` audit actions with metadata `{ auto_approved | auto_rejected: true, source: "ROUTING_POLICY", routing_policy_id, reason }`. A `REQUIRE_APPROVALS` / `ESCALATE` match records the same matched-policy metadata (`source: "ROUTING_POLICY", routing_policy_id, effective_min_approvals, reason`) on the `QUERY_REVIEW_REQUESTED` action — the `QueryReadyForReviewEvent` carries the matched-policy fields for the routed-to-review path (AF-446). Policy CRUD writes the dedicated `ROUTING_POLICY_CREATED` / `_UPDATED` / `_DELETED` / `_REORDERED` actions against the `routing_policy` resource type. The engine reads / writes the new `routing_policy` and `routing_decision` tables (Flyway `V59__create_routing_policy.sql`).

### Implementation: review decisions

`workflow.internal.DefaultReviewService` enforces eligibility and orchestrates state transitions through `core.api.QueryRequestStateService`:

1. **Self-approval check** (first): submitter ≠ reviewer, regardless of role. Throws `AccessDeniedException` (HTTP 403). Enforced in service, not controller — see `docs/07-security.md:50`.
2. **Tenant scope**: query, plan, and reviewer must all be in the same `organization_id`.
3. **Role gate**: caller must be `REVIEWER` or `ADMIN`.
4. **Approver match at current stage**: caller's `userId` matches a `review_plan_approvers.user_id` at the current stage, OR caller's role matches a `review_plan_approvers.role` at that stage.
4a. **Datasource reviewer scope (AF-353)**: if `datasource_reviewers` has any rows for the query's datasource, the caller's user id must additionally appear in the eligible set (direct assignment, or membership in an assigned group). When the table is empty for that datasource, this check is a no-op — the system falls back to plan-approver logic for backward compatibility. The resolution lives in `core.api.ReviewerEligibilityService` (`DefaultReviewerEligibilityService` returns `Optional.empty()` to signal "no scope"). The same predicate is folded into `QueryRequestRepository.findPendingForReviewer` so the SQL-side query queue stays consistent with the service-side decision gate.
5. **State guard**: the underlying `QueryRequestStateService` takes a `PESSIMISTIC_WRITE` lock on the `query_requests` row (`@Lock(LockModeType.PESSIMISTIC_WRITE)` in `QueryRequestRepository.findByIdForUpdate`), re-reads decisions inside that transaction, inserts the new `review_decisions` row, and conditionally transitions the status — all atomically. The row lock makes it impossible for two concurrent approvers to both observe the threshold-met condition and double-advance.
6. **Idempotency**: a unique index on `(query_request_id, reviewer_id, stage)` (Flyway V11) plus a service-level pre-check guarantees that a duplicate decision (e.g. a double-clicked button) returns the existing decision rather than inserting twice.

`approve` may resolve to either `PENDING_REVIEW` (more approvers needed at this stage, or higher stages remain) or `APPROVED` (last stage threshold met). `reject` is always terminal (`REJECTED`). `request-changes` is non-terminal — the query stays in `PENDING_REVIEW` and the comment is recorded for the submitter.

### Step-up auth and the one-tap push decide path (AF-444)

`POST /reviews/{queryId}/decide` is the decision endpoint for the mobile/PWA one-tap push flow. It is
**not** a new decision path — it consumes a step-up token, then delegates to the same
`ReviewService.approve()` / `reject()` as the in-app and Slack endpoints, so the self-approval guard
(step 1 above) and every eligibility/state guard apply identically. A user can never approve their
own query from any channel.

Step-up auth lives in the `security` module:

- `security.api.StepUpService` (`DefaultStepUpService`) re-verifies an existing credential — a TOTP
  code when the user has 2FA enrolled (`core.api.TotpVerificationService`), otherwise the account
  password (`PasswordEncoder` against the `UserView.passwordHash`). On success it mints a **single-use**
  token via `StepUpCodeStore` (Redis, `stepup:` namespace, TTL `accessflow.security.step-up.ttl`,
  default `PT5M`), following the OAuth2 / SAML exchange-code pattern. SSO-only users without a password
  and without 2FA cannot step up — they decide in the standard authenticated review screen.
- `POST /auth/step-up` issues the token; `ReviewController.decide` `consume()`s it, asserts the token
  was issued to the caller, then commits the decision and audits it (`QUERY_APPROVED` / `QUERY_REJECTED`
  with metadata `channel=PUSH, step_up=true`).

Web-push delivery (the per-user subscription path, VAPID, and `WebPushSender`'s pure-JDK RFC 8291 /
RFC 8292 implementation) lives in the `notifications` module — see
[docs/08-notifications.md → Web Push](08-notifications.md).

Terminal transitions publish `QueryApprovedEvent` / `QueryRejectedEvent` (in `workflow.events`) for the audit and notifications modules to subscribe to.

### Approval timeout (auto-rejection)

`QueryTimeoutJob` (`workflow.internal.scheduled`) runs on a `@Scheduled(fixedDelayString = "${accessflow.workflow.timeout-poll-interval:PT5M}")` cadence. Each tick:

1. Calls `QueryRequestLookupService.findTimedOutPendingReviewIds(now)` — a native SQL join over `query_requests → datasources → review_plans` that returns any `PENDING_REVIEW` row whose `created_at + INTERVAL approval_timeout_hours` is before now.
2. For each id, calls `QueryRequestStateService.markTimedOut(id)`, which acquires the same pessimistic write lock as manual decisions, transitions `PENDING_REVIEW → TIMED_OUT`, and publishes `QueryStatusChangedEvent` and `QueryTimedOutEvent` (both in `core.events`).
3. Logs a summary: `"Auto-rejected N queries due to approval timeout (scanned M)"`.

`markTimedOut` does **not** insert a `review_decisions` row — auto-rejections carry no reviewer. The status field is the authoritative signal for distinguishing auto-rejections from manual rejections (`TIMED_OUT` vs `REJECTED`); `AuditEventListener.onQueryTimedOut` additionally writes a `QUERY_REJECTED` audit row with `metadata = { auto_rejected: true, reason: "approval_timeout", timeout_hours: N }` for backward compatibility with external audit consumers. The notifications module dispatches `NotificationEventType.REVIEW_TIMEOUT` (currently sharing the rejection email/Slack template — a dedicated template is tracked under [accessflow#101](https://github.com/bablsoft/accessflow/issues/101)).

The job is idempotent: a row already in `TIMED_OUT` (or any non-`PENDING_REVIEW` state — e.g. when a manual decision raced the timeout) is observed by `markTimedOut`, which returns `false` without re-publishing events.

The `GET /queries/{id}` response surfaces the active plan via `review_plan_name` and `approval_timeout_hours` so clients can render the timeout reason on the detail page (and, for queries still in `PENDING_REVIEW`, an "auto-rejects in N hours" hint).

### External ticket decisions (ServiceNow / Jira bi-directional sync, AF-453)

`workflow.api.ExternalDecisionService` (`DefaultExternalDecisionService`) is the entry point the notifications module's ticketing inbound webhook calls when a linked ticket's resolution should decide a query (see [docs/08-notifications.md → Ticketing inbound webhooks](08-notifications.md#ticketing-inbound-webhooks--bi-directional-sync-af-453)):

- `applyTicketDecision(queryRequestId, organizationId, APPROVE | REJECT, reason)` is **idempotent and race-safe**: it returns `false` (never throws) when the query is missing, belongs to another organization, is no longer `PENDING_REVIEW`, or a manual decision races the transition.
- An `APPROVE` transitions `PENDING_REVIEW → APPROVED` via `QueryRequestStateService.transitionTo` and publishes `QueryAutoApprovedEvent(id, null, reason, null, null)`; a `REJECT` transitions to `REJECTED` and publishes `QueryAutoRejectedEvent(id, null, reason)`. Reusing the auto-decision core events means the existing audit and notification listeners fire exactly as they do for routing-policy decisions — the `reason` string (e.g. `"ServiceNow ticket INC0010023 moved to 'Resolved' by jdoe"`) carries the provenance into the audit metadata.
- Like the timeout path, **no `review_decisions` row is inserted** — there is no reviewer; the trail is the audit rows (`TICKET_STATUS_SYNCED` from the webhook + the auto-decision `QUERY_APPROVED`/`QUERY_REJECTED` row).

Ticket **creation** is the notifications module's job (`TicketingNotificationStrategy` and its ServiceNow / Jira subclasses); the persisted links live in core (`query_tickets`, `core.api.QueryTicketService`) so `QueryReadController` can embed them as `linked_tickets` in the query detail response without a workflow → notifications dependency.

### Query result diffing (AF-361)

When a submitter re-runs the same SQL against the same datasource, AccessFlow links the new `query_requests` row to the previous successful run and surfaces a small delta panel on `QueryDetailPage`. The implementation is intentionally narrow — three scalar deltas (rows affected, execution duration, result row count) and a "previous run" link, no row-level diff.

**Canonicalisation rule.** `core.api.SqlCanonicalizer` (implemented in `core.internal.DefaultSqlCanonicalizer`) is a pure-logic helper that produces a normalised key from a SQL string:

1. Strip `/* … */` block comments (`(?s)/\*.*?\*/`).
2. Strip `--…<EOL>` line comments.
3. Collapse runs of whitespace (incl. tabs / newlines) to a single space.
4. `trim()`.
5. Upper-case the result with `Locale.ROOT`.

Returns `null` for null / blank / comment-only input — those rows skip the lookup. Quoted string literals are folded along with the rest of the SQL (the canonical key is opaque, never executed). A more elaborate AST-based deparser was rejected as out of scope; this lightweight textual rule is what the issue specifies and is straightforward to unit-test (`DefaultSqlCanonicalizerTest`).

**Linking on execution.** `DefaultQueryLifecycleService.doExecute` performs the lookup inside the success branch, before calling `recordExecutionOutcome`:

1. `canonicalSql = sqlCanonicalizer.canonicalize(query.sqlText())`.
2. `previousRunId = queryRequestLookupService.findPreviousRunId(submitterId, datasourceId, canonicalSql, currentQueryId).orElse(null)` — backed by a JPA query against the partial index `idx_query_requests_diff_lookup` (see [docs/03-data-model.md](03-data-model.md#query_requests)).
3. Both values are passed through `RecordExecutionCommand` so `recordExecutionOutcome` writes them in the **same transaction** that flips the status to `EXECUTED`. The status change and the link are therefore atomic — readers never see one without the other.

Failure-path executions (`recordExecutionOutcome` with `outcome = FAILED`) carry `canonicalSql = null` and `previousRunId = null`. Failed runs never become future "previous run" candidates because the partial index requires `status = 'EXECUTED'`.

**Diff endpoint.** `GET /api/v1/queries/{id}/diff` (handled in `QueryReadController`) resolves the current row, applies the same submitter/reviewer/admin authorization as `GET /queries/{id}`, then:

- Returns `404 QUERY_DIFF_NOT_AVAILABLE` (RFC 9457 `ProblemDetail`) when `previous_run_id` is null or when the referenced row has been deleted. The detail message comes from the `error.query_diff_no_previous_run` i18n key.
- Otherwise fetches the previous row, computes `rows_affected_delta` and `execution_ms_delta` from the entity columns, and — only when both runs are `SELECT` and both have a persisted `query_request_results` snapshot — computes `row_count_delta` from those snapshots. Non-SELECT diffs return `null` for `row_count_delta`.

Response shape: see [docs/04-api-spec.md → GET /queries/{id}/diff](04-api-spec.md#get-queriesiddiff--response-200). The response record is annotated `@JsonInclude(ALWAYS)` so the three delta fields are always present (with `null` when not applicable), overriding the global `non_null` default — clients don't need defensive property checks.

### Scheduled jobs and clustering

`@EnableScheduling` and `@EnableSchedulerLock` are activated in the dedicated `scheduling` Spring Modulith module (`com.bablsoft.accessflow.scheduling`) — `SchedulingConfiguration` carries both annotations and `RedisLockProviderConfiguration` defines the `LockProvider` bean. Both classes are package-private under `scheduling/internal/`. Every `@Scheduled` method **must** carry a `@SchedulerLock(name = …, lockAtMostFor = …, lockAtLeastFor = …)`. The lock provider is `RedisLockProvider`, which reuses the same `RedisConnectionFactory` as the JWT refresh-token store. Lock keys live under the `accessflow:shedlock:` Redis prefix.

Scheduling infrastructure lives in its own module because it is cross-cutting: any business module can add a `@Scheduled` method without depending on another module's internals. The module exposes one public type, `scheduling.api.DistributedLockService` — a JDK-only wrapper for programmatic, one-shot cluster-wide locks (see [§ Startup bootstrap](#startup-bootstrap-env-driven-admin-config)). ShedLock types stay confined to `scheduling.internal/`.

This makes horizontal scaling safe: when the AccessFlow backend runs as multiple replicas (Kubernetes Deployment with `replicas > 1`, or any process supervisor that runs N instances against the same Postgres + Redis), only one replica wins the lock per tick and runs the job. The other replicas observe the lock and skip — they will see no PENDING_REVIEW rows that match by the time their own next tick fires, because the winner already drained them.

| Job | Module | Lock name | Cadence property | Default |
|-----|--------|-----------|------------------|---------|
| `QueryTimeoutJob` | workflow | `queryTimeoutJob` | `accessflow.workflow.timeout-poll-interval` | `PT5M` |
| `ReviewEscalationJob` | workflow | `reviewEscalationJob` | `accessflow.workflow.escalation-poll-interval` | `PT5M` |
| `ApiReviewEscalationJob` | apigov | `apiReviewEscalationJob` | `accessflow.apigov.escalation-poll-interval` | `PT5M` |
| `GroupReviewEscalationJob` | requestgroups | `groupReviewEscalationJob` | `accessflow.requestgroups.escalation-poll-interval` | `PT5M` |
| `ScheduledQueryRunJob` | workflow | `scheduledQueryRunJob` | `accessflow.workflow.scheduled-run-poll-interval` | `PT1M` |
| `RecurringQueryRunJob` | workflow | `recurringQueryRunJob` | `accessflow.workflow.recurring-run-poll-interval` | `PT1M` |
| `AccessGrantExpiryJob` | access | `accessGrantExpiryJob` | `accessflow.access.grant-expiry-poll-interval` | `PT5M` |
| `BehaviorAnomalyDetectionJob` | ai | `behaviorAnomalyDetectionJob` | `accessflow.ai.anomaly.detection-poll-interval` | `PT15M` |
| `WeeklyDigestJob` | dashboard | `weeklyDigestJob` | `accessflow.dashboard.weekly-digest.poll-interval` | `P1D` |
| `AttestationCampaignOpenJob` | attestation | `attestationCampaignOpenJob` | `accessflow.attestation.open-poll-interval` | `PT5M` |
| `AttestationCampaignCloseJob` | attestation | `attestationCampaignCloseJob` | `accessflow.attestation.close-poll-interval` | `PT5M` |
| `ApiRequestRunJob` | apigov | `apiRequestRunJob` | `accessflow.apigov.scheduled-run-poll-interval` | `PT1M` |
| `ApiRequestTimeoutJob` | apigov | `apiRequestTimeoutJob` | `accessflow.apigov.timeout-poll-interval` | `PT5M` |
| `RetentionPolicyScanJob` | lifecycle | `retentionPolicyScanJob` | `accessflow.lifecycle.policy-scan-interval` | `PT1H` |
| `RetentionPolicyExecutionJob` | lifecycle | `retentionPolicyExecutionJob` | `accessflow.lifecycle.policy-execution-interval` | `PT5M` |
| `ErasureExecutionJob` | lifecycle | `erasureExecutionJob` | `accessflow.lifecycle.erasure-execution-interval` | `PT1M` |
| `ErasureReviewTimeoutJob` | lifecycle | `erasureReviewTimeoutJob` | `accessflow.lifecycle.review-timeout-poll-interval` | `PT5M` |
| `ScheduledGroupRunJob` | requestgroups | `scheduledGroupRunJob` | `accessflow.requestgroups.run-poll-interval` | `PT1M` |
| `GroupTimeoutJob` | requestgroups | `groupTimeoutJob` | `accessflow.requestgroups.timeout-poll-interval` | `PT5M` |
| `DiscoveryScanJob` | discovery | `discoveryScanJob` | `accessflow.discovery.scan-poll-interval` | `PT15M` |
| `ApprovalPredictionTrainingJob` | ai | `approvalPredictionTrainingJob` | `accessflow.ai.approval-prediction.retrain-poll-interval` | `P1D` |
| `GrantUsageAggregationJob` | access | `grantUsageAggregationJob` | `accessflow.access.usage.aggregation-poll-interval` | `PT1H` |
| `AuditSinkDrainJob` | audit | `auditSinkDrainJob` | `accessflow.audit.sinks.drain-interval` | `PT30S` |

`WeeklyDigestJob` implements the opt-in weekly dashboard digest (AF-498): it scans `dashboard_digest_subscription` for `enabled = true` rows whose `last_sent_at` is null or older than `accessflow.dashboard.weekly-digest.period` (default `P7D`, a partial index backs the scan) and, per row, builds that user's weekly summary, publishes a `dashboard.events.WeeklyDigestReadyEvent`, and stamps `last_sent_at`. The per-row build+publish+stamp runs inside `WeeklyDigestDispatchService.publishDigest` (`@Transactional`) so the event is published within a committed transaction — otherwise the notifications module's AFTER_COMMIT `@ApplicationModuleListener` would silently drop it. Per-row `RuntimeException`s are swallowed (`log.error`) so one bad subscription cannot abort the batch. The `notifications` module consumes the event and fans the summary out over the user's email + chat channels (`WEEKLY_DIGEST`); PagerDuty treats it as not-applicable (never pages).

`AccessGrantExpiryJob` implements JIT access-grant expiry (AF-378): it scans for `access_grant_request` rows in `APPROVED` with `expires_at ≤ now()` (a partial index backs the scan) and, per row, revokes the materialised `datasource_user_permissions` row and transitions the request to `EXPIRED`. It is idempotent (`AccessGrantExpiryService.expireAndRevoke` returns `false` if the row is no longer `APPROVED` — an admin revoke may have raced) and swallows per-row `RuntimeException`s so one bad row cannot abort the batch. The system-driven `ACCESS_GRANT_EXPIRED` audit row is written by the `access` module itself (not the audit-module listener) so there is no reverse `audit → access` module dependency.

`AttestationCampaignOpenJob` / `AttestationCampaignCloseJob` drive recertification campaigns (AF-384) — see [§ Access recertification campaigns](#access-recertification-campaigns-af-384). The open job scans `SCHEDULED` campaigns past `scheduled_open_at`; the close job scans `OPEN` campaigns past `due_at`. Both delegate to the idempotent `AttestationLifecycleService` and swallow per-campaign `RuntimeException`s. System-driven `ATTESTATION_CAMPAIGN_OPENED` / `_CLOSED` and the auto-default item audits are written inline by the lifecycle service (no reverse `audit → attestation` dependency).

### Review escalation and nudges (#622)

Until a request is decided, the only thing that ever happened to it was the hard
`approval_timeout_hours` auto-reject — so a stalled approval chain was silent right up to the point
the submitter was rejected. Three jobs close that gap, one per request type because each table is
owned by a different module (the same reason `ApiRequestTimeoutJob` sits beside `QueryTimeoutJob`):
`ReviewEscalationJob`, `ApiReviewEscalationJob`, `GroupReviewEscalationJob`.

Each pass does two things against the request's review plan: **escalate** once past
`escalation_after_hours`, and **nudge** undecided reviewers every `nudge_interval_hours`. Both
columns are nullable and null means off, so an upgraded deployment behaves exactly as before until
an admin opts in.

**Notify-only, structurally.** Neither path touches the decision or eligibility code, and the job
tests assert it — they verify the job never calls `markTimedOut`, `recordApprovalAndAdvance`, or
`recordRejection`. Idleness must never become a route around the configured approver set.

**Idempotency lives in the stamp, not the scan.** `markEscalated` / `markNudged` re-check status and
the prior stamp under a row lock and publish their event inside that same transaction. A reviewer
who decides between the scan and the lock yields a no-op rather than a stray notification, and a
second replica cannot double-fire. This is why the jobs are safe to run on every node.

**Recipients follow intent, at the stage that is actually blocked.** Both events resolve reviewers
through `core.api.ReviewStages.current` — the same definition `DefaultReviewService` uses to decide
who *may* act, deliberately shared so the two cannot drift. Using the plan's lowest stage instead
would, on a multi-stage plan whose first stage has already approved, remind the people who are done
and say nothing to the ones holding it up. An escalation adds **every active org admin** on top —
the whole point is that the assigned reviewers did not act, so re-telling only them would be a
nudge. A nudge goes to those reviewers alone and deliberately does not copy admins.
PagerDuty pages on `REVIEW_ESCALATED` via the `REVIEW_STALLED` trigger, and has no trigger at
all for `REVIEW_NUDGE`: a reminder is not an incident.

**Grouped requests take the minimum** non-null `escalation_after_hours` across their members' plans
— see [03-data-model.md](03-data-model.md#escalation-and-nudges-622) — and stamp without notifying,
because `requestgroups` has no notification path at all. They have **no nudge half** for the same
reason: a reminder needs somebody to remind, so a group nudge could only advance a cursor nobody
reads, rewriting every pending bundle on every interval. `request_groups` therefore has
`escalated_at` and no `last_nudged_at`.

**API-request escalations do not email.** `REVIEW_ESCALATED` / `REVIEW_NUDGE` are the first event
types shared between queries and API requests, and the query path has an email template while
API-request events deliver in-app and over chat only (AF-500). `EmailNotificationStrategy` therefore
gates on the context, not just the event type — otherwise an API escalation would render a
query-shaped mail with an empty SQL-preview block. Note the pre-existing consequence: because plan
channels are resolved by *datasource*, an API-request context resolves none, so those escalations
reach the in-app bell and the system-SMTP fallback but not a configured Slack/PagerDuty channel.

All three jobs inject `java.time.Clock` rather than calling `Instant.now()`, per
`.claude/patterns/scheduled-job.md`. `QueryTimeoutJob` and `ScheduledQueryRunJob` predate that rule
and still deviate, which is precisely why their "is it due yet" logic cannot be unit-tested.

---

### Reviewer delegation (#622)

A reviewer sets an out-of-office window naming a delegate; during it the delegate is an eligible
approver everywhere the delegator was. Resolution lives in `core.api.ReviewDelegationLookupService`
and is consumed by `workflow`, `apigov` and `requestgroups` — see
[03-data-model.md → review_delegations](03-data-model.md#review_delegations-622) for the schema and
its invariants.

**Eligibility is evaluated per identity as a whole.** Each flow builds a list of
`core.api.ReviewCandidate` — the caller's own identity first, then any borrowed, ordered by
`(created_at, id)` so a replayed decision records the same provenance — and a candidate must satisfy
*every* predicate the flow applies. Satisfying the approver rule as delegator A while satisfying the
datasource-reviewer scope as delegator B would synthesize an identity nobody holds, so the
predicates are never OR-ed across candidates.

**Ordering matters.** The `Permission` check (`QUERY_REVIEW` / `API_REQUEST_REVIEW`) runs *before*
delegation is resolved, so a delegation can never confer a permission — it widens which requests an
already-permitted reviewer may act on, never whether they may review at all.

**The self-approval ban covers both identities.** A delegator who submitted the request is dropped
from the candidate set rather than rejecting the call outright, so a reviewer who is independently
eligible keeps their own authority.

**One authority, one vote.** Beyond the unique index, each flow rejects a candidate when a decision
already exists at the current stage whose `reviewer_id` *or* `on_behalf_of_user_id` is that
candidate — covering both "delegator voted, then delegate voted for them" and the reverse.

**Queue visibility.** `QueryRequestRepository.findPendingForReviewer` takes collections of principal
ids and lower-cased role names and is a deliberate **over-approximation**: it flattens identities, so
`DefaultReviewService.isCurrentlyActionable` re-checks every row per-candidate before the user sees
it. Its approver match is an `exists` subquery rather than a join — the join fanned out one row per
matching approver rule, which is why it once needed `select distinct` and a `count(distinct q)`. The
`submittedBy <> :userId` exclusion stays scalar in all three queues: widening it to cover delegators
would hide requests the reviewer is eligible for in their own right, and that rule is per-identity.
The grouped-request queue filters in memory for the same reason (its eligibility is a union over
member plans), so `total_elements` is an upper bound there too.

**API-request review gained approver eligibility.** Before this, `DefaultApiReviewService` checked
only self-approval and status: any holder of `API_REQUEST_REVIEW` could decide any pending request in
the org. It now honours the connector review plan's `approvers()`, **opt-in by configuration** — a
connector with no review plan, or a plan carrying no approver rules, stays open to any permitted
reviewer. Treating "no plan" as "nobody is an approver" would make every un-planned connector
unreviewable on upgrade. Its permission check also moved into the service, since `listPending` is
reachable from the `dashboard` module rather than only through the controller. ⚠️ `apigov` still
hard-codes `STAGE = 1`, so an approver rule with `stage > 1` on a connector's plan is unreachable.

---

### Access recertification campaigns (AF-384)

The `attestation` module adds recurring **access-recertification campaigns** so datasource owners
periodically attest "these users still need this access" — the access-governance control SOX / SOC2 /
ISO 27001 auditors require. It depends only on the `core.api`, `audit.api`, and `scheduling.api`
exposed interfaces.

**Open (snapshot).** `AttestationLifecycleService.openCampaign` (idempotent, row-locked, one
transaction) flips `SCHEDULED → OPEN` and snapshots the current standing grants into
`attestation_item` rows: a `DATASOURCE`-scoped campaign reads
`DatasourceAdminService.listPermissions(datasourceId, orgId)`; an `ORGANIZATION`-scoped one iterates
every active datasource (`DatasourceLookupService.findActiveRefsByOrganization`). Each item
denormalizes the grant (subject, capabilities, expiry) plus a full `permission_snapshot` JSONB so the
evidence survives even after the grant is revoked/deleted. A `UNIQUE(campaign_id, permission_id)`
constraint backstops a re-run. It then publishes `AttestationCampaignOpenedEvent`, consumed by the
`notifications` module (`ATTESTATION_CAMPAIGN_OPENED` multi-channel fan-out to eligible reviewers +
admins) and the `realtime` module (`attestation.campaign_opened` WebSocket event).

**Review.** `AttestationReviewService` mirrors the query review queue: reviewers `certify`/`revoke`
each item, with bulk support (per-row independent, non-transactional). Eligibility derives from the
item's datasource reviewers via `ReviewerEligibilityService.findEligibleReviewerIds`, falling back to
active org admins when a datasource has none. **Self-review is unconditionally blocked** at the
service layer — a reviewer can never attest their own grant. A `REVOKE` decision routes through
`DatasourceAdminService.revokePermission` (hard-delete), tolerating an already-absent permission
(an out-of-band admin/JIT revoke is treated as a successful `REVOKED` — the intent is met).

**Close.** `AttestationCampaignCloseJob` closes `OPEN` campaigns past `due_at`:
`AttestationLifecycleService.closeCampaign` (idempotent) sweeps every still-`PENDING` item per the
campaign's `pending_default` — `REVOKE` revokes the grant, `KEEP` certifies it — recording each as
`AUTO_DEFAULT_REVOKE` / `AUTO_DEFAULT_KEEP`.

**Evidence.** `AttestationEvidenceExportService` streams a CSV of every item (subject, capabilities,
decision, who decided, when), capped at `accessflow.attestation.max-evidence-rows` (default 50000;
beyond it the export is flagged truncated). The HTTP export (ADMIN or AUDITOR) writes an
`ATTESTATION_EVIDENCE_EXPORTED` audit row. Since #625 the CSV also carries the five `usage_*` columns
frozen on each item — an auditor asking "why was this certified?" needs the picture the reviewer had
at decision time, not today's usage.

### Least-privilege intelligence (#625)

The `access` module folds `audit_log` execution history into **per-standing-grant usage evidence** and
derives a revocation recommendation from it, so an admin can see which grants are unused or far wider
than the work they support. Both grant kinds are covered — `datasource_user_permissions` (scope =
`allowed_tables`) and `api_connector_user_permissions` (scope = `allowed_operations`); group-inherited
grants (AF-530/531) are not. The read model is `grant_usage_summary`, one row per live grant, refreshed
by `GrantUsageAggregationJob` (see [§ Scheduled jobs](#scheduled-jobs-and-clustering)).

**Where the usage comes from.** `audit.api.GrantUsageAuditAggregationService` is the only reader — a
sibling of the UBA aggregation rather than an extension of it, because UBA asks "what did this known
subject do on this one datasource" while this asks "which grants were exercised at all", across both
resource kinds. It lives in the `audit` module so JSONB `metadata` parsing stays with the table that
owns it, and it reads only, which is what keeps it compatible with the SELECT-only application role on
`audit_log`. It counts the **four execution actions** — `QUERY_EXECUTED`,
`QUERY_BREAK_GLASS_EXECUTED`, `API_REQUEST_EXECUTED` and `API_REQUEST_BREAK_GLASS_EXECUTED`.
Break-glass is deliberately included: emergency access is unambiguously use of a grant, and omitting it
would let a grant exercised only under break-glass look abandoned. `QUERY_FAILED` is excluded — it
carries no referenced tables, and a query that did not run is not evidence that its granted scope is
needed. Exercised scope comes from metadata only, never result data: `referenced_tables` (AF-383) for a
datasource event, and for a connector event the new `operation_id` enrichment that
`DefaultApiRequestService` writes on `API_REQUEST_EXECUTED` / `API_REQUEST_BREAK_GLASS_EXECUTED` — the
connector-side analogue of `referenced_tables`, nullable because an ad-hoc call need not resolve to a
catalogued operation. Every mapping step is fail-soft: an unparseable row, a missing resource id, or a
row predating an enrichment yields nothing or an event with **empty** targets, which downstream reads
as "used, scope unknown" rather than "used nothing".

**The recommendation ladder.** `GrantUsageRecommender` is a pure function of its inputs — no Spring, no
repository, no clock of its own — so every verdict and boundary is unit-testable. In order:
`ACTIVE` / `OVER_SCOPED` when the last use is within `staleness-threshold`; otherwise
`INSUFFICIENT_DATA` when the observation window is shorter than `min-observation-window`; otherwise
`NEVER_USED` when nothing was ever observed and `STALE` when something was. Two conservatism rules
shape that ordering, and both exist so the ladder never recommends revoking a grant that is genuinely
in use:

- **Recent use beats youth.** A grant created yesterday and used today is `ACTIVE`, not
  `INSUFFICIENT_DATA` — the observation-window guard applies only when there is nothing recent to
  report.
- **Unknown scope is never over-scope.** `OVER_SCOPED` requires a scope-limited grant *and* at least
  one granted target observed. An unrestricted grant (`granted_target_count` null, not zero) has no
  scope to under-use, and a grant known only through audit rows predating the target enrichment has
  `used_target_count = 0` with a non-zero usage count — reporting either as over-scoped would be
  fabricating evidence.

Exercised scope is recomputed each tick against the **live** allow-list rather than a stored count, so
a query outside the allow-list (possible for a `QUERY_ADMIN` holder, whose submissions bypass the
per-grant check) can never inflate the figure past the grant's own scope, and a shrunken allow-list is
reflected immediately.

**Advisory only, structurally.** Nothing in the product acts on a `GrantUsageRecommendation`. It is
read by the over-provisioned report, by attestation reviewers, and by the `GRANT_STALE` nudge — never
by routing policies, grant-covered auto-approval, break-glass, or any other decision path. The
staleness nudge is addressed to org **administrators**, not to the grant holder: admins are the party
who can act on it, and telling one user about another's inactivity would leak activity data across the
tenant.

**Attestation enrichment.** `AttestationLifecycleService.openCampaign` calls
`GrantUsageService.findFor` per item and freezes the evidence onto the `attestation_item` row
(`usage_last_used_at`, `usage_count`, `usage_granted_target_count`, `usage_used_target_count`,
`usage_recommendation`), and the reviewer worklist defaults to staleness-first ordering (never-used
before merely-idle, then longest-idle, `id` breaking ties so no row can swap between pages and be
skipped) — an explicit `?sort=` still wins. `findFor` returns an `Optional` and the absent case must be
rendered as "no data", never defaulted to zero: a grant not yet summarised looks identical in the data
to one never used, and the two point a reviewer in opposite directions.

**Report and export.** `GrantUsageService.report` serves the paginated, filterable over-provisioned
report and `GrantUsageExportService` renders the same rows as CSV, capped at
`accessflow.access.usage.max-report-rows` (default 50000; the exporter fetches `cap + 1` to tell a full
page from "exactly cap rows exist", and flags the result truncated rather than silently shipping a
partial inventory). Both are gated on the new `ACCESS_USAGE_REPORT_VIEW` permission (ADMIN and
AUDITOR); the export writes an `OVER_PROVISIONED_ACCESS_EXPORTED` audit row (resource type
`GRANT_USAGE_SUMMARY`) carrying the row count, the truncation flag and the applied filters. Endpoints:
[docs/04-api-spec.md → Over-Provisioned Access Endpoints](04-api-spec.md#over-provisioned-access-endpoints-625).

`ScheduledQueryRunJob` implements query scheduling (AF-345): a submitter may include `scheduled_for` on `POST /queries` to defer execution. The query still goes through the normal AI / review flow; once it reaches `APPROVED`, the job picks it up at the next tick where `scheduled_for ≤ now()` and calls `QueryLifecycleService.executeScheduled(id)`. That method bypasses the per-user ownership guard (the actor is the scheduler, not a request principal), records the submitter as the audit actor, and tags the audit metadata with `"trigger": "scheduled"`. The job is idempotent — if the query is no longer `APPROVED` (manual execute / cancel raced the tick), the lifecycle service logs and returns without firing.

`RecurringQueryRunJob` implements recurring approved queries (#627): a submitter may include `recurrence_rule` (6-field Spring cron, evaluated in UTC, or an ISO-8601 duration) plus a mandatory `recurrence_until` expiry on `POST /queries`; reviewers approve query + cadence + expiry **once**. After approval the job picks up due parents (`status = APPROVED AND recurrence_next_run_at ≤ now()`, backed by a partial index) and calls `QueryLifecycleService.executeRecurringOccurrence(id)`, which (1) **re-checks fail-closed with current state** — active submitter, active datasource, SQL re-parse (a parse failure would otherwise vacuously pass the table allow-list), and the submitter's live permission (existence, expiry, capability, allow-list; skipped for submitters whose effective role holds `QUERY_ADMIN`, mirroring submission) — halting the series on any failure (`recurrence_next_run_at` cleared, `recurrence_halted_reason` persisted, `RECURRING_SERIES_HALTED` audit row); (2) creates a child occurrence row copied from the parent, **directly in `APPROVED`** with `submission_reason = RECURRING` and `recurring_parent_id` set, atomically advancing the parent's cursor in the same transaction via a locked compare-and-set on the observed due value (racing ticks and crashes can never double-fire an occurrence — a crash mid-execution may leave one orphan APPROVED child that is never re-picked and cannot be executed manually); and (3) executes the child through the full proxy pipeline with `"trigger": "recurring"` audit metadata. The parent stays `APPROVED` for the series' lifetime; missed occurrences are never backfilled (the cursor always advances from *now*); once `recurrence_until` passes the cursor is cleared with no halt reason (derived "Series completed"). Occurrence results are delivered to the submitter via the `QUERY_EXECUTED` notification event (email carries a capped `results.csv`; see [docs/08-notifications.md](08-notifications.md)). Kill-switches: the submitter — or, uniquely for recurring series, any `QUERY_REVIEW` holder — may cancel the parent (`POST /queries/{id}/cancel`); a recurring parent can never be executed manually (`POST /{id}/execute` returns 409) because that would consume its `APPROVED` status and silently end the series.

`BehaviorAnomalyDetectionJob` implements behavioural anomaly detection (UBA, AF-383): each tick it advances each `(user, datasource)` baseline watermark over the configured `accessflow.ai.anomaly.lookback-window`, aggregating new windows from `audit_log` metadata (never query result data) into `behavior_baseline`, then runs statistical detection on the freshest window and inserts `behavior_anomaly` rows for out-of-pattern features. It swallows per-row `RuntimeException`s so one bad principal cannot abort the batch. See [§ Behavioural anomaly detection (UBA)](#behavioural-anomaly-detection-uba-af-383).

`RetentionPolicyScanJob` implements the retention scan (AF-499, the `lifecycle` module): each tick it loads enabled `retention_policies`, computes eligibility per policy via the proxy's non-committing dry-run (`LifecyclePreviewCalculator`), and stages a `lifecycle_runs` row (`STAGED`) for each policy with eligible rows and no pending run yet. It is idempotent (a policy with an existing `STAGED` run is skipped) and swallows per-policy `RuntimeException`s so one bad policy cannot abort the batch. Each cycle publishes a `LifecycleScanCompletedEvent` consumed by the notifications module.

`ScheduledGroupRunJob` / `GroupTimeoutJob` drive deferred grouped requests (AF-501, the `requestgroups` module) — see [§ Request chaining & grouping](#request-chaining--grouping-af-501). The run job scans `APPROVED` groups whose `scheduled_for ≤ now()` and executes their ordered sequence via `GroupExecutionService`; the timeout job auto-rejects `PENDING_REVIEW` groups past the review timeout to `TIMED_OUT`. Both are idempotent and swallow per-group `RuntimeException`s so one bad group cannot abort the batch.

`GrantUsageAggregationJob` refreshes the per-grant usage summaries behind least-privilege intelligence (#625) — see [§ Least-privilege intelligence](#least-privilege-intelligence-625). It loops the active organizations and runs four phases per organization, each in one transaction: (1) **reconcile + backfill** — upsert one `grant_usage_summary` per live standing grant across both resource kinds, delete rows whose grant is gone, and replay `[observed_since, cursor)` for rows summarised for the first time (without that replay a grant created after the cursor moved forward would read as never-used forever); (2) **fold** new `audit_log` execution events from the cursor forward into the counters, `first_used_at` / `last_used_at`, and the exercised-target set; (3) **recompute** every recommendation, so a threshold change or the mere passage of time is reflected without waiting for new activity; (4) **nudge** — publish `GrantStaleEvent` for `NEVER_USED` / `STALE` rows outside `accessflow.access.usage.nudge-cooldown`, stamping `nudged_at`. The transaction is what makes the nudge safe at all: the notifications listener is an AFTER_COMMIT `@ApplicationModuleListener`, and an event published outside a transaction would be dropped silently. Per-organization `RuntimeException`s are logged and skipped so one bad tenant cannot abort the batch; without the `@SchedulerLock` every replica would fold the same audit window and multiply every usage count.

The fold cursor (`grant_usage_watermark`) is **per organization, not per summary row**, and that is forced by the read shape rather than chosen: `audit_log` is indexed on `(organization_id, created_at DESC)` with **no index on `action`**, and since `V38` the table is owned by the dedicated audit role while Flyway runs as the application role — so no index can ever be added, and an org-scoped range read is the only shape that stays off a sequential scan. One such read advances every row in the organization together, which is why a newly-created row needs the explicit backfill above; keeping the cursor out of the summary row is what makes that gap visible rather than silent. When a tick comes back with a **full page** (`accessflow.access.usage.max-rows-per-tick`), the window was not drained, so the cursor advances to the last event's timestamp + 1 ms instead of to the window end — resuming from the window end would silently skip everything after the last event seen; the +1 ms keeps the half-open read from re-serving that final row forever.

To add a new job: place the `@Component` under `<module>/internal/scheduled/`, annotate the method with `@Scheduled` + `@SchedulerLock(name = "<unique>")`, and document the row above. Lock-name conventions: short camelCase (`<jobName>`); never reuse a name across modules. The `scheduling` module's `LockProvider` is picked up automatically — no extra wiring needed.

---

## Data Lifecycle Manager (AF-499)

The `lifecycle` module (`com.bablsoft.accessflow.lifecycle`) governs **data lifecycle** — retention and
right-to-erasure — atop the existing access-governance primitives. It depends only on `core.api`,
`audit.api`, `scheduling.api`, and `proxy.api` exposed interfaces.

**Retention policies.** An admin declares a per-datasource `retention_policies` row targeting a
table / column-set / classification tag, a retention window (ISO-8601 period/duration) measured
against a timestamp column, and an action (`HARD_DELETE` / `SOFT_DELETE` / `PSEUDONYMIZE` — the last
carrying a `lifecycle_transform`). CRUD lives at `/api/v1/lifecycle/policies` (`RetentionPolicyService`
→ `DefaultRetentionPolicyService`, ADMIN-gated). `POST …/{id}/preview` returns the dry-run impact
(matched tables, best-effort estimated rows via `proxy.api.QueryDryRunService`, method) without
executing. `RetentionPolicyScanJob` stages eligible runs (see [§ Scheduled jobs](#scheduled-jobs-and-clustering)).

**Right-to-erasure.** `deletion_requests` flow an erasure state machine
(`PENDING_SCOPE_AI → PENDING_REVIEW → APPROVED → EXECUTED`, + `REJECTED`/`FAILED`/`CANCELLED`) mirroring
the query-review lifecycle, with async scope detection (`ErasureScopeAnalyzer`, the AI plug-in point)
and human approval (submitter can never self-approve).

**Read-time pseudonymization (proxy-enforced).** `LifecycleDirectiveResolutionService`
(`lifecycle.api`, depended on by `workflow`) turns each enabled `PSEUDONYMIZE` retention policy into
post-fetch `ColumnMaskDirective`s — one per target column — which `DefaultQueryLifecycleService` merges
alongside the masking-policy directives before execution, so the shared `core.api.ColumnMasker`
applies them. The `LifecycleTransform` maps onto the masker: `SHA256_SALTED` / `TOKENIZATION` → a
salted SHA-256 (the per-org salt is added via a new optional `salt` param on the `HASH` strategy — a
backward-compatible extension; `TOKENIZATION` uses a `tok:`-prefixed salt to stay distinguishable),
`FORMAT_PRESERVING` → the format-preserving strategy. The per-org salt is owned by `LifecycleSaltService`
(lazily created, AES-256-GCM encrypted in `lifecycle_salt`, rotatable — rotation bumps `version` while
previously hashed values stay hashed). Because masking preserves row presence, counts/aggregates
survive while PII is irreversibly transformed.

**Soft-delete enforcement (proxy-enforced).** Each enabled `SOFT_DELETE` retention policy contributes,
through the same `LifecycleDirectiveResolutionService`, (1) a read filter — an `IS_NULL`
`RowSecurityDirective` on the marker column (default `deleted_at`, overridable per policy) merged into
the row-security predicates so soft-deleted rows are invisible to SELECT/UPDATE/DELETE — and (2) a
`SoftDeleteDirective` carried on the `QueryExecutionRequest`. The `RowSecurityRewriter` rewrites a plain
`DELETE FROM t [WHERE …]` against a soft-delete target into `UPDATE t SET <marker> = CURRENT_TIMESTAMP …`
before injecting the row-security predicates (so the soft-delete `UPDATE` is itself scoped to live
rows); `DELETE … USING` / multi-table deletes are rejected fail-closed
(`UnrewritableRowSecurityException`, 422). `IS_NULL` is a new unary `RowSecurityOperator` that binds no
parameter — it is never persisted by row-security policies, only synthesised here.

**Retention-adherence compliance report.** A `ComplianceReportType.RETENTION_ADHERENCE` joins the
compliance suite: `DefaultComplianceReportService` reads `lifecycle_runs` over the period through the
`lifecycle.api.LifecycleRunLookupService` (compliance → lifecycle.api, acyclic) and renders the
deletion-history rows to the existing signed PDF/CSV export. Served at
`GET /admin/compliance/reports/retention-adherence` (+ the generic signed `…/export?type=…`).

**Lifecycle notifications.** Approving an erasure publishes `ErasureRequestApprovedEvent`; the
notifications module's `NotificationListener` consumes it and dispatches an `ERASURE_APPROVED`
notification to the submitter over their chat + in-app channels (`buildLifecycleErasure` resolves the
submitter as the recipient). notifications → lifecycle.events only (acyclic).

**Approved-erasure execution.** `ErasureExecutionJob` (clustered-safe; see
[§ Scheduled jobs](#scheduled-jobs-and-clustering)) picks up `APPROVED` requests and runs
`ErasureExecutionService.execute`: for each table in the immutable scope snapshot it issues a
governed `DELETE FROM <table>` through `proxy.api.QueryExecutor`, scoping it to the subject with a
**parameter-bound** `RowSecurityDirective` (`<subjectColumn> = <subjectIdentifier>` — the value is
bound, never concatenated; the table is validated as a simple identifier). The datasource's
`SOFT_DELETE` policies turn matching DELETEs into marker updates automatically (reusing the proxy
rewrite). It writes a `lifecycle_runs` row + a tamper-evident `DATA_ERASURE_COMPLETED`
proof-of-deletion audit row (affected rows, tables, method), then transitions the request to
`EXECUTED` (or `FAILED` with the offending tables). Per-table failures are isolated so one bad table
fails only that request. The subject-linking column is currently derived from `subject_type`
(`EMAIL`→`email`, `USER_ID`→`user_id`); AI-assisted per-table column detection is a follow-up.

**Configurable & request-based erasure (AF-519).** Both the admin retention rule and the user erasure
request share one richer configuration shape (target table/columns + structured conditions + a raw
WHERE escape hatch), compiled by the single `ErasurePredicateCompiler`: each structured
`ErasureCondition` (`lifecycle.api`, AND-combined) becomes a **bound** `RowSecurityDirective` (values
are JDBC-bound, negation flips to the complementary operator), the retention age window is an inlined
UTC-constant clause, and the `rawWhere` is validated via `proxy.api.SqlParserService` (JSqlParser) and
inlined as an ANDed clause — never concatenating user values. `ErasureConditionValidator` rejects
conditions/raw WHERE on non-SQL datasources and bad operator arity / unparseable WHERE / bad cron at
save/submit time (`InvalidErasureConfigException` → 422). **Retention execution is now wired**:
`RetentionPolicyScanService` gates staging on the optional per-policy cron
(`CronExpression.next(lastRunAt)`, advancing `last_run_at`/`next_run_at`), and the new
`RetentionPolicyExecutionService`/`RetentionPolicyExecutionJob` drain STAGED `RETENTION_POLICY` runs
through the proxy — HARD_DELETE issues a governed `DELETE`, SOFT_DELETE relies on the datasource's
soft-delete rewrite, and PSEUDONYMIZE is recorded as read-time-enforced (no destructive batch write,
consistent with the read-time masking model) — writing a `RETENTION_POLICY_EXECUTED` audit row.
`ErasureScopeAnalyzer` derives scope from the request's own `target_table` when present (else the
enabled-policy derivation), and `ErasureExecutionService` builds predicates from the request's config
(a subject-only request is byte-for-byte the pre-AF-519 single-directive path).

**Review-plan-based erasure review (AF-519).** `DefaultErasureReviewService` was reworked from
admin-only, single-stage to mirror `DefaultAccessReviewService`: it resolves the datasource
`ReviewPlanSnapshot`, computes the current stage from recorded decisions, checks the caller is a plan
approver at that stage within the datasource's scoped-reviewer set (`ReviewerEligibilityService`), and
treats any ADMIN as a backstop approver (finalising with `minApprovals=1, isLastStage=true` when the
plan does not route to them). The **self-approval guard is preserved** (`ErasureSelfApprovalException`).
The package-private `ErasureRequestStateService` (mirrors `AccessGrantRequestStateService`) is the sole
owner of `deletion_requests.status` for review transitions, pessimistic-locked and idempotent on
`(request_id, reviewer_id, stage)`. The reviewer surface moved to REVIEWER-reachable
`/api/v1/lifecycle/erasure-reviews` (`ErasureReviewController`, `hasAnyRole('REVIEWER','ADMIN')`), and
`ErasureReviewTimeoutJob` auto-rejects requests stuck in `PENDING_REVIEW` past
`accessflow.lifecycle.review-timeout`. Cross-engine (NoSQL) conditions are out of scope for v1.

---

## JIT time-bound access requests (AF-378, AF-567)

The `access` module (`com.bablsoft.accessflow.access`) lets users self-request temporary, scoped access — to a **datasource** (AF-378) or an **API connector** (AF-567) — that is granted on approval and auto-revoked on expiry. A request targets exactly one resource kind (`AccessResourceKind`), enforced by Bean Validation (`@ExactlyOneResource`) and the V113 DB CHECK.

**Approval reuses query-review machinery.** `DefaultAccessReviewService` mirrors `DefaultReviewService`: it resolves the resource's `ReviewPlanSnapshot` — the datasource's plan via `core.api.ReviewPlanLookupService.findForDatasource`, or, for connector requests, the connector's `review_plan_id` via `apigov.api.ApiConnectorLookupService.findRef` + `ReviewPlanLookupService.findById` (the same plan that gates the connector's governed API calls) — computes the current stage from the recorded `access_grant_decision` rows, checks the caller is an approver at that stage and (datasource requests only) within the datasource's scoped-reviewer set (`core.api.ReviewerEligibilityService`; reviewer scoping is a datasource-only concept, so connector requests skip that filter), and **blocks self-approval at the service layer** (`requesterId == reviewerId` → `AccessDeniedException`). Multi-stage chains are supported exactly as for queries — only the final stage transitions the request to `APPROVED`. The state primitive `AccessGrantRequestStateService` (pessimistic row lock, idempotent replay on `(requestId, reviewerId, stage)`) is the sole owner of `access_grant_request.status`.

**Admin fallback (the backstop approver).** Because a datasource or connector can be created with no `review_plan_id` (and `submit()` does not require one), a request could otherwise land where no plan approver exists — invisible and un-actionable. To prevent that, `DefaultAccessReviewService` treats any `ADMIN` as a universal approver: `listPendingForReviewer` returns **every** `PENDING` request in the org for an admin (self-requests still excluded; `toPendingAccessRequest` tolerates a null plan, reporting stage `0`), and `prepareDecision` permits an admin who is *not* plan-eligible (no plan, foreign-org plan, out of scope, or not a named approver) to decide the request — `approve()` then issues the command with `minApprovalsRequired=1, isLastStage=true` so the single admin approval finalises and materialises the grant. When the plan *does* route to the admin as a configured stage approver, the normal multi-stage path is taken instead (no short-circuit). `REVIEWER`s stay strictly plan-gated — the self-approval block applies to admins too.

**Connector submission validation (AF-567).** A connector request must target an active connector in the caller's organization (`apigov.api.ApiConnectorLookupService.findActiveRefsByOrganization`, mirroring the datasource check — deliberately not permission-gated, JIT exists to obtain access you don't have) and its optional `allowed_operations` entries must exist in the connector's operation catalog (`apigov.api.ApiSchemaService.listOperations`; unknown ids → `InvalidAccessOperationsException`, HTTP 422). The requestable-connector list and per-connector operation catalog are served by `GET /access-requests/connectors{,/{id}/operations}`.

**Grant materialisation.** On final-stage approval, `approve()` runs `AccessGrantMaterializer` inside the same transaction so approval + grant commit atomically. The materializer computes `expires_at = now + Duration.parse(requested_duration)` and branches on the resource kind: datasource requests call `core.api.DatasourceAdminService.grantPermission(...)`; connector requests call `apigov.api.ApiConnectorAdminService.grantPermission(...)` with `canRead`/`canWrite`, the request's `allowed_operations`, and **never** break-glass or response-field restrictions. The new permission id is stored on the request.

**Pre-existing-permission policy.** If the requester already holds a **direct** permission on the resource (group grants are never considered or touched): a **standing** permission (`expires_at == null`, admin-granted) is never silently deleted — the materializer throws `AccessGrantAlreadyExistsException` (HTTP 409; the datasource path uses `core.api.DatasourceUserPermissionLookupService.findDirectFor`, the connector path `apigov.api.ApiConnectorPermissionLookupService.findDirectFor`). Another **time-boxed** (JIT) permission is replaced so the new grant's capabilities/expiry take effect (extend/widen) — revoke-then-grant on the datasource path, the `(connector_id, user_id)` upsert of `grantPermission` on the connector path. This keeps standing access safe while letting JIT grants stack predictably. (See [docs/07-security.md](07-security.md).)

**Expiry & revoke.** `AccessGrantExpiryJob` (see "Scheduled jobs" above) revokes grants past `expires_at` → `EXPIRED`. An admin may early-revoke an active grant (`POST /admin/access-requests/{id}/revoke`) → `REVOKED`. Both paths revoke the materialised permission — deleting the `datasource_user_permissions` or `api_connector_user_permissions` row by kind (tolerating an already-deleted row) — and publish events consumed by the notifications + realtime modules. Effective-permission resolution needs no special handling: `EffectiveApiConnectorPermissionResolver` already excludes rows past `expires_at`, so a connector JIT grant stops resolving the moment it expires even before the job deletes it.

**Module boundaries.** `access → core.api`, `apigov.api` (AF-567 — bare UUIDs across the boundary, no JPA relation), `audit.api`; `notifications`/`realtime`/`audit` read access data through `access.api.AccessRequestLookupService` (and never reach into `access.internal`). `access` only *publishes* events to notifications/realtime, so there is no cycle. In-app + WebSocket notifications are delivered for access events (`AccessNotificationListener` + `RealtimeEventDispatcher`); payloads carry `resource_kind` plus the datasource or connector name. Per-channel email/Slack delivery for access events is a follow-up.

### Grant-covered query auto-approval (#582)

A grant that only conveys *submission* rights still forces every query through human review — the same access reviewed twice. The requester can therefore opt a request into **query pre-approval** via the `pre_approve_queries` flag (default `false`; a checkbox on the Request Access form, echoed as a highlighted tag on the reviewer's queue so the approver sees exactly what they authorize).

While such a grant is `APPROVED` and unexpired, `QueryReviewStateMachine.tryGrantFastPath` runs on the AI-completed and AI-skipped paths — **after** routing-policy evaluation returned no match (any matching policy, including `AUTO_REJECT` and anomaly-driven `ESCALATE`, wins) and **before** the plan fall-through. The fast-path transitions `PENDING_AI → APPROVED` when **all** of the following hold:

- no open behavioural anomaly for the requester on the datasource (`ConditionContext.anomalyActive` — UBA, AF-383);
- the AI risk level is LOW/MEDIUM, or absent because the datasource has `ai_analysis_enabled=false` (HIGH/CRITICAL falls through to normal review; the AI-*failed* path never runs the fast-path, consistent with routing);
- the requester holds an `APPROVED`, unexpired grant on the datasource with `pre_approve_queries=true` (`access.api.AccessGrantLookupService.findActivePreApprovedGrants` — expiry/revocation flip the grant status, so the fast-path shuts off immediately);
- the grant **covers** the query: its capability matches the query type (SELECT→`can_read`, DML→`can_write`, DDL→`can_ddl`) and every referenced table is inside its `allowed_schemas`/`allowed_tables` (the same `DatasourcePermissionChecker` semantics as the submission gate). The SQL is **re-parsed inside the fast-path and a parse failure fails closed** — the routing context's `referencedTables` is empty on parse failure, which would pass an allow-list check vacuously.

On a match, `core.api.QueryRequestStateService.approveByAccessGrant` stamps `query_requests.approved_by_grant_id` atomically with the transition, and the published `QueryAutoApprovedEvent` carries the grant id + the grant's final-stage approver email — audited as `QUERY_APPROVED` with metadata `{ auto_approved: true, source: "ACCESS_GRANT", access_grant_id, grant_approver, reason }`, so the approval chain stays traceable to the human who approved the grant. `GET /queries/{id}` surfaces the provenance as `approved_by_grant` (resolved at read time from the grant row, which is never deleted), rendered as an alert on `QueryDetailPage`.

---

## Query snapshots & replay (AF-449)

Executed queries are otherwise immutable, but there was no first-class way to take an approved/executed query and **replay its exact SQL against a test datasource** for debugging an approval or satisfying a compliance audit. The `workflow` module adds an immutable snapshot written on execution plus a replay endpoint that re-enters the full review workflow.

**Snapshot on execution.** `QuerySnapshotListener` (`workflow/internal/`) is a plain synchronous `@EventListener` on `QueryExecutedEvent`. It fires only when `finalStatus = EXECUTED` (FAILED executions get no snapshot) and delegates to `DefaultQuerySnapshotService.recordOnExecution(queryRequestId)`, which writes one `query_snapshots` row (see [docs/03-data-model.md → query_snapshots](03-data-model.md)) capturing the exact `sql_text`, the source datasource's schema fingerprint (`SchemaHasher` → SHA-256, best-effort/null on introspection failure), the referenced tables (from `proxy.api.QueryParser`), the AI verdict, and the approval decisions (both read from `core.api.QueryRequestLookupService.findDetailById`). The write is **idempotent** (`existsByQueryRequestId` guard + the `UNIQUE(query_request_id)` backstop) and the service swallows its own failures so snapshot capture can never disrupt execution.

> Why a plain `@EventListener` and not `@ApplicationModuleListener`: `QueryExecutedEvent` is published *outside* a surrounding transaction (the EXECUTED outcome is already committed by `QueryRequestStateService` before the event fires), so an `AFTER_COMMIT` transactional listener would be silently skipped when no transaction is active — the snapshot would never be written. A synchronous listener fires unconditionally, reads the now-committed query / AI / decision rows via fresh transactions, and guarantees the snapshot exists the moment `execute()` returns, so an immediate replay never races a missing snapshot.

**Replay.** `POST /queries/{id}/replay?targetDatasourceId=…` (`QueryReplayController` → `DefaultQueryReplayService`) loads the snapshot (org-scoped; absent → `QuerySnapshotNotFoundException` → 404, which naturally rejects never-executed queries), resolves the target datasource (its own org-scoped not-found → 404, and enforces the caller's visibility/permission), then validates schema compatibility:

- **Engine family** — the target's `db_type` must equal the snapshot's, else `ReplaySchemaIncompatibleException` (422).
- **Referenced tables present** — a fresh introspection of the target must contain every table the query references (`ReplaySchemaMatcher`, normalising `schema.table`/bare `table`); missing tables → 422. The full schemas need not match (a test DB legitimately diverges) — only the referenced tables. If the target cannot be introspected the replay is **rejected fail-closed** (422) rather than skipping the check.

It then re-submits through the existing `QuerySubmissionService.submit(...)` with the **caller** as submitter, the target datasource, the snapshot's SQL, `scheduledFor=null` (a stale schedule never re-arms), and `SubmissionReason.USER_SUBMITTED`. The new query enters the normal `PENDING_AI → review` pipeline — **approval is never bypassed**, and because the submitter is the replaying caller, `DefaultReviewService`'s self-approval guard still prevents them from approving their own replay. The controller records a `QUERY_SUBMITTED` audit row on the **new** query id with metadata `{ trigger: "replay", original_query_id, source_datasource_id, target_datasource_id, source_schema_hash, target_schema_hash }` — mirroring the `trigger=scheduled` convention so an auditor can both distinguish a replay and see whether the schema drifted. No new `AuditAction` or `SubmissionReason` enum value is introduced.

---

## Query templates (AF-364)

`workflow.api.QueryTemplateService` and its `Default*` implementation own the saved-snippets library exposed at `/api/v1/query-templates`. Templates are a pure save / load surface — submission still flows through `POST /api/v1/queries` unchanged. `:identifier` placeholders in the body are stored verbatim; the editor parses them and substitutes values on the client before submit, so there is no template-aware parameter binding on the backend.

**Module placement.** The entity, repository, specifications, mapper, and service live in `workflow.internal.*`; the controller and DTOs in `workflow.internal.web.*`. The entity references `organization_id`, `owner_id`, and `datasource_id` as raw `UUID` columns (no `@ManyToOne` to `core.internal` entities) — keeps the modulith green and decouples the persistence layer from cross-module joins.

**Visibility enforcement** is implemented by `DefaultQueryTemplateService`, not the controller — every read passes through `QueryTemplateSpecifications.forList(organizationId, callerUserId, filter)`:

| Operation | Rule |
|---|---|
| `list` | `WHERE organization_id = :org AND (owner_id = :caller OR visibility = 'TEAM')` |
| `get` | Load by id; if `organization_id != caller.org` or (`visibility = PRIVATE` and `owner_id != caller`), throw `QueryTemplateNotFoundException` — existence is not leaked |
| `update` / `delete` | Apply the `get` rule first, then require `owner_id == caller`; non-owner TEAM access throws `QueryTemplateAccessDeniedException` (403, not 404 — the row is already visible) |
| `create` | Inserts `owner_id = caller`; unique index `(organization_id, owner_id, LOWER(name))` enforces per-owner name uniqueness |

**Tag storage** is a native PostgreSQL `text[]` column mapped via Hibernate 6's `@JdbcTypeCode(SqlTypes.ARRAY)` on a `String[]` field — no `hypersistence-utils` dependency. The list endpoint's tag filter uses `array_position(tags, :tag) IS NOT NULL` for index-friendly containment lookups, and the GIN index `idx_query_templates_tags_gin` keeps that path cheap.

**Audit.** Every successful mutation calls `auditLogService.record(...)` with one of `QUERY_TEMPLATE_CREATED`, `QUERY_TEMPLATE_UPDATED`, `QUERY_TEMPLATE_DELETED`, `QUERY_TEMPLATE_RESTORED` and resource type `QUERY_TEMPLATE`.

### Version history & restore (AF-442)

`workflow.api.QueryTemplateVersionService` (+ `DefaultQueryTemplateVersioningService`) own the immutable history table `query_template_versions`. The versioning service implements the public read interface (`listVersions`, `getVersion`) **and** a package-private `QueryTemplateVersionRecorder` (`recordSnapshot`, `requireVersion`) — keeping the entity-typed snapshot methods out of the `api` package so `ApiPackageDependencyTest` stays green.

- **Snapshot on save.** `DefaultQueryTemplateService.create()` records a `CREATED` snapshot; `update()` records an `UPDATED` snapshot **only when the content actually changed** (the recorder compares `name`/`body`/`description`/`tags`/`visibility`/`datasourceId` against the latest snapshot and no-ops otherwise). The insert runs in the caller's transaction — no `REQUIRES_NEW` — so the version commits atomically with the edit.
- **Version numbering** is `max(version_number) + 1` per template (1 when none), with the unique index `(template_id, version_number)` as the race safety-net.
- **Visibility.** Reads enforce the same rule as `QueryTemplateService.get`, evaluated against the **current** parent template — a snapshot's stored `visibility` is never trusted for access control, so a template flipped `TEAM → PRIVATE` cannot leak old TEAM snapshots. Missing versions throw `QueryTemplateVersionNotFoundException` (404).
- **Restore** lives on `QueryTemplateService.restoreVersion` (it is a template mutation): it reuses `loadVisibleOrThrow` + the owner-check + name-uniqueness guard, applies the snapshot's fields to the template, bumps `updated_at`, and records a fresh `RESTORED` snapshot. History is preserved — restore never deletes a version. The dependency direction is one-way (`DefaultQueryTemplateService` → `DefaultQueryTemplateVersioningService`), so there is no Spring bean cycle.

---

## Multi-tenant isolation hardening (AF-456)

A deployment hosts one or more organizations, each scoped by `organization_id`. AF-456 adds a
platform-admin management plane, per-org quotas, and a disabled-org kill-switch. The migration is
`V87__org_isolation_quotas_platform_admin.sql` (adds `organizations.disabled` / `max_datasources` /
`max_users` / `max_queries_per_day` and `users.platform_admin`).

**Platform-admin management plane.** Cross-org tenant CRUD lives behind
`/api/v1/platform/organizations` (`@PreAuthorize("hasAuthority('PLATFORM_ADMIN')")`). `platform_admin`
is an orthogonal boolean on the `users` row, not a fifth role — a platform admin keeps their home-org
role and is additionally granted the `PLATFORM_ADMIN` Spring Security authority; the JWT carries a
`platform_admin` claim and the login / `GET /me` user object includes the boolean. The bootstrap admin
and the first-run setup-wizard admin are provisioned as platform admins (a pre-existing bootstrap admin
is promoted on an upgrade re-run). Each lifecycle mutation is audited against the **target** org
(`ORGANIZATION_CREATED` / `ORGANIZATION_UPDATED` / `ORGANIZATION_DISABLED` / `ORGANIZATION_ENABLED`).

**Quota enforcement (fail-on-breach → 409).** A quota-enforcement service performs count-based checks
at the service layer, at each resource-creation choke point:

| Quota | Checked at | Count basis |
|---|---|---|
| `max_datasources` | Datasource creation | Datasources in the org |
| `max_users` | User creation **and** invitation issuance | Active users in the org |
| `max_queries_per_day` | Query submission | Rolling **trailing-24h** count over `query_requests` — no counter table, no reset job |

`NULL` or `0` means unlimited. A breach throws a domain exception mapped to `409 Conflict` with
`error: "QUOTA_EXCEEDED"` and a localized `detail` naming the limit. Quotas bound consumption — they are
not an access boundary.

**Disabled-org enforcement (immediate per-request block).** `organizations.disabled` is enforced at
two layers:

- **Authentication choke points** — login, refresh, and the OAuth2 / SAML exchange reject a user whose
  org is disabled (local + SSO).
- **The two auth filters** — `JwtAuthenticationFilter` and `ApiKeyAuthenticationFilter` perform a
  lightweight per-request org-status lookup and reject any request whose org is disabled.

There is **no cache**, by design: disabling a tenant takes effect on the next request rather than at
token expiry, so an in-flight session stops working immediately.

**Domain invariants.** Quota = fail-on-breach (409, never silently truncate or queue). Disabled-org =
immediate, per-request block (no grace window, no cache).

---

## Startup bootstrap (env-driven admin config)

The `bootstrap` module ([com.bablsoft.accessflow.bootstrap](../backend/src/main/java/com/bablsoft/accessflow/bootstrap)) reconciles declared admin configuration from `accessflow.bootstrap.*` properties into the database on every backend start. It is the mechanism that lets a Helm/Kubernetes deployment ship organization, admin user, review plans, AI configs, datasources, SAML, OAuth2 providers, notification channels, and system SMTP through GitOps — no admin-API click-ops required.

**When it runs.** `BootstrapRunner` listens for `ApplicationReadyEvent`. When `accessflow.bootstrap.enabled=false` (the default) it returns immediately. Otherwise it acquires the cluster-wide `bootstrapReconcile` lock via `scheduling.api.DistributedLockService` (Redis-backed, key `accessflow:shedlock:bootstrapReconcile`, `lockAtMostFor=10m`) and runs the reconcilers in this fixed topological order:

1. **Organization** — looks up by slug, creates if missing. Slug is derived from `bootstrap.organization.name` when `bootstrap.organization.slug` is blank.
2. **Admin user** — looks up by email. Creates with role=ADMIN if missing. **Does NOT rotate** the password on existing users (operators rotate via the admin API).
3. **Notification channels** — upsert by `(orgId, name)`.
4. **AI configs** — upsert by `(orgId, name)`.
5. **Review plans** — upsert by `(orgId, name)`. Resolves `notifyChannelNames` against step 3 and `approverEmails` against step 2 (or any pre-existing users in the same org).
6. **Datasources** — upsert by `(orgId, name)`. Resolves `reviewPlanName` and `aiConfigName`. `dbType=CUSTOM` is rejected — operators upload CUSTOM JDBC driver JARs through the admin API.
7. **SAML** — singleton per org. Only applied when `bootstrap.saml.enabled=true`.
8. **OAuth2 providers** — upsert by `(orgId, provider)`.
9. **System SMTP** — singleton per org. Only applied when `bootstrap.systemSmtp.enabled=true`.

**Authoritative semantics.** Every restart re-applies the declared spec, overwriting matching rows in the DB. Rows that are NOT declared are left untouched (no destructive cleanup). Operators who edit a declared row through the admin UI will see their change reverted on the next restart.

**Multi-replica safety.** In a Kubernetes Deployment with `replicas > 1`, every backend pod fires `ApplicationReadyEvent` independently. Bootstrap wraps the reconciliation body in a `bootstrapReconcile` Redis lock (`lockAtMostFor=10m`) so exactly one replica per startup wave performs the upserts. The losing replicas log `Bootstrap: another node holds the 'bootstrapReconcile' lock; skipping reconciliation on this replica` at INFO and complete their `ApplicationReadyEvent` without throwing — they stay ready to serve traffic. If the winning replica crashes mid-reconcile, the Redis key expires after `lockAtMostFor`, and the next pod to restart picks up where the previous left off (every reconciler is idempotent). If Redis is unreachable, the lock acquisition throws and the pod fails readiness — the same loud-failure model as `BootstrapException`.

**Failure handling.** If the organization reconciler fails, bootstrap aborts immediately. For every subsequent reconciler, failures are logged at ERROR, collected, and the runner throws a `BootstrapException` at the end — the pod fails its readiness probe so the operator sees the failure in `kubectl describe pod` rather than discovering it through silent half-applied state.

**Module boundaries.** `bootstrap` is a Spring Modulith application module with only an `internal/` package — it has no public API of its own. It depends on the public `api/` packages of `core`, `ai`, `security`, `notifications`, and `scheduling` (for `DistributedLockService`), plus the `audit/events/` named interface (which owns `BootstrapResourceUpsertedEvent` so the consumer doesn't form a cycle back into bootstrap). It reuses each domain's `Default*Service` for encryption / persistence (sensitive fields like API keys, datasource passwords, OAuth2 client secrets, and SMTP passwords are AES-256-GCM encrypted by those services, not by bootstrap).

**Validation parity.** The Helm chart validates required `bootstrap.*` values at `helm template` / `helm install` time (`accessflow.bootstrap.validate` in [_bootstrap-env.tpl](../charts/accessflow/templates/_bootstrap-env.tpl)) so misconfig surfaces at deploy time, not at pod start. The backend re-checks the same invariants in each reconciler to defend against non-Helm install paths.

### Bootstrap audit semantics

Each reconciler that performs a real INSERT or UPDATE publishes a `BootstrapResourceUpsertedEvent` (in `audit/events/`). The audit module's `AuditEventListener` consumes the event and writes an `audit_log` row with `actor_id = NULL` and `metadata.source = "BOOTSTRAP"`, matching the existing system-driven audit pattern (e.g. AI analysis completions, query timeouts). The row participates in the same per-org HMAC-SHA256 chain as user-driven audits, so a mixed run — admin UI edit → backend restart with env vars → admin UI edit — verifies end-to-end via `AuditLogService.verify(orgId, …)`.

**No-op detection.** Reconcilers compute a SHA-256 fingerprint of the canonical-sorted JSON of the env-driven spec and compare it against the previous fingerprint stored in `bootstrap_state` (V41). A match short-circuits both the underlying service upsert and the event publication, so restarting the backend with unchanged env vars produces zero new audit rows. On a fingerprint mismatch (or first-ever run), the change is applied and audited; `metadata.changed_fields` lists the field names that differ between the persisted view and the new spec (best-effort — encrypted fields like passwords are not enumerated in the diff).

**Transactional publish.** Bootstrap events must be published inside the same `@Transactional` boundary that writes the fingerprint, because `@ApplicationModuleListener` fires AFTER_COMMIT; events published outside a transaction are silently dropped. The reconcilers route every publish through `BootstrapStateTracker.recordFingerprintAndPublish(…)` (or `publishWithinTransaction(…)` for resources without a fingerprint, e.g. the admin user) so this invariant holds by construction.

**Resource id conventions.** For normal resources the audit row's `resource_id` is the entity UUID. For singleton-per-org configs (SAML, SystemSmtp) it is the `organization_id`. For per-provider OAuth2 rows it is a deterministic UUID derived via `UUID.nameUUIDFromBytes("OAUTH2:" + provider)` so each provider gets its own `bootstrap_state` row without colliding.

See [docs/09-deployment.md → "Bootstrap configuration"](09-deployment.md#bootstrap-configuration) for the operator-facing env-var reference and the Helm `bootstrap:` values shape.

---

## AI Query Analyzer Service

The `AiAnalyzerService` (`accessflow-ai` module) is pluggable via a strategy interface:

```java
public interface AiAnalyzerStrategy {
    AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                             String language, UUID organizationId);
}
```

`schemaContext` is an opaque, provider-renderable description of the target schema (for example,
the output of `SystemPromptRenderer.describeSchema(...)`). It may be `null` or empty when
introspection is unavailable, in which case the prompt substitutes `(no schema introspection
available)`. `language` is the BCP-47 language code (see *Response language*). `aiConfigId`
identifies the specific `ai_config` row to use — resolved upstream from the datasource's
`ai_config_id` binding.

Three concrete strategy classes (Anthropic, OpenAI, Ollama) live under `ai/internal/`. None of
them is a `@Service` — they are plain classes built by `AiAnalyzerStrategyHolder`, the single
autowired `AiAnalyzerStrategy` bean, from the bound `ai_config` row using Spring AI 2.0
(`spring-ai-bom:2.0.0` — `spring-ai-starter-model-anthropic`, `…-openai`, `…-ollama`).
`OpenAiAnalyzerStrategy` is reused for three providers (`OPENAI`, `OPENAI_COMPATIBLE`,
`HUGGING_FACE`) since they share the OpenAI chat-completions wire format:

- `AnthropicAnalyzerStrategy` — `AnthropicChatModel` built programmatically from the row's
  provider / model / API key / timeout. The base URL comes from Spring AI's built-in default;
  the `ai_config.endpoint` column is ignored for this provider. Default boot model:
  `claude-sonnet-4-20250514`.
- `OpenAiAnalyzerStrategy` — `OpenAiChatModel`. Serves three providers: `OPENAI` (Spring AI's
  built-in default base URL; `ai_config.endpoint` ignored; default boot model `gpt-4o`),
  `OPENAI_COMPATIBLE`, which passes `ai_config.endpoint` to the OpenAI client as a custom base URL
  so any OpenAI API–compatible backend works (vLLM, LM Studio, Together, Groq, OpenRouter, …), and
  `HUGGING_FACE`, which points the same client at the Hugging Face Inference Providers router
  (`https://router.huggingface.co/v1` by default — authenticated with a HF token) or, via a custom
  base URL, at a **local / self-hosted Text Generation Inference (TGI ≥ 1.4)** server or a Dedicated
  Inference Endpoint. `OPENAI_COMPATIBLE` requires an `endpoint`; `HUGGING_FACE` defaults it to the
  router. Both may run keyless — when no API key is stored, the holder substitutes a non-secret
  placeholder so the client still constructs (this is how local tokenless TGI works). Default boot
  model for `HUGGING_FACE`: `meta-llama/Llama-3.3-70B-Instruct`. The configured provider is recorded
  on each `ai_analyses` row.
- `OllamaAnalyzerStrategy` — `OllamaChatModel`. Keyless; needs only `endpoint` (default
  `http://localhost:11434`).

### Runtime strategy refresh

`AiAnalyzerStrategyHolder` caches one delegate per `ai_config` row (`Map<UUID aiConfigId,
AiAnalyzerStrategy>`). On a successful `PUT /api/v1/admin/ai-configs/{id}`,
`DefaultAiConfigService` publishes an `AiConfigUpdatedEvent`. On `DELETE`, it publishes an
`AiConfigDeletedEvent`. Both are consumed via `@ApplicationModuleListener` (so they fire after
the transaction commits) and the cached delegate for that id is evicted — the next
`analyze(...)` call rebuilds against the new (or absent) row. No application restart, no
Spring context refresh.

### Editable system prompt

`SystemPromptRenderer` holds the built-in analyzer prompt (`DEFAULT_TEMPLATE`) and renders it
with named placeholders substituted at call time: `{{db_type}}`, `{{schema_context}}`,
`{{rag_context}}`, `{{cost_estimate}}` (AF-624 — the pre-flight estimate summary, falling back to
`(no cost estimate available)`), `{{sql}}` and `{{language}}`. `{{sql}}` is replaced last so SQL
text that happens to contain another token string is never re-substituted.

Admins may override the prompt per `ai_config` row via the `system_prompt_template` column
(`NULL`/blank ⇒ use `DEFAULT_TEMPLATE`). `DefaultAiConfigService` validates that a custom template
contains `{{sql}}` — otherwise the model never sees the query — throwing
`AiConfigInvalidPromptException` (HTTP 400 `AI_CONFIG_INVALID_PROMPT`). The holder threads the
row's template into the per-row strategy delegate, so the same `AiConfigUpdatedEvent` eviction
above picks up a prompt change at runtime (the event's `promptChanged` flag is also logged). The
admin UI fetches the default for pre-fill / reset via `GET /admin/ai-configs/prompt-default`
(`AiConfigService.defaultSystemPromptTemplate()`).

The analyzer service resolves which row to use by reading
`DatasourceConnectionDescriptor.aiConfigId` from `DatasourceLookupService.findById(...)`. Two
opt-out paths exist:

- `ai_analysis_enabled = false` — the listener publishes `AiAnalysisSkippedEvent` (see the
  state-machine section above) which advances the query out of `PENDING_AI` without persisting
  any `ai_analyses` row. The editor preview still rejects with `AiAnalysisException`
  (`analyzePreview` requires AI to be enabled).
- `ai_config_id is null` while `ai_analysis_enabled = true` — treated as an admin
  misconfiguration: the listener persists a sentinel `CRITICAL` analysis row marked
  `failed=true` and publishes `AiAnalysisFailedEvent`, so a human reviewer sees the broken
  binding on `QueryDetailPage` and can fix it. Admins are prevented from saving an inconsistent
  state — `DatasourceAdminServiceImpl.create/update` throws
  `MissingAiConfigForDatasourceException` (HTTP 422) when AI analysis is enabled but no config
  is bound, and `IllegalAiConfigBindingException` (HTTP 422) when the requested `ai_config_id`
  belongs to a different organization.

If the looked-up `ai_config` row has no API key set (and the provider needs one — Anthropic /
OpenAI; `OLLAMA`, `OPENAI_COMPATIBLE` and `HUGGING_FACE` are keyless-capable), the holder throws `AiAnalysisException`
whose message is resolved via `MessageSource` (`error.ai.not_configured` in
`i18n/messages.properties`). The smoke endpoint `POST /admin/ai-configs/{id}/test` surfaces that
text as the `detail` of `{"status":"ERROR", ...}`.

### Langfuse integration

The `ai` module integrates with [Langfuse](https://langfuse.com) for two independent, composable
concerns, both configured per organization via the singleton `langfuse_config` row
(`LangfuseConfigService` / `AdminLangfuseConfigController` at `/api/v1/admin/langfuse-config`,
modeled on `saml_config`). The secret key is AES-256-GCM encrypted and never returned. A
`LangfuseConfigResolver` loads + decrypts the row and caches it per org, evicting on
`LangfuseConfigUpdatedEvent`; it returns empty when Langfuse is disabled or credentials are
incomplete, so callers short-circuit. All Langfuse HTTP goes through `LangfuseClient` (a `RestClient`
authenticated per call with HTTP Basic `publicKey:secretKey` — hand-rolled, no SDK, matching the
notifications dispatchers). Outbound host/timeouts come from `accessflow.langfuse.*`
(`LangfuseProperties`); per-org credentials live in the DB.

- **Tracing.** `AiAnalyzerStrategyHolder` wraps every built delegate in a `TracingAiAnalyzerStrategy`
  decorator, so both the editor-preview and submitted-query paths are covered. After each
  `analyze(...)` the decorator fires `LangfuseTracer.trace(...)` (on success and failure). The tracer
  resolves the org config on the calling thread (cheap, cached) to skip disabled orgs, then posts a
  batched `trace-create` + `GENERATION` observation to `POST {host}/api/public/ingestion` on a
  dedicated virtual-thread executor. It is **best-effort and non-blocking**: any failure (or a
  disabled org) is logged and swallowed — analysis is never affected. The trace input is the SQL +
  db-type + schema-context; the output is the structured `AiAnalysisResult` (model, provider, token
  usage, latency in `usageDetails`).
- **Prompt management.** Strategies resolve their template at call time via a `SystemPromptSource`.
  When an `ai_config` row sets `langfuse_prompt_name`, the holder builds a source that asks
  `LangfusePromptProvider` first (`GET {host}/api/public/v2/prompts/{name}?label={label}`), falling
  back to the local `system_prompt_template` / built-in default when Langfuse / prompt-management is
  off or the fetch fails. Successful fetches are cached for `accessflow.langfuse.prompt-cache-ttl`
  (so Langfuse edits propagate without a restart) and evicted per org on `LangfuseConfigUpdatedEvent`.
  Only text prompts are used; chat prompts and fetch errors fall back. Toggling Langfuse config does
  **not** require rebuilding the holder delegate — the source re-asks the provider each call.

`POST /admin/langfuse-config/test` verifies the saved credentials against an authenticated Langfuse
endpoint via `LangfuseConfigService.testConnection(...)`.

### Setup progress

`DefaultSetupProgressService` reports `ai_provider_configured = true` when the org has at
least one `ai_config` row that is "usable" on its own — a keyless-capable provider (`OLLAMA`,
`OPENAI_COMPATIBLE`, or `HUGGING_FACE`) or a non-blank API key is stored. This signal flows through
`AiConfigLookupService.hasAnyUsableAiConfig(orgId)`, which simply scans
`AiConfigRepository.findAllByOrganizationIdOrderByNameAsc(orgId)` and filters on usability.
The signal does **not** require any datasource to bind to the config — admins configure AI
before creating their first datasource (the onboarding widget lists AI as step 2).

### No yaml-driven AI config

`spring.ai.anthropic.*`, `spring.ai.openai.*`, `spring.ai.ollama.*` and `accessflow.ai.provider`
are **not** read. `application.yml` sets `spring.ai.model.{chat,embedding,image,audio.speech,
audio.transcription,moderation}=none` to disable every Spring AI startup auto-config — the
context still holds `AnthropicApi` / `OpenAIClient` / `OllamaApi` classes on the classpath, but
no `ChatModel` is auto-built. All connection settings come from the DB row via the holder.

Two entry points:
- `AiAnalyzerService.analyzePreview(...)` — synchronous, used by `POST /api/v1/queries/analyze`. No
  persistence; failures propagate as exceptions.
- `AiAnalyzerService.analyzeSubmittedQuery(UUID queryRequestId)` — invoked from
  `AiAnalysisListener` on `QuerySubmittedEvent`. Persists an `ai_analyses` row, links it from
  `query_requests.ai_analysis_id`, and publishes `AiAnalysisCompletedEvent` (or
  `AiAnalysisFailedEvent` plus a sentinel `CRITICAL` row on failure — never propagates).

#### Rate limit & cost budget (AF-55)

Immediately before each `AiAnalyzerStrategy` call — in `analyzePreview`, in `analyzeSubmittedQuery`, and in `DefaultTextToSqlService.generateSql` — the `ai` module's `AiRateLimiter` (`DefaultAiRateLimiter`) enforces two per-organization guardrails so a runaway editor or compromised account cannot drain the provider API key / monthly budget:

- **Requests per minute** — a Redis fixed-window counter (key `accessflow:ai:ratelimit:{orgId}:{epochMinute}`, 60s TTL on first increment), reusing the same Redis that backs JWT revocation / ShedLock. Bound from `accessflow.ai.rate-limit.requests-per-minute` (default 30; `<= 0` disables).
- **Monthly token budget** — sums `prompt_tokens + completion_tokens` from the org's `ai_analyses` rows since the start of the current calendar month (UTC `Clock`) via the new `core.api` method `AiAnalysisStatsLookupService.sumTokensSince(orgId, since)`. Bound from `accessflow.ai.rate-limit.tokens-per-month` (default 0 = unlimited / opt-in; `<= 0` disables).

On violation the limiter throws `AiRateLimitExceededException` / `AiBudgetExceededException` (both extend `AiAnalysisException`). On the synchronous preview / text-to-SQL paths these propagate to `AiAnalysisExceptionHandler`, which maps them to **HTTP 429** (`AI_RATE_LIMIT_EXCEEDED` — carrying `limit` + `retryAfterSeconds` — and `AI_BUDGET_EXCEEDED`). On the async submitted-query path the listener catches them and records a sentinel `CRITICAL` analysis row (`summary = "AI rate limit exceeded"` / `"AI budget exhausted"`, `failed = true`) so review still proceeds with a missing-AI-signal surface. The admin connectivity-test path (`POST /admin/ai-configs/{id}/test`) is intentionally **not** rate-limited.

### System Prompt Template

```
You are a database security and performance expert reviewing SQL before execution in production.
Analyze the following SQL query and respond ONLY with a JSON object matching this exact schema.
Do not include any text outside the JSON.

Schema:
{
  "risk_score": <integer 0-100>,
  "risk_level": <"LOW"|"MEDIUM"|"HIGH"|"CRITICAL">,
  "summary": <string — one sentence human-readable summary>,
  "issues": [
    {
      "severity": <"LOW"|"MEDIUM"|"HIGH"|"CRITICAL">,
      "category": <string — e.g. "MISSING_WHERE_CLAUSE", "SELECT_STAR", "MISSING_INDEX">,
      "message": <string — clear explanation of the issue>,
      "suggestion": <string — concrete fix>
    }
  ],
  "missing_indexes_detected": <boolean>,
  "affects_row_estimate": <integer or null>,
  "optimizations": [
    {
      "type": <"INDEX"|"REWRITE">,
      "title": <string — short imperative summary, e.g. "Add index on orders(customer_id)">,
      "rationale": <string — why it helps, referencing the query and schema>,
      "sql": <string — one concrete, runnable statement in the {db_type} dialect>
    }
  ]
}

Columns marked *RESTRICTED* in the schema context are sensitive and the values returned for them are masked at the proxy layer. If the SQL references any *RESTRICTED* column (in SELECT, WHERE, JOIN, ORDER BY, INSERT, UPDATE, or DELETE), add an issue with category="RESTRICTED_COLUMN_ACCESS" and severity="LOW" summarizing which restricted columns are touched. Do NOT raise the overall risk_level above MEDIUM solely for this reason — this is informational, not a blocker.

Optimization suggestions: when the query would benefit from an index or a rewrite, populate "optimizations". Every "sql" value MUST be a single statement in the SAME query language as the analyzed query for this {db_type} engine — NOT necessarily SQL. For type="INDEX", give the engine's native index-definition statement (SQL / SQL++ / CQL: CREATE INDEX …; Neo4j Cypher: CREATE INDEX FOR (n:Label) ON (n.prop); MongoDB: db.collection.createIndex({…}); DynamoDB: a Global Secondary Index definition; Elasticsearch: a mapping / field change) — for engines without secondary indexes (e.g. Redis) omit INDEX suggestions and prefer a REWRITE. For type="REWRITE", give a complete, runnable, more-efficient version of the query in that same language (e.g. replace SELECT * with the needed columns, add a sargable predicate, remove a redundant subquery; for Cassandra/CQL restrict to partition & clustering keys; for MongoDB add an indexable filter/projection; for Elasticsearch use filter context on keyword fields). Reference only objects present in the schema context; never invent names. Suggest at most 3, ordered by impact. If there is no worthwhile optimization, return "optimizations": [].

Database type: {db_type}
Schema context: {schema_context}
Pre-flight cost estimate (from the database engine's own EXPLAIN / affected-row count — treat it as the authoritative blast radius and factor it into risk_score and risk_level):
{cost_estimate}
SQL to analyze:
{sql}
```

`AiAnalysisResult` carries the parsed `optimizations` as a `List<OptimizationSuggestion>` (`type`, `title`, `rationale`, `sql`); `AiResponseParser` parses it leniently (an absent or `null` `optimizations` key ⇒ empty list, so older/custom prompts and pre-AF-451 persisted rows are unaffected) and `ai_analyses.optimizations` (JSONB) persists it alongside `issues`. This is **engine-agnostic**: because the prompt instructs the model to emit each suggestion in the analyzed engine's native query language, optimizations work for the NoSQL engines too — `db.collection.createIndex({…})` for MongoDB, `CREATE INDEX FOR (n:Label) …` (Cypher) for Neo4j, a GSI definition for DynamoDB, a `CREATE INDEX` for SQL++/CQL, a mapping change for Elasticsearch, and REWRITE-only for index-less engines such as Redis. The frontend `AiHintPanel` renders each suggestion as a card with an **"Apply as draft"** button: clicking it loads the suggestion's `sql` into the editor and mounts the engine's native editor syntax (e.g. MongoDB shell vs JSON), the user re-analyzes the draft, and submits it through the normal pipeline (`POST /queries`) with `submission_reason=AI_SUGGESTION`. Nothing is auto-executed; the applied statement still passes the engine's query parser (JSqlParser for SQL, the engine plugin's parser for NoSQL), the schema allow-list, and the permission check at submit time.

### Response language

`AiAnalyzerStrategy.analyze(sql, dbType, schemaContext, costEstimateContext, language, aiConfigId)` takes a BCP-47 `language` code (`en`, `es`, `de`, `fr`, `zh-CN`, `ru`, `hy`). The renderer appends one line at the end of the user prompt: `Respond in: <DisplayName>. Translate the free-form fields (summary, issues[].message, issues[].suggestion) into that language. Keep risk_level and issues[].category as their original English enum values.`

`DefaultAiAnalyzerService` resolves the language per call by reading the org's `localization_config.ai_review_language` via `LocalizationConfigService.getOrDefault(organizationId)`. If the lookup fails or returns an unknown code the service silently falls back to English so prompt construction never blocks AI analysis. The `/admin/ai-config/test` smoke endpoint always passes `"en"` since it is a synthetic, language-agnostic call.

The `risk_level` and `issues[].category` fields are deliberately kept as English enum strings — the SPA renders them through dictionaries (`statusColors.ts`, `riskColors.ts`) that don't translate, and the workflow state machine matches on the canonical names.

### Restricted-column awareness

`SystemPromptRenderer.describeSchema(schema, restrictedColumns)` annotates restricted columns inline in the schema context, e.g. `public.users(id uuid pk, ssn text *RESTRICTED*, email text)`. The prompt template instructs the model to emit a `RESTRICTED_COLUMN_ACCESS` issue (severity `LOW`) when the SQL references any of those columns. The workflow state machine ignores this category — it never auto-rejects on restricted-column access; the value is masked at the proxy layer regardless. Both `analyzePreview(...)` and `analyzeSubmittedQuery(...)` resolve the caller's restricted columns via `DatasourceUserPermissionLookupService` before rendering the prompt.

### Text-to-query generation (AF-335, AF-439)

Per-datasource `text_to_sql_enabled` lets a user draft a query from a natural-language prompt — in the datasource engine's **native query language** (SQL for the relational engines, SQL++ for Couchbase, PartiQL for DynamoDB, MongoDB shell/JSON, CQL for Cassandra/ScyllaDB, the Elasticsearch Query DSL for Elasticsearch/OpenSearch, redis-cli for Redis, Cypher for Neo4j). The capability **reuses the same `AiAnalyzerStrategy` infrastructure** as risk analysis — the strategy interface gained a second method, `generateSql(prompt, dbType, schemaContext, language, aiConfigId)`, implemented by every provider adapter (Anthropic / OpenAI-compatible / Ollama) and routed through the same `AiAnalyzerStrategyHolder` per-config delegate cache and eviction listeners. The backend is **`db_type`-agnostic** — it does not branch per engine; the prompt does the steering. A shared `ChatModelInvoker` performs the Spring AI `ChatModel` call and token/model extraction so each adapter's `generateSql` stays a thin wrapper.

- `TextToSqlService.generateSql(datasourceId, prompt, userId, organizationId, isAdmin)` (impl `DefaultTextToSqlService`) mirrors `analyzePreview`: it resolves the datasource, **requires `text_to_sql_enabled`** (`TextToSqlDisabledException` → HTTP 409), **requires a bound `ai_config`** (`TextToSqlNotConfiguredException` → HTTP 400), verifies the config belongs to the org, introspects the schema (honouring the caller's restricted columns), resolves the org's review language, then calls the strategy. After generation it attaches the editor **`syntax`** hint (`SystemPromptRenderer.syntaxFor` → `engineModes` ids `sql`/`shell`/`json`/`cli`/`cql`/`query_dsl`/`cypher`/`sqlpp`/`partiql`; MongoDB resolves to `shell` or `json` from the draft's shape) and **validates the draft** through the engine-aware `proxy.api.QueryParser.parse(query, dbType)` — an unparseable draft fails closed as `AiAnalysisParseException` → HTTP 422 (`AI_RESPONSE_INVALID`). The handler logs the cause at `WARN` and surfaces it as a `reason` property on the `ProblemDetail` (e.g. `Generated query did not parse for MONGODB: …`) so the editor can show the user *why* generation failed instead of a bare generic toast. **No persistence, no events, no query request is created.**
- The generation prompt is a fixed, **engine-language-aware** default template (`SystemPromptRenderer.DEFAULT_QUERY_GENERATION_TEMPLATE`, tokens `{{target_language}}`, `{{target_guidance}}`, `{{db_type}}`, `{{schema_context}}`, `{{rag_context}}`, `{{language}}`, `{{user_request}}`). A per-`DbType` profile supplies the target query-language name and the engine-specific guidance bullet (read-only bias plus the banned shapes that engine rejects — Mongo `$where`, ES `script`/Painless, Cypher `LOAD CSV`/arbitrary `CALL`, Redis server-side scripting, CQL `ALLOW FILTERING`, multi-statement input). It steers the model toward a single schema-grounded statement, instructs it to **never reference `*RESTRICTED*` fields**, and to return a strict JSON envelope `{"sql": "..."}` (the key stays `sql` for wire compatibility; the value is now one runnable statement in the target query language) parsed by `SqlGenerationResponseParser` (malformed output → `AiAnalysisParseException` → HTTP 422; provider failure → `AiAnalysisException` → HTTP 503). Per-config custom prompt / Langfuse override for generation is a deliberate follow-up — for v1 the default template is used directly and the generation path is not traced to Langfuse.
- **Governance is preserved end to end:** the returned query is only a draft that lands in the editor. The user still submits it through `POST /api/v1/queries`, where engine validation (JSqlParser for SQL, the engine plugin's parser for NoSQL), the schema allow-list, permission checks, AI risk analysis, and human review all run as normal — so text-to-query can never bypass the approval pipeline or column masking. Exposed at `POST /api/v1/queries/generate-sql` (`TextToSqlController`); handlers live in the existing `AiAnalysisExceptionHandler`.

### RAG knowledge base (AF-336)

Admins attach a per-`ai_config` knowledge base; at analysis / text-to-SQL time the most relevant chunks are retrieved and injected into the prompt's `{{rag_context}}` token. Retrieval lives **entirely inside the `ai` module** — like the `SystemPromptSource` pattern, `AiAnalyzerStrategyHolder` builds a per-config `RagRetriever` and injects it into each provider delegate, so the public `AiAnalyzerStrategy` API and its callers are unchanged. A disabled config gets `RagRetriever.DISABLED` (returns `null` → the renderer substitutes "(no knowledge base context available)").

- **Pluggable backends via Spring AI `VectorStore`.** `SpringAiVectorStoreFactory` builds a `VectorStore` per config: `PgVectorStore` for `PGVECTOR` (the shared application `JdbcTemplate` + the Flyway-created `vector_store` table, `initializeSchema=false`, cosine distance) and `QdrantVectorStore` for `QDRANT` (a gRPC client built from `rag_endpoint` / `rag_api_key`). Both partition rows by an `ai_config_id` metadata/payload filter so one store serves many configs and orgs.
- **Dedicated embeddings.** `SpringAiEmbeddingModelFactory` builds an `EmbeddingModel` per config from the `embedding_*` settings — independent of the chat `provider` (an Anthropic chat config still embeds via OpenAI / Ollama). `ANTHROPIC` is rejected as an embedding provider. `RagComponentsFactory` centralizes decrypt + factory wiring and is shared by the holder and the knowledge-base service.
- **Ingestion (synchronous, v1).** `KnowledgeBaseService` (`ai/api`) → `DefaultKnowledgeBaseService` chunks a document with Spring AI's `TokenTextSplitter` (size = `accessflow.rag.chunk-size`), tags each chunk with `{ai_config_id, document_id, organization_id, title}` metadata, and `vectorStore.add(...)` embeds + stores it. Deleting a document removes its chunks (`vectorStore.delete("document_id == '…'")`). Content is capped at `accessflow.rag.max-document-chars`.
- **Retrieval is fail-safe.** `DefaultRagRetriever.retrieve(query)` runs `similaritySearch(topK, threshold, filter=ai_config_id)` and joins chunk text; any failure (store down, embedding error) is swallowed and returns `null` — analysis is never blocked by RAG. A `rag/test` endpoint embeds a probe + searches to verify connectivity (and, for `PGVECTOR`, that the embedding dimension matches the column).
- **pgvector is provisioned outside Flyway.** The `vector` extension is not trusted and the app DB role is not a superuser, so a superuser init script creates it (`deploy/postgres-init/02-pgvector.sql` for Compose, the Helm initContainer, `withInitScript` for Testcontainers); Flyway V69 creates only the `vector_store` table. The embedding dimension is a Flyway placeholder (`ACCESSFLOW_RAG_PGVECTOR_DIMENSIONS`, default 1536). The pgvector / Qdrant Spring AI auto-configs are excluded in `application.yml` — stores are built per row, never as context beans.
- **Graceful degradation when pgvector is absent.** `core.internal.config.PgVectorFlywayConfiguration` registers a `FlywayMigrationStrategy` that, before migrating, best-effort runs `CREATE EXTENSION IF NOT EXISTS vector` (toggle: `accessflow.rag.pgvector.auto-provision`) and detects whether the type is usable. If it is, migrations run normally and `vector_store` is created if missing (self-heals a pgvector-installed-later deployment). If it is not — or `accessflow.rag.pgvector.enabled=false` — V69 is recorded as applied without executing it (its resolved checksum is stored so later boots validate), the pgvector-free `knowledge_document` table is created by the idempotent `V73__ensure_knowledge_document.sql` (Hibernate `ddl-auto=validate` needs it), and `vector_store` is omitted. The decision is published via `core.api.PgVectorAvailability`: `RagComponentsFactory` returns `RagRetriever.DISABLED` for PGVECTOR configs, `DefaultKnowledgeBaseService` throws `AiConfigRagInvalidException` (`error.ai_config.rag.pgvector_unavailable`, HTTP 400) on PGVECTOR ingest / test, and `GET /admin/ai-configs/rag/capabilities` reports `pgvector_available`. The external QDRANT path is unaffected. So the application always starts even on a Postgres without the extension.

### Multi-model orchestration, voting & guardrails (AF-450)

A single `ai_config` can run **several models in parallel** and combine their verdicts, and can
**block configured prompt patterns** before any model is called. Both live entirely inside the `ai`
module — `AiAnalyzerStrategyHolder` composes them into the delegate it caches, so the public
`AiAnalyzerStrategy` API and its callers (preview, async submitted-query analysis, text-to-SQL) are
unchanged. The delegate is a chain: `GuardrailAiAnalyzerStrategy( OrchestratingAiAnalyzerStrategy( [ member₀(primary), member₁, … ] ) )`.

- **Members.** Member 0 is the primary `ai_config` row; when `orchestration_enabled`, the enabled
  `ai_config_model` rows are added. Each member is the existing per-provider strategy wrapped in
  `TracingAiAnalyzerStrategy` (so every member is traced to Langfuse independently). Members reuse the
  parent's prompt source + RAG retriever and inherit its `timeout_ms` / `max_completion_tokens` — only
  provider/model/endpoint/key vary. With one member the orchestrator degenerates to that member's
  result plus a one-entry breakdown.
- **Parallel invocation.** `OrchestratingAiAnalyzerStrategy.analyze(...)` fans members out on
  **virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`), timing each call. Each task
  catches its own exception, so one member's failure never aborts the others.
- **Voting (`AiVoteAggregator`).** Over the **successful** members: `WEIGHTED_AVERAGE` = weight-weighted
  mean of risk scores (level derived from the score via the quartile thresholds in
  `ClassificationRiskBooster.levelFromScore`); `MAX_RISK` = the single highest-risk member;
  `MAJORITY` = the weight-weighted most common risk level (ties break toward the higher risk).
  Regardless of strategy, issues and optimizations are **merged** (union, deduped), the
  missing-indexes flag is **OR**-ed, the affected-row estimate is the **max**, and tokens are
  **summed** across successes (so the monthly token budget naturally accounts for every model). The
  aggregate row's `ai_provider`/`ai_model` are the primary's.
- **Partial / total failure.** If ≥1 member succeeds, the aggregate is computed over the survivors and
  the failed members are still recorded in the breakdown (`failed=true`, null score). If **every**
  member fails, the first failure is rethrown — the async path records the usual sentinel
  `CRITICAL` row (no breakdown).
- **Per-model cost / latency.** The aggregate `AiAnalysisResult` carries a `modelResults` list (one
  `AiModelResult` per member, success or failed). `DefaultAiAnalyzerService` maps it into
  `PersistAiAnalysisCommand.modelResults`, and `DefaultAiAnalysisPersistenceService` writes one
  `ai_analysis_model_result` row per member alongside the aggregate `ai_analyses` row — for **every**
  analysis, so the admin dashboard's per-model token/latency view is uniform. `AiAnalysisStatsRepository.findPerModelStats`
  groups those rows by `(provider, model)` (summed tokens, average latency, average risk over
  non-failed members) for `GET /admin/ai-analyses/stats`.
- **Guardrails (pre-call).** `GuardrailAiAnalyzerStrategy` compiles `guardrail_patterns` (case-insensitive
  regex) and, before invoking the orchestrator, rejects a submitted SQL / NL prompt matching any with
  `AiGuardrailViolationException` → HTTP 422 `AI_GUARDRAIL_BLOCKED` on the preview / text-to-SQL paths
  and the sentinel `CRITICAL` row on the async path. Empty patterns = pass-through. Patterns are
  validated as regexes at save time (HTTP 400 `AI_CONFIG_ORCHESTRATION_INVALID`).
- **Post-call guardrail.** Output-schema validation is the existing strict `AiResponseParser` — a member
  returning malformed JSON throws `AiAnalysisParseException`, which (when all members fail) yields the
  sentinel row without failing the query. No new code; AF-450 just frames it as the output guardrail.
- **`generateSql` is not voted** — it delegates to the primary member only (voting an aggregated SQL
  string is not meaningful); the guardrail still applies to its prompt.
- **Cache eviction.** Changing orchestration scalars, guardrail patterns, or the member set publishes
  `AiConfigUpdatedEvent` (now carrying `orchestrationChanged`), evicting the cached delegate so the next
  call rebuilds the chain.

### AI provider fallback pool (AF-458)

An organization can mark any number of `ai_config` rows as **fallbacks** via the nullable
`fallback_priority` column (`NULL` = not a fallback; lower value = tried first; `-1` on the
update API clears it). When the config bound to a request fails at call time — on either
`analyze()` or `generateSql()` — `AiAnalyzerStrategyHolder` retries the request **once per
fallback config** in ascending `(fallback_priority, name)` order, skipping the config that
just failed; the first success wins and the **original** failure is rethrown when the pool
is exhausted (or empty), so existing failure semantics (sentinel `CRITICAL` row on the async
path, `503 AI_PROVIDER_UNAVAILABLE` on preview) are unchanged when no fallback helps.

Semantics worth knowing:

- **Trigger set.** `AiAnalysisException` (provider unreachable / HTTP error / timeout /
  missing key — a keyless or misconfigured primary also fails over) and
  `AiAnalysisParseException` (provider responded with unusable output). The
  `AiGuardrailViolationException` / `AiBudgetExceededException` / `AiRateLimitExceededException`
  subclasses never trigger the pool — a guardrail block is a policy decision and
  budget/rate-limit exceedance is org-level — and a *fallback's* guardrail block is
  rethrown rather than swallowed.
- **Lazy resolution.** Fallbacks are resolved per failure with a fresh repository read
  (indexed partial scan on `(organization_id, fallback_priority)`), so the holder's
  per-id delegate-cache eviction needs no fan-out and priority edits take effect
  immediately without an `AiConfigUpdatedEvent`.
- **Attribution.** A fallback-served analysis records the *fallback's* provider/model in
  `ai_analyses` (the result carries them from the serving delegate) and logs a WARN naming
  both configs.
- **Offline / air-gapped story.** The intended use is a keyless local **Ollama** config
  marked `fallback_priority = 0` (see `charts/accessflow/examples/values-airgapped.yaml`
  and `bootstrap.aiConfigs[].fallbackPriority`): cloud-provider outages degrade to the
  self-hosted model instead of pushing every query to manual review. Ollama verifies
  pulled model blobs by SHA-256 digest natively, so pre-pulled models on an internal
  mirror stay integrity-checked — AccessFlow ships no separate model-download tooling.

### Risk Score Heuristics

| Condition | Score Contribution |
|-----------|-------------------|
| DELETE without WHERE | +60 |
| UPDATE without WHERE | +55 |
| DDL statement | +50 |
| SELECT * | +20 |
| No LIMIT on SELECT | +15 |
| Missing index on WHERE column | +15 |
| Subquery with no index | +10 |
| Single-row operation with PK WHERE | -20 |

### Behavioural anomaly detection (UBA, AF-383)

The `ai` module additionally runs **user-behaviour analytics**: a clustered-safe scheduled job builds
rolling per-`(user, datasource)` behavioural baselines and flags out-of-pattern activity. It is built
**entirely from `audit_log` metadata** — never query result data — so the audit log stays the single
forensic source and no new data path touches customer rows.

**Metadata enrichment.** To make features derivable from the audit log alone, the `QUERY_EXECUTED`
audit metadata was enriched with `datasource_id`, `query_type`, `referenced_tables`,
`distinct_table_count`, and `rows_returned`; `QUERY_FAILED` gained `datasource_id` and `query_type`.

**Pipeline** (`BehaviorAnomalyDetectionJob`, lock `behaviorAnomalyDetectionJob`, cadence
`accessflow.ai.anomaly.detection-poll-interval`, default `PT15M`):

1. **Baseline aggregation.** For each `(user, datasource)` with new audit activity, the job advances the
   `behavior_baseline` watermark over `accessflow.ai.anomaly.lookback-window` (default `PT1H`) windows,
   updating a JSONB `features` blob: rolling per-feature observation series (query count, distinct
   tables, rows returned, error rate — capped at `accessflow.ai.anomaly.max-baseline-samples`, default
   `90`), a 24-bucket active-hour histogram, and query-type / table frequency maps. Detection stays
   dormant until `sample_size ≥ accessflow.ai.anomaly.min-sample-size` (default `7`) — the cold-start
   guard.
2. **Statistical detection.** On the freshest window each tracked feature is scored:
   - **Scalar features** (query count, distinct tables, rows returned, error rate) use a **z-score**
     against the baseline mean/stddev, flagging when it crosses `accessflow.ai.anomaly.z-score-threshold`
     (default `3.0`); when the baseline stddev is degenerate it falls back to a **robust IQR** test
     (`accessflow.ai.anomaly.iqr-multiplier`, default `1.5`), with a constant-baseline guard so a flat
     history never divides by zero.
   - **Active-hour** detection flags a query landing in a bucket whose baseline frequency is below
     `accessflow.ai.anomaly.off-hours-threshold` (default `0.02`) — off-hours activity.
   - **Categorical novelty** (query types) and **unseen tables** (new tables) flag the first appearance
     of a value absent from the baseline frequency map.
3. **AI summary (optional, fail-safe).** When `accessflow.ai.anomaly.summary-enabled` (default `true`),
   the bound `ai_config`'s analyzer is asked for a one-paragraph natural-language explanation, stored in
   `behavior_anomaly.ai_summary`. Per the AI module's no-throw contract this is **fully fail-safe** — a
   failed or disabled summary leaves the column `null` and never blocks the detection.
4. **Integration / events.** Each new `OPEN` anomaly:
   - raises the `anomalyActive` routing signal — the new `AnomalyDetected` routing condition (wire type
     `anomaly_detected`) lets a policy `ESCALATE` the flagged user's **next** query (detection is a
     periodic batch over past data, so it influences future submissions, not the already-executed
     query);
   - fires the notification fanout mirroring `AI_HIGH_RISK` across all active org channels including
     PagerDuty (new `NotificationEventType.ANOMALY_DETECTED`, PagerDuty trigger `ANOMALY`);
   - emits an `anomaly.detected` WebSocket event (`{ anomaly_id, user_id, datasource_id, feature,
     score }`) to org admins and the subject user.

Anomalies are read/triaged via `GET /admin/anomalies` (AUDITOR/ADMIN) and acknowledged / dismissed by an
ADMIN (`POST /admin/anomalies/{id}/{acknowledge,dismiss}`); each user sees their own open-anomaly badge
via `GET /anomalies/badge?datasourceId=`. See [docs/03-data-model.md → behavior_baseline / behavior_anomaly](03-data-model.md#behavior_baseline)
and [docs/04-api-spec.md → Behavioural Anomaly Detection (UBA)](04-api-spec.md#behavioural-anomaly-detection-uba--af-383).

### Approval-outcome prediction (AF-645)

A per-organization logistic regression trained on the org's historical **human** review decisions,
predicting the probability a reviewer will approve a query that has just landed in `PENDING_REVIEW`.
Same "hand-rolled, unit-testable statistics" approach as the UBA detector above — no ML library.

**Advisory only, structurally.** The probability is a triage signal for the review queue. It is never
an input to the routing engine, grant coverage, break-glass, or any other decision path; only the read
side consumes it. `ai/api/ApprovalPredictionService` exposes scoring and training and nothing else.

**Serving path.** `ai/internal/ApprovalPredictionListener` has two `@ApplicationModuleListener` methods:

- `QueryReadyForReviewEvent` → `predictForQuery`. That event is published exactly on the
  `PENDING_AI → PENDING_REVIEW` transition and never on an auto path (routing auto-approve/reject,
  grant coverage, break-glass), which is precisely the population that has a human decision to predict.
- `QueryEstimateCompletedEvent` → `refreshForLateEstimate`. The cost estimate (AF-624) is computed in
  parallel; on the AI-disabled and AI-failed paths it can land *after* the query is already in review,
  and it contributes four features.

`ai/internal/ApprovalFeatureLoader` assembles the schema-v1 vector from `QueryRequestLookupService`
(`findById` for `organizationId`/`transactional`, then `findDetailById` for `created_at`, the AI
analysis and the cost estimate) plus the two `ApprovalOutcomeHistoryLookupService` rate counts. It
reads the analysis and estimate through the query's **back-pointers**, the same way the training query
joins them, so training and serving can never disagree on what a feature means.

**Feature schema v1** — 21 features, in the frozen order `ApprovalFeatureVector.FEATURE_SCHEMA_V1`
declares, grouped as they are derived. `ApprovalFeatureExtractor` is the single stateless encoder for
both training and serving, so an encoding cannot drift between them.

| Group | Feature | Derivation |
|---|---|---|
| AI analysis | `risk_score` | `ai_analyses.risk_score / 100` |
| | `risk_level_LOW` / `_MEDIUM` / `_HIGH` / `_CRITICAL` | one-hot on `RiskLevel` |
| | `issue_count` | number of entries in `ai_analyses.issues`; unparseable JSON counts 0 |
| | `ai_missing` | 1 when there is no linked analysis, or it failed |
| Cost estimate (AF-624) | `estimated_rows_log10`, `affected_row_count_log10`, `estimated_cost_log10` | `log10(1 + v)`, compressing the heavy right tail |
| | `seq_scan` | 1 when the normalized `query_estimates.scan_type` is one of 12 known full/sequential-scan operation names (`seq scan`, `table access full`, `collscan`, `allnodesscan`, …) |
| | `estimate_missing` | 1 when there is no estimate, it reported `supported=false`, or it failed |
| Query request | `query_type_SELECT` / `_INSERT` / `_UPDATE` / `_DELETE` / `_DDL` | one-hot on `QueryType` |
| | `transactional` | the query's transactional flag |
| | `off_hours` | 1 when `created_at` falls outside `[08:00, 18:00)` **UTC** (see below — the zone is fixed, not the app's) |
| Historical rates | `submitter_approval_rate`, `datasource_approval_rate` | Laplace-smoothed `(approved + 1) / (decided + 2)` over the same decided population the labels come from |

Three encoding rules carry more weight than they look like they do:

- **Missing inputs are indicated, never imputed.** Every absent value contributes an explicit
  indicator (`ai_missing`, `estimate_missing`) *plus* a zero fill. The indicators are redundant with
  their payloads on purpose — that redundancy is what stops an absent input from reading as a genuine
  zero, and standardization plus L2 absorbs the collinearity. The vector's constructor rejects `NaN`
  and the infinities, so "a missing input never produces `NaN`" holds structurally, not just under
  test.
- **`off_hours` buckets against fixed UTC**, not the application `Clock`. Training and serving must
  bucket identically, and reading the deployment zone would let a zone change silently invalidate
  every already-trained model.
- **`QueryType.OTHER` and a null query type / risk level are the reference category** — their whole
  one-hot block stays zero. One-hot positions are resolved *by name*, so adding an enum constant
  degrades to the reference category instead of shifting indices. Reordering or renaming inside v1 is
  a different matter: `predict` validates only the array *length*, so it would silently score every
  trained model against the wrong coefficients. That is a schema-v2 event — bump `SCHEMA_VERSION`,
  add a new list, retrain.

**Training population and labels.** One predicate
(`QueryRequestRepository.APPROVAL_OUTCOME_DECIDED_PREDICATE`) selects the samples *and* backs both
rate-count queries, so features and labels cannot disagree about who counts:

```sql
where q.datasource.organization.id = :orgId
  and q.createdAt >= :since
  and q.approvedByGrantId is null
  and q.submissionReason <> :excludedReason
  and (q.status = :timedOut
       or (q.status in :humanDecidedStatuses
           and exists (select 1 from ReviewDecisionEntity rd where rd.queryRequest = q)))
```

Every auto path is excluded, because none of them carries reviewer judgment and all of them would
poison the model:

| Clause | What it excludes |
|---|---|
| `approvedByGrantId is null` | grant-covered auto-approval (#582) |
| `submissionReason <> EMERGENCY_ACCESS` | break-glass (AF-385) |
| `exists (… ReviewDecisionEntity …)` | routing-policy `AUTO_APPROVE` / `AUTO_REJECT` (AF-379) **and** external-ticket system decisions (AF-453) — neither writes a `review_decisions` row, which is exactly what makes this one clause cover both |
| `status in (APPROVED, EXECUTED, REJECTED)` | `CANCELLED`, everything still pending, and `FAILED` (a post-approval execution error is not a review outcome) |

Label: **positive** = `APPROVED` / `EXECUTED`, **negative** = `REJECTED` / `TIMED_OUT`. `TIMED_OUT` is
the one status admitted without a decision row — it short-circuits the `exists` clause, because no
reviewer acting *is* the outcome being predicted.

One accepted source of noise: an external-ticket resolution that lands *after* a partial human review
(an earlier-stage approval, or changes requested) leaves a decision row behind and is counted as
human-decided, even though the terminal transition was machine-attributed. Separating those needs a
decision-provenance column the schema does not have.

**Every path persists a row**, mirroring the AF-624 estimate convention so the read side always has a
definitive state. Guards, in order:

| Guard | Row written |
|---|---|
| feature disabled | `skipped`, reason `DISABLED` |
| query request not found, or its detail row vanished mid-flight | none — nothing to attach a row to (logged `WARN`) |
| no model row, or `serving=false` | `skipped`, reason `MODEL_NOT_SERVING` |
| model's `feature_schema_version` ≠ current, or its feature names don't match the schema | `skipped`, reason `MODEL_NOT_SERVING` (both versions logged `WARN`) |
| scored | probability + `model_id` + the feature snapshot |
| unexpected `RuntimeException` | `failed` sentinel with the message truncated to 500 chars |

Skip reasons are **machine tokens**, not `MessageSource`-resolved text: the row is written once by an
async listener and read later by any reviewer, so localizing at write time would freeze the listener
thread's locale. Schema mismatch folds into `MODEL_NOT_SERVING` to keep the token set closed.

Deliberately **no `status == PENDING_REVIEW` guard** on `predictForQuery` — the listener runs after
commit and asynchronously, so a fast reviewer can decide first, and a status guard would write no row
at all and leave the detail view blank. Insert-once persistence is the idempotency mechanism.
`refreshForLateEstimate` is the only path that replaces a row, and it no-ops unless the persisted
snapshot recorded `estimate_missing=true`, the query is still `PENDING_REVIEW`, and the freshly built
vector no longer records a missing estimate (a transactional query is estimated `supported=false` yet
still publishes the completion event, so it reaches this guard routinely). It **replaces only with a
real probability, never with a sentinel** — the row it overwrites already holds a number a reviewer may
have acted on, and the rewrite would be irreversible because the new snapshot no longer records a
missing estimate, spending the single replace path. If the model stopped serving or re-scoring throws,
the existing row stands.

Failures cannot reach the workflow: `@ApplicationModuleListener` is asynchronous, so nothing here can
propagate into the transition that published the event. One accepted race — if scoring reads "no
estimate", the estimate commits, the refresh finds no prediction row yet, and only then scoring
commits, the row keeps `estimate_missing=true` with nothing left to re-trigger it. The snapshot
honestly records that the estimate was absent at scoring time.

**Training** (`ai/internal/ApprovalModelTrainingService.trainForOrganization`, one transaction per org
so a failing org rolls back only its own row; `trainAll` pages `OrganizationAdminService.list` and
swallows per-org `RuntimeException`):

1. Fetch decided samples over `training-lookback`, capped at 20 000 rows.
2. Gate: fewer than `min-training-samples` (50), or fewer than 10 per class → store the row with the
   sample counts, `coefficients = '{}'` and `serving=false`, so an admin can see *why*.
3. Featurize with **point-in-time approval rates** — each sample sees only strictly-earlier samples.
   This is not an optimization: with the Laplace-smoothed rate `(approved+1)/(decided+2)`, a
   leave-one-out implementation makes a sample's own rate differ by exactly `1/(decided+1)` according
   to its own label, which after standardization is a perfect inverse label predictor. It would drive
   holdout AUC to ≈1.0, wave a useless model through the quality gate, and then apply that large
   coefficient to an uninformative value at serving time.
4. Split deterministically by `floorMod(queryId.hashCode(), 100)` against `holdout-fraction` — no RNG
   anywhere, so a retrain over unchanged history reproduces the same model.
5. Train (`LogisticRegressionTrainer`), evaluate on the holdout (`ModelEvaluator`), and set
   `serving = auc >= min-auc-to-serve`. An empty or single-class holdout stores `auc = null` rather
   than the evaluator's `0.5` return, so "no ranking signal" cannot be mistaken for a measured 0.5.

Several numbers in that sequence are **compiled-in constants, not knobs** — worth knowing before
reaching for an env var that does not exist:

| Constant | Value | Where |
|---|---|---|
| Learning rate | `0.1` | `LogisticRegressionTrainer` — batch gradient descent from a zero init, early stop once the loss improves by less than `1e-7` **while still improving**; an iteration that makes the loss worse does not stop, it runs on to `max-iterations` |
| Standardization | z-score, sample (`n-1`) stddev | same; a stddev under `1e-9` is clamped to `1.0`, and the intercept is **not** regularized |
| Split buckets | `100` | `ApprovalTrainingSetBuilder` — percent granularity, so `holdout-fraction = 0.15` is representable |
| `MIN_SAMPLES_PER_CLASS` | `10` | `ApprovalModelTrainingService` — the per-class floor beside the configurable total |
| `MAX_TRAINING_ROWS` | `20 000` | same — a safety cap on the fetch, deliberately not a property |
| Accuracy threshold | `0.5` | same — the neutral midpoint, not a tuned operating point |

AUC is Mann-Whitney U with tie-averaged ranks. The gate **fails closed** in both directions: a
single-class holdout leaves `auc` null (and `serving` false) rather than accepting the evaluator's
`0.5`, and a model whose serialized `feature_names` no longer match the current schema is refused at
serving time rather than scored.

Tunables are `accessflow.ai.approval-prediction.*` — see
[docs/09-deployment.md](09-deployment.md#approval-prediction-af-645). `ApprovalPredictionTrainingJob`
(`ai/internal/scheduled/`, lock `approvalPredictionTrainingJob`) calls `trainAll()` every
`retrain-poll-interval` (default `P1D`), retraining every organization that is not disabled. The job
is thin: the org loop, the per-org error swallowing and the `enabled` switch are `trainAll()`'s, the
per-org transaction is the training service's, and the job's own `catch` covers the one thing outside
`trainAll()`'s per-org `try` — paging the organization list. Note the cadence is *per replica*, not
cluster-wide: `@SchedulerLock` guarantees one replica per run, but a `fixedDelay` timer starts at
context refresh, so an N-replica cluster retrains up to N times a day and every pod start triggers a
pass. That is redundant load, never divergence — training is deterministic, so a repeat pass over
unchanged history reproduces the same model. Schema:
[docs/03-data-model.md → approval_prediction_model / approval_predictions](03-data-model.md#approval_prediction_model).

**Read side (AF-653).** Three surfaces, and structurally nothing else — that is what keeps the
advisory-only guarantee enforceable rather than merely stated:

- `GET /queries/{id}` carries an `approval_prediction` block, assembled in
  `core/internal/DefaultQueryRequestLookupService.toDetailView` from
  `QueryDetailView.ApprovalPredictionDetail`. Unlike the AF-624 cost estimate next to it there is no
  reciprocal `query_requests.approval_prediction_id` FK to short-circuit on, so this is one
  unconditional `findByQueryRequestId` on the unique index per detail fetch. The block deliberately
  omits `model_id`, `feature_schema_version`, the feature snapshot and the failure message — the
  snapshot exists for operator explainability, not for the wire.
- `GET /reviews/pending` carries a nullable `approval_probability` per row.
  `DefaultReviewService.listPendingForReviewer` filters the page to the actionable rows first, then
  makes **one** `ApprovalPredictionLookupService.findByQueryRequestIds` call for the whole page and
  keys the result into a `Map<UUID, Double>`. A per-row lookup here would be an N+1 on every queue
  render. Sentinel rows carry no probability and are absent from the map, so the queue shows no badge
  and the detail endpoint is where the reason lives.
- `RealtimeEventDispatcher` consumes `ApprovalPredictionCompletedEvent` and pushes
  `query.prediction_complete`. It is the mirror image of the submitter-only
  `query.estimate_complete`: it fans out via `eligibleReviewersForLowestStage` — the review plan's
  **first-stage** approvers, the same set `review.new_request` targets — and to nobody else. That
  helper already excludes the submitter, which is exactly right here, because `QueryReadController`
  withholds the prediction block from the submitter (see the visibility rule below); pushing them a
  refetch trigger would only make them refetch nothing. The payload is a refetch trigger, not data:
  `query_id` and a `probability` that is JSON-`null` on the skipped / failed rows.
- **Visibility.** `GET /queries/{id}` serves the prediction block only to callers holding
  `QUERY_REVIEW` who are **not** the query's submitter — a reviewer reading their own request is
  excluded too. The block is dropped from the response rather than hidden client-side: showing a
  submitter how their peers are likely to vote on their own open request invites cancelling and
  resubmitting until the number looks better. `QueryDetailPage.tsx` applies the same predicate to
  the card, so the rule holds even if a client is written against the raw API.

  Note the recipient set is the plan's first stage, not the query's *currently open* stage. That is
  exact for the first push (the prediction lands as the query enters review at stage 1), but the
  late-estimate rescore can publish a second event after a stage-1 decision, and that one still
  targets stage-1 approvers. Resolving the open stage needs the decision list and the routing
  engine's effective `min_approvals`, both of which live behind `workflow`-internal beans the
  `realtime` module cannot reach — closing the gap would mean a new `api` surface for a refetch hint.
  Not worth it: the reviewer who now needs the badge is still served by their next queue fetch.

Nothing localizes `skipped_reason` on the way out — it stays the machine token the serving path
wrote, and the client resolves it against the reader's locale.

---

## Audit Logging

Lives in `audit/`. Owns the `audit_log` table (entity + repository) and exposes a single public service: `AuditLogService` (`audit/api/AuditLogService.java`). Two write paths:

1. **Synchronous, from controllers** — for user-initiated actions where `ip_address` / `user_agent` should be captured from the live `HttpServletRequest`. The controller calls `auditLogService.record(...)` after the underlying service call succeeds (or in the catch block for failed login). `RequestAuditContext.from(httpRequest)` extracts IP (honoring `X-Forwarded-For`) and user-agent. Failures in audit writes are caught and logged — never propagated to the caller.

   | Controller | Action |
   |---|---|
   | `AuthController.login` | `USER_LOGIN`, `USER_LOGIN_FAILED`, `USER_LOGIN_TOTP_FAILED` |
   | `AdminUserController` | `USER_CREATED`, `USER_DEACTIVATED` |
   | `MeProfileController` | `USER_PROFILE_UPDATED`, `USER_PASSWORD_CHANGED`, `USER_TOTP_ENABLED`, `USER_TOTP_DISABLED` |
   | `DatasourceController` | `DATASOURCE_CREATED`, `DATASOURCE_UPDATED`, `PERMISSION_GRANTED`, `PERMISSION_REVOKED` |
   | `QuerySubmissionController` | `QUERY_SUBMITTED` |
   | `ReviewController` | `QUERY_APPROVED`, `QUERY_REJECTED` |
   | `QueryReadController.cancel` | `QUERY_CANCELLED` |

2. **Asynchronous, from `AuditEventListener`** — for system-driven state transitions where there is no live request thread. Uses Spring Modulith's `@ApplicationModuleListener` (which is `@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener(AFTER_COMMIT)`). IP/UA are intentionally null on these rows. Each handler swallows runtime failures to keep the publishing transaction unaffected.

   | Event | Action |
   |---|---|
   | `AiAnalysisCompletedEvent` | `QUERY_AI_ANALYZED` |
   | `AiAnalysisFailedEvent` | `QUERY_AI_FAILED` |
   | `QueryReadyForReviewEvent` | `QUERY_REVIEW_REQUESTED` |
   | `QueryAutoApprovedEvent` | `QUERY_APPROVED` (system actor, `actor_id = NULL`, metadata `{"auto_approved": true}`) |
   | `DatasourceDeactivatedEvent` | `DATASOURCE_UPDATED` with metadata `{"change":"deactivated"}` |

`AuditAction` extends the doc enum with `QUERY_AI_FAILED` so the read API can filter for failed AI runs without parsing the JSONB metadata. `QUERY_AI_REANALYZE_REQUESTED` is written synchronously from `QueryReadController.reanalyze` whenever a reviewer or admin re-runs analysis through [`POST /queries/{id}/reanalyze`](04-api-spec.md#post-queriesidreanalyze--response-202); the row captures the caller's IP and User-Agent in addition to the standard fields.

### Read endpoint

`GET /api/v1/admin/audit-log` — `@PreAuthorize("hasRole('ADMIN')")`. Filters: `actorId`, `action`, `resourceType`, `resourceId`, `from`, `to`. Pagination via Spring `Pageable`; max page size 500. Always scoped to the caller's organization — admins in org A cannot read org B's rows.

`GET /api/v1/admin/audit-log/export.csv` — same filter set, same ADMIN-only authorization. The body is built by `audit/internal/AuditLogCsvService` and returned as a `StreamingResponseBody`: the service walks the result in 500-row pages and flushes each page to the response `OutputStream`, capping the export at 50,000 rows and emitting `X-AccessFlow-Export-Truncated: true` when the filter matches more. The export itself is recorded as an `AUDIT_LOG_EXPORTED` row (resource `audit_log`, no resource id) whose `metadata` captures the filter and the row counts, so the export is part of the same tamper-evident chain it is exporting.

### Module isolation

- The `audit_log` entity lives under `audit/internal/persistence/entity/`, with plain `UUID` columns for `organizationId` / `actorId` (no JPA `@ManyToOne` joins — same pattern as `NotificationChannelEntity`). Postgres-level FKs to `organizations` and `users` were dropped in V14 so audit history survives org/user deletion.
- Cross-module event types live in `core/events/` (`QueryReadyForReviewEvent`, `QueryAutoApprovedEvent`, `QueryStatusChangedEvent`, `AiAnalysisCompletedEvent`, `ApprovalPredictionCompletedEvent`) and `workflow/events/` (`QueryApprovedEvent`, `QueryRejectedEvent`, `QueryCancelledEvent`, `QueryExecutedEvent`, `ReviewDecisionMadeEvent`). Keeping the read-side events in `core/events/` lets audit and realtime consume them without depending on `workflow`, breaking what would otherwise be a slice cycle (workflow controllers call `AuditLogService` synchronously).

### Chain verification

Both hardening layers originally listed as deferred have shipped: the **tamper-evident
HMAC-SHA256 hash chain** (`previous_hash` / `current_hash`, V26 — keyed by `AUDIT_HMAC_KEY`
or an HKDF derivative of `ENCRYPTION_KEY`, see `audit/internal/AuditChainHasher`) and the
**separate audit-writer DB role** (`AUDIT_DB_USER`, V38 — see
[docs/09-deployment.md → audit_log role separation](09-deployment.md#audit_log-role-separation)).

Verification entry points:

- **Per organization** — `AuditLogService.verify(orgId, from, to)`, exposed to admins as
  `GET /api/v1/admin/audit-log/verify`. Walks the chain ASC by `(created_at, id)`,
  recomputing every HMAC; pre-V26 NULL-hash rows are skipped as pre-chain.
- **All organizations** (AF-458) — `AuditLogService.verifyAllOrganizations()` returns one
  `AuditChainVerificationSummary` per org. Consumed by `audit/internal/AuditChainStartupVerifier`:
  when `accessflow.audit.verify-chain-on-startup` (env `ACCESSFLOW_AUDIT_VERIFY_CHAIN_ON_STARTUP`,
  default `false`) is set, the sweep runs on `ApplicationReadyEvent` and logs a per-org
  outcome — INFO when intact, ERROR with the first bad row id + reason when broken. It
  never fails startup. This is the post-restore integrity check in the
  [disaster-recovery runbook](09-deployment.md#disaster-recovery); verification only
  succeeds under the same HMAC key material the rows were written with.

### SIEM & WORM audit streaming (#628)

Admin-configurable **external audit sinks** stream `audit_log` rows to SIEM / WORM destinations, managed on `/admin/audit-sinks` (gated by the `AUDIT_SINK_MANAGE` permission — see [04-api-spec.md → Audit Sinks](04-api-spec.md#audit-sinks-adminaudit-sinks--siem--worm-streaming-628)). Four sink types: `SPLUNK_HEC` (newline-stacked HEC envelopes, `Authorization: Splunk <token>`), `SYSLOG_CEF` (RFC 5424 frames carrying CEF:0 over TCP or TLS with RFC 6587 octet-counting; TLS validates against the system truststore, no skip-verify; severity is a v1 heuristic — 7 for actions containing `BREAK_GLASS`/`DELETED`/`REJECTED`, else 5), `HTTPS_BATCH` (JSON array of events, HMAC-signed with the webhook signature contract: `X-AccessFlow-Signature: sha256=<hex>`, `X-AccessFlow-Event: audit.batch`, `X-AccessFlow-Delivery`), and `S3_OBJECT_LOCK` (periodic signed JSONL segments under a WORM retention lock).

Mechanics:

- **Durable keyset cursor, at-least-once.** Each `audit_sinks` row carries a `(cursor_created_at, cursor_id)` keyset cursor over the append-only `audit_log` (`created_at` is not unique — same rationale as the `grant_usage_watermark`, V135; the range read rides `idx_audit_log_org_created_id`). The cursor advances only after a successful delivery, so receivers dedupe on the immutable event `id`.
- **Clustered-safe drain.** `AuditSinkDrainJob` (`audit/internal/scheduled/`, `@SchedulerLock(name = "auditSinkDrainJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT20S")`, cadence `accessflow.audit.sinks.drain-interval`, default `PT30S`) drains, per enabled+due sink, up to `max-batches-per-tick` (default 5) batches of `batch-size` (default 500) rows through the sink's deliverer.
- **Failure isolation, retry forever.** Export is strictly downstream of the synchronous audit write path — a dead sink never blocks audit writes, and per-sink failures are isolated. On failure: `last_error` recorded (truncated to 500 chars), `consecutive_failures` incremented, retry with backoff 30 s → 2 min → 10 min, then every 10 min forever; the durable cursor makes retry-forever safe (no exhaustion state). Health (cursor position, last success, last error, consecutive failures, next retry, capped behind-count) is embedded in the admin list response.
- **Canonical event.** Every sink type serializes the same canonical audit event JSON — all `audit_log` columns, metadata embedded, ISO-8601 microsecond `created_at`, lowercase-hex `previous_hash`/`current_hash` — so any exported window is independently chain-verifiable.
- **WORM segments.** The S3 deliverer flushes a segment when the batch is full or the oldest pending row exceeds `segment_max_age` (default `PT15M`): key `<prefix><yyyy/MM/dd>/audit-<orgId>-<fromCreatedAt>-<toCreatedAt>-<lastRowId>.jsonl`, uploaded with the Object Lock retention (`retention_mode` default `COMPLIANCE`), plus a sibling `<key>.sig` holding the base64 SHA256withRSA signature of the segment bytes (the JWT RSA key — same as compliance exports, verifiable via `GET /admin/compliance/signing-certificate`). The segment's last line carries the org chain head, so the signature covers the chain head — combined with the in-DB HMAC chain, an externally verifiable WORM copy.
- **Test endpoint.** `POST /admin/audit-sinks/{id}/test` synchronously delivers one synthetic event through the sink's deliverer (for S3: a small test segment **without** a retention lock, named `<prefix>test/…`); destination rejection maps to 502 `AUDIT_SINK_TEST_FAILED`.
- **Audit of the sinks themselves.** Admin CRUD writes best-effort `AUDIT_SINK_CREATED`/`_UPDATED`/`_DELETED` rows (resource `audit_sink`; metadata name + type, never secrets). Deliveries are deliberately not audited per batch — that would feed back into the stream being drained.

---

## Observability and tracing

AccessFlow ships with distributed tracing enabled out of the box. Every HTTP request gets a W3C trace context and the resulting `traceId` / `spanId` propagate into three places — log lines, the `ProblemDetail` error envelope, and (optionally, when an exporter is configured) an OpenTelemetry collector. The aim is correlation: a user reporting an error can copy a trace id from the UI and an operator can grep server logs for the same id.

**Wiring (no exporter by default).** The backend pulls three dependencies in `backend/pom.xml`:

- `spring-boot-starter-actuator` — exposes `/actuator/health` and `/actuator/info` only (the exposure list is narrowed in `application.yml`).
- `spring-boot-micrometer-tracing` + `spring-boot-micrometer-tracing-opentelemetry` — Spring Boot's auto-configuration glue that creates an OpenTelemetry SDK and the bridge tracer.
- `io.micrometer:micrometer-tracing-bridge-otel` — the bridge that populates `org.slf4j.MDC` with `traceId` and `spanId` for every active span.

Spans are generated on every request via `ServerHttpObservationFilter` (auto-registered by Spring Boot). No exporter dependency is bundled, so traces are visible in logs and in error responses but not shipped to a remote collector — operators can wire an OTLP / Zipkin exporter later via Spring's standard `management.otlp.tracing.*` / `management.zipkin.tracing.*` properties without changes to AccessFlow.

**Log pattern.** `application.yml` overrides Spring Boot's default level pattern:

```yaml
logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

Every log line is therefore prefixed with `[accessflow-app,<traceId>,<spanId>]`. The brackets are empty (`[accessflow-app,,]`) for log lines emitted outside a request scope (startup, scheduled jobs, webhook retries on a non-request thread, etc.) — that is correct behavior.

**Structured logging (JSON).** By default the console emits the plain-text pattern above. Setting `ACCESSFLOW_LOGGING_STRUCTURED_FORMAT=logstash` (or `ecs` for Elastic Common Schema, or `gelf` for Graylog) switches the console appender to emit one JSON object per line — ready to ship to ELK / OpenSearch / Loki / Datadog without an intermediate parser. The implementation uses Spring Boot's built-in `logging.structured.format.console` support (Spring Boot 3.4+) — no `logstash-logback-encoder` dependency, no custom `logback-spring.xml`. MDC values populated by the Micrometer→OTEL bridge (`traceId`, `spanId`) become top-level fields in every JSON variant, so trace correlation works the same in text and JSON modes. The Spring Boot ASCII banner is hidden by default (`spring.main.banner-mode=off`) so it does not pollute structured streams; set `SPRING_MAIN_BANNER_MODE=console` via Spring relaxed binding to restore it.

**`ProblemDetail` integration.** Two places attach `traceId` to error responses:

- `security/internal/web/ProblemDetailTraceAdvice` is a `ResponseBodyAdvice<ProblemDetail>` that reads `MDC.get("traceId")` and calls `pd.setProperty("traceId", id)` on every `ProblemDetail` returned by any `@RestControllerAdvice` (`GlobalExceptionHandler`, `ReviewExceptionHandler`, `NotificationsExceptionHandler`, `AiAnalysisExceptionHandler`). One advice covers all ~50 ProblemDetail constructions across the codebase — handler authors do not need to remember to set `traceId`.
- `security/internal/web/SecurityExceptionHandler.writeProblemDetail()` reads the same MDC key inline. This handler writes directly to `HttpServletResponse` (it implements `AuthenticationEntryPoint` / `AccessDeniedHandler`) and so bypasses Spring's `ResponseBodyAdvice` chain — the trace id has to be appended manually for the 401 and 403 cases.

**Sampling.** Defaults to `1.0` (sample every request). For high-traffic deployments operators can lower this with `ACCESSFLOW_TRACING_SAMPLING_PROBABILITY` (e.g. `0.1` to sample one in ten). Sampling controls export volume — log MDC and `ProblemDetail.traceId` are populated regardless of the sampling decision because the trace context is always active per request.

**OTLP trace export (AF-454).** The backend bundles `io.opentelemetry:opentelemetry-exporter-otlp`, so trace export is first-class — no rebuild needed. Export is **off by default** (no endpoint configured); setting the standard `OTEL_EXPORTER_OTLP_ENDPOINT` (the full OTLP/HTTP traces URL, e.g. `http://tempo:4318/v1/traces`) turns it on. `OtlpTracingEnvironmentPostProcessor` (registered via `META-INF/spring.factories`) bridges `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (and optional `OTEL_EXPORTER_OTLP_HEADERS`, e.g. a Honeycomb team key) onto Spring Boot 4.1's `management.opentelemetry.tracing.export.otlp.*` properties **only when present** — so an unset endpoint never creates an exporter pointed at an invalid URL. The exported spans are correlated with the same `traceId` that appears in logs and `ProblemDetail`; sampling (above) controls export volume, MDC is unaffected.

**Instrumented spans.** The proxy pipeline is traced with the Micrometer Observation API (one instrumentation point yields both a span and a timer):

| Span / meter | Where | Tags |
|---|---|---|
| `accessflow.query.parse` | `DefaultQueryParser.parse` | `db_type`, `outcome` |
| `accessflow.datasource.acquire` | `RoutingDataSourceResolver.acquire` (child of execute) | `query_type`, `outcome` |
| `accessflow.query.execute` | `DefaultQueryExecutor.execute` | `db_type`, `query_type`, `outcome` |
| `accessflow.ai.analyze` | `DefaultAiAnalyzerService` (the `strategy.analyze` call) | `provider`, `risk_level`, `outcome` |

**Metrics & Grafana dashboards.** `micrometer-registry-prometheus` is bundled and `/actuator/prometheus` is exposed (and `permitAll` for in-cluster scraping — restrict it with a NetworkPolicy; do not route `/actuator` through the public ingress). Beyond the Observation timers above, the `WorkflowMetricsListener` turns lifecycle events into business meters — `accessflow.query.{submitted,approved,rejected,executed}` counters (rejected split by `reason=manual|auto|timeout`), the `accessflow.query.approval.latency` SLA timer (submission → approval), and the `accessflow.query.execution.duration` timer — while `DefaultAiAnalyzerService` records `accessflow.ai.tokens` (per `provider` + `type=prompt|completion`). HikariCP `hikaricp_connections_*` pool metrics bind automatically. The Helm chart ships two pre-built dashboards ([`charts/accessflow/dashboards/`](../charts/accessflow/dashboards/)) — *Query Pipeline* (volume, latency, rejection rate, approval SLA, pool stats) and *AI Usage* (analyses, latency, token cost, risk mix) — as a `grafana_dashboard`-labelled ConfigMap when `dashboards.enabled=true`. See [docs/09-deployment.md](09-deployment.md).

---

## Self-service Profile and 2FA

Lives in `core/` (services) and `security/internal/web/` (REST surface). Endpoints: `GET /me`, `PUT /me/profile`, `POST /me/password`, `POST /me/totp/{enroll,confirm,disable}` — see `docs/04-api-spec.md` for the full contract.

- `core/api/UserProfileService` is the public service interface; `core/internal/DefaultUserProfileService` is the only implementation.
- `core/internal/totp/TotpCodec` wraps the `dev.samstevens.totp` library (secret generation, code verification, QR data URI, recovery-code generation). Issuer is hard-coded to `AccessFlow`; account-name in the otpauth URL is the user's email.
- `core/api/TotpVerificationService` (implemented by `DefaultTotpVerificationService`) is consumed by `LocalAuthenticationService.login` at sign-in to verify a 6-digit TOTP **or** consume a single-use backup recovery code. Backup codes are stored as a JSON array of bcrypt hashes, AES-256-GCM-encrypted via the existing `CredentialEncryptionService`; verified codes are removed from the array on use.
- The TOTP shared secret is AES-256-GCM-encrypted on the user row (`totp_secret_encrypted`, `@JsonIgnore`). It is decrypted briefly inside the verification service and never returned to the API surface.
- Password change and 2FA disable revoke **all** of the user's refresh tokens. The bridge is `core/api/SessionRevocationService`, implemented by `security/internal/DefaultSessionRevocationService` (delegates to `RefreshTokenStore.revokeAllForUser`). Keeping the interface in `core.api` keeps modulith boundaries clean — `core.internal` never references `security.internal`.
- Login flow change: `LocalAuthenticationService.login` runs the password check first, then `totpVerificationService.isEnabled(userId)`. If 2FA is enabled it requires `LoginCommand.totpCode`. Missing code → `TotpRequiredException` (mapped to 401 `TOTP_REQUIRED`); bad code → `TotpAuthenticationException` (401 `TOTP_INVALID`). Both extend Spring's `AuthenticationException` so existing filters keep working; `GlobalExceptionHandler` has dedicated mappers that produce stable error codes the frontend switches on.
- SAML-authenticated accounts (`auth_provider = SAML`) cannot change their password or enrol in 2FA — `DefaultUserProfileService` short-circuits with `PasswordChangeNotAllowedException` for those paths. They may still update their display name via `PUT /me/profile`.

---

## Setup Progress

Lives in `api/` (the cross-cutting REST aggregator module). Powers the frontend setup-completion widget that nags fresh-install admins until they have at least one datasource, one review plan, and an AI provider configured.

- `core/api/OrganizationSetupLookupService` — public interface in `core` exposing `hasAnyDatasource(orgId)` and `hasAnyReviewPlan(orgId)`. Backed by derived `existsByOrganization_Id` repository methods so no rows are loaded just to count.
- `api/internal/DefaultSetupProgressService` — combines the two lookups with `ai.api.AiConfigService#getOrDefault` to compute `SetupProgressView`. AI is considered configured when the merged config reports `apiKeyMasked == true` (an API key is stored, whether via DB row or env defaults) **or** when the provider is `OLLAMA` (local, needs no key).
- `api/internal/web/AdminSetupProgressController` — `GET /api/v1/admin/setup-progress`, `@PreAuthorize("hasRole('ADMIN')")`. Returns a snake_case JSON snapshot; see [`docs/04-api-spec.md`](04-api-spec.md#get-adminsetup-progress).

Placing the controller in `api/` (which imports `core.api` and `ai.api` cleanly) avoids a cycle between `core` and `ai`. The service runs read-only in a single transaction.

---

## System SMTP and user invitations

System SMTP lives in `core` (entity `SystemSmtpConfigEntity` under `core/internal/persistence/entity`, service `core.api.SystemSmtpService`). Storage is per-organization (one row, enforced by UNIQUE on `organization_id`) and the password is encrypted with the same `CredentialEncryptionService` used by `notification_channels`. Reads through `SystemSmtpService.resolveSendingConfig` return a transient `SystemSmtpSendingConfig` record with the decrypted password — callers MUST treat it as ephemeral (never log, never persist).

Two consumers depend on it:

1. **`notifications/internal/SystemEmailFallback`** — invoked by `NotificationDispatcher` after the per-channel loop. When the resolved channel list contains zero `EMAIL` rows AND the event has an email template AND `ctx.recipients()` is non-empty, the fallback converts the system SMTP into an `EmailChannelConfig` and routes through `EmailNotificationStrategy.deliverInternal(ctx, config)` — the same code path as per-channel email. A `SystemSmtpDeliveryException` raised by the JavaMail send is caught and logged so the workflow state machine is unaffected.

2. **`security/internal/DefaultUserInvitationService`** — implements the invitation lifecycle (entity `UserInvitationEntity`, repo `UserInvitationRepository`). On invite the service generates a 32-byte SecureRandom token, Base64URL-encodes it for the email, persists only its SHA-256 hex digest in `token_hash`, renders `templates/email/user-invitation.html` via the auto-configured `SpringTemplateEngine`, and dispatches through `SystemSmtpService.sendSystemEmail`. Accept hashes the inbound token, validates status + expiry, calls `UserAdminService.createUser` with the encoded password, and marks the row `ACCEPTED`. Status transitions: `PENDING → ACCEPTED` (terminal), `PENDING → REVOKED` (terminal, admin action), `PENDING → EXPIRED` (terminal; set lazily when a preview/accept request lands after `expires_at`). Resend rotates the token and resets `status` to `PENDING`.

Configuration property: `accessflow.security.invitation.ttl` (ISO-8601 Duration, default `P7D`, env `ACCESSFLOW_SECURITY_INVITATION_TTL`). The accept-URL base is `accessflow.security.invitation.accept-base-url`, defaulting to `ACCESSFLOW_PUBLIC_BASE_URL`.

The setup endpoint (`POST /api/v1/auth/setup`) was extended to auto-login: after `BootstrapService.performSetup` the controller calls `AuthenticationService.login(...)` with the just-supplied plaintext password and returns a `LoginResponse` plus a `refresh_token` cookie, so the SPA can chain straight into `PUT /admin/system-smtp` without a second sign-in.

Audit actions added: `USER_INVITED`, `USER_INVITATION_RESENT`, `USER_INVITATION_REVOKED`, `USER_INVITATION_ACCEPTED`, `SYSTEM_SMTP_UPDATED`, `SYSTEM_SMTP_DELETED`, `SYSTEM_SMTP_TEST_SENT`. Resource types: `system_smtp`, `user_invitation`.

---

## Password reset (self-service)

Lets a user who has forgotten their password recover access without admin intervention. Three public endpoints under `/api/v1/auth/password/...` (see [`04-api-spec.md`](04-api-spec.md)) plus a single email per request, delivered through the same `SystemSmtpService` as invitations.

**Service:** `security/internal/DefaultPasswordResetService` (interface `security.api.PasswordResetService`). Token storage uses entity `PasswordResetTokenEntity` and repository `PasswordResetTokenRepository` in `security/internal/persistence/`. Status enum `security.api.PasswordResetStatusType` mirrors the `password_reset_status` Postgres enum: `PENDING | USED | REVOKED | EXPIRED`.

`requestReset(email)` is **enumeration-safe** — it always returns to the caller without throwing, and only sends an email when all of the following hold:

1. A user matches the email exactly (case-insensitive lookup via `UserQueryService.findByEmail`).
2. `authProvider == LOCAL` (SAML / OAuth2 users have no password to reset).
3. `isActive == true`.
4. `passwordHash` is non-null (defense-in-depth).
5. The user's org has system SMTP configured.

When eligible, the service marks any existing `PENDING` row for that user as `REVOKED`, inserts a new row with a 32-byte SecureRandom base64url token (only the SHA-256 hex stored in `token_hash`), renders `templates/email/password-reset.html`, and dispatches via `SystemSmtpService.sendSystemEmail`. The partial unique index `uq_password_reset_tokens_pending_user` enforces one-pending-per-user at the database level; a concurrent insert that loses the race is swallowed.

`previewByToken` and `resetPassword` validate by hashing the inbound plaintext and looking up `token_hash`. Status transitions: `PENDING → USED` (terminal, on successful reset), `PENDING → REVOKED` (terminal, superseded by a newer request), `PENDING → EXPIRED` (terminal, lazily set when a preview/reset lands after `expires_at`).

Password mutation goes through `core.api.UserProfileService.resetPassword(userId, newPassword)` — a new method that mirrors `changePassword` but skips the current-password check. It still enforces the LOCAL-account guard and calls `SessionRevocationService.revokeAllSessions(userId)` so any logged-in sessions are kicked out. This keeps all password-hash mutations inside `core` rather than reaching into `core.internal` from the security module.

Configuration: `accessflow.security.password-reset.ttl` (ISO-8601 Duration, default `PT1H`, env `ACCESSFLOW_SECURITY_PASSWORD_RESET_TTL`); reset-link base `accessflow.security.password-reset.reset-base-url` (default `http://localhost:5173`, env `ACCESSFLOW_SECURITY_PASSWORD_RESET_RESET_BASE_URL`). The emailed URL is `{base}/reset-password/{plaintextToken}`.

Audit actions added: `USER_PASSWORD_RESET_REQUESTED` (only when the email resolves to a real LOCAL active account — unknown-email requests still return 202 but skip the audit row), `USER_PASSWORD_RESET_COMPLETED`. Both are written inline by `AuthController` so the request's IP and User-Agent are captured.

---

## Realtime / WebSocket

Lives in `realtime/`. Pushes domain events to connected frontend clients over a single WebSocket endpoint at `/ws`, so status changes, review notifications, and execution outcomes appear in the UI within ~1 s without polling. Wire format and event list are defined in [`docs/04-api-spec.md`](04-api-spec.md#websocket-events).

### Handshake auth

Browsers cannot set a custom `Authorization` header on a WebSocket upgrade, so the access token is passed as a query parameter: `ws://host/ws?token=<JWT>`.

`realtime/internal/ws/JwtHandshakeInterceptor` (a `HandshakeInterceptor`) extracts the token, calls `AccessTokenAuthenticator` from `security/api/`, and on success stores the resolved `JwtClaims` on the handshake attributes. The same RSA signing key, expiry, and type checks as the REST `JwtAuthenticationFilter` apply — there is no separate WS token. On failure the interceptor returns `false` and the upgrade is rejected with HTTP 403.

`/ws` is added to the `permitAll()` list in `SecurityConfiguration`; the interceptor performs auth, not the JWT filter (which only reads `Authorization`).

### Session registry and fan-out

`realtime/internal/ws/SessionRegistry` maintains a `ConcurrentMap<UUID userId, Set<WebSocketSession>>`. The handler (`RealtimeWebSocketHandler extends TextWebSocketHandler`) registers on `afterConnectionEstablished` and unregisters on `afterConnectionClosed`. Per-session sends are synchronized on the session (Spring requires single-threaded sends per session); a send that throws drops the offending session from the registry without affecting the user's other tabs.

### Source events → WS events

| WS event                | Source domain event                                       | Target               |
| ----------------------- | --------------------------------------------------------- | -------------------- |
| `query.status_changed`  | `QueryStatusChangedEvent` (in `core/events/`)             | submitter            |
| `query.executed`        | `QueryExecutedEvent` (in `workflow/events/`)              | submitter            |
| `ai.analysis_complete`  | `AiAnalysisCompletedEvent` (in `core/events/`)            | submitter            |
| `query.estimate_complete` | `QueryEstimateCompletedEvent` / `QueryEstimateFailedEvent` (in `core/events/`, AF-624) | submitter          |
| `query.prediction_complete` | `ApprovalPredictionCompletedEvent` (in `core/events/`, AF-645) | first-stage approvers (never the submitter) |
| `review.new_request`    | `QueryReadyForReviewEvent` (in `core/events/`)            | eligible reviewers   |
| `review.decision_made`  | `ReviewDecisionMadeEvent` (in `workflow/events/`)         | submitter            |
| `notification.created`  | `UserNotificationCreatedEvent` (in `notifications/events/`) | the recipient user |
| `request_group.status_changed` | `RequestGroupStatusChangedEvent` (in `requestgroups/events/`) | submitter; reviewers on ready-for-review |
| `request_group.item_executed`  | `RequestGroupItemExecutedEvent` (in `requestgroups/events/`)  | submitter            |

`QueryStatusChangedEvent` is published from the single chokepoint `DefaultQueryRequestStateService.transitionTo(...)` and the explicit decision/execution paths in the same service — every status mutation funnels through entity save in this service.

`ReviewDecisionMadeEvent` fires from `DefaultReviewService.approve/reject/requestChanges` on every non-replay decision (the existing `QueryApprovedEvent`/`QueryRejectedEvent` pair is unchanged and still consumed by audit/notifications — they signal terminal state, not every review touch).

`QueryExecutedEvent` fires from `DefaultQueryLifecycleService.execute(...)` on both the success and failure branches.

### Dispatcher

`realtime/internal/RealtimeEventDispatcher` is a `@Component` with one `@ApplicationModuleListener` per source event. Each listener:
1. Builds the spec-shaped envelope `{event, timestamp, data}` via Jackson (`tools.jackson.databind.ObjectMapper`).
2. Resolves enrichment fields (datasource name, submitter email, AI risk) through the existing public lookup APIs in `core/api/` (`QueryRequestLookupService`, `DatasourceAdminService`, `UserQueryService`, `AiAnalysisLookupService`, `ReviewPlanLookupService`) — same pattern as `NotificationContextBuilder`.
3. Calls `SessionRegistry.sendToUser(userId, json)`.

Every handler wraps its body in try/catch and logs at ERROR; a transient WS or lookup failure never propagates back to the publishing transaction (same defensive pattern as `AuditEventListener` and `NotificationDispatcher`).

### JSON envelope

```json
{
  "event": "query.status_changed",
  "timestamp": "2026-05-07T10:31:00Z",
  "data": {
    "query_id": "uuid",
    "old_status": "PENDING_AI",
    "new_status": "PENDING_REVIEW"
  }
}
```

### Real-time collaboration relay (AF-441)

For collaborative editing of a query that is in review, the `/ws` channel becomes **bidirectional**.
`RealtimeWebSocketHandler.handleTextMessage` routes inbound client frames to
`realtime/internal/CollaborationCoordinator`, which:

1. **Authorizes joins** through `workflow.api.QueryCollaborationAccessService` — a single source of truth
   for "who may co-author this query": the submitter, an eligible reviewer (review-plan approver in
   datasource scope), or an admin, while the query is co-authorable (`PENDING_REVIEW`; the submitter may
   also co-author while `PENDING_AI`). This centralizes the reviewer-eligibility logic the review path and
   the dispatcher used to compute separately. An unauthorized join gets a `collab.denied` frame.
2. **Tracks query-scoped rooms** in `realtime/internal/ws/CollaborationRoomRegistry`
   (`ConcurrentMap<queryId, Map<sessionId, Participant>>`). A room is created on the first join and dropped
   when its last participant leaves, so memory is bounded by live collaboration. `afterConnectionClosed`
   evicts the session from every room and broadcasts the updated presence.
3. **Relays opaquely.** The backend never parses the Yjs payload — `collab.sync` (document) and
   `collab.awareness` (cursors/selections) frames are forwarded verbatim to the other members of the room.
   Convergence (conflict-free merge) is a client-side Yjs CRDT; the keystroke stream is **not persisted**.
   Late-joiner state is handled client-side: the first joiner of a fresh room seeds the shared document
   from the query's SQL (signalled by `seed` on `collab.joined`); peers exchange full state on each
   presence change.

**Approval safety.** Live edits are an ephemeral shared buffer — the backend never mutates the query's
`sql_text` under review. Committing the co-authored SQL goes through the existing `POST /api/v1/queries`
submit path, which re-enters the workflow at `PENDING_AI`; the self-approval guard in `DefaultReviewService`
is unchanged.

**Persisted discussion.** Inline comment threads (`workflow.api.QueryCommentService` →
`query_comments` table, audited via `QUERY_COMMENT_*` actions) are the durable collaboration artifact. A
`QueryCommentChangedEvent` drives a `collab.comment` WebSocket fan-out so collaborators' comment panels
refetch.

**Multi-replica caveat.** Rooms are per-node (in-memory), identical to the existing `SessionRegistry`
broadcast model — Spring application events are in-process. Cross-node room fan-out is out of scope; a
deployment that needs collaboration across replicas should pin a query's collaborators to one node
(sticky sessions) or front `/ws` with a single replica, as for the rest of the realtime module today.

---

## User API keys (security module)

User-managed API keys live alongside the rest of authentication in the **`security/` module**:

- **Persistence.** `api_keys` table (see `docs/03-data-model.md`),
  `security.internal.persistence.entity.ApiKeyEntity` + `repo.ApiKeyRepository`.
- **Service.** `security.api.ApiKeyService` (public — also consumed by the MCP tools' filter
  pipeline) with `DefaultApiKeyService` under `security.internal.apikey`. Issue / list / revoke
  / resolveUserId. Plaintext is shown once on creation and stored as SHA-256 only.
- **Hashing.** `security.internal.apikey.ApiKeyHasher` — `af_<32-byte base64url>` format,
  SHA-256 hex hash, 12-char display prefix.
- **Auth filter.** `security.internal.filter.ApiKeyAuthenticationFilter`, registered into the
  main Spring Security chain before `JwtAuthenticationFilter` in `SecurityConfiguration`. Reads
  `X-API-Key` or `Authorization: ApiKey …`, resolves to `JwtClaims`, populates an
  `ApiKeyAuthenticationToken` — same shape as the JWT path so downstream code is auth-agnostic.
- **Web.** `security.internal.web.ApiKeysController` exposes `/api/v1/me/api-keys` CRUD;
  `ApiKeysExceptionHandler` maps `ApiKeyDuplicateNameException` / `ApiKeyNotFoundException` to
  RFC 9457 `ProblemDetail`.

The full REST contract is in `docs/04-api-spec.md` → "API Keys".

## SCIM provisioning (scim module, #621)

The **`scim/` module** (`com.bablsoft.accessflow.scim`) is the SCIM 2.0 service provider: IdPs
(Okta, Entra ID, Keycloak, OneLogin) create/update/deactivate users and sync groups over
`/scim/v2/Users` and `/scim/v2/Groups`.

- **Own security chain.** `scim.internal.config.ScimSecurityConfiguration` contributes a
  `SecurityFilterChain` bean (`@Order(0)`, `securityMatcher("/scim/v2/**")`) — Spring collects
  filter-chain beans from any `@Configuration`, so the security module's
  `SecurityConfiguration` is untouched. `ScimTokenAuthenticationFilter` resolves the per-org
  bearer token (SHA-256 hash lookup on `scim_tokens`), re-checks `scim_config.enabled` and the
  org-disabled kill-switch per request, and a dedicated entry point emits 401 in the SCIM
  error envelope (never ProblemDetail; see `docs/07-security.md`).
- **Protocol layer.** Hand-rolled RFC 7644 pragmatic subset in `scim.internal.protocol`:
  Jackson wire records (annotated `@JsonNaming(LowerCamelCase)` to override the app-wide
  SNAKE_CASE strategy — SCIM mandates camelCase), an `eq`-only filter parser, and a PatchOp
  applier that handles both Okta shapes (no-path value objects, real booleans) and Entra
  shapes (`path: "active"` with string `"False"`, `members[value eq "…"]` removes).
- **Orchestration.** `ScimUserOrchestrator` maps the wire contract onto
  `core.api.ExternalUserDirectoryService` — the system-actor user primitives (create with
  quota + global-email uniqueness, partial update limited to SCIM-owned attributes, offset
  paging for `startIndex`). `ScimGroupOrchestrator` maps onto `core.api.UserGroupService`'s
  source-scoped member operations (`source=SCIM`). DELETE on a user deactivates — AccessFlow
  never hard-deletes users.
- **Admin surface.** `/api/v1/admin/scim-config` + `/api/v1/admin/scim/tokens`
  (`PERM_SSO_CONFIGURE`), show-once token issuance mirroring API keys.
- No scheduled jobs — SCIM is entirely IdP-push-driven.

### User deactivation fan-out (UserDeactivatedEvent)

Whenever a user's `is_active` transitions `true → false` — admin `PUT /admin/users/{id}
active=false`, admin `DELETE /admin/users/{id}`, or any SCIM deactivation path —
`core.events.UserDeactivatedEvent` is published (transition-only: deactivating an inactive user
publishes nothing). Consumers, both `@ApplicationModuleListener` (async, AFTER_COMMIT):

- `security.internal.UserDeactivationListener` — revokes every refresh token
  (`RefreshTokenStore.revokeAllForUser`); outstanding access tokens expire within
  `ACCESSFLOW_JWT_ACCESS_TOKEN_EXPIRY` (default 15 min).
- `access.internal.UserDeactivationGrantRevoker` — revokes the user's `APPROVED` JIT grants
  through the ordinary revocation path (system-attributed, idempotent, per-row failures
  swallowed).

Before #621, refresh-token revocation lived in `AdminUserController` and only fired on the
DELETE path; the event unifies all deactivation paths.

## API Access Governance (apigov module, AF-500)

The **`apigov/` module** governs outbound API calls (REST / SOAP / GraphQL / gRPC) with the same
review/approval/audit model as a database query. The foundation shipped today: connector
management (CRUD + encrypted auth secret + test-connection probe), schema ingestion (OpenAPI via
swagger-parser; GraphQL/proto/WSDL/**Postman collection** via dependency-free parsers behind the
`ApiSchemaParser` SPI — see **Postman collection import** below)
producing a normalized operation catalog with read/write classification, an **import-time operation
filter** (AF-614, below), and per-user "share-with-team" permissions. The governed-call pipeline is implemented end-to-end:
submit (`POST /api/v1/api-requests`) → async rate-limited AI risk scoring (`ai.api.ApiCallAnalyzer`,
fail-safe) → routing (`api_routing_policies`) + human review (`ApiReviewService`, self-approval
forbidden, via `ApiReviewStateMachine`) → guarded execution (`ApiExecutionService`: connector-auth
injection, response cap = min(per-connector `max_response_bytes`, system `max-response-bytes`
ceiling), **dynamic-variable resolution + substitution** (AF-613, below), recursive dot-path response
masking via `ColumnMasker`, immutable masked snapshot stored in full for download). Break-glass (`EMERGENCY_ACCESS` + `can_break_glass`), scheduled execution
(`ApiRequestRunJob`), review timeout (`ApiRequestTimeoutJob`), and text-to-API
(`ApiCallAnalyzer.generateApiCall`, schema connectors only) all mirror the query path. gRPC call
**execution** is the one piece not yet wired (REST/SOAP/GraphQL execute over the JDK HTTP client).
Cross-module references are bare UUIDs (like `access`); `apigov.api` is JDK + project types only.
Full design: [docs/17-api-governance.md](17-api-governance.md).

**Operation import filter (AF-614).** Real-world OpenAPI documents carry operations nobody should
reach through AccessFlow (`/internal/**`, `/actuator/**`, deprecated or admin-only surfaces). An
admin declares an `apigov.api.OperationFilter` per schema upload; it is **persisted on the
`api_schemas` row (`operation_filter` JSONB) and applied on the read path**, not at parse time —
`parsed_operations` always keeps the complete parsed catalog, so the filter is re-editable via
`PUT /api-connectors/{id}/schemas/{schemaId}/filter` without re-fetching a remote `sourceUrl` (the
raw document is stored in `raw_content` anyway, so parse-time filtering would not keep the excluded
surface off disk). `DefaultApiSchemaService.listOperations` runs the stored filter through
`OperationFilterMatcher`, which is the single choke point that makes filtered-out operations
unreachable from `/api-editor`, text-to-API, and the `allowed_operations` grant picker alike.
`operation_count` is the post-filter (kept) count; the pre-filter size is surfaced as
`totalOperationCount`. Evaluation is *keep when every non-empty include dimension matches AND no
exclude dimension matches* — **exclude wins**. Dimensions: path glob, HTTP verb (exact,
case-insensitive), operation-id glob, tag (exact, case-insensitive), and `excludeDeprecated`; globs
use the same `*`-only syntax as the routing-policy table globs. `ApiOperation` carries `tags` +
`deprecated` (boxed `Boolean`, so schemas parsed before AF-614 deserialize as `null` rather than
tripping `FAIL_ON_NULL_FOR_PRIMITIVES`); both are OpenAPI-only — for GraphQL SDL / WSDL / gRPC proto
the operation-id glob is the load-bearing dimension since `path` is synthesized.
`POST .../schemas/preview` dry-runs a filter (kept/excluded counts + the dropped operations) without
persisting or auditing. Upload **and** filter edits both write `API_SCHEMA_UPLOADED` audit metadata
carrying the filter, `total_operation_count`, and `excluded_count` (the edit path is distinguished by
`action=filter_updated`). An absent/empty filter is exactly the pre-AF-614 behaviour.

**Postman collection import (#612).** Plenty of teams have no OpenAPI/WSDL/SDL/proto document but do
have a Postman collection that is already the de-facto contract. `PostmanCollectionParser`
(`apigov/internal/schema/`) accepts a **Collection v2.x export** (v2.0 and v2.1 — a v1 export, which
has no `info.schema` and a flat `requests` array, is rejected 422 pointing at Postman's v2.1 export).
It is discovered by `SchemaParserRegistry` from its `supportedType()` like every other parser, so
neither the registry nor `POST /api-connectors/{id}/schemas` changed. Folders are flattened into a
slugified, deterministic `operationId` (`billing/invoices/create-invoice`, collisions suffixed
`-2`/`-3`) — `ApiOperation` is unchanged; collection-level `variable[]` values are substituted into
paths and every remaining `{{var}}` (and Postman's `:id` param form) normalizes to a `{var}`
template, leaving the connector's `base_url` to the admin. Read/write classification is the usual
safe-method rule, so the `require_review_reads`/`require_review_writes` gates apply unchanged.

Postman carries **examples, not schemas**, so `requestSchema`/`responseSchema` are *inferred* from
the saved example bodies by `JsonShapeInferrer` (JSON shape → a compact JSON-Schema-shaped document;
arrays typed from their first element, depth-bounded; urlencoded/form-data bodies become string
properties). That fidelity gap is real and is stated in the upload UI.

Two security properties shape the design. First, **no secret from an export is ever persisted**:
only the declared auth *type* is read, mapped onto `ApiAuthMethod` and surfaced as the schema row's
`detected_auth_method` so the admin knows what to re-enter on the connector. Second, `event` blocks
carry arbitrary pre-request/test **JavaScript** and are ignored entirely — never stored, evaluated,
or fed to the AI analyzer. Because `api_schemas.raw_content` persists the uploaded document verbatim,
neither property could hold by parsing alone, so the `ApiSchemaParser` SPI returns
`apigov.api.ParsedApiSchema` (operations + `detectedAuthMethod` + `sanitizedContent`) instead of a
bare operation list: the Postman parser hands back a redacted copy of the collection — every `event`
block and every auth credential array stripped — and `DefaultApiSchemaService` stores *that* as
`raw_content`. The other four parsers return `null` for both new fields and are otherwise unchanged.
Documents over 5 MiB or defining more than 2000 requests are rejected 422 (parser constants, mirroring
`DefaultApiSchemaService.MAX_FETCH_BYTES` — no new configuration property). A `sourceUrl` pointing at
a public collection link goes through the same fetch guard as any other schema type.

**Masking & classification (AF-518).** Connector-level response governance mirroring datasource
dynamic masking (AF-381) + classification tagging (AF-447), adapted to non-tabular bodies. A
masking policy / classification tag targets a field by `api_masking_matcher_type`
(`SCHEMA_FIELD`/`JSON_PATH`/`XML_PATH`/`REGEX`). `ApiConnectorMaskingResolutionService` resolves the
policies that apply to a submitter (reveal precedence via `core.api.UserQueryService` +
`UserGroupService`); `ApiExecutionService` merges them with the legacy `restricted_response_fields`
and `ApiResponseMasker.mask(body, contentType, masks)` applies them (strategy-aware JSON dot-path /
XPath / regex masking, reusing `ColumnMasker`) once, before the snapshot is stored — applied policy
ids land in the `API_REQUEST_EXECUTED` audit metadata. Classification tags auto-derive a masking
policy (`ApiConnectorClassificationDefaults`) and raise the apigov analyzer's risk
(`ApiConnectorClassificationRiskBooster`, applied in `ApiAnalysisListener`, fail-safe).

**Request composition (AF-517, #517).** A submitted call is composed like a Postman request:
`query_params` (percent-encoded onto the URL), per-request `request_headers` merged over the
connector's admin-defined `default_headers` (shown read-only in the editor), and a `body_type`
(`NONE`/`RAW`/`FORM_DATA`/`FORM_URLENCODED`/`BINARY`) the `ApiCallExecutor` turns into a body —
`RAW` text with its `request_content_type`, a URL-encoded form, a hand-built `multipart/form-data`
body (text + base64-decoded file parts), or a base64-decoded binary. Files ride **inline as bounded
base64** (no object storage); the total encoded body is capped by
`ACCESSFLOW_APIGOV_MAX_REQUEST_BODY_BYTES` (422 when exceeded). **W3C trace context:** a `trace_id` +
`span_id` are generated at submit and injected on execution as a `traceparent` header under the
connector's admin-renamable `trace_header_mapping`; both are filterable on the list. **Response
download (AF-521, #521).** The **full** masked response body is stored for download — bounded only by
a generous system-wide ceiling `ACCESSFLOW_APIGOV_MAX_RESPONSE_BYTES` (default 10 MiB), the absolute
backstop above any per-connector `max_response_bytes` (the executor caps at the **min** of the two,
and the cut is backed off to a complete UTF-8 boundary so a truncated body is never left with a split
character; `response_truncated` flags an upstream body that exceeded the ceiling). The executor
captures the upstream `Content-Type` into `response_content_type`, and
`GET /api-requests/{id}/response` streams the **complete** stored snapshot as an attachment in its
correct format. The **detail** view embeds only a bounded inline preview
(`ACCESSFLOW_APIGOV_RESPONSE_PREVIEW_BYTES`, default 64 KiB) of that snapshot and sets
`response_snapshot_preview_truncated` when clipped, so the page stays light while the download stays
whole. **Schema URL fetch:** `DefaultApiSchemaService.upload` fetches `sourceUrl` (http(s),
bounded size/timeout) when `rawContent` is blank — a third ingestion mode alongside paste and file
upload — raising `ApiSchemaFetchException` (422 `API_SCHEMA_FETCH_ERROR`) on failure. The submitter's
email is resolved via `core.api.UserQueryService` for the list/detail submitter column.

**Dynamic variables (AF-613).** A connector may declare named variables — rows in
`api_connector_variables` — that are evaluated per request and substituted into header values, the
path, query values and the body via `{{name}}` placeholders. This is what makes vendor contracts
requiring a computed value per call (HMAC request signing, nonces, timestamps, correlation ids,
idempotency keys, digests) governable at all: a submitter cannot hand-compute a signature for a
request a reviewer will approve minutes or hours later.

*Where it runs.* Inside `ApiExecutionService.executeCall`, between composing the `ApiCallRequest` and
handing it to the executor — deliberately **after** `buildHeaders`, so the evaluation context already
carries the connector defaults, the per-request headers, the trace headers and the auth header the
applier just computed (including a freshly minted OAuth2 bearer). An expression can therefore sign
the finished `Authorization` value, which the motivating vendor scheme requires. The OAuth2 401
retry rebuilds headers and re-resolves from scratch: a nonce must not be replayed, and a signature
over the stale token would only fail again. `executeInline` (the admin "try it" path) and break-glass
both route through the same seam.

*The contract.* `DefaultApiConnectorVariableResolutionService` orders the variables topologically
over their `{{var.x}}` references (`ApiVariableGraph`, Kahn's algorithm with the repository's
`(sort_order, created_at, id)` order as a total tie-break, so evaluation order is deterministic and
operator-controlled), renders each `expression` through `ApiVariableTemplate`, and feeds the result
to `ApiVariableEvaluator` — a fixed function set over `CONSTANT` / `UUID` / `TIMESTAMP` /
`EPOCH_MILLIS` / `RANDOM_HEX` / `HASH` / `HMAC` / `ENCODE`, with `HEX` / `BASE64` / `BASE64URL`
(unpadded) encodings. There is no scripting engine and no expression language, deliberately mirroring
the engine plugins' rejection of server-side scripting (`$where`, Painless, CQL UDFs).

*Two properties worth stating explicitly.* First, every `{{request.*}}` value describes the request
**before** substitution — the vendor scheme signs a body that still contains its own
`{{signature}}` placeholder, and resolving post-substitution would silently produce a digest the
vendor rejects. Second, rendering is **single-pass**: a substituted value is never re-scanned, which
is what keeps a per-request override an opaque literal rather than a path into another variable's
value.

*What is and is not substituted.* Header **values**, the path, query **values**, `RAW` bodies and
`TEXT` form parts are. Header **names** and query **keys** are not (a variable-named header could not
be meaningfully reviewed); nor is `base_url` (a variable-controlled host is an SSRF pivot); nor are
`BINARY` bodies and `FILE` form parts (base64 — substitution would corrupt rather than template
them). A variable may also carry a `target` of `header:<Name>` or `query:<name>`, applied after
substitution, for vendors that want the value in a fixed header rather than at a placeholder.

*Secrecy.* A resolved value never leaves the call: it is not persisted onto `api_requests`, not
written into the response snapshot, and not logged. The resolver cannot tell a harmless signature
from a `CONSTANT` holding a shared secret, so all of them are treated as sensitive — including
scrubbing them out of any upstream failure message before it reaches the persisted, reviewer-visible
`error_message` (the JDK's `IOException` embeds the full URI, which may carry a `query:`-targeted
signature). Save-time validation rejects cycles, dangling references and bad kind/field combinations
as 422s while the operator is editing, rather than as a failed run after approval.

*Per-request overrides.* A variable marked `overridable` may be given a value per request
(`api_requests.variable_overrides`, persisted so a reviewer approves exactly what will execute).
Supplying any override needs `can_override_variables` on the connector grant — a capability distinct
from submitting, OR-merged across user and group grants like `can_break_glass`, and never conferred
by a JIT access grant. A secret-bearing variable is never overridable (service check plus a database
CHECK constraint). Names outside the connector's overridable set are rejected with a single uniform
message, so a submitter cannot enumerate which variables hold secrets; values are bounded and
rejected outright if they contain CR, LF or NUL (request splitting). Grouped requests (AF-501)
deliberately do not accept overrides — connector variables still resolve for a grouped member, but
the group-item shape carries no override field.

## Request chaining & grouping (AF-501)

The **`requestgroups/` module** (`com.bablsoft.accessflow.requestgroups`, migration **V106**) lets a
user bundle several **query** members (across possibly different datasources) and **API-call** members
(AF-500 connectors) into one **grouped request** that is reviewed, approved, and executed as a single
element. Group items are **self-contained** — they carry their own inline SQL / inline API call on
`request_group_items` and do **not** create `query_requests` / `api_requests` rows. The **group** is
the unit of AI + review + approval; members are run by a group executor. This is feasible because the
low-level executors are row-id-free. `requestgroups.internal` depends only on the **`api`** packages of
core / proxy / ai / apigov / audit / notifications / scheduling; `requestgroups.api` is JDK + project
types only (enforced by `ApiPackageDependencyTest`).

**Group state machine** (illegal transitions throw `IllegalRequestGroupStateException`):

```
DRAFT → PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTING → EXECUTED
                                   ↘ REJECTED            (reviewer rejection)
                                   ↘ TIMED_OUT           (review-timeout auto-reject by GroupTimeoutJob)
                  ↘ PENDING_REVIEW or APPROVED           (routing / no-human-approval paths, group level)
PENDING_REVIEW → CANCELLED                               (submitter)
APPROVED       → CANCELLED                               (submitter, when scheduled_for is set and the
                                                          deferred run has not yet fired)
APPROVED       → EXECUTING → EXECUTED                    (all members succeed, or continue_on_error=true)
EXECUTING      → PARTIALLY_EXECUTED                      (a later member failed with continue_on_error=false;
                                                          remaining members SKIPPED)
EXECUTING      → FAILED                                  (the first member failed)
```

**Aggregated review (satisfy every plan).** `GroupReviewPlanResolver` aggregates the
`ReviewPlanSnapshot` of every **distinct** member target — `core.api.ReviewPlanLookupService` for
query members (per datasource), the AF-500 connector's `reviewPlanId` for API members. The group's
eligible approvers = the **union** across all members; the group advances to `APPROVED` only when
**every** member plan's per-stage `min_approvals_required` is satisfied, so bundling never weakens a
member's policy. One `group_review_decisions` row is recorded per reviewer/stage covering the whole
group (idempotent on `(group, reviewer, stage)`); **the submitter can never approve their own group**.
Routing policy may still escalate at group level.

**Per-member AI risk + aggregate.** On submit, each member is analyzed via the async pattern — a new
`ai.api` group-item analyzer (mirrors `ApiCallAnalyzer`) calls `AiAnalyzerStrategy.analyze(...)` for
query members and `analyzeApiCall(...)` for API members, gated by `AiRateLimiter` and **fail-safe** (a
failed member analysis escalates, never blocks). **Both member kinds persist** an `ai_analyses` row
keyed to the `request_group_item_id` (AF-531): query members via `AiAnalyzerService.analyzePreview`,
API members via `ApiAssistService.analyzeDetailed` — a persistence-grade variant of the editor preview
that returns the full `AiAnalysisResult` including the AF-518 data-classification risk boost. The
listener sets the item risk + `ai_analysis_id` and publishes `RequestGroupItemAnalyzedEvent`. The
group's aggregate risk = the **max** member `risk_level` / `risk_score`, recomputed as analyses
complete. The group **detail** view (`get`/`execute`, not the list) dereferences each item's
`ai_analysis_id` through `core.api.AiAnalysisLookupService.findDetailById` and embeds the full
analysis (summary, issues, optimizations, provider/model, tokens) in the item response.

**Ordered execution (no distributed rollback).** `GroupExecutionService` runs members in
`sequence_order`: a query member resolves masking + row-security directives
(`core.api.MaskingPolicyResolutionService` + `RowSecurityResolutionService`) and runs through
`proxy.api.QueryExecutor.execute`; an API member runs through a **new `apigov.api` inline-execution
entry point** (`ApiInlineExecutionService.executeInline(...)`) that performs connector-auth injection +
call + response masking and returns a snapshot **without** persisting an `api_requests` row, reusing
apigov's internal `ApiCallExecutor` / `ApiConnectorAuthApplier` / `ApiResponseMasker`. On the **first
failure** with `continue_on_error=false` the run stops, the remaining members are marked `SKIPPED`, and
the group becomes `PARTIALLY_EXECUTED` (or `FAILED` if the first member fails). With
`continue_on_error=true` all members run and the group becomes `EXECUTED` with mixed item statuses.
**There is no cross-target rollback** — an APPROVED group is *not* atomic; already-applied members
stay. Each member records its own result snapshot + audit row (alongside the group's group-level audit
rows) and publishes `RequestGroupItemExecutedEvent`; the group publishes
`RequestGroupStatusChangedEvent`.

**Build-time permission validation.** On submit, every member is validated against the submitter's
permission for its target — `core.api.DatasourceUserPermissionLookupService` for query members, a new
`apigov.api` connector-permission lookup for API members. A **break-glass group**
(`submission_reason = EMERGENCY_ACCESS`) requires `can_break_glass` on **every** member target.

**Audit & realtime.** New `AuditResourceType.REQUEST_GROUP` and `AuditAction.REQUEST_GROUP_*` values
record the group lifecycle alongside each member's own query/API audit row. New
`NotificationEventType.REQUEST_GROUP_*` values fan out submitted / approved / executed /
partially-executed / failed over every channel. `RealtimeEventDispatcher` maps the group events to the
`request_group.status_changed` and `request_group.item_executed` WebSocket events.

## Deployment Governance (deploygov module, epic #682)

The **`deploygov/` module** governs CI/CD deployments the way `apigov` governs outbound API calls.
#684 landed the persistence foundation (entities, repositories, V149–V151); **#688 added the
admin-facing configuration surface**; **#691 adds the submission half** — the trigger API, AI
analysis, the routing engine, and the status state machine. The reviewer decision flow, break-glass
and scheduled deploys (#692) and the gate endpoint + outcome reporting (#693) follow.

**Admin services (`deploygov/api/`).** Three org-scoped service interfaces, all mirroring the
`DefaultApiConnectorAdminService` conventions — `findByIdAndOrganizationId` + not-found on every
entry point (a cross-org id reads as 404, never "exists elsewhere"), duplicate-name guards on
create and on rename only, `reviewPlanId` validated through `core.api.ReviewPlanLookupService.findById`
with an org check (cross-org → `REVIEW_PLAN_NOT_FOUND`), `aiConfigId` stored unvalidated exactly
as apigov does:

- `DeploymentPipelineAdminService` — pipeline CRUD (paginated via `core.api.PageRequest`/`PageResponse`)
  plus environment CRUD. Environments are listed by `sort_order` then name; per-environment
  `requiredApprovals`/`reviewPlanId` overrides are cleared with explicit `clearRequiredApprovals`/
  `clearReviewPlan` flags (the apigov null-means-unchanged update convention).
- `DeploymentPermissionService` — the per-user and per-group trigger-grant quartets
  (list/grant/update/revoke; grant upserts by `(pipeline, user)` / `(pipeline, group)`, update
  preserves `created_by`/`created_at` provenance), plus
  `effectivePermission(pipelineId, userId)`.
- `DeploymentFreezeWindowService` — freeze-window CRUD. `update` is a **full replacement** (the
  one-off ↔ recurring shape CHECK makes partial patch error-prone), and the service re-validates
  the same rules the DDL enforces: exactly one complete shape, `ends_at` after `starts_at`, a
  valid IANA `timezone`, ISO day numbers 1–7, `start_time ≠ end_time`, and — a service-level
  tightening the DDL doesn't have — an `environment_id` scope requires its `pipeline_id`.
  Violations throw `IllegalDeploymentFreezeWindowException` with the message resolved through
  `MessageSource` at the throw site → `400 DEPLOYMENT_FREEZE_WINDOW_INVALID`.

**Effective permission resolution.** `deploygov/internal/EffectiveDeploymentPermissionResolver`
mirrors apigov's `EffectiveApiConnectorPermissionResolver` (AF-530) collapsed to a deployment
grant's two flags: the most-permissive union of the direct user grant and every unexpired group
grant — `can_trigger`/`can_break_glass` OR-ed; `expires_at` is `null` when any contributing grant
never expires, otherwise the latest contributing expiry. The trigger and gate services (#691/#693)
route through this single point so every enforcement site sees the same answer.

**Freeze-window evaluation.** `deploygov/internal/FreezeWindowEvaluator` (Clock-injected; an
explicit-`Instant` overload serves the scheduled-deploy path) decides whether a freeze is in
effect for `(org, pipeline, environment)` at a point in time:

- **Matching.** Candidate windows are the org's `enabled` rows whose scope columns are null or
  equal (`pipeline_id IS NULL OR = :pipeline` AND `environment_id IS NULL OR = :environment`).
- **One-off** windows are active when `starts_at <= t < ends_at`. **Recurring** windows evaluate
  wall-clock in their IANA `timezone`: active when the local day is listed (ISO 1 = Monday …
  7 = Sunday) and the local time is in `[start_time, end_time)`. An `end_time` before
  `start_time` **spans midnight**: day membership belongs to the day the window *starts*, so the
  early-morning tail matches when the *previous* local day is listed.
- **Precedence.** Among simultaneously active windows the most-specific scope wins
  (environment > pipeline > org-wide); within a tier `REJECT` beats `HOLD`, then the oldest
  window, so the result is deterministic.
- **Fail closed.** A window whose stored definition cannot be evaluated (invalid zone id, day
  number outside 1–7, an inconsistent shape that slipped past the CHECK) counts as an **active
  `HOLD`** — never `REJECT`, so a broken definition can hold deployments but can never
  auto-destroy requests; it recovers as soon as the admin fixes the row. Logged at WARN.

**Web layer.** `DeploymentPipelineController` (`/api/v1/deployment-pipelines`, including the
environments and `/permissions` + `/permissions/groups` sub-resources) and
`DeploymentFreezeWindowController` (`/api/v1/deployment-freeze-windows`) both carry a class-level
`@PreAuthorize("hasAuthority('PERM_DEPLOYMENT_PIPELINE_MANAGE')")`. `DeploygovExceptionHandler`
(`@Order(HIGHEST_PRECEDENCE)`) maps the module exceptions onto the `DEPLOYMENT_*` ProblemDetail
codes documented in [docs/04-api-spec.md](04-api-spec.md). Admin-CRUD audit rows are deliberately
deferred to the audit fan-out sub-issue (#695).

### Submission (#691)

`DefaultDeploymentRequestService.submit` is the pipeline-facing trigger, mirroring
`apigov`'s `DefaultApiRequestService.submit`:

1. Resolve the pipeline by `(id, organizationId)` — cross-org reads as 404 — and reject an inactive
   one. Resolve the **environment by name** (`findByPipelineIdAndNameIgnoreCase`): a CI job knows
   `production`, not a UUID.
2. **Permission.** `EffectiveDeploymentPermissionResolver.resolve(pipelineId, userId)` must return a
   grant with `canTrigger`; `QUERY_ADMIN` holders bypass it (the apigov convention). Otherwise
   `DeploymentRequestPermissionException` → 403. This runs **before** the replay lookup, so a
   caller without a grant cannot use a repeated trigger to probe whether a given run exists.
3. **Idempotent replay.** When the command carries an `externalRunId`, an existing row for
   `(pipeline, environment, version, externalRunId)` is returned as-is with `replay = true` — no
   second row, and crucially **no second `DeploymentSubmittedEvent`**, so a retried CI job never
   queues a duplicate approval. The partial unique index `uq_deployment_requests_trigger_idem`
   (V150) is the concurrency backstop: a `DataIntegrityViolationException` on insert is caught and
   the winning row re-read. The result carries the whole `DeploymentRequestView`, so the web layer
   never re-reads it — a replay may legitimately come from a *different* CI identity than the one
   that first triggered the run, and that identity would not necessarily pass the detail endpoint's
   visibility guard.
4. Persist `PENDING_AI`. `metadata` is `jsonb NOT NULL`, so a null command value is coerced to `{}`.
5. **Freeze windows.** `FreezeWindowEvaluator.evaluate(org, pipeline, environment)` runs *after*
   persistence so the rejection is a real state transition rather than a silent refusal. A
   **`REJECT`** window moves the request to `REJECTED` through `DeploymentRequestStateService` (so
   `DeploymentStatusChangedEvent` fires and #695's audit fan-out gets it for free) and publishes
   `DeploymentDecidedEvent(…, "freeze:" + windowId)`; no analysis is ever started. A **`HOLD`**
   window does **not** block submission — it gates *releasability* at the gate endpoint (#693), so
   the deployment still flows through AI and review and simply cannot be released until the window
   closes.
6. Otherwise publish `DeploymentSubmittedEvent`.

`cancel` is submitter-only and allowed from `PENDING_REVIEW`, or from `APPROVED` while a
`scheduled_for` deferral has not yet fired.

### AI analysis (#691)

The split mirrors AF-500 exactly and is **load-bearing for the module graph**:

- **`ai/api/DeploymentAnalyzer`** + `ai/internal/DefaultDeploymentAnalyzer` are a thin wrapper —
  enforce the per-org guardrails (`AiRateLimiter`), frame the release metadata into a prompt, and
  delegate to the provider `AiAnalyzerStrategy` with `DbType.CUSTOM` (the non-SQL marker). Sibling
  of `ApiCallAnalyzer`.
- **`deploygov/internal/DeploymentAnalysisListener`** is the `@ApplicationModuleListener` on
  `DeploymentSubmittedEvent` that renders the metadata context, calls the analyzer, persists the row
  through `core.api.AiAnalysisPersistenceService.persistForDeploymentRequest` (V152 adds
  `ai_analyses.deployment_request_id`), stamps `ai_analysis_id`, and publishes
  completed / skipped / failed.

**The listener lives in `deploygov`, not in `ai`, so that `ai` never depends on `deploygov`.** The
`ApiCallAnalyzer` javadoc establishes the direction — governed-surface modules depend on `ai.api`,
never the reverse — and putting an `@ApplicationModuleListener` for `deploygov.events` inside
`ai.internal` would invert it and fail `ApplicationModulesTest`.

Two deviations from the apigov listener, both deliberate:

- It catches **`AiAnalysisParseException` as well as `AiAnalysisException`**. The two are unrelated
  `RuntimeException` subclasses, so `ApiAnalysisListener`'s single `catch (AiAnalysisException)`
  lets a strict-JSON parse failure escape. A deployment analysis must never propagate — a malformed
  provider response becomes `DeploymentAnalysisFailedEvent`, which fails safe to human review.
- The rendered metadata context is **size-capped** before it reaches the prompt; a CI job can put an
  entire changelog into `metadata`.

When the pipeline has `ai_analysis_enabled = false` or no `ai_config_id`, the listener publishes
`DeploymentAnalysisSkippedEvent("ai_disabled")` and the state machine proceeds with a null risk.

### Routing engine (#691)

`deploygov/internal/routing/DeploymentRoutingPolicyEngine` walks the org's enabled policies in
ascending `priority`, skipping any whose non-null `pipeline_id` differs from the request's, and
returns the **first** whose conditions all match. Unlike `apigov` — which reads a raw `JsonNode` —
deploygov ships admin CRUD for these policies, so the conditions are a typed
`deploygov.api.DeploymentRoutingConditions` record round-tripped by
`DeploymentRoutingConditionCodec` and validated at create/update time. A typo'd key silently meaning
"unconstrained" is an acceptable risk only for a surface nobody edits through an API; here it would
be a routine admin mistake. Conditions leaves and their semantics are tabulated in
[docs/04-api-spec.md → Deployment routing policies](04-api-spec.md).

Two evaluation rules worth stating explicitly:

- **`minRiskLevel` never matches an absent risk** (same as `ApiRoutingPolicyEngine.meetsRisk`), so a
  risk-gated policy simply does not fire on a pipeline with AI disabled.
- **A half-specified time window is rejected, not treated as unconstrained.** Supplying a
  `startTime` without an `endTime` (or vice versa) is `400` at the admin boundary, and the engine
  skips such a policy if a hand-edited row ever reaches it. Reading it as "unconstrained" would make
  the whole condition set match every deployment — an `AUTO_APPROVE` policy an admin wrote for one
  maintenance hour would approve everything, around the clock.
- **A policy that cannot be evaluated is skipped with a WARN** — the deliberate opposite of
  `FreezeWindowEvaluator`, which fails *closed* to an active `HOLD`. A broken freeze window must
  still hold deployments; a broken routing policy must never silently auto-approve or auto-reject,
  and skipping it drops the deployment through to the environment's `require_review` (default
  `true`). The two asymmetric behaviours are both "fail safe" — they just point in opposite
  directions because the failure modes do.

Time-window leaves evaluate in the policy's own IANA `timezone`, defaulting to `UTC`. There is no
organization timezone anywhere in the schema, and the sibling feature in this module — freeze
windows — already made the explicit-zone choice for the same problem ("no Friday-afternoon prod
deploys" is inherently local). The midnight-spanning rule is identical to `FreezeWindowEvaluator`'s.

### State machine (#691)

`DeploymentReviewStateMachine` listens on the three analysis events (`@ApplicationModuleListener`),
guards on `PENDING_AI` — so a replayed event is a silent no-op — and then:

1. Evaluates the routing engine. **A match wins outright**; the environment's flags are not
   consulted. `AUTO_APPROVE` / `AUTO_REJECT` skip review; `REQUIRE_APPROVALS` **replaces** the
   approval count; `ESCALATE` **adds** its count to the resolved base — identical arithmetic to
   `ApiReviewStateMachine.applyRouting`, so the two modules agree.
2. On no match, the target environment's `require_review` decides, and a review plan with
   `requires_human_approval = false` can relax it (never tighten it).
3. **Plan resolution is two-level**: the environment's `review_plan_id` override, else the
   pipeline's, resolved through `core.api.ReviewPlanLookupService.findById`. The approval count on
   the no-match path is likewise **environment `required_approvals` → plan `min_approvals_required`
   → 1**; `deployment_environments.required_approvals` exists precisely for this.
4. `DeploymentAnalysisFailedEvent` routes to `forceReview`, which **skips the routing engine
   entirely** — a failed analysis can never auto-approve or auto-reject.

Every status write goes through **`DeploymentRequestStateService`**, which — unlike apigov's
`ApiRequestStateService` — validates the transition against an explicit table and throws
`IllegalDeploymentRequestStateException` (→ `409 DEPLOYMENT_REQUEST_INVALID_STATE`) on anything
else. Applying the status a row already has is a **silent no-op**, so a retried listener does not
409. The table:

| From | Allowed next |
|---|---|
| `PENDING_AI` | `PENDING_REVIEW`, `APPROVED`, `REJECTED`, `CANCELLED` |
| `PENDING_REVIEW` | `APPROVED`, `REJECTED`, `TIMED_OUT`, `CANCELLED` |
| `APPROVED` | `EXECUTED`, `FAILED`, `TIMED_OUT`, `CANCELLED` |
| `REJECTED` / `TIMED_OUT` / `EXECUTED` / `FAILED` / `CANCELLED` | — (terminal) |

Cells beyond #691's own reach are intentional: `PENDING_REVIEW → APPROVED/REJECTED/TIMED_OUT` is
#692, `APPROVED → EXECUTED/FAILED/TIMED_OUT` is #693. `PENDING_AI → CANCELLED` is legal in the table
but unreachable through the API — `POST /cancel` rejects `PENDING_AI` per the endpoint contract —
so a future "cancel a stuck analysis" admin path costs nothing.

### Request web layer (#691)

`DeploymentRequestController` (`/api/v1/deployment-requests`) carries **no class-level
`@PreAuthorize`**: triggering is authorized by the per-pipeline `can_trigger` grant inside the
service, not by a functional permission (there is no `DEPLOYMENT_TRIGGER` value in
`core.api.Permission`). It authenticates with a JWT **or an API key** with zero new auth code —
`security/internal/filter/ApiKeyAuthenticationFilter` puts the same `JwtClaims` principal in the
`SecurityContext` as the JWT path, so `(JwtClaims) authentication.getPrincipal()` serves both. The
trigger returns **202** on create and **200** on an idempotent replay.
`AdminDeploymentRoutingPolicyController` (`/api/v1/admin/deployment-routing-policies`) is gated by
`PERM_DEPLOYMENT_PIPELINE_MANAGE`. Listing and reading share one visibility predicate —
`DeploymentRequestService.canViewAll` (`DEPLOYMENT_REVIEW` or `QUERY_ADMIN`) — so a reviewer cannot
be in the position of opening a request by id that the list endpoint hides from them.

**Audit rows for the request pipeline remain deferred to #695**, including the freeze-window
auto-reject. That is a deliberate scope call, not an oversight: `audit.api.AuditAction` has no
`DEPLOYMENT_*` value yet, and adding one is the fan-out #695 owns. Until then the trail is the
`DeploymentStatusChangedEvent` published from every transition, which #695 consumes.

## MCP server (mcp module)

The **`mcp/` module** hosts the Spring AI stateless MCP server. It depends on `security.api`
(for `JwtClaims` and `ApiKeyService` — though only the filter actually calls the latter) and on
`core.api` / `workflow.api` for the underlying services the tools delegate to.

- **Starter.** `spring-ai-starter-mcp-server-webmvc` with `spring.ai.mcp.server.protocol=STATELESS`,
  endpoint defaults to `/mcp`.
- **Tool services.** `@Tool`-annotated methods on `McpToolService` (query / datasource tools),
  `McpReviewToolService` (reviewer-only, gated with
  `@PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")`), and `McpDataToolService` (read-mostly
  inspection tools: `validate_sql`, `get_column_samples`, `get_audit_log` — delegating to
  `proxy.api` / `audit.api`). `McpCurrentUser` resolves the calling principal from the
  SecurityContext.
- **Wiring.** `McpServerConfiguration` exposes all three services as a single
  `MethodToolCallbackProvider` bean — the starter's auto-configuration picks it up.

### Exposed MCP tools

| Tool | Service called | Notes |
|------|----------------|-------|
| `list_datasources` | `DatasourceAdminService.listForUser` / `listForAdmin` | Scoped to caller's organisation + permissions. |
| `get_datasource_schema` | `DatasourceAdminService.introspectSchema` | Caller must have datasource access. |
| `list_my_queries` | `QueryRequestLookupService.findForOrganization` | Filter is hard-coded to `submittedByUserId = caller`. |
| `get_query_status` | `QueryRequestLookupService.findDetailById` | Submitter-or-admin enforced inside the tool. |
| `get_query_result` | `QueryResultPersistenceService.find` | Requires `SELECT` query in `EXECUTED` status. |
| `submit_query` | `QuerySubmissionService.submit` | Goes through the normal AI-analysis + review workflow. |
| `cancel_query` | `QueryLifecycleService.cancel` | Submitter-only (enforced in service). |
| `list_pending_reviews` | `ReviewService.listPendingForReviewer` | `@PreAuthorize` reviewer/admin. |
| `review_query` | `ReviewService.approve` / `reject` / `requestChanges` | `decision` enum dispatch; self-approval still blocked by `DefaultReviewService.prepareDecision`. |
| `validate_sql` | `QueryParser.parse` (+ `DatasourceAdminService.introspectSchema` for mismatch) | Parse-only; no AI, no execution. Parse errors returned as `valid:false`+`parseError`; schema-mismatch is best-effort (skipped on DB connectivity failure). |
| `get_column_samples` | `SampleDataService.sample` (AF-443) | Governed sample read — RLS + masking applied, `canRead` + allow-list enforced. |
| `get_audit_log` | `AuditLogService.query` | `actorId` forced to caller; org-scoped. Returns only the caller's own entries. |

### Configuration

`application.yml` adds:

```yaml
spring:
  ai:
    mcp:
      server:
        name: accessflow-mcp
        version: 1.0.0
        protocol: STATELESS
        instructions: |
          AccessFlow MCP server. Use list_datasources, validate_sql, submit_query,
          get_query_status, get_column_samples, get_audit_log, …
```

Default endpoint: `POST /mcp` (the security chain already requires authentication on it via
`anyRequest().authenticated()`). No new env vars are required — auth piggybacks on the existing
chain, transport is plain HTTP.

User-facing usage guide (creating API keys, pointing Claude / other clients at `/mcp`, full tool
reference) is in `docs/13-mcp.md`.

---

## Key Dependencies (pom.xml)

```xml
<!-- Core -->
<dependency>spring-boot-starter-web</dependency>
<dependency>spring-boot-starter-data-jpa</dependency>
<dependency>spring-boot-starter-security</dependency>
<dependency>spring-boot-starter-websocket</dependency>
<dependency>spring-boot-starter-mail</dependency>

<!-- DB -->
<dependency>org.postgresql:postgresql</dependency>   <!-- AccessFlow internal DB only -->
<dependency>org.flywaydb:flyway-core</dependency>
<dependency>com.zaxxer:HikariCP</dependency>
<!-- Customer-DB JDBC drivers (MySQL, MariaDB, Oracle, MSSQL, …) are NOT bundled.
     They are resolved on demand from the Maven repository at runtime — see
     "Dynamic JDBC Driver Loading" above. -->

<!-- Dynamic Driver Loading (implementation choice — TBD in PR) -->
<!-- Either: org.apache.maven.resolver:maven-resolver-supplier
     Or:     hand-rolled java.net.http.HttpClient + java.security.MessageDigest -->


<!-- SQL Parsing -->
<dependency>com.github.jsqlparser:jsqlparser:5.3</dependency>

<!-- JWT -->
<dependency>com.nimbusds:nimbus-jose-jwt</dependency>

<!-- AI Clients -->
<dependency>com.openai:openai-java</dependency>    <!-- OpenAI official SDK -->

<!-- MCP (Model Context Protocol) server — stateless WebMVC transport -->
<dependency>org.springframework.ai:spring-ai-starter-mcp-server-webmvc</dependency>

<!-- Redis -->
<dependency>spring-boot-starter-data-redis</dependency>

<!-- Distributed scheduler locks (clustered-deployment safety for @Scheduled jobs) -->
<dependency>net.javacrumbs.shedlock:shedlock-spring</dependency>
<dependency>net.javacrumbs.shedlock:shedlock-provider-redis-spring</dependency>

<!-- SAML 2.0 SSO -->
<dependency>org.springframework.security:spring-security-saml2-service-provider</dependency>

<!-- Testing -->
<dependency>org.testcontainers:postgresql</dependency>
<dependency>org.testcontainers:mysql</dependency>
<dependency>io.rest-assured:rest-assured</dependency>
```

---

## Flyway Migration Naming Convention

```
db/migration/
├── V1__create_organizations.sql
├── V2__create_users.sql
├── V3__create_datasources.sql
├── V4__create_permissions.sql
├── V5__create_review_plans.sql
├── V6__create_query_requests.sql
├── V7__create_ai_analyses.sql
├── V8__create_review_decisions.sql
├── V9__create_audit_log.sql
├── V10__create_notification_channels.sql
├── V11__create_indexes.sql
└── V12__create_saml_configurations.sql
```

Never modify existing migration files. Always add new `V{n}__description.sql` files for schema changes.
