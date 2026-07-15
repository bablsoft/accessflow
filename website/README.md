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

| Source of truth | Website section |
|---|---|
| [`README.md`](../README.md) (pitch, quick start) | Hero, Install tabs, terminal preview |
| [`docs/02-architecture.md`](../docs/02-architecture.md) | Architecture diagram |
| [`backend/pom.xml`](../backend/pom.xml), [`frontend/package.json`](../frontend/package.json) | Architecture callouts, From-source toolchain versions in Install tab |
| (no upstream — copy lives in the website) | System requirements panel sizing tiers (Evaluation / Production) |
| [`docs/07-security.md`](../docs/07-security.md) | "Workforce-ready auth" feature tile |
| [`docs/08-notifications.md`](../docs/08-notifications.md), [`docs/05-backend.md`](../docs/05-backend.md) "JIT time-bound access requests" + [`docs/07-security.md`](../docs/07-security.md) | "Configurable review workflows" feature tile (incl. JIT access-request blurb) |
| [`docs/05-backend.md`](../docs/05-backend.md) "JIT time-bound access requests", [`docs/07-security.md`](../docs/07-security.md) JIT section, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_ACCESS_*` env vars | "Just-in-time (JIT) access requests" subsection (`#cfg-access-requests`) + RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Dynamic data masking policies", [`docs/07-security.md`](../docs/07-security.md) masking section, [`docs/03-data-model.md`](../docs/03-data-model.md) `masking_policy` | "Dynamic data masking" blurb in the "Full query proxy — SQL and NoSQL" feature tile (homepage) + "Masking policies" paragraph under "Datasources" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Row-level security policies", [`docs/07-security.md`](../docs/07-security.md) row-security section, [`docs/03-data-model.md`](../docs/03-data-model.md) `row_security_policy` / `users.attributes`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/row-security-policies` | "Row-level security" blurb in the "Full query proxy — SQL and NoSQL" feature tile (homepage) + "Row security policies" paragraph under "Datasources" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) data-classification tags &amp; auto-derived masking (AF-518), [`docs/03-data-model.md`](../docs/03-data-model.md) `data_classification`, [`frontend/src/pages/admin/DataClassificationsPage.tsx`](../frontend/src/pages/admin/DataClassificationsPage.tsx) | "Data classification" subsection (`#cfg-data-classifications`) under "Datasources" in [`docs/index.html`](docs/index.html) |
| [`frontend/src/i18n.ts`](../frontend/src/i18n.ts) `SUPPORTED_LANGUAGES` / `LANGUAGE_DISPLAY_NAMES`, [`frontend/src/pages/admin/LanguagesConfigPage.tsx`](../frontend/src/pages/admin/LanguagesConfigPage.tsx), [`docs/06-frontend.md`](../docs/06-frontend.md) i18n section, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/localization-config` | "Languages &amp; localization" section (`#cfg-languages`) under Configuration in [`docs/index.html`](docs/index.html) |
| [`frontend/src/config/docs.ts`](../frontend/src/config/docs.ts) (`DOCS_BASE_URL` / `DOCS_ANCHORS`), [`frontend/src/components/common/PageHeader.tsx`](../frontend/src/components/common/PageHeader.tsx) `docsAnchor` prop, [`docs/06-frontend.md`](../docs/06-frontend.md) "Contextual docs links" | The anchor `id`s under Configuration in [`docs/index.html`](docs/index.html) — every one is the target of an in-app **View docs** link, so **renaming or removing an anchor breaks that link**. `frontend/src/config/__tests__/docs.test.ts` fails when a declared anchor has no matching `id` |
| [`docs/05-backend.md`](../docs/05-backend.md) "Policy-as-code routing engine", [`docs/03-data-model.md`](../docs/03-data-model.md) `routing_policy` / `routing_decision`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/routing-policies` | "Policy-as-code routing" blurb in the "Configurable review workflows" feature tile (homepage) + "Routing policies" section under Configuration in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Langfuse integration", [`docs/03-data-model.md`](../docs/03-data-model.md) `langfuse_config` / `ai_config.langfuse_prompt_*`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/langfuse-config`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_LANGFUSE_*` | "Langfuse integration" blurb in the "AI query analysis" feature tile + `Langfuse` tag (homepage) + "Langfuse integration" subsection (`#cfg-langfuse`) + `langfuse-config` (light + dark) figure under "AI configurations" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "RAG knowledge base", [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.rag_*`/`embedding_*` + `knowledge_document` + `vector_store`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/ai-configs/{id}/knowledge-documents` + `/rag/test`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_RAG_*` + "pgvector for RAG" | "RAG knowledge base" blurb + `RAG` tag in the "AI query analysis" feature tile (homepage) + "RAG knowledge base" paragraph, `ai-configs-rag` (light + dark) figure, and pgvector callout under "AI configurations" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "System Prompt Template" (`optimizations`), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_analyses.optimizations` + `query_requests.submission_reason`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/queries/analyze` + `/queries` `submission_reason` (AF-451) | "dialect-aware index recommendations and query rewrites … Apply as draft" blurb + `Optimization suggestions` tag in the "AI query analysis" feature tile (homepage) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Multi-model orchestration, voting & guardrails" (AF-450), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.orchestration_*`/`voting_*`/`guardrail_patterns` + `ai_config_model` + `ai_analysis_model_result`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/ai-configs` orchestration/guardrail fields + `/admin/ai-analyses/stats` `per_model_stats` | "Run several models at once … voting … guardrails" blurb + `Multi-model voting` / `Guardrails` / `Per-model cost & latency` tags in the "AI query analysis" feature tile + "Multi-model AI orchestration, voting & guardrails" item in the v2 roadmap group (homepage) + "Multi-model orchestration & voting" / "Guardrails" paragraphs under "AI configurations" and the per-model cost/latency note under "AI analyses dashboard" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Observability and tracing" (OTLP export, instrumented spans, metrics &amp; Grafana dashboards — AF-454), [`docs/09-deployment.md`](../docs/09-deployment.md) Observability env-var table (`OTEL_EXPORTER_OTLP_*`) + "Prometheus metrics &amp; Grafana dashboards", [`charts/accessflow/examples/values-observability.yaml`](../charts/accessflow/examples/values-observability.yaml) | "Observability" architecture callout + "Observability" feature blurb (homepage) + structured-logs and "Tracing &amp; metrics" notes under "Docker Compose" in [`docs/index.html`](docs/index.html) |
| [`docs/14-connectors.md`](../docs/14-connectors.md), [`connectors/`](../connectors/) catalog (incl. `connectors/mongodb/`, `connectors/couchbase/`, `connectors/redis/`, `connectors/cassandra/`, `connectors/scylladb/`, `connectors/elasticsearch/`, `connectors/opensearch/`, `connectors/dynamodb/`, `connectors/neo4j/`), [`docs/04-api-spec.md`](../docs/04-api-spec.md) Connector endpoints + `category`, [`docs/03-data-model.md`](../docs/03-data-model.md) `datasources.connector_id`/`db_type` | Dedicated **"Connectors" section** (homepage, `#connectors`) — **SQL / NoSQL grouped** logo grid of the catalog (logos in [`db-icons/`](db-icons/), copied from `connectors/<id>/logo.svg`, incl. `mongodb.svg`, `couchbase.svg`, `redis.svg`, `cassandra.svg`, `scylladb.svg`, `elasticsearch.svg`, `opensearch.svg`, `dynamodb.svg`, `neo4j.svg`); "Connector catalog" feature tile; ClickHouse + MongoDB + "connector catalog" in supported-DB strips (architecture diagram, proxy tag, tech-stack targets); "Declarative connector catalog" item in the v2 roadmap group; + the "Connectors" section (built-in SQL + NoSQL connector lists + install how-to) under Configuration in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "MongoDB engine" + "Couchbase engine" + "Redis engine" + "Cassandra engine" + "Elasticsearch engine" + "DynamoDB engine" + "Neo4j engine", [`docs/14-connectors.md`](../docs/14-connectors.md), [`docs/06-frontend.md`](../docs/06-frontend.md) editor/results, [`connectors/mongodb/`](../connectors/mongodb/), [`connectors/couchbase/`](../connectors/couchbase/), [`connectors/redis/`](../connectors/redis/), [`connectors/cassandra/`](../connectors/cassandra/), [`connectors/scylladb/`](../connectors/scylladb/), [`connectors/elasticsearch/`](../connectors/elasticsearch/), [`connectors/opensearch/`](../connectors/opensearch/), [`connectors/dynamodb/`](../connectors/dynamodb/), [`connectors/neo4j/`](../connectors/neo4j/) | **MongoDB + Couchbase + Redis + Cassandra + ScyllaDB + Elasticsearch + OpenSearch + Amazon DynamoDB + Neo4j (NoSQL)** copy — NoSQL connector cards (homepage `#connectors`), "Full query proxy — SQL and NoSQL" feature tile (homepage), all NoSQL engines in supported-DB strips + meta description, and the "Built-in connectors — NoSQL" subsection under "Connectors" in [`docs/index.html`](docs/index.html) |
| [`docs/03-data-model.md`](../docs/03-data-model.md) `organizations` (`disabled`/`max_*`) + `users.platform_admin`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Platform Organizations" + `platform_admin` on the login/`/me` user object, [`docs/07-security.md`](../docs/07-security.md) "Platform admin" + "Multi-tenant isolation", [`docs/05-backend.md`](../docs/05-backend.md) "Multi-tenant isolation hardening" (AF-456) | "Multi-tenant orgs · Per-org quotas" blurb + tags in the "Workforce-ready auth" feature tile + "Multi-tenant orgs &amp; per-org quotas" item in the v2 roadmap group (homepage) + the "Organizations &amp; quotas" section and the platform-admin note under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Query snapshots &amp; replay" (AF-449), [`docs/03-data-model.md`](../docs/03-data-model.md) `query_snapshots`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `POST /queries/{id}/replay` | "immutable, sanitized snapshot … Replay in test environment" blurb in the "Full query proxy — SQL and NoSQL" governance feature tile + the "Version history &amp; diff · dry-run sandbox · replay" item in the v2 roadmap group (homepage) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Compliance reporting" (AF-459), [`docs/07-security.md`](../docs/07-security.md) "Compliance reporting &amp; signed exports" + AUDITOR role matrix, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Compliance Reporting", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_COMPLIANCE_*` env vars | "Tamper-evident audit &amp; compliance reports" feature tile + "Compliance reports &amp; signed exports" item in the v2 roadmap group (homepage) + the "Compliance reports &amp; signed exports" subsection and AUDITOR row under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Behavioural anomaly detection (UBA)" (AF-383), [`docs/03-data-model.md`](../docs/03-data-model.md) `behavior_baseline` / `behavior_anomaly`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Behavioural Anomaly Detection (UBA)", [`docs/08-notifications.md`](../docs/08-notifications.md) `ANOMALY_DETECTED`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_AI_ANOMALY_*` env vars | "behavioral anomaly detection (UBA)" blurb + `Anomaly detection (UBA)` tag in the "AI query analysis" feature tile + "Behavioral anomaly detection (UBA)" item in the v2 roadmap group (homepage) + the "Behavioural anomaly detection (UBA)" subsection and anomaly RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Break-glass / emergency access" (AF-385), [`docs/03-data-model.md`](../docs/03-data-model.md) `break_glass_events` / `can_break_glass`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/queries/break-glass` + `/admin/break-glass`, [`docs/07-security.md`](../docs/07-security.md) "Break-glass / emergency access", [`docs/08-notifications.md`](../docs/08-notifications.md) `BREAK_GLASS_EXECUTED` | "break-glass / emergency access" blurb + `Break-glass emergency access` tag in the "Configurable review workflows" feature tile + "Break-glass emergency access" item in the v2 roadmap group (homepage) + the "Break-glass / emergency access" subsection (`#cfg-break-glass`) and break-glass RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Personalized dashboard" (AF-498), [`docs/06-frontend.md`](../docs/06-frontend.md) DashboardPage, [`docs/03-data-model.md`](../docs/03-data-model.md) `dashboard_suggestion_state` / `dashboard_digest_subscription`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Personalized Dashboard", [`docs/08-notifications.md`](../docs/08-notifications.md) `WEEKLY_DIGEST`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_DASHBOARD_WEEKLY_DIGEST_*` env vars | "Personalized dashboard" feature tile + "Personalized dashboard &amp; weekly digest" item in the v2 roadmap group (homepage) + the "Personalized dashboard &amp; weekly digest" subsection under "End-user workflows" in [`docs/index.html`](docs/index.html) |
| [`docs/06-frontend.md`](../docs/06-frontend.md) "Progressive Web App & Web Push" (AF-444), [`docs/08-notifications.md`](../docs/08-notifications.md) "Web Push", [`docs/05-backend.md`](../docs/05-backend.md) "Step-up auth and the one-tap push decide path", [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/auth/step-up` + `/reviews/{id}/decide` + Web Push endpoints, [`docs/03-data-model.md`](../docs/03-data-model.md) `push_subscriptions` / `push_vapid_config`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_PUSH_VAPID_*` + `ACCESSFLOW_SECURITY_STEP_UP_TTL` | "mobile PWA … one-tap from a push notification" blurb + `Mobile PWA` / `One-tap push approvals` tags in the "Configurable review workflows" feature tile + "Mobile PWA + one-tap push" item in the v2 roadmap group (homepage) + the "Mobile approvals &amp; one-tap push" subsection (incl. VAPID / step-up env vars) under "End-user workflows" in [`docs/index.html`](docs/index.html) |
| [`docs/16-iac.md`](../docs/16-iac.md), [`terraform-provider/`](../terraform-provider/), [`.github/actions/`](../.github/actions/), [`ci-templates/`](../ci-templates/), [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS_*` (AF-452) | "Infrastructure as Code" feature tile + "Automation &amp; IaC" v2 roadmap group + docs grid card (homepage) + the "Infrastructure as Code (Terraform / OpenTofu &amp; CI Actions)" section (`#iac`, incl. service-account env vars + HCL example) and further-reading link in [`docs/index.html`](docs/index.html) |
| [`docs/17-api-governance.md`](../docs/17-api-governance.md), [`docs/03-data-model.md`](../docs/03-data-model.md) `api_connectors` / `api_schemas` / `api_connector_user_permissions`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "API Access Governance", [`docs/05-backend.md`](../docs/05-backend.md) "API Access Governance (apigov module)" (AF-500) | "API Access Governance" feature tile + docs grid card (homepage) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Data Lifecycle Manager" (AF-499), [`docs/03-data-model.md`](../docs/03-data-model.md) `retention_policies` / `deletion_requests` / `lifecycle_runs`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Data Lifecycle Manager", [`docs/06-frontend.md`](../docs/06-frontend.md) lifecycle pages, [`docs/07-security.md`](../docs/07-security.md) "Lifecycle pseudonymization & salt rotation", [`docs/08-notifications.md`](../docs/08-notifications.md) `ERASURE_APPROVED`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_LIFECYCLE_*` env vars | "Data lifecycle &amp; right-to-erasure" feature tile (homepage) + the "Data lifecycle &amp; right-to-erasure" subsection (`#cfg-lifecycle`, incl. scan/erasure env vars) under "Configuration" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Request chaining & grouping" (AF-501), [`docs/03-data-model.md`](../docs/03-data-model.md) `request_groups` / `request_group_items` / `group_review_decisions`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Request chaining & grouping", [`docs/06-frontend.md`](../docs/06-frontend.md) "Request chaining & grouping pages", [`docs/07-security.md`](../docs/07-security.md) "Request chaining & grouping security", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_REQUESTGROUPS_*` env vars | "Request chaining &amp; grouping" feature tile + "Request chaining &amp; grouping" item in the v2 roadmap group (homepage) + the "Request chaining &amp; grouping" subsection (`#flow-request-groups`, incl. run/timeout env vars) under "End-user workflows" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Access recertification / attestation campaigns" (AF-384), [`docs/03-data-model.md`](../docs/03-data-model.md) `attestation_campaigns` / `attestation_items`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Attestation", [`docs/06-frontend.md`](../docs/06-frontend.md) CampaignListPage / AttestationWorklistPage, [`docs/07-security.md`](../docs/07-security.md) attestation self-review, [`docs/08-notifications.md`](../docs/08-notifications.md) `ATTESTATION_*`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_ATTESTATION_*` env vars | "Access recertification campaigns" item in the v2 roadmap group + Compliance card (homepage) + the "Access recertification campaigns" subsection (`#cfg-attestation`, incl. open/close/evidence env vars) and `attestation-campaigns` (light + dark) figure under "Configuration" in [`docs/index.html`](docs/index.html) |
| [`docs/02-architecture.md`](../docs/02-architecture.md), [`docs/05-backend.md`](../docs/05-backend.md) (JIT access, break-glass AF-385, routing policies AF-379, masking, lifecycle AF-499, attestation AF-384, compliance AF-459, apigov AF-500/AF-518), [`docs/07-security.md`](../docs/07-security.md), [`docs/17-api-governance.md`](../docs/17-api-governance.md) | **"Use cases" section** (homepage, `#use-cases`) — six enterprise use-case rows (shared credentials, JIT + break-glass, AI pre-screened review, audit readiness, data privacy operations, API governance); each claim maps to a shipped capability in the listed chapters |
| [`docs/07-security.md`](../docs/07-security.md) "External secret stores" (AF-448), [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_SECRETS_*` env vars, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/secret-providers` | "Encryption" security spec row + credential bullet in the proxy feature tile + "External secrets managers" item in the roadmap Planned group (homepage) + the "External secrets managers" note under "Docker Compose" and the secret-reference sentence under "Datasources → Connection details" in [`docs/index.html`](docs/index.html) |
| [`docs/12-roadmap.md`](../docs/12-roadmap.md) | Roadmap track |
| [`docs/`](../docs/) chapter filenames + H1s (01–17, incl. [`docs/14-connectors.md`](../docs/14-connectors.md), the [`docs/15-engine-sdk.md`](../docs/15-engine-sdk.md) engine-author guide, the [`docs/16-iac.md`](../docs/16-iac.md) IaC guide, and the [`docs/17-api-governance.md`](../docs/17-api-governance.md) API-governance guide) | Docs grid cards |
| [`CLAUDE.md`](../CLAUDE.md) (supported db list, env-var defaults) | Hero meta strip, Features tags |
| [`charts/accessflow/`](../charts/accessflow/) | Helm install tab |
| [`README.md`](../README.md) quick start + [`docs/05-backend.md`](../docs/05-backend.md), [`docs/07-security.md`](../docs/07-security.md), [`docs/08-notifications.md`](../docs/08-notifications.md), [`docs/09-deployment.md`](../docs/09-deployment.md) | [`docs/index.html`](docs/index.html) — user documentation page (run + configure) |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) pre-release handling, [`docs/09-deployment.md`](../docs/09-deployment.md) "Installing a pre-release / beta build", [`docs/11-development.md`](../docs/11-development.md) "Pre-release (beta) builds" | "Beta / pre-release channel" subsection (`#run-beta`) under "Running AccessFlow" in [`docs/index.html`](docs/index.html) |
| [`frontend/src/pages/admin/`](../frontend/src/pages/admin/), [`frontend/src/pages/datasources/`](../frontend/src/pages/datasources/) — admin SPA pages | [`docs/index.html`](docs/index.html) configuration walkthroughs (Users, User groups, Datasources + Schema explorer (searchable object tree + RLS/masking-aware sample-data previews, AF-443; see [`docs/05-backend.md`](../docs/05-backend.md) "Sample data path" + [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/sample-rows`) + ER diagram + Masking + Row security tabs, Connectors, Custom JDBC drivers, Review plans + templates, Routing policies, Access requests queue, AI configs + Langfuse + RAG, AI analyses dashboard, Datasource health, Organizations (multi-tenant management + quotas), Auditor dashboard, Anomalies (UBA), Break-glass log, Notifications, System SMTP, OAuth, SAML, Slack app, Audit log) + matching PNGs under [`images/docs/`](images/docs/) (incl. `routing-policies`, `access-requests-queue`, `datasources-masking`, `datasources-row-security`, `langfuse-config`, `ai-configs-rag`, `organizations-list`, `auditor-dashboard`, `anomalies-dashboard`, `break-glass-log`, `dashboard`, `attestation-campaigns`, `api-connectors-list`, `lifecycle-policies`, light + dark) |
| [`frontend/src/pages/editor/QueryEditorPage.tsx`](../frontend/src/pages/editor/QueryEditorPage.tsx), [`frontend/src/pages/reviews/ReviewQueuePage.tsx`](../frontend/src/pages/reviews/ReviewQueuePage.tsx) — end-user SPA pages | [`docs/index.html`](docs/index.html) "End-user workflows" section (Submitting / Scheduling a query, Drafting queries from natural language, Query templates library, Reviewing &amp; bulk approval) + matching PNGs under [`images/docs/`](images/docs/) (`editor-light`, `editor-text-to-sql-light`, `editor-schedule-light`, `editor-query-templates-light`, `reviews-queue-bulk-light`, `request-groups-list-light`, `api-requests-list-light`) |
| Existing on-page copy (hero, features, supported DBs, license) | SEO meta block (canonical, OG, Twitter, JSON-LD) in both [`index.html`](index.html) and [`docs/index.html`](docs/index.html) |

---

## File layout

```
website/
├── index.html      # Marketing site — single-page, all sections inline
├── styles.css      # Hi-tech dark theme — Geist + Geist Mono, OKLCH accents
├── app.js          # Vanilla JS: install tabs, copy buttons, how-it-works stepper
├── favicon.svg     # Brand mark (shared with frontend/public/favicon.svg)
├── og-image.png    # 1200×630 social-share image (Open Graph / Twitter Card)
├── robots.txt      # Crawler directives + sitemap pointer
├── sitemap.xml     # XML sitemap (homepage + docs page)
├── docs/
│   └── index.html  # Public user documentation — run + configure (sidebar TOC)
├── images/
│   └── docs/       # PNG screenshots of admin SPA pages, light + dark per screen
└── README.md       # this file
```

The marketing site at the root targets visitors evaluating AccessFlow. The
`docs/index.html` page targets operators and admins who need step-by-step instructions
for running and configuring a deployment. Both reuse `styles.css` and `app.js`.

No frameworks, no bundlers, no CDN runtime. The Geist + Geist Mono fonts load from
Google Fonts; everything else is local.

---

## SEO

Both HTML pages ship a full SEO meta block — canonical URL, Open Graph, Twitter Card,
`theme-color`, and a single JSON-LD `@graph` (`SoftwareApplication` + `Organization` +
`WebSite` on the homepage; `TechArticle` + `BreadcrumbList` on the docs page). The
`og:image` / `twitter:image` is `og-image.png` (1200×630, PNG, ~143 KB), regenerable from
a one-off HTML template via headless Chrome — re-create the template and run

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless --disable-gpu --hide-scrollbars --window-size=1200,630 \
  --screenshot=og-image.png http://localhost:4173/<template>.html
```

(then delete the template). All canonical / `og:url` values are hard-coded to
`https://accessflow.bablsoft.com` — if the deployed origin ever changes, search both
HTML files plus `sitemap.xml` and `robots.txt` and update in lockstep.

`robots.txt` allows all crawlers and points to `sitemap.xml`. `sitemap.xml` lists the
two HTML pages (`/` and `/docs/`).

---

## Deployment

Out of scope for this folder — the repo's existing `gh-pages` branch is reserved for the
Helm chart index. When you're ready to publish:

- **GitHub Pages** — add a workflow that uploads `website/` to a separate Pages
  environment, or to a path that does not collide with `index.yaml`.
- **Netlify / Vercel / Cloudflare Pages** — point a site at this folder, no build command.
- **S3 + CloudFront** — sync the folder, set `index.html` as the index document.
- **Nginx / Caddy** — serve the directory directly.

Whichever target you pick, the only runtime requirement is a static-file server.
