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
│   │   │   └── PageHeader.tsx      # Consistent page header with breadcrumbs + docs deep-link
│   │   │
│   │   ├── editor/
│   │   │   ├── SqlEditor.tsx       # CodeMirror 6 SQL editor component
│   │   │   ├── AiHintPanel.tsx     # Inline AI analysis results panel
│   │   │   ├── SchemaTree.tsx      # Sidebar schema/table browser
│   │   │   ├── QueryAuthoringPanel.tsx # Shared /editor authoring surface (page + group drawer — #559)
│   │   │   ├── useQueryAuthoring.ts    # Transient authoring state: analyze/dry-run staleness, syntax, templates, live SQL review (#865)
│   │   │   ├── sqlReviewDiagnostics.ts # Pure mapper: SQL review findings + AI issues → CodeMirror Diagnostics (#865)
│   │   │   ├── SqlReviewFindingsStrip.tsx # Live rule findings under the editor (#865)
│   │   │   └── EditorToolbar.tsx   # Format, run, datasource selector
│   │   │
│   │   ├── review/
│   │   │   ├── ApprovalTimeline.tsx # Visual timeline of review stages
│   │   │   ├── SqlReviewFindingList.tsx # One row per SQL review finding — shared by the editor strip and the detail card (#865)
│   │   │   ├── RejectModal.tsx     # Modal w/ required-comment textarea for /reviews reject
│   │   │   ├── BulkDecisionModal.tsx # Shared-comment modal for /reviews bulk approve/reject/request-changes
│   │   │   ├── CostEstimatePanel.tsx # Pre-flight cost / blast-radius block on the detail page (AF-624)
│   │   │   ├── ApprovalPredictionPanel.tsx # Advisory approval-likelihood block on the detail page (AF-645)
│   │   │   ├── ApprovalPredictionBadge.tsx # Neutral percentage badge, shared by the panel and the queue column
│   │   │   └── PushApprovalsToggle.tsx # Opt-in to push notifications for the review queue
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
│   │   ├── audit/
│   │   │   ├── AuditLogTable.tsx   # Searchable audit event table
│   │   │   └── AuditDetailDrawer.tsx # Slide-in detail for single event
│   │   │
│   │   └── help/                    # In-app documentation help chat (AF-906)
│   │       ├── HelpChatLauncher.tsx  # Fixed launcher; renders null unless availability.enabled
│   │       ├── HelpChatPanel.tsx     # Drawer shell (placement=right, mask={false})
│   │       ├── HelpChatMessageList.tsx # Transcript — safe markdown subset, no auto-linking
│   │       ├── HelpChatMarkdown.tsx  # Renders the subset; no anchor/img case exists (#919)
│   │       ├── helpMarkdown.ts       # Pure, bounded parser; its node union cannot carry a URL (#919)
│   │       ├── HelpChatCitations.tsx # [n] chips; the only anchors on the panel
│   │       ├── HelpChatComposer.tsx  # Question box, maxLength mirrors the backend @Size
│   │       ├── routeLabel.ts         # pathname → nav.* label key (never a path or an id)
│   │       └── help-chat.css
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
│   │   ├── useDebouncedValue.ts    # Trailing-edge debounce of a value (#865)
│   │   ├── useSqlReviewLint.ts     # Debounced live SQL review evaluation for the editor (#865)
│   │   ├── useHelpChat.ts          # Help chat availability + conversation + ask (AF-906)
│   │   ├── useGovernanceDomains.ts # The org's governance-domain visibility flags (#926)
│   │   └── useCurrentUser.ts       # Auth state, role checks
│   │
│   ├── layouts/
│   │   └── AppLayout.tsx           # Main app shell with sidebar nav (the only layout — admin
│   │                               # pages and the auth pages style themselves)
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
│   │   │   ├── ReviewHubPage.tsx     # Unified review queue — one tab per request kind (#772)
│   │   │   ├── QueryReviewsTab.tsx   # Queries tab: pending query reviews for current reviewer
│   │   │   └── legacyReviewRedirects.tsx # /api-reviews and /deployment-reviews → the hub
│   │   │
│   │   ├── requestGroups/            # Grouped requests (chaining — AF-501)
│   │   │   ├── GroupBuilderPage.tsx        # Build + reorder + submit a bundle (also mounts /request-groups/:id/edit — #559)
│   │   │   ├── GroupMemberCard.tsx         # Compact step summary (seq, kind, target, preview, risk)
│   │   │   ├── GroupMemberEditDrawer.tsx   # Full-parity per-step authoring drawer (#559)
│   │   │   ├── RequestGroupListPage.tsx    # Grouped-request history
│   │   │   └── RequestGroupDetailPage.tsx  # Ordered step progress (live)
│   │   │
│   │   ├── schemaChange/             # Schema change governance (#883, epic #870)
│   │   │   ├── SchemaChangeSetListPage.tsx   # Change sets + ladder strip + create modal
│   │   │   ├── SchemaChangeSetDetailPage.tsx # Statement editor, promotion ladder, history
│   │   │   └── SchemaDriftPage.tsx           # Drift findings grouped by environment
│   │   │
│   │   ├── datasources/
│   │   │   ├── DatasourceListPage.tsx
│   │   │   ├── DatasourceCreateWizardPage.tsx  # Multi-step create flow with type selection
│   │   │   └── DatasourceSettingsPage.tsx
│   │   │
│   │   └── admin/
│   │       ├── UsersPage.tsx
│   │       ├── AuditLogPage.tsx
│   │       ├── AuditSinksPage.tsx    # External SIEM/WORM audit sinks (#628)
│   │       ├── AnomaliesPage.tsx     # Behavioural anomaly detection (UBA — AF-383)
│   │       ├── BreakGlassLogPage.tsx # Break-glass / emergency-access log (AF-385)
│   │       ├── AIConfigPage.tsx
│   │       ├── NotificationsPage.tsx
│   │       ├── SamlConfigPage.tsx    # SAML 2.0 SSO configuration
│   │       ├── ScimConfigPage.tsx    # SCIM 2.0 provisioning config + bearer tokens (#621)
│   │       ├── LangfuseConfigPage.tsx # Langfuse tracing + prompt management
│   │       ├── HelpAgentConfigPage.tsx # In-app help chat agent settings + corpus status (AF-906)
│   │       └── helpAgentRetrieval.ts   # Pure: why retrieval cannot be enabled, from the bound config
│   │
│   ├── store/
│   │   ├── authStore.ts             # Current user, JWT, login/logout actions
│   │   └── preferencesStore.ts      # Theme, sidebar collapse, collapsed nav sub-sections (AF-837), language, dashboard widget layout + trends range (AF-498; hidden[]/order[]/collapsed{}/size{}), help chat drawer open + active session id (AF-906); persist v2
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
│   │   ├── downloadBlob.ts          # Saves an export blob to disk (shared by every CSV/PDF export)
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

The primary user-facing page. Since #559 its authoring surface is extracted into the shared
`components/editor/QueryAuthoringPanel.tsx` + `useQueryAuthoring.ts` pair — the panel renders the
schema tree, toolbar (syntax toggle / format), text-to-SQL bar, CodeMirror editor, AI/dry-run right
rail, and the template drawer/modals; the hook owns the transient authoring state (analysis + dry-run
staleness, syntax, template choreography). `QueryEditorPage` keeps the page concerns (datasource
selection, justification, schedule, `submission_reason`, submit + break-glass) and the group
builder's `GroupMemberEditDrawer` mounts the very same panel, so the two surfaces never drift.
Features:

- **Datasource selector** — dropdown of datasources the user has access to, loads schema tree on selection
- **CodeMirror SQL editor** — see SQL Editor section below. `SqlEditor` picks its CodeMirror language from the **engine-mode registry** (`src/utils/engineModes.ts`, AF-418), keyed by `db_type`: relational datasources get the SQL language with a PostgreSQL/MySQL dialect; a `MONGODB` datasource highlights as **JavaScript** (shell syntax) or **JSON** (JSON-command syntax), driven by the generic `syntax` prop. New engines register their syntaxes/highlighting in the registry — no `SqlEditor` edits.
- **Query-syntax selector** — when the engine mode declares more than one syntax (MongoDB: Shell / JSON), the toolbar shows an Ant Design `Segmented` control; the SQL **Format** button renders only when the mode's `canFormat` is true (SQL engines). The submitted text still goes in the `sql` field; the backend auto-detects the form. **Text-to-query** is gated on the mode's `supportsTextToSql`, which is now `true` for every shipped engine including NoSQL (AF-439); it is still additionally gated per-datasource on `text_to_sql_enabled` + a bound `ai_config_id`. On a successful generation `TextToSqlBar` returns a `syntax` hint that `QueryEditorPage` applies (e.g. switching a MongoDB draft to Shell or JSON) before inserting the draft. The registry also supplies `defaultResultView`, which `QueryDetailPage` passes to `QueryResultsTable` (MongoDB results open in the JSON document view).
- **Suggestions rail** (#776) — the right rail's third `Segmented` tab, beside AI analysis and Dry run. Lists draft queries mined from the organisation's own approved history on the selected datasource (`GET /datasources/{id}/query-suggestions`), each with the evidence behind it — how often it was approved, by how many people, how recently. **Apply as draft** fills the editor through `applyHistorySuggestion`, which tags the change `history_suggestion` so the submission carries `submission_reason=HISTORY_SUGGESTION`; Submit re-gates on a fresh analysis exactly as it does for an applied AI suggestion. The backend has already filtered the list to what the viewer may run, so the panel contains no permission logic of its own, and its subtitle says explicitly that an applied draft is still analysed and reviewed.
- **AI Hint Panel** — displays AI analysis after the user clicks the **Analyze** button. When the SQL is edited (including via **Apply as draft**) the analysis is **kept on screen but marked stale** — a "stale" badge plus a warning banner with a **Re-run analysis** button — so the user can still read the risks and apply the remaining optimization suggestions; Submit re-gates until the query is re-analyzed (staleness is derived by comparing the live SQL against the snapshot the analysis ran against).
- **Analyze button** — explicit user action that calls `POST /queries/analyze`. Rendered only when the selected datasource has `ai_analysis_enabled=true` and a non-null `ai_config_id`.
- **Live SQL review** (#865, epic #860) — no button: `hooks/useSqlReviewLint.ts` evaluates the draft the author has paused on (400 ms trailing debounce via `useDebouncedValue`) through the read-only `POST /sql-review/evaluate`, a TanStack `useQuery` keyed `['sqlReview', 'evaluation', datasourceId, sql]` (`sqlReviewKeys.evaluation`) and `enabled` only for a selected datasource, a non-blank draft under the 100 000-character limit, and a relational engine (`utils/sqlReview.ts` → `isSqlReviewSupported`; the server's `applicable:false` also turns the surface off). `placeholderData: keepPreviousData` keeps the previous findings on screen while the next evaluation is in flight — CodeMirror maps them through the author's edits. A 422 `INVALID_SQL` is an expected mid-keystroke state rendered as a quiet "Can't parse this yet" hint (`isInvalidSqlError`), never a toast; any other failure yields no findings, silently. Results render twice: as CodeMirror **diagnostics** in `SqlEditor` (see the SQL Editor section) and in the **findings strip** under the editor (`components/editor/SqlReviewFindingsStrip.tsx` → shared `components/review/SqlReviewFindingList.tsx`: severity pill via `riskColors.sqlReviewSeverityColor`, `L{n}` or "Statement N" for envelope members the parser gave no position, and the backend-localized message). The group builder's member drawer inherits both through the shared panel. **Submit stays enabled on a BLOCK** — blocking escalates to a human, it never rejects — and the Submit tooltip states how many blocking findings will require human approval.
- **Data-budget indicator** (#942, `components/editor/DataBudgetIndicator.tsx`) — above the review-plan preview, a compact card fed by `GET /datasources/{id}/data-budgets/me` (`dataBudgetKeys.mine`): one progress bar per applying budget with the remaining rows / bytes (`formatBytes`) and the window (`formatWindow`), a warning alert once a budget passes its warning threshold (`budgetNearlyUsed`), and an error alert when one is used up that says whether new reads are refused (`REJECT`) or need a reviewer (`REQUIRE_REVIEW`). It renders nothing when no budget applies, so an unbudgeted datasource looks exactly as before.
- **Justification field** — required text area for the reason behind the query
- **Scheduled execution picker** (AF-345) — optional Ant Design `DatePicker` with `showTime` that records `scheduled_for` on the submission payload. Past dates are disabled and a "scheduled time must be in the future" hint disables Submit when the user picks an already-elapsed instant. Leave empty for the default immediate-review flow.
- **Recurrence picker** (#627, `components/editor/RecurrencePicker.tsx`) — a mode `Select` (Does not repeat / Fixed interval / Cron schedule (UTC)) plus the rule input for the chosen mode (interval presets 15m–7d composing ISO-8601 durations, or a free-text 6-field Spring cron with a UTC hint) and a mandatory series-end `DatePicker`. Mutually exclusive with the scheduled-execution picker (setting one clears the other); Submit is gated until the rule is present, the cron has 6 fields, and the end time is in the future — mirroring the backend validation. Records `recurrence_rule` + `recurrence_until` on the submission payload.
- **Submit button** — sends `POST /queries`, transitions to status tracking view. When the datasource has AI configured, Submit is disabled until a fresh AI analysis exists for the current SQL. A BLOCK-severity SQL review finding never disables it (#865); the tooltip explains that the query will require human approval.
- **Status tracker** — real-time status updates via WebSocket (`PENDING_AI` → `PENDING_REVIEW` → `APPROVED` → `EXECUTED`)

### ReviewHubPage — the unified review queue (#772)

`/reviews` is one page for every request kind the viewer may review. It is guarded any-of
`QUERY_REVIEW` / `API_REQUEST_REVIEW` / `DEPLOYMENT_REVIEW` and renders one AntD tab per queue,
**only for the permissions the viewer holds** — a reviewer with `API_REQUEST_REVIEW` and no
`DEPLOYMENT_REVIEW` sees no Deployments tab, not an empty one:

| `?tab=` | Permission | Governance domain (#926) | Body | Formerly |
|---|---|---|---|---|
| `queries` | `QUERY_REVIEW` | — (always on) | `QueryReviewsTab` (`pages/reviews/`) | the whole `/reviews` page |
| `api` | `API_REQUEST_REVIEW` | `apis` | `ApiReviewsTab` (`pages/apigov/`) | `/api-reviews` |
| `deployments` | `DEPLOYMENT_REVIEW` | `deployments` | `PendingDeploymentsTab` (`pages/deployments/DeploymentReviewTabs.tsx`) | `/deployment-reviews` |
| `rollbacks` | `DEPLOYMENT_REVIEW` | `deployments` | `RollbackReviewsTab` (same file) | `/deployment-reviews?tab=rollbacks` |

- The tab registry (`TABS` in `ReviewHubPage.tsx`) plus `REVIEW_HUB_TAB_PERMISSION` and
  `REVIEW_HUB_TAB_DOMAIN` in the pure `utils/reviewHubTabs.ts` are the places a new queue is added;
  `reviewHubPath(tab)` is the single owner of the `/reviews?tab=` URL shape (used by
  `NotificationBell`, the dashboard tiles and the redirects). Erasure, attestation and request-group
  reviews are still separate pages.
- A tab needs **permission AND an enabled governance domain** (#926) to be offered:
  `visibleReviewHubTabs(user, domains)` is the tab bar, `permittedReviewHubTabs(user)` is
  permission alone. `REVIEW_HUB_PERMISSIONS` — the `/reviews` route guard and the sidebar entry —
  stays permission-only, so hiding a domain never turns a reachable page into a 403.
- The tab is synced to `?tab=` (`replace`, like the old deployment page). A bare `/reviews` is
  left alone and shows the first visible tab; a `?tab=` the viewer may not see, or that does not
  exist, is replaced by the first visible tab so the URL never lies about what is on screen. An
  **explicit `?tab=` the viewer holds the permission for wins even when its domain is off**, and
  the tab bar keeps that tab, so notification and dashboard deep links into a de-emphasised queue
  never dead-end. A reviewer whose only queue is in a switched-off domain still lands on it
  (`resolveReviewHubTab` falls through visible → permitted → `null`).
- **Only the active tab is mounted** (rendered beside the `Tabs`, never as `items[].children`),
  so inactive queues are never fetched and never leak hidden rows into the DOM.
- Each tab label carries its pending count (`Queries · 3`) from `hooks/usePendingReviewCounts.ts`:
  one `size=1` probe per queue the viewer may work, keyed through the existing `reviewKeys` /
  `apiRequestKeys.reviewQueue` / `deploymentReviewKeys` / `deploymentRollbackReviewKeys` factories so
  the WebSocket invalidations still hit them, 30 s polling as a backstop. `AppLayout` reads the same
  hook's `total` for the sidebar badge, so the badge and the tabs share one set of cache entries.
- The header actions — the **Enable push approvals** toggle (AF-444) and Refresh — render only
  while the Queries tab is active; push decisions cover query reviews only.
- `/api-reviews` and `/deployment-reviews[?tab=rollbacks]` are kept as `<Navigate replace>`
  redirects (`pages/reviews/legacyReviewRedirects.tsx`) so notification and docs deep links keep
  resolving.

#### Queries tab (`QueryReviewsTab`)

Available to users holding `QUERY_REVIEW`:

- Paginated list of queries in `PENDING_REVIEW` status assigned to this reviewer, rendered as an Ant Design `<Table>` with `rowSelection` so the reviewer can drive both single-row and batch flows from the same page.
- Columns: ID (short hash + full UUID + tooltip), query type, AI risk badge, approval likelihood, datasource, submitter (avatar + email), time elapsed, optional per-row status badge for failed bulk rows, and a row-actions column with per-row approve/reject buttons.
- The **AI risk** cell also carries a "N block" pill (#865, `data-testid="sql-review-blocking"`, `--risk-high` tokens) when `sql_review_blocking_count > 0` on the queue row — the deterministic-rule reason the query could not auto-approve and is in front of a human. The tooltip spells it out; the findings themselves are on the detail page.
- The **approval-likelihood** column (AF-645) renders `approval_probability` as a neutral percentage badge (`components/review/ApprovalPredictionBadge.tsx`, tooltip explaining it is an advisory statistical estimate) and a muted `—` when the row is unscored or its persisted prediction is a skipped / failed sentinel. The queue payload carries only `approval_probability` and no skip reason, so that dash's tooltip points at the detail page ("No approval likelihood for this query. Open it to see why.") rather than guessing which of the sentinels applies.
- A **Delegated** tag column (#622) appears when `delegated_for` is set on the row — the caller is eligible only through a colleague's out-of-office delegation. It is null when they are eligible in their own right, even if a delegation would also have covered it.
- Quick approve inline on the row. Reject opens `RejectModal` ([components/review/RejectModal.tsx](../frontend/src/components/review/RejectModal.tsx)) — a comment is required (the confirm button stays disabled until the textarea is non-whitespace), mirroring the backend `@NotBlank` constraint on `POST /reviews/{id}/reject`.
- Selecting one or more rows reveals a **sticky action bar** above the table with "Approve selected", "Reject selected", "Request changes", and "Clear selection". Each button opens the shared `BulkDecisionModal` ([components/review/BulkDecisionModal.tsx](../frontend/src/components/review/BulkDecisionModal.tsx)), which collects one comment to apply to every selected query and submits to `POST /api/v1/reviews/bulk`. After submit, successful rows leave the queue; failed rows stay selected with a per-row status tag (Forbidden / Not pending review / Not found) so the reviewer can retry.
- Row click opens the full detail page (`/queries/:id`); the row-actions column buttons stop propagation so they don't trigger navigation.
- The ID column renders a small clock icon (with a "Scheduled to run at …" tooltip) when the row carries a non-null `scheduled_for`, so users can spot scheduled queries at a glance from `/queries` without opening the detail page. A sync/repeat icon renders the same way when the row is a recurring-series parent (#627, `recurring=true` on the list row).
- `ApprovalTimeline` shows which reviewers in the plan have already decided

### RequestAccessPage (`/access-requests`)

Available to any authenticated user (AF-378, AF-567). A form (`api/accessRequests.ts` + TanStack Query) to request temporary, scoped access to a datasource **or an API connection**:
- A resource-type `Segmented` control (Datasource | API Connection, default Datasource — AF-567) switches the form branch and resets resource-specific fields.
- Datasource branch: datasource `Select` populated from `GET /api/v1/access-requests/datasources` (active org datasources — not scoped to existing permissions), capability checkboxes (Read / Write / DDL, validated as at-least-one to mirror the backend `@AtLeastOneCapability`), schema/table `Select`s (`mode="tags"`), and the query pre-approval checkbox (#582).
- Connector branch (AF-567): connector `Select` populated from `GET /api/v1/access-requests/connectors` (label `name (protocol)`), capability checkboxes **without DDL** (not meaningful for connectors; break-glass is never self-requestable), and an optional allowed-operations multi-select populated from `GET /api/v1/access-requests/connectors/{id}/operations` (empty = all operations) — mirroring the backend `@ExactlyOneResource` shape rules.
- Shared: a duration `Select` (preset ISO-8601 periods 1h–7d) and a justification textarea (max 4,000, mirroring the backend `@Size`).
- The schema/table dropdowns are populated by `GET /api/v1/access-requests/datasources/{id}/schema` (AF-389) — a JIT-scoped, non-permission-gated introspection so even a requester with no grant can scope the request; tables are filtered to the selected schemas. Tag mode still allows free-text entry when introspection is unavailable, and the selections clear when the datasource changes. The page is a full-height flex column with its own scroll region (header fixed, body scrolls).
- The page surfaces the requester's status first, then the form. At the top, an active-request highlight card shows the single most relevant request — the newest one still `PENDING` (an "Awaiting approval" note) or an `APPROVED` grant that hasn't expired (with a remaining-TTL chip refreshed on a 1-minute tick). Below it, a "My requests" table lists all of the caller's requests with a resource cell (kind tag + datasource/connector name), an `AccessStatusPill`, a remaining-TTL chip for active grants (via `utils/accessTtl.ts`), an operations-count tag on operation-scoped connector requests, and a Cancel action on `PENDING` rows (`DELETE /api/v1/access-requests/{id}`). The new-request form sits below the table under a "New access request" heading.

### AccessRequestsQueuePage (`/admin/access-requests`)

Available to `REVIEWER` / `ADMIN` (AF-378, AF-567). Mirrors the review hub's `QueryReviewsTab`: a TanStack Query list of pending access requests (`GET /api/v1/admin/access-requests`) — both datasource and connector kinds in one queue, each row carrying a kind tag plus the resource name and (for connector requests) an operations-count tag with the allow-list in its tooltip — per-row Approve and Reject (the reject modal requires a comment, mirroring the backend `@NotBlank`), with optimistic cache invalidation on decision. Status/colour go through `accessGrantStatusColor` / `accessGrantStatusLabel` (single source of truth in `utils/`).

### QueryDetailPage

Full detail view for any query:

- SQL text in read-only CodeMirror block with syntax highlighting. When the executed query's snapshot recorded an `effective_sql` (#937 — the statement as it actually ran, row-security / soft-delete rewrite spliced in, bound values shown as `?`), `pages/queries/QuerySqlView.tsx` adds a `Segmented` **Submitted / Effective / Diff** toggle over the card body (defaulting to Submitted) with a one-line note that bound values are never stored; **Diff** reuses `SqlDiffView` (submitted on the left, effective on the right). With no `effective_sql` the card is the plain SQL block, unchanged
- `AiAnalysisAccordion` — expandable section showing risk score, all issues with suggestions
- `ApprovalTimeline` — visual timeline of review stages and decisions with reviewer comments. A decision taken under an out-of-office delegation (#622) is attributed as "Bob (on behalf of Alice)", so the timeline never implies the delegator acted themselves. The **Human review** stage lists every approver by display name (falling back to email via `userDisplay`) once the query reaches `APPROVED` / `EXECUTED`; multi-stage chains comma-join the names in decision order. Pending reviews still render "awaiting reviewer", and the `REJECTED` stage continues to surface the last rejecter's name + comment.
- Execution result section (if executed): rows affected, duration, timestamp. `QueryResultsTable` reads `column.restricted` from each `QueryResultColumn` returned by `GET /queries/{id}/results`; restricted columns render a lock icon + tooltip in the header and muted styling on cells (the value is already `"***"` from the backend — the frontend never has the raw value). A `Segmented` **Table / JSON** toggle switches between the flattened table and a read-only pretty-printed JSON document view (documents reconstructed from `columns`+`rows` by `src/utils/resultDocuments.ts`); the JSON view is the natural fit for MongoDB results but is available for every engine. Beside the toggle, a policy-driven **Export** control (#626): the server-computed decision (`GET /queries/{id}/results/export-decision` via `src/api/resultExport.ts`) drives a CSV/PDF `Dropdown` when allowed, or a disabled button whose tooltip + `aria-label` carry the denial reason (naming the matched classifications) when denied; the button hides entirely while the decision is unavailable. Downloads stream the signed export through `utils/downloadBlob.ts` and surface a warning toast when `X-AccessFlow-Export-Truncated` was set.
- **Execution-failure card** (AF-408) — when `status === 'FAILED'`, an "Execution result" card renders in the main column showing the database/driver error from the top-level `error_message` in a monospace box (labelled "Error detail"), plus the duration and completion time. Falls back to "No error detail was captured." when `error_message` is null. The same cause is mirrored into the `ApprovalTimeline` **Execution failed** stage `detail`. This mirrors the AF-249 AI-failure surface — it gives the submitter the actual reason a query failed instead of just a red "Failed" pill.
- Cancel button (if query is in `PENDING_*` status and viewer is the submitter, or `APPROVED` with a non-null `scheduled_for` — see AF-345 below)
- **Scheduled execution banner, timeline stage & metadata row** (AF-345, AF-354) — when the query carries a `scheduled_for` timestamp the metadata sidebar renders a "scheduled" row with the formatted instant. The top-of-main info `Alert` now renders for any non-terminal status (`PENDING_AI`, `PENDING_REVIEW`, `APPROVED`) — for the pending statuses it reads "If approved, this query will run automatically at …", and for `APPROVED` it keeps the original "Scheduled to run later" copy. The `ApprovalTimeline` inserts a dedicated **Scheduled run** stage between Human review and Execute (skipped on `REJECTED` / `TIMED_OUT`); the stage is `active` while the query is `APPROVED` waiting for `ScheduledQueryRunJob` to fire, `done` once `EXECUTED`, `cancelled` when the submitter cancels before the trigger, and `failed` when the eventual execution errors. The **Cancel query** button still switches its label/copy to **Cancel schedule** while `APPROVED` with `scheduled_for` (the underlying `POST /queries/{id}/cancel` call is unchanged — backend extends the allowed states to include `APPROVED` with `scheduled_for`).
- **Recurring series surfaces** (#627) — a parent query (non-null `recurrence_rule`) renders: a series banner whose title/body derive the state (Active with rule + next run + until; Completed once `recurrence_next_run_at` is null past `recurrence_until`; Halted with the fail-closed `recurrence_halted_reason`; Cancelled), an **Occurrences** card (paginated `Table` over `GET /queries/{id}/occurrences` — id link to the child detail page, status pill, rows, duration, executed-at, error), metadata rows (`recurrence` / `ends` / `next run`), and a **Cancel series** action — visible to the submitter *and* to `QUERY_REVIEW` holders (the reviewer kill-switch). The Execute button is hidden for recurring parents (the backend 409s — manual execution would consume the parent's `APPROVED` status). An occurrence row (non-null `recurring_parent_id`) renders a banner linking back to its series parent. `buildTimelineStages` replaces the Execute stage with a **Recurring** stage (active / completed / halted / cancelled) for parents.
- When `status === 'TIMED_OUT'`, a warning callout above the SQL block names the review plan, the configured `approval_timeout_hours`, and how long ago the timeout fired. The metadata sidebar surfaces `plan` / `timeout.hours` for any query whose datasource has a review plan, regardless of status. Status-pill colour and label come from `statusColors.ts` (`TIMED_OUT` → warn-amber palette, label "TIMED OUT").
- When `ai_analysis.failed === true` (AF-249), a warning `Alert` at the top of the main column tells the reviewer that AI analysis didn't complete and that review is proceeding without an AI recommendation; the analyzer's reason is shown both in the banner detail and in a dedicated failure variant of the AI accordion. The `RiskPill` in the accordion header switches to a neutral grey "AI N/A" variant (`failed` prop on `RiskPill`). For `REVIEWER` / `ADMIN` callers a primary "Re-analyze" button (in both the banner and the accordion) calls `POST /queries/{id}/reanalyze`; the page invalidates its TanStack Query entries on success and picks up the new analysis via the existing `ai.analysis_complete` WebSocket event. The list page (`QueryListPage`) renders the same "AI N/A" pill in the risk column when `ai_failed=true` on the list row, so a CRITICAL-looking sentinel is never mistaken for a real risk verdict.
- When the latest entry in `review_decisions[]` has `decision: REQUESTED_CHANGES` AND the query is still `PENDING_REVIEW` (AF-269), an info `Alert` at the top of the main column tells the submitter that the reviewer asked for changes — body interpolates `{{reviewer}}`, `{{when}}`, and `{{comment}}`. The reviewer decision panel itself requires a non-empty comment for both **Reject** (disabled until typed) and **Request changes** (already disabled); approving still allows an empty comment. The rejected stage of `ApprovalTimeline` carries the last `REJECTED` decision's comment (wrapped in `"…"` so the existing italic style in [ApprovalTimeline.tsx](../frontend/src/components/review/ApprovalTimeline.tsx) applies).
- **Cost-estimate card** (AF-624) — a "Cost estimate" `DetailCard` (`components/review/CostEstimatePanel.tsx`) renders the query's persisted pre-flight blast-radius estimate from `cost_estimate` on `GET /queries/{id}`: the exact affected-row count for UPDATE/DELETE ("Affected rows (exact)"), the plan's estimated rows / scan type / cost, the warehouse's **estimated bytes scanned** when the engine reports one (#941), and the execution-plan tree (reusing the editor's `PlanTree` + `utils/queryPlan.ts`), falling back to the raw plan text. State machine mirrors the AI card: while `status === 'PENDING_AI'` and `cost_estimate` is null it shows "Computing the cost estimate…"; a null estimate past that shows "No cost estimate is available for this query."; `supported=false` renders the localized `unsupported_reason` (still showing the exact count when one was computed); `failed=true` renders a warning with `error_message`. The `query.estimate_complete` WebSocket event invalidates `['queries','detail',id]` so the panel fills in without polling.
- **SQL review findings card** (#865) — a "SQL review findings" `DetailCard` renders `sql_review_findings` from `GET /queries/{id}` through the shared `components/review/SqlReviewFindingList.tsx`, **only when at least one rule fired** (a clean query keeps its page short). When any finding is `BLOCK` a note above the list explains that the blocking rule(s) fired at submission, so the query could not auto-approve and was sent to human review — a BLOCK never rejects. Messages arrive localized from the backend; the frontend never formats a rule message.
- **Approval-likelihood card** (AF-645) — rendered **only for viewers holding `QUERY_REVIEW` who are not the query's submitter** (`canSeeApprovalPrediction = isReviewer && submitterId !== user.id` — deliberately *not* the decision panel's `canDecide`, which additionally requires `PENDING_REVIEW`; the card stays useful after a decision lands): the prediction is a triage aid for whoever decides, and showing a submitter how their peers are likely to vote on their own open request would invite cancel-and-resubmit gaming. The backend applies the same predicate in `QueryReadController` and omits `approval_prediction` from the response entirely for those callers, so this gate is defence in depth rather than the only thing standing between a submitter and their own number. An "Approval likelihood" `DetailCard` (`components/review/ApprovalPredictionPanel.tsx`) renders the advisory approval-outcome prediction from `approval_prediction` on `GET /queries/{id}`: the label "Historical approval likelihood" next to the probability as a rounded percentage (`components/review/ApprovalPredictionBadge.tsx`), plus a permanent note — "Statistical estimate based on this organization's past review decisions. Advisory only — it never approves or rejects anything." States: with no row yet it shows "Computing the approval likelihood…" while `status === 'PENDING_AI'`, or while `PENDING_REVIEW` and the query's `updated_at` is inside a five-minute grace window — scoring fires off the transition into review and takes seconds, so past that the row is never coming (a query that predates the feature, or a listener that never ran) and the copy switches to "No approval likelihood is available for this query."; `failed=true` renders a warning; `skipped=true` — or any row that carries no probability — renders an info notice with the **client-localized** `skipped_reason` machine token (`DISABLED` → "…switched off for this organization.", `MODEL_NOT_SERVING` → "Not enough review history yet.", any other/unknown token → the generic unavailable copy, never the raw token; the token set is modelled as the `ApprovalPredictionSkipReason` union in `types/api.ts` so a new backend token breaks the lookup map at compile time). The badge deliberately uses the neutral `--fg-muted` / `--bg-sunken` / `--border` tokens rather than the `--risk-*` palette, and all copy is strictly non-directive — the number is a triage signal, never a recommendation to approve or reject. The `query.prediction_complete` WebSocket event invalidates `['queries','detail',id]` and `['reviews','pending']` so both the card and the queue column fill in without polling.
- When `ai_analysis === null` and the query has already advanced out of `PENDING_AI` (AF-307), the AI step is rendered as **bypassed** rather than waiting. The card title becomes "AI analysis (skipped)" with a muted body — "AI analysis was skipped — this datasource has AI analysis disabled." — and the `ApprovalTimeline` shows a gray stage labeled "AI analysis skipped" (dot uses `--fg-muted`). The skipped state is derived on the frontend (`!ai_analysis && status !== 'PENDING_AI'`); the backend persists no `ai_analyses` row on the skip path. While the query is still in `PENDING_AI`, the original "Awaiting analysis…" fallback continues to render.

### DatasourceCreateWizardPage *(ADMIN)*

Four-step flow at `/datasources/new` for adding a new datasource. Replaces a flat form so the user picks a database type first — and so the backend's on-demand JDBC driver loader (see `docs/05-backend.md` → Dynamic JDBC Driver Loading) can resolve the right driver before any connection is attempted.

1. **Type selection** — fetches `GET /datasources/types` and renders a grid of cards via `DatasourceTypeSelector`. Each card shows the logo (`icon_url`), display name, a one-line description, and a `DriverStatusBadge` (`READY` / `AVAILABLE` / `UNAVAILABLE`). Cards with `UNAVAILABLE` are disabled with a tooltip pointing the admin at the driver-cache configuration. Selecting a card advances to step 2 and seeds the form with `default_port` and `default_ssl_mode`.
2. **Connection details** — standard fields (name, host, port, database, username, password, ssl_mode), pre-filled from the type's defaults. A `JdbcUrlPreview` renders the URL live from `jdbc_url_template` as the user types. Bean-Validation errors surface inline. On first submit the wizard `POST`s `/datasources`; if the user returns to this step from **Test** and resubmits, the wizard `PUT`s the previously created record instead of issuing a second `POST` (which would 409 on the unique-name-per-org constraint). The primary button label switches from **Save and test** to **Save and continue** once a record exists.
3. **Test connection** — calls `POST /datasources/{id}/test` against the persisted datasource and surfaces latency or vendor error. **Back** returns to the connection step (record retained, resubmit becomes a `PUT`). **Next** advances to step 4 and is enabled only after a successful test; **Skip** lets admins proceed without a green test (e.g. air-gapped configuration). The first connection of a never-yet-resolved type may take 1–5 s due to driver download — show an explicit "Resolving driver…" state on the test button.
4. **Configuration** — review policy and limits before finishing: `connection_pool_size` (1–200), `max_rows_per_query` (1–1,000,000), `review_plan_id` (Select populated from `GET /review-plans`), `require_review_reads` / `require_review_writes` switches, the optional `environment` select (#865 — see DatasourceSettingsPage below), plus the AI analysis toggle and AI config selector (from `GET /ai-configs`). On a BigQuery, Snowflake or Databricks datasource (`supportsBytesCap` in `src/utils/bytesCap.ts`) the step also offers the optional **Max bytes scanned per query** (`BytesInput`, rule mirroring the backend `@Min(1)`) and the **When no bytes estimate is available** select (`REQUIRE_REVIEW` / `REJECT`, #941); neither is ever sent for another engine. Submit issues `PUT /datasources/{id}` and navigates to `DatasourceSettingsPage` with a success toast.

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
- **Bytes-scanned cap** (#941) — on BigQuery, Snowflake and Databricks only (`supportsBytesCap`), the limits section adds **Max bytes scanned per query** (`BytesInput`, optional, `@Min(1)` parity) and **When no bytes estimate is available** (`REQUIRE_REVIEW` / `REJECT`). Emptying a stored cap sends `clear_max_bytes_scanned_per_query: true` (`toBytesCapUpdate`), since a null cap means "unchanged" to the API
- **Schema** tab (AF-443) — a searchable, hierarchical object tree (`components/datasources/SchemaObjectTree.tsx`) over the introspected schema: schemas → tables → columns, with a single filter that matches across **all three levels** (a column-name query surfaces its table and schema). The pure filter logic lives in `src/utils/schemaFilter.ts` (`filterSchema`). Each table row has a **"Preview data"** action that opens `components/datasources/SampleDataDrawer.tsx` → `SampleDataPreview.tsx`, a read-only AntD `Table` of a bounded, **RLS- and masking-aware** sample fetched via `useTableSample` (`GET /datasources/{id}/sample-rows`). Masked columns are badged with a lock icon and only ever render the masked value; a banner notes that row-level security and masking are applied, and the footer shows the row count / cap. The same `SchemaObjectTree` + `SampleDataDrawer` power the editor sidebar (`components/editor/SchemaTree.tsx`), so the cross-hierarchy search and sample preview are available while writing queries too.
- **ER diagram** tab (`components/datasources/ErDiagramTab.tsx` → `ErDiagram.tsx`) — renders the introspected schema as a `@xyflow/react` graph, one node per table (showing columns + PK markers) and one edge per foreign key (label `from → to`). Auto-layout via `dagre` (LR rank direction); read-only — `nodesDraggable={false}`. Clicking a node highlights all edges touching it (others fade to opacity 0.18); clicking the canvas background clears the selection. Loading state is a same-size `Skeleton.Node` to avoid CLS; databases without FKs (denormalized warehouses, custom drivers without `getImportedKeys`) render an `EmptyState`. The CSS in `src/styles/globals.css` already honours `prefers-reduced-motion` for all transitions.
- `PermissionMatrix` — table of all users × (can_read, can_write, can_ddl, can_break_glass, row_limit, allowed_schemas, restricted columns count, denied columns count, denied tables count, denied shapes count, expires_at). Restricted columns render as `"N columns"` with a hover tooltip listing the fully-qualified names; `"—"` when none. The break-glass column uses the shared `PermCell` check/dash renderer (colour + icon, never colour alone).
- `GrantAccessModal` includes a `can_break_glass` switch (after the read/write/DDL trio, default off, AF-385) and a `restricted_columns` multi-select populated from the datasource's introspected schema (`flattenSchemaToColumns` in `src/utils/schemaColumns.ts`). The break-glass help text explains it is an **additional** emergency capability that bypasses review but still requires the underlying read/write/DDL capability at submission time; the existing "at least one of read/write/DDL" rule is preserved and is **not** satisfied by break-glass alone. The restricted-columns help text explains that values are masked in results and the AI reviewer is informed but does not auto-reject. A `denied_columns` multi-select (#935), fed by the same column options, is shown only for the JSqlParser engines (`supportsDeniedColumns` in `src/utils/deniedColumns.ts`, which reuses `SQL_REVIEW_DB_TYPES`). Its `Form.Item` validator mirrors the backend `@Pattern`/`@Size`: each entry must be `table.column` or `schema.table.column`, at most 200 entries. Both the user and the group permission tables gain a "Denied columns" column: a red "N columns" tag with the columns in a tooltip, or "—". Two `mode="tags"` selects, **Denied schemas** and **Denied tables** (#939), follow; their options come from the introspected schema, and table options are schema-qualified (`schema.table`, narrowed to the selected allowed schemas when any are chosen) because a bare name would deny that table in every schema. Free-typed entries are accepted. Validators in `src/utils/deniedTables.ts` mirror the backend `@Size`/`@NotBlank` (at most 50 schemas and 200 tables, no blank entry). Both permission tables render a "Denied tables" column: a red count tag whose tooltip lists the denied schemas as `schema.*` and then the denied tables (`deniedTableEntries`), or "—". A **Denied query shapes** multi-select (#940) follows, offering the eight `QueryShape` values labelled through `queryShapeLabel` (`enums.query_shape.*`); like denied columns it is shown only for the JSqlParser engines (`supportsDeniedShapes` in `src/utils/deniedShapes.ts`), and its `Form.Item` rule mirrors the backend `@Size(max = 8)`. An empty selection is sent as `null`. Both permission tables render a "Denied shapes" column: a red "N shapes" tag whose tooltip lists the labelled shapes, or "—". On a BigQuery, Snowflake or Databricks datasource (`supportsBytesCap`) the modal also offers a **Bytes-scanned cap** (`bytes_scanned_limit_override`, `BytesInput`, rule mirroring `@Min(1)`, #941), and both permission tables gain a "Bytes cap" column; neither appears for another engine, where the backend would answer 422 `BYTES_SCANNED_CAP_NOT_SUPPORTED`. Permissions are create-only (revoke + re-grant); there is no edit flow.
- **Masking** tab (`components/datasources/MaskingTab.tsx`, AF-381) — a table of dynamic data masking policies (column, strategy, reveal-to summary, enabled) with a create/edit modal. The modal picks a column via an `AutoComplete` from the introspected schema, a strategy `Select` driven by `enumOptions(MASKING_STRATEGIES, maskingStrategyLabel, t)`, a conditional `visible_suffix` field shown only for `PARTIAL`, and reveal-to multi-selects for roles (`enumOptions`), groups, and users. A **live preview** renders the masked output of an editable sample value through `src/utils/maskingPreview.ts` (a pure client-side mirror of the backend `ColumnMasker` strategies; `HASH` shows an illustrative fixed digest since the real SHA-256 is computed server-side). CRUD calls `src/api/maskingPolicies.ts`; validation parity matches the backend DTO (required column ≤ 512 chars, required strategy, `visible_suffix` 1–256).
- **Row security** tab (`components/datasources/RowSecurityTab.tsx`, AF-380) — a table of row-level security policies (table, `column operator value` predicate, applies-to summary, enabled) with a create/edit modal. The structured form picks a table and column via `AutoComplete`s from the introspected schema, an operator `Select` (`enumOptions(ROW_SECURITY_OPERATORS, rowSecurityOperatorLabel, t)`), a value-source `Select` (`VARIABLE` | `LITERAL`), and a value field that switches to a `:user.*` variable `AutoComplete` (offering the `:user.id` / `:user.email` / `:user.role` / `:user.groups` built-ins) when the source is `VARIABLE`. Applies-to multi-selects target roles, groups, and users (empty = everyone). CRUD calls `src/api/rowSecurityPolicies.ts`; validation parity matches the backend DTO (required table/column/value ≤ 512 chars, required operator, required value source).
- **Row limits** tab (`components/datasources/RowLimitTab.tsx`, #934) — a table of per-table row-limit policies (`schema.table`, max rows, applies-to summary, enabled) with a create/edit modal. The modal picks an optional schema and a table through `AutoComplete`s fed by the introspected schema (the table list narrows to the chosen schema; an empty schema means "any schema"), a `max_rows` `InputNumber` (1–1,000,000) and applies-to multi-selects for roles, groups and users (empty = everyone, admins included). CRUD calls `src/api/rowLimitPolicies.ts`; validation parity matches the backend DTO (required table ≤ 255 chars, optional schema ≤ 255 chars, required `max_rows` 1–1,000,000).
- **Data budgets** tab (`components/datasources/DataBudgetTab.tsx`, #942) — labelled "Data budgets · N", right after *Row limits*. A table of per-user data-volume budgets (name, limits, window, breach action, applies-to summary, enabled) with a create/edit modal: `name` (required, ≤ 120), a row limit (`InputNumber`, ≥ 1) and a result-size limit (the shared `components/common/BytesInput.tsx`, ≥ 1 byte) with a form-level rule that at least one is set, a rolling window entered as an amount plus an hours/days unit (`splitWindow` / `joinWindow` in `src/utils/dataBudget.ts`, 60–44,640 minutes), a breach-action `Select` (`REQUIRE_REVIEW` / `REJECT`), an optional warning threshold (1–99 %), and applies-to multi-selects for roles, groups and users (empty = every user of the datasource). CRUD calls `src/api/dataBudgets.ts` (`dataBudgetKeys`); validation parity matches the backend `DataBudgetRequest` bounds (`DATA_BUDGET_*` constants in `src/utils/dataBudget.ts`).
- **Exports** tab (`components/datasources/ExportPolicyTab.tsx`, #626) — a table of result-export governance policies (mode, details — row cap / deny classifications, applies-to summary, enabled) with a create/edit modal. The modal picks a mode `Select` (`enumOptions(EXPORT_POLICY_MODES, exportPolicyModeLabel, t)`), a conditional `row_cap` field shown only for `ROW_CAP` (1–1,000,000), a conditional deny-classifications multi-select shown only for `DENY_CLASSIFIED` (empty = any classification), and applies-to multi-selects for roles, groups, and users (empty = everyone, no admin bypass). A **live watermark preview** renders the exact header/footer stamp through `src/utils/watermarkPreview.ts` (a pure client-side mirror of the backend `ResultExportWatermark` templates) for the `WATERMARK`/`ROW_CAP` modes. CRUD calls `src/api/exportPolicies.ts`; validation parity matches the backend DTO (required mode, `row_cap` 1–1,000,000).
- **Discovery** tab (`components/datasources/DiscoveryTab.tsx`, AF-623) — the sensitive-data discovery worklist. A settings card edits the per-datasource config (`GET`/`PUT /datasources/{id}/discovery/config`: enable switch, sample size 10–1000, interval 1–720 h, AI-pass toggle — validation parity with the backend DTO) with a **Scan now** button (`POST …/discovery/scan`, 202) and the last-scan time / error. Below, a paginated findings table (`GET …/discovery/findings`, status `Segmented` filter defaulting to `PENDING`, including the AF-659 `Stale` segment whose tag carries a hover hint explaining why the proposal aged out) shows column, proposed classification, detector (AI rationale as tooltip), confidence with match ratio, redacted sample, and status. `STALE` rows stay selectable alongside `PENDING` ones, since bulk-dismissing aged proposals is the point of retiring them. Row selection surfaces a sticky bulk bar (the `AttestationWorklistPage` pattern) whose **Confirm/Dismiss selected** call `POST …/discovery/findings/bulk-decision` with partial-success handling — failed rows stay selected with a per-row status tag. Confirming invalidates the classification + masking query keys so the sibling tabs refresh (the tag + derived policy appear there). API module `src/api/discovery.ts` (`discoveryKeys`); the tab badge on `DatasourceSettingsPage` shows the PENDING-findings count.
- Review plan assignment and row limit configuration
- **Environment** select (#865, also on the wizard's configuration step) — optional `DEVELOPMENT` / `TEST` / `STAGING` / `PRODUCTION` with an explicit "Not set (organization default rules)" first option (`utils/datasourceEnvironment.ts`; AntD `Select` cannot hold `null` as a real option). It picks which SQL review ruleset applies to the datasource's queries. Because the API treats an omitted/null `environment` as "unchanged", saving "not set" sends `environment: null, clear_environment: true`; the wizard simply omits the field. The backend has no Bean Validation on the enum, so the form carries no rule.

### AuditLogPage *(ADMIN)*

- Searchable, filterable table of all audit events
- Filters: date range picker, user selector, action type multi-select
- Row click opens `AuditDetailDrawer` with full metadata JSON
- The action filter includes the data-budget actions (#942): `QUERY_DATA_BUDGET_ENFORCED` and `DATA_BUDGET_CREATED` / `_UPDATED` / `_DELETED`
- **Verify chain** button in the page header calls `GET /api/v1/admin/audit-log/verify` and renders an inline dismissible alert with the outcome (`Chain valid` + rows-checked count on success; `Chain invalid` with `first_bad_row_id` / `first_bad_reason` when tampering is detected)

### AuditorDashboardPage *(AUDITOR or ADMIN)* — AF-459

The compliance-reporting dashboard at `/admin/auditor` (lazy-loaded). A `Segmented` control switches between the **Classified data access** and **Regulatory audit trail** reports; an AntD `RangePicker` sets the period (defaults to the last 90 days). Data is fetched with TanStack Query (`api/compliance.ts`, key `complianceKeys.report(type, params)`); results render in a `Table` with a `Skeleton` while loading and an `EmptyState` when no rows match. The regulatory audit trail has an **Effective SQL** column (#937) beside **SQL**, showing `—` when no rewrite occurred. Two header buttons export the current report as a **signed PDF** or **CSV** (`exportComplianceReport`) — the download is triggered from the response blob, and the returned signature / SHA-256 are surfaced via a success toast (with a truncation warning when the row cap was hit).

### Over-provisioned access (#625)

`/admin/over-provisioned-access` (lazy, `ACCESS_USAGE_REPORT_VIEW` — ADMIN and AUDITOR) lists every
standing grant with the usage evidence folded out of the audit log, worst first. Server-side paging
and filters (resource kind, a multi-select of recommendations, user id) on the `AuditLogPage` shell;
`api/grantUsage.ts` with `grantUsageKeys.report(filters)`; the CSV export reuses the same filters
object minus page/size and warns on the `X-AccessFlow-Export-Truncated` header.

The nullable figures carry meaning and must never be coerced for display: a null
`granted_target_count` means the grant is **unrestricted** (no allow-list to under-use, so it is
never `OVER_SCOPED`), and a null `days_since_last_use` means **never used** — rendering it as "0
days ago" would say the opposite. `INSUFFICIENT_DATA` shares that null timestamp but means only
"too new to judge", so it renders muted as "Not enough history yet" rather than critical-red.

The same evidence appears on `AttestationWorklistPage` and `CampaignDetailPage` through
`components/attestation/AttestationUsageCell.tsx`, which additionally distinguishes *no usage data*
(the grant was not summarised when the campaign opened) from *never used* — a missing measurement
must not read as an argument for revoking. `AttestationCapabilities` was extracted from the two
pages at the same time, and the worklist gained server-side paging (it previously rendered a fixed
50-row slab, which would have silently truncated the new staleness-first ordering); changing page
clears the bulk selection so a decision can never apply to rows that scrolled out of view.

### Privileged access (#968)

`/admin/privileged-access` (lazy, `DATASOURCE_PERMISSION_MANAGE` **or** `ACCESS_USAGE_REPORT_VIEW`
— the `AuthGuard` array form is any-of, as is the sidebar's `permissions`) is the org-wide
counterpart of the over-provisioned report: every identity that can reach data *without* a
permission row — `QUERY_ADMIN` holders and break-glass grantees — one row each. `api/privilegedAccess.ts`
with `privilegedAccessKeys.report(filters)`; server-side paging and two filters (bypass kind, user
id) on the same shell as the over-provisioned page. Columns: identity, role (name plus a *System
role* / *Custom role* line), one `Pill` per bypass kind (`standingBypassKindColor` — `QUERY_ADMIN`
critical, `BREAK_GLASS` high), the break-glass datasources (each with *Direct grant* or *via
<group>* and its expiry or *never expires*), and two evidence cells — queries submitted and
break-glass runs, each a count with when it last happened. A null `last_*_at` renders as **Never**,
never as a date, and an empty grant list as a dash. The subtitle states the advisory posture; there
is no action on the page. `AuditLogPage`'s filter list gained `PRIVILEGED_ACCESS_REPORT_VIEWED`.

### Access simulation and decision traces (#1066)

`/admin/access-simulations` (lazy, `DATASOURCE_PERMISSION_MANAGE` **or** `ACCESS_USAGE_REPORT_VIEW`)
is the UI over the four read-only endpoints of #859 and #967. `AccessSimulationPage` has two tabs,
filtered by permission: **Query trace** (`DATASOURCE_PERMISSION_MANAGE` — the endpoint's own gate)
and **Who has access** (either permission, so an auditor lands on the reverse index alone).

- **One renderer, three call sites.** `components/policies/DecisionTraceView.tsx` renders the shared
  `{ resulting_status?, steps[], caveats[] }` envelope for all three kinds. The step enums stay
  separate in `types/api.ts` (`QueryDecisionStepKind` — 12 values, `ApiDecisionStepKind`,
  `DeploymentDecisionStepKind`) and each call site passes its own label function
  (`queryDecisionStepLabel` / `apiDecisionStepLabel` / `deploymentDecisionStepLabel`). Every step is
  rendered, a `SKIP` included (dashed border, *Skipped* pill, the reason line). Outcome colours come
  from `decisionStepOutcomeColor`; an absent `resulting_status` reads *Refused — no request would be
  created*.
- **Details formatting is pure.** `components/policies/decisionTraceDetails.ts` turns a step's
  `details` map into labelled rows in the backend's insertion order. Known keys have a label under
  `decisionTrace.detail.*`, and an unknown key is humanised rather than dropped. Lists render one
  line each: contributing grants (*Direct grant* / *via group X* plus expiry), reviewers and plan
  approvers.
- **Routing policies render as a table.** The `ROUTING_POLICIES` step renders its `policies[]` as a
  table via `RoutingPoliciesTraceTable`, with columns priority, name, action, required approvals,
  matched and decisive. The decisive row is highlighted, so a near-miss is visible. The backend
  omits the list on refusal paths and when the assumed AI outcome is `FAILED`; the UI then says
  *Policy list not evaluated on this path* rather than showing an empty table.
- **Read-only by construction.** Simulations are POSTs run through `useMutation`, with no
  query-key factory (`api/accessSimulations.ts`, `api/apiCallSimulations.ts`,
  `api/deploymentSimulations.ts`). The primary button says *Trace* / *Simulate*, and a success
  raises no toast. Failures render `apiErrorMessage` in an `EmptyState`.
- **Risk pair.** The query form enforces the risk pair client-side: `risk_level` and `risk_score`
  are each required once the other is set. The backend accepts a level alone, scores it -1 and
  silently skips every risk policy.
- **Reverse index.** `components/access/EffectiveAccessPanel.tsx` takes a datasource, a table
  (`AutoComplete` fed by the datasource schema; free text allowed) and a capability (a `Select`),
  and reads `effectiveAccessKeys.list(filters)` with server paging. Its columns:
  - identity
  - granted
  - table scope
  - sources — kind, group, allow-list entry, expiry
  - the `QUERY_ADMIN` bypass (derived from a `QUERY_ADMIN_BYPASS` source)
  - **can break glass**, a column of its own — "can write anyway" is not "can write"
  - effective expiry
- **Settings-page tabs.** The same renderer backs a **Simulate** tab on
  `ApiConnectorSettingsPage` (after *Classification*; `ApiConnectorSimulateTab` — user, operation or
  free verb, AI outcome, risk level) and on `DeploymentPipelineSettingsPage` (`?tab=simulate`,
  before *CI setup*; `PipelineSimulateTab` — environment, user, version, AI outcome, risk level,
  optional `scheduled_for` and the hypothetical instant `at`). The deployment tab puts the
  top-level `releasable` verdict in a banner above the trace, because it is the same function the
  CI gate blocks on.
- **Simulated-user picker.** `SimulationUserSelect` offers active users *and* service accounts: an
  agent's request is traced exactly like a person's.
- **Pasted ids.** Both pickers accept a pasted id, and the pure `typedIdOption.ts` turns a whole
  UUID typed into the search box into an option. The lists they read are scoped to the caller:
  `GET /admin/users` needs `USER_MANAGE`, so the user list is skipped without it, and
  `GET /datasources` returns only the caller's own datasources unless they hold `QUERY_ADMIN` or
  `DATASOURCE_MANAGE`. The seeded `AUDITOR` holds neither. When the datasource list is scoped, the
  field says so (`access.simulation.datasource_scoped_hint`), so an auditor on the reverse index
  is never stuck with an empty dropdown.
- **Enum values in details.** Enum-valued detail keys (`query_type`, `action`, `status`,
  `risk_level`) go through `enumLabels`. Masking entries (the MASKING step's `policies`, the API
  step's `masks`) render as *field → strategy*. Only the routing step omits its `policies` key from
  the generic rows.

Home routing is permission-driven since AF-522: `homePathForUser` (`utils/homePath.ts`) sends an auditor-shaped user (holds `COMPLIANCE_REPORT_VIEW`, lacks `QUERY_SUBMIT_SELECT`) to `/admin/auditor`; everyone else lands on `/dashboard` (AF-498), and `AuthGuard` bounces a permission-mismatch to that same home.

### DashboardPage *(any authenticated user)* — AF-498

The personalized home at `/dashboard` (lazy-loaded; nav entry in the unlabelled group at the very top
of the sidebar, above **Workflow**; the
default post-login landing for non-auditor roles). The header shows **clickable** headline stat tiles
(`StatTile` — whole surface navigates to the matching list page: pending approvals → `/reviews`, open
queries → `/queries`, anomalies → `/admin/anomalies`, API requests → `/api-requests`, API approvals →
`/reviews?tab=api`, open deployments → `/deployments`, deployment approvals → `/reviews?tab=deployments`;
the suggestions tile scrolls to and expands the suggestions widget since no dedicated
page exists). The open-queries tile renders the summary's `status_counts` as a mini per-status
breakdown, and the open-queries / open-API-requests tiles carry a **Bklit sparkline + `DeltaBadge`**
(second-half vs first-half of the trends window, sharing the trends query cache).

**Bklit charts (vendored).** The dashboard's charts are Bklit UI components vendored via the shadcn
registry into `src/components/charts/` (see the CLAUDE.md tech-stack rows for the Tailwind scoping and
the `@visx` alpha-pin exception): app code imports them only through the
`src/components/charts/index.ts` barrel; theming is a pure CSS-variable bridge
(`src/styles/bklit.css`) mapping the `--af` tokens onto Bklit's `--chart-*` variables, so light/dark
follows `[data-theme]` with no JS. Data shaping (sparse day buckets → dense Date-keyed rows, daily
totals, half-window deltas, weekly heatmap columns) lives in `src/utils/trendSeries.ts`; unit tests
stub the barrel via `components/dashboard/chartsTestMocks.tsx` because jsdom cannot lay out
visx/motion SVG. Below, widgets live on a **responsive 12-column grid** (`.af-dashboard-grid`,
`pages/dashboard/dashboard.css`): each widget is `half` (span 6) or `full` (span 12) — toggleable per
widget from the card header, defaults in `DEFAULT_WIDGET_SIZE` — collapsing to a single column below
1100 px. Drag-and-drop reorder uses `@dnd-kit` with `rectSortingStrategy`.

Widgets (and their matching stat tiles) are **role-gated** to what the current user can actually use,
mirroring the sidebar nav model (`WIDGET_PERMISSIONS`), and since #926 additionally **domain-gated**
through `WIDGET_DOMAIN` — `availableIds` keeps a widget only when the permission check passes *and*
its governance domain is enabled (a widget with no entry, i.e. the database domain, is always on): **Pending approvals** (`QUERY_REVIEW`),
**Attestations due** (`ATTESTATION_REVIEW`, from `/reviews/attestations/items`), **My recent queries**
/ **My access requests** / **My request groups** / trends (any query-submitting role), **AI
optimization suggestions** (`QUERY_SUBMIT_DML`), **Anomaly alerts** (`ANOMALY_MANAGE`), **My recent
API requests** / **API request trends** (AF-500), **Pending API approvals**
(`API_REQUEST_REVIEW`), and — for deployment approval governance (#926) — **Pending deployment
approvals** (`DEPLOYMENT_REVIEW`, deep-links to `reviewHubPath('deployments')`), **My recent
deployments** (any query-submitting role, rows link to `/deployments/:id`) and **Environment
versions** (`DEPLOYMENT_PIPELINE_MANAGE` / `DEPLOYMENT_REVIEW` / `QUERY_ADMIN`, matching the
`deployment-versions` nav item). The first two read the summary's `recent_pending_deployment_approvals`
/ `recent_deployments`; **Environment versions** is the only deployment widget with its own query —
it asks the #742 org-wide inventory for the **drifted** rows only (`drifted: true, size: 5`), so an
up-to-date fleet is its empty state and the full matrix is one **View all** click away on
`/deployment-versions`. Row lists share `components/dashboard/ActivityList` (aligned pill/primary/meta
columns, single-line truncation with tooltip, ≤5 rows, a **View all** footer link to the full page);
every widget has a `Skeleton` (or the Bklit loading chrome) while loading, a compact
`EmptyState size="sm"` when empty, and a `WidgetError` block (surfacing the server `detail`, with
retry) on failure — including the stat row when `/dashboard/summary` fails. The two trend widgets are
one `TrendsWidget` (`kind: 'queries'|'apiRequests'`): a Bklit **gradient `AreaChart`** (one `Area` per
status/risk series, colored by the status/risk tokens, with a custom legend row) plus a status/risk
metric toggle and a 7d/30d/90d range control feeding `?from&to` (range persisted as
`preferencesStore.dashboardTrendsRange`; window anchored at UTC day granularity —
`trendsFiltersForRange`). Two further Bklit widgets share that range/cache: **Risk mix**
(`RiskRingWidget`, one ring per risk level with a centered total) and **Activity heatmap**
(`ActivityHeatmapWidget`, GitHub-style weekly columns over a fixed 90-day horizon), both gated on
`QUERY_SUBMIT_SELECT`.

Widget hide/show, collapse, size, and order persist in `preferencesStore.dashboardWidgets`
(`{ hidden[], order[], collapsed{}, size{} }`, `af-preferences`, persist **version 1**): visibility is
a deny-list (`hidden`), so a widget shipped after prefs were persisted appears — and can be hidden —
without migration; `migratePreferences` converts the v0 `visible[]` allow-list. The **Customize**
dropdown stays open across toggles (whole row is the hit target; the checkbox is presentational) and
carries a **Reset layout** action. The header **Refresh** invalidates every dashboard-related query key
(dashboard, anomalies-mine, attestation worklist, access requests, request groups), and the page
subscribes to `anomaly.detected` / `query.status_changed` / `review.new_request` WebSocket events as
invalidation hints. A header `Switch` toggles the opt-in weekly email digest (server-persisted via
`GET`/`PUT /dashboard/digest-subscription`; its tooltip surfaces `last_sent_at`), and an **Export this
week** dropdown downloads the signed PDF/CSV weekly summary. "Open in editor" navigates to `/editor`
with the suggestion's SQL via router `location.state.presetSql` (the editor seeds its initial SQL from
it). A notifications widget is deliberately absent — the Topbar `NotificationBell` already owns that
feed and duplicating it on the dashboard would add a second interaction surface for no navigation win.

### AnomaliesPage *(AUDITOR or ADMIN)* — AF-383

The behavioural-anomaly-detection (UBA) dashboard at `/admin/anomalies` (lazy-loaded; nav entry in the **System** group, ADMIN). The header carries summary charts (via `@ant-design/charts` — anomalies over time and by feature) above a filterable `Table` of `behavior_anomaly` rows. Filters mirror the backend query params (status, user, datasource, feature, date range); data is fetched with TanStack Query (`api/anomalies.ts`, key `anomalyKeys.list(params)`) and renders a `Skeleton` while loading / an `EmptyState` when nothing matches. Each row shows the flagged user, datasource, feature, score, observed-vs-baseline values, and the optional `ai_summary`. Per-row **Acknowledge** / **Dismiss** actions (ADMIN only — hidden for AUDITOR) call `POST /admin/anomalies/{id}/{acknowledge,dismiss}` with optimistic cache invalidation. The page subscribes to the `anomaly.detected` WebSocket event and invalidates `anomalyKeys` so a freshly-flagged anomaly appears without a manual refresh.

### BreakGlassLogPage *(AUDITOR or ADMIN)* — AF-385

The break-glass / emergency-access log at `/admin/break-glass` (lazy-loaded; nav entry in Security → **Access control**, AUDITOR/ADMIN). A filterable `Table` of `break_glass_events` (default filter `PENDING_REVIEW` — unreconciled), fetched with TanStack Query (`api/breakGlass.ts`, key `breakGlassKeys.list(params)`); filters mirror the backend params (status, datasource, user, date range). Each row shows the executing user, datasource, justification, and a `BreakGlassStatusPill`; a row click opens a `Drawer` with the executed query link, full justification, SQL, and review fields. The per-row **Acknowledge** action (ADMIN only) opens a modal with an optional reconciliation comment and calls `POST /admin/break-glass/{id}/acknowledge`, invalidating `breakGlassKeys`. The **Emergency access** flow itself lives on the editor: `QueryEditorPage` queries `GET /me/break-glass` (`meKeys.breakGlass`) and renders a danger **"Emergency access"** button — only when the selected datasource is eligible — that opens a justification-forcing confirmation modal (`breakGlassSubmit`, `POST /queries/break-glass`) and navigates to the executed query on success. A new `BreakGlassStatusPill` (`components/common/`) and `breakGlassStatusColor` / `breakGlassStatusLabel` helpers back the status rendering.

### DetailCard (`components/common/DetailCard.tsx`) — AF-531

The shared **detail-section chrome**: a bordered `--bg-elev` card with a compact header (optional icon, bold 13px title, optional right-aligned `extra` slot) over an unpadded body. Extracted from `QueryDetailPage`'s inline `Card` helper and reused verbatim across `QueryDetailPage`, `ApiRequestDetailPage`, and the request-group `RequestGroupMemberPanel`, so the query/API/group detail views render literally identical section cards. Callers own the body padding (usually `14px`).

### AnomalyBadge (`components/common/AnomalyBadge.tsx`) — AF-383

A reusable badge that surfaces the **current user's own** open-anomaly count for a datasource (e.g. in the editor's datasource selector). It reads `GET /anomalies/badge?datasourceId=` via TanStack Query and renders an AntD `Badge` with the `openCount` and a tooltip carrying `maxScore`; it renders nothing when `openCount` is `0`. Status/colour go through the shared risk-colour helpers — never an inline hex.

### VersionBadge (`components/common/VersionBadge.tsx`, #836)

The version under the brand mark in the sidebar. Queries `GET /api/v1/system/update-status` through `api/updates.ts` (`updateKeys.status()`, `fetchUpdateStatus`) with a one-hour `staleTime` and `retry: false` — the backend already caches the answer for a day, it must never poll, and a failed check has to stay silent. Rendered for **every** signed-in user (no permission gate). When `update_available` is true it becomes a warn-toned `Pill` (`driftColor(true)` from `utils/statusColors.ts` — being behind a release is an operational fact, not a failure) wrapped in a link that opens `changelog_url` from the response in a new tab (`rel="noopener noreferrer"`), falling back to `CHANGELOG_URL` from `config/docs.ts` when the manifest carries none. `CHANGELOG_URL` is deliberately **not** routed through `docsUrl()` / `DOCS_ANCHOR_PAGES`, which is a `/docs/`-only contract; `config/__tests__/docs.test.ts` pins it to the changelog page's canonical URL. Otherwise it renders the plain `v{APP_VERSION}` text. The login page keeps the plain version (no request before sign-in).

### SetupProgressWidget (`components/common/SetupProgressWidget.tsx`)

A collapsible banner mounted in `AppLayout` directly above the route `<Outlet />`. It self-gates: it renders only for a user holding `SETUP_PROGRESS_VIEW` who still has at least one step neither configured server-side nor skipped client-side. Users without the permission, and tenants that have finished onboarding, never see it. Data comes from `GET /api/v1/admin/setup-progress` via TanStack Query (key `['setupProgress','current']`, `staleTime: 30s`).

The step list is **built from the response**, not fixed. Three rows are always present, in this order:

1. **Create a review plan** → `/admin/review-plans`
2. **Configure the AI provider** → `/admin/ai-configs`
3. **Add your first datasource** → `/datasources/new`

Review plan is first because every datasource references a plan; the AI provider comes before datasources so admins land on the datasource wizard with an AI config available to pick (AI is still skippable per datasource).

One further row is appended, last, per governance domain the organization opted into — so zero, one or two extra rows (AF-898):

- **Create your first API connector** → `/api-connectors` — only when `governs_apis` **and** the caller holds `API_CONNECTOR_MANAGE`.
- **Create your first deployment pipeline** → `/admin/deployment-pipelines` — only when `governs_deployments` **and** the caller holds `DEPLOYMENT_PIPELINE_MANAGE`.

Rows are numbered by rendered position, so with only `governs_deployments` on, the pipeline step is row 4.

The permission half of each condition is the widget's own: the payload is identical for every `SETUP_PROGRESS_VIEW` holder, and a step that links to a 403 is worse than no step. The domain half is the same visibility signal the sidebar, review-hub tabs and dashboard widgets read since #926 (see [§ Governance domains](#governance-domains--the-discovery-model-926)) — it decides what is *offered*, and never gates the `/api-connectors` or `/admin/deployment-pipelines` routes themselves, which stay registered and reachable.

Each pending step renders a primary "Set up" button plus a quieter "Skip" affordance — admins who don't want to configure that step (e.g. running without AI) can mark it skipped and see it stop nagging. Skipped steps render a "Skipped" tag with an "Undo skip" link so the decision is reversible. The progress bar counts skipped + configured equally; once every rendered step is accounted for, the widget hides entirely.

State lives in `preferencesStore`:

- `setupProgressCollapsed` — collapse/expand state of the checklist body.
- `setupProgressSkipped: SetupStepId[]` — the IDs the admin marked skipped (`review_plans`, `datasources`, `ai_provider`, `api_connectors`, `deployment_pipelines`). Persisted to `localStorage` via Zustand `persist`, so the choice survives reloads but is intentionally per-browser (not per-org) since skipping is a UX nudge, not a policy.

The relevant mutations (create datasource, create review plan, save AI config, create API connector, create deployment pipeline) invalidate `setupProgressKeys.current()` on success so the widget reacts immediately when an admin completes a step the real way.

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
block (`embedding_provider` select — Anthropic excluded as it has no embeddings API, Voyage AI
included as an embedding-only provider, `embedding_model`, optional `embedding_endpoint` /
`embedding_api_key`, and `embedding_dimensions`). Required rules fire only when the dependent field is
mounted, and the form mirrors the backend `RAG_CONFIG_INVALID` constraints. API keys round-trip
masked as `********`.

Three details of that block exist for AF-918. Choosing **Voyage AI** pre-fills its endpoint
(`https://api.voyageai.com/v1`), model (`voyage-4`) and default width, and the API-key help text says
a *Voyage* key — the footgun this feature exists to signpost is assuming an Anthropic key works;
switching away again clears any of those three values the section itself wrote, so a Voyage model name
is never saved against another provider. `embedding_dimensions` renders for every provider in
`DIMENSION_CAPABLE_PROVIDERS` (mirroring `AiProviderCapabilities.supportsConfigurableDimensions`) — a
`Select` of Voyage's four widths, a free `InputNumber` otherwise, and nothing for Ollama. It is
deliberately *not* Voyage-only: an unmounted `Form.Item` is absent from `onFinish`'s values, so a
Voyage-only field would make a blank value ambiguous and `AiConfigEditPage` would clear a width set
over the API on any unrelated save — see `pages/admin/ai-configs/embeddingDimensions.ts`, which owns
the `0`-means-clear decision. Finally, pairing Voyage with the in-app store shows a warning before
submission, since the `vector(N)` width is frozen at the deployment's first migration.

`KnowledgeDocumentsSection` (edit page only — the config must already be saved with RAG enabled)
lists the documents in an AntD `Table` (title / chars / chunks / status / created), with an **Add
document** modal (title + content), per-row delete, and a **Test RAG connection** button
(`testRag`). It uses TanStack Query (`aiConfigKeys.knowledge(id)`) with mutations that invalidate the
list. Ingestion embeds immediately, so the section is gated on the *persisted* `rag_enabled` flag.

### Langfuse configuration (`pages/admin/LangfuseConfigPage.tsx`)

`LangfuseConfigPage` (`/admin/langfuse`, lazy, admin-only — nav entry in System → **AI**) is the
single-org form for the [Langfuse](https://langfuse.com) integration. It mirrors `SamlConfigPage`:
TanStack Query loads the config (`getLangfuseConfig`, key `langfuseConfigKeys.current()`), a
`useMutation` saves it (`updateLangfuseConfig`), and the secret key round-trips masked as `********`
(unchanged values are stripped from the payload). Fields: **Enabled**, **Host URL** (validated as a
URL, ≤ 500), **Public key**, **Secret key** (`Input.Password`), **Send analysis traces**, and **Use
Langfuse-managed prompts**. A **Test connection** button calls `testLangfuseConfig` and surfaces the
server's status message as a toast.

### In-app help chat (`components/help/`, AF-906)

`HelpChatLauncher` is mounted by `AppLayout`, so it exists only for a signed-in user and never
appears on `/login` or `/setup`. It reads `GET /help-chat/availability` (TanStack Query, key
`helpChatKeys.availability()`, `staleTime` 5 min) and **renders nothing at all** — no button and no
drawer — unless `enabled` is true, which is how an organization that has not configured the agent
sees no trace of it. Opening it lazy-loads `HelpChatPanel`, an Ant Design `Drawer` with
`placement="right"` and `mask={false}` so the page underneath (including `SetupProgressWidget`)
stays usable while a question is in flight.

`useHelpChat` owns the server state: availability, the active conversation
(`helpChatKeys.session(id)`), and an ask `useMutation` that appends the user's question optimistically
and rolls it back if the call fails. The conversation *id* is UI state and lives in
`preferencesStore` (`activeHelpSessionId`, alongside `helpChatOpen`) — never the messages themselves.
A session is opened lazily on the first question, so browsing the panel leaves no empty transcript
behind, and errors surface the server's own localized `detail` through `showApiError`.

Two rules are load-bearing rather than cosmetic (epic AF-899 decisions 3 and 6):

- **An answer renders through a closed markdown subset, and links remain citations-only** (#919).
  `helpMarkdown.ts` is a dependency-free parser whose node union covers headings, bold, italic,
  inline code, fenced code blocks, ordered and unordered lists, blockquotes, paragraphs and line
  breaks — and nothing else. No node can carry a URL, so `HelpChatMarkdown` has no code path that
  emits an `<a>`, an `<img>`, an `href`, a `src` or a `dangerouslySetInnerHTML`. The guarantee is
  structural rather than an allow-list that has to be configured correctly — which is why the
  parser is hand-rolled rather than a library configured closed.
  - Raw HTML, autolinks and tables are unrecognised and stay literal text.
  - Two constructs are actively neutralised, because leaving them literal would print a
    model-authored URL: `[label](url)` keeps only its label, and `![alt](url)` renders nothing at
    all — so an image can never fire an on-render beacon that leaks the viewer's IP. A
    link-reference definition whose target is a URI (`[1]: https://…`) is dropped whole; a legend
    line like `[1]: Review plans` carries no URL and stays as text.
  - `[n]` markers survive as literal text beside their chips — a bare `[1]` or `[2, 3]` is not link
    syntax.
  - Nesting past a fixed depth cap, and an over-long link scan, degrade to literal text rather than
    recursing or rescanning. The answer's shape is model-authored and the app has no error boundary,
    so a throw during render would blank the whole SPA on every reopen of that conversation.
  - A question the *user* typed is still a plain text node with `white-space: pre-wrap`.

  The **only** anchors on the panel come from `HelpChatCitations`, built from the server-resolved
  `citations` array, each an `<a target="_blank" rel="noopener noreferrer">`. A URL the model wrote
  is text; a jailbroken model cannot make the help panel a phishing surface.
  `HelpChatPromptRenderer.TEMPLATE` states the same subset back to the model and forbids images,
  tables and raw HTML, so the model does not emit constructs the reader would see as raw syntax —
  or, for an image, would not see at all.
- **The panel sends a route *label*, never a pathname.** `routeLabel.ts` maps the current route to
  the sidebar's own `nav.*` key — `/queries/<uuid>` becomes "Query history" — and returns nothing for
  an unmapped route. The pathname is never interpolated into the result, so no request id,
  datasource id or search term reaches the AI provider. The server sanitizes the field again on
  arrival.

`HelpChatComposer`'s `maxLength` is 10 000, mirroring `AskHelpChatRequest.question`'s
`@Size(max = 10000)` — the ceiling on what is *stored*, not the organization's
`max_question_chars`, which only truncates what reaches the model.

### Help agent configuration (`pages/admin/HelpAgentConfigPage.tsx`, AF-906)

`/admin/help-agent` (lazy, `AI_MANAGE` — nav entry in System → **AI**) is the single-org settings
form for the help chat agent, in the shape of `LangfuseConfigPage`: `getHelpAgentConfig`
(key `helpAgentKeys.config()`) loads it, a `useMutation` saves it, and the row never 404s — an
organization that has never saved one is served the defaults with a `null` `id`. Unbinding sends
`clear_ai_config: true`, because a `null` `ai_config_id` means "unchanged" on a partial update.

Three switches carry no numeric constraint — **Enable the help assistant**, **AI configuration**
(a `Select` over `listAiConfigs`) and **Send screen and permission context** — and the rest are
bounded.

**Validation parity** — every `Form.Item` rule mirrors a constraint on
[`UpdateHelpAgentConfigRequest`](../backend/src/main/java/com/bablsoft/accessflow/ai/internal/web/UpdateHelpAgentConfigRequest.java)
per the CLAUDE.md parity rule:

| Field | Backend constraint | Frontend rule |
|---|---|---|
| `retrieval_enabled` | — (`Boolean`, partial update) | `Switch` |
| `top_k` | `@Min(1) @Max(20)` | `required` + `type: 'number', min: 1, max: 20` |
| `similarity_threshold` | `@DecimalMin("0.0") @DecimalMax("1.0")` | `required` + `type: 'number', min: 0, max: 1` |
| `max_history_turns` | `@Min(1) @Max(50)` | `required` + `type: 'number', min: 1, max: 50` |
| `max_question_chars` | `@Min(100) @Max(10000)` | `required` + `type: 'number', min: 100, max: 10000` |
| `retention_days` | `@Min(1) @Max(3650)` | `required` + `type: 'number', min: 1, max: 3650` |
| `per_user_requests_per_minute` | `@Min(1) @Max(120)` | `required` + `type: 'number', min: 1, max: 120` |

The corpus panel shows the read-only ingestion state the indexer writes — indexed revision, last
indexed, and whether the last pass failed — plus a **Re-index documentation** button
(`POST /admin/help-agent/reindex`, accepted asynchronously, nothing to poll). **Test retrieval** sits
in the form's action row next to **Save changes**, not in that panel, and surfaces the server's
`detail` verbatim as a toast.

When retrieval cannot be enabled the page says *why* rather than leaving the admin to discover it on
save. `helpAgentRetrieval.ts` derives the reason from the bound `ai_config` and
`GET /admin/ai-configs/rag/capabilities`: no configuration selected, RAG switched off, no embedding
provider, `ANTHROPIC` (which ships no embeddings API), or pgvector unavailable. Every one of those is
paired with the reassurance that the agent still works — with retrieval off it answers from the
bundled quick reference, it simply cannot cite a section.

Three states are reported as the backend's own localized `detail` instead — on **Test retrieval**, on
a rejected save, or in `index_error` — because nothing in the payloads this page reads separates
them: a missing `vector` extension, `ACCESSFLOW_RAG_PGVECTOR_ENABLED=false` having skipped the
`vector_store` migration, and an embedding dimension that does not match the pgvector column.

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
mark-all-read, delete, and delete-all invalidate both keys on success. The dropdown header
carries the two bulk actions: **Mark all read** (shown while the unread count is non-zero)
and **Delete all** (shown while the list is non-empty), the latter guarded by an Ant Design
`<Popconfirm>` because clearing the inbox is destructive and irreversible. Clicking a row navigates to
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
| `/api-connectors/:id/settings` | `ApiConnectorSettingsPage` | Create/edit form (protocol, base URL, auth method + credential fields, a `default_headers` key-value editor and a `trace_header_mapping` editor (#517), governance toggles incl. AI analysis / text-to-API switches and an **AI-config `Select`** — required when either AI feature is on, mirroring the datasource wizard — plus a **Review-plan `Select`** (AF-579; options from `GET /review-plans`, `allowClear`, same UX as the datasource wizard — clearing it sends `clear_review_plan: true` on save so the assignment is unset, and the hint notes that a connector without a plan defaults to a single approval), schema ingestion with three modes — paste / file upload / `sourceUrl` (#517) — plus a collapsible **import-filter** section (AF-614: exclude/include path globs, exclude verbs, exclude operation-id globs, exclude tags, and an "exclude deprecated" checkbox, all mirroring the backend's 100-entry / 200-character caps) with a **Preview** button (`POST .../schemas/preview`) that reports "Keeps K of N operations" and lists what would be dropped before committing, an "N of M operations imported" result line after upload, and a per-row **Edit filter** modal (`PUT .../schemas/{schemaId}/filter`) that re-edits the filter without re-uploading the document — + parsed-operation explorer, a **Variables** tab (AF-613: `ApiConnectorVariablesTab` — per-connector dynamic variables for request signing / nonces / timestamps, with a kind-driven form that shows only the fields the kind uses, an MD5 warning, up/down reorder because evaluation order is observable, a write-only secret field that is never round-tripped, and an `overridable` switch disabled for secret-bearing kinds to mirror the server rule), and the per-user "Share with team" permission grants (`can_read`/`can_write`/`can_break_glass`/`can_override_variables`/`expires_at`/`allowed_operations`/`restricted_response_fields`), and a **Simulate** tab (#1066: `ApiConnectorSimulateTab` — the API-call decision trace through the shared `DecisionTraceView`; see "Access simulation and decision traces"). AntD `Form`; validation parity with the backend DTOs. The "New connector" modal on `/api-connectors` carries the same AI-analysis/text-to-API/AI-config and review-plan fields plus a `default_headers` editor. |
| `/api-editor` | `ApiEditorPage` | Connector picker → the shared **`ApiAuthoringPanel`** (`components/apigov/ApiAuthoringPanel.tsx` + `useApiAuthoring.ts`, #559): searchable+sorted operation `Select` from the parsed catalog auto-filling verb/path (or free-form verb + path) → a Postman-style request composer (`ApiRequestComposer`: Params / Headers / Body tabs — plus a **Variables** tab (AF-613) shown only when the connector declares dynamic variables, listing the available `{{name}}` placeholders and offering per-request overrides for the ones marked overridable; body modes none/raw/x-www-form-urlencoded/form-data/binary with file uploads read to bounded base64; the connector's default headers shown read-only) → AI risk preview (`POST /api-requests/analyze`, `RiskBadge`) and the "describe the call in plain English" text-to-API box (`POST /api-requests/generate`, shown only when the connector has a schema and `text_to_api_enabled`). The page keeps the connector select, optional scheduled-run `DatePicker` (#517), justification, and submit; the group builder's `GroupMemberEditDrawer` mounts the same panel for API steps — without the Variables tab, since the group-item wire shape carries no overrides (AF-613). |
| `/api-requests` | `ApiRequestsListPage` | API requests aligned with the Query History page: a filter bar (search, status, connector, verb, risk, submitter, trace id, span id, date range; #517), a **Submitter** column, `StatusPill`/`RiskPill`, server-side pagination + filtering (`status`/`connector_id`/`verb`/`trace_id`/`span_id`/`from`/`to`; risk + free-text incl. submitter email filtered client-side), and row click → detail. |
| `/api-requests/:id` | `ApiRequestDetailPage` | Responsive card layout: status (`StatusPill`) + AI risk (`RiskPill`), connection/response metadata in a reflowing `Descriptions` (incl. submitter, trace id, span id; #517), justification / **variable-overrides** (AF-613; the signing inputs the submitter supplied, never the resolved outputs) / AI summary / error cards, the size-capped field-masked response snapshot with a **Download full response** button (`GET /api-requests/{id}/response`, #517), and the review-decisions table. A reviewer/admin viewing a `PENDING_REVIEW` request they didn't submit gets inline **Approve / Reject** actions via a shared comment modal (#567, self-approval blocked server-side), mirroring the API tab of the review hub (`ApiReviewsTab`) so the request is actionable from where the reviewer lands. |
| `/api-reviews` | *redirect* → `/reviews?tab=api` | Since #772 the API review queue is the **API requests** tab of the unified review hub (`ApiReviewsTab`, `pages/apigov/`): the same filter bar (search, connector, verb, risk) + pagination, the **Overrides** badge column (AF-613) flagging requests that override connector variables, and approve/reject via a comment modal (self-approval blocked server-side). The legacy URL redirects into the tab. |

Navigation entries are added to `components/common/Sidebar.tsx` (Connections → **API**: connectors;
Workflow → **API**: API editor / requests), role-gated like the rest of the nav; API reviews live in
the unified `/reviews` hub since #772.

## Request chaining & grouping pages (AF-501)

The grouped-request UI lives under `src/pages/requestGroups/` with the API module
`src/api/requestGroups.ts` (TanStack Query key factories mirroring `queries.ts`/`apiRequests.ts`),
types (`RequestGroup`, `RequestGroupItem`, the three enums) in `src/types/api.ts`, and enum labels
`requestGroupStatusLabel` / `requestGroupItemStatusLabel` / `targetKindLabel` in
`src/utils/enumLabels.ts`. All visible strings are `t()`-keyed under `requestGroups.*` in every
registered locale.

| Route | Page | Notes |
|-------|------|-------|
| `/request-groups/new` | `GroupBuilderPage` | Build a bundle: add steps rendered as **compact summary cards** (`GroupMemberCard` — sequence, kind, target, one-line preview, risk, incomplete warning) whose **Edit** action opens `GroupMemberEditDrawer` — the full-parity authoring surface (#559). QUERY steps mount the shared `QueryAuthoringPanel` (the `/editor` surface: `SchemaTree` + schema autocomplete, syntax toggle, format, AI analyze + `AiHintPanel`, dry-run + `DryRunPanel`, text-to-SQL, query templates); API steps mount the shared `ApiAuthoringPanel` (the `/api-editor` surface: operation picker auto-filling verb/path, the full `ApiRequestComposer` — params / headers / body types / form-data / file — AI analyze risk preview, text-to-API). Drag-reorder the steps with `@dnd-kit` (already used for dashboard widgets), an **aggregate risk badge** (max of members), a `continueOnError` toggle, an optional scheduled-run `DatePicker`, then submit the whole bundle. Only **active** datasources / permitted connectors are selectable; validation parity with the backend DTOs. |
| `/request-groups/:id/edit` | `GroupBuilderPage` | Re-open an **own `DRAFT`** group in the builder (#559): hydrates name/description/steps — including each API step's full saved composition (headers, params, body, form fields) from the detail response — and saves via `PUT`. Non-DRAFT or foreign groups redirect back to the detail page with a warning. Reached via the detail page's **Edit** button (own DRAFT only). |
| `/request-groups` | `RequestGroupListPage` | The caller's grouped-request history (admins see all) — filter bar (status), `StatusPill`, server-side pagination, row click → detail. Skeleton + EmptyState. |
| `/request-groups/:id` | `RequestGroupDetailPage` | Status (`StatusPill`) + aggregate `RiskPill`, and the **ordered step-by-step progress** (running / done / failed / skipped) updating live via the `request_group.status_changed` / `request_group.item_executed` WebSocket events. Each step is an **expandable panel** (`RequestGroupMemberPanel`, AF-531): collapsed = one-line header (sequence, kind, status/risk pills, duration, inline SQL/path preview); expanded = `DetailCard` sections mirroring the individual detail views — the request (`SqlBlock` for queries, verb + path for API calls), the **full embedded AI analysis** (summary + `IssueCard`/`OptimizationCard` lists, provider/model, failed state), and the error block. Submitter actions: cancel (pending / scheduled-approved), execute (approved). |

The **review queue** shows a group as **one expandable element** — the members and their per-member
risk inside; the reviewer acts once per stage on the bundle (optimistic approve/reject like the existing
queue). `useWebSocket` maps `request_group.status_changed` / `request_group.item_executed` to
`queryClient.invalidateQueries`. Navigation entries are added to `components/common/Sidebar.tsx`
(Workflow → **Request groups**), role-gated like the rest of the nav.

## Schema change governance pages (#883, epic #870)

The UI for [20-schema-change-governance.md](20-schema-change-governance.md) lives under
`src/pages/schemaChange/` with its shared pieces in `src/components/schemaChange/`
(`PromotionLadder`, `LadderStrip`, `StatementListEditor`). One API module, `src/api/schemaChange.ts`,
owns the `schemaChangeKeys` factory; the ladder and promotions keys nest under a set's detail key,
so invalidating `detail(id)` refreshes all three after a promote or cancel. Pure helpers — the
freeze predicate, the 422 → per-statement problem mapping, drift reason-code parsing and grouping,
and the blocked-rung sentence — are in `src/utils/schemaChange.ts`; enum labels and colour triples
in `enumLabels.ts` / `statusColors.ts`. Strings are keyed under `schemaChange.*` (enum values under
`enums.schema_*`) in every locale.

| Route | Page | Notes |
|-------|------|-------|
| `/schema-change-sets` | `SchemaChangeSetListPage` | Filter by pipeline and status; each row shows the statement count and a `LadderStrip` (one chip per environment, coloured by rung state, reason on hover — one ladder read per row, cached under the set's key). *New change set* modal: pipeline + name (3–255) + description (≤ 2000), mirroring `CreateSchemaChangeSetRequest`. Skeleton, error, empty and filtered-empty states. |
| `/schema-change-sets/:id` | `SchemaChangeSetDetailPage` | `StatementListEditor` — an AntD `Form.List` of SQL editors with `@dnd-kit` drag-to-reorder; rules mirror `SchemaChangeSetStatementRequest` (required, ≤ 100 000 chars) plus the statement cap (the default of `ACCESSFLOW_SCHEMACHANGE_MAX_STATEMENTS`, 50 — the server enforces the configured value). A refused save maps `statementIndex` / blocking `findings` onto the statements they name; the `WARN` findings of a successful save stay on their statements until the list changes. A frozen (any non-`FAILED`/`CANCELLED` promotion) or archived set renders read-only with an `Alert` naming the reason — and so does a set whose promotions failed to load, since the freeze cannot be known. The ladder polls every 10 s while a rung is `IN_PROGRESS`, and a refused promote/cancel/save re-reads the set. `PromotionLadder` renders `GET /schema-change-sets/{id}/ladder`: the `PROMOTABLE` rung gets a confirmed *Promote*, an open `PENDING`/`IN_REVIEW` promotion a *Cancel*, an `APPROVED` one a note that it can no longer be cancelled, and every `BLOCKED` rung its reason. Edit details / archive / delete (delete hidden once frozen). |
| `/schema-drift` | `SchemaDriftPage` | Filters pipeline / environment / status (default `OPEN`). One card per bound environment: the newest scan's summary, *Scan now* (the scan list polls every 5 s while a scan is unfinished), the findings table (object path, kind, expected vs actual, status, first/last seen, *Acknowledge*). A `applicable = false` scan renders "Not supported for this engine" with its localized reason — never the "no drift" text — and a scan that recorded a baseline reason renders "Nothing was compared". Findings of an environment no longer on any ladder stay visible. One page (100) of findings and of scans is read; a larger result says so. When polling sees a scan finish, the findings are re-read. |

Pipelines and environment names come from `GET /schema-change-pipelines`, not
`/deployment-pipelines` — the latter needs `DEPLOYMENT_PIPELINE_MANAGE`. Navigation: Workflow →
**Schema changes** → `Change sets` and `Schema drift`, both on `SCHEMA_CHANGE_MANAGE`; the routes
carry the same `AuthGuard`. A `SCHEMA_DRIFT_DETECTED` notification opens `/schema-drift`, and
`websocketManager` maps `schema_change_promotion.status_changed` (pushed to the promoter) onto the
set's detail key and the list.

## Deployment governance pages (#696, epic AF-682)

The deploygov UI lives under `src/pages/deployments/` (user surface) and
`src/pages/admin/deployments/` (admin surface), with heavy settings tabs extracted to
`src/components/deployments/`. API modules: `src/api/deploymentPipelines.ts`,
`deploymentRequests.ts`, `deploymentReviews.ts`, `deploymentFreezeWindows.ts`,
`deploymentRoutingPolicies.ts`, `deploymentVersions.ts` (TanStack Query key factories mirroring
`apiConnectors.ts`; `deploymentVersionKeys` is its own root rather than a leaf on
`deploymentPipelineKeys`, because the org-wide matrix is a top-level org resource with no pipeline
id). Types in `src/types/api.ts` (`DeploymentPipeline`, `DeploymentRequest`,
`DeploymentFreezeWindow`, `DeploymentRoutingPolicy`, `DeploymentEnvironmentVersion`,
`DeploymentVersionHistoryEntry`, …; request status reuses `QueryStatus`, risk reuses `RiskLevel`,
so `StatusPill`/`RiskPill` are reused unchanged). Enum labels `pipelineProviderLabel` /
`freezeBehaviorLabel` / `deploymentOutcomeLabel` / `deploymentRollbackReviewStatusLabel` /
`isoWeekdayLabel` in `src/utils/enumLabels.ts`; outcome/rollback colors and `driftColor` in
`src/utils/statusColors.ts`. All visible strings are `t()`-keyed under `deploygov.*` in every
registered locale.

| Route | Page | Notes |
|-------|------|-------|
| `/deployments` | `DeploymentListPage` | Deployment requests (non-reviewers are server-scoped to their own submissions). Filter bar: status, pipeline, environment name, exact version; server pagination, forced `created_at DESC` (no sortable columns). The pipeline filter is built from the loaded rows unless the caller holds `DEPLOYMENT_PIPELINE_MANAGE` — a `DEPLOYMENT_REVIEW`-only user cannot call `GET /deployment-pipelines`. Row click → detail. A **Drift** column (#743) reads the per-pipeline matrix through one `useQueries` fan-out over the distinct `pipeline_id`s on the page (keys shared with the detail page, so revisits are cache hits; a 404 leaves those rows unbadged) and badges **only** the request that is currently live on its environment (`current_request_id === row.id`) — drift describes the environment now, so badging a superseded historical request would mislead. |
| `/deployments/:id` | `DeploymentDetailPage` | Metadata `Descriptions` (version, commit, artifact, CI-run link, external run id, submitter, reason, schedule), AI risk card (`RiskPill` + summary), approvals progress + decisions table, request `metadata` JSON block, and an `ApprovalTimeline` fed by the pure `buildDeploymentTimelineStages.ts` (submitted → AI → review → scheduled → release → outcome, incl. the `EXECUTED → FAILED` outcome flip). While `APPROVED`, the page calls `GET /deployment-gate?request_id=` for the **releasability banner** (releasable / held-by-freeze with reason / scheduled); a gate 404 hides the banner silently. The submitter can cancel via `Popconfirm` while cancellable. **Approve / Reject (#770)** sit in the same action row, driven solely by the response's `can_review` flag — the server's answer to the decision guard, because the review plan's approver rules are not in this payload and a `DEPLOYMENT_REVIEW`-only gate would offer a button the backend then refuses. They carry their own copy of the queue's comment modal (≤2000 chars — a third inline copy alongside `DeploymentReviewTabs`' two, matching how `ApiRequestDetailPage` duplicates its own queue's; there is no shared component) and invalidate the detail, list and review-queue keys on success. Where `can_review` is false no **decision** control renders at all — not a disabled one, unlike the queue — and the submitter of a `PENDING_REVIEW` request gets `deploygov.reviews.selfSubmissionHint` beside their Cancel button. A **Drift** row (#743) joins the pipeline matrix by environment: the drift chip when this request is the environment's live deploy, otherwise a muted "Superseded — {environment} now runs {version}"; a matrix 404 hides the row entirely, same treatment as the gate. |
| `/deployment-reviews` | *redirect* → `/reviews?tab=deployments` (or `?tab=rollbacks`) | Since #772 the two `DEPLOYMENT_REVIEW`-gated queues are tabs of the unified review hub (`pages/deployments/DeploymentReviewTabs.tsx`). **Deployments** (`PendingDeploymentsTab`): queue rows (pipeline/environment/version/risk/approvals/schedule) with approve/reject via a shared comment modal (≤2000 chars); own submissions render disabled buttons with a tooltip (self-approval is also blocked server-side with 409). **Rollbacks** (`RollbackReviewsTab`, `?tab=rollbacks`): the mandatory post-rollback acknowledgements — status filter, outcome detail, acknowledge with optional comment (idempotent; self-ack 409), link to the deployment. The legacy URL (with or without `?tab=rollbacks`) redirects into the matching tab. |
| `/deployment-versions` | `DeploymentVersionsPage` | Org-wide version matrix (#743) — one row per environment deployed at least once: pipeline (linking to the per-pipeline matrix), environment + tag chips, current/previous version, deployed-at, last outcome + rollback badge, drift badge. Filters: pipeline, tag, environment name, and a drift tri-state (`Any` / `Behind latest only` / `Up to date only` → `drifted` `undefined`/`true`/`false` — the "up to date" case is why the api module guards on `typeof … === 'boolean'` rather than truthiness). Guarded any-of `DEPLOYMENT_PIPELINE_MANAGE` / `DEPLOYMENT_REVIEW` / `QUERY_ADMIN`, mirroring the controller's `hasAnyAuthority`. Tag options come from the loaded rows, so the list collapses to the selected tag until cleared (the selection is pinned so it cannot vanish from its own `Select`). Empty with no filters ⇒ `EmptyState`; empty with filters ⇒ the table's `emptyFiltered` text. |
| `/deployment-versions/:pipelineId` | `PipelineVersionsPage` | The per-pipeline matrix as a standalone route (#743), **deliberately unguarded** — the server answers 404-never-403, which is what gives a `can_trigger`-only user (who cannot reach the admin settings page, and cannot call `GET /deployment-pipelines` to pick from) a reachable surface. Entry points: the pipeline column on `/deployment-versions`, and a "Version matrix" button beside the pipeline filter on `/deployments`. Wraps the same `PipelineVersionsTab`; a rejected matrix read renders the not-found `EmptyState`. |
| `/admin/deployment-pipelines` | `DeploymentPipelinesPage` | `DEPLOYMENT_PIPELINE_MANAGE`-gated CRUD list (name, copyable id, provider label, repository, AI toggle, active tag) + create modal (name 3–255, provider, repository URL ≤2048, project ref ≤512, review plan, AI toggle + AI config — validation parity with `CreateDeploymentPipelineRequest`). Create navigates straight into settings. |
| `/admin/deployment-pipelines/:id` | `DeploymentPipelineSettingsPage` | The header subtitle carries the provider label plus the pipeline's full id as a `PipelineIdCopy` (#771) — the UUID every CI integration is configured with, previously readable only out of the address bar or the CI snippet. Tabs: **General** (edit form, `clear_review_plan`/`clear_ai_config` on unset, active switch); **Environments** (`PipelineEnvironmentsTab` — ordered list, per-env `tags` (a `Select mode="tags"` with parity validation against `@Size(max=10)` / element `@Size(max=32)`; both mutations always send an array, never null, because the server reads null as "leave unchanged"), `require_review`/`required_approvals`/review-plan overrides/`allow_break_glass`; since #877 the existing **Order** field gains a `{ type: 'number', min: 0 }` rule (parity with `@Min(0)`), is `required` in edit mode (the update path reads null as "leave unchanged", so a cleared value must not silently keep the old position) and takes its create prefill from the pure `environmentLadder.ts` (`max + 1`, `0` when empty — never the row count, which the unique `(pipeline, sort_order)` constraint would refuse), and a new **Database** `Select` over `listDatasources` is sent as `datasource_id` + `clear_datasource` (the `clear_review_plan` shape) and rendered as a table column that falls back to the raw id for a datasource deleted since binding); **Versions** (`PipelineVersionsTab` — the unpaginated per-pipeline matrix in server order, including never-deployed environments as `—` + a "Never deployed" chip, with a per-environment `EnvironmentHistoryDrawer`); **Permissions** (`PipelinePermissionsTab` — user + group grant tables mirroring the connector-permission UI, capabilities `can_trigger`/`can_break_glass`/`expires_at`); **Freeze windows** (`PipelineFreezeWindowsTab` — the global list client-filtered to this pipeline + org-global rows ("Global" badge); one-off vs weekly-recurring editor with ISO-weekday multi-select, `HH:mm` time pickers, an IANA timezone select from `Intl.supportedValuesOf('timeZone')`, and a CSS-grid week-strip visualisation from the pure `freezeWindowCalendar.ts`); **Routing policies** (`PipelineRoutingPoliciesTab` — flat typed conditions (environments/providers/min-risk/version globs/day+time window), `required_approvals` required for `REQUIRE_APPROVALS`/`ESCALATE` and hidden for `AUTO_*`, priority conflicts surfaced from the 409); **Simulate** (`PipelineSimulateTab`, #1066 — the deployment decision trace at an optional hypothetical instant `at`, with the gate's `releasable` verdict in a banner above the shared `DecisionTraceView`); **CI setup** (`CiSnippetPanel` — copyable GitHub Actions / GitLab CI / Azure Pipelines / curl snippets from `ci-templates/`, pipeline id and origin pre-filled). The eight-tab strip is synced to `?tab=` (unknown values fall back to `general`), which makes a tab linkable and the e2e deterministic on an overflowed strip. Pure form logic lives in `freezeWindowForm.ts` / `deploymentRoutingPolicyForm.ts` / `versionMatrix.ts` / `environmentLadder.ts` (coverage-listed). |

Live refresh: the backend pushes `deployment.status_changed` to the **submitter** on every
transition; `websocketManager.invokeDefault` invalidates the deployment detail (incl. its gate
child key), list, and review queue. Reviewer queues refresh off `notification.created` — a
`DEPLOYMENT_SUBMITTED` notification additionally invalidates the review queue and a
`DEPLOYMENT_OUTCOME_FAILED` one the rollback-review list. `NotificationBell` routes deployment
notifications into the pages (submission → the hub's Deployments tab, outcome-failed → its
Rollbacks tab, the rest → the deployment detail). Schema-change promotions (#882) are reviewed and
run as request groups, so their notifications route into those pages instead — a
`SCHEMA_CHANGE_PROMOTION_SUBMITTED` notification to `/request-groups/reviews` (and it invalidates
`['request-groups', 'reviews']`), `_APPLIED` / `_FAILED` to the promoter's `/request-groups`, and
`SCHEMA_DRIFT_DETECTED` to `/schema-drift` (#883). The version caches are **not** WebSocket-invalidated — there is no
`deployment.version_changed` event — so they refresh on mount and whenever an environment mutation
drops `deploymentVersionKeys.matrix(pipelineId)` and `.lists()` (name, tags and sort order all
appear in matrix rows). Navigation: Workflow → **Deployments** `Deployments` (on `QUERY_SUBMIT_SELECT`, like
API requests) and `Version Matrix` (any of
`DEPLOYMENT_PIPELINE_MANAGE` / `DEPLOYMENT_REVIEW` / `QUERY_ADMIN`); deployment reviews are reached
through the unified `Review queue` entry (#772); Connections →
**Deployments** `Deployment Pipelines` (`DEPLOYMENT_PIPELINE_MANAGE`). A `can_trigger`-only user gets no nav entry
and reaches `/deployment-versions/:pipelineId` from `/deployments` instead.

The drift and rollback badge text is composed by the pure
`src/components/deployments/versionMatrix.ts` — `driftBadge` classifies the six shapes the
server's nullable quantities produce (`up_to_date`, `never_deployed`, `behind`, `days`,
`versions`, `versions_and_days`), and `driftBadgeText` renders the two-count case through a
`driftBoth` frame filled with two separately-pluralised fragments, because i18next carries one
`count` per key and a single combined string could not pluralise both halves in ru/hy. Shared
cells (tag chips, version, deployed-at, outcome + rollback badge, drift chip) live in
`versionMatrixCells.tsx` so the org-wide page, the tab, and the two deployment pages render them
identically.

## SQL Editor Component

Built on **CodeMirror 6** (`@codemirror/lang-sql`).

### Features

| Feature | Implementation |
|---------|---------------|
| SQL syntax highlighting | `@codemirror/lang-sql` with dialect set from selected datasource |
| Table/column autocomplete | Schema fetched from `/datasources/{id}/schema`, passed as `schema` option to `sql()` language extension |
| Keyword autocomplete | Built into `@codemirror/lang-sql` |
| On-demand AI analysis | User-triggered via the **Analyze** button → `POST /queries/analyze`; issues shown as CodeMirror lint diagnostics (below) and in the AI Hint Panel. Editing the SQL marks the analysis stale and drops its diagnostics. |
| Lint diagnostics (#865) | `@codemirror/lint`: `linter(null)` installs the lint state without a source and `lintGutter()` the marker gutter; `SqlEditor` pushes `setDiagnostics(...)` by transaction — never by rebuilding the view — whenever its `findings` (live SQL review) or `issues` (fresh AI analysis) props change, and re-applies them after a schema/syntax rebuild. The pure mapper `components/editor/sqlReviewDiagnostics.ts` marks the whole line of each finding (WARN → warning, BLOCK → error; AI CRITICAL/HIGH → error, MEDIUM/LOW → warning), clamps a stale line number into the document, skips findings the parser gave no position, and labels the hover tooltip's source through `t()`. The base theme's hard-coded colours are overridden in `codemirrorTheme.ts` with the `--risk-*` tokens. No `lintKeymap` (its panel text is untranslated). |
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

The editor's 340px right rail carries a `Segmented` **"AI analysis | Dry run"** toggle; the second tab renders `components/editor/DryRunPanel.tsx`. A **Dry run** action in the editor header (always available — not gated on AI being enabled) calls `POST /queries/dry-run` via `dryRunQuery` (`src/api/queries.ts`) and switches the rail to the Plan tab. The panel shows the estimated impact (operation + estimated rows, plus — when a warehouse engine reports one — an estimated-bytes-scanned line formatted by `formatBytes`, AF-634) and renders the execution plan through `components/editor/PlanTree.tsx` — an indented, read-only tree (`role="tree"`) of each node's operation, target, estimated rows, cost, and filter detail, flattened by the pure `src/utils/queryPlan.ts` (`flattenPlan` / `formatEstimatedRows` / `formatCost` / `formatBytes`). Engines without a plan concept render a graceful "not supported" message; when an engine returns no structured tree (e.g. Elasticsearch validation) the panel falls back to the raw plan text. A "stale" badge marks a plan whose SQL has since diverged, mirroring `AiHintPanel`.

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

### API error toasts (`showApiError` + `apiErrors.ts`)

Every failing-request toast must surface the backend's RFC 9457 `ProblemDetail.detail` rather than a
static generic string. Route errors through `showApiError(message, err, builder)` (`src/utils/showApiError.tsx`,
which also renders the trace-id footer). The `builder` is either a domain handler from
`src/utils/apiErrors.ts` (each maps its specific `error` codes to localized i18n messages, then falls
back through `detail` → `title` → axios message → a per-domain generic key), or — for call sites with
no dedicated handler — the shared `apiErrorMessage(err, () => t('...generic'))` extractor, which prefers
`detail` (backend-localized, occurrence-specific) over `title` (the HTTP reason phrase) and only shows
the caller's generic fallback when the envelope carries neither. Anti-patterns to avoid:
`onError: () => message.error(t('...'))` (discards `err`, so `detail` is lost) and passing a static
`() => t('...')` builder to `showApiError` (same — use `(e) => apiErrorMessage(e, () => t('...'))`).

### WebSocket Hook

The connection itself is owned by `<RealtimeBridge />`, mounted inside `AppLayout` so it only runs under `AuthGuard` — the WebSocket module is never imported by the `/login` or `/setup` routes, and no connection is attempted before authentication. It reads `accessToken` from `authStore`, opens `${VITE_WS_URL}?token=<JWT>` on auth, reconnects with exponential backoff, and disconnects on logout (when `AppLayout` unmounts). The bridge also wires **default `queryClient.invalidateQueries`** for the standard event/key mapping (see table below) — most callers don't need to subscribe at all; they just observe their existing TanStack queries refetching.

Detail and list views (`QueryDetailPage`, the review-hub tab bodies, etc.) **do not poll** — they rely on these WS-driven invalidations, with the manager's exponential backoff covering transient disconnects. A reload restores state if the WS is permanently unreachable. The one exception is the `usePendingReviewCounts` badge/tab-count hook (#772), which keeps a 30 s `refetchInterval` as a backstop for dropped frames.

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
| `query.estimate_complete` | `['queries','detail',query_id]`                                          |
| `query.prediction_complete` | `['queries','detail',query_id]` and `['reviews','pending']` (AF-645)   |
| `review.new_request`   | `['reviews','pending']`                                                     |
| `review.decision_made` | `['reviews','pending']` and `['queries','detail',query_id]`                 |
| `notification.created` | `['notifications','list']` and `['notifications','unread-count']`           |
| `anomaly.detected`     | `['anomalies','list']` and `['anomalies','badge',datasource_id]` (UBA — AF-383) |

#### Reconnection

`websocketManager` (in `src/realtime/`) reconnects with exponential backoff `1s → 2s → 4s → 8s → 16s → 30s` (capped). The counter resets on a successful `onopen`. After 3 consecutive failures the log level drops to `debug` so a sustained backend outage does not spam the console. `disconnect()` clears any pending reconnect timer; re-mounting (`accessToken` becomes non-null) reopens immediately.

The Axios refresh interceptor calls `useAuthStore.setState(...)` whenever the access token rotates — the bridge's `useEffect` reacts to that change and reconnects with the new token. No extra plumbing required.

---

## Contextual docs links

Every admin / configuration page deep-links to the section of the public docs site
(<https://accessflow.io/docs/>) that explains it, so an admin never has to leave the app
and hunt for the right section. The page declares only *which* section it maps to; the URL and the
rendering live in one place.

`src/config/docs.ts` owns both halves:

- **`DOCS_BASE_URL`** — the docs site root. A hardcoded constant, deliberately *not* runtime-
  overridable like `apiBaseUrl` / `wsUrl`: the docs deploy with the marketing site, not per-install.
- **`DOCS_ANCHOR_PAGES` / `DOCS_ANCHORS` / `DocsAnchor`** — the `as const` map from every linkable
  anchor to the chapter page that owns it, the anchor list derived from it, and the literal union.
  Passing an anchor that isn't in the map is a compile error.
- **`docsUrl(anchor)`** — builds the absolute `…/docs/<chapter>/#anchor` URL.

A page opts in by passing the anchor to its existing `PageHeader`:

```tsx
<PageHeader
  docsAnchor="cfg-review-plans"
  title={t('admin.review_plans.title')}
  subtitle={t('admin.review_plans.subtitle')}
/>
```

The deployment-governance admin surface uses two anchors: `cfg-deployment-pipelines`
(`/docs/configuration/review-workflows/#cfg-deployment-pipelines`) on `DeploymentPipelinesPage` —
the conceptual reference — and `guide-deployment-approval`
(`/docs/guides/deployment-approval/#guide-deployment-approval`) on
`DeploymentPipelineSettingsPage`, the step-by-step setup runbook, which is what someone standing on
a half-configured pipeline actually needs (AF-773). The wider CI reference at
`/docs/iac/#iac-deployment-gate` is linked from both of those pages rather than from the app, so it
needs no anchor entry of its own.

`PageHeader` renders it as a "View docs" link (`target="_blank"` + `rel="noopener noreferrer"`,
label and `aria-label` via `t()`) in the header row, to the left of `actions` — so it stays clear of
the page's primary call to action. The prop is optional; end-user flow pages don't pass one.

**Adding a page:** add its anchor to `DOCS_ANCHOR_PAGES` naming the owning chapter, ship a matching
`id` in that chapter under `website/docs/`, *and* add the anchor to `LEGACY_DOCS_ANCHORS` in
`website/app.js` (the permanent forwarder for the pre-split `/docs/#anchor` form that already-released
self-hosted frontends still emit). `src/config/__tests__/docs.test.ts` asserts all three halves agree,
so a typo on any side fails CI.

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
/setup                              → SetupPage (3-step wizard: org+admin, governance domains, then optional system SMTP)
/login                              → LoginPage (also renders the TOTP verification stage)
/invite/:token                      → AcceptInvitePage (public; previews + accepts a user invitation)
/forgot-password                    → ForgotPasswordPage (public; request a password-reset email)
/reset-password/:token              → ResetPasswordPage (public; previews + consumes a password-reset token)
/auth/saml/callback                 → SamlCallbackPage

/dashboard                          → DashboardPage (lazy; default post-login home for non-auditor roles, AF-498)
/editor                             → QueryEditorPage
/queries                            → QueryListPage  (header **Export CSV** button hits `GET /queries/export.csv` with the active server-side filters — `status`, `datasource_id`, `submitted_by`, `from`, `to`, `query_type`. Client-only filters on the page, namely the free-text search and risk-level select, are not sent because the backend has no equivalent filter; this matches the behaviour of the list endpoint itself. The mutation downloads via a temporary `<a>` element and shows a warning toast when the response carries `X-AccessFlow-Export-Truncated: true`.)
/queries/:id                        → QueryDetailPage
/reviews                            → ReviewHubPage (unified review queue — one permission-gated tab per request kind synced to `?tab=queries|api|deployments|rollbacks`; the Queries tab's header carries the **Enable push approvals** toggle — AF-444; #772)
/api-reviews                        → redirect → /reviews?tab=api (#772)
/reviews/:id/decide                 → PushDecidePage (lazy; REVIEWER/ADMIN — one-tap push decide landing with step-up auth, AF-444)
/reviews/attestations               → AttestationWorklistPage (lazy; REVIEWER/ADMIN — certify/revoke recertification items, bulk supported, AF-384; usage-evidence column + staleness-first server ordering + paging, #625)
/request-groups                     → RequestGroupListPage (lazy; grouped-request history — AF-501)
/request-groups/new                 → GroupBuilderPage (lazy; build + reorder + submit a grouped request — AF-501)
/request-groups/:id/edit            → GroupBuilderPage (lazy; re-open an own DRAFT for editing — #559)
/request-groups/:id                 → RequestGroupDetailPage (lazy; ordered step-by-step progress, live via WebSocket — AF-501)
/schema-change-sets                 → SchemaChangeSetListPage (lazy; SCHEMA_CHANGE_MANAGE — change sets with a ladder strip — #883)
/schema-change-sets/:id             → SchemaChangeSetDetailPage (lazy; SCHEMA_CHANGE_MANAGE — statement editor, promotion ladder, history — #883)
/schema-drift                       → SchemaDriftPage (lazy; SCHEMA_CHANGE_MANAGE — drift findings grouped by environment — #883)
/deployments                        → DeploymentListPage (lazy; deployment requests, own-scoped for non-reviewers — #696)
/deployments/:id                    → DeploymentDetailPage (lazy; metadata + AI risk + approvals + releasability banner + outcome timeline, plus the reviewer's approve/reject (#770) — #696)
/deployment-reviews                 → redirect → /reviews?tab=deployments, or ?tab=rollbacks when the legacy URL carried it (#772)
/deployment-versions                → DeploymentVersionsPage (lazy; any of DEPLOYMENT_PIPELINE_MANAGE / DEPLOYMENT_REVIEW / QUERY_ADMIN — org-wide version matrix with drift badges — #743)
/deployment-versions/:pipelineId    → PipelineVersionsPage (lazy; unguarded — the server's 404-never-403 rule is the gate, so can_trigger holders reach their own pipelines — #743)
/profile                            → ProfilePage

/datasources                        → DatasourceListPage
/datasources/new                    → DatasourceCreateWizardPage
/datasources/:id/settings           → DatasourceSettingsPage

/admin/users                        → UsersPage
/admin/roles                        → RolesPage (lazy; custom roles + permission-matrix editor — AF-522)
/admin/groups                       → GroupsListPage (lazy; user groups — AF-353)
/admin/groups/:id                   → GroupDetailPage (lazy; group membership — AF-353)
/admin/service-accounts             → ServiceAccountsPage (lazy; SERVICE_ACCOUNT_MANAGE — non-human identities list + create — #875)
/admin/service-accounts/:id         → ServiceAccountSettingsPage (lazy; SERVICE_ACCOUNT_MANAGE; tabs synced to ?tab=: overview, api-keys, mcp-tools, limits, principals, activity — #875)
/admin/audit-log                    → AuditLogPage
/admin/audit-sinks                  → AuditSinksPage (lazy; external SIEM/WORM audit sinks with per-sink delivery health — #628)
/admin/auditor                      → AuditorDashboardPage (lazy; AUDITOR or ADMIN — compliance reports + signed exports, AF-459)
/admin/anomalies                    → AnomaliesPage (lazy; AUDITOR or ADMIN — behavioural anomaly detection / UBA, AF-383)
/admin/attestation                  → CampaignListPage (lazy; ADMIN — access-recertification campaign list + create, AF-384)
/admin/attestation/:id              → CampaignDetailPage (lazy; ADMIN — campaign items + open/cancel + evidence CSV export, AF-384; usage-evidence column, #625)
/admin/over-provisioned-access      → OverProvisionedAccessPage (lazy; ADMIN or AUDITOR — unused/over-scoped standing grants + CSV export, #625)
/admin/privileged-access            → PrivilegedAccessPage (lazy; ADMIN or AUDITOR — QUERY_ADMIN holders and break-glass grantees with query evidence, #968)
/admin/access-simulations           → AccessSimulationPage (lazy; ADMIN or AUDITOR — query decision trace + effective-access reverse index, #1066)
/admin/lifecycle/policies           → LifecyclePoliciesListPage (lazy; ADMIN — retention/erasure-rule list + create + delete + dry-run preview; create modal embeds the shared ErasureConfigForm (target/columns/conditions/raw-WHERE; schema-driven cascading table→column pickers with free-text fallback when introspection is unavailable, #548) + cron field, AF-499/AF-519)
/lifecycle/erasure-reviews          → ErasureReviewQueuePage (lazy; REVIEWER/ADMIN — review-plan-based right-to-erasure review queue, optimistic approve/reject + scope snapshot, AF-519)
/lifecycle/erasure                  → ErasureSubmitPage (lazy; any authenticated — self-service erasure request submit (shared ErasureConfigForm, schema-driven target pickers #548) + my-requests list with cancel, AF-499/AF-519)
/admin/ai-configs                   → AiConfigListPage
/admin/ai-configs/new               → AiConfigCreateWizardPage (3-step wizard; connection step includes the optional system-prompt editor + RAG section)
/admin/ai-configs/:id               → AiConfigEditPage (edit connection + the per-config system prompt + RAG knowledge base)
/admin/ai-analyses                  → AiAnalysesPage (dashboard — risk-score-over-time + top categories + top submitters, lazy)
/admin/datasource-health            → DatasourceHealthPage (per-datasource pool ring + 24h query/latency/error stats, lazy)
/admin/routing-policies             → RoutingPoliciesPage (lazy; policy-as-code routing — AF-379)
/admin/sql-review                   → SqlReviewRulesetsPage (lazy; SQL_REVIEW_MANAGE — deterministic SQL review rulesets — #865)
/admin/deployment-pipelines         → DeploymentPipelinesPage (lazy; DEPLOYMENT_PIPELINE_MANAGE — pipeline CRUD — #696)
/admin/deployment-pipelines/:id     → DeploymentPipelineSettingsPage (lazy; tabs synced to ?tab=: general / environments / versions / permissions / freeze windows / routing policies / simulate / CI setup — #696, #743, #1066)
/admin/notifications                → NotificationsPage
/admin/languages                    → LanguagesConfigPage
/admin/governance-domains           → GovernanceDomainsPage (SETUP_PROGRESS_VIEW — the org's own API / deployment domain switches, #926)
/admin/drivers                      → CustomDriversPage (admin-uploaded JDBC drivers)
/admin/saml                         → SamlConfigPage
/admin/scim                         → ScimConfigPage (lazy; SCIM 2.0 provisioning — #621)
/admin/oauth2                       → OAuth2ConfigPage (lazy)
/admin/slack                        → SlackConfigPage (lazy; Slack app config — AF-362)
/admin/langfuse                     → LangfuseConfigPage (lazy; Langfuse tracing + prompt management — AF-333)
/admin/help-agent                   → HelpAgentConfigPage (lazy; in-app help chat agent settings + corpus status — AF-906)
/auth/oauth/callback                → OAuthCallbackPage (lazy, unauthenticated)
```

All routes except `/login`, `/setup`, `/invite/:token`, `/forgot-password`, `/reset-password/:token`, `/auth/saml/callback`, and `/auth/oauth/callback` are protected by an `AuthGuard` component that redirects unauthenticated users to `/login`. Route and nav gating is **permission-based** (AF-522): `AuthGuard` takes `requirePermission` (any-of over the `permissions` array carried in the auth payload) and `Sidebar` items declare `permissions: Permission[]` — the helpers live in `utils/permissions.ts` (`hasPermission`, `hasAnyPermission`, `usePermission`). Custom roles therefore gate correctly with no role-name special-casing; `/profile` is available to every authenticated user.

### Sidebar navigation (AF-837)

> **This file and `locales/en.json` are help-corpus sources (#925).** The in-app help assistant's
> menu vocabulary — every destination's label, its full `Group → Subgroup → Item` path and the
> permissions that reveal it — is parsed out of the `GROUPS` literal below and the `nav.*` keys it
> resolves. Renaming an entry or moving it between groups means regenerating
> `help-corpus/` (`node .github/scripts/build-help-corpus.mjs`) in the same commit, or the
> `help-corpus` CI job fails; a route with no `ROUTES` line in that script fails it too. See
> [help-corpus/README.md](../help-corpus/README.md) → "The UI vocabulary, and why it is derived".

`components/common/Sidebar.tsx` renders a **three-level** nav model:

```ts
interface NavSubGroup {
  id: string;
  label: string;
  items: NavItem[];
  domain?: GovernanceDomain;  // #926; absent ⇒ the always-on database domain
}
interface NavGroup {
  id: string;
  label?: string;          // absent ⇒ no divider heading (the top generic group)
  items?: NavItem[];       // rendered flat, directly under the group heading
  subgroups?: NavSubGroup[];
}
```

A group's own `items` always render **above** its `subgroups`, so a group can mix ungrouped
entries with sub-sections (only `system` does today). `NavItem` is unchanged — `permissions`
still gates each entry.

| Group | Sub-section | Routes |
|---|---|---|
| *(no heading)* | *(none)* | `/dashboard`, `/reviews` (the unified review queue — any of `QUERY_REVIEW` / `API_REQUEST_REVIEW` / `DEPLOYMENT_REVIEW`; the pending badge sums every queue the viewer may work, #772) |
| `WORKFLOW` | **Database** | `/editor`, `/queries` |
| | **API** *(domain `apis`)* | `/api-editor`, `/api-requests` |
| | **Deployments** *(domain `deployments`)* | `/deployments`, `/deployment-versions` |
| | **Request groups** | `/request-groups`, `/request-groups/reviews` |
| | **Access & lifecycle** | `/access-requests`, `/lifecycle/erasure`, `/lifecycle/erasure-reviews`, `/reviews/attestations` |
| `CONNECTIONS` | **Database** | `/datasources`, `/admin/connectors`, `/admin/drivers` |
| | **API** *(domain `apis`)* | `/api-connectors` |
| | **Deployments** *(domain `deployments`)* | `/admin/deployment-pipelines` |
| `SECURITY` | **Identity** | `/admin/users`, `/admin/groups`, `/admin/roles`, `/admin/service-accounts`, `/admin/saml`, `/admin/oauth2`, `/admin/scim` |
| | **Access control** | `/admin/access-requests`, `/admin/review-plans`, `/admin/routing-policies`, `/admin/sql-review`, `/admin/over-provisioned-access`, `/admin/privileged-access`, `/admin/access-simulations`, `/admin/break-glass` |
| | **Data governance** | `/admin/data-classifications`, `/admin/lifecycle/policies`, `/admin/attestation` |
| | **Audit & compliance** | `/admin/audit-log`, `/admin/audit-sinks`, `/admin/auditor` |
| `SYSTEM` | *(none)* | `/admin/datasource-health`, `/admin/anomalies`, `/admin/notifications`, `/admin/slack`, `/admin/languages`, `/admin/governance-domains` |
| | **AI** | `/admin/ai-configs`, `/admin/ai-analyses`, `/admin/langfuse`, `/admin/help-agent` |
| `PLATFORM` | *(none)* | `/admin/organizations`, `/admin/jobs` |

**Scheduled jobs (`/admin/jobs`, #923).** `JobsPage` (platform admins only — `AuthGuard
requirePlatformAdmin`, `platformAdmin: true` nav item) reads `GET /platform/jobs` through
`src/api/jobs.ts` (`jobKeys`) and renders one row per registered `@Scheduled` job: module, cadence,
last status (`JobStatusPill`, colours from `jobExecutionStatusColor`, which shows an abandoned
`RUNNING` row as a warning), last run, duration, a consecutive-failures badge and the summary-window
counts. `scheduling_enabled=false` with no jobs renders a "Scheduler disabled" empty state rather
than "no jobs"; `recording_enabled=false` adds a warning banner. Clicking a row opens
`components/jobs/JobExecutionsDrawer`, whose body is keyed by job name (page and status filter reset
without a `useEffect`) and pages `GET /platform/jobs/{jobName}/executions` server-side, with a
failed run's error in an expandable row. Read-only: there are no mutations.

`/admin/slack` is the one entry that **moved groups**: it left `SECURITY` for `SYSTEM`, next to
`/admin/notifications` — it is a notification channel, not a security control.

Behaviour:

- **Default closed.** Every sub-section starts collapsed; expanding is opt-in. A first login
  shows a short nav of group headings and the two generic entries, and the user opens the
  sections they work in.
- **Persisted.** Expanded sub-section ids live in `preferencesStore.navExpandedSubgroups`
  (`toggleNavSubgroup`), persisted under `af-preferences`. It is an **allow-list** of ids, so a
  sub-section added later starts collapsed like every other, and an older payload that predates
  the key falls back to `[]` through zustand's shallow merge. Persist `version` is **2**: the
  v1 → v2 migration drops the retired `navCollapsedSubgroups` deny-list without inverting it —
  everyone starts fully collapsed once.
- **Active route always visible.** A sub-section containing the active route renders open
  regardless of the stored state, so a deep link — or a fresh login straight onto a page — never
  leaves the current page hidden behind a closed header. Its header is `disabled` while that
  holds: toggling could not change anything on screen, so a live control there would be a dead
  button announcing the wrong action. Its `aria-label` says so (`nav.section_locked_open`);
  sibling headers in the same group stay interactive.
- **One entry highlights at a time.** Nav destinations nest (`/request-groups` and
  `/request-groups/reviews`, `/reviews` and `/reviews/attestations`), so the active item is the
  **longest** declared path matching the current location, not every prefix of it. `NavLink`
  takes the callback form of `className` so react-router's own plain-prefix `active` class is
  not appended on top.
- **Icon rail.** When the sidebar is collapsed there is no room for headers: sub-section headers
  are dropped, every item renders flat, and each block (a group's own items, then each
  sub-section) is separated by `.af-sidebar-divider-line`. No collapsing inside the rail.
- **Permission filtering** cascades: invisible items are dropped, then sub-sections left empty,
  then groups left with neither items nor sub-sections. A sub-section that survives with a single
  item still renders its header, so positions stay predictable across roles.
- **Governance-domain filtering (#926)** runs first, on whole sub-sections: a `NavSubGroup` whose
  `domain` the organization switched off is dropped before its items are permission-filtered. It is
  **visibility, never entitlement** — an enabled domain grants nothing, a disabled one hides only
  entries the user could already see, every route stays registered and every deep link still works.
  The flags come from `governanceDomainsOf(user)` over the same signed-in user the permission
  checks use, so the nav can never disagree with itself; both default to `true` when absent, which
  is what keeps a session issued before #926 rendering the full nav.
- **Accessibility (a11y).** Each header is a real `<button aria-expanded aria-controls>` whose accessible name is
  `nav.expand_section` / `nav.collapse_section`; the controlled item list carries
  `id="af-nav-sub-<subgroup id>"`. Labelled groups are `role="group" aria-label="<group label>"`,
  which is what tells apart the sub-sections that reuse a label (**Database**, **API** and
  **Deployments** each appear under two groups).

Labels are `t()`-keyed under `nav.sub_*` (plus `nav.group_connections`, which replaced
`nav.group_data`) in all seven locales.

### Governance domains — the discovery model (#926)

`organizations.governs_apis` / `.governs_deployments` (AF-898) reach the SPA on the auth payload
(`AuthUser.governs_apis` / `.governs_deployments`, both **optional**) and are read through one
place — `hooks/useGovernanceDomains.ts`:

```ts
export type GovernanceDomain = 'apis' | 'deployments';
export type GovernanceDomains = Record<GovernanceDomain, boolean>;
export function governanceDomainsOf(user: AuthUser | null | undefined): GovernanceDomains;
export function useGovernanceDomains(): GovernanceDomains;   // the same, over authStore
```

Three surfaces consume it, and only these three: the sidebar sub-sections (`NavSubGroup.domain`),
the review-hub tabs (`REVIEW_HUB_TAB_DOMAIN`) and the dashboard widget catalogue
(`WIDGET_DOMAIN`). Two rules hold everywhere:

- **Permission AND domain.** An enabled domain grants nothing; a disabled one hides only what the
  user could already see. No route is unregistered, no `AuthGuard` check changes, no endpoint's
  authorization moves — hiding a domain must never 403 anyone, and the notification deep links
  into `/deployments` and `/reviews?tab=deployments` keep working.
- **Fail open on unknown.** An absent flag reads as `true`. A session issued before #926, or any
  payload that omits one, renders exactly as it did before; only an explicit `false` hides
  anything.

`GovernanceDomainsPage` (`/admin/governance-domains`, nav entry in **System**, guarded on
`SETUP_PROGRESS_VIEW`) is the in-app way back for an org admin who declined a domain in the
first-run wizard — before #926 that needed a platform admin on `/admin/organizations/:id`. Two
switches over `GET`/`PUT /admin/governance-domains`; on save it patches the cached session user
through `authStore.patchUser`, so the sidebar, review tabs and dashboard react on that render
rather than at the next token refresh.

### Setup wizard

`SetupPage` is a three-step state machine (`Step = 'account' | 'domains' | 'smtp'`).

Step 1 collects org name + admin email/password and **only advances** — it fires no request. `POST /auth/setup` is one-shot, so it must carry the governance-domain answer, which does not exist yet at that point; the button therefore reads **Continue**, not "Create admin".

Step 2 (AF-898) asks which domains the organization plans to govern: two switches, **Govern outbound API calls** and **Gate CI/CD deployments**, each off by default with one explanatory line and a `docsUrl()` deep link to the matching docs chapter. Database access governance is always on and has no switch. This is the step that submits `POST /auth/setup` — **Create admin** sends the switch values, **Skip — databases only** sends both as `false`; both paths create the admin. The response returns a `LoginResponse` and sets the refresh cookie so the SPA can call admin endpoints as the freshly-created admin. A failure keeps the user on this step with the shared error `Alert`, both buttons still live. **Back** returns to step 1 with the typed values intact — the request can be rejected on an account field (409 `EMAIL_ALREADY_EXISTS`), which lives on the previous step. Copy is `t()`-keyed under `auth.setup.domains.*`.

Each step's `<Form>` carries its own `key`, so React remounts rather than reusing the fiber: rc-field-form latches both the `form` prop and `initialValues` on first mount, so without the keys only `accountForm` would ever be bound and every step's `initialValues` after the first would be silently dropped.

The answer is changeable later by an org admin on `/admin/governance-domains` (`GovernanceDomainsPage`, `PUT /admin/governance-domains`, `SETUP_PROGRESS_VIEW` — #926). The same two switches also remain on `/admin/organizations/:id` (`OrganizationDetailPage`, `PUT /platform/organizations/{id}`) for a platform admin managing any tenant; the first-run admin is provisioned as a platform admin, so both routes are open to them.

Step 3 is optional system-SMTP configuration that posts to `PUT /admin/system-smtp` — the **Skip for now** button bypasses it and lands on `/queries`. Users can configure or change SMTP later from `/admin/notifications` (the **System SMTP** card sits above the channels grid).

### User invitations on `/admin/users`

The primary action button is now a `Dropdown.Button` — the default click sends an email invitation (`POST /admin/users/invitations`), while the dropdown menu still exposes the legacy "Create with password" path (`POST /admin/users`). A **Pending invitations** table below the user list shows invitations and exposes per-row resend / revoke actions (`POST /admin/users/invitations/{id}/resend`, `DELETE /admin/users/invitations/{id}`).

The **Edit user** modal includes an **Attributes** key/value editor (AF-380, a `Form.List`) bound to `users.attributes`. Current values are loaded via `GET /admin/users/{id}/attributes` (`getUserAttributes`) and saved through the existing `PUT /admin/users/{id}` with the `attributes` field. These attributes resolve in row-security predicates as `:user.<key>` (up to 50 entries; key ≤ 128, value ≤ 512 chars — validation parity with the backend).

Every user row's action menu has a **Data usage** item (#942) that opens `components/admin/UserDataBudgetDrawer.tsx` — there is no user detail page. It lists, per datasource where a budget applies to that user, each budget's rows / bytes used against its limits, the window, and a *Used up* tag, from `GET /admin/users/{id}/data-budget-usage` (`dataBudgetKeys.forUser`); an empty state says no budget applies.

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
policy-as-code routing engine. The nav entry **Routing policies** sits in Security →
**Access control**, next to **Review plans**. The page renders a `<Table>` of the org's policies in priority order with
priority up/down reorder controls (`PUT /admin/routing-policies/reorder`), an action pill
(`AUTO_APPROVE` / `AUTO_REJECT` / `REQUIRE_APPROVALS` / `ESCALATE`), an `enabled` `Switch` toggle, a
human-readable condition summary, and per-row edit / delete. Create / edit open a `Modal` with a
**guided condition builder** — the builder produces a single-level ALL (AND) / ANY (OR) of leaf
conditions, each optionally negated (NOT); there is no raw-JSON editor. API access lives in
[frontend/src/api/routingPolicies.ts](../frontend/src/api/routingPolicies.ts); the form↔wire mapping
helper is [frontend/src/pages/admin/routingPolicyForm.ts](../frontend/src/pages/admin/routingPolicyForm.ts);
types (`RoutingPolicy`, `RoutingCondition`, `RoutingAction`, …) live in `src/types/api.ts`.
The builder offers a **Query shape** operand (#940, `query_shape`) as a required multi-select of the
eight `QueryShape` values (`QUERY_SHAPES` / `queryShapeLabel` in `src/utils/enumLabels.ts`); the row
summary lists the labelled shapes, and the decision trace labels `query_shapes` / `denied_shapes`
details the same way.
The builder covers the `estimated_rows` (comparison operator + row count) and `scan_type`
(glob tags, e.g. `Seq*`, `COLLSCAN`) pre-flight-estimate operands (AF-624) alongside the
original leaf set, plus `estimated_bytes_scanned` (#941): a comparison operator and a byte count
entered through the shared `components/common/BytesInput.tsx` (amount + MB/GB/TB/PB unit select,
decimal units; the form value and the wire value are always raw bytes — helpers `pickUnit` /
`byteUnitFactor` in `src/utils/bytesCap.ts`). Its summary renders through `formatBytes`. `QueryDetailPage` shows a **matched-policy** alert when `GET /queries/{id}`
returns a non-null `matched_policy`, and a **bytes-scanned cap** alert (`data-testid="bytes-cap-banner"`,
#941) when it returns `bytes_scanned_cap`: the outcome label (`enums.bytes_scanned_cap_outcome.*`) as
the title — an error for `EXCEEDED` / `NO_ESTIMATE_REJECTED`, a warning for `NO_ESTIMATE_REVIEW`, info
for `WITHIN` — and the estimate, the cap and its source in the body. The decision trace labels the
`BYTES_SCANNED_CAP` step and formats its byte details through `formatBytes`.
The builder also offers **Data budget used (%)** (`data_budget_used_percent`, #942): a comparison
operator and a whole-percent value (≥ 0, above 100 allowed), mapped in `routingPolicyForm.ts`. The
decision trace labels the `DATA_BUDGET` step and its `data_budget_*` details (including
`data_budget_suppressed` on the routing and review-plan steps). A result cut short by the
submitter's remaining allowance shows its own truncation notice — `QueryResultsTable` and the
table-preview `SampleDataPreview` map `truncated_reason: "DATA_BUDGET"` to a dedicated message beside
the `ROW_LIMIT` / `BYTE_LIMIT` ones.

### Policy simulator (AF-630)

The **Simulate** affordance lets an admin dry-run a *draft* policy against the organization's own
historical traffic before saving it. It appears on all three policy forms, driven by one shared API
module and one shared drawer:

- **Where.** The create/edit `Modal` on `RoutingPoliciesPage` (`/admin/routing-policies`), and the
  create/edit modals of the **Masking** and **Row security** tabs on `DatasourceSettingsPage`. Each
  form gets a secondary **Simulate** button beside its submit action — enabled only once the form
  passes the same client-side rules a save would, since the backend validates the draft exactly as a
  create.
- **API module.** [frontend/src/api/policySimulation.ts](../frontend/src/api/policySimulation.ts) —
  one function per endpoint (`simulateRoutingPolicy`, `simulateRowSecurityPolicy`,
  `simulateMaskingPolicy`) over the three `POST …/simulate` paths. The call is a **TanStack Query
  mutation**, not a query: it is user-triggered, has a request body, and must not re-run on focus.
  There is deliberately no query-key factory — a simulation is not cached server state. Response
  types (`RoutingSimulationResponse`, `RowSecuritySimulationResponse`, `MaskingSimulationResponse`,
  `SimulationCaveat`) live in `src/types/api.ts`.
- **The window.** The drawer picks the replay window itself, with a `Segmented` control offering the
  last 7 / 30 / 90 days (30 by default). 90 days is the server's own `max-window` default, so the
  presets cannot produce a `400 INVALID_SIMULATION_PERIOD`; if a deployment lowers the knob, the
  error detail is surfaced through `apiErrorMessage` rather than swallowed. The routing corpus is
  the draft's own `datasource_id` — the API accepts a separate corpus scope, but the UI does not
  expose one.
- **The results drawer.**
  [frontend/src/components/policies/PolicySimulationDrawer.tsx](../frontend/src/components/policies/PolicySimulationDrawer.tsx)
  — a right-side `Drawer` (never a nested modal: the form stays open behind it, so an admin can
  adjust the draft and re-run) showing the replayed-row count and the headline stats for that policy
  kind, a `truncated` warning when the row cap was hit, a **per-user impact** table ordered by blast
  radius, and a second drill-down tab (routing and row security: sample queries; masking: per-column
  counts). Its body is **keyed on the draft**, so editing the form and reopening starts a clean run
  with no `useEffect`. An error renders as an error — never as "nothing would change", which is a
  positive claim about governance data that a failed request must not be able to make.
  Every string is `t()`-keyed under `policySimulation.*`; row-security transitions resolve through an
  exhaustive `Record<RowSecurityTransition, string>` map in `policySimulationSummaries.ts`, never a
  key built by string interpolation.
- **Caveats are rendered, not hidden.** The response's `caveats` array becomes a visible `Alert` list
  above the numbers ("memberships are read as they are now", "masking is matched on bare column
  names", …). Unclassifiable rows carry their own count — the UI must never fold them into
  "unaffected", because "we could not tell" is not "nothing breaks".
- **A soft nudge, never a hard gate.** Saving is never blocked on having simulated. A draft that
  could take access away without a human seeing it — a routing `AUTO_APPROVE`/`AUTO_REJECT`, or an
  *edit* to a row-security or masking policy that applies to everyone — gets one `modal.confirm`
  step offering **Simulate first** or **Save anyway**, and only when the current draft differs from
  the last one simulated. Esc is disabled on that confirm (`keyboard: false`) because its cancel arm
  is the write path. Creates never nudge: the forms default to org-wide, applies-to-everyone values,
  so prompting there would fire every time and train the admin to dismiss it. The pure predicates
  live in
  [frontend/src/components/policies/policyImpact.ts](../frontend/src/components/policies/policyImpact.ts).

### SQL review rulesets (#865, epic #860)

`SqlReviewRulesetsPage` (`/admin/sql-review`, lazy, `SQL_REVIEW_MANAGE`) is the admin surface for
the deterministic SQL review rules the backend evaluates at submission (#862–#864). The nav entry
**SQL review** sits in Security → **Access control**, next to **Routing policies**. The page renders
a `<Table>` of the organization's rulesets — name + description, the environment binding as a pill
(`Development` / `Test` / `Staging` / `Production`, or **Organization default** when `environment` is
absent), an effective-severity summary (`N block · N warn · N off` across the whole catalog,
configured rows plus built-in defaults), an `enabled` `Switch` (PUT is a full replace, so the toggle
resends the stored rules verbatim through `toggleEnabledRequest`), and per-row edit / delete
(`modal.confirm`). Create / edit open a `Modal` with name (`{required, max: 255, whitespace}` ↔
`@NotBlank @Size(max = 255)`), description (`{max: 2000}` ↔ `@Size(max = 2000)`), an environment
`Select` whose first option is the organization default (form sentinel `ORG_DEFAULT_ENVIRONMENT`,
mapped to an *omitted* `environment` on the wire), an enabled `Switch`, and a **rules table driven
by `GET /sql-review/rules`** — never a client-side list, so a rule added on the backend appears
without a frontend change. Each row shows the localized rule name / description / category, a
severity **`Select`** (`aria-label` "Severity for {rule}"; a `Select`, not `Segmented`, because
Playwright cannot drive Segmented's hidden radio) with the built-in default under it, and a
`mode="tags"` input per declared param (`protected_table.globs`, `disallowed_function.names`) whose
placeholder shows the built-in defaults.

The form ↔ wire mapping is the pure, unit-tested
[frontend/src/pages/admin/sqlReviewForm.ts](../frontend/src/pages/admin/sqlReviewForm.ts):
`toFormValues` seeds every catalog rule with its configured or default severity; `toWriteRequest`
emits a rule row **only when its severity differs from the built-in default or it carries params**
(unlisted rules run at their default, exactly as the backend resolves them, so rulesets stay
sparse), omits an empty param list rather than sending `[]`, and sends `enabled` always and
`description` only when non-blank; `validateParam` mirrors the backend's 422
`SQL_REVIEW_RULESET_INVALID` rules client-side (a required param with no built-in defaults needs
≥ 1 entry whenever the row is stored; every entry must match the param's whole-string
`value_pattern`) while the server stays authoritative. API access lives in
[frontend/src/api/sqlReview.ts](../frontend/src/api/sqlReview.ts) (`sqlReviewKeys`), error toasts
go through `sqlReviewRulesetErrorMessage` (409 `SQL_REVIEW_RULESET_ENVIRONMENT_CONFLICT` /
`_DEFAULT_CONFLICT`, 422 `_INVALID` preferring the backend's localized `detail`, 404 `_NOT_FOUND`),
and the types (`SqlReviewRuleset`, `SqlReviewRule`, `SqlReviewFinding`, `SqlReviewSeverity`, …) live
in `src/types/api.ts`. The page header's *View docs* link is `docsAnchor="cfg-sql-review"` (#866 —
`DOCS_ANCHOR_PAGES` → `configuration/review-workflows/`, the section beside routing policies). The
feature chapter is [docs/19-sql-review.md](19-sql-review.md).

### Service accounts admin pages (#875, epic #867)

The web surface for non-human identities. API module `src/api/serviceAccounts.ts`
(`serviceAccountKeys` factory: `lists/list/details/detail/delegations(id)/mcpTools`; the twelve
functions over `/admin/service-accounts` incl. `listMcpTools`, which unwraps
`GET /admin/service-accounts/mcp-tools`). Types in `src/types/api.ts` (`ServiceAccount`,
`ServiceAccountKey`, `ServiceAccountDelegation`, `UpdateServiceAccountInput` with its `clear`
list, `PrincipalType`, `ServiceAccountSource`). Enum labels `principalTypeLabel` /
`serviceAccountSourceLabel` / `serviceAccountDelegationStatusLabel` / `mcpToolDescription`
(`enums.mcp_tool.<wire name>`, empty for a tool this build does not know) in
`src/utils/enumLabels.ts`; errors through `serviceAccountErrorMessage` (`SERVICE_ACCOUNT_*`
codes, the 409's `field` and the 422's `tool` interpolated into the copy). Every string is
`t()`-keyed under `admin.service_accounts.*`.

The encoding decisions live in the pure, coverage-listed
`src/pages/admin/service-accounts/serviceAccountForm.ts`, never in a component:

- **Validation parity** is a constraint *table* per backend record (`CREATE_FORM_CONSTRAINTS`,
  `UPDATE_FORM_CONSTRAINTS`, `KEY_FORM_CONSTRAINTS`) that `fieldRules(t, …)` turns into AntD rules.
  `__tests__/createFormParity.test.ts` reads the Java request records with `readFileSync` and
  asserts the tables against their `@NotBlank` / `@Email` / `@Size(max)` / `@Positive`
  annotations field for field — a constraint added on one side alone fails the build.
- **MCP tools** are three-valued on the wire (`null` = every tool, `[]` = none, else the names)
  and a PUT reads `null` as *unchanged*, so the form holds `{ mode: 'ALL' | 'RESTRICTED', tools }`:
  `ALL` saves as `{ clear: ['MCP_TOOL_ALLOW_LIST'] }`, `RESTRICTED` as the list (an empty set sends
  `[]` and the tab warns that every call will be denied). The tab repeats the #872 caveat that
  `tools/list` still advertises every tool — the allow-list is an enforcement boundary.
- **Limits**: a blanked `InputNumber` means "back to the deployment default", which is the field
  name in `clear`; untouched fields are omitted.
- **Overview** sends only changed fields (blank description / unset owner go through `clear`).
  On a `BOOTSTRAP` account the declared fields (display name, role) are disabled in the form *and*
  stripped by `overviewUpdateInput`, so an untouched submit is a no-op rather than a 409 round trip;
  the page shows one banner naming `ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS_<n>_*` as the source.

| Route | Page | Notes |
|-------|------|-------|
| `/admin/service-accounts` | `ServiceAccountsPage` | Server-paged list — display name, email, role pill, owner (`owner_display_name`/`owner_email`, resolved server-side), status, `ManagedByTag` (UI / Bootstrap), active key count, last used, tool count ("2 / 12 tools", "All tools", "No tools" — the total comes from the catalog query), rate limit ("60/min · 500/day" or "Deployment default"). `managed_by` filter. Create modal: email, display name, role (READONLY preselected once roles load; `RoleField` shows a warning `Alert` when the chosen role carries any `*_REVIEW` permission, because the account becomes an eligible approver), owner (people only — `useHumanUserOptions` calls `listUsers({ principal_type: 'HUMAN' })`), description, two rate limits. Create navigates into settings. |
| `/admin/service-accounts/:id` | `ServiceAccountSettingsPage` | Header: display name, email, `ManagedByTag`, active tag, **Deactivate** (`Popconfirm`) and Back. Tabs in `src/components/serviceaccounts/`: **Overview** (`ServiceAccountOverviewTab`), **API keys** (`ServiceAccountKeysTab` — key table with a `Declared` tag on `bootstrap_declared` keys; *Issue key* and *Rotate* modals share the `ApiKeysSection` future-only `ExpiresAtPicker`; the rotate modal takes a grace in hours → `PT{n}H`, default PT24H; `IssuedKeyModal` is the show-once treatment (`copy_once_warning` + `Typography.Paragraph copyable`) and, after a rotation, states until when the superseded key keeps working; a declared key's Rotate/Revoke are disabled inside one tooltip that explains the re-import would resurrect it and names the env var to remove), **MCP tools** (`ServiceAccountToolsTab` — radio + `Checkbox.Group` over the server catalog), **Limits** (`ServiceAccountLimitsTab`), **On-behalf-of principals** (`ServiceAccountPrincipalsTab` — delegations table with status pill and revoke on `ACTIVE`; grant modal with a people-only picker and optional expiry), **Activity** (`ServiceAccountActivityTab` — `auditKeys.list({ actor_id })`, `OnBehalfOfTag` per row, and an *Open in audit log* link to `/admin/audit-log?actor_id=<id>`). Synced to `?tab=` (unknown → `overview`). |

**Keeping robots legible (#875).** `principal_type` now rides on every user-shaped payload the
UI lists, and three shared components in `src/components/common/` render it:
`PrincipalTypeTag` (a pill only for `SERVICE_ACCOUNT`; nothing for a person, so it can sit beside
any user cell), `renderUserOption` (an AntD `Select optionRender` that keeps the option `label` a
plain string — every picker's `showSearch.optionFilterProp: 'label'` still works — and draws the
badge beside it; fed by `userSelectOptions` in `src/utils/userOptions.ts`), and `ManagedByTag`.
`UsersPage` badges agents, routes their row action to the service-account page instead of the
edit modal, and offers a *People and service accounts / People only / Service accounts only*
filter that is server-side (`?principal_type=`) because an agent can sit on any page; the default
shows everyone. The eight `listUsers`-fed pickers (group members, pipeline / connector / datasource
permission grants, masking, row-security and export-policy reveal lists) badge agents through
`renderUserOption`; `GroupDetailPage` badges members. `AttestationWorklistPage` and the campaign
item table badge a service-account subject and the worklist adds an *Owned by {name}* / *No owner
assigned* line from `subject_owner_*`, so a reviewer recognises the human accountable for a
robot's grant.

**On-behalf-of chips (#874).** `OnBehalfOfTag` (`src/components/common/`) is the one treatment:
a blue `Tag` reading *on behalf of {name}* with a tooltip, the review queue's *Delegated* tag
look (#622). It renders beside the submitter on `QueryDetailPage` (`query.on_behalf_of`),
`ApiRequestDetailPage` and `DeploymentDetailPage` (`on_behalf_of_email` / `_user_id`), and under
the actor on `AuditLogPage` rows (`on_behalf_of_email`, resolved server-side from
`metadata.on_behalf_of_user_id`; a *via service account* line when `metadata.service_account`).
`AuditLogPage` gained an *On behalf of (user id)* filter (`onBehalfOfUserId`) and seeds `actor_id`
/ `on_behalf_of_user_id` from the URL once on mount, which is what the Activity tab links into.
The action / resource-type filter lists include the `SERVICE_ACCOUNT_*` actions and the
`service_account` resource.

**Calling application (#938).** `ClientApplicationTag` (`src/components/common/`) renders the
recorded application name in `code` style with a tooltip naming its source; a `HEADER` source
(`application_name_source`, or the lowercase `header` in audit metadata) adds a warning `Tag`
reading *Untrusted*. It appears in the `QueryDetailPage` subtitle (`query.application_name`) and
under the actor on `AuditLogPage` rows and in its detail drawer (from `metadata.application_name`).
`QueryListPage` and `AuditLogPage` each gained an *Application* text filter (`application_name` /
`applicationName`, sent server-side; the audit page also seeds `?application_name=` once on
mount). The key create / issue / rotate forms (`ApiKeysSection`, `ServiceAccountKeysTab`) take an
optional *Application name* (`max: 100`, mirrored in `KEY_FORM_CONSTRAINTS`; on rotate, empty keeps
the superseded key's) and both key tables gained an *Application* column. Strings live under
`client_application.*`; the source enum label is `applicationNameSourceLabel` in `enumLabels.ts`.

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

`/profile` is composed of six Ant Design cards in `src/pages/profile/`:

- `DisplayNameForm` — Ant `Form` with a single input bound to `useMutation(updateProfile)`. On success it invalidates `meKeys.current` and patches `authStore.user.display_name` so the top-bar reflects the new name immediately.
- `ChangePasswordForm` — current / new / confirm fields (`min: 8, max: 128`). Hidden when `profile.auth_provider === 'SAML'`. On success the backend revokes all refresh tokens; the frontend explicitly calls `authStore.clear()` and navigates to `/login` so the user can re-authenticate cleanly.
- `SlackLinkSection` (AF-362) — links the AccessFlow account to a Slack user so approve/reject buttons attribute to the right person. When unlinked, **Generate code** (`createSlackLinkCode`) issues a one-time `/accessflow link <code>` snippet (copyable) to run in Slack; when linked it shows the mapped `slack_user_id` and an **Unlink** action (`unlinkSlack`, `Popconfirm`). Backed by `slackLinkKeys` in [frontend/src/api/slack.ts](../frontend/src/api/slack.ts).
- `ReviewDelegationSection` (#622) — out-of-office delegation. A delegate `Select` fed by `GET /me/review-delegations/candidates` (the admin user listing needs `USER_MANAGE`, which most reviewers lack), an optional scope, a `RangePicker` window, and two tables — granted and received — with revoke offered only while a delegation can still confer eligibility. Errors surface the server's localized `detail` through `apiErrorMessage`, since the backend names the specific rule that failed. Backed by `reviewDelegationKeys` in [frontend/src/api/reviewDelegations.ts](../frontend/src/api/reviewDelegations.ts).
- `ApiKeysSection` (AF-286) — personal API keys for the MCP server and programmatic clients. An AntD `Table` (name / prefix / created / last used / expires / status — the status is derived, reading **Revoked**, **Expired** once `expires_at` has passed, else **Active**) with a per-row **Revoke** `Popconfirm`, a **Create API key** modal (required `name`, 1–100 chars, plus an optional **Expires** `DatePicker` with `showTime` whose past dates and hours are disabled — #824), and a second modal that shows the raw `af_…` value once, copyable. A blank expiry sends only `name`, so the key never expires. Backed by `apiKeysKeys` in [frontend/src/api/apiKeys.ts](../frontend/src/api/apiKeys.ts).
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
