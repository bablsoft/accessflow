# AccessFlow — Project Documentation Index

AccessFlow is an open-source, enterprise-ready **database access governance platform**. It acts as a full SQL proxy between users and relational databases (PostgreSQL, MySQL), enforcing configurable review and approval workflows before any query reaches live data.

## Document Index

| File | Description |
|------|-------------|
| [01-overview.md](./01-overview.md) | Executive summary, problem statement, goals, non-goals |
| [02-architecture.md](./02-architecture.md) | System architecture, service descriptions, technology stack |
| [03-data-model.md](./03-data-model.md) | All database entity schemas with column definitions |
| [04-api-spec.md](./04-api-spec.md) | REST API endpoints, WebSocket events, payload examples |
| [05-backend.md](./05-backend.md) | Maven module layout, Spring Boot config, proxy engine, AI analyzer, workflow state machine |
| [06-frontend.md](./06-frontend.md) | React/Vite project structure, key pages, SQL editor component |
| [07-security.md](./07-security.md) | Auth (JWT + SAML), roles, credential encryption, injection prevention, audit integrity |
| [08-notifications.md](./08-notifications.md) | Event types, Email/Slack/Webhook config, signed payload schema |
| [09-deployment.md](./09-deployment.md) | Docker Compose, Helm chart, environment variables reference |
| [10-editions.md](./10-editions.md) | Community vs Enterprise feature matrix, edition detection |
| [11-development.md](./11-development.md) | Repo structure, local setup, testing strategy, coding standards |
| [12-roadmap.md](./12-roadmap.md) | v1.0 → v2.1 milestone scope |

## Tech Stack Summary

- **Backend:** Java 21 + Spring Boot 3.3.x + Spring Security + Flyway + Hibernate 6
- **Frontend:** React 18 + Vite 5 + TypeScript + Ant Design 5 + CodeMirror 6
- **Internal DB:** PostgreSQL 15+
- **Target DBs:** PostgreSQL, MySQL (v1.0)
- **AI Backends:** OpenAI API, Anthropic Claude API, Ollama (self-hosted) — admin configurable
- **Auth:** JWT RS256 (Community), SAML 2.0 (Enterprise)
- **Deploy:** Docker Compose, Helm 3 / Kubernetes
- **Notifications:** Email (SMTP), Slack (Incoming Webhooks), Webhooks (HMAC-signed)

## Editions

| | Community | Enterprise |
|-|-----------|------------|
| License | Apache 2.0 | Commercial |
| SQL Proxy + Editor | ✓ | ✓ |
| Review Workflows | ✓ | ✓ |
| AI Query Analysis | ✓ | ✓ |
| Audit Log | ✓ | ✓ |
| SAML / SSO | — | ✓ |
| Multi-org tenancy | — | ✓ |
