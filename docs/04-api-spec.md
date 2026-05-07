# 04 — REST API Specification

## General

- **Base path:** `/api/v1`
- **Authentication:** `Authorization: Bearer <JWT>` on all endpoints except `/auth/*`
- **Rate limits:** 1000 req/min general; 100 req/min for query execution endpoints
- **Content-Type:** `application/json`
- **Error format:**
```json
{
  "error": "PERMISSION_DENIED",
  "message": "You do not have write access to this datasource",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

---

## Authentication Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/auth/login` | Authenticate with email + password, returns JWT access token + HttpOnly refresh token cookie |
| `POST` | `/auth/refresh` | Exchange refresh token for new access token |
| `POST` | `/auth/logout` | Revoke current refresh token |
| `GET` | `/auth/saml/metadata` | Returns SP SAML metadata XML *(Enterprise only)* |
| `POST` | `/auth/saml/acs` | SAML Assertion Consumer Service endpoint *(Enterprise only)* |

### POST /auth/login

**Request:**
```json
{
  "email": "alice@company.com",
  "password": "secret"
}
```

**Response 200:**
```json
{
  "access_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 900,
  "user": {
    "id": "uuid",
    "email": "alice@company.com",
    "display_name": "Alice",
    "role": "ANALYST"
  }
}
```

The response also sets a `refresh_token` cookie scoped to `Path=/api/v1/auth` with `HttpOnly; Secure; SameSite=Strict` and a 7-day max-age.

**Response 401:** Invalid credentials, disabled account, or unknown email.

### POST /auth/refresh

Exchanges the `refresh_token` cookie for a new access token. Reads the refresh token from the `refresh_token` HttpOnly cookie sent automatically by the browser; no request body.

**Response 200:** Same shape as `POST /auth/login`. A rotated `refresh_token` cookie is set on the response.

**Response 401:** Cookie missing, expired, malformed, or revoked.

### POST /auth/logout

Revokes the current refresh token and clears the cookie. Reads the refresh token from the `refresh_token` cookie; no request body.

**Response 204:** No content. The response sets `refresh_token=` with `Max-Age=0` to clear the cookie. Returns 204 even when no cookie is present, so logout is idempotent.

---

## Datasource Endpoints

| Method | Path | Auth Required | Description |
|--------|------|---------------|-------------|
| `GET` | `/datasources/types` | Any | List supported database types with display metadata for the create wizard |
| `GET` | `/datasources` | Any | List datasources the current user has access to |
| `POST` | `/datasources` | ADMIN | Create new datasource |
| `GET` | `/datasources/{id}` | Any | Get datasource details |
| `PUT` | `/datasources/{id}` | ADMIN | Update datasource configuration |
| `DELETE` | `/datasources/{id}` | ADMIN | Soft-delete datasource |
| `POST` | `/datasources/{id}/test` | ADMIN | Test connection to customer database |
| `GET` | `/datasources/{id}/schema` | Any (with access) | Introspect tables and columns from customer DB |
| `GET` | `/datasources/{id}/permissions` | ADMIN | List all user permissions for a datasource |
| `POST` | `/datasources/{id}/permissions` | ADMIN | Grant a user permission on a datasource |
| `DELETE` | `/datasources/{id}/permissions/{permId}` | ADMIN | Revoke a permission |

### GET /datasources/types — Response 200

```json
{
  "types": [
    {
      "code": "POSTGRESQL",
      "display_name": "PostgreSQL",
      "icon_url": "/static/db-icons/postgresql.svg",
      "default_port": 5432,
      "default_ssl_mode": "VERIFY_FULL",
      "jdbc_url_template": "jdbc:postgresql://{host}:{port}/{database_name}",
      "driver_status": "READY"
    },
    {
      "code": "MYSQL",
      "display_name": "MySQL",
      "icon_url": "/static/db-icons/mysql.svg",
      "default_port": 3306,
      "default_ssl_mode": "REQUIRED",
      "jdbc_url_template": "jdbc:mysql://{host}:{port}/{database_name}",
      "driver_status": "AVAILABLE"
    }
  ]
}
```

`driver_status` values:

| Value | Meaning |
|-------|---------|
| `READY` | Driver is cached locally; first connection has no resolution cost. |
| `AVAILABLE` | Driver is in the registry but not yet cached; will be downloaded on first use. |
| `UNAVAILABLE` | Registry entry exists but the cache directory is unwritable or the resolver is offline without a cache hit. Admin attention needed. |

Initial supported types: `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `MSSQL`. See `docs/05-backend.md` → Dynamic JDBC Driver Loading for the resolution mechanism.

### GET /datasources — Query Parameters

| Param | Type | Description |
|-------|------|-------------|
| `page` | int | Page number (default 0) |
| `size` | int | Page size (default 20, max 100) |
| `sort` | string | e.g. `name,asc` (Spring Data sort syntax) |

**Response 200:**
```json
{
  "content": [
    {
      "id": "uuid",
      "organization_id": "uuid",
      "name": "Production PostgreSQL",
      "db_type": "POSTGRESQL",
      "host": "db.company.com",
      "port": 5432,
      "database_name": "app_prod",
      "username": "accessflow_svc",
      "ssl_mode": "VERIFY_FULL",
      "connection_pool_size": 10,
      "max_rows_per_query": 1000,
      "require_review_reads": false,
      "require_review_writes": true,
      "review_plan_id": "uuid",
      "ai_analysis_enabled": true,
      "active": true,
      "created_at": "2026-05-04T10:15:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total_elements": 1,
  "total_pages": 1
}
```

Results are scoped to the caller's organization. ADMINs see all datasources in the organization. Non-ADMINs see only datasources they have a row in `datasource_user_permissions` for. The encrypted password is never serialized.

### POST /datasources — Request Body

```json
{
  "name": "Production PostgreSQL",
  "db_type": "POSTGRESQL",
  "host": "db.company.com",
  "port": 5432,
  "database_name": "app_prod",
  "username": "accessflow_svc",
  "password": "service_account_password",
  "ssl_mode": "VERIFY_FULL",
  "connection_pool_size": 10,
  "max_rows_per_query": 1000,
  "require_review_reads": false,
  "require_review_writes": true,
  "review_plan_id": "uuid",
  "ai_analysis_enabled": true
}
```

The password is AES-256-GCM encrypted server-side using the `ENCRYPTION_KEY` env var before persistence.

**Response 201:** Single datasource object (same shape as a `content[]` element above). The `Location` header points to `/api/v1/datasources/{id}`.

**Response 400:** Validation error (missing required field, port out of range, etc.). `error: VALIDATION_ERROR`.
**Response 403:** Caller is not an ADMIN. `error: FORBIDDEN`.
**Response 409:** A datasource with this name already exists in the caller's organization. `error: DATASOURCE_NAME_ALREADY_EXISTS`.
**Response 422:** JDBC driver for `db_type` could not be resolved (download failure, checksum mismatch, or offline-mode cache miss). The `detail` field contains the resolver error. `error: DATASOURCE_DRIVER_UNAVAILABLE`.

### GET /datasources/{id} — Response 200

Single datasource object — same shape as a `content[]` element above. Returns `404 DATASOURCE_NOT_FOUND` if the datasource does not exist, belongs to a different organization, or — for non-ADMIN callers — if the caller has no permission row for it.

### PUT /datasources/{id} — Request Body

All fields optional. Omitted fields are left unchanged. Providing `password` triggers re-encryption with a fresh IV.

```json
{
  "name": "Production PostgreSQL (renamed)",
  "host": "new-db.company.com",
  "port": 5432,
  "password": "new-service-account-password",
  "connection_pool_size": 25,
  "active": true
}
```

**Response 200:** Updated datasource object.
**Response 404:** Datasource does not exist in the caller's organization. `error: DATASOURCE_NOT_FOUND`.
**Response 409:** Renaming would conflict with another datasource in the same organization. `error: DATASOURCE_NAME_ALREADY_EXISTS`.

### DELETE /datasources/{id}

Soft-deactivates the datasource (`is_active=false`). The row is retained for audit log foreign-key integrity. Restore by `PUT` with `{"active": true}`.

**Response 204:** No content.
**Response 404:** Datasource does not exist in the caller's organization. `error: DATASOURCE_NOT_FOUND`.

### POST /datasources/{id}/test

Opens a transient JDBC connection to the customer database (no Hikari pool), executes `SELECT 1`, and closes the connection. Login timeout is 5 seconds.

> The first call against a newly added `db_type` may take longer because the JDBC driver is resolved on demand (see `docs/05-backend.md` → Dynamic JDBC Driver Loading). The 5-second login timeout does **not** include driver download time. Driver-resolution failures surface as HTTP 422 `DATASOURCE_DRIVER_UNAVAILABLE`.

**Response 200:**
```json
{ "ok": true, "latency_ms": 42, "message": "ok" }
```

**Response 404:** Datasource does not exist in the caller's organization. `error: DATASOURCE_NOT_FOUND`.
**Response 422:** Connection failed. The body contains the vendor error message in `detail`. `error: DATASOURCE_CONNECTION_TEST_FAILED`.

### GET /datasources/{id}/schema — Response

Introspects tables and columns from the customer database via JDBC `DatabaseMetaData`. System schemas (`pg_catalog`, `information_schema`, `pg_toast`, `mysql`, `performance_schema`, `sys`) are filtered out. ADMINs may introspect any datasource in their organization; non-ADMINs require a permission row.

```json
{
  "schemas": [
    {
      "name": "public",
      "tables": [
        {
          "name": "users",
          "columns": [
            { "name": "id", "type": "uuid", "nullable": false, "primary_key": true },
            { "name": "email", "type": "varchar", "nullable": false, "primary_key": false }
          ]
        }
      ]
    }
  ]
}
```

**Response 404:** Datasource does not exist in the caller's organization, or — for non-ADMIN callers — caller has no permission row. `error: DATASOURCE_NOT_FOUND`.
**Response 422:** Schema introspection failed (e.g. customer database unreachable). `error: DATASOURCE_CONNECTION_TEST_FAILED`.

### GET /datasources/{id}/permissions — Response 200

```json
{
  "content": [
    {
      "id": "uuid",
      "datasource_id": "uuid",
      "user_id": "uuid",
      "user_email": "alice@company.com",
      "user_display_name": "Alice",
      "can_read": true,
      "can_write": false,
      "can_ddl": false,
      "row_limit_override": null,
      "allowed_schemas": ["public"],
      "allowed_tables": null,
      "expires_at": null,
      "created_by": "uuid",
      "created_at": "2026-05-04T10:15:00Z"
    }
  ]
}
```

### POST /datasources/{id}/permissions — Request Body

```json
{
  "user_id": "uuid",
  "can_read": true,
  "can_write": false,
  "can_ddl": false,
  "row_limit_override": 1000,
  "allowed_schemas": ["public"],
  "allowed_tables": ["users", "orders"],
  "expires_at": "2026-12-31T23:59:59Z"
}
```

**Response 201:** Permission object. `Location` header points to `/api/v1/datasources/{id}/permissions/{permId}`.
**Response 404:** Datasource does not exist in the caller's organization. `error: DATASOURCE_NOT_FOUND`.
**Response 409:** A permission row already exists for `(user_id, datasource_id)`. `error: DATASOURCE_PERMISSION_ALREADY_EXISTS`.
**Response 422:** Target user does not exist or does not belong to the caller's organization. `error: ILLEGAL_DATASOURCE_PERMISSION`.

### DELETE /datasources/{id}/permissions/{permId}

**Response 204:** No content.
**Response 404:** Permission does not exist or belongs to a different datasource. `error: DATASOURCE_PERMISSION_NOT_FOUND`.

---

## Query Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/queries` | Submit a query for review/execution |
| `GET` | `/queries` | List query requests (filterable by status, datasource, user, date range) |
| `GET` | `/queries/{id}` | Get full query request details including AI analysis |
| `DELETE` | `/queries/{id}` | Cancel a pending query (submitter only) |
| `POST` | `/queries/{id}/execute` | Manually trigger execution of an approved query |
| `GET` | `/queries/{id}/results` | Stream paginated query results (SELECT only) |
| `POST` | `/queries/analyze` | Submit SQL for AI analysis only — no execution, no review created |

### POST /queries — Request Body

```json
{
  "datasource_id": "uuid",
  "sql": "UPDATE orders SET status = 'shipped' WHERE id = 123",
  "justification": "Customer support ticket #8821 — order stuck in processing"
}
```

### POST /queries — Response 202 Accepted

```json
{
  "id": "uuid",
  "status": "PENDING_AI",
  "ai_analysis": null,
  "review_plan": {
    "requires_ai_review": true,
    "requires_human_approval": true,
    "min_approvals_required": 1
  },
  "estimated_review_completion": "2025-01-15T14:30:00Z"
}
```

`review_plan` and `estimated_review_completion` are returned as `null` until the review-plan resolution and SLA estimation features ship; the AI analyzer is triggered asynchronously and the persisted query starts in `PENDING_AI`.

**Errors:**
- `400 VALIDATION_ERROR` — request body missing `datasource_id` or `sql`.
- `403 FORBIDDEN` — caller has no active permission row for this datasource, or the row is missing the capability matching the query type (`can_read` for SELECT, `can_write` for INSERT/UPDATE/DELETE, `can_ddl` for DDL). Admins bypass this check.
- `404 DATASOURCE_NOT_FOUND` — datasource does not exist in the caller's organization, or — for non-admin callers — the caller has no permission row for it.
- `422 INVALID_SQL` — SQL did not parse, contained multiple statements, or classified as `OTHER` (transactional / session-control statements are not accepted).
- `422 DATASOURCE_UNAVAILABLE` — datasource exists but is inactive.

### GET /queries — Query Parameters

| Param | Type | Description |
|-------|------|-------------|
| `status` | string | Filter by status enum value |
| `datasource_id` | UUID | Filter by datasource |
| `submitted_by` | UUID | Filter by submitter |
| `from` | ISO datetime | Created after |
| `to` | ISO datetime | Created before |
| `query_type` | string | SELECT, INSERT, UPDATE, DELETE, DDL |
| `page` | int | Page number (default 0) |
| `size` | int | Page size (default 20, max 100) |

### GET /queries/{id} — Response

```json
{
  "id": "uuid",
  "datasource": { "id": "uuid", "name": "Production PostgreSQL" },
  "submitted_by": { "id": "uuid", "email": "alice@company.com", "display_name": "Alice" },
  "sql_text": "UPDATE orders SET status = 'shipped' WHERE id = 123",
  "query_type": "UPDATE",
  "status": "PENDING_REVIEW",
  "justification": "Customer support ticket #8821",
  "ai_analysis": {
    "risk_score": 42,
    "risk_level": "MEDIUM",
    "summary": "Single-row UPDATE with indexed WHERE clause. No issues detected.",
    "issues": [],
    "missing_indexes_detected": false,
    "affects_row_estimate": 1
  },
  "review_decisions": [],
  "created_at": "2025-01-15T10:00:00Z",
  "updated_at": "2025-01-15T10:01:00Z"
}
```

### POST /queries/analyze — Request Body

```json
{
  "datasource_id": "uuid",
  "sql": "SELECT * FROM users"
}
```

**Response 200:**
```json
{
  "risk_score": 75,
  "risk_level": "HIGH",
  "summary": "SELECT * returns all columns including sensitive fields. No LIMIT clause.",
  "issues": [
    {
      "severity": "HIGH",
      "category": "SELECT_STAR",
      "message": "SELECT * returns all columns including potentially sensitive data",
      "suggestion": "Specify only the columns you need: SELECT id, name, email FROM users"
    },
    {
      "severity": "MEDIUM",
      "category": "NO_LIMIT",
      "message": "Query has no LIMIT clause and could return millions of rows",
      "suggestion": "Add LIMIT 1000 or a WHERE clause to restrict results"
    }
  ],
  "missing_indexes_detected": false,
  "affects_row_estimate": null
}
```

---

## Review Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/reviews/pending` | List all query requests awaiting the current user's review |
| `POST` | `/reviews/{queryId}/approve` | Approve a query request |
| `POST` | `/reviews/{queryId}/reject` | Reject a query request |
| `POST` | `/reviews/{queryId}/request-changes` | Request changes from submitter before re-submission |
| `GET` | `/review-plans` | List all review plans |
| `POST` | `/review-plans` | Create a new review plan *(ADMIN only)* |
| `PUT` | `/review-plans/{id}` | Update a review plan *(ADMIN only)* |
| `DELETE` | `/review-plans/{id}` | Delete a review plan *(ADMIN only)* |

All `/reviews/*` endpoints require `REVIEWER` or `ADMIN` role. The submitter of a query is **never** allowed to approve, reject, or request changes on it (HTTP 403, regardless of role). Reviewers must additionally match a `review_plan_approvers` row at the query's *current* stage.

`min_approvals_required` on the review plan is interpreted **per stage**: each stage must collect that many `APPROVED` decisions before the next stage's approvers are considered current. A single `REJECTED` decision at any stage transitions the query to `REJECTED`. `REQUESTED_CHANGES` is recorded but does not transition the query — it remains in `PENDING_REVIEW` until another decision is made.

### GET /reviews/pending — Query Parameters

Standard pagination (`page`, `size`). Result is filtered to queries the caller can act on at the current stage; queries the reviewer can see but cannot yet act on (e.g. they're a stage-2 approver and stage 1 is still pending) are omitted.

**Response 200:**

```json
{
  "content": [
    {
      "id": "uuid",
      "datasource": { "id": "uuid", "name": "Production PostgreSQL" },
      "submitted_by": { "id": "uuid", "email": "alice@company.com" },
      "sql_text": "UPDATE orders SET status = 'shipped' WHERE id = 123",
      "query_type": "UPDATE",
      "justification": "Customer support ticket #8821",
      "ai_analysis": {
        "id": "uuid",
        "risk_level": "MEDIUM",
        "risk_score": 42,
        "summary": "Single-row UPDATE with indexed WHERE clause."
      },
      "current_stage": 1,
      "created_at": "2025-01-15T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total_elements": 1,
  "total_pages": 1
}
```

### POST /reviews/{queryId}/approve — Request Body

```json
{
  "comment": "Looks good — single row, indexed WHERE clause, justification matches ticket."
}
```

`comment` is optional. When the per-stage threshold is met AND it's the last stage, the query transitions to `APPROVED`. Otherwise it stays `PENDING_REVIEW` (more approvers needed at this stage, or higher stages remain).

### POST /reviews/{queryId}/reject — Request Body

```json
{
  "comment": "Please add a more specific WHERE clause. The current one could match multiple rows."
}
```

`comment` is optional. The query is immediately transitioned to `REJECTED`.

### POST /reviews/{queryId}/request-changes — Request Body

```json
{
  "comment": "Please narrow the WHERE clause to the specific order id."
}
```

`comment` is **required** (HTTP 400 if blank). The decision is recorded with type `REQUESTED_CHANGES`; the query remains in `PENDING_REVIEW` so the submitter (or another reviewer) can act on the comment.

### Common Response Body (approve / reject / request-changes)

```json
{
  "query_request_id": "uuid",
  "decision_id": "uuid",
  "decision": "APPROVED",
  "resulting_status": "APPROVED",
  "idempotent_replay": false
}
```

`idempotent_replay` is `true` if the same reviewer submitted the same decision at the same stage previously (the existing decision is returned unchanged). The unique index `(query_request_id, reviewer_id, stage)` enforces single-decision-per-stage at the database level.

### Common Error Codes

| Status | `error` code | Cause |
|--------|--------------|-------|
| 400 | `VALIDATION_ERROR` | Comment missing on `request-changes` |
| 401 | `UNAUTHORIZED` | Missing or invalid JWT |
| 403 | `FORBIDDEN` | Caller is the submitter (self-approval blocked) |
| 403 | `REVIEWER_NOT_ELIGIBLE` | Caller is not an approver at the current stage |
| 404 | `QUERY_REQUEST_NOT_FOUND` | Query doesn't exist or belongs to a different organization |
| 409 | `QUERY_NOT_PENDING_REVIEW` | Query is not in `PENDING_REVIEW` (already terminal, or still in `PENDING_AI`) |
| 409 | `ILLEGAL_STATUS_TRANSITION` | Lower-level state-machine guard fired |

### GET /review-plans

Returns every review plan visible to the caller's organization (no pagination — picker-friendly). Authenticated users in the org can read; only `ADMIN` can mutate.

**Response 200:**

```json
{
  "content": [
    {
      "id": "uuid",
      "organization_id": "uuid",
      "name": "PII writes",
      "description": "All writes against PII tables",
      "requires_ai_review": true,
      "requires_human_approval": true,
      "min_approvals_required": 1,
      "approval_timeout_hours": 24,
      "auto_approve_reads": false,
      "notify_channels": [],
      "approvers": [
        { "user_id": null, "role": "REVIEWER", "stage": 1 }
      ],
      "created_at": "2026-05-01T00:00:00Z"
    }
  ]
}
```

### POST /review-plans — Request Body *(ADMIN only)*

```json
{
  "name": "PII writes",
  "description": "All writes against PII tables",
  "requires_ai_review": true,
  "requires_human_approval": true,
  "min_approvals_required": 1,
  "approval_timeout_hours": 24,
  "auto_approve_reads": false,
  "notify_channels": [],
  "approvers": [
    { "user_id": null, "role": "REVIEWER", "stage": 1 }
  ]
}
```

Validation: `name` non-blank, ≤255 chars; `description` ≤2000; `min_approvals_required` 1–10; `approval_timeout_hours` 1–8760. Each approver must specify `user_id` OR `role` (`role` must be `ADMIN` or `REVIEWER`); `stage` ≥ 1. When `requires_human_approval=true` at least one approver is required.

**Response 201**: full review plan body (same shape as `GET /review-plans/{id}`); `Location` header points to the new resource.

### GET /review-plans/{id}

Returns a single review plan in the caller's organization. 404 if missing or in another organization.

### PUT /review-plans/{id} — Request Body *(ADMIN only)*

Same shape as `POST` but every field is optional. When `approvers` is provided, the entire approver set is replaced atomically; omit it to keep the existing approvers.

### DELETE /review-plans/{id} *(ADMIN only)*

Returns 204 on success. Returns **409 `REVIEW_PLAN_IN_USE`** if any datasource still references the plan; reassign datasources to a different plan before deletion. Approver rows attached to the plan are removed inside the same transaction.

### Review-plans Error Codes

| Status | `error` code | Cause |
|--------|--------------|-------|
| 400 | `VALIDATION_ERROR` | Bean Validation failure on the request body |
| 403 | `FORBIDDEN` | Caller is not an `ADMIN` (mutations only) |
| 404 | `REVIEW_PLAN_NOT_FOUND` | Plan does not exist or is in another organization |
| 409 | `REVIEW_PLAN_IN_USE` | One or more datasources still reference the plan |
| 422 | `ILLEGAL_REVIEW_PLAN` | Approver configuration invalid, name conflict, etc. |

---

## Admin Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/admin/users` | List all users in the organization |
| `POST` | `/admin/users` | Create user (Community Edition — no SSO) |
| `PUT` | `/admin/users/{id}` | Update user role or active status |
| `DELETE` | `/admin/users/{id}` | Deactivate user |
| `GET` | `/admin/audit-log` | Query audit log with filters (see below) |
| `GET` | `/admin/notification-channels` | List notification channels |
| `POST` | `/admin/notification-channels` | Add a notification channel |
| `PUT` | `/admin/notification-channels/{id}` | Update channel configuration |
| `POST` | `/admin/notification-channels/{id}/test` | Send a test notification |
| `GET` | `/admin/ai-config` | Get current AI analyzer configuration |
| `PUT` | `/admin/ai-config` | Update AI provider, model, API key *(ADMIN only)* |
| `GET` | `/admin/saml-config` | Get SAML configuration *(Enterprise only)* |
| `PUT` | `/admin/saml-config` | Update SAML configuration *(Enterprise only)* |
| `GET` | `/system/info` | Returns edition, version, feature flags |

### GET /admin/users — Query Parameters

| Param | Type | Description |
|-------|------|-------------|
| `page` | int | Page number (default 0) |
| `size` | int | Page size (default 20, max 100) |
| `sort` | string | e.g. `email,asc` (Spring Data sort syntax) |

**Response 200:**
```json
{
  "content": [
    {
      "id": "uuid",
      "email": "alice@company.com",
      "display_name": "Alice",
      "role": "ANALYST",
      "auth_provider": "LOCAL",
      "active": true,
      "last_login_at": "2026-05-04T10:15:00Z",
      "created_at": "2026-04-01T09:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total_elements": 1,
  "total_pages": 1
}
```

Results are scoped to the caller's organization.

### POST /admin/users — Request Body

```json
{
  "email": "newuser@company.com",
  "password": "InitialPassword123!",
  "display_name": "New User",
  "role": "ANALYST"
}
```

The new user is created with `auth_provider=LOCAL` in the caller's organization.

**Response 201:** Single user object (same shape as a `content[]` element above). The `Location` header points to `/api/v1/admin/users/{id}`.

**Response 400:** Validation error (invalid email, password too short, missing role).
**Response 409:** A user with this email already exists. `error: EMAIL_ALREADY_EXISTS`.

### PUT /admin/users/{id} — Request Body

All fields optional. Omitted fields are left unchanged.

```json
{
  "role": "REVIEWER",
  "active": true,
  "display_name": "Updated Name"
}
```

**Response 200:** Updated user object.
**Response 404:** User does not exist in the caller's organization. `error: USER_NOT_FOUND`.
**Response 422:** Self-protection violation — admins cannot demote themselves from `ADMIN` or set `active=false` on their own account. `error: ILLEGAL_USER_OPERATION`.

### DELETE /admin/users/{id}

Soft-deactivates the user (`active=false`) and revokes all of their refresh tokens, forcing logout across all sessions.

**Response 204:** No content.
**Response 404:** User does not exist in the caller's organization. `error: USER_NOT_FOUND`.
**Response 422:** Admins cannot deactivate their own account. `error: ILLEGAL_USER_OPERATION`.

### Notification Channels (`/admin/notification-channels`)

All four endpoints require `role=ADMIN` and operate within the caller's organization. Sensitive config fields (`smtp_password`, `secret`) are AES-256-GCM encrypted at rest and replaced with the literal `"********"` on read; sending the masked value back on `PUT` preserves the existing ciphertext.

#### GET /admin/notification-channels

**Response 200:**
```json
[
  {
    "id": "uuid",
    "organization_id": "uuid",
    "channel_type": "WEBHOOK",
    "name": "Eng webhook",
    "config": {
      "url": "https://hooks.example.com/x",
      "secret": "********",
      "timeout_seconds": 10
    },
    "active": true,
    "created_at": "2026-05-06T10:15:00Z"
  }
]
```

#### POST /admin/notification-channels

**Request body:**
```json
{
  "name": "Eng webhook",
  "channel_type": "WEBHOOK",
  "config": {
    "url": "https://hooks.example.com/x",
    "secret": "topsecret",
    "timeout_seconds": 10
  }
}
```

**Response 201:** The created channel (same shape as GET, `secret` replaced with `********`). `Location` header points to `/api/v1/admin/notification-channels/{id}`.
**Response 400:** Validation error (e.g. missing `name`).
**Response 422:** Channel `config` is missing required keys for its type. `error: NOTIFICATION_CHANNEL_CONFIG_INVALID`.

Required `config` keys per channel type:
- `EMAIL`: `smtp_host`, `smtp_port`, `smtp_password`, `from_address` (optional: `smtp_user`, `smtp_tls` (default true), `from_name`).
- `SLACK`: `webhook_url` (optional: `channel`, `mention_users`).
- `WEBHOOK`: `url`, `secret` (optional: `timeout_seconds` default 10).

#### PUT /admin/notification-channels/{id}

Partial update. Any field omitted is left unchanged. Sending `"smtp_password": "********"` or `"secret": "********"` preserves the existing ciphertext.

**Request body:**
```json
{
  "name": "Eng webhook v2",
  "active": true,
  "config": {
    "secret": "rotated-secret"
  }
}
```

**Response 200:** The updated channel.
**Response 404:** Channel not found in caller's organization. `error: NOTIFICATION_CHANNEL_NOT_FOUND`.
**Response 422:** Resulting config is invalid. `error: NOTIFICATION_CHANNEL_CONFIG_INVALID`.

#### POST /admin/notification-channels/{id}/test

Sends a synthetic message through the channel.

**Request body (optional, EMAIL only):**
```json
{ "email": "ops@example.com" }
```

**Response 200:**
```json
{ "status": "OK", "detail": "Test notification dispatched" }
```

Per-type behavior:
- `EMAIL`: sends a fixed-subject test email to `body.email` if present, else to the configured `from_address`.
- `SLACK`: posts the literal message `AccessFlow notification channel test successful`.
- `WEBHOOK`: POSTs `{"event": "TEST", "timestamp": "..."}` with full HMAC headers (`X-AccessFlow-Event: TEST`, `X-AccessFlow-Signature`, `X-AccessFlow-Delivery`).

**Response 404:** Channel not found in caller's organization.
**Response 502:** Delivery failed. `error: NOTIFICATION_DELIVERY_FAILED`.

### GET /admin/audit-log — Query Parameters

| Param | Type | Description |
|-------|------|-------------|
| `actorId` | UUID | Filter by user who performed the action |
| `action` | string | Filter by action type (e.g. `QUERY_SUBMITTED`) |
| `resourceType` | string | Filter by resource type, snake_case (e.g. `query_request`, `datasource`, `user`, `permission`, `notification_channel`) |
| `resourceId` | UUID | Filter by specific resource |
| `from` | ISO datetime | Inclusive lower bound on `created_at` |
| `to` | ISO datetime | Exclusive upper bound on `created_at` |
| `page` | int | Page number (default 0) |
| `size` | int | Page size (default 20, max 500). Requests over the cap get `400 BAD_AUDIT_QUERY` |
| `sort` | string | Spring Data sort syntax; default `created_at,DESC` |

Returns rows scoped to the caller's organization only. ADMIN role required (otherwise 403).

**Response 200:**
```json
{
  "content": [
    {
      "id": "uuid",
      "organization_id": "uuid",
      "actor_id": "uuid",
      "action": "QUERY_SUBMITTED",
      "resource_type": "query_request",
      "resource_id": "uuid",
      "metadata": {"datasource_id": "uuid"},
      "ip_address": "10.0.0.1",
      "user_agent": "Mozilla/5.0",
      "created_at": "2026-05-06T10:30:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total_elements": 1,
  "total_pages": 1
}
```

`actor_id`, `ip_address`, `user_agent` may be `null` for system-driven rows (e.g. AI analysis completion).

---

## WebSocket Events

**Endpoint:** `ws://host/ws`  
**Auth:** Pass JWT as query param `?token=<JWT>` on connect.

Clients subscribe to real-time updates for their own queries and (for reviewers) new review requests.

| Event | Description | Payload Fields |
|-------|-------------|----------------|
| `query.status_changed` | Query request changes status | `query_id`, `old_status`, `new_status` |
| `review.new_request` | New query needs reviewer's approval | `query_id`, `risk_level`, `submitter`, `datasource` |
| `review.decision_made` | Reviewer approved/rejected submitter's query | `query_id`, `decision`, `reviewer`, `comment` |
| `query.executed` | Execution completed | `query_id`, `rows_affected`, `duration_ms` |
| `ai.analysis_complete` | AI analysis finished | `query_id`, `risk_level`, `risk_score` |

### WebSocket Message Format

```json
{
  "event": "query.status_changed",
  "timestamp": "2025-01-15T10:31:00Z",
  "data": {
    "query_id": "uuid",
    "old_status": "PENDING_AI",
    "new_status": "PENDING_REVIEW"
  }
}
```

---

## Common HTTP Status Codes

| Code | Meaning |
|------|---------|
| `200` | OK |
| `201` | Created |
| `202` | Accepted (async operation started) |
| `204` | No Content (successful DELETE) |
| `400` | Bad Request — validation error |
| `401` | Unauthorized — missing or invalid JWT |
| `403` | Forbidden — insufficient role or no datasource permission |
| `404` | Not Found |
| `409` | Conflict — e.g. duplicate datasource name |
| `422` | Unprocessable Entity — SQL parse error, query execution failure, datasource unavailable |
| `429` | Too Many Requests — rate limit hit |
| `500` | Internal Server Error |
| `503` | Service Unavailable — connection pool initialization failed |
| `504` | Gateway Timeout — query exceeded `accessflow.proxy.execution.statement-timeout` |

## Error Codes (`error` property on `ProblemDetail`)

The following codes are returned in addition to the per-endpoint codes documented above:

| Code | HTTP | Source | Notes |
|------|------|--------|-------|
| `INVALID_SQL` | 422 | `InvalidSqlException` | SQL did not parse, or contained multiple statements. |
| `QUERY_EXECUTION_FAILED` | 422 | `QueryExecutionFailedException` | The customer database rejected the query. Body includes `sqlState` and `vendorCode`. |
| `QUERY_EXECUTION_TIMEOUT` | 504 | `QueryExecutionTimeoutException` | Query exceeded the configured statement timeout. Body includes `timeoutSeconds`. |
| `DATASOURCE_UNAVAILABLE` | 422 | `DatasourceUnavailableException` | Datasource is missing or marked inactive. |
| `POOL_INITIALIZATION_FAILED` | 503 | `PoolInitializationException` | HikariCP could not open a pool to the customer database (bad credentials, host unreachable, etc.). |
