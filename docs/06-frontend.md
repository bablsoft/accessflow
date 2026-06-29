# 06 — Frontend Architecture

## Tech Stack

Frontend dependencies follow a **latest-stable** policy: pin every package to the most recent stable major published on npm at the time of `npm install`. Verify with `npm view <pkg> version` before adding or upgrading; if a newer major has shipped since the last check, prefer it unless a specific incompatibility is documented in the same change. The table below names the role each library plays — the version column captures the latest-stable snapshot at the time the row was last touched, not a pin.

| Technology | Version snapshot | Purpose |
|-----------|------------------|---------|
| React + ReactDOM | latest stable (19.x at 2026-05-06) | UI framework |
| Vite + @vitejs/plugin-react | latest stable (8.x at 2026-05-06) | Build tool and dev server |
| TypeScript | latest stable (6.x at 2026-05-06) | Type safety (`strict: true`) |
| Ant Design | latest stable (6.x at 2026-05-06) | UI component library |
| CodeMirror + @codemirror/lang-sql | latest stable (6.x at 2026-05-06) | SQL editor engine (PostgreSQL/MySQL dialects) |
| @codemirror/lang-javascript + @codemirror/lang-json | latest stable (6.x) | MongoDB query highlighting — shell (JavaScript) and JSON-command modes |
| yjs + y-codemirror.next + y-protocols | latest stable (13.x / 0.3.x / 1.x at 2026-06-17) | CRDT collaborative editing of a query in review — shared document, remote cursors, awareness (AF-441) |
| Zustand | latest stable (5.x at 2026-05-06) | Global state management |
| TanStack Query | latest stable (5.x at 2026-05-06) | Server state, caching, refetching |
| Axios | latest stable (1.x at 2026-05-06) | HTTP client |
| React Router | latest stable (7.x at 2026-05-06, library mode) | Client-side routing |
| sql-formatter | latest stable (15.x at 2026-05-06) | SQL formatting (Ctrl+Shift+F) |
| @xyflow/react | latest stable (12.x at 2026-05-27) | ER diagram rendering on `DatasourceSettingsPage` |
| dagre | latest stable (0.8.x at 2026-05-27) | Auto-layout for the ER diagram graph |
| Vitest + @testing-library/react | latest stable | Unit/component tests |
| Playwright (lives in [`e2e/`](../e2e/)) | latest stable | End-to-end tests — separate npm project, own docker-compose stack. See [docs/11-development.md → End-to-End](11-development.md#end-to-end-e2e) |

When upgrading the codebase to a new major, update this snapshot column in the same change so the doc stays in sync.

---

## Project Directory Structure

```
accessflow-ui/
├── public/
│   ├── favicon.svg
│   └── db-icons/                   # SVG logos shown in DatasourceTypeSelector
│       ├── LICENSE                 # Devicon MIT licence + attribution preamble
│       ├── postgresql.svg          # Devicon (MIT)
│       ├── mysql.svg               # Devicon (MIT)
│       ├── mariadb.svg             # Devicon (MIT)
│       ├── oracle.svg              # Devicon (MIT)
│       ├── mssql.svg               # Devicon (MIT)
│       └── generic.svg             # AccessFlow original — fallback for UNAVAILABLE / unknown type
├── src/
│   ├── api/                        # Axios client instances, one per domain
│   │   ├── client.ts               # Base Axios instance with JWT interceptor
│   │   ├── queries.ts              # Query request API calls
│   │   ├── datasources.ts          # Datasource API calls
│   │   ├── datasourceTypes.ts      # GET /datasources/types — wizard metadata
│   │   ├── reviews.ts              # Review workflow API calls
│   │   ├── admin.ts                # Admin API calls
│   │   └── auth.ts                 # Auth API calls
│   │
│   ├── assets/
│   │   └── logo.svg
│   │
│   ├── components/
│   │   ├── common/
│   │   │   ├── StatusBadge.tsx     # Color-coded query status badge
│   │   │   ├── RiskBadge.tsx       # Color-coded AI risk level badge
│   │   │   ├── AnomalyBadge.tsx    # Caller's open-anomaly count for a datasource (UBA — AF-383)
│   │   │   ├── CopyButton.tsx      # Copy-to-clipboard wrapper
│   │   │   ├── LogoMark.tsx        # Two-tone brand mark (mirrors website logo)
│   │   │   └── PageHeader.tsx      # Consistent page header with breadcrumbs
│   │   │
│   │   ├── editor/
│   │   │   ├── SqlEditor.tsx       # CodeMirror 6 SQL editor component
│   │   │   ├── AiHintPanel.tsx     # Inline AI analysis results panel
│   │   │   ├── SchemaTree.tsx      # Sidebar schema/table browser
│   │   │   └── EditorToolbar.tsx   # Format, run, datasource selector
│   │   │
│   │   ├── review/
│   │   │   ├── ApprovalTimeline.tsx # Visual timeline of review stages
│   │   │   ├── ReviewDecisionForm.tsx # Approve/reject form with comment
│   │   │   ├── RejectModal.tsx     # Modal w/ required-comment textarea for /reviews reject
│   │   │   ├── BulkDecisionModal.tsx # Shared-comment modal for /reviews bulk approve/reject/request-changes
│   │   │   └── AiAnalysisAccordion.tsx # Expandable AI analysis details
│   │   │
│   │   ├── datasources/
│   │   │   ├── DatasourceForm.tsx  # Create/edit datasource form
│   │   │   ├── DatasourceTypeSelector.tsx # Visual grid of supported db types (wizard step 1)
│   │   │   ├── DatasourceWizardSteps.tsx  # Stepper shell driving the create wizard
│   │   │   ├── JdbcUrlPreview.tsx  # Live-rendered JDBC URL from selected type + form state
│   │   │   ├── DriverStatusBadge.tsx # READY / AVAILABLE / UNAVAILABLE indicator
│   │   │   ├── ConnectionTester.tsx # Live connection test widget
│   │   │   ├── PermissionMatrix.tsx # User × permission grid
│   │   │   ├── MaskingTab.tsx       # Dynamic data masking policies tab + create/edit modal (AF-381)
│   │   │   ├── RowSecurityTab.tsx    # Row-level security policies tab + create/edit modal (AF-380)
│   │   │   ├── SchemaObjectTree.tsx  # Searchable schema→table→column tree (AF-443)
│   │   │   ├── SampleDataPreview.tsx # Read-only RLS/masking-aware sample-row table (AF-443)
│   │   │   ├── SampleDataDrawer.tsx  # Drawer hosting SampleDataPreview (AF-443)
│   │   │   └── ReviewPlanPicker.tsx # Review plan assignment dropdown
│   │   │
│   │   └── audit/
│   │       ├── AuditLogTable.tsx   # Searchable audit event table
│   │       └── AuditDetailDrawer.tsx # Slide-in detail for single event
│   │
│   ├── realtime/
│   │   ├── websocketManager.ts     # Framework-free singleton: connect/reconnect/dispatch
│   │   └── RealtimeBridge.tsx      # Mounted by AppLayout (under AuthGuard) so /login does not connect
│   │
│   ├── hooks/
│   │   ├── useQueryRequest.ts      # CRUD + status polling for a query request
│   │   ├── useReviewQueue.ts       # Pending reviews for current user
│   │   ├── useWebSocket.ts         # Typed `subscribe` wrapper for components
│   │   ├── useSchemaIntrospect.ts  # Fetch and cache datasource schema
│   │   ├── useAiAnalysis.ts        # Debounced AI analysis calls from editor
│   │   └── useCurrentUser.ts       # Auth state, role checks
│   │
│   ├── layouts/
│   │   ├── AppLayout.tsx           # Main app shell with sidebar nav
│   │   ├── AdminLayout.tsx         # Admin section layout with sub-nav
│   │   └── AuthLayout.tsx          # Centered card layout for login
│   │
│   ├── pages/
│   │   ├── auth/
│   │   │   ├── LoginPage.tsx
│   │   │   └── SamlCallbackPage.tsx  # SAML SSO callback handler
│   │   │
│   │   ├── editor/
│   │   │   └── QueryEditorPage.tsx   # Full SQL editor with submit flow
│   │   │
│   │   ├── queries/
│   │   │   ├── QueryListPage.tsx     # Paginated query history (with CSV export)
│   │   │   └── QueryDetailPage.tsx   # Full detail view for a single query
│   │   │
│   │   ├── reviews/
│   │   │   └── ReviewQueuePage.tsx   # Pending reviews for current reviewer
│   │   │
│   │   ├── datasources/
│   │   │   ├── DatasourceListPage.tsx
│   │   │   ├── DatasourceCreateWizardPage.tsx  # Multi-step create flow with type selection
│   │   │   └── DatasourceSettingsPage.tsx
│   │   │
│   │   └── admin/
│   │       ├── UsersPage.tsx
│   │       ├── AuditLogPage.tsx
│   │       ├── AnomaliesPage.tsx     # Behavioural anomaly detection (UBA — AF-383)
│   │       ├── BreakGlassLogPage.tsx # Break-glass / emergency-access log (AF-385)
│   │       ├── AIConfigPage.tsx
│   │       ├── NotificationsPage.tsx
│   │       ├── SamlConfigPage.tsx    # SAML 2.0 SSO configuration
│   │       └── LangfuseConfigPage.tsx # Langfuse tracing + prompt management
│   │
│   ├── store/
│   │   ├── authStore.ts             # Current user, JWT, login/logout actions
│   │   └── preferencesStore.ts      # Theme, sidebar collapse, language, dashboard widget layout (AF-498)
│   │
│   ├── types/
│   │   ├── api.ts                   # All API response/request types
│   │   ├── datasource.ts
│   │   ├── query.ts
│   │   └── user.ts
│   │
│   ├── utils/
│   │   ├── riskColors.ts            # Risk level → Ant Design color token map
│   │   ├── statusColors.ts          # Query status → color map
│   │   ├── dateFormat.ts            # Consistent date/time formatting
│   │   └── sqlFormat.ts             # sql-formatter wrapper
│   │
│   ├── App.tsx                      # Route definitions
│   └── main.tsx                     # App entry point
│
├── vite.config.ts
├── tsconfig.json
└── package.json
```

---

## Key Pages

### QueryEditorPage

The primary user-facing page. Features:

- **Datasource selector** — dropdown of datasources the user has access to, loads schema tree on selection
- **CodeMirror SQL editor** — see SQL Editor section below. `SqlEditor` picks its CodeMirror language from the **engine-mode registry** (`src/utils/engineModes.ts`, AF-418), keyed by `db_type`: relational datasources get the SQL language with a PostgreSQL/MySQL dialect; a `MONGODB` datasource highlights as **JavaScript** (shell syntax) or **JSON** (JSON-command syntax), driven by the generic `syntax` prop. New engines register their syntaxes/highlighting in the registry — no `SqlEditor` edits.
- **Query-syntax selector** — when the engine mode declares more than one syntax (MongoDB: Shell / JSON), the toolbar shows an Ant Design `Segmented` control; the SQL **Format** button renders only when the mode's `canFormat` is true (SQL engines). The submitted text still goes in the `sql` field; the backend auto-detects the form. **Text-to-query** is gated on the mode's `supportsTextToSql`, which is now `true` for every shipped engine including NoSQL (AF-439); it is still additionally gated per-datasource on `text_to_sql_enabled` + a bound `ai_config_id`. On a successful generation `TextToSqlBar` returns a `syntax` hint that `QueryEditorPage` applies (e.g. switching a MongoDB draft to Shell or JSON) before inserting the draft. The registry also supplies `defaultResultView`, which `QueryDetailPage` passes to `QueryResultsTable` (MongoDB results open in the JSON document view).
- **AI Hint Panel** — displays AI analysis after the user clicks the **Analyze** button. When the SQL is edited (including via **Apply as draft**) the analysis is **kept on screen but marked stale** — a "stale" badge plus a warning banner with a **Re-run analysis** button — so the user can still read the risks and apply the remaining optimization suggestions; Submit re-gates until the query is re-analyzed (staleness is derived by comparing the live SQL against the snapshot the analysis ran against).
- **Analyze button** — explicit user action that calls `POST /queries/analyze`. Rendered only when the selected datasource has `ai_analysis_enabled=true` and a non-null `ai_config_id`.
- **Justification field** — required text area for the reason behind the query
- **Scheduled execution picker** (AF-345) — optional Ant Design `DatePicker` with `showTime` that records `scheduled_for` on the submission payload. Past dates are disabled and a "scheduled time must be in the future" hint disables Submit when the user picks an already-elapsed instant. Leave empty for the default immediate-review flow.
- **Submit button** — sends `POST /queries`, transitions to status tracking view. When the datasource has AI configured, Submit is disabled until a fresh AI analysis exists for the current SQL.
- **Status tracker** — real-time status updates via WebSocket (`PENDING_AI` → `PENDING_REVIEW` → `APPROVED` → `EXECUTED`)

### ReviewQueuePage

Available to users with `REVIEWER` or `ADMIN` role:

- Paginated list of queries in `PENDING_REVIEW` status assigned to this reviewer, rendered as an Ant Design `<Table>` with `rowSelection` so the reviewer can drive both single-row and batch flows from the same page.
- Columns: ID (short hash + full UUID + tooltip), query type, AI risk badge, datasource, submitter (avatar + email), time elapsed, optional per-row status badge for failed bulk rows, and a row-actions column with per-row approve/reject buttons.
- Quick approve inline on the row. Reject opens `RejectModal` ([components/review/RejectModal.tsx](../frontend/src/components/review/RejectModal.tsx)) — a comment is required (the confirm button stays disabled until the textarea is non-whitespace), mirroring the backend `@NotBlank` constraint on `POST /reviews/{id}/reject`.
- Selecting one or more rows reveals a **sticky action bar** above the table with "Approve selected", "Reject selected", "Request changes", and "Clear selection". Each button opens the shared `BulkDecisionModal` ([components/review/BulkDecisionModal.tsx](../frontend/src/components/review/BulkDecisionModal.tsx)), which collects one comment to apply to every selected query and submits to `POST /api/v1/reviews/bulk`. After submit, successful rows leave the queue; failed rows stay selected with a per-row status tag (Forbidden / Not pending review / Not found) so the reviewer can retry.
- Row click opens the full detail page (`/queries/:id`); the row-actions column buttons stop propagation so they don't trigger navigation.
- The ID column renders a small clock icon (with a "Scheduled to run at …" tooltip) when the row carries a non-null `scheduled_for`, so users can spot scheduled queries at a glance from `/queries` without opening the detail page.
- `ApprovalTimeline` shows which reviewers in the plan have already decided

### RequestAccessPage (`/access-requests`)

Available to any authenticated user (AF-378). A form (`api/accessRequests.ts` + TanStack Query) to request temporary, scoped access:
- Datasource `Select` populated from `GET /api/v1/access-requests/datasources` (active org datasources — not scoped to existing permissions), capability checkboxes (Read / Write / DDL, validated as at-least-one to mirror the backend `@AtLeastOneCapability`), schema/table `Select`s (`mode="tags"`), a duration `Select` (preset ISO-8601 periods 1h–7d), and a justification textarea (max 4,000, mirroring the backend `@Size`).
- The schema/table dropdowns are populated by `GET /api/v1/access-requests/datasources/{id}/schema` (AF-389) — a JIT-scoped, non-permission-gated introspection so even a requester with no grant can scope the request; tables are filtered to the selected schemas. Tag mode still allows free-text entry when introspection is unavailable, and the selections clear when the datasource changes. The page is a full-height flex column with its own scroll region (header fixed, body scrolls).
- The page surfaces the requester's status first, then the form. At the top, an active-request highlight card shows the single most relevant request — the newest one still `PENDING` (an "Awaiting approval" note) or an `APPROVED` grant that hasn't expired (with a remaining-TTL chip refreshed on a 1-minute tick). Below it, a "My requests" table lists all of the caller's requests with an `AccessStatusPill`, a remaining-TTL chip for active grants (via `utils/accessTtl.ts`), and a Cancel action on `PENDING` rows (`DELETE /api/v1/access-requests/{id}`). The new-request form sits below the table under a "New access request" heading.

### AccessRequestsQueuePage (`/admin/access-requests`)

Available to `REVIEWER` / `ADMIN` (AF-378). Mirrors `ReviewQueuePage`: a TanStack Query list of pending access requests (`GET /api/v1/admin/access-requests`), per-row Approve and Reject (the reject modal requires a comment, mirroring the backend `@NotBlank`), with optimistic cache invalidation on decision. Status/colour go through `accessGrantStatusColor` / `accessGrantStatusLabel` (single source of truth in `utils/`).

### QueryDetailPage

Full detail view for any query:

- SQL text in read-only CodeMirror block with syntax highlighting
- `AiAnalysisAccordion` — expandable section showing risk score, all issues with suggestions
- `ApprovalTimeline` — visual timeline of review stages and decisions with reviewer comments. The **Human review** stage lists every approver by display name (falling back to email via `userDisplay`) once the query reaches `APPROVED` / `EXECUTED`; multi-stage chains comma-join the names in decision order. Pending reviews still render "awaiting reviewer", and the `REJECTED` stage continues to surface the last rejecter's name + comment.
- Execution result section (if executed): rows affected, duration, timestamp. `QueryResultsTable` reads `column.restricted` from each `QueryResultColumn` returned by `GET /queries/{id}/results`; restricted columns render a lock icon + tooltip in the header and muted styling on cells (the value is already `"***"` from the backend — the frontend never has the raw value). A `Segmented` **Table / JSON** toggle switches between the flattened table and a read-only pretty-printed JSON document view (documents reconstructed from `columns`+`rows` by `src/utils/resultDocuments.ts`); the JSON view is the natural fit for MongoDB results but is available for every engine.
- **Execution-failure card** (AF-408) — when `status === 'FAILED'`, an "Execution result" card renders in the main column showing the database/driver error from the top-level `error_message` in a monospace box (labelled "Error detail"), plus the duration and completion time. Falls back to "No error detail was captured." when `error_message` is null. The same cause is mirrored into the `ApprovalTimeline` **Execution failed** stage `detail`. This mirrors the AF-249 AI-failure surface — it gives the submitter the actual reason a query failed instead of just a red "Failed" pill.
- Cancel button (if query is in `PENDING_*` status and viewer is the submitter, or `APPROVED` with a non-null `scheduled_for` — see AF-345 below)
- **Scheduled execution banner, timeline stage & metadata row** (AF-345, AF-354) — when the query carries a `scheduled_for` timestamp the metadata sidebar renders a "scheduled" row with the formatted instant. The top-of-main info `Alert` now renders for any non-terminal status (`PENDING_AI`, `PENDING_REVIEW`, `APPROVED`) — for the pending statuses it reads "If approved, this query will run automatically at …", and for `APPROVED` it keeps the original "Scheduled to run later" copy. The `ApprovalTimeline` inserts a dedicated **Scheduled run** stage between Human review and Execute (skipped on `REJECTED` / `TIMED_OUT`); the stage is `active` while the query is `APPROVED` waiting for `ScheduledQueryRunJob` to fire, `done` once `EXECUTED`, `cancelled` when the submitter cancels before the trigger, and `failed` when the eventual execution errors. The **Cancel query** button still switches its label/copy to **Cancel schedule** while `APPROVED` with `scheduled_for` (the underlying `POST /queries/{id}/cancel` call is unchanged — backend extends the allowed states to include `APPROVED` with `scheduled_for`).
- When `status === 'TIMED_OUT'`, a warning callout above the SQL block names the review plan, the configured `approval_timeout_hours`, and how long ago the timeout fired. The metadata sidebar surfaces `plan` / `timeout.hours` for any query whose datasource has a review plan, regardless of status. Status-pill colour and label come from `statusColors.ts` (`TIMED_OUT` → warn-amber palette, label "TIMED OUT").
- When `ai_analysis.failed === true` (AF-249), a warning `Alert` at the top of the main column tells the reviewer that AI analysis didn't complete and that review is proceeding without an AI recommendation; the analyzer's reason is shown both in the banner detail and in a dedicated failure variant of the AI accordion. The `RiskPill` in the accordion header switches to a neutral grey "AI N/A" variant (`failed` prop on `RiskPill`). For `REVIEWER` / `ADMIN` callers a primary "Re-analyze" button (in both the banner and the accordion) calls `POST /queries/{id}/reanalyze`; the page invalidates its TanStack Query entries on success and picks up the new analysis via the existing `ai.analysis_complete` WebSocket event. The list page (`QueryListPage`) renders the same "AI N/A" pill in the risk column when `ai_failed=true` on the list row, so a CRITICAL-looking sentinel is never mistaken for a real risk verdict.
- When the latest entry in `review_decisions[]` has `decision: REQUESTED_CHANGES` AND the query is still `PENDING_REVIEW` (AF-269), an info `Alert` at the top of the main column tells the submitter that the reviewer asked for changes — body interpolates `{{reviewer}}`, `{{when}}`, and `{{comment}}`. The reviewer decision panel itself requires a non-empty comment for both **Reject** (disabled until typed) and **Request changes** (already disabled); approving still allows an empty comment. The rejected stage of `ApprovalTimeline` carries the last `REJECTED` decision's comment (wrapped in `"…"` so the existing italic style in [ApprovalTimeline.tsx](../frontend/src/components/review/ApprovalTimeline.tsx) applies).
- When `ai_analysis === null` and the query has already advanced out of `PENDING_AI` (AF-307), the AI step is rendered as **bypassed** rather than waiting. The card title becomes "AI analysis (skipped)" with a muted body — "AI analysis was skipped — this datasource has AI analysis disabled." — and the `ApprovalTimeline` shows a gray stage labeled "AI analysis skipped" (dot uses `--fg-muted`). The skipped state is derived on the frontend (`!ai_analysis && status !== 'PENDING_AI'`); the backend persists no `ai_analyses` row on the skip path. While the query is still in `PENDING_AI`, the original "Awaiting analysis…" fallback continues to render.

### DatasourceCreateWizardPage *(ADMIN)*

Four-step flow at `/datasources/new` for adding a new datasource. Replaces a flat form so the user picks a database type first — and so the backend's on-demand JDBC driver loader (see `docs/05-backend.md` → Dynamic JDBC Driver Loading) can resolve the right driver before any connection is attempted.

1. **Type selection** — fetches `GET /datasources/types` and renders a grid of cards via `DatasourceTypeSelector`. Each card shows the logo (`icon_url`), display name, a one-line description, and a `DriverStatusBadge` (`READY` / `AVAILABLE` / `UNAVAILABLE`). Cards with `UNAVAILABLE` are disabled with a tooltip pointing the admin at the driver-cache configuration. Selecting a card advances to step 2 and seeds the form with `default_port` and `default_ssl_mode`.
2. **Connection details** — standard fields (name, host, port, database, username, password, ssl_mode), pre-filled from the type's defaults. A `JdbcUrlPreview` renders the URL live from `jdbc_url_template` as the user types. Bean-Validation errors surface inline. On first submit the wizard `POST`s `/datasources`; if the user returns to this step from **Test** and resubmits, the wizard `PUT`s the previously created record instead of issuing a second `POST` (which would 409 on the unique-name-per-org constraint). The primary button label switches from **Save and test** to **Save and continue** once a record exists.
3. **Test connection** — calls `POST /datasources/{id}/test` against the persisted datasource and surfaces latency or vendor error. **Back** returns to the connection step (record retained, resubmit becomes a `PUT`). **Next** advances to step 4 and is enabled only after a successful test; **Skip** lets admins proceed without a green test (e.g. air-gapped configuration). The first connection of a never-yet-resolved type may take 1–5 s due to driver download — show an explicit "Resolving driver…" state on the test button.
4. **Configuration** — review policy and limits before finishing: `connection_pool_size` (1–200), `max_rows_per_query` (1–1,000,000), `review_plan_id` (Select populated from `GET /review-plans`), `require_review_reads` / `require_review_writes` switches, plus the AI analysis toggle and AI config selector (from `GET /ai-configs`). Submit issues `PUT /datasources/{id}` and navigates to `DatasourceSettingsPage` with a success toast.

The wizard is the only entry point that materializes a datasource; `DatasourceListPage` links to it via a "New datasource" button.

**Custom drivers + dynamic mode.** The type-selection step groups results into two sections: a
**Bundled drivers** group (the five canonical engines) and a **Custom drivers** group (entries
where `source==="uploaded"` in `GET /datasources/types`). Each uploaded entry shows vendor +
driver class instead of the generic description. Selecting an uploaded entry passes the option
into the connection step, which:

- Sets `custom_driver_id` from `option.custom_driver_id` on submit so the backend resolves the
  per-driver classloader instead of the bundled registry.
- If the entry's `code` is `CUSTOM`, switches the connection form into **dynamic mode**: the
  host / port / database fields are replaced with a single `JDBC URL` textarea bound to
  `jdbc_url_override`. `JdbcUrlPreview` is suppressed (the URL is the URL).

When no uploaded drivers exist, the Custom drivers group renders a single help row with a deep
link to `/admin/drivers` so admins can upload one without abandoning the wizard. The selector
exposes a stable `optionKey(option)` helper so two uploaded drivers with the same target
`db_type` stay independently selectable.

### CustomDriversPage *(ADMIN — `/admin/drivers`)*

Lazy-loaded admin page listing the organization's uploaded JDBC drivers. Implemented in
`src/pages/admin/drivers/CustomDriversPage.tsx` with the upload flow in
`CustomDriverUploadModal.tsx`. Data source: `GET /datasources/drivers` via TanStack Query (key
`customDriverKeys.lists()`).

The table shows vendor, target `db_type`, fully-qualified driver class, JAR filename and size,
truncated SHA-256 (with copy button), uploader, and upload timestamp. The row action is a
single delete button gated by a `Popconfirm`; 409 `CUSTOM_DRIVER_IN_USE` errors surface via
`customDriverErrorMessage` with a count of referencing datasources.

The upload modal collects the JAR through Ant Design's `Upload.Dragger` (`.jar` extension and
50 MB size enforced client-side as a first line of defence), plus vendor name, target
`db_type` (including a "Custom / Dynamic JDBC" option), fully-qualified driver class, and the
admin-computed SHA-256. Form-level Bean-Validation mirrors the backend regexes:
`^[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+$` for the driver class and
`^[a-fA-F0-9]{64}$` for the SHA-256. On success the modal invalidates both
`customDriverKeys.lists()` and `['datasources', 'types']` so the wizard picks up the new entry
immediately.

**Logo asset licensing.** All five database type icons (`postgresql.svg`, `mysql.svg`, `mariadb.svg`, `oracle.svg`, `mssql.svg`) are sourced verbatim from [Devicon](https://devicon.dev/) pinned to release [v2.17.0](https://github.com/devicons/devicon/releases/tag/v2.17.0) and redistributed under the MIT licence. The licence text and attribution preamble live in `frontend/public/db-icons/LICENSE` — keep them next to the SVGs and update the preamble when the pinned tag is bumped. `generic.svg` is original AccessFlow artwork and remains as the fallback for any future `DbType` added before its icon has been vendored.

### DatasourceSettingsPage *(ADMIN)*

- Connection config form with live test button (`POST /datasources/{id}/test`)
- **Schema** tab (AF-443) — a searchable, hierarchical object tree (`components/datasources/SchemaObjectTree.tsx`) over the introspected schema: schemas → tables → columns, with a single filter that matches across **all three levels** (a column-name query surfaces its table and schema). The pure filter logic lives in `src/utils/schemaFilter.ts` (`filterSchema`). Each table row has a **"Preview data"** action that opens `components/datasources/SampleDataDrawer.tsx` → `SampleDataPreview.tsx`, a read-only AntD `Table` of a bounded, **RLS- and masking-aware** sample fetched via `useTableSample` (`GET /datasources/{id}/sample-rows`). Masked columns are badged with a lock icon and only ever render the masked value; a banner notes that row-level security and masking are applied, and the footer shows the row count / cap. The same `SchemaObjectTree` + `SampleDataDrawer` power the editor sidebar (`components/editor/SchemaTree.tsx`), so the cross-hierarchy search and sample preview are available while writing queries too.
- **ER diagram** tab (`components/datasources/ErDiagramTab.tsx` → `ErDiagram.tsx`) — renders the introspected schema as a `@xyflow/react` graph, one node per table (showing columns + PK markers) and one edge per foreign key (label `from → to`). Auto-layout via `dagre` (LR rank direction); read-only — `nodesDraggable={false}`. Clicking a node highlights all edges touching it (others fade to opacity 0.18); clicking the canvas background clears the selection. Loading state is a same-size `Skeleton.Node` to avoid CLS; databases without FKs (denormalized warehouses, custom drivers without `getImportedKeys`) render an `EmptyState`. The CSS in `src/styles/globals.css` already honours `prefers-reduced-motion` for all transitions.
- `PermissionMatrix` — table of all users × (can_read, can_write, can_ddl, row_limit, allowed_schemas, restricted columns count, expires_at). Restricted columns render as `"N columns"` with a hover tooltip listing the fully-qualified names; `"—"` when none.
- `GrantAccessModal` includes a `restricted_columns` multi-select populated from the datasource's introspected schema (`flattenSchemaToColumns` in `src/utils/schemaColumns.ts`). Help text explains that values are masked in results and the AI reviewer is informed but does not auto-reject.
- **Masking** tab (`components/datasources/MaskingTab.tsx`, AF-381) — a table of dynamic data masking policies (column, strategy, reveal-to summary, enabled) with a create/edit modal. The modal picks a column via an `AutoComplete` from the introspected schema, a strategy `Select` driven by `enumOptions(MASKING_STRATEGIES, maskingStrategyLabel, t)`, a conditional `visible_suffix` field shown only for `PARTIAL`, and reveal-to multi-selects for roles (`enumOptions`), groups, and users. A **live preview** renders the masked output of an editable sample value through `src/utils/maskingPreview.ts` (a pure client-side mirror of the backend `ColumnMasker` strategies; `HASH` shows an illustrative fixed digest since the real SHA-256 is computed server-side). CRUD calls `src/api/maskingPolicies.ts`; validation parity matches the backend DTO (required column ≤ 512 chars, required strategy, `visible_suffix` 1–256).
- **Row security** tab (`components/datasources/RowSecurityTab.tsx`, AF-380) — a table of row-level security policies (table, `column operator value` predicate, applies-to summary, enabled) with a create/edit modal. The structured form picks a table and column via `AutoComplete`s from the introspected schema, an operator `Select` (`enumOptions(ROW_SECURITY_OPERATORS, rowSecurityOperatorLabel, t)`), a value-source `Select` (`VARIABLE` | `LITERAL`), and a value field that switches to a `:user.*` variable `AutoComplete` (offering the `:user.id` / `:user.email` / `:user.role` / `:user.groups` built-ins) when the source is `VARIABLE`. Applies-to multi-selects target roles, groups, and users (empty = everyone). CRUD calls `src/api/rowSecurityPolicies.ts`; validation parity matches the backend DTO (required table/column/value ≤ 512 chars, required operator, required value source).
- Review plan assignment and row limit configuration

### AuditLogPage *(ADMIN)*

- Searchable, filterable table of all audit events
- Filters: date range picker, user selector, action type multi-select
- Row click opens `AuditDetailDrawer` with full metadata JSON
- **Verify chain** button in the page header calls `GET /api/v1/admin/audit-log/verify` and renders an inline dismissible alert with the outcome (`Chain valid` + rows-checked count on success; `Chain invalid` with `first_bad_row_id` / `first_bad_reason` when tampering is detected)

### AuditorDashboardPage *(AUDITOR or ADMIN)* — AF-459

The compliance-reporting dashboard at `/admin/auditor` (lazy-loaded). A `Segmented` control switches between the **Classified data access** and **Regulatory audit trail** reports; an AntD `RangePicker` sets the period (defaults to the last 90 days). Data is fetched with TanStack Query (`api/compliance.ts`, key `complianceKeys.report(type, params)`); results render in a `Table` with a `Skeleton` while loading and an `EmptyState` when no rows match. Two header buttons export the current report as a **signed PDF** or **CSV** (`exportComplianceReport`) — the download is triggered from the response blob, and the returned signature / SHA-256 are surfaced via a success toast (with a truncation warning when the row cap was hit).

The `AUDITOR` role is read-only and has no personal query workflow, so `homePathForRole` (`utils/homePath.ts`) sends an auditor's home/`*` redirects to `/admin/auditor`; every other role lands on `/dashboard` (AF-498), and `AuthGuard` bounces a role-mismatch to that same role-aware home.

### DashboardPage *(any authenticated user)* — AF-498

The personalized home at `/dashboard` (lazy-loaded; nav entry at the top of the **Workflow** group; the
default post-login landing for non-auditor roles). The header shows headline stat cards (pending
approvals, open queries, open anomalies, open suggestions) over a customizable, drag-sortable column of
widgets. Widgets (and their matching stat cards) are **role-gated** to what the current user can
actually use, mirroring the sidebar nav model — **Pending approvals** shows only for `REVIEWER`/`ADMIN`,
**My recent queries** / **Query trends** for any query-submitting role, **AI optimization suggestions**
for editor-capable roles (`ANALYST`/`REVIEWER`/`ADMIN`), and **Anomaly alerts** for `ADMIN`. The core
widgets — **Pending approvals**, **My recent queries** (+ status/risk trend sparklines via
`@ant-design/charts`), **AI optimization suggestions** (dismissable, with "open in editor"), and
**Anomaly alerts** (acknowledge/dismiss) — read from the self-scoped `api/dashboard.ts` (summary +
trends + suggestions) and `api/anomalies.ts` (`/anomalies/mine`) via TanStack Query, each with a
`Skeleton` while loading and a per-widget `EmptyState`. Widget show/hide, collapse, and **drag-and-drop
reorder** (`@dnd-kit`) persist in `preferencesStore.dashboardWidgets` (`{ visible[], order[], collapsed{} }`,
stored in `af-preferences`). A header `Switch` toggles the opt-in weekly email digest (server-persisted via
`GET`/`PUT /dashboard/digest-subscription`), and an **Export this week** dropdown downloads the signed
PDF/CSV weekly summary. "Open in editor" navigates to `/editor` with the suggestion's SQL via router
`location.state.presetSql` (the editor seeds its initial SQL from it).

### AnomaliesPage *(AUDITOR or ADMIN)* — AF-383

The behavioural-anomaly-detection (UBA) dashboard at `/admin/anomalies` (lazy-loaded; nav entry in the **System** group, ADMIN). The header carries summary charts (via `@ant-design/charts` — anomalies over time and by feature) above a filterable `Table` of `behavior_anomaly` rows. Filters mirror the backend query params (status, user, datasource, feature, date range); data is fetched with TanStack Query (`api/anomalies.ts`, key `anomalyKeys.list(params)`) and renders a `Skeleton` while loading / an `EmptyState` when nothing matches. Each row shows the flagged user, datasource, feature, score, observed-vs-baseline values, and the optional `ai_summary`. Per-row **Acknowledge** / **Dismiss** actions (ADMIN only — hidden for AUDITOR) call `POST /admin/anomalies/{id}/{acknowledge,dismiss}` with optimistic cache invalidation. The page subscribes to the `anomaly.detected` WebSocket event and invalidates `anomalyKeys` so a freshly-flagged anomaly appears without a manual refresh.

### BreakGlassLogPage *(AUDITOR or ADMIN)* — AF-385

The break-glass / emergency-access log at `/admin/break-glass` (lazy-loaded; nav entry in the **Security** group, AUDITOR/ADMIN). A filterable `Table` of `break_glass_events` (default filter `PENDING_REVIEW` — unreconciled), fetched with TanStack Query (`api/breakGlass.ts`, key `breakGlassKeys.list(params)`); filters mirror the backend params (status, datasource, user, date range). Each row shows the executing user, datasource, justification, and a `BreakGlassStatusPill`; a row click opens a `Drawer` with the executed query link, full justification, SQL, and review fields. The per-row **Acknowledge** action (ADMIN only) opens a modal with an optional reconciliation comment and calls `POST /admin/break-glass/{id}/acknowledge`, invalidating `breakGlassKeys`. The **Emergency access** flow itself lives on the editor: `QueryEditorPage` queries `GET /me/break-glass` (`meKeys.breakGlass`) and renders a danger **"Emergency access"** button — only when the selected datasource is eligible — that opens a justification-forcing confirmation modal (`breakGlassSubmit`, `POST /queries/break-glass`) and navigates to the executed query on success. A new `BreakGlassStatusPill` (`components/common/`) and `breakGlassStatusColor` / `breakGlassStatusLabel` helpers back the status rendering.

### AnomalyBadge (`components/common/AnomalyBadge.tsx`) — AF-383

A reusable badge that surfaces the **current user's own** open-anomaly count for a datasource (e.g. in the editor's datasource selector). It reads `GET /anomalies/badge?datasourceId=` via TanStack Query and renders an AntD `Badge` with the `openCount` and a tooltip carrying `maxScore`; it renders nothing when `openCount` is `0`. Status/colour go through the shared risk-colour helpers — never an inline hex.

### SetupProgressWidget (`components/common/SetupProgressWidget.tsx`)

A collapsible banner mounted in `AppLayout` directly above the route `<Outlet />`. It self-gates: returns `null` unless the current user is an `ADMIN` and every step is either configured server-side or skipped client-side. Non-admins and tenants that have finished onboarding never see it. Data comes from `GET /api/v1/admin/setup-progress` via TanStack Query (key `['setupProgress','current']`, `staleTime: 30s`).

The widget shows three numbered rows in this order:

1. **Create a review plan** → `/admin/review-plans`
2. **Add your first datasource** → `/datasources/new`
3. **Configure the AI provider** → `/admin/ai-configs/new`

Review plan is first because every datasource references a plan; AI is last because it is the most likely step to be skipped on a fresh install. Each pending step renders a primary "Set up" button plus a quieter "Skip" affordance — admins who don't want to configure that step (e.g. running without AI) can mark it skipped and see it stop nagging. Skipped steps render a "Skipped" tag with an "Undo skip" link so the decision is reversible. The progress bar counts skipped + configured equally; once all three are accounted for, the widget hides entirely.

State lives in `preferencesStore`:

- `setupProgressCollapsed` — collapse/expand state of the checklist body.
- `setupProgressSkipped: SetupStepId[]` — the IDs the admin marked skipped. Persisted to `localStorage` via Zustand `persist`, so the choice survives reloads but is intentionally per-browser (not per-org) since skipping is a UX nudge, not a policy.

The relevant mutations (create datasource, create review plan, save AI config) invalidate `setupProgressKeys.current()` on success so the widget reacts immediately when an admin completes a step the real way.

### AI configuration system prompt

Both `AiConfigCreateWizardPage` (connection step) and `AiConfigEditPage` expose an optional
**System prompt** `Input.TextArea`. Left blank, the configuration uses the built-in default
analyzer prompt; a custom value is validated client-side to contain the `{{sql}}` placeholder
(mirroring the backend `AI_CONFIG_INVALID_PROMPT` guard) and capped at 20,000 chars. A **Load /
reset to default** button fetches the built-in template from `GET /admin/ai-configs/prompt-default`
(`getDefaultAiPrompt`, query key `aiConfigKeys.promptDefault()`) and fills the editor so admins can
tweak from the default. The help text lists the available placeholders (`{{sql}}`,
`{{schema_context}}`, `{{db_type}}`, `{{language}}`).

Both pages also expose optional **Langfuse prompt name** / **Langfuse prompt label** inputs
(≤ 255 chars each). When set — and the org's Langfuse config has prompt management enabled — the
analyzer fetches its system prompt from Langfuse by that name+label instead of the system prompt
above (the label defaults to `production`).

### AI configuration RAG knowledge base (AF-336)

`RagFormSection` (shared by the create wizard's connection step and `AiConfigEditPage`) renders an
**Enable RAG** `Switch`; when on it reveals the vector-store fields (`rag_store_type` select —
**In-app (pgvector)** / **Qdrant**, `rag_top_k` 1–20, `rag_similarity_threshold` 0–1, plus
`rag_endpoint` / `rag_collection` / `rag_api_key` only for Qdrant) and a dedicated **Embeddings**
block (`embedding_provider` select — Anthropic excluded, `embedding_model`, optional
`embedding_endpoint` / `embedding_api_key`). Required rules fire only when the dependent field is
mounted, and the form mirrors the backend `RAG_CONFIG_INVALID` constraints. API keys round-trip
masked as `********`.

`KnowledgeDocumentsSection` (edit page only — the config must already be saved with RAG enabled)
lists the documents in an AntD `Table` (title / chars / chunks / status / created), with an **Add
document** modal (title + content), per-row delete, and a **Test RAG connection** button
(`testRag`). It uses TanStack Query (`aiConfigKeys.knowledge(id)`) with mutations that invalidate the
list. Ingestion embeds immediately, so the section is gated on the *persisted* `rag_enabled` flag.

### Langfuse configuration (`pages/admin/LangfuseConfigPage.tsx`)

`LangfuseConfigPage` (`/admin/langfuse`, lazy, admin-only — nav entry in the **System** group) is the
single-org form for the [Langfuse](https://langfuse.com) integration. It mirrors `SamlConfigPage`:
TanStack Query loads the config (`getLangfuseConfig`, key `langfuseConfigKeys.current()`), a
`useMutation` saves it (`updateLangfuseConfig`), and the secret key round-trips masked as `********`
(unchanged values are stripped from the payload). Fields: **Enabled**, **Host URL** (validated as a
URL, ≤ 500), **Public key**, **Secret key** (`Input.Password`), **Send analysis traces**, and **Use
Langfuse-managed prompts**. A **Test connection** button calls `testLangfuseConfig` and surfaces the
server's status message as a toast.

### Topbar (`components/common/Topbar.tsx`)

The app shell topbar contains: a mobile-nav menu button, a light/dark theme toggle, the
[language switcher](#language-switcher), the notification bell, and a sign-out button. It
deliberately has no global search input.

### Language switcher

`components/common/LanguageSwitcher.tsx` is a dropdown next to the theme toggle. It takes a `mode?: 'authenticated' | 'public'` prop (default `'authenticated'`):

- **`'authenticated'` (Topbar)** calls `GET /me/localization` (TanStack Query, key `['localization', 'me']`) to discover the org-admin's allow-list, and its menu lists only those languages by display name. On select it optimistically updates `preferencesStore.language` (which is what i18next, dayjs, and the Ant Design `ConfigProvider locale` all subscribe to in `main.tsx`) and fires `PUT /me/localization` to persist the choice on the server. On 4xx the mutation surfaces a toast via `errors.languages_save_error` but does not roll back the optimistic update — i18next has already switched and rolling back would be jarring; the user can re-select.
- **`'public'` (LoginPage)** calls the unauthenticated `GET /auth/localization-config` (TanStack Query, key `['localization', 'public']`) for the union of allowed languages across every persisted org. On select it only updates `preferencesStore.language` (and via the store, i18next) — no per-user write happens, since no user is signed in yet. After login the choice survives because the store persists to `localStorage`; the authenticated `LanguageSwitcher` then takes over and starts writing to `me/localization`.

The seven supported locales (`en`, `es`, `de`, `fr`, `zh-CN`, `ru`, `hy`) are bundled at build time in `src/locales/*.json`. Adding a new language is a single new JSON file plus an entry in `SUPPORTED_LANGUAGES`/`LANGUAGE_DISPLAY_NAMES` in `src/i18n.ts`. Translations missing from a non-English locale fall back to English at runtime via i18next's `fallbackLng`.

#### Enum labels

Backend enums (`QueryStatus`, `QueryType`, `RiskLevel`, `Role`, `DbType`, `SslMode`, `ChannelType`, `AiProvider`, `AuthProvider`, `OAuth2Provider`) are never rendered as raw `UPPER_SNAKE_CASE` to users. Instead, every value has a translation key under `enums.<enum_name>.<VALUE>` in `src/locales/en.json` (mirrored in every other locale to keep `locales.parity.test.ts` green). Use the pure helpers in [`src/utils/enumLabels.ts`](../frontend/src/utils/enumLabels.ts) — `queryStatusLabel`, `roleLabel`, `dbTypeLabel`, etc. — which take the `t` function from `useTranslation()` and a typed enum value, and return the translated label. For `<Select>` option arrays use `enumOptions(VALUES, label, t)` to map a list of enum values to `{ value, label }` pairs in one call. The value sent over the wire stays the raw enum string; only the label changes.

Add new enums by extending `frontend/src/types/api.ts` with the union, adding an `enums.<enum_name>` block to every locale JSON, exporting a helper from `enumLabels.ts`, and shipping a matching test case in `enumLabels.test.ts` — the parity test and type-safe `i18n.d.ts` will fail CI if any of these steps is skipped.

`/admin/languages` is the admin counterpart: `LanguagesConfigPage` renders three controls — multi-select for `available_languages`, single-select for `default_language` (filtered to that allow-list), single-select for `ai_review_language` (full seven-language list, independent of user choice). On save it `PUT`s `/admin/localization-config` and invalidates both `['localization', 'admin']` and `['localization', 'me']` so the topbar switcher picks up the new allow-list immediately.

`NotificationBell` (in the same folder) wraps an Ant Design `<Badge>` + `<Dropdown>`
around the bell icon. It uses TanStack Query for the unread count
(`['notifications','unread-count']`, polled every 60 s) and lazy-loads the inbox list
(`['notifications','list',{page,size}]`) when the dropdown opens. Mutations for mark-read,
mark-all-read, and delete invalidate both keys on success. Clicking a row navigates to
`/queries/{query_id}` when the payload has one and marks the row read in the same handler.
The `notification.created` WebSocket event triggers default invalidations (see the WS
default-invalidations table) so the badge and list update in near-real-time without
polling.

---

## API Access Governance pages (AF-500)

The `apigov` UI lives under `src/pages/apigov/` with API modules `src/api/apiConnectors.ts` and
`src/api/apiRequests.ts` (TanStack Query key factories mirroring `queries.ts`/`datasources.ts`),
types in `src/types/api.ts`, and protocol/auth/schema-type enum labels in `src/utils/enumLabels.ts`
(`apiProtocolLabel` / `apiAuthMethodLabel` / `apiSchemaTypeLabel`). All visible strings are `t()`-keyed
under `apiGov.*` in every registered locale.

| Route | Page | Notes |
|-------|------|-------|
| `/api-connectors` | `ApiConnectorsListPage` | Admin list of connectors (protocol / auth / active) with Skeleton + EmptyState; "New connector" CTA. |
| `/api-connectors/:id/settings` | `ApiConnectorSettingsPage` | Create/edit form (protocol, base URL, auth method + credential fields, governance toggles), schema upload + parsed-operation explorer, and the per-user "Share with team" permission grants (`can_read`/`can_write`/`can_break_glass`/`expires_at`/`allowed_operations`/`restricted_response_fields`). AntD `Form`; validation parity with the backend DTOs. |
| `/api-editor` | `ApiEditorPage` | Connector picker → operation `Select` from the parsed catalog (or free-form verb + path + body when no operation is chosen) → debounced AI risk preview (`POST /api-requests/analyze`, `RiskBadge`) → submit. A "describe the call in plain English" text-to-API box (`POST /api-requests/generate`) shows only when the connector has a schema and `text_to_api_enabled`. |
| `/api-requests` | `ApiRequestsListPage` | The caller's API requests (status, connector, verb/path, risk) with detail drill-in. |
| `/api-requests/:id` | `ApiRequestDetailPage` | Request detail: status timeline, AI risk, review decisions, and the size-capped, field-masked response snapshot once executed. |
| `/api-reviews` | `ApiReviewQueuePage` | Reviewer/admin queue of API requests awaiting review; approve/reject (self-approval blocked server-side). |

Navigation entries are added to `components/common/Sidebar.tsx` (Data group: connectors; Workflow
group: API editor / requests / reviews), role-gated like the rest of the nav.

## SQL Editor Component

Built on **CodeMirror 6** (`@codemirror/lang-sql`).

### Features

| Feature | Implementation |
|---------|---------------|
| SQL syntax highlighting | `@codemirror/lang-sql` with dialect set from selected datasource |
| Table/column autocomplete | Schema fetched from `/datasources/{id}/schema`, passed as `schema` option to `sql()` language extension |
| Keyword autocomplete | Built into `@codemirror/lang-sql` |
| On-demand AI analysis | User-triggered via the **Analyze** button → `POST /queries/analyze`; issues shown as CodeMirror gutter markers and in the AI Hint Panel. Editing the SQL clears the previous result so the analysis cannot become stale. |
| Query formatter | `sql-formatter` library called on `Ctrl+Shift+F` keyboard binding |
| Read-only mode | `EditorState.readOnly` extension set to `true` for detail/history views |
| Theme | Custom theme matching Ant Design token colors; dark/light follows OS preference |
| Risk indicator | Risk score badge in toolbar updates live as AI analysis returns |

### Collaborative editing (AF-441)

When a query is in review (`PENDING_REVIEW`) and the viewer is an authorized co-author (submitter, or a
reviewer/admin — the backend confirms assigned-reviewer eligibility on join), the read-only SQL block on
`QueryDetailPage` is replaced by `components/editor/QueryCollaboration.tsx`, which composes:

- **`CollaborativeSqlEditor.tsx`** — a CodeMirror 6 editor bound to a shared **Yjs** document via the
  `yCollab` extension from `y-codemirror.next` (remote cursors/selections + a CRDT undo manager). It does
  **not** take a controlled `value`/`onChange` — the Yjs doc owns the content. It reuses the same
  language/theme/gutter stack as `SqlEditor` (`engineMode`/`activeSyntax`/`accessflowHighlight`).
- **`PresenceBar.tsx`** — avatars of the co-authors currently in the room (colour matches each user's
  remote cursor).
- **`CommentsPanel.tsx`** + **`CommentThread.tsx`** — inline comment threads anchored to a line range,
  with reply / resolve / reopen. Data via TanStack Query (`['queries','detail',id,'comments']`, `src/api/comments.ts`);
  a `collab.comment` WebSocket frame invalidates the key.
- A **"Save as draft"** action that submits the co-authored SQL through the normal `POST /queries` path
  (re-entering review) — never a silent mutation of the query under review.

The Yjs transport is `src/realtime/collabProvider.ts` (`QueryCollabProvider`): it owns the `Y.Doc` +
`Awareness`, sends `collab.join` on construct, relays document/awareness updates over the existing
`websocketManager` (a single subscription per open query), seeds the document from the query's SQL when it
is the first joiner, and `destroy()`s on unmount. Deps: `yjs`, `y-codemirror.next`, `y-protocols`.

### AiHintPanel

Displayed below or beside the editor. Shows:

- Overall `risk_level` badge (LOW / MEDIUM / HIGH / CRITICAL)
- `risk_score` progress bar
- List of issues, each with severity icon, message, and expandable suggestion
- "Analyzing…" skeleton state while request is in flight
- Empty state if SQL is blank or analysis returns no issues

### Dry-run plan panel (AF-445)

The editor's 340px right rail carries a `Segmented` **"AI analysis | Dry run"** toggle; the second tab renders `components/editor/DryRunPanel.tsx`. A **Dry run** action in the editor header (always available — not gated on AI being enabled) calls `POST /queries/dry-run` via `dryRunQuery` (`src/api/queries.ts`) and switches the rail to the Plan tab. The panel shows the estimated impact (operation + estimated rows) and renders the execution plan through `components/editor/PlanTree.tsx` — an indented, read-only tree (`role="tree"`) of each node's operation, target, estimated rows, cost, and filter detail, flattened by the pure `src/utils/queryPlan.ts` (`flattenPlan` / `formatEstimatedRows` / `formatCost`). Engines without a plan concept render a graceful "not supported" message; when an engine returns no structured tree (e.g. Elasticsearch validation) the panel falls back to the raw plan text. A "stale" badge marks a plan whose SQL has since diverged, mirroring `AiHintPanel`.

### Query templates drawer (AF-364)

The editor's `actions` slot exposes two buttons next to **History**:

- **Templates** opens `QueryTemplatesDrawer` ([frontend/src/components/editor/QueryTemplatesDrawer.tsx](../frontend/src/components/editor/QueryTemplatesDrawer.tsx)) — an Ant `Drawer` listing templates the caller may read, filterable by `All / Mine / Team` and free-text search. Each row shows the template name, visibility, owner, a "pinned to current datasource" badge when `datasource_id` matches the editor's current datasource, plus tag chips. Row actions: `Open` always; `Delete` only when `editable === true` (the API returns this flag — owner-only).
- **Save as template** opens `SaveTemplateModal` ([frontend/src/components/editor/SaveTemplateModal.tsx](../frontend/src/components/editor/SaveTemplateModal.tsx)) — capture name, visibility (default `PRIVATE`), description, tags, and an optional "pin to current datasource" checkbox. Disabled until the editor has non-empty SQL. The current editor SQL is supplied as a prop (not a form field).

Picking **Open** stages the template in `pendingTemplate` state, which mounts `LoadTemplateModal` ([frontend/src/components/editor/LoadTemplateModal.tsx](../frontend/src/components/editor/LoadTemplateModal.tsx)). That component calls `extractPlaceholders(template.body)` ([frontend/src/utils/sqlPlaceholders.ts](../frontend/src/utils/sqlPlaceholders.ts)); when zero placeholders are present it auto-fires `onConfirm(body)` and skips the modal, otherwise it renders one required `Form.Item` per `:identifier` and substitutes via `substitutePlaceholders` on submit. The negative lookbehind `(?<![:\w])` excludes PostgreSQL `::` casts so `x::text` is never treated as a placeholder.

Each row also has a **History** action (and a clickable name) that opens `TemplateDetailDrawer` ([frontend/src/components/editor/TemplateDetailDrawer.tsx](../frontend/src/components/editor/TemplateDetailDrawer.tsx)) — an Ant `Drawer` with two tabs (AF-442):

- **Details** — name, visibility, owner, datasource pin, tags, description, and the read-only SQL body (reusing `SqlEditor` in `readOnly` mode).
- **History** — the version timeline (version number, a `change_type` badge — `CREATED` / `UPDATED` / `RESTORED` — author, and timestamp). Two `Select`s pick a **base** and a **compare** version (defaulting to the two newest); the chosen pair renders a side-by-side Git-style diff via `SqlDiffView` ([frontend/src/components/editor/SqlDiffView.tsx](../frontend/src/components/editor/SqlDiffView.tsx)), a thin wrapper over `@codemirror/merge`'s `MergeView` (both panes read-only, reusing the shared SQL highlight + theme extracted to [frontend/src/components/editor/codemirrorTheme.ts](../frontend/src/components/editor/codemirrorTheme.ts)). A **Restore this version** action (owner-only, behind a `Popconfirm`) calls `restoreTemplateVersion` and invalidates the template + version caches; restore creates a new version rather than destroying history.

API access lives in [frontend/src/api/queryTemplates.ts](../frontend/src/api/queryTemplates.ts) with the `queryTemplateKeys` factory (`all`, `lists`, `list(filters)`, `detail(id)`, `versions(id)`, `version(id, versionId)`) plus `listTemplateVersions` / `getTemplateVersion` / `restoreTemplateVersion`. Mutations invalidate `queryTemplateKeys.all` on success.

**Validation parity** — every `Form.Item` rule mirrors a backend Bean Validation constraint per the CLAUDE.md parity rule:

| Field | Backend constraint | Frontend rule |
|---|---|---|
| `name` | `@NotBlank` + `@Size(max=128)` | `required` + `max: 128` |
| `body` | `@NotBlank` + `@Size(max=100_000)` | supplied as prop; gated by `sqlNonEmpty` |
| `description` | `@Size(max=1000)` | `max: 1000` |
| `tags` | `@Size(max=10)` + per-entry `@Size(max=32)` | custom validator (length + per-entry) |
| `visibility` | `@NotNull` | `required` |

The drawer / save modal / load modal / detail drawer / diff view each ship a Vitest + React Testing Library suite under [frontend/src/components/editor/](../frontend/src/components/editor/) covering the happy path, the auto-confirm shortcut, validation, the editable-flag visibility of Edit / Delete buttons, the default two-newest diff selection, and the restore confirm flow.

---

## Theming

- All colour comes from CSS custom properties defined in `src/styles/tokens.css` (light + dark blocks) and mirrored on the Ant Design side in `src/theme/antdTheme.ts`. The two are intentionally duplicated and must be kept in sync.
- Both themes carry WCAG AA contrast on the `--fg-*` text tiers (`--fg`, `--fg-muted`, `--fg-subtle`, `--fg-faint`) against `--bg`. Components must read text colour via these tokens — never hardcode hex.
- The `<Avatar />` component picks its OKLCH lightness from `--avatar-bg-l` / `--avatar-fg-l` so initials remain legible in dark mode against the hue-rotated background. When introducing similar dynamic-colour components, follow the same token-layered pattern rather than branching on `theme` at runtime.
- Status / risk colours go through `src/utils/statusColors.ts` and `src/utils/riskColors.ts` — single source of truth, already covered by tests.
- Dark mode follows `prefers-color-scheme`; user preference lives in `preferencesStore` (Zustand), not `localStorage` directly.

---

## State Management

### authStore (Zustand)

```typescript
interface AuthStore {
  user: User | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  refreshToken: () => Promise<void>;
}
```

### WebSocket Hook

The connection itself is owned by `<RealtimeBridge />`, mounted inside `AppLayout` so it only runs under `AuthGuard` — the WebSocket module is never imported by the `/login` or `/setup` routes, and no connection is attempted before authentication. It reads `accessToken` from `authStore`, opens `${VITE_WS_URL}?token=<JWT>` on auth, reconnects with exponential backoff, and disconnects on logout (when `AppLayout` unmounts). The bridge also wires **default `queryClient.invalidateQueries`** for the standard event/key mapping (see table below) — most callers don't need to subscribe at all; they just observe their existing TanStack queries refetching.

Detail and list views (`QueryDetailPage`, `ReviewQueuePage`, etc.) **do not poll** — they rely on these WS-driven invalidations, with the manager's exponential backoff covering transient disconnects. A reload restores state if the WS is permanently unreachable.

For event-specific side effects (e.g. a toast on a new review request), subscribe inside a component:

```typescript
// useWebSocket.ts — typed subscribe wired to the singleton manager
const { subscribe } = useWebSocket();

useEffect(() =>
  subscribe('review.new_request', (data) => {
    message.info(t('realtime.new_review_request', { id: data.query_id }));
  }),
  [subscribe, message, t],
);
```

`subscribe` returns an unsubscribe function — return it from `useEffect` so the listener is removed on unmount.

#### Default invalidations

| Event                  | Invalidates                                                                |
| ---------------------- | -------------------------------------------------------------------------- |
| `query.status_changed` | `['queries','detail',query_id]` and `['queries','list']`                    |
| `query.executed`       | `['queries','detail',query_id]` and `['queries','list']`                    |
| `ai.analysis_complete` | `['queries','detail',query_id]`                                             |
| `review.new_request`   | `['reviews','pending']`                                                     |
| `review.decision_made` | `['reviews','pending']` and `['queries','detail',query_id]`                 |
| `notification.created` | `['notifications','list']` and `['notifications','unread-count']`           |
| `anomaly.detected`     | `['anomalies','list']` and `['anomalies','badge',datasource_id]` (UBA — AF-383) |

#### Reconnection

`websocketManager` (in `src/realtime/`) reconnects with exponential backoff `1s → 2s → 4s → 8s → 16s → 30s` (capped). The counter resets on a successful `onopen`. After 3 consecutive failures the log level drops to `debug` so a sustained backend outage does not spam the console. `disconnect()` clears any pending reconnect timer; re-mounting (`accessToken` becomes non-null) reopens immediately.

The Axios refresh interceptor calls `useAuthStore.setState(...)` whenever the access token rotates — the bridge's `useEffect` reacts to that change and reconnects with the new token. No extra plumbing required.

---

## Environment Variables

Two values drive the frontend: the REST base URL and the WebSocket URL. They are read through a
single module — `src/config/runtimeConfig.ts` — which exposes `getApiBaseUrl()` and `getWsUrl()`.
Resolution precedence:

1. **`window.__APP_CONFIG__`** — set synchronously by `public/runtime-config.js`, loaded from
   `index.html` *before* the React bundle. This is the production override path: replace one
   file in the served static root (Docker bind-mount, Kubernetes ConfigMap, `sed` in an
   entrypoint) to retarget the same image at a different backend without rebuilding.
2. **`import.meta.env.VITE_*`** — read by Vite at build time. Use for `npm run dev` only; the
   values are baked into the production bundle and cannot be changed at container runtime.
3. **Localhost defaults** — `http://localhost:8080` and `ws://localhost:8080/ws`.

Build-time `.env` (for `npm run dev`):

```env
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080/ws
```

Runtime override (`public/runtime-config.js`, shipped at
`/usr/share/nginx/html/runtime-config.js` in the image):

```js
window.__APP_CONFIG__ = {
  apiBaseUrl: "https://api.example.com",
  wsUrl: "wss://api.example.com/ws",
};
```

Never read `import.meta.env.VITE_*` directly from components — always go through
`getApiBaseUrl()` / `getWsUrl()` so the precedence stays consistent. See
[docs/09-deployment.md → "Frontend Runtime Configuration"](09-deployment.md#frontend-runtime-configuration)
for deployment recipes (Docker Compose, Helm).

---

## Routing Structure

```
/setup                              → SetupPage (2-step wizard: org+admin, then optional system SMTP)
/login                              → LoginPage (also renders the TOTP verification stage)
/invite/:token                      → AcceptInvitePage (public; previews + accepts a user invitation)
/forgot-password                    → ForgotPasswordPage (public; request a password-reset email)
/reset-password/:token              → ResetPasswordPage (public; previews + consumes a password-reset token)
/auth/saml/callback                 → SamlCallbackPage

/dashboard                          → DashboardPage (lazy; default post-login home for non-auditor roles, AF-498)
/editor                             → QueryEditorPage
/queries                            → QueryListPage  (header **Export CSV** button hits `GET /queries/export.csv` with the active server-side filters — `status`, `datasource_id`, `submitted_by`, `from`, `to`, `query_type`. Client-only filters on the page, namely the free-text search and risk-level select, are not sent because the backend has no equivalent filter; this matches the behaviour of the list endpoint itself. The mutation downloads via a temporary `<a>` element and shows a warning toast when the response carries `X-AccessFlow-Export-Truncated: true`.)
/queries/:id                        → QueryDetailPage
/reviews                            → ReviewQueuePage (header carries the **Enable push approvals** toggle — AF-444)
/reviews/:id/decide                 → PushDecidePage (lazy; REVIEWER/ADMIN — one-tap push decide landing with step-up auth, AF-444)
/reviews/attestations               → AttestationWorklistPage (lazy; REVIEWER/ADMIN — certify/revoke recertification items, bulk supported, AF-384)
/profile                            → ProfilePage

/datasources                        → DatasourceListPage
/datasources/new                    → DatasourceCreateWizardPage
/datasources/:id/settings           → DatasourceSettingsPage

/admin/users                        → UsersPage
/admin/groups                       → GroupsListPage (lazy; user groups — AF-353)
/admin/groups/:id                   → GroupDetailPage (lazy; group membership — AF-353)
/admin/audit-log                    → AuditLogPage
/admin/auditor                      → AuditorDashboardPage (lazy; AUDITOR or ADMIN — compliance reports + signed exports, AF-459)
/admin/anomalies                    → AnomaliesPage (lazy; AUDITOR or ADMIN — behavioural anomaly detection / UBA, AF-383)
/admin/attestation                  → CampaignListPage (lazy; ADMIN — access-recertification campaign list + create, AF-384)
/admin/attestation/:id              → CampaignDetailPage (lazy; ADMIN — campaign items + open/cancel + evidence CSV export, AF-384)
/admin/ai-configs                   → AiConfigListPage
/admin/ai-configs/new               → AiConfigCreateWizardPage (3-step wizard; connection step includes the optional system-prompt editor + RAG section)
/admin/ai-configs/:id               → AiConfigEditPage (edit connection + the per-config system prompt + RAG knowledge base)
/admin/ai-analyses                  → AiAnalysesPage (dashboard — risk-score-over-time + top categories + top submitters, lazy)
/admin/datasource-health            → DatasourceHealthPage (per-datasource pool ring + 24h query/latency/error stats, lazy)
/admin/routing-policies             → RoutingPoliciesPage (lazy; policy-as-code routing — AF-379)
/admin/notifications                → NotificationsPage
/admin/languages                    → LanguagesConfigPage
/admin/drivers                      → CustomDriversPage (admin-uploaded JDBC drivers)
/admin/saml                         → SamlConfigPage
/admin/oauth2                       → OAuth2ConfigPage (lazy)
/admin/slack                        → SlackConfigPage (lazy; Slack app config — AF-362)
/admin/langfuse                     → LangfuseConfigPage (lazy; Langfuse tracing + prompt management — AF-333)
/auth/oauth/callback                → OAuthCallbackPage (lazy, unauthenticated)
```

All routes except `/login`, `/setup`, `/invite/:token`, `/forgot-password`, `/reset-password/:token`, `/auth/saml/callback`, and `/auth/oauth/callback` are protected by an `AuthGuard` component that redirects unauthenticated users to `/login`. Admin routes additionally check `user.role === 'ADMIN'`; `/profile` is available to every authenticated user.

### Setup wizard

`SetupPage` is a two-step state machine. Step 1 collects org name + admin email/password and submits `POST /auth/setup`; the response now returns a `LoginResponse` and sets the refresh cookie so the SPA can call admin endpoints as the freshly-created admin. Step 2 is optional system-SMTP configuration that posts to `PUT /admin/system-smtp` — the **Skip for now** button bypasses it and lands on `/queries`. Users can configure or change SMTP later from `/admin/notifications` (the **System SMTP** card sits above the channels grid).

### User invitations on `/admin/users`

The primary action button is now a `Dropdown.Button` — the default click sends an email invitation (`POST /admin/users/invitations`), while the dropdown menu still exposes the legacy "Create with password" path (`POST /admin/users`). A **Pending invitations** table below the user list shows invitations and exposes per-row resend / revoke actions (`POST /admin/users/invitations/{id}/resend`, `DELETE /admin/users/invitations/{id}`).

The **Edit user** modal includes an **Attributes** key/value editor (AF-380, a `Form.List`) bound to `users.attributes`. Current values are loaded via `GET /admin/users/{id}/attributes` (`getUserAttributes`) and saved through the existing `PUT /admin/users/{id}` with the `attributes` field. These attributes resolve in row-security predicates as `:user.<key>` (up to 50 entries; key ≤ 128, value ≤ 512 chars — validation parity with the backend).

### User groups and reviewer scope (AF-353)

`GroupsListPage` (`/admin/groups`, lazy, admin-only) is a paginated `<Table>` of the org's user
groups (name → link to detail, description, `member_count`, `created_at`) with create / edit
(`Modal` + `Form`, `name` `1–128`, `description` `max 512`) and `Popconfirm` delete. Clicking a
group name opens `GroupDetailPage` (`/admin/groups/:id`): a members `<Table>` (email, display
name, a `source` tag — `MANUAL` blue / `IDP` gold — and `joined_at`) with **Add member** (a
searchable user `Select` filtered to active non-members) and per-row remove. API access lives in
[frontend/src/api/groups.ts](../frontend/src/api/groups.ts) (`groupKeys` factory); mutations
invalidate `groupKeys.all`. `IDP`-sourced memberships come from SSO group→group mapping and are
managed by the IdP, not removable by hand.

Groups (and individual users) can be assigned as **per-datasource reviewers** so each team only
sees the review queues it owns; the client for that endpoint is
[frontend/src/api/datasourceReviewers.ts](../frontend/src/api/datasourceReviewers.ts)
(`listReviewers` / `addReviewer` / `removeReviewer` against `/datasources/{id}/reviewers`).

### Routing policies (AF-379)

`RoutingPoliciesPage` (`/admin/routing-policies`, lazy, admin-only) is the admin surface for the
policy-as-code routing engine. The nav entry **Routing policies** sits in the **Security** group
next to **Review plans**. The page renders a `<Table>` of the org's policies in priority order with
priority up/down reorder controls (`PUT /admin/routing-policies/reorder`), an action pill
(`AUTO_APPROVE` / `AUTO_REJECT` / `REQUIRE_APPROVALS` / `ESCALATE`), an `enabled` `Switch` toggle, a
human-readable condition summary, and per-row edit / delete. Create / edit open a `Modal` with a
**guided condition builder** — the builder produces a single-level ALL (AND) / ANY (OR) of leaf
conditions, each optionally negated (NOT); there is no raw-JSON editor. API access lives in
[frontend/src/api/routingPolicies.ts](../frontend/src/api/routingPolicies.ts); the form↔wire mapping
helper is [frontend/src/pages/admin/routingPolicyForm.ts](../frontend/src/pages/admin/routingPolicyForm.ts);
types (`RoutingPolicy`, `RoutingCondition`, `RoutingAction`, …) live in `src/types/api.ts`.
`QueryDetailPage` shows a **matched-policy** alert when `GET /queries/{id}` returns a non-null
`matched_policy`.

### OAuth 2.0 sign-in

`LoginPage` renders one "Continue with &lt;Provider&gt;" button per active row returned by
`GET /api/v1/auth/oauth2/providers` (a public endpoint, queried via TanStack Query with a
short 30 s `staleTime`). Click → `window.location.assign(${API_BASE_URL}/api/v1/auth/oauth2/authorize/<provider>)`
so Spring Security can take over the redirect dance.

`OAuthCallbackPage` parses `?code=...` or `?error=...` from the URL, calls
`exchangeOAuth2Code(code)` to swap the one-time code for a `LoginPayload`, hands it to
`useAuthStore.setSession`, and navigates to `/editor`. On `?error=...` it shows a localised
message (keys under `auth.oauth_callback.error.*`) plus a "Back to sign in" button.

`OAuth2ConfigPage` (`/admin/oauth2`) is admin-only. It renders one Ant `Tabs` per supported
provider (Google, GitHub, Microsoft, GitLab). Each tab is a `Form` with `client_id`,
`client_secret` (masked passthrough — leave `********` to keep the existing secret),
`scopes_override`, `tenant_id` (Microsoft only), `default_role`, and an `active` toggle.
Saving invalidates `oauth2ConfigKeys.all`. Deleting clears the row and the cache so the
button disappears from `/login` after the page is refreshed.

### Slack app configuration (AF-362)

`SlackConfigPage` (`/admin/slack`, lazy, admin-only) is the single-org form for the interactive
Slack app that powers Approve / Reject from a Slack message. The `Form` collects `app_id`,
`default_channel_id`, a masked `bot_token` and `signing_secret` (`********` passthrough — leave
the mask to keep the stored secret; required only on first save), and an `active` toggle. Three
actions sit below a divider: **Save** (`upsertSlackAppConfig`), **Test** (`testSlackAppConfig`,
disabled until configured — surfaces the `status`/`detail` of a probe), and **Delete** (gated by
`Popconfirm`). All mutations invalidate `slackAppConfigKeys.all`. API access lives in
[frontend/src/api/slack.ts](../frontend/src/api/slack.ts). Validation parity: `app_id` `max 64`,
`default_channel_id` `max 64`, `bot_token` `max 512`, `signing_secret` `max 255` — each mirroring
the backend `UpsertSlackAppConfigRequest` constraints.

### Profile page and 2FA

`/profile` is composed of four Ant Design cards in `src/pages/profile/`:

- `DisplayNameForm` — Ant `Form` with a single input bound to `useMutation(updateProfile)`. On success it invalidates `meKeys.current` and patches `authStore.user.display_name` so the top-bar reflects the new name immediately.
- `ChangePasswordForm` — current / new / confirm fields (`min: 8, max: 128`). Hidden when `profile.auth_provider === 'SAML'`. On success the backend revokes all refresh tokens; the frontend explicitly calls `authStore.clear()` and navigates to `/login` so the user can re-authenticate cleanly.
- `SlackLinkSection` (AF-362) — links the AccessFlow account to a Slack user so approve/reject buttons attribute to the right person. When unlinked, **Generate code** (`createSlackLinkCode`) issues a one-time `/accessflow link <code>` snippet (copyable) to run in Slack; when linked it shows the mapped `slack_user_id` and an **Unlink** action (`unlinkSlack`, `Popconfirm`). Backed by `slackLinkKeys` in [frontend/src/api/slack.ts](../frontend/src/api/slack.ts).
- `TwoFactorSection` — branches on `profile.totp_enabled`. Enabled state shows a "Disable 2FA" button that opens `TotpDisableDialog` (password challenge). Disabled state opens `TotpEnrollmentDialog`, a 3-step `Steps` modal: (1) render the backend-supplied `qr_data_uri` in an `<img>` plus the raw secret for manual entry, (2) collect a 6-digit code and `POST /me/totp/confirm`, (3) display the 10 backup recovery codes with copy-to-clipboard and an explicit "I've saved these" acknowledgement before closing. SAML accounts see an info alert instead.

### Two-stage TOTP login

`LoginPage` is a single component with a `stage: 'CREDENTIALS' | 'TOTP'` flag.

1. The user submits email and password. The frontend calls `authStore.login(email, password)`.
2. If the backend returns `401 { error: 'TOTP_REQUIRED' }` the form switches to the TOTP stage (email and password stay in component state, never persisted) and renders a single 6-digit input.
3. On second submit, `authStore.login(email, password, totpCode)` re-posts to `/auth/login`. `TOTP_INVALID` keeps the form on the TOTP stage with an inline error; success navigates to `/editor` as usual. A "Back to sign-in" link returns to stage 1.

The Axios response interceptor in `api/client.ts` skips the auto-refresh path for `/auth/*` URLs so the `TOTP_REQUIRED` 401 reaches the LoginPage component without being absorbed.

When the refresh attempt **itself** fails (the cookie is gone or revoked, the server replies 401 on `/auth/refresh`), the interceptor clears the auth store, surfaces an `auth.session_expired` toast via the `messageBridge`, and navigates to `/login` via the `navigationBridge`. Both bridges are module-level handles bound from inside `<AntdApp>` — `MessageBridgeBinder` wires `App.useApp().message`, and `NavigationBridgeBinder` wires React Router's `useNavigate()`. The redirect is a soft SPA navigation (no full page reload), so the AntD message portal survives across the route change and the toast remains visible on `/login`. If the navigation bridge hasn't bound yet (e.g. before the React tree mounts), the interceptor falls back to `window.location.assign('/login')`. The end-to-end failure path is covered by `e2e/tests/auth-session-expiry.spec.ts`.

The Topbar replaces the standalone logout button with an Ant `Dropdown` whose menu items are **Profile settings** (`/profile`) and **Sign out**. On narrow viewports the display-name pill collapses to the icon via `topbar.css`.

## Progressive Web App & Web Push (AF-444)

The app is an installable PWA with an offline-capable review-queue shell and one-tap push approvals.

- **Build & service worker.** `vite-plugin-pwa` runs in `injectManifest` mode: we own the service
  worker source at [`src/sw.ts`](../frontend/src/sw.ts) (so the push / notificationclick handlers are
  hand-written), while Workbox injects the precache manifest for the offline shell. `src/sw.ts` is a
  `ServiceWorkerGlobalScope` file excluded from the app `tsconfig`/ESLint (it is bundled by
  vite-plugin-pwa's esbuild). The SW precaches the app shell, serves cached `index.html` for
  navigations when offline, renders the push notification with **Approve** / **Reject** actions, and on
  `notificationclick` deep-links to `/reviews/{id}/decide?action=…`.
- **Manifest & icons.** Generated from the plugin `manifest` option (`manifest.webmanifest`,
  `display: standalone`, `start_url: /reviews`), with a maskable SVG icon at `public/pwa-icon.svg`.
- **Registration.** `main.tsx` registers `/sw.js` manually (production only) — never via an inline
  script — so the strict CSP (`default-src 'self'`) is honoured. `frontend/nginx.conf` serves `sw.js`
  and `manifest.webmanifest` with `no-store` so updates propagate.
- **Subscription.** `hooks/usePushSubscription.ts` owns the opt-in: it requests notification permission,
  subscribes via the registered SW with the deployment VAPID key (`GET /push/vapid-public-key`), and
  registers / removes the subscription on the backend (`src/api/push.ts`). The
  `components/review/PushApprovalsToggle` (on the review-queue header) drives it; it is hidden on
  browsers without push support and disabled when notifications are blocked. Pure helpers
  (`urlBase64ToUint8Array`, `serializePushSubscription`) live in `src/utils/push.ts`.
- **One-tap decide.** `pages/reviews/PushDecidePage.tsx` (`/reviews/:id/decide`) is the focused,
  mobile-friendly landing the notification opens. It shows the query summary, then requires **step-up
  auth** — password, or a TOTP code when 2FA is enrolled — via `POST /auth/step-up` (`src/api/stepup.ts`)
  before committing the decision through `decideFromPush` → `POST /reviews/{id}/decide`. A single tap
  never commits; the self-approval guard is enforced server-side regardless of channel.
