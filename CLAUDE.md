# CLAUDE.md — AccessFlow Agent Rules

This file is the authoritative guide for AI agents implementing AccessFlow. Read it entirely before writing any code. When in doubt, prefer the rules here over general best-practice intuition.

---

## Project at a Glance

AccessFlow is an open-source **database access governance platform**. It acts as a full SQL proxy between users and customer databases (PostgreSQL / MySQL), enforcing configurable review and approval workflows before any query executes. Core capabilities: AI-powered SQL analysis, multi-stage human approval chains, role-based access control, tamper-evident audit log, and real-time notifications.

**Editions:** Community (Apache 2.0, JWT auth) and Enterprise (commercial, adds SAML/SSO and multi-org). All code lives in a single repository; Enterprise features are conditionally activated via `@ConditionalOnProperty`.

**Full design docs:** `docs/` — read them before implementing any feature. The authoritative references are:
- `docs/02-architecture.md` — system architecture and request flow
- `docs/03-data-model.md` — all entities, columns, enums, and indexes
- `docs/04-api-spec.md` — complete REST API and WebSocket spec
- `docs/05-backend.md` — proxy engine, workflow state machine, AI analyzer
- `docs/06-frontend.md` — directory structure, routing, state management
- `docs/07-security.md` — auth, authorization matrix, encryption rules
- `docs/11-development.md` — coding standards, testing strategy, Git workflow

---

## Repository Layout

```
accessflow/
├── backend/          # Spring Boot application (single Maven module)
├── frontend/         # React / Vite / TypeScript SPA (to be created)
├── docs/             # Design documentation
├── docker-compose.yml
└── .github/workflows/
```

---

## Backend

### Runtime & Framework Versions

| Item | Version |
|------|---------|
| Java | **25** (not 21 — the pom.xml uses `<java.version>25</java.version>`) |
| Spring Boot | **4.0.6** (not 3.x — the actual parent POM) |
| Spring Modulith | 2.0.6 |
| PostgreSQL driver | latest compatible with Boot 4 |

### Module Structure — Spring Modulith (FLAT)

The project was refactored to a **single Maven module** with **Spring Modulith** enforcing logical module boundaries via package conventions. Do **not** split into Maven sub-modules; do **not** recreate the multi-module layout described in `docs/05-backend.md` (that doc is the original design, not the current implementation).

**Root package:** `com.partqam.accessflow`

Logical Modulith modules map to sub-packages:

```
com.partqam.accessflow/
├── proxy/          # SQL proxy engine, JDBC connection management
├── workflow/       # Review state machine, approval chains
├── ai/             # AI analyzer strategy + adapters (OpenAI, Anthropic, Ollama)
├── security/       # JWT config, Spring Security filters, SAML (Enterprise)
├── notifications/  # Email, Slack, Webhook dispatchers
├── audit/          # Audit log service, ApplicationEvent consumers
├── admin/          # Admin REST controllers and services
├── api/            # REST controllers, DTOs, request/response types
└── core/           # Domain entities, JPA repositories, enums, service interfaces
```

**Cross-module communication rules (Spring Modulith):**
- Modules expose a public API via types in their root package (e.g. `com.partqam.accessflow.workflow`).
- Internal implementation goes in sub-packages (e.g. `...workflow.internal`).
- Modules communicate via **Spring ApplicationEvents** — never import an internal type from another module.
- The `ApplicationModulesTest` must pass after every change (`./mvnw test -Dtest=ApplicationModulesTest`).

### Naming & Code Conventions

- **No `@Autowired` field injection** — constructor injection only. Use `@RequiredArgsConstructor` (Lombok) or explicit constructors.
- **No `@Transactional` on controllers** — only on service methods.
- **No JPA entities returned from controllers** — always map to a DTO. DTOs live in `api/` or the relevant module's public package.
- All API response envelopes follow the error format in `docs/04-api-spec.md`:
  ```json
  { "error": "ERROR_CODE", "message": "Human-readable text", "timestamp": "ISO-8601" }
  ```
- Service interfaces defined in `core/`; implementations in their owning module.
- Prefer `record` types for DTOs and value objects (Java 25 supports them fully).
- Use **virtual threads** (Project Loom) for blocking I/O — the proxy engine and AI calls must not block platform threads. Enable with `spring.threads.virtual.enabled=true`.

### Configuration

`application.yml` must not contain secrets — use `${ENV_VAR}` placeholders. Required env vars:

| Variable | Purpose |
|----------|---------|
| `DB_URL` | JDBC URL for AccessFlow PostgreSQL |
| `DB_USER` | PostgreSQL username |
| `DB_PASSWORD` | PostgreSQL password |
| `ENCRYPTION_KEY` | 32-byte hex — AES-256-GCM for datasource credential encryption |
| `JWT_PRIVATE_KEY` | RSA-2048 PEM — JWT RS256 signing key |
| `AI_PROVIDER` | `anthropic` \| `openai` \| `ollama` (default: `anthropic`) |
| `AI_API_KEY` | API key for OpenAI or Anthropic |
| `REDIS_URL` | Redis for token revocation (default: `redis://localhost:6379`) |
| `ACCESSFLOW_EDITION` | `community` \| `enterprise` (default: `community`) |
| `CORS_ALLOWED_ORIGIN` | Frontend origin for CORS |

`application.yml` structure to follow (see `docs/05-backend.md` for the full template):
```yaml
spring:
  jpa:
    open-in-view: false          # always off
    hibernate.ddl-auto: validate # never create/update/create-drop in any real env
```

### Database Migrations (Flyway)

- All schema changes via Flyway only. Location: `src/main/resources/db/migration/`.
- File naming: `V{n}__{Snake_case_description}.sql` (double underscore).
- **Never modify an existing migration file.**
- Every new column must either have a DEFAULT value or be nullable (zero-downtime deploys).
- Versioning sequence from the data model doc:

  ```
  V1__create_organizations.sql
  V2__create_users.sql
  V3__create_datasources.sql
  V4__create_permissions.sql
  V5__create_review_plans.sql
  V6__create_query_requests.sql
  V7__create_ai_analyses.sql
  V8__create_review_decisions.sql
  V9__create_audit_log.sql
  V10__create_notification_channels.sql
  V11__create_indexes.sql
  V12__create_saml_configurations.sql
  ```

### Data Model Rules

All tables: `snake_case` names, `UUID` PKs, `TIMESTAMPTZ` timestamps. See `docs/03-data-model.md` for every column definition. Critical invariants:

- `datasource.password_encrypted` — **always `@JsonIgnore`**; never serialized in any response.
- `notification_channels.config` — sensitive sub-fields (SMTP password, webhook secret) are AES-256-GCM encrypted before persistence; never returned in GET responses.
- `audit_log` — INSERT-only from the application; the DB user has no UPDATE/DELETE privilege on this table. Never delete or update audit records.
- `query_requests.status` transitions must follow the state machine exactly (see below).

### Query Request State Machine

```
PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTED
                           ↘ REJECTED
           ↘ PENDING_REVIEW (if AI not required)
PENDING_REVIEW → CANCELLED (submitter only)
APPROVED → FAILED (execution error)
```

Illegal transitions must throw a domain exception, not silently succeed.

### Security Rules — Non-Negotiable

1. **No string-concatenation SQL** — `PreparedStatement` exclusively in the proxy engine.
2. **JSqlParser validation first** — parse every submitted SQL before any execution path. Reject unparseable SQL with HTTP 422.
3. **Schema allow-list at AST level** — walk the parsed AST to validate referenced tables, not string matching.
4. **`password_encrypted` never in heap beyond pool init** — decrypt credentials once inside `QueryProxyService`, pass to HikariCP, do not store the plaintext.
5. **A user can never approve their own query**, regardless of role. Enforce this in the workflow service, not just the UI.
6. **`@JsonIgnore` on all encrypted/sensitive fields** — entity-level, not just controller-level.
7. **CORS** — only the configured `CORS_ALLOWED_ORIGIN` is allowed. No wildcard in production.
8. **Refresh token cookies** — `HttpOnly; Secure; SameSite=Strict`.

### Enterprise Conditional Beans

```java
// Community default
@Service
@ConditionalOnProperty(name = "accessflow.edition", havingValue = "community", matchIfMissing = true)
public class LocalAuthenticationService implements AuthenticationService { ... }

// Enterprise override
@Service
@ConditionalOnProperty(name = "accessflow.edition", havingValue = "enterprise")
public class SamlAuthenticationService implements AuthenticationService { ... }
```

Never use `if (edition.equals("enterprise"))` guards inside a shared bean — use conditional beans.

### Testing (Backend)

| Layer | Tools |
|-------|-------|
| Unit | JUnit 5 + Mockito — service logic, state machine, AI prompt building |
| Integration | Spring Boot Test + Testcontainers (PostgreSQL 15 / MySQL 8) |
| API | REST Assured + `@SpringBootTest(webEnvironment = RANDOM_PORT)` |
| Security | Dedicated tests for JWT forgery, permission boundary violations |

- Coverage target: **≥ 80%** line coverage on proxy, workflow, and core modules.
- **Use Testcontainers** for all integration tests — never mock the database.
- `ApplicationModulesTest` must always pass. Run it after any refactor.

```java
@SpringBootTest
@Testcontainers
class SomeIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }
}
```

### Build Commands

```bash
cd backend
./mvnw verify                    # full build + tests
./mvnw verify -Pcoverage         # with JaCoCo coverage report
./mvnw checkstyle:check          # style enforcement
./mvnw spotless:apply            # auto-format
./mvnw spring-boot:run           # run locally (requires .env or env vars set)
./mvnw test -Dtest=ApplicationModulesTest  # module boundary check
```

---

## Frontend

The frontend does not exist yet in the repository. Create it at `frontend/` using:

```bash
npm create vite@latest frontend -- --template react-ts
```

### Tech Stack (required — do not substitute)

| Technology | Version | Role |
|-----------|---------|------|
| React | 18 | UI framework |
| Vite | 5 | Build tool |
| TypeScript | 5 | Language (`strict: true`) |
| Ant Design | 5.x | Component library |
| CodeMirror | 6 | SQL editor (`@codemirror/lang-sql`) |
| Zustand | 4 | Auth + UI state |
| TanStack Query | 5 | Server state (replaces `useEffect` for data fetching) |
| Axios | 1.x | HTTP client |
| React Router | 6 | Routing |
| sql-formatter | 15 | SQL formatting |
| Vitest | latest | Unit/component tests |
| React Testing Library | latest | Component tests |
| Playwright | latest | E2E tests |

### Directory Structure

Follow `docs/06-frontend.md` exactly. Key conventions:

```
src/
├── api/          # One Axios module per domain (queries.ts, datasources.ts, etc.)
├── components/   # Shared UI — common/, editor/, review/, datasources/, audit/
├── hooks/        # Custom hooks — useQueryRequest, useWebSocket, useCurrentUser, etc.
├── layouts/      # AppLayout, AdminLayout, AuthLayout
├── pages/        # One directory per route group (auth, editor, queries, reviews, admin)
├── store/        # Zustand stores (authStore, notificationStore, preferencesStore)
├── types/        # TypeScript types — api.ts, query.ts, datasource.ts, user.ts
└── utils/        # Pure functions (riskColors, statusColors, dateFormat, sqlFormat)
```

### TypeScript Rules

- `strict: true` in `tsconfig.json` — no implicit `any`, no `any` casts unless unavoidable.
- All API response and request shapes must be defined in `src/types/api.ts`.
- Never use `as any` to silence a type error — fix the type.
- Prefer `interface` for object shapes that may be extended; `type` for unions and mapped types.

### State Management Rules

- **TanStack Query** for all server data — no `useEffect` + `useState` for fetching.
- **Zustand** only for client-side state: auth (`authStore`), in-app notifications (`notificationStore`), editor preferences (`preferencesStore`).
- Never put server data (query lists, datasources) into Zustand.
- WebSocket events invalidate TanStack Query cache via `queryClient.invalidateQueries`.

### Auth Flow

- JWT access token (15 min TTL) stored in memory (Zustand), **not** `localStorage`.
- Refresh token is an `HttpOnly` cookie — Axios sends it automatically on `POST /auth/refresh`.
- Axios request interceptor: on 401, call `POST /auth/refresh`, retry original request. On refresh failure, call `logout()` and redirect to `/login`.
- All routes except `/login` and `/auth/saml/callback` are wrapped in `<AuthGuard>`.
- Admin routes additionally check `user.role === 'ADMIN'`.

### Environment Variables

```env
VITE_API_BASE_URL=http://localhost:8080
VITE_WS_URL=ws://localhost:8080/ws
VITE_APP_EDITION=community
```

Prefix all Vite env vars with `VITE_`. Never access `process.env` in frontend code — use `import.meta.env`.

### Routing

```
/login                         → LoginPage
/auth/saml/callback            → SamlCallbackPage (Enterprise)
/editor                        → QueryEditorPage
/queries                       → QueryListPage
/queries/:id                   → QueryDetailPage
/reviews                       → ReviewQueuePage
/datasources                   → DatasourceListPage
/datasources/:id/settings      → DatasourceSettingsPage
/admin/users                   → UsersPage
/admin/audit-log               → AuditLogPage
/admin/ai-config               → AIConfigPage
/admin/notifications           → NotificationsPage
/admin/saml                    → SamlConfigPage (Enterprise — render only if features.saml_enabled)
```

### SQL Editor Component

Built with CodeMirror 6. Required features:
- Dialect-aware syntax highlighting (PostgreSQL vs MySQL based on selected datasource).
- Schema autocomplete — pass introspected schema from `/datasources/{id}/schema` as the `schema` option to `sql()`.
- Debounced AI analysis (800 ms) calling `POST /queries/analyze`; render issues as gutter markers + AiHintPanel.
- `Ctrl+Shift+F` → format SQL using `sql-formatter`.
- Read-only mode (`EditorState.readOnly`) for detail/history views.

### Testing (Frontend)

```bash
cd frontend
npm run test           # Vitest unit + component tests
npm run test:e2e       # Playwright (requires running backend)
npm run test:coverage  # Coverage report
npm run lint           # ESLint
npm run build          # Vite production build
```

---

## API Contract

Base path: `/api/v1`. All requests need `Authorization: Bearer <JWT>` except `/auth/*`.

The full API spec is in `docs/04-api-spec.md`. Key points:

- Use HTTP 202 (Accepted) for async operations (query submission).
- Use HTTP 204 for successful DELETE.
- Use HTTP 422 for SQL parse errors.
- Pagination params: `page` (0-indexed) and `size` (default 20, max 100).
- WebSocket at `/ws?token=<JWT>` — JWT in query param on connect.

**Never add endpoints not in the spec without noting the addition.** If a feature requires a new endpoint, document it in `docs/04-api-spec.md` first.

---

## AI Analyzer Integration

The `AiAnalyzerStrategy` interface must be implemented by all three adapters:

```java
public interface AiAnalyzerStrategy {
    AiAnalysisResult analyze(String sql, DbType dbType);
}
```

- Active adapter is selected by `accessflow.ai.provider` config.
- The system prompt template is in `docs/05-backend.md` — use it verbatim; do not invent a different prompt.
- AI calls are asynchronous — publish a `QuerySubmittedEvent` and handle in the strategy asynchronously using virtual threads.
- The response must be parsed strictly as JSON matching the `AiAnalysisResult` schema. If the AI returns non-JSON or an unexpected schema, log and mark the analysis as failed; do not propagate the exception to the query request.
- For Anthropic: use `claude-sonnet-4-20250514` as the default model.
- For OpenAI: use `gpt-4o` as the default model.

---

## Notification System

- All notification delivery is **async and non-blocking** — failures must not affect query workflow state.
- `NotificationDispatcher` listens to Spring `ApplicationEvent` objects; never called synchronously from the workflow engine.
- Email bodies use Thymeleaf templates in `resources/templates/email/` — one template per event type.
- Slack messages use Block Kit format (see `docs/08-notifications.md`).
- Webhooks must include `X-AccessFlow-Signature: sha256=<HMAC-SHA256>` on every delivery.
- Webhook retry policy: 30 s → 2 min → 10 min (3 attempts total). Mark failed and write to audit log after exhaustion.
- Sensitive channel config fields (`smtp_password`, `webhook_secret`) must be AES-256 encrypted before persistence; never returned in API responses.

---

## Git Workflow

```
main           → production-ready, tagged releases
develop        → integration branch
feature/AF-{n}-description  → from develop
fix/AF-{n}-description      → from develop
hotfix/AF-{n}-description   → from main, merge to both main and develop
```

**PR requirements:** passing CI (build + tests + lint), ≥ 1 approval, Checkstyle + Spotless green, PR description references the issue number.

Branch names must match the pattern above. Commit messages should be imperative mood, ≤ 72 chars subject line.

---

## CI / CD

`.github/workflows/ci.yml` runs on every PR:
- Backend: `./mvnw verify -Pcoverage` (Java 25, Ubuntu)
- Frontend: `npm ci && npm run test:coverage && npm run build` (Node 20, Ubuntu)

`.github/workflows/release.yml` runs on `v*` tags:
- Builds JAR, Docker images for backend and frontend, and packages the Helm chart.

Docker images:
- Backend: `eclipse-temurin:25-jre-alpine` base image (use the correct Java version).
- Frontend: multi-stage — `node:20-alpine` build, then `nginx:alpine` serve.

---

## What to Avoid

- Do not use `@Autowired` field injection.
- Do not return JPA entities from REST controllers — always use DTOs.
- Do not put `@Transactional` on controllers.
- Do not modify existing Flyway migration files.
- Do not use `ddl-auto: create` or `update` outside Testcontainers tests.
- Do not store the decrypted DB password anywhere beyond the HikariCP pool init.
- Do not expose `password_encrypted` in any API response.
- Do not use `useEffect` for data fetching in the frontend — use TanStack Query.
- Do not store JWT access tokens in `localStorage` or `sessionStorage`.
- Do not approve a user's own query — enforce in service logic.
- Do not hard-code secrets — use environment variables.
- Do not write multi-paragraph comments or doc comments on obvious methods.
- Do not add features beyond what is requested; do not design for hypothetical future requirements.
