# 07 — Security Design

## Authentication

### JWT (RS256)

- Access tokens signed with RSA-2048 private key (`JWT_PRIVATE_KEY` env var)
- Access token TTL: **15 minutes**
- Refresh token TTL: **7 days**, stored in `HttpOnly; Secure; SameSite=Strict` cookie
- Refresh token rotation: each use issues a new token and invalidates the old one
- Revocation: invalidated refresh tokens stored in Redis with TTL matching remaining lifetime
- On access token expiry, frontend automatically calls `POST /auth/refresh` via Axios interceptor

### Two-factor authentication (TOTP)

Every LOCAL user can opt in to TOTP-based 2FA from `/profile`. SAML-provisioned users authenticate through their IdP and cannot enrol locally — they rely on the IdP's MFA controls instead.

- **Standard:** RFC 6238 TOTP, SHA-1, 6 digits, 30-second window. Implemented via `dev.samstevens.totp:totp` (current pin: 1.7.1) — re-verify against the latest stable on each bump.
- **Secret storage:** the shared secret is AES-256-GCM encrypted on the user row (`users.totp_secret_encrypted`, `@JsonIgnore`) via the existing `CredentialEncryptionService`. The plaintext exists only inside `DefaultTotpVerificationService.verify`, never on a response.
- **Enrolment flow:** `POST /me/totp/enroll` generates the secret and an otpauth URL (issuer `AccessFlow`, label `<email>`) plus a base64-PNG QR data URI. `totp_enabled` stays false until `POST /me/totp/confirm` proves possession by verifying a 6-digit code; the same call returns 10 single-use backup recovery codes (plaintext, **once**) and persists them as bcrypt hashes inside an AES-encrypted JSON array.
- **Login enforcement:** `LocalAuthenticationService.login` rejects 2FA-enabled accounts that present no `totp_code` with HTTP 401 `TOTP_REQUIRED`. A valid 6-digit code OR an unused backup code unlocks the session; a backup code is removed from the encrypted blob on first successful use.
- **Disable:** `POST /me/totp/disable` requires the caller's current password, then clears the secret, `totp_enabled`, and the backup-codes blob. All refresh tokens are revoked on disable (via `SessionRevocationService`).
- **Password change side effect:** `POST /me/password` also revokes all refresh tokens, forcing the user to sign in again on every device.
- **Replay & rate limiting:** the underlying TOTP library tolerates the immediately preceding 30-second window for clock skew; outside that, replays are rejected. There is no application-level lockout yet — that's a deferred item.

### WebSocket handshake

The realtime endpoint at `/ws` is exempt from `JwtAuthenticationFilter` and authenticates the upgrade through `realtime/internal/ws/JwtHandshakeInterceptor` instead. Browsers do not allow custom headers on a WebSocket upgrade, so the access token is supplied as a query parameter: `ws://host/ws?token=<JWT>`.

- The same RSA signing key, expiry rules, and token-type checks apply — there is **no separate WS token**.
- The handshake interceptor calls the public `AccessTokenAuthenticator` (`security/api/`); on failure the upgrade is rejected with HTTP 403.
- After the handshake, no further per-frame auth is performed — the validated `JwtClaims` are stored on the session for the lifetime of the connection.
- The frontend reconnects whenever the access token rotates (after a `/auth/refresh` 200), so a long-running socket cannot outlive its credentials.
- `/ws` is added to the `permitAll()` list in `SecurityConfiguration` because the handshake interceptor — not the JWT filter — is the auth boundary here.

### SAML 2.0 SSO

All JWT mechanisms remain in place. Additionally:

- SP-initiated and IdP-initiated SSO flows supported
- Spring Security SAML2 extension handles assertion parsing and validation
- On successful SAML assertion:
  1. Extract `NameID` as `saml_subject`
  2. Look up user by `saml_subject` — create if `auto_provision_users=true`
  3. Map SAML attributes to `display_name`, `email`, `role` per `attribute_mapping` config
  4. Issue standard JWT pair — same token format as for local logins
- SAML session lifetime respected: when IdP sends `SessionNotOnOrAfter`, refresh tokens beyond that time are rejected

### OAuth 2.0 / OIDC SSO

All JWT mechanisms remain in place. Configuration is **fully DB-driven** (`oauth2_config`
table, one row per `(organization_id, provider)`); there are no `spring.security.oauth2.client.*`
properties. Four providers ship with built-in templates: `GOOGLE`, `GITHUB`, `MICROSOFT`,
`GITLAB`. The admin enters only `client_id`, `client_secret`, optional `scopes_override`, and
(for Microsoft) `tenant_id`. Authorization / token / userinfo URLs come from
`OAuth2ProviderTemplate` and are never user-editable, so a misconfigured row cannot redirect
the browser to a hostile authorization server.

**Account-linking model — verified email + safe rejection.** The success handler:

1. Pulls `email` (and `email_verified` when the provider supplies it) from the userinfo
   payload. For GitHub it falls back to `GET /user/emails` with the access token and picks
   the row where `primary=true AND verified=true`.
2. Rejects sign-in with `OAUTH2_EMAIL_UNVERIFIED` when the provider says the email is not
   verified. Google / Microsoft / GitLab include `email_verified`; GitHub uses the
   `/user/emails` filter described above. We never trust an unverified email.
3. Looks up the matching user by email. If they already exist with
   `auth_provider=OAUTH2` (or `auth_provider=LOCAL` **without** a password hash, i.e.
   admin-created shell account), the existing row is reused.
4. Rejects with `OAUTH2_LOCAL_EMAIL_CONFLICT` when an existing user has
   `auth_provider=LOCAL` **and** a populated `password_hash`. The admin must manually
   convert the account before the user can sign in via OAuth — auto-linking would let
   anyone who controls a provider account with the same email take over a local account.
5. JIT-provisions a new user otherwise, with `auth_provider=OAUTH2` and `role =
   oauth2_config.default_role` (per-provider).

**Redirect handshake.** Spring Security's `oauth2Login()` handles the browser redirect to
the provider and the code-for-token exchange. The custom success handler then issues a
one-time exchange code via `OAuth2ExchangeCodeStore` (Redis, 60 s default TTL) and redirects
the browser to `${ACCESSFLOW_OAUTH2_FRONTEND_CALLBACK_URL}?code=…`. The frontend posts the
code to `/api/v1/auth/oauth2/exchange`, which consumes it (single-use) and returns the same
JWT pair shape as `/auth/login`. Tokens never appear in the redirect URL itself.

**Secret storage.** `oauth2_config.client_secret_encrypted` is AES-256-GCM ciphertext via
the existing `CredentialEncryptionService`. The entity field is `@JsonIgnore` and the admin
API returns `"********"` whenever a secret is stored — the plaintext never leaves
`DynamicClientRegistrationRepository.build`.

**Dynamic config refresh.** `DynamicClientRegistrationRepository` caches
`ClientRegistration`s per registration id and listens for `OAuth2ConfigUpdatedEvent` /
`OAuth2ConfigDeletedEvent` via `@ApplicationModuleListener`, mirroring
`AiAnalyzerStrategyHolder`. Config changes take effect on the next authorize request — no
application restart.

### API key authentication

Users may create personal API keys (under **Profile → API keys**) to authenticate the MCP
server and other programmatic clients without a browser session. The flow:

- **Format.** `af_<32-byte base64url, no padding>` — ~38 characters. Generated with
  `SecureRandom`; the `af_` prefix is informational.
- **Storage.** Only the `SHA-256` hash (hex, 64 chars) and a 12-char display prefix are
  persisted (`api_keys.key_hash`, `api_keys.key_prefix`). The plaintext is shown **once** on
  creation and is unrecoverable thereafter. Hashing is plain SHA-256 (not bcrypt) because lookup
  happens on every request and the keys carry 256 bits of entropy — brute force is infeasible.
- **Header parity.** The filter accepts either `X-API-Key: <key>` (preferred for MCP clients)
  or `Authorization: ApiKey <key>` (parity with `Authorization: Bearer <jwt>`). The CORS
  config exposes `X-API-Key` as an allowed header.
- **Scope.** A key acts as its owning user — same role, same datasource permissions, same
  review-self-approval block. There is no separate scope model: an API key can hit any endpoint
  the user can hit, including `/mcp/**`.
- **Lifecycle.** Per-user CRUD endpoints live at `/api/v1/me/api-keys` (see
  `docs/04-api-spec.md`). Revocation sets `revoked_at = now()` and is idempotent; revoked or
  expired keys never authenticate.
- **Filter placement.** `ApiKeyAuthenticationFilter` (in the `security` module, sibling to
  `JwtAuthenticationFilter`) runs before
  `JwtAuthenticationFilter` in the main security chain. If no API key header is present, the
  JWT filter still gets a chance. Both filters end up populating a `JwtAuthenticationToken`
  with the same `JwtClaims` shape, so downstream controllers and MCP tools are auth-agnostic.
- **Audit.** `api_keys.last_used_at` is bumped on each successful authentication. Bumps are
  best-effort and swallow exceptions to avoid impacting auth latency.

See `docs/13-mcp.md` for the end-user guide.

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
| Configure SAML | — | — | — | ✓ |
| Configure OAuth providers | — | — | — | ✓ |

**Key rule:** A user can never approve their own query request, regardless of role.

**CSV export of query history** (`GET /queries/export.csv`) reuses the same org-scoping and
submitter rules as `GET /queries`: non-admin callers receive only their own queries; admins may
override `submitted_by` to scope to a specific user. No additional role is required, and the
endpoint never returns SQL text — only the metadata fields already visible on the list page.

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
Are restricted_columns set?
  YES → AI analyzer is told which columns are sensitive (informational — never auto-rejects)
        SELECT result rows have those values replaced with "***" before persistence
         ↓
  PROCEED to review plan
```

### Column-level restrictions

`datasource_user_permissions.restricted_columns` is a `TEXT[]` of fully-qualified `schema.table.column` entries. This is a **defense-in-depth, value-masking** control — not a primary access boundary:

- Restricted columns can still be referenced in SQL (WHERE, JOIN, GROUP BY, etc.). The system does not reject the query; it masks the value in the SELECT response and informs the AI reviewer.
- Masking happens in `JdbcResultRowMapper` before rows are added to the in-memory result and before they are written to `query_request_results.rows`. The raw value never lands in our database. The sentinel is `"***"`; `null` stays `null`.
- The AI analyzer renders `*RESTRICTED*` markers next to flagged columns in the schema context and is instructed to emit `RESTRICTED_COLUMN_ACCESS` issues (severity `LOW`) — the workflow state machine ignores this category for auto-rejection logic.
- For high-confidentiality data where the value must never be retrievable at all, prefer an `allowed_tables` denial or a database-side view that excludes the column.

---

## Database Credential Security

- Customer DB credentials stored in `datasource.password_encrypted` as AES-256-GCM ciphertext
- Encryption key: `ENCRYPTION_KEY` env var (32-byte hex) — never stored in database
- `password_encrypted` is **excluded from all API serialization** (`@JsonIgnore`)
- Credentials are decrypted only inside the `QueryProxyService` at JDBC pool creation time
- The decrypted password is passed directly to HikariCP and not retained in application memory beyond pool initialization
- A dedicated low-privilege service account is recommended on each customer database (SELECT only, or specific table grants matching `allowed_tables`)

---

## Custom JDBC Driver Trust Boundary

Admin-uploaded JDBC driver JARs (see [`docs/05-backend.md`](05-backend.md#admin-uploaded-drivers-94--142)) live on the AccessFlow filesystem unencrypted. The trust anchors are:

- **Admin-only RBAC** — `POST /datasources/drivers`, `GET /datasources/drivers`, and the delete endpoint all require `hasRole('ADMIN')`. The upload flow is the only way to add a custom driver; the static `DriverRegistry` is compile-time and cannot be mutated at runtime.
- **Pinned SHA-256, verified twice** — The admin enters the expected SHA-256 on upload; the server computes the actual digest while streaming bytes to disk and refuses to persist on mismatch. Every subsequent `resolveCustom(...)` re-verifies the on-disk JAR against the persisted descriptor before instantiating the classloader, so an attacker who tampers with the file (e.g. via a privileged shell on the host) is caught at the next pool init.
- **Per-driver classloader isolation** — Each uploaded JAR loads into its own `URLClassLoader` keyed by `custom_jdbc_driver.id`. Two datasources targeting different uploaded drivers — even with the same `db_type` — cannot share static state. This also limits the blast radius of a malicious driver to its own classloader and the customer DB it connects to; it cannot reach AccessFlow beans (which live on the parent classloader and are not exported into the child).
- **No remote download path** — Unlike the bundled registry, uploaded drivers are never fetched from Maven Central or any remote URL. They are admin-supplied and verified locally.
- **Org-scoped visibility** — Every list / lookup / delete is filtered by `organization_id`. A driver uploaded by org A is invisible to org B, even at the `GET /datasources/types` catalog level.
- **Driver class probe** — At upload time the server instantiates the declared driver class in a throwaway classloader and asserts it implements `java.sql.Driver`. Uploads where the declared class is missing or wrong-typed are rejected with `422 CUSTOM_DRIVER_INVALID_JAR` so they cannot be referenced by a datasource later.
- **JARs are not encrypted at rest.** AccessFlow does not encrypt driver JARs because their contents are not secret (they typically come from public Maven coordinates) and the file is signed-by-content via SHA-256. Operators who need at-rest encryption should mount `${ACCESSFLOW_DRIVER_CACHE}` on a volume that provides it (e.g. dm-crypt / KMS-managed cloud volumes).

The 50 MB upload limit (`spring.servlet.multipart.max-file-size=50MB`) is a defence-in-depth bound — far above any legitimate driver (Snowflake's bundle, the largest in common use, is ~30 MB) and small enough that a runaway upload cannot fill an operator's storage volume.

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

The `audit_log` table is tamper-evident. The cryptographic chain (added in V26) makes any post-hoc edit, deletion, or reordering detectable; deployment-level role separation is still tracked separately (see "Deferred").

Implemented today:

- Audit writes go through `AuditLogService` (`audit/api/`). Writes are append-only — neither the entity nor the service exposes UPDATE or DELETE.
- User-initiated actions are audited synchronously from controllers so `ip_address` (honoring `X-Forwarded-For`) and `user_agent` from the HTTP request are captured on the row.
- System-driven state transitions are audited via `@ApplicationModuleListener` in `audit/internal/AuditEventListener` — these run after the publishing transaction commits, on a separate thread; `ip_address` / `user_agent` are NULL on those rows by design.
- `metadata` JSONB contains context-specific information but **never** stores query result data (rows returned), passwords, or encryption keys.
- **HMAC-SHA256 hash chain.** Every new row carries `previous_hash` (the predecessor's `current_hash`, NULL only for the org's first chained row) and `current_hash = HMAC-SHA256(key, canonical(row) ‖ previous_hash)`. The canonical form is a length-prefixed concatenation of `id`, `organization_id`, `actor_id`, `action`, `resource_type`, `resource_id`, normalised JSON metadata, `ip_address`, `user_agent`, and ISO-8601 `created_at` — length-prefixed so the encoding is injective. The key is `AUDIT_HMAC_KEY` (hex-encoded, ≥ 32 bytes); when unset, the audit module derives the key from `ENCRYPTION_KEY` via HKDF-SHA256 with info string `accessflow-audit-hmac-v1` and logs a single WARN. Startup fails if neither key is available.
- **Per-organization insert serialization.** `DefaultAuditLogService.record(...)` takes a Postgres advisory lock (`pg_advisory_xact_lock(orgIdHigh ^ orgIdLow)`) inside the transaction before reading the prior row's hash, so concurrent writes to the same org cannot interleave and break the chain. The lock releases automatically on commit/rollback.
- **Verifier endpoint.** `GET /api/v1/admin/audit-log/verify` (ADMIN only) walks the chain in ASC order, recomputes each row's HMAC, and returns the first row whose recorded `previous_hash` or `current_hash` does not match — see `docs/04-api-spec.md`. The verifier is scoped to the caller's organization. Pre-V26 rows have NULL hashes and are skipped without counting.

Deferred (tracked as separate GitHub issues):

- The application database user has **INSERT-only** privilege on `audit_log`. No UPDATE or DELETE. Today the application uses a single Postgres role; the second role with INSERT-only grant is a deployment-level change tracked separately.
- A **separate audit writer DB user** for audit log inserts, distinct from the general application user.
- Exporting hashes in `GET /admin/audit-log` row responses (the verifier is the canonical tamper-detection surface today).

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
| `AUDIT_HMAC_KEY` | Environment variable / Kubernetes Secret (hex, ≥ 32 bytes). Optional — when unset, derived from `ENCRYPTION_KEY` via HKDF-SHA256. |
| `AI_API_KEY` | Environment variable / Kubernetes Secret |
| `DB_PASSWORD` | Environment variable / Kubernetes Secret |
| Customer DB credentials | Stored encrypted in DB; never in env vars |
| SAML keystore password | Environment variable / Kubernetes Secret |

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
