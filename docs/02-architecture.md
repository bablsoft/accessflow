# 02 — System Architecture

## High-Level Architecture

AccessFlow is composed of six primary subsystems — Proxy Engine, Workflow, AI Analyzer, Notifications, Audit, and Realtime — communicating via REST/WebSocket internally. The Proxy Engine is the **sole path** to production databases — no direct database credentials are ever exposed to users. The Realtime subsystem fans domain events out to connected frontend clients over a single WebSocket at `/ws`.

```
┌─────────────────────────────────────────────────────────────────┐
│                        USER BROWSER                             │
│           React + Vite + Ant Design  (Frontend SPA)             │
└─────────────────────────┬───────────────────────────────────────┘
                          │  HTTPS REST + WebSocket
┌─────────────────────────▼───────────────────────────────────────┐
│                     API GATEWAY LAYER                           │
│         Spring Boot 3  /  JWT Auth  /  Rate Limiting            │
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
| **AI Query Analyzer Service** | Wraps configurable AI backends (OpenAI, Anthropic, or local Ollama). Provides risk scoring, query analysis, index hints, and syntax suggestions. |
| **Admin & Audit Service** | Datasource CRUD, user/role management, policy configuration, audit log queries, notification channel setup. |
| **Notification Dispatcher** | Fanout service sending review events to Email, Slack, and configurable webhooks asynchronously. |

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
| Auth | JWT (RS256) with refresh token rotation + optional SAML 2.0 SSO and OAuth 2.0 / OIDC sign-in (built-in templates for Google, GitHub, Microsoft, and GitLab; additional providers via DB-driven `oauth2_config` rows — see [07-security.md](07-security.md)) |
| Containerization | Docker, Docker Compose 2.x |
| Kubernetes | Helm 3 chart with ConfigMap/Secret templating |
| Cache / Locks | Redis (JWT refresh-token revocation, ShedLock distributed locks for `@Scheduled` jobs) |
| Background scheduling | Spring `@Scheduled` + ShedLock-on-Redis — safe for horizontally-scaled deployments (only one node runs each tick). See [05-backend.md → Scheduled jobs and clustering](05-backend.md#scheduled-jobs-and-clustering). |
| AI Backends | OpenAI API, Anthropic Claude API, Ollama (self-hosted) — admin configurable |

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
