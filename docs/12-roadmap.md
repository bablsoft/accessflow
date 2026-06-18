# 12 — Roadmap

## Version History & Milestones

---

## v1.0 — General Availability ✅ released

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

## v1.1 ✅ released

**Theme:** Productivity and operations polish.

- **Dynamic JDBC driver loading** — drivers resolved from Maven Central on demand instead of bundled in the fat JAR (AF-10)
- **Datasource creation wizard** — visual type-selection step (Postgres, MySQL, MariaDB, Oracle, MSSQL) with logos and prefilled defaults (AF-11)
- **Query scheduling** — submit an approved query to execute at a future datetime (AF-345)
- **Bulk approval UI** — reviewers can approve/reject multiple queries in one action (AF-346)
- **AI analysis history dashboard** — trend charts: average risk score over time, most flagged query types, most active users (AF-347)
- **Schema explorer ER view** — basic entity-relationship diagram rendered from introspected schema (AF-348)
- **Enhanced schema exploration** — searchable object tree (filter across schemas/tables/columns) plus on-demand, RLS- and masking-aware sample-row previews per table, across every engine (AF-443)
- **Review plan templates** — pre-built plans (e.g. "Strict — all writes need 2 approvals", "Lenient — reads auto-approved") (AF-349)
- **Audit log CSV export** (AF-350)
- **User invitation flow** — invite users by email instead of admin-created accounts (AF-276)

---

## v1.2 ✅ released

**Theme:** Integrations and reviewer experience.

- **Reviewer Access** — user groups, per-datasource reviewer scoping (users or groups), and SSO group → role/group mapping so teams only see the queues they own (AF-353)
- **Read replica routing** — admin can configure a read replica endpoint; SELECT queries are automatically routed there, leaving the primary for writes (AF-360)
- **Query result diffing** — for repeated runs of the same query, show a diff of `rows_affected` and execution time vs previous run (AF-361)
- **Slack bot approve/reject** — reviewers can approve or reject directly from the Slack message using Slack Interactive Components (OAuth app, not just webhooks) (AF-362)
- **PagerDuty integration** — built-in PagerDuty channel type for `CRITICAL` risk or `REVIEW_TIMEOUT` events (AF-363)
- **Query templates library** — save frequently used queries as templates, share across team (AF-364)
- **Datasource health dashboard** — connection pool stats, query volume, average execution time per datasource (AF-365)

---

## v1.3 ✅ released

**Theme:** Fine-grained access control and data protection.

- **Just-in-time (JIT) time-bound access requests** — users self-request temporary, scoped datasource access (e.g. write for 4 hours); granted on approval and auto-revoked on expiry by a clustered scheduler (AF-378)
- **Policy-as-code routing engine** — attribute-based rules decide query routing (auto-approve, auto-reject, require N approvers, escalate) from query type, referenced tables, AI risk, role / group membership, and time-of-day (AF-379)
- **Row-level security policies** — per-user/group/datasource row predicates injected at the AST layer so users see only the rows they are authorised for (AF-380)
- **Dynamic data masking policies** — per-column masking strategies (full, partial, hash, email, format-preserving) with role/group-based reveal conditions, extending today's static `restricted_columns` masking (AF-381)

---

## v1.4 — AI enhancements ✅ released

**Theme:** Deeper, configurable AI integrations.

- **Custom / OpenAI-compatible AI provider** — point the OpenAI adapter at any OpenAI-wire-compatible endpoint via an admin-supplied base URL (AF-330)
- **Hugging Face AI provider** — built-in provider using the Hugging Face Inference Providers router, with `meta-llama/Llama-3.3-70B-Instruct` as the default model (AF-331)
- **Editable analyzer prompts** — per-`ai_config` system-prompt override (validated to contain `{{sql}}`), with the default template served to the admin UI (AF-332)
- **Langfuse integration** — per-org LLM-call tracing + managed analyzer prompts (AF-333)
- **Text-to-SQL generation** — draft SQL from a natural-language prompt, still submitted through the full review pipeline (AF-335)
- **RAG knowledge base** — per-AI-config retrieval-augmented generation: admins attach knowledge documents (in-app pgvector or external Qdrant) that are embedded and injected into risk analysis and text-to-SQL prompts (AF-336)

---

## v2.0

**Theme:** Beyond relational databases — plus native client access and continuous governance.

- **MongoDB support** — query governance for MongoDB (find, update, delete, aggregation pipeline review)
- **Couchbase support** — SQL++ (N1QL) query governance through the second on-demand engine plugin (AF-412): classification onto the standard approval workflow, WHERE-splice row-level security, field masking, and scope/collection introspection
- **Redis read-access governance** — audit and optionally require review for Redis GET/SCAN/keys operations
- **Text-to-query for NoSQL** — extend text-to-SQL (AF-335) to every NoSQL engine via an engine-language-aware generation prompt: a natural-language prompt drafts the engine's native query (MongoDB shell/JSON, Cypher, CQL, Elasticsearch Query DSL, redis-cli), still submitted through the full review pipeline (AF-439)
- **REST API access governance** — extend AccessFlow concept to HTTP API calls (not just SQL), for teams using internal REST services
- **Plugin API for custom AI analyzers** — allow teams to plug in their own analysis logic via a defined Java SPI or HTTP callback
- **Granular column-level permissions** — mask or block specific columns from appearing in SELECT results
- **Native database wire-protocol gateway** — connect existing SQL clients (psql, DBeaver, DataGrip) and BI tools (Metabase, Tableau, Superset) through AccessFlow over the native PostgreSQL wire protocol, with every statement still flowing through the proxy's validation, masking, row-security, and audit path (AF-382)
- **Behavioral anomaly detection (UBA)** — rolling per-user / per-role baselines built from the audit stream flag out-of-pattern activity (volume, active hours, tables touched, rows returned) and escalate via the policy engine (AF-383)
- **Access recertification campaigns** — recurring attestation campaigns where datasource owners certify or revoke standing access, with exportable evidence for SOC2 / ISO 27001 audits (AF-384)
- **Break-glass / emergency access** — a gated, audited, time-boxed bypass that executes immediately while notifying all admins (incl. PagerDuty) and forcing a mandatory post-hoc review (AF-385)

---

## v2.1

**Theme:** Data classification, query suggestions, and compliance reporting.

- **Data classification tagging** — mark columns as PII, PCI, PHI in the schema explorer; AI analysis uses tags to increase risk score automatically
- **AI query-optimization & index recommendations** — the analyzer returns concrete, dialect-aware optimization suggestions (index DDL + query rewrites) alongside the risk verdict; each has a one-click "Apply as draft" that pre-fills the editor and routes the suggested statement through the normal review pipeline, audited as `submission_reason=AI_SUGGESTION` (AF-451)
- **Automatic query suggestions** — based on historical approved queries, suggest similar safe queries to analysts
- **Saved-query version history & diffing** — every template save records an immutable version; view a side-by-side Git-style diff between any two revisions and restore a prior one, with history preserved (AF-442)
- **Real-time collaborative query editing & co-authoring** — multiple authorized engineers co-edit a query that is in review (VS Code Live Share / Google Docs style): live presence, remote cursors, conflict-free CRDT merge (Yjs relayed over the existing `/ws`), and inline comment threads anchored to the SQL, persisted and audited. Edits re-enter the workflow through the normal submit path; the self-approval guard is unaffected (AF-441)
- **Compliance report export** — generate PDF/CSV compliance reports for SOC2, HIPAA, ISO 27001 audit evidence

---

## Contribution Path

PostgreSQL, MySQL, MariaDB, Oracle, and Microsoft SQL Server ship as bundled adapters; any other JDBC-compatible engine (SQLite, CockroachDB, Snowflake-via-JDBC, vendor forks, etc.) can already be added today by uploading a custom driver JAR through the admin UI (`db_type=CUSTOM` + `custom_jdbc_driver` — see [docs/03-data-model.md](03-data-model.md)). Community contributions promoting an engine to a first-class bundled adapter are welcome after v2.0; the `accessflow-proxy` module defines a `DatabaseAdapter` SPI that third-party adapters can implement. See `CONTRIBUTING.md` for the adapter development guide.
