# 12 — Roadmap

## Version History & Milestones

---

## v1.0 — General Availability (Target: Launch)

**Theme:** Core platform — everything needed for a team to govern database access end-to-end.

### Scope

**Proxy & Execution**
- Full SQL proxy with bundled drivers for PostgreSQL 13+, MySQL 8+, MariaDB, Oracle, and Microsoft SQL Server — plus admin-uploaded custom JDBC driver JARs for any other relational engine
- JSqlParser-based query validation and classification
- Per-datasource HikariCP connection pool management
- Schema / table allow-listing enforcement at AST level
- Row cap enforcement via JDBC `setMaxRows()`
- DDL blocking by default (opt-in via `can_ddl` permission)

**SQL Editor**
- CodeMirror 6 editor with SQL dialect-aware highlighting
- Schema autocomplete (tables, columns)
- SQL formatter (Ctrl+Shift+F)
- Real-time AI hint panel with gutter annotations

**Review Workflows**
- Configurable review plans (per datasource)
- Multi-stage sequential approval chains
- AI review → human approval pipeline
- Auto-approve reads option
- Approval timeout / auto-reject
- Cancel pending query (submitter)

**AI Query Analysis**
- OpenAI API adapter (GPT-4o)
- Anthropic API adapter (Claude Sonnet)
- Ollama adapter (self-hosted LLMs)
- Risk scoring (0–100), risk level (LOW/MEDIUM/HIGH/CRITICAL)
- Missing index detection
- Anti-pattern flags (SELECT *, no WHERE, no LIMIT)
- Syntax improvement suggestions

**Access Control**
- 4-role RBAC (ADMIN, REVIEWER, ANALYST, READONLY)
- Per-user datasource permissions with granular flags
- Time-limited access grants (`expires_at`)
- Schema/table scoping per permission record

**Audit**
- Append-only metadata audit log
- Full event coverage (see audit action types in data model doc)
- Searchable / filterable audit log UI

**Notifications**
- Email (SMTP / JavaMail + Thymeleaf templates)
- Slack (Incoming Webhooks + Block Kit)
- Webhooks (HMAC-SHA256 signed, with retry)

**Deployment**
- Docker Compose (single command startup)
- Helm 3 chart for Kubernetes
- Horizontal Pod Autoscaler support
- Spring Boot Actuator health / readiness probes

**Authentication**
- JWT RS256 with refresh token rotation
- Redis-backed token revocation
- SAML 2.0 SP-initiated and IdP-initiated SSO
- Auto-provisioning of users from SAML assertions
- SAML attribute → role mapping
- OAuth 2.0 / OIDC sign-in with built-in templates for Google, GitHub, Microsoft, and GitLab; additional providers configurable via DB-driven `oauth2_config` rows

---

## v1.1

**Theme:** Productivity and operations polish.

- **Dynamic JDBC driver loading** — drivers resolved from Maven Central on demand instead of bundled in the fat JAR (AF-10)
- **Datasource creation wizard** — visual type-selection step (Postgres, MySQL, MariaDB, Oracle, MSSQL) with logos and prefilled defaults (AF-11)
- **Query scheduling** — submit an approved query to execute at a future datetime
- **Bulk approval UI** — reviewers can approve/reject multiple queries in one action
- **AI analysis history dashboard** — trend charts: average risk score over time, most flagged query types, most active users
- **Schema explorer ER view** — basic entity-relationship diagram rendered from introspected schema
- **Review plan templates** — pre-built plans (e.g. "Strict — all writes need 2 approvals", "Lenient — reads auto-approved")
- **Audit log CSV export**
- **User invitation flow** — invite users by email instead of admin-created accounts

---

## v1.2

**Theme:** Integrations and reviewer experience.

- **Read replica routing** — admin can configure a read replica endpoint; SELECT queries are automatically routed there, leaving the primary for writes
- **Query result diffing** — for repeated runs of the same query, show a diff of `rows_affected` and execution time vs previous run
- **Slack bot approve/reject** — reviewers can approve or reject directly from the Slack message using Slack Interactive Components (OAuth app, not just webhooks)
- **PagerDuty integration** — built-in PagerDuty channel type for `CRITICAL` risk or `REVIEW_TIMEOUT` events
- **Query templates library** — save frequently used queries as templates, share across team
- **Datasource health dashboard** — connection pool stats, query volume, average execution time per datasource

---

## v2.0

**Theme:** Beyond relational databases.

- **MongoDB support** — query governance for MongoDB (find, update, delete, aggregation pipeline review)
- **Redis read-access governance** — audit and optionally require review for Redis GET/SCAN/keys operations
- **REST API access governance** — extend AccessFlow concept to HTTP API calls (not just SQL), for teams using internal REST services
- **Plugin API for custom AI analyzers** — allow teams to plug in their own analysis logic via a defined Java SPI or HTTP callback
- **Granular column-level permissions** — mask or block specific columns from appearing in SELECT results

---

## v2.1

**Theme:** Data classification, query suggestions, and compliance reporting.

- **Data classification tagging** — mark columns as PII, PCI, PHI in the schema explorer; AI analysis uses tags to increase risk score automatically
- **Automatic query suggestions** — based on historical approved queries, suggest similar safe queries to analysts
- **Compliance report export** — generate PDF/CSV compliance reports for SOC2, HIPAA, ISO 27001 audit evidence

---

## Contribution Path

PostgreSQL, MySQL, MariaDB, Oracle, and Microsoft SQL Server ship as bundled adapters; any other JDBC-compatible engine (SQLite, CockroachDB, Snowflake-via-JDBC, vendor forks, etc.) can already be added today by uploading a custom driver JAR through the admin UI (`db_type=CUSTOM` + `custom_jdbc_driver` — see [docs/03-data-model.md](03-data-model.md)). Community contributions promoting an engine to a first-class bundled adapter are welcome after v2.0; the `accessflow-proxy` module defines a `DatabaseAdapter` SPI that third-party adapters can implement. See `CONTRIBUTING.md` for the adapter development guide.
