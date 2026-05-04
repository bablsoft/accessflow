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

### GET /datasources/{id}/schema — Response

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

### POST /reviews/{queryId}/approve — Request Body

```json
{
  "comment": "Looks good — single row, indexed WHERE clause, justification matches ticket."
}
```

### POST /reviews/{queryId}/reject — Request Body

```json
{
  "comment": "Please add a more specific WHERE clause. The current one could match multiple rows."
}
```

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

### GET /admin/audit-log — Query Parameters

| Param | Type | Description |
|-------|------|-------------|
| `actor_id` | UUID | Filter by user who performed the action |
| `action` | string | Filter by action type (e.g. `QUERY_SUBMITTED`) |
| `resource_type` | string | Filter by resource type |
| `resource_id` | UUID | Filter by specific resource |
| `from` | ISO datetime | Start of time range |
| `to` | ISO datetime | End of time range |
| `page` | int | Page number |
| `size` | int | Page size (max 500) |

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
| `422` | Unprocessable Entity — SQL parse error |
| `429` | Too Many Requests — rate limit hit |
| `500` | Internal Server Error |
