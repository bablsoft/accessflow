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

---

### Architecture

#### Spring Modulith Structure

The project is a **single Maven module** with **Spring Modulith** enforcing logical module boundaries via package conventions. Do **not** split into Maven sub-modules.

**Root package:** `com.partqam.accessflow`

Each business module lives under its own top-level sub-package. Modules communicate through **Spring application events** and **exposed `api` packages** — never by reaching into another module's `internal` sub-packages.

```
com.partqam.accessflow/
├── AccessFlowApplication.java
├── core/           # Domain entities, JPA repositories, enums, service interfaces
│   ├── api/        # Public — enums and interfaces accessible to other modules
│   └── internal/
│       └── persistence/
│           ├── entity/    # JPA entity classes (suffix: *Entity)
│           └── repo/      # Spring Data JPA repository interfaces
├── proxy/          # SQL proxy engine, JDBC connection management
│   ├── api/
│   └── internal/
├── workflow/       # Review state machine, approval chains
│   ├── api/
│   └── internal/
├── ai/             # AI analyzer strategy + adapters (OpenAI, Anthropic, Ollama)
│   ├── api/
│   └── internal/
├── security/       # JWT config, Spring Security filters, SAML (Enterprise)
│   ├── api/
│   └── internal/
├── notifications/  # Email, Slack, Webhook dispatchers
│   ├── api/
│   └── internal/
└── audit/          # Audit log service, ApplicationEvent consumers
    ├── api/
    └── internal/
```

#### Key Rules

- **Module boundaries are enforced.** `ApplicationModulesTest` must exist and pass in CI. Run it after every change: `./mvnw test -Dtest=ApplicationModulesTest`.
- **No cyclic dependencies between modules.** If two modules need each other, extract a shared interface or communicate through events.
- **`internal` sub-packages are module-private.** Only types in `api` (or the module root package) are accessible to other modules.
- **Spring configuration placement:** all `@Configuration` classes belong in the owning module's `internal` package.
- **Cross-module communication** uses `ApplicationEventPublisher` (fire-and-forget) or `@ApplicationModuleListener` (transactional event listeners). Direct injection of another module's internal beans is forbidden.

#### Layering Within a Module

| Layer | Package | Responsibility |
|-------|---------|----------------|
| API | `api/` | Service interfaces, DTOs, and enums exposed to other modules |
| Internal – Persistence – Entity | `internal/persistence/entity/` | JPA entity classes; every class **must** carry the `Entity` suffix (e.g. `UserEntity`) |
| Internal – Persistence – Repo | `internal/persistence/repo/` | Spring Data JPA repository interfaces |
| Internal – Service | `internal/` (root) | Business logic, state machines, orchestration |
| Internal – Web | `internal/web/` | REST controllers, request/response models, web mappers |
| Events | `events/` | Published and consumed domain events |

- Controllers delegate to services; they never contain business logic.
- Controllers expose dedicated request/response models defined in `internal/web/`; they must not return `api/` DTOs or entities directly.
- `@RestController` classes live under `<module>.internal.web`, not the module root.
- JPA entity classes live in `internal/persistence/entity/` and **must** carry the `Entity` suffix (e.g. `UserEntity`, `QueryRequestEntity`). Never place entities in the persistence root package.
- Spring Data JPA repository interfaces live in `internal/persistence/repo/`. Never place repositories in the persistence root package.
- Repositories are Spring Data JPA interfaces — no custom JDBC unless justified.
- Mappers (MapStruct preferred) convert between entities and DTOs. No entity ever leaks into a controller response.

---

### Code Standards

#### Java 25

- Use **records** for DTOs, events, and value objects.
- Use **sealed interfaces/classes** where a closed type hierarchy is appropriate.
- Use **pattern matching** (`switch` expressions, `instanceof` patterns) over manual type checks.
- Use **text blocks** for multi-line strings (SQL, JSON templates).
- Prefer `var` for local variables where the type is obvious from the right-hand side.
- Use **virtual threads** (`spring.threads.virtual.enabled=true`) — never create platform threads manually. The proxy engine and AI calls must not block platform threads.
- Keep method cognitive complexity within Sonar thresholds.

#### Naming Conventions

- Classes: `PascalCase`.
- Methods / variables: `camelCase`.
- Constants: `UPPER_SNAKE_CASE`.
- Packages: all lowercase, no underscores.
- REST endpoints: `kebab-case` paths — `/api/v1/query-requests`.
- Database tables / columns: `snake_case`.
- Test classes: `<ClassUnderTest>Test` (unit), `<ClassUnderTest>IntegrationTest` (integration).

#### REST API Design

- All endpoints versioned: `/api/v1/...`.
- Use proper HTTP methods and status codes (201 for creation, 202 for async acceptance, 204 for deletion, 422 for SQL parse errors).
- Return `ProblemDetail` (RFC 9457) for all error responses via `@ControllerAdvice`.
- Use `ResponseEntity` only when setting custom headers or non-default status codes; return concrete types otherwise.
- Every controller method MUST have Springdoc `@Operation` and `@ApiResponse` annotations.
- Request DTOs use Bean Validation annotations (`@NotNull`, `@Size`, `@Valid`, etc.).
- All API response envelopes follow the error format in `docs/04-api-spec.md`.

#### Database & JPA

- All entities use **UUID** primary keys.
- Entity field access strategy: `@Access(AccessType.FIELD)`.
- Always specify `@Column(nullable = ...)` and `@Table(name = ...)` explicitly.
- Use **Flyway** for schema migrations. Migration files: `V{number}__{description}.sql`. **Never modify an existing migration file.**
- `spring.jpa.hibernate.ddl-auto` must be `validate` in all real environments.
- Avoid `FetchType.EAGER` — always `LAZY`; fetch via `@EntityGraph` or join-fetch when needed.
- Use `@Version` for optimistic locking on entities that can be concurrently modified.
- All tables: `snake_case` names, `UUID` PKs, `TIMESTAMPTZ` timestamps.
- PostgreSQL enum types: `snake_case`, **no** `_enum` suffix (e.g. `db_type`, `query_status`, `risk_level`, not `db_type_enum`). The `columnDefinition` value in the `@Column` annotation must match the SQL type name exactly.

#### Dependency Injection

- **Constructor injection exclusively.** No `@Autowired` on fields. All dependencies must be `final`.
- Use `@RequiredArgsConstructor` (Lombok) or explicit constructors.

#### JSON Mapping

- Always import `tools.jackson.databind.ObjectMapper` (not `com.fasterxml.jackson.databind.ObjectMapper`).
- For tree parsing, use `tools.jackson.databind.JsonNode`.
- When touching existing code that still uses `com.fasterxml.jackson.databind.*`, migrate imports in the same change.

#### Logging

- Use SLF4J (`LoggerFactory.getLogger(...)`) — never `System.out.println`.
- `ERROR` for failures needing attention, `WARN` for recoverable issues, `INFO` for business events, `DEBUG`/`TRACE` for development.

#### Exception Handling

- Define module-specific exception hierarchies extending a common base.
- Never catch `Exception` or `Throwable` broadly — catch specific types.
- A global `@ControllerAdvice` maps exceptions to `ProblemDetail` responses.
- Never expose stack traces or internal details in API error responses.

---

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

---

### Database Migrations (Flyway)

- All schema changes via Flyway only. Location: `src/main/resources/db/migration/`.
- File naming: `V{n}__{Snake_case_description}.sql` (double underscore).
- **Never modify an existing migration file.**
- Every new column must either have a DEFAULT value or be nullable (zero-downtime deploys).
- Versioning sequence:

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

---

### Domain Invariants

- `datasource.password_encrypted` — **always `@JsonIgnore`**; never serialized in any response.
- `notification_channels.config` — sensitive sub-fields (SMTP password, webhook secret) AES-256-GCM encrypted before persistence; never returned in GET responses.
- `audit_log` — INSERT-only; the DB user has no UPDATE/DELETE privilege on this table.
- `query_requests.status` transitions must follow the state machine exactly:

  ```
  PENDING_AI → PENDING_REVIEW → APPROVED → EXECUTED
                             ↘ REJECTED
             ↘ PENDING_REVIEW (if AI not required)
  PENDING_REVIEW → CANCELLED (submitter only)
  APPROVED → FAILED (execution error)
  ```

  Illegal transitions must throw a domain exception, not silently succeed.

---

### Security Rules — Non-Negotiable

1. **No string-concatenation SQL** — `PreparedStatement` exclusively in the proxy engine.
2. **JSqlParser validation first** — parse every submitted SQL before any execution path. Reject unparseable SQL with HTTP 422.
3. **Schema allow-list at AST level** — walk the parsed AST to validate referenced tables, not string matching.
4. **`password_encrypted` never in heap beyond pool init** — decrypt credentials once inside `QueryProxyService`, pass to HikariCP, do not store the plaintext.
5. **A user can never approve their own query**, regardless of role. Enforce in the workflow service, not just the UI.
6. **`@JsonIgnore` on all encrypted/sensitive fields** — entity-level, not just controller-level.
7. **CORS** — only the configured `CORS_ALLOWED_ORIGIN` is allowed. No wildcard in production.
8. **Refresh token cookies** — `HttpOnly; Secure; SameSite=Strict`.

---

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

---

### Testing (Backend)

| Type | Suffix | Framework | Scope |
|------|--------|-----------|-------|
| Unit | `*Test.java` | JUnit 5 + Mockito | Single class, no Spring context |
| Integration | `*IntegrationTest.java` | `@SpringBootTest` + Testcontainers | Full context, real DB |
| Module | `*ModuleTest.java` | `@ApplicationModuleTest` | Single module isolation |
| Architecture | `ApplicationModulesTest.java` | Spring Modulith verify API | Module boundary enforcement |

**Coverage target: ≥ 90% line coverage** (enforced via JaCoCo — build fails below threshold).

**Testcontainers setup** — use a shared `@TestConfiguration`:

```java
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:18-alpine");
    }
}
```

Use `@Import(TestcontainersConfig.class)` in integration tests. **Never use H2** as a substitute for PostgreSQL.

**Architecture verification test** must exist at the root:

```java
class ApplicationModulesTest {
    @Test
    void verifyModularStructure() {
        ApplicationModules.of(AccessFlowApplication.class).verify();
    }
}
```

---

### Maven Build Configuration

| Plugin | Purpose |
|--------|---------|
| `spring-boot-maven-plugin` | Packaging & running |
| `maven-surefire-plugin` | Unit tests (`*Test.java`) |
| `maven-failsafe-plugin` | Integration tests (`*IntegrationTest.java`) |
| `jacoco-maven-plugin` | Coverage enforcement (90% minimum, build fails below) |
| `maven-compiler-plugin` | Java 25, enable preview features if used |

### Dependency Management

When adding a new dependency to `backend/pom.xml` (or `frontend/package.json`), always pin to the **latest stable version** available at the time of the change. Verify on Maven Central / npm before committing — do not blindly trust versions referenced in the design docs under `docs/`, which may have drifted. If the docs cite an older pin for the same library, update the doc in the same change so the codebase and docs stay consistent.

### Build Commands

```bash
cd backend
./mvnw verify                    # full build + tests
./mvnw verify -Pcoverage         # with JaCoCo coverage report
./mvnw spring-boot:run           # run locally (requires env vars set)
./mvnw test -Dtest=ApplicationModulesTest  # module boundary check
```

---

### Event-Driven Patterns

```java
// Publishing
applicationEventPublisher.publishEvent(new QuerySubmittedEvent(request.id()));

// Consuming
@ApplicationModuleListener
void onQuerySubmitted(QuerySubmittedEvent event) { ... }
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
    AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext);
}
```

`schemaContext` may be `null` or empty when introspection is unavailable; the prompt template
substitutes `(no schema introspection available)` in that case.

- Adapters route their HTTP calls through **Spring AI 2.0** (`spring-ai-bom:2.0.0-M5`) — inject the auto-configured `ChatModel` (or `ChatClient`) rather than hand-rolling a `RestClient` per provider.
- Active adapter is selected by `accessflow.ai.provider` config (toggles the `@ConditionalOnProperty` strategy beans).
- Provider settings live under Spring AI's namespace: `spring.ai.anthropic.api-key`, `spring.ai.anthropic.chat.options.model`, etc. Don't duplicate them under `accessflow.ai.*`.
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
- Webhook retry policy: 1 initial attempt + up to 3 scheduled retries at +30 s, +2 min, +10 min (4 total attempts). Retry delays are configurable via `accessflow.notifications.retry.{first,second,third}`. On exhaustion the dispatcher logs `ERROR`; audit-log integration is deferred until the audit module exists.
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
