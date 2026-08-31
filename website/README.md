# website/

Public marketing site for AccessFlow. Static HTML / CSS / vanilla JavaScript — no build
step, no Node, no package.json. Deployable to any static host (GitHub Pages, S3, Netlify,
Cloudflare Pages, plain Nginx).

All copy is sourced from the application itself and the `docs/` chapters; no claims are
invented here. When the underlying code or docs change, the website must be updated in
the same change set — see the **"Do not let `website/` drift"** rule in
[`CLAUDE.md`](../CLAUDE.md).

---

## Local preview

```bash
cd website
python3 -m http.server 4173
```

Then open <http://localhost:4173>.

Any HTTP server that can serve a directory works equally well (`npx http-server`,
`caddy file-server`, etc.).

---

## Content-source map

When you change one of the source files on the left, check the corresponding section on
the right.

> **Since [#789](https://github.com/bablsoft/accessflow/issues/789) the homepage is a hub of
> teasers**: `/` keeps the hero, one real product screenshot (`#proof`, filled by
> [#790](https://github.com/bablsoft/accessflow/issues/790)), the request-flow tablist, three
> pillar cards under `#features`, the connector logo grid, one-command install tabs, six
> persona tiles, three FAQ answers, and a `#roadmap` stub — everything else lives on the
> spoke pages, and [#792](https://github.com/bablsoft/accessflow/issues/792) re-pointed every
> row below at wherever the copy actually landed.
>
> **How to read the right-hand column.** A bare `/features/#database` is a live URL on the
> site; the page that owns it has its own row further down. Where a claim survives in two
> places — a short hub card and the spoke section carrying the mechanism — both are named,
> because both have to change together. The wording is literal and was checked against the
> markup: **sentence** or **paragraph** means real prose you can grep, **chip** means the
> claim appears only as a `.tag` in a card's chip line and there is no sentence to edit, and
> a named anchor means the copy is in that section and nowhere else. Several capabilities are
> chip-only on `/features/` — the sentence lives on the spoke, and editing the hub would do
> nothing.

| Source of truth | Website section |
|---|---|
| [`README.md`](../README.md) (pitch, quick start) | Hero, Install tabs, terminal preview. The hero subhead is the `## Why AccessFlow` two-extremes / missing-middle framing — change both together |
| [`README.md`](../README.md), [`docs/01-overview.md`](../docs/01-overview.md), [`docs/07-security.md`](../docs/07-security.md), [`LICENSE.md`](../LICENSE.md) | **"Common questions" section** (homepage, `#questions`) — three question-form headings with short, self-contained answers (what a database access proxy is, VPN/bastion contrast, licence). The other three moved with [#789](https://github.com/bablsoft/accessflow/issues/789): supported engines to `/connectors/`, data handling to `/security/`, AI-agent/MCP access to `/ai-agents/`. Question-form headings and standalone answers are what AI-search surfaces extract, so keep each answer readable with **no surrounding context**. Deliberately **no `FAQPage` schema** — Google retired FAQ rich results for all sites in May 2026. Also linked from [`llms.txt`](llms.txt) |
| (no upstream — derived from the chapter it opens) | The question-form `<h3>` + answer block at the top of each [`docs/`](docs/) chapter, and the `Last updated <time>` line under every docs `<h1>`. The `datetime` attribute must stay equal to that page's JSON-LD `dateModified` **and** its [`sitemap.xml`](sitemap.xml) `<lastmod>` — move all three together, which `frontend/src/config/__tests__/websitePages.test.ts` now enforces |
| [`docs/02-architecture.md`](../docs/02-architecture.md), [`docs/07-security.md`](../docs/07-security.md), [`docs/09-deployment.md`](../docs/09-deployment.md) (`ENCRYPTION_KEY` / `JWT_PRIVATE_KEY` / `AUDIT_DB_USER`, observability vars) | **`/security/` page** ([`security/index.html`](security/index.html)) — the buying-question security answer: architecture canvas + Encryption / Runtime / Observability callouts, credential storage (AES-256-GCM at rest vs. Vault / AWS Secrets Manager / Azure Key Vault secret references), the "Workforce-ready auth" tile (`#auth`), the "Tamper-evident audit &amp; compliance reports" tile (`#audit`), and the data-handling answer (`#data-handling`). Note the `/features/` hub carries **shortened rewrites** of those same two tiles. `docs/02-architecture.md` still owns the *design* question — this page answers the *buying* one. Since [#789](https://github.com/bablsoft/accessflow/issues/789) cut the homepage `#architecture` section, feature tiles and data-handling FAQ entry, this page holds the **only** copy of the architecture canvas and the data-handling answer — no homepage twin to keep in sync |
| [`docs/14-connectors.md`](../docs/14-connectors.md), the [`connectors/`](../connectors/) manifests (`connector.json` — `category`, `bundled`, `driver.type`), [`CLAUDE.md`](../CLAUDE.md) *Project at a Glance* engine table, [`docs/05-backend.md`](../docs/05-backend.md) per-engine sections (the warehouse auth models), [`docs/15-engine-sdk.md`](../docs/15-engine-sdk.md) (the SPI / `ServiceLoader` claims), [`charts/accessflow/values.yaml`](../charts/accessflow/values.yaml) `driverCache.persistence` + [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_DRIVER_CACHE` / `ACCESSFLOW_DRIVERS_OFFLINE` (the cache-persistence and air-gap claims) | **`/connectors/` page** ([`connectors/index.html`](connectors/index.html)) — the "does it support my database?" answer: the full logo catalog split **SQL / cloud data warehouses / NoSQL**, a per-engine reference table (engine · `category` · in-process JDBC vs. engine plugin · install path), the manifest / SHA-256-pin / `ServiceLoader` catalog internals, uploaded-driver `CUSTOM` datasources, and the "Which databases does AccessFlow support?" answer. Since [#789](https://github.com/bablsoft/accessflow/issues/789), `/`'s `#connectors` block keeps only the logo grid under a retargeted h2 ("Eighteen engines, one governed checkpoint") — the catalog-internals prose and the "Which databases…" answer live **only** here, and the phrase "database connectors" belongs to this page's `h1` (plus `/`'s exact-match anchor text into it). The **logo grid** is still duplicated on `/` — change both copies together. Note `/` still groups the three warehouse engines under its **NoSQL** eyebrow — this page corrects that, and `/` is left alone because a third eyebrow there needs a fourth inline `style=""` |
| [`docs/02-architecture.md`](../docs/02-architecture.md), [`docs/05-backend.md`](../docs/05-backend.md) (JIT access, break-glass AF-385, routing policies AF-379, masking, lifecycle AF-499, attestation AF-384, compliance AF-459, discovery AF-623, apigov AF-500/AF-518), [`docs/07-security.md`](../docs/07-security.md) (credential resolution, the hash-chained audit log), [`docs/17-api-governance.md`](../docs/17-api-governance.md) (the `#api` block) | **`/use-cases/` page** ([`use-cases/index.html`](use-cases/index.html)) — the "is this my problem?" answer: six persona sections, each an anchor (`#platform`, `#sre`, `#dba`, `#compliance`, `#privacy`, `#api`), carrying the prose, the benefit list and the mock UI panel from the homepage `#use-cases` row — except `#sre`, whose mock [#790](https://github.com/bablsoft/accessflow/issues/790) replaced with the real `access-requests-queue` screenshot — expanded with a second paragraph of mechanism and sideways links into `/security/`, `/connectors/`, `/ai-agents/` and `/docs/**`. Since [#789](https://github.com/bablsoft/accessflow/issues/789) cut the homepage `#use-cases` rows to six one-line persona tiles linking these anchors, this page holds the only full copy of each persona story — a tile's one-liner is its section's h2 phrasing, so rename both together. The `#api` block is the canonical copy, and `/features/api-access-governance/#pipeline` now links to it rather than duplicating it. The `#api` block's "All AccessFlow capabilities" link was the last `/#features` link on the site after [#791](https://github.com/bablsoft/accessflow/issues/791) took the rest out of the chrome; [#792](https://github.com/bablsoft/accessflow/issues/792) retargeted it at `/features/`, which is what its anchor text promises — `/`'s `#features` block is three pillar cards, not all twelve |
| [`backend/pom.xml`](../backend/pom.xml), [`frontend/package.json`](../frontend/package.json) | From-source toolchain versions — the Install-tab comment on `/` and the from-source steps in [`docs/install/index.html`](docs/install/index.html); `/security/` owns the architecture callouts since [#789](https://github.com/bablsoft/accessflow/issues/789) |
| (no upstream — copy lives in the website) | System requirements &amp; sizing tiers (Evaluation / Production) — `#run-sizing` in [`docs/install/index.html`](docs/install/index.html), moved off `/` by [#789](https://github.com/bablsoft/accessflow/issues/789) |
| [`docs/07-security.md`](../docs/07-security.md) | "Workforce-ready auth" hub card (`/features/#platform`) — a shortened rewrite of the canonical copy, the sign-in section at `/security/#auth` |
| [`docs/07-security.md`](../docs/07-security.md) "SCIM 2.0 provisioning" | SCIM operator guide (`#cfg-scim`) in [`docs/configuration/auth/index.html`](docs/configuration/auth/index.html) + the SCIM sentence in the "Workforce-ready auth" hub card (`/features/#platform`) and in `/security/#auth` |
| [`docs/08-notifications.md`](../docs/08-notifications.md), [`docs/05-backend.md`](../docs/05-backend.md) "JIT time-bound access requests" + [`docs/07-security.md`](../docs/07-security.md) | "Configurable review workflows" hub card (`/features/#database`, incl. the JIT access-request sentence) + the review mechanism at `/features/database-access-governance/#review` |
| [`docs/08-notifications.md`](../docs/08-notifications.md) "ServiceNow" / "Jira" / "Ticketing inbound webhooks & bi-directional sync" (AF-453), [`docs/03-data-model.md`](../docs/03-data-model.md) `query_tickets`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Ticketing Integration Endpoints", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_NOTIFICATIONS_TICKETING_SIGNATURE_TOLERANCE` | the `ServiceNow` / `Jira` chips on the "Configurable review workflows" hub card (`/features/#database`) — chips only + the "Humans approve" pane of `/#how` — the one place on `/` that still names them + `/features/database-access-governance/#review` + the "ServiceNow &amp; Jira ticketing" item in `/roadmap/`'s available-now **Security &amp; ops** group + ServiceNow / Jira bullets, bi-directional-sync step, and encrypted-fields list under "Notification channels" in [`docs/configuration/notifications/index.html`](docs/configuration/notifications/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "JIT time-bound access requests", [`docs/07-security.md`](../docs/07-security.md) JIT section, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_ACCESS_*` env vars | "Just-in-time (JIT) access requests" subsection (`#cfg-access-requests`) + RBAC rows under "User roles &amp; RBAC" in [`docs/configuration/users-roles/index.html`](docs/configuration/users-roles/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Dynamic data masking policies", [`docs/07-security.md`](../docs/07-security.md) masking section, [`docs/03-data-model.md`](../docs/03-data-model.md) `masking_policy` | "Dynamic data masking" sentence in the "Full query proxy — SQL and NoSQL" hub card (`/features/#database`) + the masking paragraph at `/features/database-access-governance/#proxy` + "Masking policies" paragraph under "Datasources" in [`docs/configuration/datasources/index.html`](docs/configuration/datasources/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Row-level security policies", [`docs/07-security.md`](../docs/07-security.md) row-security section, [`docs/03-data-model.md`](../docs/03-data-model.md) `row_security_policy` / `users.attributes`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/row-security-policies` | "Row-level security" sentence in the "Full query proxy — SQL and NoSQL" hub card (`/features/#database`) + the row-security paragraph at `/features/database-access-governance/#proxy` + "Row security policies" paragraph under "Datasources" in [`docs/configuration/datasources/index.html`](docs/configuration/datasources/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Result-export governance &amp; DLP" (#626), [`docs/07-security.md`](../docs/07-security.md) result-export governance section, [`docs/03-data-model.md`](../docs/03-data-model.md) `export_policy`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/export-policies` + `/queries/{id}/results/export` | Result-export governance sentences at `/security/#audit` — the only marketing copy; the "Tamper-evident audit &amp; compliance reports" hub card says "searchable, exportable", which is compliance-report export, a different claim + the "Result-export governance &amp; DLP" item in `/roadmap/`'s available-now **Compliance** group + the `SoftwareApplication.featureList` entry, which stays on `/` + the export-policies mention in the "Datasources" chapter card on the [`/docs/` hub](docs/index.html) + the "Export policies" paragraph under "Datasources" in [`docs/configuration/datasources/index.html`](docs/configuration/datasources/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) data-classification tags &amp; auto-derived masking (AF-518), [`docs/03-data-model.md`](../docs/03-data-model.md) `data_classification`, [`frontend/src/pages/admin/DataClassificationsPage.tsx`](../frontend/src/pages/admin/DataClassificationsPage.tsx) | "Data classification" subsection (`#cfg-data-classifications`) under "Datasources" in [`docs/configuration/datasources/index.html`](docs/configuration/datasources/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Automated sensitive-data discovery" (AF-623), [`docs/03-data-model.md`](../docs/03-data-model.md) `discovery_scan_config` / `discovery_finding`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_DISCOVERY_*` env vars | the "Automated sensitive-data discovery" paragraph at `/features/database-access-governance/#proxy` — the hub card stops at "data classification tags", so discovery copy exists only on the spoke + the "Automated sensitive-data discovery" item in `/roadmap/`'s available-now **Security &amp; ops** group + "Automated discovery" paragraph under "Data classification" in [`docs/configuration/datasources/index.html`](docs/configuration/datasources/index.html) |
| [`frontend/src/i18n.ts`](../frontend/src/i18n.ts) `SUPPORTED_LANGUAGES` / `LANGUAGE_DISPLAY_NAMES`, [`frontend/src/pages/admin/LanguagesConfigPage.tsx`](../frontend/src/pages/admin/LanguagesConfigPage.tsx), [`docs/06-frontend.md`](../docs/06-frontend.md) i18n section, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/localization-config` | "Languages &amp; localization" section (`#cfg-languages`) under Configuration in [`docs/configuration/users-roles/index.html`](docs/configuration/users-roles/index.html) |
| [`frontend/src/config/docs.ts`](../frontend/src/config/docs.ts) (`DOCS_BASE_URL` / `DOCS_ANCHORS`), [`frontend/src/components/common/PageHeader.tsx`](../frontend/src/components/common/PageHeader.tsx) `docsAnchor` prop, [`docs/06-frontend.md`](../docs/06-frontend.md) "Contextual docs links" | The anchor `id`s in the [`docs/configuration/`](docs/configuration/) chapters, plus `#iac` in [`docs/iac/index.html`](docs/iac/index.html) — every one is the target of an in-app **View docs** link, so **renaming or removing an anchor breaks that link**. `frontend/src/config/__tests__/docs.test.ts` fails when a declared anchor has no matching `id` |
| [`docs/05-backend.md`](../docs/05-backend.md) "Review escalation and nudges", [`docs/03-data-model.md`](../docs/03-data-model.md) `review_plans` escalation columns, [`docs/08-notifications.md`](../docs/08-notifications.md) `REVIEW_ESCALATED` / `REVIEW_NUDGE` | the escalation-and-nudge paragraph at `/features/database-access-governance/#review` — the hub card names escalation only in its outbound link label, and its "escalate" is the routing action, a different feature + "Escalation &amp; reminders" section (`#cfg-review-escalation`) under Configuration &rarr; Review workflows |
| [`docs/05-backend.md`](../docs/05-backend.md) "Reviewer delegation", [`docs/03-data-model.md`](../docs/03-data-model.md) `review_delegations`, [`docs/07-security.md`](../docs/07-security.md) "Reviewer delegation", [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/me/review-delegations` | the out-of-office delegation paragraph at `/features/database-access-governance/#review` — the hub card names delegation only in its outbound link label + "Out-of-office delegation" section (`#cfg-review-delegation`) under Configuration &rarr; Review workflows |
| [`docs/05-backend.md`](../docs/05-backend.md) "Policy-as-code routing engine", [`docs/03-data-model.md`](../docs/03-data-model.md) `routing_policy` / `routing_decision`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/routing-policies` | "Policy-as-code routing" sentence in the "Configurable review workflows" hub card (`/features/#database`) + the routing paragraph at `/features/database-access-governance/#review` + "Routing policies" section under Configuration in [`docs/configuration/review-workflows/index.html`](docs/configuration/review-workflows/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Langfuse integration", [`docs/03-data-model.md`](../docs/03-data-model.md) `langfuse_config` / `ai_config.langfuse_prompt_*`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/langfuse-config`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_LANGFUSE_*` | the `Langfuse` chip on the "AI query analysis" hub card (`/features/#database`) — a chip only, there is no Langfuse prose anywhere on the marketing pages + "Langfuse integration" subsection (`#cfg-langfuse`) + `langfuse-config` (light + dark) figure under "AI configurations" in [`docs/configuration/ai/index.html`](docs/configuration/ai/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "RAG knowledge base", [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.rag_*`/`embedding_*` + `knowledge_document` + `vector_store`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/ai-configs/{id}/knowledge-documents` + `/rag/test`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_RAG_*` + "pgvector for RAG" | "RAG knowledge base" sentence + `RAG` chip in the "AI query analysis" hub card (`/features/#database`) + the analyzer section at `/features/database-access-governance/#ai` + "RAG knowledge base" paragraph, `ai-configs-rag` (light + dark) figure, and pgvector callout under "AI configurations" in [`docs/configuration/ai/index.html`](docs/configuration/ai/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "System Prompt Template" (`optimizations`), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_analyses.optimizations` + `query_requests.submission_reason`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/queries/analyze` + `/queries` `submission_reason` (AF-451) | the "missing-index detection, and anti-pattern flags, plus concrete fixes offered as a draft edit" sentence + `Optimization suggestions` chip in the "AI query analysis" hub card (`/features/#database`) + the analyzer section at `/features/database-access-governance/#ai` |
| [`docs/05-backend.md`](../docs/05-backend.md) "Multi-model orchestration, voting & guardrails" (AF-450), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.orchestration_*`/`voting_*`/`guardrail_patterns` + `ai_config_model` + `ai_analysis_model_result`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/ai-configs` orchestration/guardrail fields + `/admin/ai-analyses/stats` `per_model_stats` | "Run several models at once … voting … guardrails" sentence + `Multi-model voting` / `Guardrails` / `Per-model cost & latency` chips in the "AI query analysis" hub card (`/features/#database`) + the analyzer section at `/features/database-access-governance/#ai` + "Multi-model orchestration & voting" / "Guardrails" paragraphs under "AI configurations" and the per-model cost/latency note under "AI analyses dashboard" in [`docs/configuration/ai/index.html`](docs/configuration/ai/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Observability and tracing" (OTLP export, instrumented spans, metrics &amp; Grafana dashboards — AF-454), [`docs/09-deployment.md`](../docs/09-deployment.md) Observability env-var table (`OTEL_EXPORTER_OTLP_*`) + "Prometheus metrics &amp; Grafana dashboards", [`charts/accessflow/examples/values-observability.yaml`](../charts/accessflow/examples/values-observability.yaml) | the "Observability" callout in the architecture canvas at `/security/#architecture` — the only copy since [#789](https://github.com/bablsoft/accessflow/issues/789) cut `/`'s `#architecture` section + structured-logs and "Tracing &amp; metrics" notes under "Docker Compose" in [`docs/install/index.html`](docs/install/index.html) |
| [`docs/14-connectors.md`](../docs/14-connectors.md), [`connectors/`](../connectors/) catalog (incl. `connectors/mongodb/`, `connectors/couchbase/`, `connectors/redis/`, `connectors/cassandra/`, `connectors/scylladb/`, `connectors/elasticsearch/`, `connectors/opensearch/`, `connectors/dynamodb/`, `connectors/neo4j/`, `connectors/snowflake/`, `connectors/bigquery/`, `connectors/databricks/`), [`docs/04-api-spec.md`](../docs/04-api-spec.md) Connector endpoints + `category`, [`docs/03-data-model.md`](../docs/03-data-model.md) `datasources.connector_id`/`db_type` | Dedicated **"Connectors" section** (homepage, `#connectors`) — **SQL / cloud-data-warehouse / NoSQL grouped** logo grid of the catalog (logos in [`db-icons/`](db-icons/), copied from `connectors/<id>/logo.svg`, incl. `mongodb.svg`, `couchbase.svg`, `redis.svg`, `cassandra.svg`, `scylladb.svg`, `elasticsearch.svg`, `opensearch.svg`, `dynamodb.svg`, `neo4j.svg`, `snowflake.svg`, `bigquery.svg`, `databricks.svg`); the `connector catalog` chip and engine strip on the "Full query proxy — SQL and NoSQL" hub card (`/features/#database`) — there is no "Connector catalog" card any more, and the catalog prose lives only at `/connectors/`; the "Declarative connector catalog" item in `/roadmap/`'s available-now **Proxy &amp; data access** group; the "Connectors" section (built-in SQL + cloud-data-warehouse + NoSQL connector lists + install how-to) under Configuration in [`docs/configuration/connectors/index.html`](docs/configuration/connectors/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "MongoDB engine" + "Couchbase engine" + "Redis engine" + "Cassandra engine" + "Elasticsearch engine" + "DynamoDB engine" + "Neo4j engine" + "Snowflake engine" + "BigQuery engine" + "Databricks engine", [`docs/14-connectors.md`](../docs/14-connectors.md), [`docs/06-frontend.md`](../docs/06-frontend.md) editor/results, [`connectors/mongodb/`](../connectors/mongodb/), [`connectors/couchbase/`](../connectors/couchbase/), [`connectors/redis/`](../connectors/redis/), [`connectors/cassandra/`](../connectors/cassandra/), [`connectors/scylladb/`](../connectors/scylladb/), [`connectors/elasticsearch/`](../connectors/elasticsearch/), [`connectors/opensearch/`](../connectors/opensearch/), [`connectors/dynamodb/`](../connectors/dynamodb/), [`connectors/neo4j/`](../connectors/neo4j/), [`connectors/snowflake/`](../connectors/snowflake/), [`connectors/bigquery/`](../connectors/bigquery/), [`connectors/databricks/`](../connectors/databricks/) | **MongoDB + Couchbase + Redis + Cassandra + ScyllaDB + Elasticsearch + OpenSearch + Amazon DynamoDB + Neo4j (NoSQL) + Snowflake + BigQuery + Databricks (cloud data warehouses)** copy — the logo grid on `/` (`#connectors`) and its fuller twin at `/connectors/#catalog`, the "Full query proxy — SQL and NoSQL" hub card (`/features/#database`) and its engine chip strip, `/`'s meta description, and the "Built-in connectors — NoSQL" / "— Cloud data warehouses" subsections under "Connectors" in [`docs/configuration/connectors/index.html`](docs/configuration/connectors/index.html) |
| [`docs/03-data-model.md`](../docs/03-data-model.md) `organizations` (`disabled`/`max_*`) + `users.platform_admin`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Platform Organizations" + `platform_admin` on the login/`/me` user object, [`docs/07-security.md`](../docs/07-security.md) "Platform admin" + "Multi-tenant isolation", [`docs/05-backend.md`](../docs/05-backend.md) "Multi-tenant isolation hardening" (AF-456) | the multi-tenancy paragraph at `/security/#auth` + the `Multi-tenant orgs` chip on the "Workforce-ready auth" hub card (`/features/#platform`), which carries no multi-tenancy prose of its own + the "Multi-tenant orgs &amp; per-org quotas" item in `/roadmap/`'s available-now **Review &amp; access** group + the "Organizations &amp; quotas" section and the platform-admin note under "User roles &amp; RBAC" in [`docs/configuration/users-roles/index.html`](docs/configuration/users-roles/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Query snapshots &amp; replay" (AF-449), [`docs/03-data-model.md`](../docs/03-data-model.md) `query_snapshots`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `POST /queries/{id}/replay` | the snapshot-and-replay sentence at `/features/database-access-governance/#proxy` ("kept as an exact snapshot that can be replayed in a test environment") — not on the `/features/` hub, which has no snapshot copy + the "Version history &amp; diff · dry-run sandbox · replay" item in `/roadmap/`'s available-now **Review &amp; access** group |
| [`docs/05-backend.md`](../docs/05-backend.md) "Compliance reporting" (AF-459), [`docs/07-security.md`](../docs/07-security.md) "Compliance reporting &amp; signed exports" + AUDITOR role matrix, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Compliance Reporting", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_COMPLIANCE_*` env vars | "Tamper-evident audit &amp; compliance reports" hub card (`/features/#platform`), a shortened rewrite of the canonical copy at `/security/#audit`, + the "Compliance reports &amp; signed exports" item in `/roadmap/`'s available-now **Compliance** group + the "Compliance reports &amp; signed exports" section in [`docs/configuration/audit-compliance/index.html`](docs/configuration/audit-compliance/index.html) and the AUDITOR row under "User roles &amp; RBAC" in [`docs/configuration/users-roles/index.html`](docs/configuration/users-roles/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "SIEM &amp; WORM audit streaming" (#628), [`docs/03-data-model.md`](../docs/03-data-model.md) `audit_sinks` + `AUDIT_SINK_*` actions, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Audit Sinks", [`docs/07-security.md`](../docs/07-security.md) "SIEM &amp; WORM audit streaming" + `AUDIT_SINK_MANAGE` matrix row, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_AUDIT_SINKS_*` env vars + "S3 Object Lock prerequisite" | SIEM/WORM sentence + `SIEM streaming` / `WORM archival` chips in the "Tamper-evident audit &amp; compliance reports" hub card (`/features/#platform`) and at `/security/#audit` + the "SIEM audit streaming &amp; WORM archival" item in `/roadmap/`'s available-now **Auth &amp; audit** group + the `featureList` entry, which stays on `/`, + the "Audit sinks (SIEM &amp; WORM streaming)" section (`#cfg-audit-sinks`) in [`docs/configuration/audit-compliance/index.html`](docs/configuration/audit-compliance/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Behavioural anomaly detection (UBA)" (AF-383), [`docs/03-data-model.md`](../docs/03-data-model.md) `behavior_baseline` / `behavior_anomaly`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Behavioural Anomaly Detection (UBA)", [`docs/08-notifications.md`](../docs/08-notifications.md) `ANOMALY_DETECTED`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_AI_ANOMALY_*` env vars | the `Anomaly detection (UBA)` chip on the "AI query analysis" hub card (`/features/#database`) — chip only — plus the anomaly paragraphs at `/features/database-access-governance/#ai` and `#review`, and the "your own anomaly alerts" clause in the "Personalized dashboard" hub card + the "Behavioral anomaly detection (UBA)" item in `/roadmap/`'s available-now **AI &amp; monitoring** group + the "Behavioural anomaly detection (UBA)" section in [`docs/configuration/ai/index.html`](docs/configuration/ai/index.html) and the anomaly RBAC rows under "User roles &amp; RBAC" in [`docs/configuration/users-roles/index.html`](docs/configuration/users-roles/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Approval-outcome prediction" (AF-645), [`docs/03-data-model.md`](../docs/03-data-model.md) `approval_prediction_model` / `approval_predictions`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `approval_prediction` block + `approval_probability` + `query.prediction_complete`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_AI_APPROVAL_PREDICTION_*` env vars | "historical approval likelihood" sentence + `Approval likelihood (advisory)` chip in the "AI query analysis" hub card (`/features/#database`) + the analyzer section at `/features/database-access-governance/#ai` + the approval-likelihood bullet in the "Review at scale without a ticket queue" persona at `/use-cases/#dba` + the "Approval-likelihood prediction" item in `/roadmap/`'s available-now **AI &amp; monitoring** group + the "Approval-likelihood prediction" subsection (`#cfg-approval-prediction`) in [`docs/configuration/ai/index.html`](docs/configuration/ai/index.html) and the queue paragraph under "Reviewing &amp; bulk approval" in [`docs/workflows/index.html`](docs/workflows/index.html). Keep the advisory-only framing — it is a triage signal, never auto-approval |
| [`docs/05-backend.md`](../docs/05-backend.md) "Break-glass / emergency access" (AF-385), [`docs/03-data-model.md`](../docs/03-data-model.md) `break_glass_events` / `can_break_glass`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/queries/break-glass` + `/admin/break-glass`, [`docs/07-security.md`](../docs/07-security.md) "Break-glass / emergency access", [`docs/08-notifications.md`](../docs/08-notifications.md) `BREAK_GLASS_EXECUTED` | "break-glass / emergency access" sentence + `Break-glass emergency access` chip in the "Configurable review workflows" hub card (`/features/#database`) + `/features/database-access-governance/#review` + the on-call persona at `/use-cases/#sre` + the "Break-glass emergency access" item in `/roadmap/`'s available-now **Review &amp; access** group + the "Break-glass / emergency access" subsection (`#cfg-break-glass`) and break-glass RBAC rows under "User roles &amp; RBAC" in [`docs/configuration/users-roles/index.html`](docs/configuration/users-roles/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Personalized dashboard" (AF-498), [`docs/06-frontend.md`](../docs/06-frontend.md) DashboardPage, [`docs/03-data-model.md`](../docs/03-data-model.md) `dashboard_suggestion_state` / `dashboard_digest_subscription`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Personalized Dashboard", [`docs/08-notifications.md`](../docs/08-notifications.md) `WEEKLY_DIGEST`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_DASHBOARD_WEEKLY_DIGEST_*` env vars | "Personalized dashboard" hub card (`/features/#platform`) + the `dashboard` screenshot in `/`'s `#proof` band + the "Personalized dashboard &amp; weekly digest" item in `/roadmap/`'s available-now **Review &amp; access** group + the "Personalized dashboard &amp; weekly digest" section in [`docs/configuration/audit-compliance/index.html`](docs/configuration/audit-compliance/index.html) |
| [`docs/06-frontend.md`](../docs/06-frontend.md) "Progressive Web App & Web Push" (AF-444), [`docs/08-notifications.md`](../docs/08-notifications.md) "Web Push", [`docs/05-backend.md`](../docs/05-backend.md) "Step-up auth and the one-tap push decide path", [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/auth/step-up` + `/reviews/{id}/decide` + Web Push endpoints, [`docs/03-data-model.md`](../docs/03-data-model.md) `push_subscriptions` / `push_vapid_config`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_PUSH_VAPID_*` + `ACCESSFLOW_SECURITY_STEP_UP_TTL` | the `Mobile PWA` / `One-tap push approvals` chips on the "Configurable review workflows" hub card (`/features/#database`) — chips only — plus the one-tap push paragraph at `/features/database-access-governance/#review` + the "Mobile PWA + one-tap push" item in `/roadmap/`'s available-now **Review &amp; access** group + the "Mobile approvals &amp; one-tap push" subsection (incl. VAPID / step-up env vars) under "End-user workflows" in [`docs/workflows/index.html`](docs/workflows/index.html) |
| [`docs/16-iac.md`](../docs/16-iac.md), [`terraform-provider/`](../terraform-provider/), [`.github/actions/`](../.github/actions/), [`ci-templates/`](../ci-templates/), [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS_*` (AF-452) | "Infrastructure as Code" hub card (`/features/#platform`) + `/roadmap/`'s available-now **Automation &amp; IaC** group — the homepage `#docs` grid card was deleted, not moved, by [#789](https://github.com/bablsoft/accessflow/issues/789) + the "Infrastructure as Code (Terraform / OpenTofu &amp; CI Actions)" section (`#iac`, incl. service-account env vars + HCL example) in [`docs/iac/index.html`](docs/iac/index.html) and the further-reading link on the [`/docs/` hub](docs/index.html) |
| [`docs/17-api-governance.md`](../docs/17-api-governance.md), [`docs/03-data-model.md`](../docs/03-data-model.md) `api_connectors` / `api_schemas` / `api_connector_user_permissions`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "API Access Governance", [`docs/05-backend.md`](../docs/05-backend.md) "API Access Governance (apigov module)" (AF-500) | "Governed outbound API calls" hub card (`/features/#api`) — renamed from "API Access Governance" by [#787](https://github.com/bablsoft/accessflow/issues/787), the phrase now being the `/features/api-access-governance/` spoke's own head term — plus that spoke and the `/use-cases/#api` persona. The homepage `#docs` grid card was deleted, not moved |
| [`docs/17-api-governance.md`](../docs/17-api-governance.md) "Dynamic variables" (AF-613), [`docs/03-data-model.md`](../docs/03-data-model.md) `api_connector_variables` / `api_requests.variable_overrides` / `can_override_variables`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Connector variables", [`docs/05-backend.md`](../docs/05-backend.md) "Dynamic variables", [`docs/07-security.md`](../docs/07-security.md) dynamic-variable secret bullets, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_APIGOV_MAX_VARIABLE_VALUE_BYTES` | the "Values computed per call" block at `/features/api-access-governance/#masking` — the only marketing copy, and note it sits under that section, not `#connectors`; the "Governed outbound API calls" hub card stops at the auth methods + the "Dynamic variables (request signing)" and "Per-request overrides" paragraphs under "API connectors" (`#cfg-api-connectors`) in [`docs/configuration/connectors/index.html`](docs/configuration/connectors/index.html) |
| [`docs/18-deployment-governance.md`](../docs/18-deployment-governance.md), [`docs/03-data-model.md`](../docs/03-data-model.md) `deployment_pipelines` / `deployment_environments` / `deployment_freeze_windows` / `deployment_requests`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Deployment Governance", [`docs/05-backend.md`](../docs/05-backend.md) "Deployment Governance (deploygov module)", [`docs/07-security.md`](../docs/07-security.md) "Deployment governance security" (epic AF-682) | "Gated CI/CD releases" hub card (`/features/#deployment`) — renamed from "Deployment approval governance" by [#787](https://github.com/bablsoft/accessflow/issues/787), which moved that phrase to the `/features/deployment-governance/` spoke as its head term — plus that spoke and `/`'s "Deployments" pillar card; the homepage `#docs` grid card was deleted, not moved, **"Deployment pipelines"** section (`/docs/configuration/review-workflows/#cfg-deployment-pipelines`) + matching WebP under [`images/docs/`](images/docs/) (`deployments-list`, `deployment-detail`, `deployment-reviews-queue`, `deployment-rollback-reviews`, `deployment-pipelines-list`, `deployment-pipeline-environments`, `deployment-pipeline-freeze-windows`, `deployment-pipeline-ci`, light + dark) |
| [`docs/16-iac.md`](../docs/16-iac.md) "Deployment gate (AF-694)", [`.github/actions/deployment-gate`](../.github/actions/deployment-gate) + [`deployment-outcome`](../.github/actions/deployment-outcome), [`ci-templates/gitlab/accessflow-deployment.gitlab-ci.yml`](../ci-templates/gitlab/accessflow-deployment.gitlab-ci.yml), [`ci-templates/azure/`](../ci-templates/azure) | **"Deployment gate in CI"** section (`/docs/iac/#iac-deployment-gate`) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Data Lifecycle Manager" (AF-499), [`docs/03-data-model.md`](../docs/03-data-model.md) `retention_policies` / `deletion_requests` / `lifecycle_runs`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Data Lifecycle Manager", [`docs/06-frontend.md`](../docs/06-frontend.md) lifecycle pages, [`docs/07-security.md`](../docs/07-security.md) "Lifecycle pseudonymization & salt rotation", [`docs/08-notifications.md`](../docs/08-notifications.md) `ERASURE_APPROVED`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_LIFECYCLE_*` env vars | "Data lifecycle &amp; right-to-erasure" hub card (`/features/#database`) + the retirement section at `/features/database-access-governance/#lifecycle` + the privacy persona at `/use-cases/#privacy` + the "Data lifecycle &amp; right-to-erasure" subsection (`#cfg-lifecycle`, incl. scan/erasure env vars) under "Configuration" in [`docs/configuration/audit-compliance/index.html`](docs/configuration/audit-compliance/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Request chaining & grouping" (AF-501), [`docs/03-data-model.md`](../docs/03-data-model.md) `request_groups` / `request_group_items` / `group_review_decisions`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Request chaining & grouping", [`docs/06-frontend.md`](../docs/06-frontend.md) "Request chaining & grouping pages", [`docs/07-security.md`](../docs/07-security.md) "Request chaining & grouping security", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_REQUESTGROUPS_*` env vars | "Request chaining &amp; grouping" hub card (`/features/#database`) + the bundling section at `/features/database-access-governance/#grouping` + the "Request chaining &amp; grouping" item in `/roadmap/`'s available-now **Review &amp; access** group + the "Request chaining &amp; grouping" subsection (`#flow-request-groups`, incl. run/timeout env vars) under "End-user workflows" in [`docs/workflows/index.html`](docs/workflows/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Access recertification / attestation campaigns" (AF-384), [`docs/03-data-model.md`](../docs/03-data-model.md) `attestation_campaigns` / `attestation_items`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Attestation", [`docs/06-frontend.md`](../docs/06-frontend.md) CampaignListPage / AttestationWorklistPage, [`docs/07-security.md`](../docs/07-security.md) attestation self-review, [`docs/08-notifications.md`](../docs/08-notifications.md) `ATTESTATION_*`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_ATTESTATION_*` env vars | the "Access recertification campaigns" item in `/roadmap/`'s available-now **Compliance** group + the compliance persona at `/use-cases/#compliance` + the "Access recertification campaigns" subsection (`#cfg-attestation`, incl. open/close/evidence env vars) and `attestation-campaigns` (light + dark) figure under "Configuration" in [`docs/configuration/review-workflows/index.html`](docs/configuration/review-workflows/index.html) |
| [`docs/02-architecture.md`](../docs/02-architecture.md), [`docs/05-backend.md`](../docs/05-backend.md) (JIT access, break-glass AF-385, routing policies AF-379, masking, lifecycle AF-499, attestation AF-384, compliance AF-459, apigov AF-500/AF-518), [`docs/07-security.md`](../docs/07-security.md), [`docs/17-api-governance.md`](../docs/17-api-governance.md) | **"Use cases" section** (homepage, `#use-cases`) — six one-line persona tiles since [#789](https://github.com/bablsoft/accessflow/issues/789), each linking a `/use-cases/` anchor (`#platform`, `#sre`, `#dba`, `#compliance`, `#privacy`, `#api`); the full persona stories live on `/use-cases/` |
| [`docs/07-security.md`](../docs/07-security.md) "External secret stores" (AF-448), [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_SECRETS_*` env vars, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/secret-providers` | the "Encryption" callout and the credential-storage section at `/security/#architecture` and `/security/#credentials` + the "External secrets managers" item in `/roadmap/`'s available-now **Security &amp; ops** group + the "External secrets managers" note under "Docker Compose" in [`docs/install/index.html`](docs/install/index.html) and the secret-reference sentence under "Datasources → Connection details" in [`docs/configuration/datasources/index.html`](docs/configuration/datasources/index.html) |
| [`docs/09-deployment.md`](../docs/09-deployment.md) "Disaster Recovery" (AF-458), [`charts/accessflow/README.md`](../charts/accessflow/README.md) "Backup & restore", [`charts/accessflow/examples/values-backup.yaml`](../charts/accessflow/examples/values-backup.yaml), `ACCESSFLOW_AUDIT_VERIFY_CHAIN_ON_STARTUP` env var | the "Backup / restore &amp; DR tooling" item in `/roadmap/`'s available-now **Security &amp; ops** group + the `values-backup.yaml` example bullet and "Backup, restore &amp; disaster recovery" paragraph under "Kubernetes &amp; Helm" in [`docs/install/index.html`](docs/install/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "AI provider fallback pool" (AF-458), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.fallback_priority`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) AI Configurations `fallback_priority` | the `Fallback pool` chip on the "AI query analysis" hub card (`/features/#database`) — chip only — plus the fallback-pool sentence at `/features/database-access-governance/#ai` + the "Offline AI fallback" item in `/roadmap/`'s available-now **AI &amp; monitoring** group + the "Fallback pool" paragraph under "AI configurations" in [`docs/configuration/ai/index.html`](docs/configuration/ai/index.html) |
| [`docs/12-roadmap.md`](../docs/12-roadmap.md) | **Roadmap stub** (homepage, `#roadmap`) — since [#789](https://github.com/bablsoft/accessflow/issues/789) a two-line stub that keeps the `#roadmap` id alive and links the `/roadmap/` page. [#791](https://github.com/bablsoft/accessflow/issues/791) took `#roadmap` out of the nav, which does **not** retire the id: it is still in the nav of every already-deployed page and in the wild, and a fragment can never be redirect-repaired. The `/roadmap/` page owns the capability map |
| [`docs/12-roadmap.md`](../docs/12-roadmap.md) (milestone grouping, `✅ released` / `🚧 in progress` status markers, Backlog list, Contribution Path), the [GitHub milestones](https://github.com/bablsoft/accessflow/milestones) (live status of the release in progress — note the doc grouping runs about one release offset from the GitHub milestones, so reconcile against both) | **`/roadmap/` page** ([`roadmap/index.html`](roadmap/index.html)) — the status answer: the **Available now** capability grid (ten groups: Proxy &amp; data access · AI &amp; monitoring · Review &amp; access · API governance · Deployment governance · Compliance · Auth &amp; audit · Security &amp; ops · Automation &amp; IaC · Deploy) above a compact **Planned** band — the **only** copy since [#789](https://github.com/bablsoft/accessflow/issues/789) cut the homepage `#roadmap` section to a stub. Grouped by capability, never by release; a feature moving out of the doc's Backlog into a released milestone must move out of the Planned band into the matching available-now group. Plus what the homepage cannot afford: per-milestone context for the recent releases, the in-progress milestone's scope, and links to the GitHub milestones. Never name website work as a roadmap item — the page describes the product |
| [`CLAUDE.md`](../CLAUDE.md) *Project at a Glance*, [`docs/05-backend.md`](../docs/05-backend.md), [`docs/07-security.md`](../docs/07-security.md) — the sources the twelve capability cards draw on | **`/features/` hub** ([`features/index.html`](features/index.html)) — the "what can it actually do?" answer: all twelve capabilities at ~90 words each, grouped into `#database`, `#api`, `#deployment` and `#platform`, with each card linking to its deep dive. Since [#789](https://github.com/bablsoft/accessflow/issues/789) cut the homepage's twelve `.feat` cards to three pillar cards, the hub cards (and their `.tag` chip lines) are the canonical capability-by-capability copy — a capability change lands here first, and on `/` only if a pillar card states the changed claim. The sidebar is #782's deliberate replacement for a JS nav dropdown: unlike a dropdown, it is rankable. The head term **database access control** lives here and nowhere else — [#787](https://github.com/bablsoft/accessflow/issues/787) lifted it off `/`. `/` links the hub and — since [#789](https://github.com/bablsoft/accessflow/issues/789) rebuilt its `#features` section as three pillar cards — each spoke directly, with exact-match anchor text |
| [`docs/05-backend.md`](../docs/05-backend.md) (proxy pipeline, AI analyzer, routing policies AF-379, JIT access, break-glass AF-385, masking AF-381, row security, discovery AF-623, cost estimate AF-624, request groups AF-501, lifecycle AF-499), [`docs/07-security.md`](../docs/07-security.md), [`docs/08-notifications.md`](../docs/08-notifications.md) (delegation, escalation, ticketing) | **`/features/database-access-governance/` spoke** ([`features/database-access-governance/index.html`](features/database-access-governance/index.html)) — the mechanism the hub summarises, following one query end to end: `#proxy`, `#ai`, `#review`, `#grouping`, `#lifecycle`, plus a closing `#teams` band that hands off to the personas. Primary keyword **database access governance**, which is why `/`'s `#architecture` and `#use-cases` h2s no longer carry that phrase ([#787](https://github.com/bablsoft/accessflow/issues/787)). Keep the honest caveats: approval likelihood is advisory only, and a grouped request has no distributed rollback |
| [`docs/17-api-governance.md`](../docs/17-api-governance.md) (connectors + OAuth2 token sourcing §1, schema ingestion §2, the governed-call pipeline §4, masking &amp; classification §5, dynamic variables §6) | **`/features/api-access-governance/` spoke** ([`features/api-access-governance/index.html`](features/api-access-governance/index.html)) — `#connectors`, `#catalog`, `#pipeline`, `#masking`. Primary keyword **API access governance**. Its `#pipeline` section **links** [`use-cases/index.html`](use-cases/index.html) `#api` rather than restating the persona story — that block stays the canonical copy, which is the arrangement [#788](https://github.com/bablsoft/accessflow/issues/788) asked for |
| [`docs/18-deployment-governance.md`](../docs/18-deployment-governance.md) (pipelines &amp; grants §1, the CI trigger §2, review and break-glass §4, the gate §5, freeze windows §6, outcome reporting §7), [`docs/16-iac.md`](../docs/16-iac.md) + [`ci-templates/`](../ci-templates/) for the CI wrappers | **`/features/deployment-governance/` spoke** ([`features/deployment-governance/index.html`](features/deployment-governance/index.html)) — `#pipelines`, `#gate`, `#freeze`, `#outcome`, `#ci`. Primary keyword **deployment approval governance**. Two claims are easy to get wrong: triggering is a per-pipeline `can_trigger` **grant**, not the `DEPLOYMENT_PIPELINE_MANAGE` permission, and the CI wrappers address a pipeline by id because a trigger-only key may not resolve names |
| [`docs/`](../docs/) chapter filenames + H1s (01–18, incl. [`docs/14-connectors.md`](../docs/14-connectors.md), the [`docs/15-engine-sdk.md`](../docs/15-engine-sdk.md) engine-author guide, the [`docs/16-iac.md`](../docs/16-iac.md) IaC guide, the [`docs/17-api-governance.md`](../docs/17-api-governance.md) API-governance guide, and the [`docs/18-deployment-governance.md`](../docs/18-deployment-governance.md) deployment-governance guide) | The further-reading links inside [`docs/index.html`](docs/index.html), and the footer "Docs" column — which [#791](https://github.com/bablsoft/accessflow/issues/791) repointed at the on-site chapters. Note the four footer labels are copied verbatim from the **docs sidebar TOC**, not from these markdown H1s and not from the chapters' own on-page `h1`s (all three differ): rename a sidebar label and all 22 footers move with it. The targets are `/docs/`, `/docs/install/`, `/docs/configuration/auth/`, `/docs/workflows/` and `/docs/iac/`, leaving exactly one engineering-reference link to `github.com/blob` — [`docs/04-api-spec.md`](../docs/04-api-spec.md), the one of the four with no on-site equivalent, labelled `REST API (GitHub)` so the off-site jump is legible and the name still matches the further-reading list. The homepage `#docs` grid of raw `github.com/blob` cards was **deleted** (not moved) by [#789](https://github.com/bablsoft/accessflow/issues/789); `/docs/` is the docs hub |
| [`CLAUDE.md`](../CLAUDE.md) (supported db list, env-var defaults) | Hero meta strip (the features `.tag` chips moved to the `/features/` hub with [#789](https://github.com/bablsoft/accessflow/issues/789)) |
| [`charts/accessflow/`](../charts/accessflow/) | Helm install tab |
| [`README.md`](../README.md) quick start + [`docs/05-backend.md`](../docs/05-backend.md), [`docs/07-security.md`](../docs/07-security.md), [`docs/08-notifications.md`](../docs/08-notifications.md), [`docs/09-deployment.md`](../docs/09-deployment.md) | The [`/docs/` hub](docs/index.html) and its twelve chapter pages — user documentation (run + configure) |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) pre-release handling, [`docs/09-deployment.md`](../docs/09-deployment.md) "Installing a pre-release / beta build", [`docs/11-development.md`](../docs/11-development.md) "Pre-release (beta) builds" | "Beta / pre-release channel" subsection (`#run-beta`) under "Running AccessFlow" in [`docs/install/index.html`](docs/install/index.html) |
| [`frontend/src/pages/admin/`](../frontend/src/pages/admin/), [`frontend/src/pages/datasources/`](../frontend/src/pages/datasources/) — admin SPA pages | The [`docs/configuration/`](docs/configuration/) chapter walkthroughs (Users, User groups, Datasources + Schema explorer (searchable object tree + RLS/masking-aware sample-data previews, AF-443; see [`docs/05-backend.md`](../docs/05-backend.md) "Sample data path" + [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/sample-rows`) + ER diagram + Masking + Row security tabs, Connectors, Custom JDBC drivers, Review plans + templates, Routing policies, Access requests queue, AI configs + Langfuse + RAG, AI analyses dashboard, Datasource health, Organizations (multi-tenant management + quotas), Auditor dashboard, Anomalies (UBA), Break-glass log, Notifications, System SMTP, OAuth, SAML, Slack app, Audit log) + matching PNGs under [`images/docs/`](images/docs/) (incl. `routing-policies`, `access-requests-queue`, `datasources-masking`, `datasources-row-security`, `langfuse-config`, `ai-configs-rag`, `organizations-list`, `auditor-dashboard`, `anomalies-dashboard`, `break-glass-log`, `dashboard`, `attestation-campaigns`, `api-connectors-list`, `lifecycle-policies`, `deployments-list`, `deployment-detail`, `deployment-reviews-queue`, `deployment-rollback-reviews`, `deployment-pipelines-list`, `deployment-pipeline-environments`, `deployment-pipeline-freeze-windows`, `deployment-pipeline-ci`, light + dark) |
| [`frontend/src/pages/editor/QueryEditorPage.tsx`](../frontend/src/pages/editor/QueryEditorPage.tsx), [`frontend/src/pages/reviews/ReviewQueuePage.tsx`](../frontend/src/pages/reviews/ReviewQueuePage.tsx) — end-user SPA pages | [`docs/workflows/index.html`](docs/workflows/index.html) — the "End-user workflows" chapter (Submitting / Scheduling a query, Drafting queries from natural language, Query templates library, Reviewing &amp; bulk approval) + matching PNGs under [`images/docs/`](images/docs/) (`editor-light`, `editor-text-to-sql-light`, `editor-schedule-light`, `editor-query-templates-light`, `reviews-queue-bulk-light`, `request-groups-list-light`, `api-requests-list-light`) |
| Existing on-page copy (hero, features, supported DBs, license) | SEO meta block (canonical, OG, Twitter, JSON-LD) on every page — [`index.html`](index.html), the eight topic pages and the twelve docs chapters |
| Homepage meta description + the eight topic pages' own descriptions + [`docs/`](../docs/) chapter list | [`llms.txt`](llms.txt) — llmstxt.org index for LLM agents. The `>` summary blockquote is the text LLM crawlers quote — change it only when the pitch changes, and never reword it incidentally. `## Product` carries one line per topic page (the homepage `#docs` grid it used to mirror was deleted by [#789](https://github.com/bablsoft/accessflow/issues/789)), `## Docs` the on-site chapters plus `/ai-agents/`, `## Engineering reference` the raw-markdown URLs. `/#install` and `/#questions` under `## Optional` depend on those two ids staying alive on `/`. Update in the same change set as the pitch, supported-DB list, docs chapters, or any new page |

---

## File layout

```
website/
├── index.html       # Marketing site — single-page, all sections inline
├── styles.css       # Hi-tech dark theme — Geist + Geist Mono, OKLCH accents
├── app.js           # Vanilla JS: install tabs, copy buttons, how-it-works stepper
├── favicon.svg      # Brand mark (shared with frontend/public/favicon.svg)
├── og-image.png     # 1200×630 social-share image (Open Graph / Twitter Card)
├── robots.txt       # Crawler directives + sitemap pointer
├── sitemap.xml      # XML sitemap (homepage + topic pages + docs pages)
├── llms.txt         # llms.txt (llmstxt.org) — curated product overview + doc links for LLM agents
├── _headers         # Cloudflare asset headers — Cache-Control + security headers (see SEO)
├── .assetsignore    # Files in this folder that must NOT be published
├── wrangler.jsonc   # Cloudflare Workers static-assets deploy config
├── googlef4908e4bf779aae8.html  # Google Search Console site-verification token
├── db-icons/        # Connector logos, copied from connectors/<id>/logo.svg
├── ai-agents/       # Topic page — governed database access for AI agents
├── security/        # Topic page — credential storage, auth, audit (AF-783)
├── connectors/      # Topic page — the engine catalog, per-engine reference (AF-784)
├── use-cases/       # Topic page — the six persona use cases and their mocks (AF-785)
├── roadmap/         # Topic page — available-now grid, milestone context, planned band (AF-786)
├── features/        # Topic hub — all 12 capabilities, grouped by governance area (AF-787)
│   ├── database-access-governance/  # Deep dive — proxy, AI, review, grouping, lifecycle (AF-788)
│   ├── api-access-governance/       # Deep dive — connectors, catalog, masking (AF-788)
│   └── deployment-governance/       # Deep dive — pipelines, gate, freeze windows (AF-788)
├── docs/            # Public user documentation — one page per chapter
│   ├── index.html   #   hub: read-this-first, chapter index, legacy-anchor forwarder
│   ├── install/     #   Docker Compose / Helm / from source + first-run setup
│   ├── configuration/  # users-roles, datasources, connectors, review-workflows,
│   │                   # ai, auth, notifications, audit-compliance
│   ├── workflows/   #   end-user: submit, track, review, approve
│   └── iac/         #   Terraform / OpenTofu provider + CI Actions
├── images/
│   └── docs/        # Lossless WebP screenshots of SPA pages, light + dark per screen (9 are light-only)
└── README.md        # this file
```

`assets.directory` in `wrangler.jsonc` is `"."`, so **everything in this folder is served
publicly unless listed in `.assetsignore`.** `README.md` and `wrangler.jsonc` are excluded
there; add any future maintainer-only file to that list when you create it.

The marketing site at the root targets visitors evaluating AccessFlow. The `docs/` pages
target operators and admins who need step-by-step instructions for running and configuring
a deployment. Everything reuses `styles.css` and `app.js`.

### The docs are one page per chapter

They used to be a single 17,000-word `docs/index.html`. Google ranks URLs, not fragments, so
that one page could only ever compete for one query — and AI engines citing a source cite a
URL. Each chapter is now its own indexable page, with the cross-chapter sidebar built into
every file.

**Two rules when editing them:**

1. **Every in-app deep link is a contract.** `frontend/src/config/docs.ts` maps each anchor
   to the chapter that owns it, and `frontend/src/config/__tests__/docs.test.ts` fails if an
   anchor is missing from the chapter that claims it. Moving a section between chapters means
   updating that map in the same commit.
2. **`app.js` holds a PERMANENT legacy-anchor forwarder.** AccessFlow is self-hosted, so every
   already-released frontend links to the old `/docs/#cfg-<x>` form forever; those installs
   never update. `LEGACY_DOCS_ANCHORS` forwards all 55 old anchors to their new chapter, and
   the test asserts it agrees with `docs.ts`. Never delete it as "migration cruft".

Because there is no build step, the nav, `<head>`, and footer are duplicated across the
chapter files — a nav change is a 22-file edit (~170 KB of duplicated shell). That is the
deliberate trade for keeping this folder buildless.

Nothing can remove that edit cost without a build step, but the *risk* it creates — editing
21 files and missing the 22nd — is guarded.
[`frontend/src/config/__tests__/websitePages.test.ts`](../frontend/src/config/__tests__/websitePages.test.ts)
runs over **every** page, `index.html` and `ai-agents/` included, and fails CI unless each
shares a byte-identical nav (normalized for the active-link markers, which are asserted
separately) and footer, carries a self-referencing canonical with a matching `og:url`, keeps
one `<h1>` with no skipped levels, holds its description under 160 characters, has no
duplicate ids, no dead fragments and no unresolvable internal links, ships every asset it
references, declares no retired `FAQPage`/`HowTo` schema, and agrees with `sitemap.xml` in
both directions.
[`websiteDocs.test.ts`](../frontend/src/config/__tests__/websiteDocs.test.ts) keeps only what
is meaningless outside `docs/`: every chapter linked from every chapter sidebar, and
cross-chapter links resolving.

So: editing all 22 files is on you; forgetting one is on CI.

### The header nav is six page links

Since [#791](https://github.com/bablsoft/accessflow/issues/791) the header nav is **six real
page links** — `/features/`, `/connectors/`, `/use-cases/`, `/security/`, `/roadmap/`, `/docs/`
— not homepage fragments, so each one is a URL a crawler can follow and rank. `How it works`
and `Questions` moved to the footer.

There is deliberately **no dropdown**. Six items do not need one, and the version worth having
opens on click — which means new behaviour in `app.js` (click-outside, Escape, roving focus,
`aria-expanded`) shipped to all 22 pages, on a site whose CSP forbids inline script and which
has no build step to tree-shake it. The `/features/` sidebar is the discoverability affordance
instead, and unlike a click-driven menu it is plain markup a crawler already sees.

**Install access is breakpoint-sensitive — read the collapse ladder before touching either
half.** `styles.css` hides `.nav-right`'s ghost CTA below 1280px, the GitHub chip below 1140px
and the primary CTA below 520px, and folds `.nav-links` into the hamburger below 1024px. So on
a phone the mobile panel *is* the header nav, which is why it carries its own `Quick start`
entry to `/#install` below the divider, alongside the theme toggle. Removing that entry without
un-hiding a `.nav-right` CTA leaves phones with no route to the install command from the header
at all; `websitePages.test.ts` fails if it goes missing.

Each page marks its own nav item with `aria-current="page"` and `nav-link-active`, resolved by
**longest URL prefix**, so the three `/features/*` spokes light up `Features` and every
`/docs/**` chapter lights up `Docs`. `/` and `/ai-agents/` are not nav items and mark nothing.

No frameworks, no bundlers, no CDN runtime — **nothing is fetched from a third-party origin
at runtime.**

Geist and Geist Mono (SIL OFL 1.1) are vendored in `fonts/` rather than loaded from Google
Fonts, which removes two DNS+TLS handshakes from the critical path before first paint and
lets the CSP stay `default-src 'self'`. Both are *variable* fonts, so one file covers the
whole weight range — hence four files, not one per weight:

| File | Subset |
|---|---|
| `geist-latin.woff2`, `geist-latin-ext.woff2` | Geist, weights 300–700 |
| `geist-mono-latin.woff2`, `geist-mono-latin-ext.woff2` | Geist Mono, weights 400–600 |

Cyrillic, Vietnamese, and symbol subsets are deliberately not shipped (this site is
English-only). `@font-face` rules with matching `unicode-range` values live at the top of
`styles.css`; every page preloads `geist-latin.woff2`, and only that file. To
refresh or add a subset, pull the CSS from `fonts.googleapis.com/css2?family=Geist:...`
with a modern browser User-Agent, and download the `.woff2` URLs it returns.

---

## SEO

Every HTML page ships a full SEO meta block — canonical URL, Open Graph, Twitter Card,
`theme-color`, and a JSON-LD `@graph` (`SoftwareApplication` + `Organization` + `WebSite`
on the homepage; `TechArticle` + `BreadcrumbList` + `Organization` on each docs chapter, on
`/security/`, on `/ai-agents/` and on the three `/features/` spokes; `CollectionPage` + `ItemList` +
`BreadcrumbList` + `Organization` on the `/connectors/`, `/use-cases/` and `/features/`
catalog pages; plain `WebPage` + `BreadcrumbList` + `Organization` on `/roadmap/`). Only the
homepage declares `SoftwareApplication` — every other page references it as
`{"@id": ".../#software"}`. `BreadcrumbList` depth follows URL depth: two levels for a
top-level page, three for anything nested — the eleven `docs/` chapters
(AccessFlow → Documentation → the chapter) and the three `/features/` spokes
(AccessFlow → Features → the spoke).

### Regenerating og-image.png

`og:image` / `twitter:image` is `og-image.png` (1200×630, PNG, ~72 KB). **It is a
hand-built card, not a screenshot of the site, so nothing regenerates it automatically —
it will silently go stale.** It sat at "v1.0 · Open-source SQL proxy" long after the
product covered NoSQL, warehouses and API governance. Re-cut it whenever the version
badge, the hero headline, or the supported-engine list changes.

Build a throwaway `_og-template.html` in this folder (dark `#0A0D11` background, the
`@font-face` rules pointing at `/fonts/`, the wordmark + version pill + hero headline +
subtitle + a single non-wrapping row of connector chips), serve the folder so `/fonts/`
resolves, then:

```bash
python3 -m http.server 4173 &
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
  --window-size=1200,630 --screenshot=/tmp/og-raw.png \
  http://localhost:4173/_og-template.html
pngquant --quality=90-100 --speed 1 --force --output og-image.png /tmp/og-raw.png
rm _og-template.html
```

`pngquant` roughly halves the file at an RMSE of ~0.25 (imperceptible on flat design art)
and keeps it PNG — do not switch the card to WebP or JPEG, since social crawlers are least
surprising with PNG and JPEG rings around the headline text. Keep the output exactly
1200×630: the `og:image:width` / `og:image:height` meta tags assert those numbers.

All canonical / `og:url` values are hard-coded to `https://accessflow.io` — if
the deployed origin ever changes, search every HTML file plus `sitemap.xml`,
`robots.txt`, `llms.txt` and `.well-known/security.txt` and update in lockstep —
along with `frontend/src/config/docs.ts` and the tests that assert those literals.

### Provenance strip

The `#provenance` section above the footer is the site's E-E-A-T Trust signal: it answers
"who builds this and can we trust it" with facts a prospect can check, each linked to the
GitHub page that proves it.

**The counts are deliberately floors — keep them that way.** `900+ commits` and
`20+ tagged releases` stay true as the repo grows, so they cannot rot into a false claim
the way a hardcoded exact number would. Raise a floor only when it is comfortably passed.

`20+` counts **GA releases only** — at the time of writing 22 of the 36 `v*` tags were
pre-releases (`-beta.N`, `-rc.N`). Do not quote the raw tag count; it overstates by ~60%:

```bash
git tag --list 'v*' | grep -v '\-' | wc -l   # GA releases
```

No personal names are published here by choice. If that changes, an `About` with a named
maintainer is the strongest version of this signal — security reviewers look for a human.

### security.txt

`.well-known/security.txt` (RFC 9116) points researchers at GitHub private vulnerability
reporting.

Cloudflare Workers assets **does** upload dot-directories — verified in production, the file
returns 200 with `content-type: text/plain`. No workaround needed.

### Renewing it

`Expires` is mandatory under RFC 9116, and once that date passes the file is *invalid*, not
merely old: it keeps serving 200 while scanners and researcher tooling treat it as unusable.
That failure is completely silent, which makes an expired security.txt a worse signal than
none at all.

Nothing here renews it automatically, so
[`frontend/src/config/__tests__/websiteSecurityTxt.test.ts`](../frontend/src/config/__tests__/websiteSecurityTxt.test.ts)
is the alarm:

| Time to expiry | Behaviour |
|---|---|
| > 90 days | passes silently |
| 90 → 30 days | passes, prints a renewal warning in CI output |
| < 30 days | **fails CI** with the file path and what to change |
| expired | **fails CI**, reporting how many days ago |
| set > 1 year out | **fails CI** — RFC 9116 §2.5.5 recommends under a year, and this blocks "fixing" it by setting a date in 2099 |

To renew: set `Expires` to one year from today **and** confirm the `Contact` URL still
accepts reports — the date is a claim that the contact information is current, so bumping it
without checking is the thing this guard exists to discourage.

**Residual gap:** the guard only fires when CI runs. That is often enough for an active
repo, but if development goes quiet for months the date could pass unnoticed. A scheduled
workflow that opens an issue would close that gap; the repo has no cron workflows today, so
this was left as a deliberate trade rather than new machinery.

`robots.txt` allows all crawlers and points to `sitemap.xml`. `sitemap.xml` lists every
HTML page — the homepage, each topic page, the docs hub, and one entry per docs chapter — so
a new page of any kind needs a new `<url>` block. `websitePages.test.ts` checks that mapping
in both directions, so a page without an entry (or an entry without a page) fails CI.

`<priority>` is pinned per URL by the same test, because a flat sitemap is the one SEO
regression nothing on the page would show you. The tiers: `/` alone at 1.0; the three hubs a
visitor starts from (`/features/`, `/ai-agents/`, `/docs/`) at 0.9; the six topic pages, plus
`/docs/install/` and `/docs/workflows/` — the two chapters every reader opens — at 0.8; the
remaining nine chapters, the eight under `/docs/configuration/` and `/docs/iac/`, at 0.7,
since each answers a question only some deployments ask; `/roadmap/` at 0.6, since it reports
status rather than competing for a query. A new page fails CI until someone picks its tier in
`SITEMAP_PRIORITY`.

Meta descriptions must stay **≤ 160 rendered characters** — past that Google truncates the
tag and usually substitutes its own snippet. Whenever you edit content, bump all three
published dates together — `<lastmod>` in `sitemap.xml`, `dateModified` in the page's
JSON-LD, and on a docs chapter the visible `<time datetime>` — because all three are
hand-maintained in a folder with no build step. `websitePages.test.ts` fails when they
disagree, but nothing can tell you an agreed date is stale. Do not add `HowTo` schema
(deprecated 2023) or `FAQPage` (Google retired FAQ rich results for all sites in May 2026).

### Response headers

Cloudflare's default for static assets is `Cache-Control: public, max-age=0,
must-revalidate` on *everything*, which makes repeat visitors revalidate every asset on
every navigation. `_headers` overrides that:

| Path | Cache-Control | Why |
|---|---|---|
| `/db-icons/*`, `/favicon.svg` | 1 year, `immutable` | Vendor logos — effectively static |
| `/fonts/*` | 1 year, `immutable` | Subsetted Geist woff2 — content-stable, and `geist-latin.woff2` is preloaded on every page |
| `/images/*`, `/og-image.png` | 7 days | Screenshots are regenerated **under the same filenames** at release time, so `immutable` would strand viewers on a stale image |
| `/styles.css`, `/app.js` | 1 hour, `must-revalidate` | Unhashed filenames; any site edit changes them in place |

`_headers` also sets HSTS, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`,
`Permissions-Policy`, and a `Content-Security-Policy` on `/*`. The file is parsed as config
by the assets runtime and is never served — **do not add it to `.assetsignore`**, which
would stop it uploading and silently disable every rule.

### Regenerating the CSP script hash

`script-src` carries a `sha256-` hash of the inline theme-bootstrap script (the one that
reads `localStorage` before first paint so the page doesn't flash the wrong theme). It is
byte-identical across every page, so one hash covers them all. Editing that script by even
one character invalidates the hash, and the browser then silently blocks it.

**You do not have to remember this.**
[`frontend/src/config/__tests__/websiteCsp.test.ts`](../frontend/src/config/__tests__/websiteCsp.test.ts)
hashes every inline script in this folder and fails if `_headers` does not allow it — so
CI catches the drift even though `website/` has no build step or test runner of its own.
The failure message prints the replacement hash; paste it into the
`Content-Security-Policy` line in `_headers`.

That test also asserts the policy never regains `'unsafe-inline'` / `'unsafe-eval'` on
`script-src` and never reintroduces a third-party font origin.

`<script type=\"application/ld+json\">` blocks need no hash — browsers never execute
non-JavaScript MIME types, so `script-src` does not apply to them.

#### Why style-src keeps 'unsafe-inline'

36 multi-declaration inline `style=""` attributes remain site-wide (the single-declaration
colour and `margin-left` ones were replaced by the `.t-*` / `.ml-auto` utilities, and the
sidebar-less prose column by `.wrap-narrow`).
`websitePages.test.ts` pins that number and ratchets it down — it is the one place to read it. Dropping the
directive is **all-or-nothing** — one leftover inline style and it has to stay — so the
partial cleanup bought readability, not a tighter policy.

Leaving it is a considered call, not an oversight. CSS injection needs an injection vector,
and this site has no forms, no query-param rendering, no user content and no CMS: it is
static HTML served from git. Anyone able to inject markup here already has repo or deploy
access, at which point CSP is not the control that matters.

**Revisit if that stops being true** — if the site ever renders user input, a URL parameter,
or third-party content, finish the sweep and tighten to `style-src 'self'`. The other reason
to finish it is cosmetic-but-real: scanners such as Mozilla Observatory dock points for
`'unsafe-inline'`, and this is a security product's own site.

Fonts are self-hosted in `fonts/` precisely so this policy can stay `'self'` — see below.

### Product screenshots

`images/docs/` holds **lossless WebP**, not PNG — pixel-identical to a PNG screenshot but
~70% smaller — 3.2 MB across 85 files. They are written by
[`e2e/screenshots/capture.ts`](../e2e/screenshots/capture.ts), which encodes through `sharp`
because Playwright can only emit PNG/JPEG. **That script is the only writer** — do not add
PNGs alongside, or the two formats will drift and the site will serve stale screenshots.

The directory is named `docs/` for historical reasons; since
[#790](https://github.com/bablsoft/accessflow/issues/790) the marketing pages draw from it too,
one figure each:

| Page | Section | Base name |
|---|---|---|
| `/` | `#proof` | `dashboard` |
| `/features/` | `#database` | `review-plans-create` |
| `/features/database-access-governance/` | `#review` | `routing-policies` |
| `/features/api-access-governance/` | `#connectors` | `api-connectors-list` |
| `/features/deployment-governance/` | `#gate` | `deployment-detail` |
| `/security/` | `#audit` | `audit-log` |
| `/use-cases/` | `#sre` | `access-requests-queue` |

Each screen has a `-light` and `-dark` variant wrapped in a `<picture>`, referenced by
root-absolute path so the same markup works from any directory depth:

```html
<figure class="docs-figure">
  <picture>
    <source srcset="/images/docs/foo-light.webp" media="(prefers-color-scheme: light)" />
    <img src="/images/docs/foo-dark.webp" alt="…" loading="lazy" width="1440" height="900" />
  </picture>
  <figcaption><code>/route</code> — what the reader is looking at.</figcaption>
</figure>
```

`prefers-color-scheme` covers the default case; the site's own theme toggle is handled by
`swapDocsImages()` in `app.js`. That function must rewrite **both** the `<source srcset>`
and the `<img src>` — when a `<source>` media query matches, it wins over `img.src`, so
rewriting `img.src` alone leaves the toggle silently broken for visitors on a light-themed
OS. Keep every image inside a `<picture>` with that exact `-light` / `-dark` naming, or the
swap will not find it.

**Only reference a base name that has both twins.** The rewrite is unconditional, so a
light-only screenshot 404s the moment a visitor toggles to dark. Nine base names are light-only
today — regenerating them is
[#798](https://github.com/bablsoft/accessflow/issues/798), and until it lands the ones already
referenced from `/docs/**` sit in `MISSING_DARK_SCREENSHOTS` in
[`websitePages.test.ts`](../frontend/src/config/__tests__/websitePages.test.ts). That test fails
on any *new* light-only reference; do not add to the allowlist to get past it.

---

## Deployment

Live at <https://accessflow.io/>, served as **Cloudflare Workers static assets**
per `wrangler.jsonc` (`assets.directory: "."`, no Worker `main` — pure static). Deploy with
`wrangler deploy` from this folder. The repo's `gh-pages` branch is unrelated and stays
reserved for the Helm chart index.

Because there is no Worker script, `_headers` governs every response header; if a Worker
`main` is ever added, note that Cloudflare does **not** apply `_headers` to Worker-generated
responses — those must set headers in code.

Any other static host works too (Netlify, Vercel, S3 + CloudFront — no build command), but
`_headers` and `.assetsignore` are Cloudflare-specific and would need porting.
- **Nginx / Caddy** — serve the directory directly.

Whichever target you pick, the only runtime requirement is a static-file server.
