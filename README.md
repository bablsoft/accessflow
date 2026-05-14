# AccessFlow

> Open-source database access governance platform — a SQL proxy that puts review, approval, and audit between your team and production data.

[![Backend CI](https://github.com/bablsoft/accessflow/actions/workflows/ci.yml/badge.svg)](https://github.com/bablsoft/accessflow/actions/workflows/ci.yml)
[![Frontend CI](https://github.com/bablsoft/accessflow/actions/workflows/frontend-ci.yml/badge.svg)](https://github.com/bablsoft/accessflow/actions/workflows/frontend-ci.yml)
![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)

AccessFlow sits as a full SQL proxy in front of your relational databases — PostgreSQL, MySQL, MariaDB, Oracle, and Microsoft SQL Server are supported out of the box, and any other JDBC-compatible engine can be added by uploading its driver JAR. Every query a user submits is parsed, classified, optionally analyzed by AI, and routed through a configurable human-approval workflow before it ever reaches live data. Every request, decision, and execution is captured in a tamper-evident metadata audit log. Authentication is JWT (RS256) with optional SAML 2.0 SSO and OAuth 2.0 / OIDC sign-in (built-in templates for Google, GitHub, Microsoft, and GitLab). AccessFlow ships as a single open-source product under Apache 2.0 and is designed to run entirely inside your own infrastructure.

---

## Why AccessFlow

Most teams pick one of two extremes for database access:

- **Shared production credentials** — anyone with the password can run `DELETE`. Fast, but a single mistake is unbounded and there is no record of who did what.
- **Ticket-driven DBA access** — every change goes through a manual DBA queue. Safe, but slow enough that engineers route around it.

AccessFlow provides the missing middle: governed, self-service access where every query is reviewable, every approval is traceable, and AI catches the obvious problems before a human ever sees the request.

---

## Features

- **Proxy-first execution** — no user ever holds production credentials; the proxy holds them encrypted and opens connections only after approval. Single SQL statements run with autocommit; multi-statement INSERT/UPDATE/DELETE batches wrapped in `BEGIN; … COMMIT;` execute atomically inside one JDBC transaction (mixed SELECT/DML batches are rejected at parse time).
- **Configurable review workflows** — per-datasource review plans, multi-stage sequential approval chains, optional auto-approve for reads, approval timeouts with auto-reject.
- **AI query analysis** — pluggable adapters for OpenAI, Anthropic Claude, and self-hosted Ollama; risk scoring (0–100), missing-index detection, anti-pattern hints. Per-organization configuration via the admin UI.
- **Built-in SQL editor** — CodeMirror 6 with dialect-aware highlighting, schema autocomplete from live introspection, SQL formatter, and inline AI hint markers.
- **Tamper-evident audit log** — INSERT-only table chained with HMAC-SHA256; INSERT-only DB grants make after-the-fact rewrites detectable.
- **Real-time updates** — single WebSocket at `/ws` fans review-queue, status, and AI-analysis events to connected clients.
- **Notifications** — Email (SMTP), Slack (Incoming Webhooks), and HMAC-signed outbound webhooks with retry policy.
- **Identity & SSO** — JWT access tokens (15 min) + HttpOnly refresh cookies, optional SAML 2.0 SSO, OAuth 2.0 / OIDC sign-in with built-in templates for Google, GitHub, Microsoft, and GitLab (additional providers via DB-driven `oauth2_config` rows), password reset and user-invitation flows.
- **MCP server** — built-in Spring AI MCP server exposes a stateless tool surface so external AI agents can submit queries through the same review pipeline.
- **Deploy anywhere** — `docker compose up` for local and small environments; Helm chart for Kubernetes production.

---

## Architecture (at a glance)

AccessFlow is a single Spring Boot 4 application organized as Spring Modulith modules — six logical subsystems share one process, one Postgres, and one Redis but communicate strictly through events and exposed API packages:

- **Proxy** — parses, validates, and executes SQL against customer databases via per-datasource HikariCP pools.
- **Workflow** — review-plan state machine, approval chains, scheduled timeout auto-reject.
- **AI Analyzer** — Spring AI–backed adapters resolved per organization from the `ai_config` row.
- **Notifications** — async dispatcher fanning events to Email / Slack / webhooks.
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
| Target databases | PostgreSQL, MySQL, MariaDB, Oracle, Microsoft SQL Server (bundled drivers) — plus any JDBC-compatible engine via an admin-uploaded custom driver JAR |
| Frontend | React 19, Vite 8, TypeScript 6, Ant Design 6, CodeMirror 6 |
| Server state | TanStack Query 5 |
| Client state | Zustand 5 |
| Cache & locks | Redis 8 (JWT refresh-token revocation, ShedLock locks for `@Scheduled` jobs) |
| AI backends | OpenAI, Anthropic, Ollama (admin-configurable per organization) |
| Auth | JWT RS256 + optional SAML 2.0 SSO and OAuth 2.0 / OIDC (Google, GitHub, Microsoft, GitLab built in) |
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
│   │   ├── ai/               # Spring AI adapters (OpenAI / Anthropic / Ollama)
│   │   ├── security/         # JWT, Spring Security filters, SAML 2.0 SSO
│   │   ├── notifications/    # Email / Slack / Webhook dispatchers
│   │   ├── audit/            # INSERT-only, HMAC-chained audit log
│   │   └── mcp/              # Stateless MCP server for AI agents
│   └── pom.xml
├── frontend/         # React 19 + Vite + TypeScript SPA (Ant Design 6, TanStack Query, Zustand)
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
| [`docs/08-notifications.md`](https://github.com/bablsoft/accessflow/blob/main/docs/08-notifications.md) | Event types, Email / Slack / Webhook config, signed payload schema |
| [`docs/09-deployment.md`](https://github.com/bablsoft/accessflow/blob/main/docs/09-deployment.md) | Docker Compose, Helm, environment-variable reference |
| [`docs/11-development.md`](https://github.com/bablsoft/accessflow/blob/main/docs/11-development.md) | Local setup, testing strategy, coding standards, Git workflow |
| [`docs/12-roadmap.md`](https://github.com/bablsoft/accessflow/blob/main/docs/12-roadmap.md) | v1.0 → v2.x milestone scope |
| [`docs/13-mcp.md`](https://github.com/bablsoft/accessflow/blob/main/docs/13-mcp.md) | MCP server, user API keys, exposed tools |

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

See [`docs/11-development.md`](https://github.com/bablsoft/accessflow/blob/main/docs/11-development.md) for the full testing strategy and `CLAUDE.md` for the per-class coverage-parity rule.

---

## Contributing

- **Branches** off `main`: `feature/AF-<n>-<kebab-summary>`, `fix/AF-<n>-<kebab-summary>`, `hotfix/AF-<n>-<kebab-summary>`. Frontend-only work uses the `FE-<n>` prefix.
- **Commits** use imperative mood, ≤ 72 characters, prefixed by issue: `feat(AF-58): auto-reject queries past approval_timeout_hours`.
- **PRs** require green CI (backend + frontend), at least one approval, and a description that links the issue.
- **Before writing code**, read [`CLAUDE.md`](https://github.com/bablsoft/accessflow/blob/main/CLAUDE.md) end-to-end — it is the authoritative rulebook for module boundaries, validation parity, i18n, scheduled-job locking, and the coverage gates. The human-facing companion is [`docs/11-development.md`](https://github.com/bablsoft/accessflow/blob/main/docs/11-development.md).
- **Update documentation in the same change** — `docs/*.md`, this README, and the env-var table in `CLAUDE.md` must stay in sync with what the code actually does.

---

## Status

AccessFlow is pre-v1.0; expect APIs and migrations to evolve. The current scope and target dates are tracked in [`docs/12-roadmap.md`](https://github.com/bablsoft/accessflow/blob/main/docs/12-roadmap.md).

---

## License

Apache License 2.0.
