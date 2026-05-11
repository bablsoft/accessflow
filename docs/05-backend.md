# 05 — Backend Architecture

## Maven Module Layout

```
accessflow/
├── accessflow-parent/            # Parent POM — dependency management, plugin config
├── accessflow-api/               # REST controllers, DTOs, OpenAPI/Swagger spec
├── accessflow-core/              # Domain entities, JPA repositories, service interfaces
├── accessflow-proxy/             # SQL proxy engine, JDBC connection pool management
├── accessflow-workflow/          # Review workflow state machine, notification fanout
├── accessflow-ai/                # AI analyzer — OpenAI / Anthropic / Ollama adapters
├── accessflow-security/          # JWT config, Spring Security, SAML (Enterprise module)
├── accessflow-notifications/     # Email (JavaMail), Slack API, Webhook dispatcher
├── accessflow-realtime/          # WebSocket fanout of domain events to connected frontend clients
├── accessflow-audit/             # Audit log service, Spring application event publishers
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
  edition: community           # community | enterprise
  encryption-key: ${ENCRYPTION_KEY}   # 256-bit AES key for credential encryption

  jwt:
    private-key: ${JWT_PRIVATE_KEY}   # RSA-2048 PEM
    access-token-expiry: 15m
    refresh-token-expiry: 7d

  ai:
    provider: anthropic              # openai | anthropic | ollama
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

### application-enterprise.yml (Enterprise overlay)

```yaml
accessflow:
  edition: enterprise
  saml:
    sp-entity-id: https://accessflow.company.com/saml/metadata
    idp-metadata-url: ${SAML_IDP_METADATA_URL}
    keystore-path: ${SAML_KEYSTORE_PATH}
    keystore-password: ${SAML_KEYSTORE_PASSWORD}
```

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
> `/approve`, `/reject`, `/request-changes` for human approvers. AST-level schema allow-listing
> (step 3) and the executor invocation (steps 9, 10, 11) ship in follow-up issues.

1. **Receive request** — `POST /api/v1/queries` hits the controller, which delegates to `QueryProxyService`.
2. **Permission check** — Load `DatasourceUserPermission` for `(user, datasource)`. Verify `can_read` / `can_write` / `can_ddl` as appropriate. Reject with 403 if no permission record exists.
3. **SQL parsing** — Parse SQL using `JSqlParser` via `SqlParserService` (`proxy/api/`). Determine `QueryType` (SELECT, INSERT, UPDATE, DELETE, DDL, OTHER). Reject unparseable SQL and stacked / multi-statement input with 422 (`InvalidSqlException` → `error: "INVALID_SQL"`).
4. **Schema allow-list check** — If `allowed_schemas` or `allowed_tables` is set on the permission, validate parsed statement only touches permitted objects. Reject with 403 if violated.
5. **Review plan lookup** — Load the `ReviewPlan` assigned to the datasource. Determine whether AI review and/or human approval is required for this `QueryType`.
6. **Fast path** — If neither AI nor human review is required (e.g. `auto_approve_reads=true` for a SELECT), skip to step 9.
7. **AI analysis** — If `requires_ai_review=true`, publish `QuerySubmittedEvent`. The `AiAnalyzerService` picks it up asynchronously. Query status → `PENDING_AI`. When complete, status → `PENDING_REVIEW` (or `APPROVED` if no human review needed).
8. **Human approval** — If `requires_human_approval=true`, status → `PENDING_REVIEW`. Notification Dispatcher sends alerts to reviewers. System waits for decisions. Once `min_approvals_required` is met (respecting `stage` ordering), status → `APPROVED`.
9. **Execute** — Workflow orchestrator calls `QueryExecutor.execute(...)` (`proxy/api/`). The executor acquires a JDBC connection from the per-datasource pool, runs the SQL via `PreparedStatement` with `setQueryTimeout` and `setMaxRows(N+1)` (truncation detection), and dispatches by `QueryType`: `SELECT → executeQuery`, anything else → `executeLargeUpdate`. Returns a `SelectExecutionResult` (columns + rows + truncated flag) or `UpdateExecutionResult` (rows affected) — both carry `duration`. The orchestrator persists `rows_affected`, `execution_started_at`, `execution_completed_at`, `execution_duration_ms`, and `error_message` onto `query_requests`.
10. **Audit** — Every status transition publishes an `AuditEvent` (Spring Application Event) consumed by `AuditLogService` and written to `audit_log`.
11. **Respond** — Status → `EXECUTED`. WebSocket event pushed to submitter. API returns execution metadata.

### Connection Pool Management

Implemented in `proxy/internal/`:

- `DatasourceConnectionPoolManager` (public API) — `DataSource resolve(UUID)` and `void evict(UUID)`. Returns a Hikari pool typed as `javax.sql.DataSource` so callers stay framework-agnostic and use the standard JDBC `try-with-resources` idiom.
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

- `DatasourceConfigChangedEvent(UUID datasourceId)` — fired from `update(...)` when any of `host`, `port`, `databaseName`, `username`, `passwordEncrypted`, `sslMode`, or `connectionPoolSize` changed.
- `DatasourceDeactivatedEvent(UUID datasourceId)` — fired from `update(...)` when `active` flips `true → false`, and from `deactivate(...)` (idempotent — only when the entity was active before the call).

The proxy module reads the datasource state via `DatasourceLookupService` (`core/api/`) which returns a `DatasourceConnectionDescriptor` record — a Modulith-clean alternative to letting `proxy/internal/` reach into `core/internal/` JPA entities. The descriptor exposes `maxRowsPerQuery` so the executor can enforce per-datasource row caps without a second round trip.

### Query Execution

Implemented in `proxy/internal/`:

- `QueryExecutor` (public API in `proxy/api/`) — single method `QueryExecutionResult execute(QueryExecutionRequest)`. Pure execution primitive: input is `(datasourceId, sql, queryType, maxRowsOverride?, statementTimeoutOverride?)`; output is a sealed `QueryExecutionResult` (`SelectExecutionResult` | `UpdateExecutionResult`). Status transitions and `query_requests` writes live in the workflow orchestrator that consumes this service.
- `DefaultQueryExecutor` — `@Service`. Resolves the datasource descriptor, computes `effectiveMaxRows = min(override ?? datasource.maxRowsPerQuery, accessflow.proxy.execution.max-rows)` and `effectiveTimeout = override ?? accessflow.proxy.execution.statement-timeout`, then runs:
  ```
  Connection.setReadOnly(queryType == SELECT)
  PreparedStatement.setQueryTimeout(effectiveTimeout)
  PreparedStatement.setFetchSize(min(effectiveMaxRows + 1, accessflow.proxy.execution.default-fetch-size))
  if SELECT → setMaxRows(effectiveMaxRows + 1) + executeQuery + materialize
  else      → executeLargeUpdate
  ```
  `autoCommit` is left at the HikariCP default (`true`). The `+1` row beyond the cap is read solely to mark the result `truncated=true` and is then discarded.
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

### SQL Injection Prevention

- JSqlParser validates all SQL before any execution path.
- Proxy uses `PreparedStatement` exclusively — no string interpolation.
- Schema/table allow-listing validated at the AST level (not string matching).
- DDL blocked by default; requires explicit `can_ddl=true` permission.

### Column-level masking

When a `(user_id, datasource_id)` permission row carries `restricted_columns` (a `TEXT[]` of fully-qualified `schema.table.column` strings), SELECT result values for those columns are masked **before** rows are added to the in-memory result list — and therefore before they are serialised into `query_request_results.rows`. The raw sensitive value never lands in our database.

- Wiring: `DefaultQueryLifecycleService.execute(...)` resolves `restrictedColumns` via `DatasourceUserPermissionLookupService`, threads them into `QueryExecutionRequest`, which `DefaultQueryExecutor` forwards to `JdbcResultRowMapper.materialize(...)`.
- Matching uses `RestrictedColumnMatcher`, which inspects each column's `ResultSetMetaData` and applies (in priority order):
  1. Exact `schema.table.column` match (case-insensitive) when the JDBC driver populates both `getSchemaName(i)` and `getTableName(i)`.
  2. `table.column` fallback when only the table name is available.
  3. Bare `column` fallback for computed expressions, aliased outputs, and other cases where the driver omits table metadata. This errs toward over-masking, which is the secure default.
- Sentinel: a restricted cell is replaced with the literal string `"***"`. `null` values stay `null`.
- Each `ResultColumn` returned from `materialize(...)` carries a `restricted` boolean so the API response (and the persisted `columns` JSON in `query_request_results`) tells the frontend which headers should render a "masked" marker.
- Write statements (INSERT / UPDATE / DELETE) have no result set to mask. Restrictions still surface in the AI prompt (see below) — informational only.

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

`workflow.internal.QueryReviewStateMachine` is a Spring Modulith `@ApplicationModuleListener` consuming `AiAnalysisCompletedEvent` and `AiAnalysisFailedEvent` from the `core` module's events. It runs `AFTER_COMMIT` of the AI module's persistence transaction, so the `ai_analyses` row and `query_requests.ai_analysis_id` link are already visible.

Decision rules:

| Plan flag combination | Resulting status |
|-----------------------|-------------------|
| `requires_human_approval=false` | `APPROVED` (auto-approve) |
| `auto_approve_reads=true` AND `query_type=SELECT` AND AI risk ∈ {LOW, MEDIUM} | `APPROVED` (fast path) |
| (default) | `PENDING_REVIEW` |
| Datasource has no review plan | `PENDING_REVIEW` (safe default) |

`AiAnalysisFailedEvent` **always** transitions to `PENDING_REVIEW`, regardless of plan flags. Auto-approve is a positive-signal shortcut; failure is a missing signal — they aren't symmetric, so an AI provider error never short-circuits human review. (The AI module persists a sentinel `CRITICAL` analysis row on failure so the reviewer has context.)

### Implementation: review decisions

`workflow.internal.DefaultReviewService` enforces eligibility and orchestrates state transitions through `core.api.QueryRequestStateService`:

1. **Self-approval check** (first): submitter ≠ reviewer, regardless of role. Throws `AccessDeniedException` (HTTP 403). Enforced in service, not controller — see `docs/07-security.md:50`.
2. **Tenant scope**: query, plan, and reviewer must all be in the same `organization_id`.
3. **Role gate**: caller must be `REVIEWER` or `ADMIN`.
4. **Approver match at current stage**: caller's `userId` matches a `review_plan_approvers.user_id` at the current stage, OR caller's role matches a `review_plan_approvers.role` at that stage.
5. **State guard**: the underlying `QueryRequestStateService` takes a `PESSIMISTIC_WRITE` lock on the `query_requests` row (`@Lock(LockModeType.PESSIMISTIC_WRITE)` in `QueryRequestRepository.findByIdForUpdate`), re-reads decisions inside that transaction, inserts the new `review_decisions` row, and conditionally transitions the status — all atomically. The row lock makes it impossible for two concurrent approvers to both observe the threshold-met condition and double-advance.
6. **Idempotency**: a unique index on `(query_request_id, reviewer_id, stage)` (Flyway V11) plus a service-level pre-check guarantees that a duplicate decision (e.g. a double-clicked button) returns the existing decision rather than inserting twice.

`approve` may resolve to either `PENDING_REVIEW` (more approvers needed at this stage, or higher stages remain) or `APPROVED` (last stage threshold met). `reject` is always terminal (`REJECTED`). `request-changes` is non-terminal — the query stays in `PENDING_REVIEW` and the comment is recorded for the submitter.

Terminal transitions publish `QueryApprovedEvent` / `QueryRejectedEvent` (in `workflow.events`) for the audit and notifications modules to subscribe to.

### Approval timeout (auto-rejection)

`QueryTimeoutJob` (`workflow.internal.scheduled`) runs on a `@Scheduled(fixedDelayString = "${accessflow.workflow.timeout-poll-interval:PT5M}")` cadence. Each tick:

1. Calls `QueryRequestLookupService.findTimedOutPendingReviewIds(now)` — a native SQL join over `query_requests → datasources → review_plans` that returns any `PENDING_REVIEW` row whose `created_at + INTERVAL approval_timeout_hours` is before now.
2. For each id, calls `QueryRequestStateService.markTimedOut(id)`, which acquires the same pessimistic write lock as manual decisions, transitions `PENDING_REVIEW → TIMED_OUT`, and publishes `QueryStatusChangedEvent` and `QueryTimedOutEvent` (both in `core.events`).
3. Logs a summary: `"Auto-rejected N queries due to approval timeout (scanned M)"`.

`markTimedOut` does **not** insert a `review_decisions` row — auto-rejections carry no reviewer. The status field is the authoritative signal for distinguishing auto-rejections from manual rejections (`TIMED_OUT` vs `REJECTED`); `AuditEventListener.onQueryTimedOut` additionally writes a `QUERY_REJECTED` audit row with `metadata = { auto_rejected: true, reason: "approval_timeout", timeout_hours: N }` for backward compatibility with external audit consumers. The notifications module dispatches `NotificationEventType.REVIEW_TIMEOUT` (currently sharing the rejection email/Slack template — a dedicated template is tracked under [accessflow#101](https://github.com/partqam/accessflow/issues/101)).

The job is idempotent: a row already in `TIMED_OUT` (or any non-`PENDING_REVIEW` state — e.g. when a manual decision raced the timeout) is observed by `markTimedOut`, which returns `false` without re-publishing events.

The `GET /queries/{id}` response surfaces the active plan via `review_plan_name` and `approval_timeout_hours` so clients can render the timeout reason on the detail page (and, for queries still in `PENDING_REVIEW`, an "auto-rejects in N hours" hint).

### Scheduled jobs and clustering

`@EnableScheduling` is activated in `WorkflowConfiguration`. Every `@Scheduled` method **must** carry a `@SchedulerLock(name = …, lockAtMostFor = …, lockAtLeastFor = …)`. The lock provider is `RedisLockProvider` (configured in `RedisLockProviderConfiguration`), which reuses the same `RedisConnectionFactory` as the JWT refresh-token store. Lock keys live under the `accessflow:shedlock:` Redis prefix.

This makes horizontal scaling safe: when the AccessFlow backend runs as multiple replicas (Kubernetes Deployment with `replicas > 1`, or any process supervisor that runs N instances against the same Postgres + Redis), only one replica wins the lock per tick and runs the job. The other replicas observe the lock and skip — they will see no PENDING_REVIEW rows that match by the time their own next tick fires, because the winner already drained them.

| Job | Module | Lock name | Cadence property | Default |
|-----|--------|-----------|------------------|---------|
| `QueryTimeoutJob` | workflow | `queryTimeoutJob` | `accessflow.workflow.timeout-poll-interval` | `PT5M` |

To add a new job: place the `@Component` under `<module>/internal/scheduled/`, annotate the method with `@Scheduled` + `@SchedulerLock(name = "<unique>")`, and document the row above. Lock-name conventions: short camelCase (`<jobName>`); never reuse a name across modules.

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
available)`. `language` is the BCP-47 language code (see *Response language*). `organizationId`
scopes provider lookup to that org's `ai_config` row.

Three concrete strategy classes (Anthropic, OpenAI, Ollama) live under `ai/internal/`. None of
them is a `@Service` — they are plain classes built by `AiAnalyzerStrategyHolder`, the single
autowired `AiAnalyzerStrategy` bean, from the per-org `ai_config` row using Spring AI 2.0
(`spring-ai-bom:2.0.0-M6` — `spring-ai-starter-model-anthropic`, `…-openai`, `…-ollama`):

- `AnthropicAnalyzerStrategy` — `AnthropicChatModel` built programmatically from the row's
  provider / model / endpoint / API key / timeout. Default boot model: `claude-sonnet-4-20250514`.
- `OpenAiAnalyzerStrategy` — `OpenAiChatModel`. Default boot model: `gpt-4o`.
- `OllamaAnalyzerStrategy` — `OllamaChatModel`. Keyless; needs only `endpoint` (default
  `http://localhost:11434`).

### Runtime strategy refresh

`AiAnalyzerStrategyHolder` caches one delegate per organization (`Map<UUID, AiAnalyzerStrategy>`).
On a successful `PUT /api/v1/admin/ai-config`, `DefaultAiConfigService` publishes an
`AiConfigUpdatedEvent`. The holder consumes it via `@ApplicationModuleListener` (so it fires
after the transaction commits) and evicts the cached entry for that org — the next `analyze(...)`
call rebuilds the delegate from the new row. No application restart, no Spring context refresh.

If the looked-up `ai_config` row has no API key set (and the provider needs one — Anthropic /
OpenAI), the holder throws `AiAnalysisException` whose message is resolved via `MessageSource`
(`error.ai.not_configured` in `i18n/messages.properties`). The smoke endpoint `POST
/admin/ai-config/test` surfaces that text as the `detail` of `{"status":"ERROR", ...}`.

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

2. **Asynchronous, from `AuditEventListener`** — for system-driven state transitions where there is no live request thread. Uses Spring Modulith's `@ApplicationModuleListener` (which is `@Async + @Transactional(REQUIRES_NEW) + @TransactionalEventListener(AFTER_COMMIT)`). IP/UA are intentionally null on these rows. Each handler swallows runtime failures to keep the publishing transaction unaffected.

   | Event | Action |
   |---|---|
   | `AiAnalysisCompletedEvent` | `QUERY_AI_ANALYZED` |
   | `AiAnalysisFailedEvent` | `QUERY_AI_FAILED` |
   | `QueryReadyForReviewEvent` | `QUERY_REVIEW_REQUESTED` |
   | `QueryAutoApprovedEvent` | `QUERY_APPROVED` (system actor, `actor_id = NULL`, metadata `{"auto_approved": true}`) |
   | `DatasourceDeactivatedEvent` | `DATASOURCE_UPDATED` with metadata `{"change":"deactivated"}` |

`AuditAction` extends the doc enum with `QUERY_AI_FAILED` so the read API can filter for failed AI runs without parsing the JSONB metadata.

### Read endpoint

`GET /api/v1/admin/audit-log` — `@PreAuthorize("hasRole('ADMIN')")`. Filters: `actorId`, `action`, `resourceType`, `resourceId`, `from`, `to`. Pagination via Spring `Pageable`; max page size 500. Always scoped to the caller's organization — admins in org A cannot read org B's rows.

### Module isolation

- The `audit_log` entity lives under `audit/internal/persistence/entity/`, with plain `UUID` columns for `organizationId` / `actorId` (no JPA `@ManyToOne` joins — same pattern as `NotificationChannelEntity`). Postgres-level FKs to `organizations` and `users` were dropped in V14 so audit history survives org/user deletion.
- Cross-module event types live in `core/events/` (`QueryReadyForReviewEvent`, `QueryAutoApprovedEvent`, `QueryStatusChangedEvent`, `AiAnalysisCompletedEvent`) and `workflow/events/` (`QueryApprovedEvent`, `QueryRejectedEvent`, `QueryExecutedEvent`, `ReviewDecisionMadeEvent`). Keeping the read-side events in `core/events/` lets audit and realtime consume them without depending on `workflow`, breaking what would otherwise be a slice cycle (workflow controllers call `AuditLogService` synchronously).

### Deferred

- **Tamper-evident hash chain** (`previous_hash` / `current_hash`) — not yet implemented; tracked as a follow-up issue.
- **Separate audit-writer DB user** with INSERT-only privilege — deployment-level, tracked as a follow-up issue.
- **`QUERY_EXECUTED` / `QUERY_FAILED` audit** — depends on the proxy executor wiring `APPROVED → EXECUTED` / `APPROVED → FAILED` status transitions, which it does not yet do. Tracked as a follow-up issue.
- **`QUERY_CANCELLED` audit** — depends on a cancel endpoint, which does not yet exist. Tracked as a follow-up issue.

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

<!-- Redis -->
<dependency>spring-boot-starter-data-redis</dependency>

<!-- Distributed scheduler locks (clustered-deployment safety for @Scheduled jobs) -->
<dependency>net.javacrumbs.shedlock:shedlock-spring</dependency>
<dependency>net.javacrumbs.shedlock:shedlock-provider-redis-spring</dependency>

<!-- SAML (Enterprise module only) -->
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
