# 01 — Overview, Problem Statement & Goals

## Executive Summary

AccessFlow is an open-source database access governance platform. It acts as a full SQL proxy between application users and relational databases — PostgreSQL, MySQL, MariaDB, Oracle, and Microsoft SQL Server are supported out of the box, and any other JDBC-compatible engine can be wired in via an admin-uploaded custom driver JAR — enforcing configurable review and approval workflows before any query is executed against live data.

Modern engineering teams face a critical gap: databases hold sensitive business data yet granting access is typically binary — either a user has credentials or they do not. AccessFlow bridges this gap by introducing a structured, auditable layer that supports:

- Fine-grained per-user access policies
- Human approval workflows (configurable per datasource)
- AI-powered query analysis (risk scoring, index hints, anti-pattern detection)
- A built-in SQL editor with real-time AI hints
- Complete metadata audit trails (who requested what, when, who approved it)
- SAML 2.0 SSO integration
- OAuth 2.0 / OIDC sign-in with built-in templates for Google, GitHub, Microsoft, and GitLab (additional providers configurable via the admin UI)

All deployed within the customer's own infrastructure.

---

## Problem Statement

Engineering and data teams require database access to do their jobs. The current industry norm presents two extremes:

1. **Full credentials shared across the team** — any developer can run `DELETE` without review, creating serious data integrity and compliance risks.
2. **No direct access at all** — every change goes through a slow manual DBA ticket process, creating bottlenecks that slow delivery.

There is no widely-adopted, self-hostable, open-source tool that provides a governed middle ground with AI assistance, human review workflows, and enterprise identity integration.

---

## Goals (v1.0)

- Provide a **proxy-based SQL governance layer** that intercepts all queries before execution.
- Allow admins to **define datasources** and configure per-datasource review policies.
- Enable users to **submit queries via a built-in SQL editor**, with AI-powered review before human approval.
- Maintain a **tamper-evident metadata audit log** of every query request, review, and execution.
- Support **notification delivery** via Email, Slack, and webhooks for review events.
- Ship as a production-ready **open-source project** under Apache 2.0.
- Be **easy to operate**: single `docker compose up` for local/small environments; Helm chart for Kubernetes production.

---

## Non-Goals (v1.0)

- NoSQL / Redis support (planned for v2.0)
- Data masking / column-level encryption in proxy
- Query result data storage — metadata-only audit trail (no actual row data stored)

---

## Core Value Proposition

| Capability | Description |
|-----------|-------------|
| Query interception | Every write (and optionally every read) can require review before execution |
| AI review | AI assistant reviews SQL for correctness, missing indexes, and anti-patterns |
| Audit trail | Full metadata audit — who requested what, when, who approved it |
| SQL editor | Built-in editor with syntax highlighting, autocomplete, and AI hints |
| Configurable policies | Per-datasource approval chains, row limits, time windows |
| SSO | SAML 2.0 and OAuth 2.0 / OIDC integration for workforce identity providers (Google, GitHub, Microsoft, GitLab templates ship out of the box) |
| Easy deployment | Docker Compose for small teams, Helm chart for Kubernetes |
