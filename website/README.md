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
| [`docs/08-notifications.md`](../docs/08-notifications.md) "ServiceNow" / "Jira" / "Ticketing inbound webhooks & bi-directional sync" (AF-453), [`docs/03-data-model.md`](../docs/03-data-model.md) `query_tickets`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Ticketing Integration Endpoints", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_NOTIFICATIONS_TICKETING_SIGNATURE_TOLERANCE` | ServiceNow &amp; Jira mentions in the "Configurable review workflows" tile blurb/tags + "Notify on every channel" panel + integrations docs card + "ServiceNow &amp; Jira ticketing" item in the Planned roadmap group (homepage) + ServiceNow / Jira bullets, bi-directional-sync step, and encrypted-fields list under "Notification channels" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "JIT time-bound access requests", [`docs/07-security.md`](../docs/07-security.md) JIT section, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_ACCESS_*` env vars | "Just-in-time (JIT) access requests" subsection (`#cfg-access-requests`) + RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Dynamic data masking policies", [`docs/07-security.md`](../docs/07-security.md) masking section, [`docs/03-data-model.md`](../docs/03-data-model.md) `masking_policy` | "Dynamic data masking" blurb in the "Full query proxy — SQL and NoSQL" feature tile (homepage) + "Masking policies" paragraph under "Datasources" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Row-level security policies", [`docs/07-security.md`](../docs/07-security.md) row-security section, [`docs/03-data-model.md`](../docs/03-data-model.md) `row_security_policy` / `users.attributes`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/row-security-policies` | "Row-level security" blurb in the "Full query proxy — SQL and NoSQL" feature tile (homepage) + "Row security policies" paragraph under "Datasources" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) data-classification tags &amp; auto-derived masking (AF-518), [`docs/03-data-model.md`](../docs/03-data-model.md) `data_classification`, [`frontend/src/pages/admin/DataClassificationsPage.tsx`](../frontend/src/pages/admin/DataClassificationsPage.tsx) | "Data classification" subsection (`#cfg-data-classifications`) under "Datasources" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Automated sensitive-data discovery" (AF-623), [`docs/03-data-model.md`](../docs/03-data-model.md) `discovery_scan_config` / `discovery_finding`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_DISCOVERY_*` env vars | "Automated sensitive-data discovery" blurb in the "Full query proxy — SQL and NoSQL" feature tile + "Automated sensitive-data discovery" item in the Planned roadmap group (homepage) + "Automated discovery" paragraph under "Data classification" in [`docs/index.html`](docs/index.html) |
| [`frontend/src/i18n.ts`](../frontend/src/i18n.ts) `SUPPORTED_LANGUAGES` / `LANGUAGE_DISPLAY_NAMES`, [`frontend/src/pages/admin/LanguagesConfigPage.tsx`](../frontend/src/pages/admin/LanguagesConfigPage.tsx), [`docs/06-frontend.md`](../docs/06-frontend.md) i18n section, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/localization-config` | "Languages &amp; localization" section (`#cfg-languages`) under Configuration in [`docs/index.html`](docs/index.html) |
| [`frontend/src/config/docs.ts`](../frontend/src/config/docs.ts) (`DOCS_BASE_URL` / `DOCS_ANCHORS`), [`frontend/src/components/common/PageHeader.tsx`](../frontend/src/components/common/PageHeader.tsx) `docsAnchor` prop, [`docs/06-frontend.md`](../docs/06-frontend.md) "Contextual docs links" | The anchor `id`s under Configuration in [`docs/index.html`](docs/index.html) — every one is the target of an in-app **View docs** link, so **renaming or removing an anchor breaks that link**. `frontend/src/config/__tests__/docs.test.ts` fails when a declared anchor has no matching `id` |
| [`docs/05-backend.md`](../docs/05-backend.md) "Policy-as-code routing engine", [`docs/03-data-model.md`](../docs/03-data-model.md) `routing_policy` / `routing_decision`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/routing-policies` | "Policy-as-code routing" blurb in the "Configurable review workflows" feature tile (homepage) + "Routing policies" section under Configuration in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Langfuse integration", [`docs/03-data-model.md`](../docs/03-data-model.md) `langfuse_config` / `ai_config.langfuse_prompt_*`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/langfuse-config`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_LANGFUSE_*` | "Langfuse integration" blurb in the "AI query analysis" feature tile + `Langfuse` tag (homepage) + "Langfuse integration" subsection (`#cfg-langfuse`) + `langfuse-config` (light + dark) figure under "AI configurations" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "RAG knowledge base", [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.rag_*`/`embedding_*` + `knowledge_document` + `vector_store`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/ai-configs/{id}/knowledge-documents` + `/rag/test`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_RAG_*` + "pgvector for RAG" | "RAG knowledge base" blurb + `RAG` tag in the "AI query analysis" feature tile (homepage) + "RAG knowledge base" paragraph, `ai-configs-rag` (light + dark) figure, and pgvector callout under "AI configurations" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "System Prompt Template" (`optimizations`), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_analyses.optimizations` + `query_requests.submission_reason`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/queries/analyze` + `/queries` `submission_reason` (AF-451) | "dialect-aware index recommendations and query rewrites … Apply as draft" blurb + `Optimization suggestions` tag in the "AI query analysis" feature tile (homepage) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Multi-model orchestration, voting & guardrails" (AF-450), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.orchestration_*`/`voting_*`/`guardrail_patterns` + `ai_config_model` + `ai_analysis_model_result`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/ai-configs` orchestration/guardrail fields + `/admin/ai-analyses/stats` `per_model_stats` | "Run several models at once … voting … guardrails" blurb + `Multi-model voting` / `Guardrails` / `Per-model cost & latency` tags in the "AI query analysis" feature tile + "Multi-model AI orchestration, voting & guardrails" item in the v2 roadmap group (homepage) + "Multi-model orchestration & voting" / "Guardrails" paragraphs under "AI configurations" and the per-model cost/latency note under "AI analyses dashboard" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Observability and tracing" (OTLP export, instrumented spans, metrics &amp; Grafana dashboards — AF-454), [`docs/09-deployment.md`](../docs/09-deployment.md) Observability env-var table (`OTEL_EXPORTER_OTLP_*`) + "Prometheus metrics &amp; Grafana dashboards", [`charts/accessflow/examples/values-observability.yaml`](../charts/accessflow/examples/values-observability.yaml) | "Observability" architecture callout + "Observability" feature blurb (homepage) + structured-logs and "Tracing &amp; metrics" notes under "Docker Compose" in [`docs/index.html`](docs/index.html) |
| [`docs/14-connectors.md`](../docs/14-connectors.md), [`connectors/`](../connectors/) catalog (incl. `connectors/mongodb/`, `connectors/couchbase/`, `connectors/redis/`, `connectors/cassandra/`, `connectors/scylladb/`, `connectors/elasticsearch/`, `connectors/opensearch/`, `connectors/dynamodb/`, `connectors/neo4j/`, `connectors/snowflake/`, `connectors/bigquery/`, `connectors/databricks/`), [`docs/04-api-spec.md`](../docs/04-api-spec.md) Connector endpoints + `category`, [`docs/03-data-model.md`](../docs/03-data-model.md) `datasources.connector_id`/`db_type` | Dedicated **"Connectors" section** (homepage, `#connectors`) — **SQL / cloud-data-warehouse / NoSQL grouped** logo grid of the catalog (logos in [`db-icons/`](db-icons/), copied from `connectors/<id>/logo.svg`, incl. `mongodb.svg`, `couchbase.svg`, `redis.svg`, `cassandra.svg`, `scylladb.svg`, `elasticsearch.svg`, `opensearch.svg`, `dynamodb.svg`, `neo4j.svg`, `snowflake.svg`, `bigquery.svg`, `databricks.svg`); "Connector catalog" feature tile; ClickHouse + MongoDB + "connector catalog" in supported-DB strips (architecture diagram, proxy tag, tech-stack targets); "Declarative connector catalog" item in the v2 roadmap group; + the "Connectors" section (built-in SQL + cloud-data-warehouse + NoSQL connector lists + install how-to) under Configuration in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "MongoDB engine" + "Couchbase engine" + "Redis engine" + "Cassandra engine" + "Elasticsearch engine" + "DynamoDB engine" + "Neo4j engine" + "Snowflake engine" + "BigQuery engine" + "Databricks engine", [`docs/14-connectors.md`](../docs/14-connectors.md), [`docs/06-frontend.md`](../docs/06-frontend.md) editor/results, [`connectors/mongodb/`](../connectors/mongodb/), [`connectors/couchbase/`](../connectors/couchbase/), [`connectors/redis/`](../connectors/redis/), [`connectors/cassandra/`](../connectors/cassandra/), [`connectors/scylladb/`](../connectors/scylladb/), [`connectors/elasticsearch/`](../connectors/elasticsearch/), [`connectors/opensearch/`](../connectors/opensearch/), [`connectors/dynamodb/`](../connectors/dynamodb/), [`connectors/neo4j/`](../connectors/neo4j/), [`connectors/snowflake/`](../connectors/snowflake/), [`connectors/bigquery/`](../connectors/bigquery/), [`connectors/databricks/`](../connectors/databricks/) | **MongoDB + Couchbase + Redis + Cassandra + ScyllaDB + Elasticsearch + OpenSearch + Amazon DynamoDB + Neo4j (NoSQL) + Snowflake + BigQuery + Databricks (cloud data warehouses)** copy — NoSQL + warehouse connector cards (homepage `#connectors`), "Full query proxy — SQL and NoSQL" feature tile (homepage), all engines in supported-DB strips + meta description, and the "Built-in connectors — NoSQL" / "— Cloud data warehouses" subsections under "Connectors" in [`docs/index.html`](docs/index.html) |
| [`docs/03-data-model.md`](../docs/03-data-model.md) `organizations` (`disabled`/`max_*`) + `users.platform_admin`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Platform Organizations" + `platform_admin` on the login/`/me` user object, [`docs/07-security.md`](../docs/07-security.md) "Platform admin" + "Multi-tenant isolation", [`docs/05-backend.md`](../docs/05-backend.md) "Multi-tenant isolation hardening" (AF-456) | "Multi-tenant orgs · Per-org quotas" blurb + tags in the "Workforce-ready auth" feature tile + "Multi-tenant orgs &amp; per-org quotas" item in the v2 roadmap group (homepage) + the "Organizations &amp; quotas" section and the platform-admin note under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Query snapshots &amp; replay" (AF-449), [`docs/03-data-model.md`](../docs/03-data-model.md) `query_snapshots`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `POST /queries/{id}/replay` | "immutable, sanitized snapshot … Replay in test environment" blurb in the "Full query proxy — SQL and NoSQL" governance feature tile + the "Version history &amp; diff · dry-run sandbox · replay" item in the v2 roadmap group (homepage) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Compliance reporting" (AF-459), [`docs/07-security.md`](../docs/07-security.md) "Compliance reporting &amp; signed exports" + AUDITOR role matrix, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Compliance Reporting", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_COMPLIANCE_*` env vars | "Tamper-evident audit &amp; compliance reports" feature tile + "Compliance reports &amp; signed exports" item in the v2 roadmap group (homepage) + the "Compliance reports &amp; signed exports" subsection and AUDITOR row under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Behavioural anomaly detection (UBA)" (AF-383), [`docs/03-data-model.md`](../docs/03-data-model.md) `behavior_baseline` / `behavior_anomaly`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Behavioural Anomaly Detection (UBA)", [`docs/08-notifications.md`](../docs/08-notifications.md) `ANOMALY_DETECTED`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_AI_ANOMALY_*` env vars | "behavioral anomaly detection (UBA)" blurb + `Anomaly detection (UBA)` tag in the "AI query analysis" feature tile + "Behavioral anomaly detection (UBA)" item in the v2 roadmap group (homepage) + the "Behavioural anomaly detection (UBA)" subsection and anomaly RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Break-glass / emergency access" (AF-385), [`docs/03-data-model.md`](../docs/03-data-model.md) `break_glass_events` / `can_break_glass`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/queries/break-glass` + `/admin/break-glass`, [`docs/07-security.md`](../docs/07-security.md) "Break-glass / emergency access", [`docs/08-notifications.md`](../docs/08-notifications.md) `BREAK_GLASS_EXECUTED` | "break-glass / emergency access" blurb + `Break-glass emergency access` tag in the "Configurable review workflows" feature tile + "Break-glass emergency access" item in the v2 roadmap group (homepage) + the "Break-glass / emergency access" subsection (`#cfg-break-glass`) and break-glass RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Personalized dashboard" (AF-498), [`docs/06-frontend.md`](../docs/06-frontend.md) DashboardPage, [`docs/03-data-model.md`](../docs/03-data-model.md) `dashboard_suggestion_state` / `dashboard_digest_subscription`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Personalized Dashboard", [`docs/08-notifications.md`](../docs/08-notifications.md) `WEEKLY_DIGEST`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_DASHBOARD_WEEKLY_DIGEST_*` env vars | "Personalized dashboard" feature tile + "Personalized dashboard &amp; weekly digest" item in the v2 roadmap group (homepage) + the "Personalized dashboard &amp; weekly digest" subsection under "End-user workflows" in [`docs/index.html`](docs/index.html) |
| [`docs/06-frontend.md`](../docs/06-frontend.md) "Progressive Web App & Web Push" (AF-444), [`docs/08-notifications.md`](../docs/08-notifications.md) "Web Push", [`docs/05-backend.md`](../docs/05-backend.md) "Step-up auth and the one-tap push decide path", [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/auth/step-up` + `/reviews/{id}/decide` + Web Push endpoints, [`docs/03-data-model.md`](../docs/03-data-model.md) `push_subscriptions` / `push_vapid_config`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_PUSH_VAPID_*` + `ACCESSFLOW_SECURITY_STEP_UP_TTL` | "mobile PWA … one-tap from a push notification" blurb + `Mobile PWA` / `One-tap push approvals` tags in the "Configurable review workflows" feature tile + "Mobile PWA + one-tap push" item in the v2 roadmap group (homepage) + the "Mobile approvals &amp; one-tap push" subsection (incl. VAPID / step-up env vars) under "End-user workflows" in [`docs/index.html`](docs/index.html) |
| [`docs/16-iac.md`](../docs/16-iac.md), [`terraform-provider/`](../terraform-provider/), [`.github/actions/`](../.github/actions/), [`ci-templates/`](../ci-templates/), [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS_*` (AF-452) | "Infrastructure as Code" feature tile + "Automation &amp; IaC" v2 roadmap group + docs grid card (homepage) + the "Infrastructure as Code (Terraform / OpenTofu &amp; CI Actions)" section (`#iac`, incl. service-account env vars + HCL example) and further-reading link in [`docs/index.html`](docs/index.html) |
| [`docs/17-api-governance.md`](../docs/17-api-governance.md), [`docs/03-data-model.md`](../docs/03-data-model.md) `api_connectors` / `api_schemas` / `api_connector_user_permissions`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "API Access Governance", [`docs/05-backend.md`](../docs/05-backend.md) "API Access Governance (apigov module)" (AF-500) | "API Access Governance" feature tile + docs grid card (homepage) |
| [`docs/17-api-governance.md`](../docs/17-api-governance.md) "Dynamic variables" (AF-613), [`docs/03-data-model.md`](../docs/03-data-model.md) `api_connector_variables` / `api_requests.variable_overrides` / `can_override_variables`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Connector variables", [`docs/05-backend.md`](../docs/05-backend.md) "Dynamic variables", [`docs/07-security.md`](../docs/07-security.md) dynamic-variable secret bullets, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_APIGOV_MAX_VARIABLE_VALUE_BYTES` | "dynamic variables" clause in the "API Access Governance" feature tile (homepage) + the "Dynamic variables (request signing)" and "Per-request overrides" paragraphs under "API connectors" (`#cfg-api-connectors`) in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Data Lifecycle Manager" (AF-499), [`docs/03-data-model.md`](../docs/03-data-model.md) `retention_policies` / `deletion_requests` / `lifecycle_runs`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Data Lifecycle Manager", [`docs/06-frontend.md`](../docs/06-frontend.md) lifecycle pages, [`docs/07-security.md`](../docs/07-security.md) "Lifecycle pseudonymization & salt rotation", [`docs/08-notifications.md`](../docs/08-notifications.md) `ERASURE_APPROVED`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_LIFECYCLE_*` env vars | "Data lifecycle &amp; right-to-erasure" feature tile (homepage) + the "Data lifecycle &amp; right-to-erasure" subsection (`#cfg-lifecycle`, incl. scan/erasure env vars) under "Configuration" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Request chaining & grouping" (AF-501), [`docs/03-data-model.md`](../docs/03-data-model.md) `request_groups` / `request_group_items` / `group_review_decisions`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Request chaining & grouping", [`docs/06-frontend.md`](../docs/06-frontend.md) "Request chaining & grouping pages", [`docs/07-security.md`](../docs/07-security.md) "Request chaining & grouping security", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_REQUESTGROUPS_*` env vars | "Request chaining &amp; grouping" feature tile + "Request chaining &amp; grouping" item in the v2 roadmap group (homepage) + the "Request chaining &amp; grouping" subsection (`#flow-request-groups`, incl. run/timeout env vars) under "End-user workflows" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Access recertification / attestation campaigns" (AF-384), [`docs/03-data-model.md`](../docs/03-data-model.md) `attestation_campaigns` / `attestation_items`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Attestation", [`docs/06-frontend.md`](../docs/06-frontend.md) CampaignListPage / AttestationWorklistPage, [`docs/07-security.md`](../docs/07-security.md) attestation self-review, [`docs/08-notifications.md`](../docs/08-notifications.md) `ATTESTATION_*`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_ATTESTATION_*` env vars | "Access recertification campaigns" item in the v2 roadmap group + Compliance card (homepage) + the "Access recertification campaigns" subsection (`#cfg-attestation`, incl. open/close/evidence env vars) and `attestation-campaigns` (light + dark) figure under "Configuration" in [`docs/index.html`](docs/index.html) |
| [`docs/02-architecture.md`](../docs/02-architecture.md), [`docs/05-backend.md`](../docs/05-backend.md) (JIT access, break-glass AF-385, routing policies AF-379, masking, lifecycle AF-499, attestation AF-384, compliance AF-459, apigov AF-500/AF-518), [`docs/07-security.md`](../docs/07-security.md), [`docs/17-api-governance.md`](../docs/17-api-governance.md) | **"Use cases" section** (homepage, `#use-cases`) — six enterprise use-case rows (shared credentials, JIT + break-glass, AI pre-screened review, audit readiness, data privacy operations, API governance); each claim maps to a shipped capability in the listed chapters |
| [`docs/07-security.md`](../docs/07-security.md) "External secret stores" (AF-448), [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_SECRETS_*` env vars, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/secret-providers` | "Encryption" security spec row + credential bullet in the proxy feature tile + "External secrets managers" item in the roadmap Planned group (homepage) + the "External secrets managers" note under "Docker Compose" and the secret-reference sentence under "Datasources → Connection details" in [`docs/index.html`](docs/index.html) |
| [`docs/09-deployment.md`](../docs/09-deployment.md) "Disaster Recovery" (AF-458), [`charts/accessflow/README.md`](../charts/accessflow/README.md) "Backup & restore", [`charts/accessflow/examples/values-backup.yaml`](../charts/accessflow/examples/values-backup.yaml), `ACCESSFLOW_AUDIT_VERIFY_CHAIN_ON_STARTUP` env var | "Backup / restore &amp; DR tooling" item in the roadmap Planned group (homepage) + the `values-backup.yaml` example bullet and "Backup, restore &amp; disaster recovery" paragraph under "Kubernetes &amp; Helm" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "AI provider fallback pool" (AF-458), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.fallback_priority`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) AI Configurations `fallback_priority` | "fallback pool" blurb + `Fallback pool` tag in the "AI query analysis" feature tile + "Offline AI fallback" item in the roadmap Planned group (homepage) + the "Fallback pool" paragraph under "AI configurations" in [`docs/index.html`](docs/index.html) |
| [`docs/12-roadmap.md`](../docs/12-roadmap.md) | Roadmap track |
| [`docs/`](../docs/) chapter filenames + H1s (01–17, incl. [`docs/14-connectors.md`](../docs/14-connectors.md), the [`docs/15-engine-sdk.md`](../docs/15-engine-sdk.md) engine-author guide, the [`docs/16-iac.md`](../docs/16-iac.md) IaC guide, and the [`docs/17-api-governance.md`](../docs/17-api-governance.md) API-governance guide) | Docs grid cards |
| [`CLAUDE.md`](../CLAUDE.md) (supported db list, env-var defaults) | Hero meta strip, Features tags |
| [`charts/accessflow/`](../charts/accessflow/) | Helm install tab |
| [`README.md`](../README.md) quick start + [`docs/05-backend.md`](../docs/05-backend.md), [`docs/07-security.md`](../docs/07-security.md), [`docs/08-notifications.md`](../docs/08-notifications.md), [`docs/09-deployment.md`](../docs/09-deployment.md) | [`docs/index.html`](docs/index.html) — user documentation page (run + configure) |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) pre-release handling, [`docs/09-deployment.md`](../docs/09-deployment.md) "Installing a pre-release / beta build", [`docs/11-development.md`](../docs/11-development.md) "Pre-release (beta) builds" | "Beta / pre-release channel" subsection (`#run-beta`) under "Running AccessFlow" in [`docs/index.html`](docs/index.html) |
| [`frontend/src/pages/admin/`](../frontend/src/pages/admin/), [`frontend/src/pages/datasources/`](../frontend/src/pages/datasources/) — admin SPA pages | [`docs/index.html`](docs/index.html) configuration walkthroughs (Users, User groups, Datasources + Schema explorer (searchable object tree + RLS/masking-aware sample-data previews, AF-443; see [`docs/05-backend.md`](../docs/05-backend.md) "Sample data path" + [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/sample-rows`) + ER diagram + Masking + Row security tabs, Connectors, Custom JDBC drivers, Review plans + templates, Routing policies, Access requests queue, AI configs + Langfuse + RAG, AI analyses dashboard, Datasource health, Organizations (multi-tenant management + quotas), Auditor dashboard, Anomalies (UBA), Break-glass log, Notifications, System SMTP, OAuth, SAML, Slack app, Audit log) + matching PNGs under [`images/docs/`](images/docs/) (incl. `routing-policies`, `access-requests-queue`, `datasources-masking`, `datasources-row-security`, `langfuse-config`, `ai-configs-rag`, `organizations-list`, `auditor-dashboard`, `anomalies-dashboard`, `break-glass-log`, `dashboard`, `attestation-campaigns`, `api-connectors-list`, `lifecycle-policies`, light + dark) |
| [`frontend/src/pages/editor/QueryEditorPage.tsx`](../frontend/src/pages/editor/QueryEditorPage.tsx), [`frontend/src/pages/reviews/ReviewQueuePage.tsx`](../frontend/src/pages/reviews/ReviewQueuePage.tsx) — end-user SPA pages | [`docs/index.html`](docs/index.html) "End-user workflows" section (Submitting / Scheduling a query, Drafting queries from natural language, Query templates library, Reviewing &amp; bulk approval) + matching PNGs under [`images/docs/`](images/docs/) (`editor-light`, `editor-text-to-sql-light`, `editor-schedule-light`, `editor-query-templates-light`, `reviews-queue-bulk-light`, `request-groups-list-light`, `api-requests-list-light`) |
| Existing on-page copy (hero, features, supported DBs, license) | SEO meta block (canonical, OG, Twitter, JSON-LD) in both [`index.html`](index.html) and [`docs/index.html`](docs/index.html) |
| Homepage meta description + docs grid cards (`#docs`) + [`docs/`](../docs/) chapter list | [`llms.txt`](llms.txt) — llmstxt.org index for LLM agents (summary blockquote, chapter link list with raw-markdown URLs). When the pitch, supported-DB list, or docs chapters change, update it in the same change set |

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
├── sitemap.xml      # XML sitemap (homepage + docs page)
├── llms.txt         # llms.txt (llmstxt.org) — curated product overview + doc links for LLM agents
├── _headers         # Cloudflare asset headers — Cache-Control + security headers (see SEO)
├── .assetsignore    # Files in this folder that must NOT be published
├── wrangler.jsonc   # Cloudflare Workers static-assets deploy config
├── googlef4908e4bf779aae8.html  # Google Search Console site-verification token
├── db-icons/        # Connector logos, copied from connectors/<id>/logo.svg
├── docs/            # Public user documentation — one page per chapter
│   ├── index.html   #   hub: read-this-first, chapter index, legacy-anchor forwarder
│   ├── install/     #   Docker Compose / Helm / from source + first-run setup
│   ├── configuration/  # users-roles, datasources, connectors, review-workflows,
│   │                   # ai, auth, notifications, audit-compliance
│   ├── workflows/   #   end-user: submit, track, review, approve
│   └── iac/         #   Terraform / OpenTofu provider + CI Actions
├── images/
│   └── docs/        # Lossless WebP screenshots of admin SPA pages, light + dark per screen
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
   never update. `LEGACY_DOCS_ANCHORS` forwards all 50 old anchors to their new chapter, and
   the test asserts it agrees with `docs.ts`. Never delete it as "migration cruft".

Because there is no build step, the nav, `<head>`, and footer are duplicated across the
chapter files — a nav change is a 13-file edit (~98 KB of duplicated shell). That is the
deliberate trade for keeping this folder buildless.

Nothing can remove that edit cost without a build step, but the *risk* it creates — editing
12 files and missing the 13th — is guarded:
[`frontend/src/config/__tests__/websiteDocs.test.ts`](../frontend/src/config/__tests__/websiteDocs.test.ts)
fails CI unless every chapter shares a byte-identical nav and footer, links every other
chapter, carries a correct self-referencing canonical, keeps one `<h1>` with no skipped
levels, holds its description under 160 characters, has no duplicate ids, has no dead
same-page or cross-chapter links, and appears in `sitemap.xml`.

So: editing all 13 files is on you; forgetting one is on CI.

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
`styles.css`; `index.html` and `docs/index.html` preload `geist-latin.woff2` only. To
refresh or add a subset, pull the CSS from `fonts.googleapis.com/css2?family=Geist:...`
with a modern browser User-Agent, and download the `.woff2` URLs it returns.

---

## SEO

Every HTML page ships a full SEO meta block — canonical URL, Open Graph, Twitter Card,
`theme-color`, and a JSON-LD `@graph` (`SoftwareApplication` + `Organization` + `WebSite`
on the homepage; `TechArticle` + `BreadcrumbList` + `Organization` on each docs chapter).

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

All canonical / `og:url` values are hard-coded to `https://accessflow.bablsoft.com` — if
the deployed origin ever changes, search every HTML file plus `sitemap.xml` and
`robots.txt` and update in lockstep.

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

`robots.txt` allows all crawlers and points to `sitemap.xml`. `sitemap.xml` lists the
two HTML pages (`/` and `/docs/`).

Meta descriptions must stay **≤ 160 rendered characters** — past that Google truncates the
tag and usually substitutes its own snippet. Bump `<lastmod>` in `sitemap.xml` and
`dateModified` in each touched page's JSON-LD whenever you edit content; both are
hand-maintained because this folder has no build step. Do not add `HowTo` schema
(deprecated 2023) or `FAQPage` (Google retired FAQ rich results for all sites in May 2026).

### Response headers

Cloudflare's default for static assets is `Cache-Control: public, max-age=0,
must-revalidate` on *everything*, which makes repeat visitors revalidate every asset on
every navigation. `_headers` overrides that:

| Path | Cache-Control | Why |
|---|---|---|
| `/db-icons/*`, `/favicon.svg` | 1 year, `immutable` | Vendor logos — effectively static |
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

39 multi-declaration inline `style=""` attributes remain (the single-declaration colour and
`margin-left` ones were replaced by the `.t-*` / `.ml-auto` utilities). Dropping the
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

### Docs screenshots

`images/docs/` holds **lossless WebP**, not PNG — pixel-identical to a PNG screenshot but
~70% smaller (8.1 MB → 2.4 MB across 69 files). They are written by
[`e2e/screenshots/capture.ts`](../e2e/screenshots/capture.ts), which encodes through `sharp`
because Playwright can only emit PNG/JPEG. **That script is the only writer** — do not add
PNGs alongside, or the two formats will drift and the site will serve stale screenshots.

Each screen has a `-light` and `-dark` variant wrapped in a `<picture>`:

```html
<picture>
  <source srcset="../images/docs/foo-light.webp" media="(prefers-color-scheme: light)" />
  <img src="../images/docs/foo-dark.webp" alt="…" loading="lazy" width="1440" height="900" />
</picture>
```

`prefers-color-scheme` covers the default case; the site's own theme toggle is handled by
`swapDocsImages()` in `app.js`. That function must rewrite **both** the `<source srcset>`
and the `<img src>` — when a `<source>` media query matches, it wins over `img.src`, so
rewriting `img.src` alone leaves the toggle silently broken for visitors on a light-themed
OS. Keep every image inside a `<picture>` with that exact `-light` / `-dark` naming, or the
swap will not find it.

---

## Deployment

Live at <https://accessflow.bablsoft.com/>, served as **Cloudflare Workers static assets**
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
