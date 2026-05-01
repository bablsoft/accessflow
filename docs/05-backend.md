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
3. **SQL parsing** — Parse SQL using `JSqlParser`. Determine `QueryType` (SELECT, INSERT, UPDATE, DELETE, DDL). Reject unparseable SQL with 422.
4. **Schema allow-list check** — If `allowed_schemas` or `allowed_tables` is set on the permission, validate parsed statement only touches permitted objects. Reject with 403 if violated.
5. **Review plan lookup** — Load the `ReviewPlan` assigned to the datasource. Determine whether AI review and/or human approval is required for this `QueryType`.
6. **Fast path** — If neither AI nor human review is required (e.g. `auto_approve_reads=true` for a SELECT), skip to step 9.
7. **AI analysis** — If `requires_ai_review=true`, publish `QuerySubmittedEvent`. The `AiAnalyzerService` picks it up asynchronously. Query status → `PENDING_AI`. When complete, status → `PENDING_REVIEW` (or `APPROVED` if no human review needed).
8. **Human approval** — If `requires_human_approval=true`, status → `PENDING_REVIEW`. Notification Dispatcher sends alerts to reviewers. System waits for decisions. Once `min_approvals_required` is met (respecting `stage` ordering), status → `APPROVED`.
9. **Execute** — Proxy acquires a JDBC connection from the per-datasource connection pool. Executes SQL with `PreparedStatement`. Applies `max_rows_per_query` hard cap via `setMaxRows()`. Captures `rows_affected`, `execution_duration_ms`.
10. **Audit** — Every status transition publishes an `AuditEvent` (Spring Application Event) consumed by `AuditLogService` and written to `audit_log`.
11. **Respond** — Status → `EXECUTED`. WebSocket event pushed to submitter. API returns execution metadata.

### Connection Pool Management

- One `HikariCP` pool per active datasource.
- Pool created lazily on first query, destroyed on datasource deletion.
- Pool config: `maximumPoolSize` from `datasource.connection_pool_size`, `connectionTimeout=30s`, `idleTimeout=10m`.
- Customer DB credentials decrypted from `password_encrypted` at pool creation time; never stored in JVM heap beyond pool init.

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
<dependency>org.postgresql:postgresql</dependency>
<dependency>com.mysql:mysql-connector-j</dependency>
<dependency>org.flywaydb:flyway-core</dependency>
<dependency>com.zaxxer:HikariCP</dependency>

<!-- SQL Parsing -->
<dependency>com.github.jsqlparser:jsqlparser:4.7</dependency>

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
