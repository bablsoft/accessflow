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
3. Click **Create API key**, give it a short name (e.g. `claude-desktop`), optionally set an
   expiry (see [Expiry (optional)](#expiry-optional) below), and confirm.
4. **Copy the raw key now** — `af_kQ7…` — and store it somewhere safe. The plaintext is shown
   exactly once; AccessFlow only persists a SHA-256 hash, so neither admins nor the system can
   recover it later. If you lose it, revoke the key and create a new one.

Keys inherit the owning user's role and datasource permissions exactly. A reviewer's key can
review queries; an analyst's key cannot.

### Revocation

Click **Revoke** next to any key in the list. Revocation is immediate — clients using that key
get 401 on the next request. Idempotent: revoking an already-revoked key is a no-op.

### Expiry (optional)

`POST /api/v1/me/api-keys` accepts an optional `expires_at` timestamp. Once it passes, the key stops
authenticating exactly as a revoked one does — 401 on the next request, on `/mcp/**` and on any REST
endpoint. Expiry is checked at every resolve, so nothing has to run for it to take effect. The
profile list shows the key's `expires_at` and marks the row **Expired** once that instant passes.

**Service accounts (#871).** A non-human agent should not borrow a person's key. An admin with
`SERVICE_ACCOUNT_MANAGE` creates a service account (`POST /api/v1/admin/service-accounts` —
`principal_type = SERVICE_ACCOUNT`, no interactive sign-in, default role `READONLY`) and issues,
rotates and revokes its keys on its behalf under `/api/v1/admin/service-accounts/{id}/api-keys`.
Rotation issues the replacement and lets the old key keep working for a grace window
(`ACCESSFLOW_SERVICEACCOUNTS_ROTATION_GRACE`, default 24 h), so a running agent is never cut off
mid-session. The account's `mcp_tool_allow_list` limits which of the tools below its key may
call: `NULL` allows every tool, `[]` none. It is enforced on every `tools/call` since #872 — see
[§4](#4-limits-errors-and-audit). Keys declared in bootstrap YAML cannot be revoked or rotated from
either surface — rotate the secret at the source and restart. Full contract:
[04-api-spec.md → Service Accounts](04-api-spec.md#service-accounts-adminservice-accounts-service_account_manage-871).

The **Create API key** form carries an optional **Expires** field, a date-and-time picker whose
past dates and hours are greyed out. Leave it empty and the key never expires, and revoking it is
then the only way to cut it off.

`expires_at` can also be set on the request body directly, or declaratively on a bootstrap service
account (`apiKeyExpiresAt`). Neither of those paths range-checks the value — only the picker does —
so an instant already in the past mints a key that is dead on arrival.

---

## 2. Point an MCP client at AccessFlow

The server runs at `POST <accessflow base url>/mcp` using the stateless Streamable HTTP
transport from Spring AI 2.0. The same auth header works for `/mcp/**` and any REST endpoint:

```
X-API-Key: af_kQ7abcdeXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
```

(or, equivalently: `Authorization: ApiKey af_…`).

A service account's key may add a second header naming the human the agent is acting **for**
(#874):

```
X-API-Key: af_kQ7abcdeXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
X-AccessFlow-On-Behalf-Of: alice@example.com      # or her user uuid
```

It is attribution, not authority: the agent keeps exactly its own permissions, the human must have
consented first (`POST /api/v1/me/service-account-delegations`, or an admin on their behalf under
`/admin/service-accounts/{id}/delegated-principals`), every submission and audit row is stamped with
the human, and the human can then no longer approve what the agent submitted for them. An unknown,
un-consenting or foreign name is a loud `403 ON_BEHALF_OF_NOT_PERMITTED` — never silently ignored.
Details under [Limits, errors, and audit](#4-limits-errors-and-audit).

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
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq .
```

You should see all twelve tools listed — for every key, including a service account whose
allow-list excludes most of them (see §4). The stateless transport answers `400` unless `Accept`
names both `application/json` and `text/event-stream`; real MCP clients send both.

---

## 3. Tools

### Read / discovery

| Tool | Args | Returns |
|------|------|---------|
| `list_datasources` | `page?`, `size?` | Paginated list of datasources the caller can query (`id`, `name`, `db_type`, `host`, `database_name`, `active`, `require_review_reads/writes`). Admins see all org datasources. |
| `get_datasource_schema` | `datasourceId` | `{ schemas: [{ name, tables: [{ name, columns: [{ name, type, nullable, primaryKey }] }] }] }`. Use this to discover what tables and columns exist before writing SQL. Non-admin callers see only the tables their grant's allow-list covers, without denied columns (#936). |
| `list_my_queries` | `status?`, `datasourceId?`, `queryType?`, `page?`, `size?` | Caller's own queries (newest first). `status` is forced to the caller's history regardless of args — admins still see all submitters' queries via the REST endpoint, not this tool. |
| `get_query_status` | `queryId` | Full detail: status, AI risk, review decisions, execution outcome. Submitter-or-admin only. |
| `get_query_result` | `queryId` | Rows + columns (as JSON strings) for an `EXECUTED` `SELECT`. Returns `invalid_state` if the query is the wrong type or not executed yet. |
| `validate_sql` | `datasourceId`, `sql` | Parse-only check against the datasource's engine — **no AI cost, no execution**. Returns `{ valid, queryType, referencedTables, hasWhereClause, hasLimitClause, unknownTables, schemaChecked, parseError }`. Parse errors come back as `valid: false` + `parseError` (data, not an MCP error). `unknownTables` are referenced tables absent from the schema you can see; `schemaChecked` is `false` when the database was unreachable (mismatch skipped). Use it to sanity-check a draft before `submit_query`. |
| `get_column_samples` | `datasourceId`, `schema?`, `table`, `limit?` | Bounded sample rows (default 50, max 200) with the **same row-level security and column masking** as a governed read — masked columns carry the masked value, never the raw one (`restricted` flags them). Requires read access to the table; `not_found` for an unknown or forbidden table. |
| `get_audit_log` | `action?`, `resourceType?`, `from?`, `to?`, `page?`, `size?` | The **caller's own** audit entries (newest first), scoped to the caller + their organisation — never another user's activity. `from`/`to` are ISO-8601 instants; `resourceType` accepts the enum name (`QUERY_REQUEST`) or db value (`query_request`); `action` is an `AuditAction`. |

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
  - `review_query` enforces stage eligibility + the "no self-approval" rule — which, since #874,
    also refuses the human a query was submitted *on behalf of*.
  - `get_column_samples` runs through the same governed read path as the schema explorer (AF-443):
    it applies the caller's row-level security predicates and column masks and enforces `canRead` +
    the schema/table allow-list, so a masked column never returns a raw value.
  - `get_audit_log` is always scoped to the caller (`actorId` is forced to the calling user) and the
    caller's organisation — it never returns another user's activity, even for an admin key.
  - `validate_sql` only parses; it never executes or runs AI analysis. It needs the datasource to be
    visible to the caller (to pick the engine dialect) and reuses the schema introspection the caller
    is already permitted to read for its best-effort mismatch check.
- **Tool allow-list (#872).** A service account's `mcp_tool_allow_list` (set from
  `/admin/service-accounts`, #871 — since #875 a checkbox list on the account's **MCP tools**
  tab, built from `GET /admin/service-accounts/mcp-tools`, which serves this enum's wire names)
  decides which tools its key may *invoke*. It is enforced at
  invocation by a decorator around every registered tool — `GuardedToolCallback` consults
  `ServiceAccountToolPolicyService` on the request thread before the tool body runs, so no tool
  (including one added later) can be reached past it. Decision order: a name outside the twelve-tool
  catalog is denied for everyone; a person (no service-account row) and a service account with a
  `NULL` list are allowed every tool; `[]` allows none; otherwise the tool must be listed. The
  usual permission checks then still run inside the tool — the allow-list can only *narrow* what
  the account's role and grants already permit, never widen it.
  **`tools/list` still advertises all twelve tools to every caller.** The stateless server's list
  handler cannot see who is asking, so the allow-list is an enforcement boundary, not a discovery
  filter — the MCP tools tab in the admin UI states the same: an agent limited to `["list_datasources", "validate_sql"]` still *sees* `submit_query`
  and, if it tries it, gets the structured `permission_denied` below without the service ever being
  invoked. The server `instructions` tell the model to report such a denial rather than retry it.
- **Rate limits (#873).** Every API-key-authenticated `POST /mcp` counts against the calling
  identity's per-minute / per-day cap — the service account's `rate_limit_per_minute` /
  `rate_limit_per_day` when set (`/admin/service-accounts`), otherwise the deployment defaults
  (`ACCESSFLOW_SERVICEACCOUNTS_RATE_LIMIT_REQUESTS_PER_MINUTE`, 120; `…_PER_DAY`, 0 = unlimited).
  Over the cap the *transport* answers `HTTP 429` with an RFC 9457 `ProblemDetail`
  (`error: SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED`, `limit`, `retryAfterSeconds`) and a `Retry-After`
  header — not a JSON-RPC error, because the filter runs before the MCP handler. Clients should
  back off for `Retry-After` seconds and resend the same request. The limiter fails open when
  Redis is unavailable, so a Redis blip never stalls an agent.
- **On-behalf-of (#874).** `X-AccessFlow-On-Behalf-Of` on `POST /mcp` is resolved by the same
  filter, before the JSON-RPC handler: the named human must be an active person of the agent's
  organisation holding a live delegated-principal grant for that service account, otherwise the
  transport answers `HTTP 403 ON_BEHALF_OF_NOT_PERMITTED` (`reason=not_permitted`; a JWT session
  sending it gets `reason=not_api_key`). It changes **nothing** about what the agent may do — the
  permission set stays the key owner's — it only records whom the agent acted for:
  `submit_query` stamps the query's `on_behalf_of_user_id`, and every audit row the request writes
  carries `on_behalf_of_user_id`, `api_key_id` and `service_account` in its metadata, inside the
  tamper-evident chain. Because `/mcp` multiplexes every tool through one endpoint, the decision
  tool is refused at invocation instead of at the transport: **`review_query` returns the structured
  `permission_denied` whenever a principal is present** — an agent may submit *for* a human, never
  vote *as* one.
- **Errors:** tools return a structured `{ code, message }` rather than raw exceptions. Codes:
  - `permission_denied` — caller is not allowed; also returned when the tool is outside the
    caller's allow-list (the message names the tool).
  - `not_found` — unknown id, or the resource is in a different org.
  - `invalid_state` — wrong status, wrong query type, etc.
  - `validation_failed` — SQL parse or argument validation.
- **Audit:** MCP-driven submissions and review decisions hit the same audit log entries as the
  web UI — `submit_query` writes `QUERY_SUBMITTED` itself (`metadata.channel = "mcp"`; no client
  IP / user agent, because the tool layer has no `RequestAuditContext` the way a controller does —
  fixed in #874, before which the MCP path wrote no submission row at all) and `review_query`
  records the decision row like the REST endpoint, likewise without IP / user agent. On an API-key request every row also carries the request provenance
  (`api_key_id`, `service_account`, `on_behalf_of_user_id` when named). No new audit action
  types are introduced.
- **Notifications:** the same notification fanout applies — review queue events, query-status
  changes, etc.
