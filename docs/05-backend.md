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
    max-execution-timeout: 30s
    max-result-rows: 10000
    connection-timeout: 30s              # HikariCP connectionTimeout (per-pool)
    idle-timeout: 10m                    # HikariCP idleTimeout
    max-lifetime: 30m                    # HikariCP maxLifetime
    leak-detection-threshold: 0s         # 0 disables leak detection
    pool-name-prefix: accessflow-ds-     # pool name = prefix + datasource UUID

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

1. **Receive request** — `POST /api/v1/queries` hits the controller, which delegates to `QueryProxyService`.
2. **Permission check** — Load `DatasourceUserPermission` for `(user, datasource)`. Verify `can_read` / `can_write` / `can_ddl` as appropriate. Reject with 403 if no permission record exists.
3. **SQL parsing** — Parse SQL using `JSqlParser` via `SqlParserService` (`proxy/api/`). Determine `QueryType` (SELECT, INSERT, UPDATE, DELETE, DDL, OTHER). Reject unparseable SQL and stacked / multi-statement input with 422 (`InvalidSqlException` → `error: "INVALID_SQL"`).
4. **Schema allow-list check** — If `allowed_schemas` or `allowed_tables` is set on the permission, validate parsed statement only touches permitted objects. Reject with 403 if violated.
5. **Review plan lookup** — Load the `ReviewPlan` assigned to the datasource. Determine whether AI review and/or human approval is required for this `QueryType`.
6. **Fast path** — If neither AI nor human review is required (e.g. `auto_approve_reads=true` for a SELECT), skip to step 9.
7. **AI analysis** — If `requires_ai_review=true`, publish `QuerySubmittedEvent`. The `AiAnalyzerService` picks it up asynchronously. Query status → `PENDING_AI`. When complete, status → `PENDING_REVIEW` (or `APPROVED` if no human review needed).
8. **Human approval** — If `requires_human_approval=true`, status → `PENDING_REVIEW`. Notification Dispatcher sends alerts to reviewers. System waits for decisions. Once `min_approvals_required` is met (respecting `stage` ordering), status → `APPROVED`.
9. **Execute** — Proxy acquires a JDBC connection from the per-datasource connection pool. Executes SQL with `PreparedStatement`. Applies `max_rows_per_query` hard cap via `setMaxRows()`. Captures `rows_affected`, `execution_duration_ms`.
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

The proxy module reads the datasource state via `DatasourceLookupService` (`core/api/`) which returns a `DatasourceConnectionDescriptor` record — a Modulith-clean alternative to letting `proxy/internal/` reach into `core/internal/` JPA entities.

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

---

## AI Query Analyzer Service

The `AiAnalyzerService` (`accessflow-ai` module) is pluggable via a strategy interface:

```java
public interface AiAnalyzerStrategy {
    AiAnalysisResult analyze(String sql, DbType dbType);
}
```

Implementations:
- `OpenAiAnalyzerStrategy` — calls OpenAI Chat Completions API
- `AnthropicAnalyzerStrategy` — calls Anthropic Messages API
- `OllamaAnalyzerStrategy` — calls local Ollama REST API

Active strategy selected via `accessflow.ai.provider` config. Can be changed at runtime by ADMIN via `PUT /admin/ai-config` (triggers bean refresh).

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

Database type: {db_type}
Schema context: {schema_context}
SQL to analyze:
{sql}
```

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
