# MCP server and API keys

AccessFlow exposes a **Model Context Protocol (MCP)** server so AI agents — Claude Desktop,
Claude Code, custom LangChain pipelines, and any other MCP-compatible client — can discover
datasources, submit SQL queries against them, monitor the review workflow, and (for reviewers)
record approval decisions. Every action goes through the same review pipeline as the web UI;
agents cannot bypass AI analysis, multi-stage approvals, or column-level restrictions.

This document covers:

1. Issuing and revoking API keys.
2. Configuring an MCP client.
3. The full tool surface.
4. Limits, errors, and audit behaviour.

For the wire-level REST spec see [04-api-spec.md → API Keys](04-api-spec.md#api-keys-meapi-keys).
For Spring-side architecture see [05-backend.md → User API keys (security module)](05-backend.md#user-api-keys-security-module)
and [05-backend.md → MCP server (mcp module)](05-backend.md#mcp-server-mcp-module).

---

## 1. Issue an API key

1. Sign in to AccessFlow.
2. Open **Profile → API keys**.
3. Click **Create API key**, give it a short name (e.g. `claude-desktop`), and confirm.
4. **Copy the raw key now** — `af_kQ7…` — and store it somewhere safe. The plaintext is shown
   exactly once; AccessFlow only persists a SHA-256 hash, so neither admins nor the system can
   recover it later. If you lose it, revoke the key and create a new one.

Keys inherit the owning user's role and datasource permissions exactly. A reviewer's key can
review queries; an analyst's key cannot.

### Revocation

Click **Revoke** next to any key in the list. Revocation is immediate — clients using that key
get 401 on the next request. Idempotent: revoking an already-revoked key is a no-op.

### Expiry (optional)

A future release will accept an `expires_at` timestamp at creation. The DB column and filter
already honour it; the UI control is tracked under [FE-09](https://github.com/bablsoft/accessflow/issues/80).

---

## 2. Point an MCP client at AccessFlow

The server runs at `POST <accessflow base url>/mcp` using the stateless Streamable HTTP
transport from Spring AI 2.0. The same auth header works for `/mcp/**` and any REST endpoint:

```
X-API-Key: af_kQ7abcdeXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

(or, equivalently: `Authorization: ApiKey af_…`).

### Claude Desktop

In your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "accessflow": {
      "type": "http",
      "url": "https://accessflow.example.com/mcp",
      "headers": {
        "X-API-Key": "af_kQ7abcdeXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
      }
    }
  }
}
```

Restart Claude Desktop. The `accessflow` tools (listed below) appear under the MCP icon.

### Claude Code

```bash
claude mcp add accessflow --transport http \
  --url https://accessflow.example.com/mcp \
  --header "X-API-Key: af_kQ7…"
```

### Smoke test with curl

```bash
curl -s -X POST https://accessflow.example.com/mcp \
  -H "X-API-Key: af_…" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq .
```

You should see all nine tools listed.

---

## 3. Tools

### Read / discovery

| Tool | Args | Returns |
|------|------|---------|
| `list_datasources` | `page?`, `size?` | Paginated list of datasources the caller can query (`id`, `name`, `db_type`, `host`, `database_name`, `active`, `require_review_reads/writes`). Admins see all org datasources. |
| `get_datasource_schema` | `datasourceId` | `{ schemas: [{ name, tables: [{ name, columns: [{ name, type, nullable, primaryKey }] }] }] }`. Use this to discover what tables and columns exist before writing SQL. |
| `list_my_queries` | `status?`, `datasourceId?`, `queryType?`, `page?`, `size?` | Caller's own queries (newest first). `status` is forced to the caller's history regardless of args — admins still see all submitters' queries via the REST endpoint, not this tool. |
| `get_query_status` | `queryId` | Full detail: status, AI risk, review decisions, execution outcome. Submitter-or-admin only. |
| `get_query_result` | `queryId` | Rows + columns (as JSON strings) for an `EXECUTED` `SELECT`. Returns `invalid_state` if the query is the wrong type or not executed yet. |

### Workflow

| Tool | Args | Notes |
|------|------|-------|
| `submit_query` | `datasourceId`, `sql`, `justification?` | Submits a SQL query for review. Returns `{ queryRequestId, status: "PENDING_AI" }`. The query proceeds through AI analysis and the datasource's configured review workflow. Permission and parse errors come back as structured MCP errors. |
| `cancel_query` | `queryId` | Cancels a `PENDING_AI` or `PENDING_REVIEW` query. Submitter only. |

### Reviewer-only

| Tool | Args | Notes |
|------|------|-------|
| `list_pending_reviews` | `page?`, `size?` | Queries at a stage the caller is eligible to approve. Excludes the caller's own submissions. `permission_denied` for non-reviewers. |
| `review_query` | `queryId`, `decision`, `comment?` | `decision` ∈ `APPROVED` / `REJECTED` / `REQUESTED_CHANGES`. Self-approval is blocked by the service layer — attempting it returns `permission_denied`. `comment` is capped at 4000 chars. |

---

## 4. Limits, errors, and audit

- **Pagination:** all `*_page` tools default to `page=0, size=20`, capped at `size=100`.
- **Authentication:** every MCP call requires a valid, non-revoked, unexpired API key (or a
  JWT). 401 if missing/invalid; the connection itself is not pre-authenticated since the
  transport is stateless.
- **Authorization:** all guards run inside the underlying service. Specifically:
  - `submit_query` enforces `canRead` / `canWrite` / `canDdl` on the datasource permission.
  - `get_query_result` requires `SELECT` + `EXECUTED`.
  - `review_query` enforces stage eligibility + the "no self-approval" rule.
- **Errors:** tools return a structured `{ code, message }` rather than raw exceptions. Codes:
  - `permission_denied` — caller is not allowed.
  - `not_found` — unknown id, or the resource is in a different org.
  - `invalid_state` — wrong status, wrong query type, etc.
  - `validation_failed` — SQL parse or argument validation.
- **Audit:** MCP-driven submissions and review decisions hit the same audit log entries as the
  web UI — the audit row records the user id, the IP/UA of the MCP request, and the action
  (e.g. `QUERY_SUBMITTED`, `REVIEW_APPROVED`). No new audit action types are introduced.
- **Notifications:** the same notification fanout applies — review queue events, query-status
  changes, etc.
