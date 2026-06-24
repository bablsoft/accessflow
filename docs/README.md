# AccessFlow — Project Documentation Index

AccessFlow is an open-source **database access governance platform**. It acts as a full SQL proxy between users and relational databases (PostgreSQL, MySQL), enforcing configurable review and approval workflows before any query reaches live data.

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
| [11-development.md](./11-development.md) | Repo structure, local setup, testing strategy, coding standards |
| [12-roadmap.md](./12-roadmap.md) | v1.0 → v2.1 milestone scope |
| [13-mcp.md](./13-mcp.md) | Stateless MCP server, user API keys, exposed tools |
| [14-connectors.md](./14-connectors.md) | Declarative connector catalog — manifests, install lifecycle, release artifacts |
| [15-engine-sdk.md](./15-engine-sdk.md) | Engine-plugin SDK — authoring guide for native (non-JDBC) engines, host↔plugin contract, add-an-engine checklist |
| [16-iac.md](./16-iac.md) | Infrastructure as Code — Terraform/OpenTofu provider, reusable GitHub/GitLab CI Actions, service-account API keys, registry-publishing runbook |

## Tech Stack Summary

- **Backend:** Java 25 + Spring Boot 4 + Spring Modulith + Spring Security + Flyway + Hibernate 6
- **Frontend:** React 19 + Vite + TypeScript + Ant Design 6 + CodeMirror 6
- **Internal DB:** PostgreSQL 15+
- **Target DBs:** PostgreSQL, MySQL (v1.0)
- **AI Backends:** OpenAI API, Anthropic Claude API, Ollama (self-hosted), any OpenAI-compatible endpoint, Hugging Face (Inference Providers router or local TGI) — admin configurable
- **Auth:** JWT RS256 + optional SAML 2.0 SSO
- **Deploy:** Docker Compose, Helm 3 / Kubernetes
- **Notifications:** Email (SMTP), Slack (Incoming Webhooks), Webhooks (HMAC-signed)

## License

AccessFlow ships as a single open-source product under the Apache 2.0 license.
