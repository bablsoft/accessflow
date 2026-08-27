# 02 — System Architecture

## High-Level Architecture

AccessFlow is composed of seven primary subsystems — Proxy Engine, Workflow, AI Analyzer, Notifications, Audit, Realtime, and Deployment Governance — communicating via REST/WebSocket internally. The Proxy Engine is the **sole path** to production databases — no direct database credentials are ever exposed to users. The Realtime subsystem fans domain events out to connected frontend clients over a single WebSocket at `/ws`.

```
┌─────────────────────────────────────────────────────────────────┐
│                        USER BROWSER                             │
│           React + Vite + Ant Design  (Frontend SPA)             │
└─────────────────────────┬───────────────────────────────────────┘
                          │  HTTPS REST + WebSocket
┌─────────────────────────▼───────────────────────────────────────┐
│                     API GATEWAY LAYER                           │
│         Spring Boot 4  /  JWT Auth  /  Rate Limiting            │
└──┬────────────┬───────────────┬───────────────────┬────────────┘
   │            │               │                   │
┌──▼──┐    ┌────▼────┐    ┌─────▼──────┐    ┌──────▼──────┐
│Query│    │ Review  │    │  AI Query  │    │  Admin &    │
│Proxy│    │Workflow │    │  Analyzer  │    │  Audit Svc  │
│ Svc │    │   Svc   │    │    Svc     │    │             │
└──┬──┘    └────┬────┘    └─────┬──────┘    └──────┬──────┘
   │            │               │                   │
┌──▼────────────▼───────────────▼───────────────────▼──────────┐
│              PostgreSQL (AccessFlow Internal DB)              │
└───────────────────────────────────────────────────────────────┘
   │
┌──▼──────────────────────────────────────────────────────────┐
│              CUSTOMER DATABASES (Proxied)                   │
│   PostgreSQL / MySQL / MariaDB / Oracle / MS SQL Server     │
│   + any JDBC engine via admin-uploaded custom driver JAR    │
└─────────────────────────────────────────────────────────────┘
```

---

## Service Descriptions

| Service | Responsibility |
|---------|---------------|
| **Query Proxy Service** | Core engine that intercepts SQL, validates against policies, routes to approval workflow or executes directly, streams results back to the user. |
| **Review Workflow Service** | Manages approval chains: creates review requests, notifies reviewers, records decisions, triggers execution on approval. Implements a state machine for multi-stage approvals. |
| **AI Query Analyzer Service** | Wraps configurable AI backends (OpenAI, Anthropic, local Ollama, any OpenAI-compatible endpoint, or Hugging Face — hosted router or local TGI). Provides risk scoring, query analysis, index hints, and syntax suggestions. |
| **Admin & Audit Service** | Datasource CRUD, user/role management, policy configuration, audit log queries, notification channel setup. |
| **Notification Dispatcher** | Fanout service sending review events to Email, Slack, and configurable webhooks asynchronously. |
| **Deployment Governance Service** | Gates CI/CD deployments (epic AF-682): accepts API-key-authenticated pipeline triggers, runs them through AI release-risk analysis, routing and human review, and answers a fail-closed **deployment gate** the pipeline blocks on. Also owns freeze windows, break-glass deploys and post-deploy outcome reporting. See [18-deployment-governance.md](18-deployment-governance.md). |

---

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Backend Runtime | Java 21 (LTS) with Virtual Threads (Project Loom) |
| Backend Framework | Spring Boot 3.3.x, Spring Security, Spring Data JPA |
| Database (internal) | PostgreSQL 15+ (AccessFlow metadata, audit log, user data) |
| ORM / Migrations | Hibernate 6, Flyway for schema migrations |
| Frontend Framework | React 18, Vite 5, TypeScript |
| UI Component Library | Ant Design 5.x |
| SQL Editor | CodeMirror 6 with SQL language plugin |
| Auth | JWT (RS256) with refresh token rotation + optional SAML 2.0 SSO and OAuth 2.0 / OIDC sign-in (built-in templates for Google, GitHub, Microsoft, and GitLab; additional providers via DB-driven `oauth2_config` rows — see [07-security.md](07-security.md)). User/group lifecycle can be IdP-driven over SCIM 2.0 (`/scim/v2`, per-org bearer tokens, #621). |
| Containerization | Docker, Docker Compose 2.x |
| Kubernetes | Helm 3 chart with ConfigMap/Secret templating |
| Cache / Locks | Redis (JWT refresh-token revocation, ShedLock distributed locks for `@Scheduled` jobs) |
| Background scheduling | Spring `@Scheduled` + ShedLock-on-Redis — safe for horizontally-scaled deployments (only one node runs each tick). See [05-backend.md → Scheduled jobs and clustering](05-backend.md#scheduled-jobs-and-clustering). |
| AI Backends | OpenAI API, Anthropic Claude API, Ollama (self-hosted), any OpenAI-compatible endpoint, Hugging Face (Inference Providers router or local TGI) — admin configurable |

---

## Request Flow Summary

1. User opens SQL editor in browser, selects a datasource, writes SQL.
2. Frontend sends `POST /api/v1/queries` to the Spring Boot API.
3. API validates JWT, checks the user has a permission record for the datasource.
4. Query Proxy Service classifies the SQL type (SELECT / DML / DDL). Multi-statement input is rejected, except for `BEGIN; … COMMIT;` blocks wrapping homogeneous INSERT/UPDATE/DELETE batches — those are accepted, recorded with `transactional=true`, and executed atomically.
5. Review plan is looked up for the datasource → determines if AI review and/or human approval required.
6. If AI review enabled → AI Analyzer Service invoked asynchronously; status becomes `PENDING_AI`.
7. On AI completion → if human approval required, status becomes `PENDING_REVIEW`; reviewers notified.
8. Reviewer approves via UI (or Slack/webhook) → status becomes `APPROVED`.
9. Proxy opens JDBC connection to customer database, executes SQL, captures metadata.
10. Audit log entry written. WebSocket event pushed to submitter. Status becomes `EXECUTED`.

### Deployment request flow (epic AF-682)

Deployments follow the same machinery with a machine at both ends instead of a browser:

1. A CI job calls `POST /api/v1/deployment-requests` with an AccessFlow **API key**, naming the pipeline, environment, version and its own CI run id. The run id makes the call idempotent — a retry returns the existing request rather than opening a second review.
2. The request is authorized by a per-pipeline `can_trigger` grant (not a functional permission), then analyzed by the AI release-risk analyzer; status becomes `PENDING_AI`.
3. Routing policies may auto-approve, auto-reject or change the required approval count; otherwise the environment's policy decides and status becomes `PENDING_REVIEW`.
4. Reviewers approve in the UI. The submitter — including the API key's owning user — can never approve their own deployment.
5. The job polls `GET /api/v1/deployment-gate` until `releasable: true`. The gate is **fail-closed**: anything other than an `APPROVED`, unfrozen, due request answers `false`.
6. The job calls `confirm-execution` (re-checked against freeze windows at that instant, status becomes `EXECUTED`), runs the deploy, then reports `SUCCEEDED` / `FAILED` / `ROLLED_BACK` back to AccessFlow. A rollback opens a mandatory follow-up review.

---

## Multi-Tenancy

AccessFlow is multi-tenant: a single deployment hosts one or more **organizations**, and every
domain entity (users, datasources, queries, review plans, audit rows, …) is scoped by
`organization_id`. Tenants share one process, one Postgres, and one Redis, but never see each
other's data.

- **Per-org scoping derived from the JWT.** Every tenant-scoped request reads its
  `organizationId` from the authenticated principal — never from the request body or path — so a
  user can only ever reach their own org's resources. This is the primary isolation boundary and it
  is enforced server-side on every endpoint.
- **Platform-admin management plane.** A super-admin (the orthogonal `users.platform_admin` flag,
  surfaced as the `PLATFORM_ADMIN` Spring Security authority — see
  [07-security.md](07-security.md#platform-admin-super-admin--platform_admin-authority-af-456))
  manages organizations across the cluster through `/api/v1/platform/organizations`: list, create,
  update name + quotas, and disable / enable tenants. These are the only endpoints that take a
  foreign org id by path.
- **Quota enforcement points.** Each org may carry caps (`max_datasources`, `max_users`,
  `max_queries_per_day`; NULL / 0 = unlimited). They are enforced at the service layer where the
  resource is created: datasource creation, user creation + invitation issuance, and query
  submission (a rolling trailing-24h count). A breach is rejected with `409 QUOTA_EXCEEDED`.
- **Disabled-org kill-switch.** Setting `organizations.disabled` blocks the tenant at both
  authentication (login / refresh / SSO exchange) and request time — the JWT and API-key auth
  filters do a lightweight per-request org-status lookup with no caching, so the block takes effect
  immediately.

See [03-data-model.md → organizations](03-data-model.md#organizations) for the schema and
[07-security.md → Multi-tenant isolation](07-security.md#multi-tenant-isolation-af-456) for the full
security posture.
