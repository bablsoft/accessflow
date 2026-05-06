# 07 — Security Design

## Authentication

### Community Edition — JWT (RS256)

- Access tokens signed with RSA-2048 private key (`JWT_PRIVATE_KEY` env var)
- Access token TTL: **15 minutes**
- Refresh token TTL: **7 days**, stored in `HttpOnly; Secure; SameSite=Strict` cookie
- Refresh token rotation: each use issues a new token and invalidates the old one
- Revocation: invalidated refresh tokens stored in Redis with TTL matching remaining lifetime
- On access token expiry, frontend automatically calls `POST /auth/refresh` via Axios interceptor

### Enterprise Edition — SAML 2.0

All Community JWT mechanisms remain in place. Additionally:

- SP-initiated and IdP-initiated SSO flows supported
- Spring Security SAML2 extension handles assertion parsing and validation
- On successful SAML assertion:
  1. Extract `NameID` as `saml_subject`
  2. Look up user by `saml_subject` — create if `auto_provision_users=true`
  3. Map SAML attributes to `display_name`, `email`, `role` per `attribute_mapping` config
  4. Issue standard JWT pair — same token format as Community Edition
- SAML session lifetime respected: when IdP sends `SessionNotOnOrAfter`, refresh tokens beyond that time are rejected

---

## Authorization — Role Matrix

| Capability | READONLY | ANALYST | REVIEWER | ADMIN |
|-----------|----------|---------|----------|-------|
| Submit SELECT queries | ✓ | ✓ | ✓ | ✓ |
| Submit DML queries (INSERT/UPDATE/DELETE) | — | ✓ | ✓ | ✓ |
| Submit DDL queries | — | — | — | ✓ |
| View own query history | ✓ | ✓ | ✓ | ✓ |
| View all query history | — | — | ✓ | ✓ |
| Approve / reject queries | — | — | ✓ | ✓ |
| Approve own submitted queries | — | — | — | — |
| View AI analysis results | ✓ | ✓ | ✓ | ✓ |
| Create / edit datasources | — | — | — | ✓ |
| Manage user permissions | — | — | — | ✓ |
| Create / edit review plans | — | — | — | ✓ |
| View audit log | — | — | — | ✓ |
| Manage notification channels | — | — | — | ✓ |
| Configure AI provider | — | — | — | ✓ |
| Manage users (create/deactivate) | — | — | — | ✓ |
| Configure SAML (Enterprise) | — | — | — | ✓ |

**Key rule:** A user can never approve their own query request, regardless of role.

---

## Datasource-Level Access Control

Beyond platform roles, every action against a customer database is validated against `datasource_user_permissions`:

```
User attempts query on datasource
         │
         ▼
Does permission record exist for (user_id, datasource_id)?
  NO  → 403 Forbidden
  YES ↓
Does permission allow this query type?
  can_read=false + SELECT query → 403
  can_write=false + DML query   → 403
  can_ddl=false + DDL query     → 403
         ↓
Is access expired?
  expires_at < now → 403
         ↓
Are allowed_schemas / allowed_tables set?
  YES → validate query AST touches only permitted objects
  Violation → 403
         ↓
  PROCEED to review plan
```

---

## Database Credential Security

- Customer DB credentials stored in `datasource.password_encrypted` as AES-256-GCM ciphertext
- Encryption key: `ENCRYPTION_KEY` env var (32-byte hex) — never stored in database
- `password_encrypted` is **excluded from all API serialization** (`@JsonIgnore`)
- Credentials are decrypted only inside the `QueryProxyService` at JDBC pool creation time
- The decrypted password is passed directly to HikariCP and not retained in application memory beyond pool initialization
- A dedicated low-privilege service account is recommended on each customer database (SELECT only, or specific table grants matching `allowed_tables`)

---

## SQL Injection Prevention

AccessFlow uses defense-in-depth against injection attacks:

1. **JSqlParser validation** — All SQL is parsed before any execution path. Queries that fail to parse are rejected with HTTP 422. This blocks syntactically invalid injection attempts.

2. **PreparedStatement only** — The proxy engine uses `PreparedStatement` exclusively. No string concatenation or interpolation is used to build queries.

3. **Schema allow-listing at AST level** — If `allowed_tables` is configured, the parsed SQL AST is walked to extract referenced tables. Violations are rejected without touching the database.

4. **DDL blocked by default** — `can_ddl=false` (the default) prevents CREATE/ALTER/DROP from being executed even if submitted by an ANALYST or REVIEWER.

5. **Row cap enforcement** — `max_rows_per_query` is enforced via JDBC `setMaxRows()`, not by appending LIMIT to the query string. The executor reads one extra row beyond the cap to set a `truncated=true` flag on the result, then discards it.

6. **Statement timeout** — `accessflow.proxy.execution.statement-timeout` (default 30s) is applied via `PreparedStatement.setQueryTimeout()`. Driver-level cancellation paths (PostgreSQL SQLState `57014`, MySQL `HY008`/`70100`) are mapped to `QueryExecutionTimeoutException` → HTTP 504, distinct from generic execution failures (HTTP 422).

7. **Read-only flag for SELECT** — `Connection.setReadOnly(true)` is set for `SELECT` queries before execution. A driver-level hint that lets replicas/poolers refuse writes; not a substitute for JSqlParser validation but an extra defense if a misclassified statement somehow reaches the executor.

8. **Plaintext credentials never escape pool init** — The decrypted customer-database password is handed to HikariCP at pool creation and the local reference is dropped before `createPool` returns. The `QueryExecutor` never sees plaintext credentials; it acquires connections through `DatasourceConnectionPoolManager.resolve(...)`.

---

## Audit Log Integrity

The `audit_log` table is designed to be tamper-evident. Today the implementation provides the application-level half of that goal; the deployment-level and cryptographic-chain halves are deferred (see "Deferred" below).

Implemented today:

- Audit writes go through `AuditLogService` (`audit/api/`). Writes are append-only — neither the entity nor the service exposes UPDATE or DELETE.
- User-initiated actions are audited synchronously from controllers so `ip_address` (honoring `X-Forwarded-For`) and `user_agent` from the HTTP request are captured on the row.
- System-driven state transitions are audited via `@ApplicationModuleListener` in `audit/internal/AuditEventListener` — these run after the publishing transaction commits, on a separate thread; `ip_address` / `user_agent` are NULL on those rows by design.
- `metadata` JSONB contains context-specific information but **never** stores query result data (rows returned), passwords, or encryption keys.

Deferred (tracked as separate GitHub issues):

- The application database user has **INSERT-only** privilege on `audit_log`. No UPDATE or DELETE. Today the application uses a single Postgres role; the second role with INSERT-only grant is a deployment-level change tracked separately.
- A **separate audit writer DB user** for audit log inserts, distinct from the general application user.
- Cryptographic hash chain (`previous_hash` / `current_hash` columns + verifier endpoint). The schema does not yet include those columns; the chain will be added in a follow-up issue.

---

## HTTPS and Transport Security

- All production deployments must run behind TLS termination (nginx ingress or load balancer).
- The Spring Boot backend sets `server.ssl.enabled=false` by default — TLS is handled at the ingress layer.
- `HttpOnly; Secure; SameSite=Strict` cookies for refresh tokens prevent CSRF and XSS token theft.
- CORS is configured to allow only the configured frontend origin (`accessflow.cors.allowed-origin` env var).

---

## Security Headers

The Spring Boot API sets the following response headers:

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-XSS-Protection: 1; mode=block
Strict-Transport-Security: max-age=31536000; includeSubDomains
Content-Security-Policy: default-src 'self'
```

---

## Secrets Management

| Secret | How to Supply |
|--------|--------------|
| `ENCRYPTION_KEY` | Environment variable / Kubernetes Secret |
| `JWT_PRIVATE_KEY` | Environment variable / Kubernetes Secret (PEM format) |
| `AI_API_KEY` | Environment variable / Kubernetes Secret |
| `DB_PASSWORD` | Environment variable / Kubernetes Secret |
| Customer DB credentials | Stored encrypted in DB; never in env vars |
| SAML keystore password | Environment variable / Kubernetes Secret (Enterprise) |

For Kubernetes deployments, all secrets should be injected via `secretKeyRef` in the deployment manifest, not hardcoded in `values.yaml`.

---

## Recommended Customer Database Service Account Setup

```sql
-- PostgreSQL example: minimum privilege service account for AccessFlow
CREATE USER accessflow_svc WITH PASSWORD 'strong_random_password';

-- Read-only access (for datasources where only SELECT is needed)
GRANT CONNECT ON DATABASE app_prod TO accessflow_svc;
GRANT USAGE ON SCHEMA public TO accessflow_svc;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO accessflow_svc;

-- Or for write access (AccessFlow enforces review before any write reaches here)
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO accessflow_svc;

-- Do NOT grant SUPERUSER, CREATEDB, CREATEROLE, or DDL privileges
-- unless can_ddl is intentionally enabled in the datasource config
```
