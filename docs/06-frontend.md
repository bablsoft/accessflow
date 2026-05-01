# 06 — Frontend Architecture

## Tech Stack

| Technology | Version | Purpose |
|-----------|---------|---------|
| React | 18 | UI framework |
| Vite | 5 | Build tool and dev server |
| TypeScript | 5 | Type safety |
| Ant Design | 5.x | UI component library |
| CodeMirror | 6 | SQL editor engine |
| Zustand | 4 | Global state management |
| React Query (TanStack) | 5 | Server state, caching, refetching |
| Axios | 1.x | HTTP client |
| React Router | 6 | Client-side routing |
| sql-formatter | 15 | SQL formatting (Ctrl+Shift+F) |

---

## Project Directory Structure

```
accessflow-ui/
├── public/
│   └── favicon.svg
├── src/
│   ├── api/                        # Axios client instances, one per domain
│   │   ├── client.ts               # Base Axios instance with JWT interceptor
│   │   ├── queries.ts              # Query request API calls
│   │   ├── datasources.ts          # Datasource API calls
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
│   │   │   ├── CopyButton.tsx      # Copy-to-clipboard wrapper
│   │   │   └── PageHeader.tsx      # Consistent page header with breadcrumbs
│   │   │
│   │   ├── editor/
│   │   │   ├── SqlEditor.tsx       # CodeMirror 6 SQL editor component
│   │   │   ├── AiHintPanel.tsx     # Inline AI analysis results panel
│   │   │   ├── SchemaTree.tsx      # Sidebar schema/table browser
│   │   │   └── EditorToolbar.tsx   # Format, run, datasource selector
│   │   │
│   │   ├── review/
│   │   │   ├── ReviewCard.tsx      # Query card in review queue
│   │   │   ├── ApprovalTimeline.tsx # Visual timeline of review stages
│   │   │   ├── ReviewDecisionForm.tsx # Approve/reject form with comment
│   │   │   └── AiAnalysisAccordion.tsx # Expandable AI analysis details
│   │   │
│   │   ├── datasources/
│   │   │   ├── DatasourceForm.tsx  # Create/edit datasource form
│   │   │   ├── ConnectionTester.tsx # Live connection test widget
│   │   │   ├── PermissionMatrix.tsx # User × permission grid
│   │   │   └── ReviewPlanPicker.tsx # Review plan assignment dropdown
│   │   │
│   │   └── audit/
│   │       ├── AuditLogTable.tsx   # Searchable audit event table
│   │       └── AuditDetailDrawer.tsx # Slide-in detail for single event
│   │
│   ├── hooks/
│   │   ├── useQueryRequest.ts      # CRUD + status polling for a query request
│   │   ├── useReviewQueue.ts       # Pending reviews for current user
│   │   ├── useWebSocket.ts         # WebSocket connection and event subscriptions
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
│   │   │   └── SamlCallbackPage.tsx  # Enterprise SSO callback handler
│   │   │
│   │   ├── editor/
│   │   │   └── QueryEditorPage.tsx   # Full SQL editor with submit flow
│   │   │
│   │   ├── queries/
│   │   │   ├── QueryListPage.tsx     # Paginated query history
│   │   │   └── QueryDetailPage.tsx   # Full detail view for a single query
│   │   │
│   │   ├── reviews/
│   │   │   └── ReviewQueuePage.tsx   # Pending reviews for current reviewer
│   │   │
│   │   ├── datasources/
│   │   │   ├── DatasourceListPage.tsx
│   │   │   └── DatasourceSettingsPage.tsx
│   │   │
│   │   └── admin/
│   │       ├── UsersPage.tsx
│   │       ├── AuditLogPage.tsx
│   │       ├── AIConfigPage.tsx
│   │       ├── NotificationsPage.tsx
│   │       └── SamlConfigPage.tsx    # Enterprise only
│   │
│   ├── store/
│   │   ├── authStore.ts             # Current user, JWT, login/logout actions
│   │   ├── notificationStore.ts     # In-app toast notifications queue
│   │   └── preferencesStore.ts      # Editor theme, font size, etc.
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
- **CodeMirror SQL editor** — see SQL Editor section below
- **AI Hint Panel** — displays current AI analysis (debounced, updates as user types)
- **Justification field** — required text area for the reason behind the query
- **Submit button** — sends `POST /queries`, transitions to status tracking view
- **Status tracker** — real-time status updates via WebSocket (`PENDING_AI` → `PENDING_REVIEW` → `APPROVED` → `EXECUTED`)

### ReviewQueuePage

Available to users with `REVIEWER` or `ADMIN` role:

- Paginated list of queries in `PENDING_REVIEW` status assigned to this reviewer
- Each card shows: datasource name, submitter, query type, AI risk badge, time elapsed, SQL preview
- Quick approve/reject inline on card; full detail opens in a right-side drawer
- `ApprovalTimeline` shows which reviewers in the plan have already decided

### QueryDetailPage

Full detail view for any query:

- SQL text in read-only CodeMirror block with syntax highlighting
- `AiAnalysisAccordion` — expandable section showing risk score, all issues with suggestions
- `ApprovalTimeline` — visual timeline of review stages and decisions with reviewer comments
- Execution result section (if executed): rows affected, duration, timestamp
- Cancel button (if query is in `PENDING_*` status and viewer is the submitter)

### DatasourceSettingsPage *(ADMIN)*

- Connection config form with live test button (`POST /datasources/{id}/test`)
- Schema explorer with table/column tree
- `PermissionMatrix` — table of all users × (can_read, can_write, can_ddl, row_limit, allowed_tables, expires_at)
- Review plan assignment and row limit configuration

### AuditLogPage *(ADMIN)*

- Searchable, filterable table of all audit events
- Filters: date range picker, user selector, action type multi-select
- Row click opens `AuditDetailDrawer` with full metadata JSON

---

## SQL Editor Component

Built on **CodeMirror 6** (`@codemirror/lang-sql`).

### Features

| Feature | Implementation |
|---------|---------------|
| SQL syntax highlighting | `@codemirror/lang-sql` with dialect set from selected datasource |
| Table/column autocomplete | Schema fetched from `/datasources/{id}/schema`, passed as `schema` option to `sql()` language extension |
| Keyword autocomplete | Built into `@codemirror/lang-sql` |
| Real-time AI hints | Debounced (800ms) calls to `POST /queries/analyze`; issues shown as CodeMirror gutter markers and in the AI Hint Panel |
| Query formatter | `sql-formatter` library called on `Ctrl+Shift+F` keyboard binding |
| Read-only mode | `EditorState.readOnly` extension set to `true` for detail/history views |
| Theme | Custom theme matching Ant Design token colors; dark/light follows OS preference |
| Risk indicator | Risk score badge in toolbar updates live as AI analysis returns |

### AiHintPanel

Displayed below or beside the editor. Shows:

- Overall `risk_level` badge (LOW / MEDIUM / HIGH / CRITICAL)
- `risk_score` progress bar
- List of issues, each with severity icon, message, and expandable suggestion
- "Analyzing…" skeleton state while request is in flight
- Empty state if SQL is blank or analysis returns no issues

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

```typescript
// useWebSocket.ts
const { subscribe, unsubscribe } = useWebSocket();

// Subscribe to events for a specific query
subscribe('query.status_changed', (data) => {
  if (data.query_id === currentQueryId) {
    queryClient.invalidateQueries(['query', currentQueryId]);
  }
});
```

---

## Environment Variables

```env
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080/ws
VITE_APP_EDITION=community         # community | enterprise
```

---

## Routing Structure

```
/login                              → LoginPage
/auth/saml/callback                 → SamlCallbackPage (Enterprise)

/editor                             → QueryEditorPage
/queries                            → QueryListPage
/queries/:id                        → QueryDetailPage
/reviews                            → ReviewQueuePage

/datasources                        → DatasourceListPage
/datasources/:id/settings           → DatasourceSettingsPage

/admin/users                        → UsersPage
/admin/audit-log                    → AuditLogPage
/admin/ai-config                    → AIConfigPage
/admin/notifications                → NotificationsPage
/admin/saml                         → SamlConfigPage (Enterprise)
```

All routes except `/login` and `/auth/saml/callback` are protected by an `AuthGuard` component that redirects unauthenticated users to `/login`. Admin routes additionally check `user.role === 'ADMIN'`.
