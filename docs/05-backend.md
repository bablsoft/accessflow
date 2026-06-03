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
9. **Execute** — Workflow orchestrator calls `QueryExecutor.execute(...)` (`proxy/api/`). The executor acquires a JDBC connection from the per-datasource pool, runs the SQL via `PreparedStatement` with `setQueryTimeout` and `setMaxRows(N+1)` (truncation detection), and dispatches by `QueryType`: `SELECT → executeQuery`, anything else → `executeLargeUpdate`. Returns a `SelectExecutionResult` (columns + rows + truncated flag) or `UpdateExecutionResult` (rows affected) — both carry `duration`. The orchestrator persists `rows_affected`, `execution_started_at`, `execution_completed_at`, `execution_duration_ms`, and `error_message` onto `query_requests`.
10. **Audit** — Every status transition publishes an `AuditEvent` (Spring Application Event) consumed by `AuditLogService` and written to `audit_log`.
11. **Respond** — Status → `EXECUTED`. WebSocket event pushed to submitter. API returns execution metadata.

### Connection Pool Management

Implemented in `proxy/internal/`:

- `DatasourceConnectionPoolManager` (public API) — `DataSource resolve(UUID)`, `Optional<DataSource> resolveReplica(UUID)`, `void evict(UUID)`, and `Optional<DatasourcePoolStats> poolStats(UUID)`. Returns Hikari pools typed as `javax.sql.DataSource` so callers stay framework-agnostic and use the standard JDBC `try-with-resources` idiom. `resolveReplica` returns empty when the datasource has no replica configured; `evict` closes both the primary and replica pools. `poolStats` reads the live `HikariPoolMXBean` gauges (active / idle / waiting / total / max) for the **already-cached** primary pool and returns empty when none is cached — it never creates a pool, so reading health metrics can't trigger a connection attempt against an unreachable customer DB.
- `DefaultDatasourceConnectionPoolManager` — `ConcurrentHashMap` cache, atomic lazy creation via `compute`, `@PreDestroy` shutdown closes all pools.
- `DatasourcePoolFactory` — owns the Hikari wiring; decrypts the password only here and drops the local reference before returning.
- `DatasourcePoolEvictionListener` — `@ApplicationModuleListener` for `DatasourceConfigChangedEvent` and `DatasourceDeactivatedEvent` (both in `core/events/`); fires in a new transaction after the publisher's transaction commits. Annotation comes from `spring-modulith-events-api`.

Behavior:

- One `HikariCP` pool per active datasource, keyed by datasource id.
- Pool created lazily on first `resolve(...)`. The pool is closed and the entry removed when:
  - `evict(...)` is called (e.g. by the listener after a config-change or deactivation event).
  - The application shuts down (`@PreDestroy`).
- Per-pool config: `maximumPoolSize` from `datasource.connection_pool_size`, plus the timeouts under `accessflow.proxy.*` (`connection-timeout`, `idle-timeout`, `max-lifetime`, optional `leak-detection-threshold`).
- Customer DB credentials decrypted from `password_encrypted` at pool creation time only; the local plaintext reference is dropped before `createPool` returns. Hikari retains its own copy for reconnects.
- Pool init is fail-fast: bad credentials or unreachable hosts raise `PoolInitializationException` from `resolve(...)` rather than on first `getConnection()`.

Eviction events (in `core/events/`, published by `DatasourceAdminServiceImpl`):

- `DatasourceConfigChangedEvent(UUID datasourceId)` — fired from `update(...)` when any of `host`, `port`, `databaseName`, `username`, `passwordEncrypted`, `sslMode`, `connectionPoolSize`, `readReplicaJdbcUrl`, `readReplicaUsername`, or `readReplicaPasswordEncrypted` changed. Eviction closes both pools.
- `DatasourceDeactivatedEvent(UUID datasourceId)` — fired from `update(...)` when `active` flips `true → false`, and from `deactivate(...)` (idempotent — only when the entity was active before the call).

The proxy module reads the datasource state via `DatasourceLookupService` (`core/api/`) which returns a `DatasourceConnectionDescriptor` record — a Modulith-clean alternative to letting `proxy/internal/` reach into `core/internal/` JPA entities. The descriptor exposes `maxRowsPerQuery` so the executor can enforce per-datasource row caps without a second round trip. It also carries the optional `readReplicaJdbcUrl`/`readReplicaUsername`/`readReplicaPasswordEncrypted` fields plus a convenience `hasReadReplica()` method.

### Read-replica routing

When a datasource has a `read_replica_jdbc_url` set, `RoutingDataSourceResolver` (`proxy/internal/`) routes any query classified by `SqlParserService` as `QueryType.SELECT` to the sibling replica pool. INSERT/UPDATE/DELETE/DDL and transactional `BEGIN…COMMIT` batches always hit the primary, regardless of the replica configuration.

- Replica credentials are encrypted with the same `ENCRYPTION_KEY` as the primary, decrypted only inside `DatasourcePoolFactory.createReplicaPool(...)`, and surface a pool name suffixed `-replica`. When `read_replica_username` or `read_replica_password_encrypted` is `NULL`, the primary's credentials are reused — useful when the replica accepts the same service account.
- The driver class is shared with the primary: replicas must use the same engine (you cannot point a PostgreSQL primary at a MySQL replica).
- On a connection failure against the replica (or on first-time pool init failure), the resolver records a `DATASOURCE_REPLICA_FALLBACK` audit row (action in `audit/api/AuditAction`; metadata includes the `error` message and `query_type=SELECT`), logs a `WARN`, and falls back to the primary so the query still runs. The fallback audit is recorded with `actorId=null` (system-initiated). Audit failures are swallowed.
- One audit row is written per failed SELECT. There is no rate-limiting — sustained replica downtime under load will produce one row per SELECT and the action filter on `/admin/audit-log` is the diagnostic.
- The Hikari pool tuning under `accessflow.proxy.*` (`connection-timeout`, `idle-timeout`, `max-lifetime`, `leak-detection-threshold`) applies to the replica pool too — no separate env vars.

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
- `SqlExceptionTranslator` — package-private. Maps `SQLException` → `QueryExecutionException` subclasses. SQLState `57014` (PostgreSQL cancellation), `HY008` (MySQL/ODBC cancellation), and `70100` (MySQL connection killed) become `QueryExecutionTimeoutException`; everything else becomes `QueryExecutionFailedException` with `sqlState` and `vendorCode` preserved.

Configuration (`accessflow.proxy.execution.*`, see `application.yml` block above):

| Key | Default | Purpose |
|-----|---------|---------|
| `max-rows` | `10000` | Global ceiling for SELECT result rows. Per-datasource `maxRowsPerQuery` is clamped to this. |
| `statement-timeout` | `30s` | Default JDBC `setQueryTimeout` for every execution. |
| `default-fetch-size` | `1000` | JDBC `setFetchSize` hint to bound driver-side buffers. |

Exception → HTTP mapping is in `security/internal/web/GlobalExceptionHandler.java`:

| Exception | Status | `error` code |
|-----------|--------|--------------|
| `QueryExecutionTimeoutException` | 504 Gateway Timeout | `QUERY_EXECUTION_TIMEOUT` |
| `QueryExecutionFailedException` | 422 Unprocessable Entity | `QUERY_EXECUTION_FAILED` (also exposes `sqlState`, `vendorCode`) |
| `DatasourceUnavailableException` | 422 Unprocessable Entity | `DATASOURCE_UNAVAILABLE` |
| `PoolInitializationException` | 503 Service Unavailable | `POOL_INITIALIZATION_FAILED` |

Out of scope for the executor itself (tracked separately): persistent storage of SELECT rows for the `/queries/{id}/results` endpoint, byte-size caps and concurrency budgets, and the workflow orchestrator that flips `QueryStatus` and writes execution metadata onto `query_requests`.

### Dynamic JDBC Driver Loading

Customer-database JDBC drivers are **not** bundled in the Spring Boot fat JAR. They are resolved per `DbType` on demand the first time a datasource of that type is used (via `POST /datasources` or its first `POST /datasources/{id}/test`). Only `org.postgresql:postgresql` ships baked in — used for AccessFlow's own internal database.

**Driver registry.** A static, in-process allowlist keyed by `DbType` maps to `{groupId, artifactId, version, sha256}`. Initial entries:

| DbType | Maven coordinates | Notes |
|--------|-------------------|-------|
| `POSTGRESQL` | `org.postgresql:postgresql` | Already on classpath; no resolution needed |
| `MYSQL` | `com.mysql:mysql-connector-j` | |
| `MARIADB` | `org.mariadb.jdbc:mariadb-java-client` | |
| `ORACLE` | `com.oracle.database.jdbc:ojdbc11` | Oracle license terms apply |
| `MSSQL` | `com.microsoft.sqlserver:mssql-jdbc` | |

Versions are pinned in the registry; SHA-256 checksums are verified after every download. The API will not accept arbitrary GAVs from callers — only registry entries are resolvable.

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

**Per-driver classloader.** `DefaultDriverCatalogService` caches resolved drivers in two maps:
`Map<DbType, ResolvedDriver>` for bundled entries, and `Map<UUID, ResolvedDriver>` keyed by
`custom_jdbc_driver.id` for uploads. Each uploaded driver becomes its own
`URLClassLoader` named `accessflow-jdbc-custom-{driverId}`. This guarantees that two datasources
referencing different uploads — even if both target ORACLE — load disjoint copies of
`oracle.jdbc.OracleDriver` and cannot interfere via static state.

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
| `auto_approve_reads=true` AND `query_type=SELECT` AND AI risk ∈ {LOW, MEDIUM} | `APPROVED` (fast path) |
| (default) | `PENDING_REVIEW` |
| Datasource has no review plan | `PENDING_REVIEW` (safe default) |

`AiAnalysisFailedEvent` **always** transitions to `PENDING_REVIEW`, regardless of plan flags. Auto-approve is a positive-signal shortcut; failure is a missing signal — they aren't symmetric, so an AI provider error never short-circuits human review. The AI module persists a sentinel `CRITICAL` analysis row on failure with `failed=true` and `error_message=<reason>` (added in AF-249) so the reviewer can render an "AI analysis failed" surface on `QueryDetailPage` instead of seeing a fake CRITICAL verdict. Reviewers and admins can call [`POST /queries/{id}/reanalyze`](04-api-spec.md#post-queriesidreanalyze--response-202) to re-run analysis on the failed row — the workflow service deletes the sentinel and publishes `AiReanalysisRequestedEvent`, which the AI module's listener consumes by invoking the normal `analyzeSubmittedQuery` pipeline. A `QUERY_AI_REANALYZE_REQUESTED` audit row is written from the controller on each call.

`AiAnalysisSkippedEvent` (added in AF-307) covers the case where the datasource has `ai_analysis_enabled = false`. The state machine respects `plan.requires_human_approval`: when human review is not required the query transitions `PENDING_AI → APPROVED`; otherwise (plan requires human approval, or no plan is configured) it transitions to `PENDING_REVIEW`. The fast-path `auto_approve_reads` shortcut is **never** applied — without an AI risk signal, the SELECT/low-risk shortcut cannot be evaluated. No sentinel `ai_analyses` row is persisted, so the frontend renders the analysis step as bypassed rather than failed.

### Policy-as-code routing engine (AF-379)

Routing policies are ordered, attribute-based rules that decide how a submitted query is routed **before** the default review-plan logic runs. The engine is owned by the `workflow` module and evaluated inside the same `QueryReviewStateMachine` listener, **after** AI analysis (or the skip event) and **before** reviewer fan-out:

1. `RoutingPolicyEngine` loads the org's enabled policies (org-wide + this datasource) in ascending `priority` and evaluates each `condition` against the query context (query type, referenced tables, AI risk level / score, requester role + group memberships, time-of-day / day-of-week, WHERE / LIMIT presence, transactional flag) via `RoutingConditionEvaluator`.
2. **First match wins.** The first enabled policy whose condition matches decides the action; evaluation stops there. On **no match** the query falls through to the datasource's review plan exactly as before — deterministic fall-through, identical to the pre-AF-379 behaviour.
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

**Skip / failure paths.** On the AI-skipped path (`datasource.ai_analysis_enabled = false`) the risk-based operands (`risk_level`, `risk_score`) evaluate to **false** — there is no AI signal, so risk-gated policies simply don't match and the query continues to non-risk policies or the plan fall-through. Routing is **not** run on the AI-failure path (`AiAnalysisFailedEvent`) — a missing AI signal never feeds an automated routing decision; the query lands in `PENDING_REVIEW` for a human, consistent with the auto-approve asymmetry above.

**Audit.** Automated decisions reuse the `QUERY_APPROVED` / `QUERY_REJECTED` audit actions with metadata `{ auto_approved | auto_rejected: true, source: "ROUTING_POLICY", routing_policy_id, reason }`. Policy CRUD writes the dedicated `ROUTING_POLICY_CREATED` / `_UPDATED` / `_DELETED` / `_REORDERED` actions against the `routing_policy` resource type. The engine reads / writes the new `routing_policy` and `routing_decision` tables (Flyway `V59__create_routing_policy.sql`).

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

Terminal transitions publish `QueryApprovedEvent` / `QueryRejectedEvent` (in `workflow.events`) for the audit and notifications modules to subscribe to.

### Approval timeout (auto-rejection)

`QueryTimeoutJob` (`workflow.internal.scheduled`) runs on a `@Scheduled(fixedDelayString = "${accessflow.workflow.timeout-poll-interval:PT5M}")` cadence. Each tick:

1. Calls `QueryRequestLookupService.findTimedOutPendingReviewIds(now)` — a native SQL join over `query_requests → datasources → review_plans` that returns any `PENDING_REVIEW` row whose `created_at + INTERVAL approval_timeout_hours` is before now.
2. For each id, calls `QueryRequestStateService.markTimedOut(id)`, which acquires the same pessimistic write lock as manual decisions, transitions `PENDING_REVIEW → TIMED_OUT`, and publishes `QueryStatusChangedEvent` and `QueryTimedOutEvent` (both in `core.events`).
3. Logs a summary: `"Auto-rejected N queries due to approval timeout (scanned M)"`.

`markTimedOut` does **not** insert a `review_decisions` row — auto-rejections carry no reviewer. The status field is the authoritative signal for distinguishing auto-rejections from manual rejections (`TIMED_OUT` vs `REJECTED`); `AuditEventListener.onQueryTimedOut` additionally writes a `QUERY_REJECTED` audit row with `metadata = { auto_rejected: true, reason: "approval_timeout", timeout_hours: N }` for backward compatibility with external audit consumers. The notifications module dispatches `NotificationEventType.REVIEW_TIMEOUT` (currently sharing the rejection email/Slack template — a dedicated template is tracked under [accessflow#101](https://github.com/bablsoft/accessflow/issues/101)).

The job is idempotent: a row already in `TIMED_OUT` (or any non-`PENDING_REVIEW` state — e.g. when a manual decision raced the timeout) is observed by `markTimedOut`, which returns `false` without re-publishing events.

The `GET /queries/{id}` response surfaces the active plan via `review_plan_name` and `approval_timeout_hours` so clients can render the timeout reason on the detail page (and, for queries still in `PENDING_REVIEW`, an "auto-rejects in N hours" hint).

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
| `ScheduledQueryRunJob` | workflow | `scheduledQueryRunJob` | `accessflow.workflow.scheduled-run-poll-interval` | `PT1M` |
| `AccessGrantExpiryJob` | access | `accessGrantExpiryJob` | `accessflow.access.grant-expiry-poll-interval` | `PT5M` |

`AccessGrantExpiryJob` implements JIT access-grant expiry (AF-378): it scans for `access_grant_request` rows in `APPROVED` with `expires_at ≤ now()` (a partial index backs the scan) and, per row, revokes the materialised `datasource_user_permissions` row and transitions the request to `EXPIRED`. It is idempotent (`AccessGrantExpiryService.expireAndRevoke` returns `false` if the row is no longer `APPROVED` — an admin revoke may have raced) and swallows per-row `RuntimeException`s so one bad row cannot abort the batch. The system-driven `ACCESS_GRANT_EXPIRED` audit row is written by the `access` module itself (not the audit-module listener) so there is no reverse `audit → access` module dependency.

`ScheduledQueryRunJob` implements query scheduling (AF-345): a submitter may include `scheduled_for` on `POST /queries` to defer execution. The query still goes through the normal AI / review flow; once it reaches `APPROVED`, the job picks it up at the next tick where `scheduled_for ≤ now()` and calls `QueryLifecycleService.executeScheduled(id)`. That method bypasses the per-user ownership guard (the actor is the scheduler, not a request principal), records the submitter as the audit actor, and tags the audit metadata with `"trigger": "scheduled"`. The job is idempotent — if the query is no longer `APPROVED` (manual execute / cancel raced the tick), the lifecycle service logs and returns without firing.

To add a new job: place the `@Component` under `<module>/internal/scheduled/`, annotate the method with `@Scheduled` + `@SchedulerLock(name = "<unique>")`, and document the row above. Lock-name conventions: short camelCase (`<jobName>`); never reuse a name across modules. The `scheduling` module's `LockProvider` is picked up automatically — no extra wiring needed.

---

## JIT time-bound access requests (AF-378)

The `access` module (`com.bablsoft.accessflow.access`) lets users self-request temporary, scoped datasource access that is granted on approval and auto-revoked on expiry.

**Approval reuses query-review machinery.** `DefaultAccessReviewService` mirrors `DefaultReviewService`: it resolves the datasource's `ReviewPlanSnapshot` (`core.api.ReviewPlanLookupService`), computes the current stage from the recorded `access_grant_decision` rows, checks the caller is an approver at that stage and within the datasource's scoped-reviewer set (`core.api.ReviewerEligibilityService`), and **blocks self-approval at the service layer** (`requesterId == reviewerId` → `AccessDeniedException`). Multi-stage chains are supported exactly as for queries — only the final stage transitions the request to `APPROVED`. The state primitive `AccessGrantRequestStateService` (pessimistic row lock, idempotent replay on `(requestId, reviewerId, stage)`) is the sole owner of `access_grant_request.status`.

**Admin fallback (the backstop approver).** Because a datasource can be created with no `review_plan_id` (and `submit()` does not require one), a request could otherwise land where no plan approver exists — invisible and un-actionable. To prevent that, `DefaultAccessReviewService` treats any `ADMIN` as a universal approver: `listPendingForReviewer` returns **every** `PENDING` request in the org for an admin (self-requests still excluded; `toPendingAccessRequest` tolerates a null plan, reporting stage `0`), and `prepareDecision` permits an admin who is *not* plan-eligible (no plan, foreign-org plan, out of scope, or not a named approver) to decide the request — `approve()` then issues the command with `minApprovalsRequired=1, isLastStage=true` so the single admin approval finalises and materialises the grant. When the plan *does* route to the admin as a configured stage approver, the normal multi-stage path is taken instead (no short-circuit). `REVIEWER`s stay strictly plan-gated — the self-approval block applies to admins too.

**Grant materialisation.** On final-stage approval, `approve()` runs `AccessGrantMaterializer` inside the same transaction so approval + grant commit atomically. The materializer computes `expires_at = now + Duration.parse(requested_duration)`, calls `core.api.DatasourceAdminService.grantPermission(...)`, and stores the new permission id on the request.

**Pre-existing-permission policy.** If the requester already holds a permission on the datasource: a **standing** permission (`expires_at == null`, admin-granted) is never silently deleted — the materializer throws `AccessGrantAlreadyExistsException` (HTTP 409). Another **time-boxed** (JIT) permission is revoked and replaced so the new grant's capabilities/expiry take effect (extend/widen). This keeps standing access safe while letting JIT grants stack predictably. (See [docs/07-security.md](07-security.md).)

**Expiry & revoke.** `AccessGrantExpiryJob` (see "Scheduled jobs" above) revokes grants past `expires_at` → `EXPIRED`. An admin may early-revoke an active grant (`POST /admin/access-requests/{id}/revoke`) → `REVOKED`. Both paths revoke the materialised permission (tolerating an already-deleted row) and publish events consumed by the notifications + realtime modules.

**Module boundaries.** `access → core.api`, `audit.api`; `notifications`/`realtime`/`audit` read access data through `access.api.AccessRequestLookupService` (and never reach into `access.internal`). `access` only *publishes* events to notifications/realtime, so there is no cycle. In-app + WebSocket notifications are delivered for access events (`AccessNotificationListener` + `RealtimeEventDispatcher`); per-channel email/Slack delivery for access events is a follow-up.

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

**Audit.** Every successful mutation calls `auditLogService.record(...)` with one of `QUERY_TEMPLATE_CREATED`, `QUERY_TEMPLATE_UPDATED`, `QUERY_TEMPLATE_DELETED` and resource type `QUERY_TEMPLATE`.

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
(`spring-ai-bom:2.0.0-M7` — `spring-ai-starter-model-anthropic`, `…-openai`, `…-ollama`).
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
  "affects_row_estimate": <integer or null>
}

Columns marked *RESTRICTED* in the schema context are sensitive and the values returned for them are masked at the proxy layer. If the SQL references any *RESTRICTED* column (in SELECT, WHERE, JOIN, ORDER BY, INSERT, UPDATE, or DELETE), add an issue with category="RESTRICTED_COLUMN_ACCESS" and severity="LOW" summarizing which restricted columns are touched. Do NOT raise the overall risk_level above MEDIUM solely for this reason — this is informational, not a blocker.

Database type: {db_type}
Schema context: {schema_context}
SQL to analyze:
{sql}
```

### Response language

`AiAnalyzerStrategy.analyze(sql, dbType, schemaContext, language)` takes a BCP-47 code (`en`, `es`, `de`, `fr`, `zh-CN`, `ru`, `hy`). The renderer appends one line at the end of the user prompt: `Respond in: <DisplayName>. Translate the free-form fields (summary, issues[].message, issues[].suggestion) into that language. Keep risk_level and issues[].category as their original English enum values.`

`DefaultAiAnalyzerService` resolves the language per call by reading the org's `localization_config.ai_review_language` via `LocalizationConfigService.getOrDefault(organizationId)`. If the lookup fails or returns an unknown code the service silently falls back to English so prompt construction never blocks AI analysis. The `/admin/ai-config/test` smoke endpoint always passes `"en"` since it is a synthetic, language-agnostic call.

The `risk_level` and `issues[].category` fields are deliberately kept as English enum strings — the SPA renders them through dictionaries (`statusColors.ts`, `riskColors.ts`) that don't translate, and the workflow state machine matches on the canonical names.

### Restricted-column awareness

`SystemPromptRenderer.describeSchema(schema, restrictedColumns)` annotates restricted columns inline in the schema context, e.g. `public.users(id uuid pk, ssn text *RESTRICTED*, email text)`. The prompt template instructs the model to emit a `RESTRICTED_COLUMN_ACCESS` issue (severity `LOW`) when the SQL references any of those columns. The workflow state machine ignores this category — it never auto-rejects on restricted-column access; the value is masked at the proxy layer regardless. Both `analyzePreview(...)` and `analyzeSubmittedQuery(...)` resolve the caller's restricted columns via `DatasourceUserPermissionLookupService` before rendering the prompt.

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
- Cross-module event types live in `core/events/` (`QueryReadyForReviewEvent`, `QueryAutoApprovedEvent`, `QueryStatusChangedEvent`, `AiAnalysisCompletedEvent`) and `workflow/events/` (`QueryApprovedEvent`, `QueryRejectedEvent`, `QueryCancelledEvent`, `QueryExecutedEvent`, `ReviewDecisionMadeEvent`). Keeping the read-side events in `core/events/` lets audit and realtime consume them without depending on `workflow`, breaking what would otherwise be a slice cycle (workflow controllers call `AuditLogService` synchronously).

### Deferred

- **Tamper-evident hash chain** (`previous_hash` / `current_hash`) — not yet implemented; tracked as a follow-up issue.
- **Separate audit-writer DB user** with INSERT-only privilege — deployment-level, tracked as a follow-up issue.

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

**Wiring an exporter (optional).** To ship traces to a collector, add (for example) `io.opentelemetry:opentelemetry-exporter-otlp` to `pom.xml` and set `management.otlp.tracing.endpoint`. AccessFlow's auto-configuration picks the exporter up automatically; no application-level code change is required.

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
| `review.new_request`    | `QueryReadyForReviewEvent` (in `core/events/`)            | eligible reviewers   |
| `review.decision_made`  | `ReviewDecisionMadeEvent` (in `workflow/events/`)         | submitter            |
| `notification.created`  | `UserNotificationCreatedEvent` (in `notifications/events/`) | the recipient user |

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

## MCP server (mcp module)

The **`mcp/` module** hosts the Spring AI stateless MCP server. It depends on `security.api`
(for `JwtClaims` and `ApiKeyService` — though only the filter actually calls the latter) and on
`core.api` / `workflow.api` for the underlying services the tools delegate to.

- **Starter.** `spring-ai-starter-mcp-server-webmvc` with `spring.ai.mcp.server.protocol=STATELESS`,
  endpoint defaults to `/mcp`.
- **Tool services.** `@Tool`-annotated methods on `McpToolService` (query / datasource tools)
  and `McpReviewToolService` (reviewer-only, gated with
  `@PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")`). `McpCurrentUser` resolves the calling
  principal from the SecurityContext.
- **Wiring.** `McpServerConfiguration` exposes both services as a single
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
          AccessFlow MCP server. Use list_datasources, submit_query, get_query_status, …
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
