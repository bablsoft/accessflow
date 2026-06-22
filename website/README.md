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
| [`docs/05-backend.md`](../docs/05-backend.md) "JIT time-bound access requests", [`docs/07-security.md`](../docs/07-security.md) JIT section, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_ACCESS_*` env vars | "Just-in-time (JIT) access requests" paragraph + RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Dynamic data masking policies", [`docs/07-security.md`](../docs/07-security.md) masking section, [`docs/03-data-model.md`](../docs/03-data-model.md) `masking_policy` | "Dynamic data masking" blurb in the "Full SQL proxy" feature tile (homepage) + "Masking policies" paragraph under "Datasources" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Row-level security policies", [`docs/07-security.md`](../docs/07-security.md) row-security section, [`docs/03-data-model.md`](../docs/03-data-model.md) `row_security_policy` / `users.attributes`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/row-security-policies` | "Row-level security" blurb in the "Full SQL proxy" feature tile (homepage) + "Row security policies" paragraph under "Datasources" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Policy-as-code routing engine", [`docs/03-data-model.md`](../docs/03-data-model.md) `routing_policy` / `routing_decision`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/routing-policies` | "Policy-as-code routing" blurb in the "Configurable review workflows" feature tile (homepage) + "Routing policies" section under Configuration in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Langfuse integration", [`docs/03-data-model.md`](../docs/03-data-model.md) `langfuse_config` / `ai_config.langfuse_prompt_*`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/langfuse-config`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_LANGFUSE_*` | "Langfuse integration" blurb in the "AI query analysis" feature tile + `Langfuse` tag (homepage) + "Langfuse integration" paragraph + `langfuse-config` (light + dark) figure under "AI configurations" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "RAG knowledge base", [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_config.rag_*`/`embedding_*` + `knowledge_document` + `vector_store`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/admin/ai-configs/{id}/knowledge-documents` + `/rag/test`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_RAG_*` + "pgvector for RAG" | "RAG knowledge base" blurb + `RAG` tag in the "AI query analysis" feature tile (homepage) + "RAG knowledge base" paragraph, `ai-configs-rag` (light + dark) figure, and pgvector callout under "AI configurations" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "System Prompt Template" (`optimizations`), [`docs/03-data-model.md`](../docs/03-data-model.md) `ai_analyses.optimizations` + `query_requests.submission_reason`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/queries/analyze` + `/queries` `submission_reason` (AF-451) | "dialect-aware index recommendations and query rewrites … Apply as draft" blurb + `Optimization suggestions` tag in the "AI query analysis" feature tile (homepage) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Observability and tracing", [`docs/09-deployment.md`](../docs/09-deployment.md) Observability env-var table | "Observability" architecture callout (homepage) + structured-logs note under "Docker Compose" in [`docs/index.html`](docs/index.html) |
| [`docs/14-connectors.md`](../docs/14-connectors.md), [`connectors/`](../connectors/) catalog (incl. `connectors/mongodb/`, `connectors/couchbase/`, `connectors/redis/`, `connectors/cassandra/`, `connectors/scylladb/`, `connectors/elasticsearch/`, `connectors/opensearch/`, `connectors/dynamodb/`, `connectors/neo4j/`), [`docs/04-api-spec.md`](../docs/04-api-spec.md) Connector endpoints + `category`, [`docs/03-data-model.md`](../docs/03-data-model.md) `datasources.connector_id`/`db_type` | Dedicated **"Connectors" section** (homepage, `#connectors`) — **SQL / NoSQL grouped** logo grid of the catalog (logos in [`db-icons/`](db-icons/), copied from `connectors/<id>/logo.svg`, incl. `mongodb.svg`, `couchbase.svg`, `redis.svg`, `cassandra.svg`, `scylladb.svg`, `elasticsearch.svg`, `opensearch.svg`, `dynamodb.svg`, `neo4j.svg`); "Connector catalog" feature tile; ClickHouse + MongoDB + "connector catalog" in supported-DB strips (architecture diagram, proxy tag, tech-stack targets); "Declarative connector catalog" roadmap item; + the "Connectors" section (built-in SQL + NoSQL connector lists + install how-to) under Configuration in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "MongoDB engine" + "Couchbase engine" + "Redis engine" + "Cassandra engine" + "Elasticsearch engine" + "DynamoDB engine" + "Neo4j engine", [`docs/14-connectors.md`](../docs/14-connectors.md), [`docs/06-frontend.md`](../docs/06-frontend.md) editor/results, [`connectors/mongodb/`](../connectors/mongodb/), [`connectors/couchbase/`](../connectors/couchbase/), [`connectors/redis/`](../connectors/redis/), [`connectors/cassandra/`](../connectors/cassandra/), [`connectors/scylladb/`](../connectors/scylladb/), [`connectors/elasticsearch/`](../connectors/elasticsearch/), [`connectors/opensearch/`](../connectors/opensearch/), [`connectors/dynamodb/`](../connectors/dynamodb/), [`connectors/neo4j/`](../connectors/neo4j/) | **MongoDB + Couchbase + Redis + Cassandra + ScyllaDB + Elasticsearch + OpenSearch + Amazon DynamoDB + Neo4j (NoSQL)** copy — NoSQL connector cards (homepage `#connectors`), "Full query proxy — SQL and NoSQL" feature tile (homepage), all NoSQL engines in supported-DB strips + meta description, and the "Built-in connectors — NoSQL" subsection under "Connectors" in [`docs/index.html`](docs/index.html) |
| [`docs/03-data-model.md`](../docs/03-data-model.md) `organizations` (`disabled`/`max_*`) + `users.platform_admin`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Platform Organizations" + `platform_admin` on the login/`/me` user object, [`docs/07-security.md`](../docs/07-security.md) "Platform admin" + "Multi-tenant isolation", [`docs/05-backend.md`](../docs/05-backend.md) "Multi-tenant isolation hardening" (AF-456) | "Multi-tenant orgs · Per-org quotas" blurb + tags in the "Workforce-ready auth" feature tile + "Multi-tenant org management &amp; per-org quotas" v2.0 roadmap item (homepage) + the "Organizations &amp; quotas" section and the platform-admin note under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Query snapshots &amp; replay" (AF-449), [`docs/03-data-model.md`](../docs/03-data-model.md) `query_snapshots`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `POST /queries/{id}/replay` | "immutable, sanitized snapshot … Replay in test environment" blurb in the "Full SQL proxy" governance feature tile + "Immutable query snapshots &amp; replay" roadmap item (homepage) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Compliance reporting" (AF-459), [`docs/07-security.md`](../docs/07-security.md) "Compliance reporting &amp; signed exports" + AUDITOR role matrix, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Compliance Reporting", [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_COMPLIANCE_*` env vars | "Tamper-evident audit &amp; compliance reports" feature tile + "Compliance reporting &amp; signed PDF/CSV exports" v2.0 roadmap item (homepage) + the "Compliance reports &amp; signed exports" subsection and AUDITOR row under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Behavioural anomaly detection (UBA)" (AF-383), [`docs/03-data-model.md`](../docs/03-data-model.md) `behavior_baseline` / `behavior_anomaly`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) "Behavioural Anomaly Detection (UBA)", [`docs/08-notifications.md`](../docs/08-notifications.md) `ANOMALY_DETECTED`, [`docs/09-deployment.md`](../docs/09-deployment.md) `ACCESSFLOW_AI_ANOMALY_*` env vars | "behavioral anomaly detection (UBA)" blurb + `Anomaly detection (UBA)` tag in the "AI query analysis" feature tile + "Behavioral anomaly detection (UBA)" v2.0 roadmap item (homepage) + the "Behavioural anomaly detection (UBA)" subsection and anomaly RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/05-backend.md`](../docs/05-backend.md) "Break-glass / emergency access" (AF-385), [`docs/03-data-model.md`](../docs/03-data-model.md) `break_glass_events` / `can_break_glass`, [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/queries/break-glass` + `/admin/break-glass`, [`docs/07-security.md`](../docs/07-security.md) "Break-glass / emergency access", [`docs/08-notifications.md`](../docs/08-notifications.md) `BREAK_GLASS_EXECUTED` | "break-glass / emergency access" blurb + `Break-glass emergency access` tag in the "Configurable review workflows" feature tile + "Break-glass emergency access" v2.0 roadmap item (homepage) + the "Break-glass / emergency access" paragraph and break-glass RBAC rows under "User roles &amp; RBAC" in [`docs/index.html`](docs/index.html) |
| [`docs/12-roadmap.md`](../docs/12-roadmap.md) | Roadmap track |
| [`docs/`](../docs/) chapter filenames + H1s (01–15, incl. [`docs/14-connectors.md`](../docs/14-connectors.md) and the [`docs/15-engine-sdk.md`](../docs/15-engine-sdk.md) engine-author guide) | Docs grid cards |
| [`CLAUDE.md`](../CLAUDE.md) (supported db list, env-var defaults) | Hero meta strip, Features tags |
| [`charts/accessflow/`](../charts/accessflow/) | Helm install tab |
| [`README.md`](../README.md) quick start + [`docs/05-backend.md`](../docs/05-backend.md), [`docs/07-security.md`](../docs/07-security.md), [`docs/08-notifications.md`](../docs/08-notifications.md), [`docs/09-deployment.md`](../docs/09-deployment.md) | [`docs/index.html`](docs/index.html) — user documentation page (run + configure) |
| [`.github/workflows/release.yml`](../.github/workflows/release.yml) pre-release handling, [`docs/09-deployment.md`](../docs/09-deployment.md) "Installing a pre-release / beta build", [`docs/11-development.md`](../docs/11-development.md) "Pre-release (beta) builds" | "Beta / pre-release channel" subsection (`#run-beta`) under "Running AccessFlow" in [`docs/index.html`](docs/index.html) |
| [`frontend/src/pages/admin/`](../frontend/src/pages/admin/), [`frontend/src/pages/datasources/`](../frontend/src/pages/datasources/) — admin SPA pages | [`docs/index.html`](docs/index.html) configuration walkthroughs (Users, User groups, Datasources + Schema explorer (searchable object tree + RLS/masking-aware sample-data previews, AF-443; see [`docs/05-backend.md`](../docs/05-backend.md) "Sample data path" + [`docs/04-api-spec.md`](../docs/04-api-spec.md) `/datasources/{id}/sample-rows`) + ER diagram + Masking + Row security tabs, Connectors, Custom JDBC drivers, Review plans + templates, Routing policies, Access requests queue, AI configs + Langfuse + RAG, AI analyses dashboard, Datasource health, Notifications, System SMTP, OAuth, SAML, Slack app, Audit log) + matching PNGs under [`images/docs/`](images/docs/) (incl. `routing-policies`, `access-requests-queue`, `datasources-masking`, `datasources-row-security`, `langfuse-config`, `ai-configs-rag`, light + dark) |
| [`frontend/src/pages/editor/QueryEditorPage.tsx`](../frontend/src/pages/editor/QueryEditorPage.tsx), [`frontend/src/pages/reviews/ReviewQueuePage.tsx`](../frontend/src/pages/reviews/ReviewQueuePage.tsx) — end-user SPA pages | [`docs/index.html`](docs/index.html) "End-user workflows" section (Submitting / Scheduling a query, Drafting queries from natural language, Query templates library, Reviewing &amp; bulk approval) + matching PNGs under [`images/docs/`](images/docs/) (`editor-light`, `editor-text-to-sql-light`, `editor-schedule-light`, `editor-query-templates-light`, `reviews-queue-bulk-light`) |
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
