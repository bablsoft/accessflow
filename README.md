<p align="center">
  <img src="https://raw.githubusercontent.com/bablsoft/accessflow/main/website/favicon.svg" alt="AccessFlow logo" width="96" height="96">
</p>

<h1 align="center">AccessFlow</h1>

<p align="center">
  <strong>Open-source database access governance platform</strong><br>
  A SQL proxy that puts review, approval, and audit between your team and production data.
</p>

<p align="center">
  <a href="https://github.com/bablsoft/accessflow/actions/workflows/ci.yml"><img src="https://github.com/bablsoft/accessflow/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License: Apache 2.0">
  <a href="https://accessflow.bablsoft.com/"><img src="https://img.shields.io/badge/Live%20Demo-accessflow.bablsoft.com-1f6feb?logo=githubpages&logoColor=white" alt="Live site"></a>
</p>

<p align="center">
  <a href="https://accessflow.bablsoft.com/">Website</a> ·
  <a href="https://accessflow.bablsoft.com/docs/">Live Docs</a> ·
  <a href="#quick-start">Quick Start</a> ·
  <a href="docs/">Design Docs</a>
</p>

AccessFlow sits as a full query proxy in front of your databases — the relational engines PostgreSQL, MySQL, MariaDB, Oracle, and Microsoft SQL Server are supported out of the box via a declarative **connector catalog** (additional engines such as ClickHouse install with one click), any other JDBC-compatible engine can be added by uploading its driver JAR, the NoSQL document engines **MongoDB** and **Couchbase** (SQL++), the NoSQL key-value engine **Redis**, the NoSQL wide-column engines **Apache Cassandra** (CQL) and **ScyllaDB** (CQL-compatible), and the NoSQL search engines **Elasticsearch** and **OpenSearch** install the same way through on-demand native engine plugins. The catalog separates the **SQL** (relational) family from the **NoSQL** umbrella of native engine-managed connectors. Every query a user submits — SQL, a MongoDB shell / JSON command, a Couchbase SQL++ statement, a Redis command, a Cassandra/ScyllaDB CQL statement, or an Elasticsearch/OpenSearch query — is parsed, classified, optionally analyzed by AI, and routed through a configurable human-approval workflow before it ever reaches live data. Every request, decision, and execution is captured in a tamper-evident metadata audit log. Authentication is JWT (RS256) with optional SAML 2.0 SSO and OAuth 2.0 / OIDC sign-in (built-in templates for Google, GitHub, GitHub Enterprise Server, Microsoft, GitLab, and self-managed GitLab). AccessFlow ships as a single open-source product under Apache 2.0 and is designed to run entirely inside your own infrastructure.

---

## Why AccessFlow

Most teams pick one of two extremes for database access:

- **Shared production credentials** — anyone with the password can run `DELETE`. Fast, but a single mistake is unbounded and there is no record of who did what.
- **Ticket-driven DBA access** — every change goes through a manual DBA queue. Safe, but slow enough that engineers route around it.

AccessFlow provides the missing middle: governed, self-service access where every query is reviewable, every approval is traceable, and AI catches the obvious problems before a human ever sees the request.

---

## See it in action

A glance at the day-to-day flows engineers and approvers actually use.

![SQL editor](https://raw.githubusercontent.com/bablsoft/accessflow/main/website/images/docs/editor-light.png)

*Submit a query — CodeMirror 6 with dialect-aware highlighting, live schema autocomplete, and an inline review-plan preview that shows exactly which approvals the submission will trigger.*

![Review queue](https://raw.githubusercontent.com/bablsoft/accessflow/main/website/images/docs/reviews-queue-light.png)

*Approve or reject pending writes from one place — the queue is scoped to queries assigned to you, with risk score, query type, and submitter at a glance. A user can never approve their own query.*

![Query history](https://raw.githubusercontent.com/bablsoft/accessflow/main/website/images/docs/queries-list-light.png)

*Searchable, filterable history of every query — by status, type, risk, datasource, submitter, or date range — with CSV export. Each row links to the full request, AI analysis, approval timeline, and result set.*

![Configure a governed datasource](https://raw.githubusercontent.com/bablsoft/accessflow/main/website/images/docs/datasources-create-light.png)

*Connect a database in the admin UI — credentials are AES-256-GCM encrypted at rest, the proxy holds them, and end users never see them.*

> **More walkthroughs** — Review plans, AI provider configuration, notification channels (Email, Slack, Discord, Telegram, Teams, PagerDuty, webhooks), OAuth 2.0 / OIDC sign-in, SAML 2.0 SSO, users & invitations, and system SMTP all have step-by-step screenshots on the [public documentation site](https://accessflow.bablsoft.com/docs/).

---

## Features

- **Proxy-first execution** — no user ever holds production credentials; the proxy holds them encrypted and opens connections only after approval. Single SQL statements run with autocommit; multi-statement INSERT/UPDATE/DELETE batches wrapped in `BEGIN; … COMMIT;` execute atomically inside one JDBC transaction (mixed SELECT/DML batches are rejected at parse time). Optional **read-replica routing**: attach a replica JDBC URL to a datasource and SELECT traffic is served from there, with automatic primary fall-back and an audit row on replica failure.
- **Configurable review workflows** — per-datasource review plans, multi-stage sequential approval chains, optional auto-approve for reads, approval timeouts with auto-reject. Reviewers can be scoped **per-datasource** (directly or via groups) so different teams see only the queues that belong to them.
- **Policy-as-code routing** — ordered, attribute-based routing policies decide a query's path after AI analysis and before reviewers see it: **auto-approve**, **auto-reject**, **require N approvals**, or **escalate**. Conditions match on query type, referenced tables (glob), AI risk level / score, requester role or group, time-of-day / day-of-week, WHERE / LIMIT presence, and the transactional flag — combined with AND / OR / NOT. First match by priority wins; on no match the query falls through to the datasource's review plan. Every automated decision is recorded in the audit log.
- **Just-in-time (JIT) access requests** — users self-request temporary, scoped datasource access (read/write/DDL for an ISO-8601 duration). Requests flow through the same approval engine, a time-boxed permission is granted on approval, and a clustered scheduler auto-revokes it on expiry (admins can also revoke early).
- **Dynamic data masking** — per-column masking policies (full, partial last-N, stable hash, email-preserving, format-preserving) with role / group / user **reveal** conditions evaluated per requester. Masking is applied at result-read time before results are serialized or stored, so unmasked values never persist; applied policy ids are recorded in the audit log. Extends the static `restricted_columns` masking.
- **Row-level security** — per-table row predicates the proxy injects into the parsed SQL so a scoped user only sees (SELECT) or affects (UPDATE/DELETE) the rows they are authorised for. Admins author a structured `column operator value` predicate where the value is a fixed literal or a `:user.*` variable (built-in id / email / role / groups, or an admin-set per-user attribute). Values are bound as JDBC parameters — never concatenated; predicates that can't be safely applied are rejected, never run unfiltered. Composes with column masking and the schema/table allow-list.
- **User groups** — bundle reviewers (and other users) into groups; groups can be assigned as datasource reviewers and auto-synced from SAML / OAuth2 IdP claims via per-IdP `group_mappings` (admin-managed memberships are preserved across logins).
- **AI query analysis** — pluggable adapters for OpenAI, Anthropic Claude, self-hosted Ollama, any OpenAI API–compatible backend (vLLM, LM Studio, Together, Groq, OpenRouter, …) via a custom endpoint, and Hugging Face (Inference Providers router or a local / self-hosted TGI server); risk scoring (0–100), missing-index detection, anti-pattern hints. Per-organization configuration via the admin UI, including an **editable analyzer prompt** per configuration (override the built-in template, or leave it blank to use the default); admin **AI analyses dashboard** charts average risk over time, top issue categories, and most active submitters.
- **Text-to-SQL** — opt-in per datasource. Users describe what they want in plain language ("order numbers for the last 5 days") and the AI drafts a schema-grounded SQL statement into the editor. Reuses the datasource's AI configuration and is grounded in its introspected schema (restricted columns are never referenced). The generated SQL is only a draft — it's still submitted through the normal pipeline, so AI risk analysis and human review always apply.
- **RAG knowledge base** — opt-in per AI configuration. Attach knowledge documents (data-governance policies, naming conventions, schema notes); AccessFlow embeds them with a dedicated embedding model and, at analysis / text-to-SQL time, retrieves the most relevant chunks and injects them into the prompt — so analysis reflects your house rules. Pluggable vector store: in-app **pgvector** (self-contained, auto-provisioned where the DB role permits — and optional: AccessFlow still starts if the extension is absent, with the in-app store disabled) or external **Qdrant**. Retrieval is best-effort and never blocks analysis.
- **Langfuse integration** — optional, per-organization. Send a trace of every AI analysis (input SQL, structured output, model, token usage, latency) to [Langfuse](https://langfuse.com) for LLM observability, and/or fetch analyzer prompts from Langfuse by name at runtime (prompt management) so you can iterate on prompts without redeploying. Best-effort and non-blocking — a Langfuse outage never affects query workflow.
- **Datasource health dashboard** — admin-only `/admin/datasource-health` surfaces per-datasource HikariCP pool utilisation plus a trailing 24h summary of query volume, p50/p95 execution latency, and error count, so operators can spot pool exhaustion, slow datasources, or volume spikes. Auto-refreshes every 30s.
- **Built-in SQL editor** — CodeMirror 6 with dialect-aware highlighting, schema autocomplete from live introspection, SQL formatter, and inline AI hint markers.
- **Query templates library** — save frequently used queries (private or team-visible) with tags and `:placeholder` substitution, then load them straight into the editor and share them across the team.
- **Query result diffing** — re-running the same query links the run to its previous execution and surfaces the delta in `rows_affected`, row count, and execution time, so reviewers can spot drift between repeated runs.
- **Tamper-evident audit log** — INSERT-only table chained with HMAC-SHA256; INSERT-only DB grants make after-the-fact rewrites detectable.
- **Real-time updates** — single WebSocket at `/ws` fans review-queue, status, and AI-analysis events to connected clients.
- **Notifications** — Email (SMTP), Slack, Discord, Telegram, Microsoft Teams, PagerDuty, and HMAC-signed outbound webhooks with retry policy.
- **Slack approve/reject** — a configured Slack app adds **Approve** / **Reject** buttons to review-request messages; the decision runs through the same self-approval and RBAC guards as the REST API (HMAC-verified Interactive Components).
- **Identity & SSO** — JWT access tokens (15 min) + HttpOnly refresh cookies, optional SAML 2.0 SSO, OAuth 2.0 / OIDC sign-in with built-in templates for Google, GitHub, GitHub Enterprise Server, Microsoft, GitLab, and self-managed GitLab plus a generic `OIDC` provider for other IdPs (Keycloak, Auth0, Okta, Authentik, Zitadel), password reset and user-invitation flows.
- **MCP server** — built-in Spring AI MCP server exposes a stateless tool surface so external AI agents can submit queries through the same review pipeline.
- **Connector catalog** — supported databases are described declaratively in a repo-root `connectors/` folder (one manifest + logo per connector), not hardcoded. Each connector carries a `category` (`RELATIONAL` for the SQL family; `DOCUMENT`, `KEY_VALUE`, `WIDE_COLUMN`, `SEARCH`, or `GRAPH` for the NoSQL umbrella) and the marketplace groups them into SQL and NoSQL sections accordingly. Admins browse the **Connectors** marketplace and install a relational database's JDBC driver with one click (downloaded + SHA-256-verified + cached); engines beyond the built-in five (e.g. ClickHouse) install the same way. **MongoDB**, **Couchbase**, **Redis**, **Apache Cassandra**, **ScyllaDB**, **Elasticsearch**, and **OpenSearch** are native (non-JDBC) engine-managed connectors whose engines ship as on-demand **plugin JARs** (`engines/mongodb/`, `engines/couchbase/`, `engines/redis/`, `engines/cassandra/` — the Cassandra plugin serves both Cassandra and ScyllaDB; `engines/elasticsearch/` — the Elasticsearch plugin serves both Elasticsearch and OpenSearch), downloaded + verified through the same catalog pipeline. The catalog ships in the image and is also published as a release artifact.
- **MongoDB (NoSQL)** — a first-class document connector. Users write MongoDB queries in either the familiar shell form (`db.users.find({ age: { $gt: 21 } })`) or a JSON command document, selectable in the editor; results render in both a JSON document view and a flattened table view. The same governance applies — AI risk analysis, human approval, row-level security (`$match` injection), and field masking.
- **Couchbase (NoSQL)** — a first-class document connector speaking **SQL++ (N1QL)**, shipped as the second on-demand engine plugin (`engines/couchbase/`). SQL++ statements get SQL-style highlighting and formatting in the editor, classify onto the same approval workflow, and run with row-level security ANDed into the WHERE clause (named parameters, fail-closed on unrewritable shapes) plus field masking; dangerous constructs (`CURL()`, JavaScript UDFs, `system:*` keyspaces) are rejected up front.
- **Redis (NoSQL key-value)** — a first-class key-value connector, shipped as the third on-demand engine plugin (`engines/redis/`, native Jedis driver). Users submit redis-cli commands (`GET user:42`, `HGETALL session:abc`, `SCAN 0 MATCH orders:* COUNT 100`) classified onto the same approval workflow; field masking applies to returned hash fields / values. Row-security policies on a Redis datasource fail closed (row predicates have no key-value meaning), and server-side scripting / blast-radius commands (`EVAL`, `CONFIG`, `FLUSHALL`, `SHUTDOWN`, …) are rejected at submission.
- **Cassandra & ScyllaDB (NoSQL wide-column)** — first-class wide-column connectors speaking **CQL**, shipped as the fourth on-demand engine plugin (`engines/cassandra/`, native DataStax Java driver); the same plugin JAR serves both **Apache Cassandra** and the CQL-compatible **ScyllaDB**. CQL statements classify onto the same approval workflow (SELECT / INSERT / UPDATE / DELETE plus `CREATE`/`ALTER`/`DROP` and `TRUNCATE`); row-level security is key-aware and fails closed — predicates splice into the WHERE clause only on partition/clustering key columns with CQL-filterable operators (`=, IN, <, <=, >, >=`), and a non-key column, `!=`/`NOT IN`, or INSERT into a policied table is rejected rather than injecting `ALLOW FILTERING`, plus field masking on returned columns. Server-side code (`BEGIN … BATCH`, `CREATE`/`DROP FUNCTION`/`AGGREGATE`) is rejected up front. Each datasource sets its load-balancing `local_datacenter`.
- **Elasticsearch & OpenSearch (NoSQL search)** — first-class search connectors, shipped as the fifth on-demand engine plugin (`engines/elasticsearch/`, low-level REST client); the same plugin JAR serves both **Elasticsearch** and the wire-compatible **OpenSearch**. Users write a JSON query envelope (`{"search":"logs-*","query":{…}}`, plus `count`, `get`/`mget`, `index`/`bulk`, `update_by_query`/`delete_by_query`, and index management) classified onto the same approval workflow; row-level security injects `bool.filter` clauses on keyword fields (fail-closed on writes into a policied index), and field masking applies recursively to `_source` fields including nested dot-paths. Server-side scripting (`script`, `runtime_mappings`, Painless) and cluster/system-index APIs are rejected up front. Each datasource authenticates with basic auth or an API key.
- **Deploy anywhere** — `docker compose up` for local and small environments; Helm chart for Kubernetes production.

---

## Architecture (at a glance)

AccessFlow is a single Spring Boot 4 application organized as Spring Modulith modules — six logical subsystems share one process, one Postgres, and one Redis but communicate strictly through events and exposed API packages:

- **Proxy** — parses, validates, and executes queries against customer databases: SQL via per-datasource HikariCP pools, MongoDB / Couchbase / Redis / Cassandra / ScyllaDB / Elasticsearch / OpenSearch via on-demand engine plugins (`engines/mongodb/`, `engines/couchbase/`, `engines/redis/`, `engines/cassandra/`, `engines/elasticsearch/` — per-datasource native clients behind the `core.api.QueryEngine` SPI).
- **Workflow** — review-plan state machine, approval chains, scheduled timeout auto-reject.
- **AI Analyzer** — Spring AI–backed adapters resolved per organization from the `ai_config` row.
- **Notifications** — async dispatcher fanning events to Email, Slack, Discord, Telegram, Microsoft Teams, PagerDuty, and outbound webhooks.
- **Audit** — INSERT-only, HMAC-chained record of every domain event.
- **Realtime** — WebSocket fan-out for the React SPA.

For the full request flow, technology stack table, and component-level diagrams, see [`docs/02-architecture.md`](https://github.com/bablsoft/accessflow/blob/main/docs/02-architecture.md).

---

## Tech Stack

| Layer | Stack |
|-------|-------|
| Backend runtime | Java 25, virtual threads |
| Backend framework | Spring Boot 4, Spring Modulith 2, Spring Security, Spring Data JPA, Spring AI 2.0 |
| Internal database | PostgreSQL 18 |
| Migrations | Flyway |
| Target databases | **SQL:** PostgreSQL, MySQL, MariaDB, Oracle, Microsoft SQL Server, ClickHouse via the declarative connector catalog (one-click driver install) — plus any JDBC-compatible engine via an admin-uploaded custom driver JAR. **NoSQL:** MongoDB, Couchbase, Redis, Apache Cassandra, ScyllaDB, Elasticsearch, and OpenSearch (native engine plugins, installed on demand) |
| Frontend | React 19, Vite 8, TypeScript 6, Ant Design 6, CodeMirror 6 |
| Server state | TanStack Query 5 |
| Client state | Zustand 5 |
| Cache & locks | Redis 8 (JWT refresh-token revocation, ShedLock locks for `@Scheduled` jobs) |
| AI backends | OpenAI, Anthropic, Ollama, any OpenAI-compatible endpoint, Hugging Face (Inference Providers router or local TGI) (admin-configurable per organization) |
| Auth | JWT RS256 + optional SAML 2.0 SSO and OAuth 2.0 / OIDC (Google, GitHub, GitHub Enterprise Server, Microsoft, GitLab, self-managed GitLab built in) |
| Deploy | Docker Compose, Helm 3 |

Library versions in `backend/pom.xml` and `frontend/package.json` are pinned to the latest stable at the time of merge; see `CLAUDE.md` for the dependency-bump rule.

---

## Quick Start

### Try it in Docker (zero-config demo)

The only prerequisite is Docker Desktop. From a fresh clone:

```bash
git clone https://github.com/bablsoft/accessflow.git
cd accessflow
docker compose up -d        # pulls Postgres + Redis + the released backend & frontend images
open http://localhost:5173  # the SPA detects the empty DB and walks you through /setup
```

The setup wizard creates the first organization and admin user — no `.env`, no key generation, no Maven, no npm.

> ⚠️ The root [`docker-compose.yml`](docker-compose.yml) embeds **demo-only** JWT and encryption keys so it works out of the box. Do not point it at real customer data; for anything beyond evaluation, follow the production-style configuration in [`docs/09-deployment.md`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md#production-style-configuration).

### Develop from source

For the hot-reload dev loop you'll also need:

- JDK 25 (e.g. `sdk install java 25-tem`)
- Node.js 20 LTS

The dev compose boots just the infrastructure (Postgres + Redis + a fake SMTP inbox at <http://localhost:1080>) so it can coexist with `./mvnw spring-boot:run` and `npm run dev`:

```bash
docker compose -f backend/docker-compose-dev.yml up -d   # Postgres + Redis + Mailcrab
```

Then in two more terminals:

```bash
cd backend && ./mvnw spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

The API comes up on `http://localhost:8080` and the SPA on `http://localhost:5173`. Required environment variables (`DB_PASSWORD`, `ENCRYPTION_KEY`, `JWT_PRIVATE_KEY`, …) are documented in [`docs/09-deployment.md`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md) and the env-var table in [`CLAUDE.md`](https://github.com/bablsoft/accessflow/blob/main/CLAUDE.md).

### Running released images

Tagged releases are published to GitHub Container Registry as multi-arch (`linux/amd64`, `linux/arm64`) images:

```bash
docker pull ghcr.io/bablsoft/accessflow-backend:1.2.3
docker pull ghcr.io/bablsoft/accessflow-frontend:1.2.3
# or :latest
```

The running backend exposes its version at `GET /actuator/info` (`build.version`); the frontend shows it under the brand mark in the sidebar. Maintainers cut a release by running the [`Release` workflow](https://github.com/bablsoft/accessflow/actions/workflows/release.yml) from the Actions tab with a semver `version` input — see [`docs/09-deployment.md → Releases`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md#releases) for what the pipeline does.

### Install on Kubernetes (Helm)

Each release also publishes a Helm chart to the repo at `https://bablsoft.github.io/accessflow`:

```bash
helm repo add accessflow https://bablsoft.github.io/accessflow
helm repo update

# Create the required secrets first — see charts/accessflow/README.md
helm install accessflow accessflow/accessflow \
  --namespace accessflow --create-namespace \
  --values my-values.yaml
```

The chart bundles optional `bitnami/postgresql` and `bitnami/redis` subcharts (toggle off
for production with `postgresql.enabled=false` / `redis.enabled=false`) and ships a single
Ingress that dispatches `/api`+`/ws` → backend, `/` → frontend.

For GitOps deployments, the chart can also seed the organization, first admin user, review
plans, AI configs, datasources, SAML, OAuth2 providers, notification channels, and system
SMTP from `values.yaml` (with sensitive fields routed through Kubernetes Secrets) — see
[`docs/09-deployment.md → Bootstrap configuration`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md#bootstrap-configuration).

Full reference: [`charts/accessflow/README.md`](https://github.com/bablsoft/accessflow/blob/main/charts/accessflow/README.md)
and [`docs/09-deployment.md`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md).

---

## Project Structure

```
accessflow/
├── backend/          # Spring Boot 4 application (single Maven module, Spring Modulith)
│   ├── src/main/java/com/bablsoft/accessflow/
│   │   ├── core/             # Domain entities, repositories, shared service contracts
│   │   ├── proxy/            # SQL proxy engine, JDBC connection management
│   │   ├── workflow/         # Review state machine, approval chains, scheduled jobs
│   │   ├── access/           # JIT time-bound access requests + grant-expiry job
│   │   ├── ai/               # Spring AI adapters (OpenAI / Anthropic / Ollama / Hugging Face)
│   │   ├── security/         # JWT, Spring Security filters, SAML 2.0 SSO
│   │   ├── notifications/    # Email / Slack / Webhook / Discord / Telegram / MS Teams / PagerDuty dispatchers
│   │   ├── audit/            # INSERT-only, HMAC-chained audit log
│   │   └── mcp/              # Stateless MCP server for AI agents
│   └── pom.xml
├── engines/          # On-demand engine plugins — engines/mongodb/, engines/couchbase/, engines/redis/, engines/cassandra/ (shaded QueryEngine SPI jars)
├── frontend/         # React 19 + Vite + TypeScript SPA (Ant Design 6, TanStack Query, Zustand)
├── connectors/       # Declarative connector catalog (one connector.json + logo per database)
├── e2e/              # Playwright end-to-end suite + docker-compose.e2e.yml (own npm project)
├── docs/             # Authoritative design documentation
├── website/          # Public marketing site (static HTML/CSS/JS, no build step)
├── docker-compose.yml          # Local infrastructure (Postgres + Redis)
├── CLAUDE.md         # Agent-facing rulebook (read before changing code)
└── README.md
```

---

## Documentation

| File | Description |
|------|-------------|
| [`docs/01-overview.md`](https://github.com/bablsoft/accessflow/blob/main/docs/01-overview.md) | Executive summary, problem statement, goals, non-goals |
| [`docs/02-architecture.md`](https://github.com/bablsoft/accessflow/blob/main/docs/02-architecture.md) | System architecture, service descriptions, technology stack |
| [`docs/03-data-model.md`](https://github.com/bablsoft/accessflow/blob/main/docs/03-data-model.md) | Entity schemas, columns, enums, indexes |
| [`docs/04-api-spec.md`](https://github.com/bablsoft/accessflow/blob/main/docs/04-api-spec.md) | REST endpoints, WebSocket events, payload examples |
| [`docs/05-backend.md`](https://github.com/bablsoft/accessflow/blob/main/docs/05-backend.md) | Modulith layout, proxy engine, workflow state machine, AI analyzer, scheduled jobs |
| [`docs/06-frontend.md`](https://github.com/bablsoft/accessflow/blob/main/docs/06-frontend.md) | Frontend structure, routing, state management, SQL editor |
| [`docs/07-security.md`](https://github.com/bablsoft/accessflow/blob/main/docs/07-security.md) | Auth, RBAC matrix, credential encryption, audit integrity |
| [`docs/08-notifications.md`](https://github.com/bablsoft/accessflow/blob/main/docs/08-notifications.md) | Event types; Email, Slack, Webhook, Discord, Telegram, Microsoft Teams, and PagerDuty channel config; signed payload schema |
| [`docs/09-deployment.md`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md) | Docker Compose, Helm, environment-variable reference |
| [`docs/11-development.md`](https://github.com/bablsoft/accessflow/blob/main/docs/11-development.md) | Local setup, testing strategy, coding standards, Git workflow |
| [`docs/12-roadmap.md`](https://github.com/bablsoft/accessflow/blob/main/docs/12-roadmap.md) | v1.0 → v2.x milestone scope |
| [`docs/13-mcp.md`](https://github.com/bablsoft/accessflow/blob/main/docs/13-mcp.md) | MCP server, user API keys, exposed tools |
| [`docs/14-connectors.md`](https://github.com/bablsoft/accessflow/blob/main/docs/14-connectors.md) | Declarative connector catalog — manifests, install lifecycle, release artifacts |
| [`docs/15-engine-sdk.md`](https://github.com/bablsoft/accessflow/blob/main/docs/15-engine-sdk.md) | Engine-plugin SDK — authoring guide for native (non-JDBC) engines |

---

## Testing & Quality Gates

Both halves of the codebase ship with strict coverage gates enforced by CI.

**Backend:**

```bash
cd backend
./mvnw verify -Pcoverage                        # full build + tests + JaCoCo coverage
./mvnw test -Dtest=ApplicationModulesTest       # Spring Modulith boundary check
```

JaCoCo enforces ≥ 90 % line coverage; the `ApiPackageDependencyTest` ArchUnit check refuses third-party imports inside any `<module>/api/` package; `ApplicationModulesTest` refuses cross-module reaches into `internal/` packages.

**Frontend:**

```bash
cd frontend
npm run lint
npm run typecheck
npm run test:coverage    # Vitest, ≥ 90 % line / ≥ 80 % branch on included modules
npm run build
```

**End-to-end:**

```bash
cd e2e
npm ci
npx playwright install --with-deps chromium
npm run stack:up         # builds backend + frontend, brings up Postgres + Redis, waits on healthchecks
npm test                 # Playwright auth-flow suite against http://localhost:5173
npm run stack:down
npm run test:setup       # first-run setup-wizard spec against a no-admin variant stack (ports 5174 / 8081)
```

See [`docs/11-development.md`](https://github.com/bablsoft/accessflow/blob/main/docs/11-development.md) for the full testing strategy and `CLAUDE.md` for the per-class coverage-parity rule.

---

## Contributing

- **Branches** off `main`: `feature/AF-<n>-<kebab-summary>`, `fix/AF-<n>-<kebab-summary>`, `hotfix/AF-<n>-<kebab-summary>`. Frontend-only work uses the `FE-<n>` prefix.
- **Commits** use imperative mood, ≤ 72 characters, prefixed by issue: `feat(AF-58): auto-reject queries past approval_timeout_hours`.
- **PRs** require green CI (backend + frontend), at least one approval, and a description that links the issue.
- **Before writing code**, read [`CLAUDE.md`](https://github.com/bablsoft/accessflow/blob/main/CLAUDE.md) end-to-end — it is the authoritative rulebook for module boundaries, validation parity, i18n, scheduled-job locking, and the coverage gates. The human-facing companion is [`docs/11-development.md`](https://github.com/bablsoft/accessflow/blob/main/docs/11-development.md).
- **Update documentation in the same change** — `docs/*.md`, this README, and the env-var table in `CLAUDE.md` must stay in sync with what the code actually does.

---

## License

Apache License 2.0.
