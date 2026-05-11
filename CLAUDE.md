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

- Controllers delegate to services; they never contain business logic. "Business logic" here covers anything beyond parameter binding, calling a service, and mapping the result onto the HTTP envelope — including but not limited to: CSV / Excel / PDF / report assembly, paginated slicing, value formatting (timestamp stamping, filename construction), branching on domain state (e.g. status guards, ownership checks), event publishing, encryption / hashing, retry loops, JSON tree rewriting, or stateful caching. All of that lives in a `<module>.api` service interface with a `Default*` implementation under `<module>.internal/`. When you find yourself wanting a `StringWriter`, a per-row `Consumer<T>`, a `DateTimeFormatter`, or a `for` loop over domain entities inside a controller method, that is the signal to introduce or extend a service.
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
- **Validation parity rule:** Every Bean Validation constraint on a request DTO (`@NotBlank`, `@Email`, `@Size`, `@Pattern`, `@NotNull`, etc.) must have a matching client-side rule in the frontend form that submits to that endpoint. When you add, change, or remove a constraint on either side, update the other side in the same commit.
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

#### Internationalisation (i18n)

- **Never hardcode user-facing strings in Java source.** All exception `detail` messages and validation messages must live in `src/main/resources/i18n/messages.properties`. Developer-facing log messages (SLF4J calls) may remain in code.
- Bean Validation `message` attributes must use `{key}` syntax referencing a key in `messages.properties` — e.g. `@NotBlank(message = "{validation.email.required}")`. Never use inline English text as a `message` attribute value.
- Exception handlers must resolve `ProblemDetail.detail` via `messageSource.getMessage(key, args, LocaleContextHolder.getLocale())`. Never pass `ex.getMessage()` from a constructor-built message as the detail string.
- Service classes that throw exceptions with varying messages per call site must inject `MessageSource` and resolve the message at the `throw` site using `LocaleContextHolder.getLocale()`.
- `SecurityExceptionHandler` (writes directly to `HttpServletResponse`) must use `request.getLocale()` — it cannot use `LocaleContextHolder`.
- Message key naming convention: `error.<snake_case>` for exception messages; `validation.<field>.<rule>` for Bean Validation messages. Add the key to `messages.properties` in the same commit that adds the exception or constraint.
- Adding a new language requires only a new `messages_<locale>.properties` file — no code changes.

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
| `REDIS_URL` | Redis for JWT token revocation **and** ShedLock distributed scheduler locks (default: `redis://localhost:6379`) |
| `ACCESSFLOW_WORKFLOW_TIMEOUT_POLL_INTERVAL` | ISO-8601 duration. Cadence at which `QueryTimeoutJob` scans for `PENDING_REVIEW` queries past their plan's `approval_timeout_hours` (default: `PT5M`). |
| `ACCESSFLOW_EDITION` | `community` \| `enterprise` (default: `community`) |
| `CORS_ALLOWED_ORIGIN` | Frontend origin for CORS |
| `ACCESSFLOW_DRIVER_CACHE` | Filesystem path for cached customer-DB JDBC driver JARs (default: `${user.home}/.accessflow/drivers`). Set to a system path like `/var/lib/accessflow/drivers` and mount as a persistent volume in production. |
| `ACCESSFLOW_DRIVERS_REPOSITORY_URL` | Maven repository base URL for on-demand driver downloads (default: `https://repo1.maven.org/maven2`). Override for internal Nexus / Artifactory mirrors. |
| `ACCESSFLOW_DRIVERS_OFFLINE` | Boolean. When `true`, disables network resolution and serves only from the cache. Required for air-gapped installs. |
| `ACCESSFLOW_TRACING_SAMPLING_PROBABILITY` | Micrometer Tracing sampling probability (default `1.0`). Lower this in high-traffic deployments to reduce export volume; MDC trace ids and `ProblemDetail.traceId` are populated regardless. |

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
                             ↘ REJECTED   (manual reviewer rejection)
                             ↘ TIMED_OUT  (approval-timeout auto-reject by QueryTimeoutJob)
             ↘ PENDING_REVIEW (if AI not required)
  PENDING_REVIEW → CANCELLED (submitter only)
  APPROVED → FAILED (execution error)
  ```

  Illegal transitions must throw a domain exception, not silently succeed.

---

### Scheduled Jobs (clustered-safe)

- `@EnableScheduling` is enabled in `workflow/internal/config/WorkflowConfiguration`. Place new `@Configuration` toggles in the owning module's `internal/config/` package.
- **Every `@Scheduled` method MUST also carry `@SchedulerLock`** with a unique short camelCase `name`, plus `lockAtMostFor` and `lockAtLeastFor`. The Redis-backed `LockProvider` (`workflow/internal/config/RedisLockProviderConfiguration`) reuses the existing `RedisConnectionFactory`. Without the annotation, multi-replica deployments would run the job once per replica per tick.
- Job classes live under `<module>/internal/scheduled/`. They MUST take long-lived dependencies via constructor injection (`@RequiredArgsConstructor`) and MUST swallow per-row `RuntimeException`s with `log.error(...)` so one bad row does not abort the batch.
- Cadence is configured via a property under the owning module's namespace (e.g. `accessflow.workflow.timeout-poll-interval`). Use `@Scheduled(fixedDelayString = "${...:PT5M}")` with an ISO-8601 `Duration` default — never a hard-coded number of seconds.
- Document each job in [docs/05-backend.md → "Scheduled jobs and clustering"](docs/05-backend.md#scheduled-jobs-and-clustering) (job name, lock name, cadence property, default).
- Long-running mutations called from a job MUST go through a public `core.api` service interface — workflow may not depend on `core.internal`.

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
9. **WebSocket handshake auth** — `/ws` is exempt from `JwtAuthenticationFilter`; the upgrade is authenticated by `realtime/internal/ws/JwtHandshakeInterceptor`, which calls the public `AccessTokenAuthenticator` (`security/api/`) on the `?token=<JWT>` query param. Same RSA key, same expiry rules — never a separate WS token. Browsers cannot set custom headers on a WS upgrade, which is why this path exists.

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

**Coverage parity rule — every concrete class ships with its own test class.** When you add a `Default*` implementation of an `*Service` interface, a `*Specifications` helper, a JPA entity / repository wrapper, a request/response DTO with mapping logic, or a record with non-trivial constructor validation, you must ship a dedicated test for it in the same change. **Do not assume coverage will arrive from upstream callers.** Controller integration tests almost always `@MockitoBean` the service interface (so the implementation is never executed); other services typically mock their collaborators too. The JaCoCo gate is a backstop, not a substitute — by the time it fires you may already have an under-tested class merged. Concretely:

- New `Default*Service` → `Default*ServiceTest.java` with one `@Test` per public method, covering happy path, every documented exception, and every distinct branch (status guard, null-check, role-check). Mockito-driven, no Spring context.
- New JPA `*Specifications` helper → unit test that mocks `Root`, `CriteriaQuery`, `CriteriaBuilder` and verifies each filter field independently AND the no-filter path (see `AuditLogSpecificationsTest` / `QueryRequestSpecificationsTest`).
- New record/DTO with a static `from(...)` mapper or non-default constructor (validation, normalization) → a focused test of the mapper covering the null-input branch and any conditional logic.
- Adding a new method to an existing service → extend the existing `*Test.java` in the same change, not in a follow-up. The PR author sets the line/branch coverage of the touched class; the JaCoCo gate just refuses to merge below 90/80.

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

### Tech Stack (required libraries — version: always latest stable)

For all frontend dependencies, pin to the **latest stable** version available on npm at the time of `npm install`. Re-verify with `npm view <pkg> version` before adding or upgrading. If a newer major has shipped since the last check, prefer it unless a specific incompatibility is documented in the same change. Do not substitute the libraries themselves — but always take the newest stable major of each.

| Technology | Snapshot (latest stable as of 2026-05-06) | Role |
|-----------|-------------------------------------------|------|
| React + ReactDOM | 19.x | UI framework |
| Vite + @vitejs/plugin-react | 8.x | Build tool |
| TypeScript | 6.x | Language (`strict: true`) |
| Ant Design | 6.x | Component library |
| CodeMirror + @codemirror/lang-sql | 6.x | SQL editor |
| Zustand | 5.x | Auth + UI state |
| TanStack Query | 5.x | Server state (replaces `useEffect` for data fetching) |
| Axios | 1.x | HTTP client |
| React Router | 7.x (library mode) | Routing |
| sql-formatter | 15.x | SQL formatting |
| Vitest | latest stable | Unit/component tests |
| React Testing Library | latest stable | Component tests |
| Playwright | latest stable | E2E tests |

When you bump a major in `frontend/package.json`, update this snapshot row in the same change so the doc stays consistent with the lockfile.

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
npm run test:coverage  # Coverage report (enforces threshold)
npm run lint           # ESLint
npm run typecheck      # tsc -b --noEmit
npm run build          # Vite production build
```

**Coverage target: ≥ 90% line coverage** — same gate as the backend. Enforced by Vitest's `coverage.thresholds` in `vite.config.ts`; the build fails when below threshold. Branches must hit ≥ 80%, lines/functions/statements ≥ 90%.

The coverage `include` list deliberately scopes measurement to pure-logic modules (`src/utils/**`, the analyzer/schema/delay mocks) at the demo stage. As feature work lands and pages/components gain meaningful tests (tracked under [FE-09](https://github.com/partqam/accessflow/issues/80)), expand the `include` list in the same change. **Any time you add a new pure-logic module, include it in coverage measurement and ship it with tests.**

CI pipeline (`.github/workflows/frontend-ci.yml`):
- Triggers on changes to `frontend/**` on push to main and on PRs.
- Steps: `npm ci → lint → typecheck → test:coverage → build`.
- Posts a JUnit-based test summary and a coverage diff comment to the PR (`EnricoMi/publish-unit-test-result-action` + `davelosert/vitest-coverage-report-action`).

**Test layering and conventions:**
- **Unit tests** (`src/utils`, `src/mocks`, store logic): pure logic only, no React.
- **Component tests** (React Testing Library): assert behaviour from the user's perspective — query by role/label, not test IDs. No snapshot tests of large component trees.
- **E2E tests** (Playwright, when added): cover login, submit query, approve in review queue, create datasource. Backed by a real backend (compose + Testcontainers) — not by mocks.
- Mock HTTP at the network layer with **MSW** (when first needed); do not mock Axios directly.
- Tests live alongside source as `*.test.ts(x)` or in `__tests__/`. Pick one per directory and stay consistent.

### Component & File Conventions

- One component per file. Filename matches the default export (`PascalCase.tsx` for components, `camelCase.ts` for hooks/utilities).
- Components use named exports; default exports are reserved for lazy-loaded route pages.
- `components/` folders are grouped by **domain**, not by type — see `docs/06-frontend.md` for the canonical list (`common/`, `editor/`, `review/`, `datasources/`, `audit/`).
- Shared primitives (`StatusBadge`, `RiskBadge`, `CopyButton`, `PageHeader`) live in `components/common/`. Reuse them — never re-implement status/risk colours inline.
- Pages own routing concerns and data fetching; presentational components stay pure (props in, JSX out).
- Co-locate component-specific styles, helpers, and tests alongside the component (`Foo.tsx`, `Foo.test.tsx`, `useFoo.ts`).

### Accessibility (a11y)

- Use semantic HTML (`<button>`, `<nav>`, `<main>`, `<form>`) — never click handlers on `<div>`.
- All interactive elements must be keyboard-reachable; no `tabindex` > 0.
- Provide `aria-label` for icon-only buttons (e.g. `CopyButton`, audit-log row actions).
- Form inputs require visible labels via Ant Design `Form.Item label` — placeholder is not a label.
- Tables (audit log, permission matrix) need `<caption>` or `aria-label`; column headers use `scope="col"`.
- Modals/drawers must trap focus and restore it on close (Ant Design's `Modal`/`Drawer` do this — don't bypass with custom overlays).
- Honour `prefers-reduced-motion`: skip non-essential transitions when set.
- Colour is never the sole status indicator — pair colour with text or an icon (the existing `StatusBadge` pattern).

### Error Handling & Error Envelopes

- Wrap each top-level route in an error boundary; surface a recovery action ("Retry"), not a stack trace.
- Backend errors follow the RFC 9457 `ProblemDetail` envelope — see `docs/04-api-spec.md`. Render `title`/`detail`; map known `error` codes (e.g. `PERMISSION_DENIED`, `SQL_PARSE_ERROR`) to user-friendly messages in `src/utils/apiErrors.ts`.
- Never display raw axios error objects, server stack traces, or SQL strings as error messages.
- 422 (SQL parse) errors render inline in the editor as gutter markers — not as a toast.
- 401 is handled by the Axios interceptor (refresh + retry) — components must not catch it.

### TanStack Query Defaults & Conventions

- Default `staleTime: 30_000`, `gcTime: 5 * 60_000`, `refetchOnWindowFocus: false`, `retry: 1` — set on the global `QueryClient` (`src/main.tsx`). Don't change defaults per-call without a comment explaining why.
- Query keys are arrays, hierarchical, prefixed by domain — `['queries', queryId]`, `['datasources', 'list', { page, size }]`. Define key factories in `src/api/<domain>.ts`.
- Mutations that change a list invalidate the list key; mutations that change one record invalidate both `['<domain>', id]` and the list key.
- Use `useMutation` with optimistic updates for review approve/reject; roll back on error.
- Never duplicate server data into Zustand — use `useQueryClient().getQueryData(...)` if a non-React caller needs it.

### WebSocket Conventions

- A single `useWebSocket()` hook owns the connection; pages subscribe/unsubscribe to events.
- JWT is passed as the `?token=<JWT>` query param on connect — **not** an `Authorization` header (browsers don't allow custom headers on WS handshake). Reconnect when the access token rotates.
- Auto-reconnect with exponential backoff (1s, 2s, 4s, … capped at 30s); reset on successful connect.
- Map WS events → query invalidations:
  - `query.status_changed`, `query.executed` → invalidate `['queries', queryId]` and `['queries', 'list']`
  - `review.new_request`, `review.decision_made` → invalidate `['reviews', 'queue']`
  - `ai.analysis_complete` → invalidate `['queries', queryId]`
- Never trust WS payloads as authoritative — always re-fetch via REST after invalidation.

### Forms & Validation

- Use Ant Design `Form` + `Form.Item`; do not manage form state manually with `useState`.
- Mirror server-side validation rules in the form (e.g. `name` length 3–50, `host` non-empty) so users see errors before submit. The server remains the source of truth — surface its 400/422 responses field-level when `error.path` is present.
- **Validation parity rule:** Every `Form.Item` rule must mirror the corresponding backend DTO Bean Validation constraint for that field — and vice versa. For example, a backend `@Size(min=8, max=128)` requires `{ min: 8, max: 128 }` on the frontend rule; a frontend `type: 'email'` requires `@Email` on the backend DTO. When adding or changing validation on either side, update the other side in the same change.
- For runtime validation of API responses with non-trivial shapes (AI analysis, datasource schema), use `zod` (add the dep when first needed). Don't trust `as` casts.
- Submit handlers are typed (`(values: SubmitQueryRequest) => Promise<void>`); never `any`.
- Disable the submit button while pending; render a spinner inside the button, not a full-page loader.

### Internationalisation (i18n)

- **Never hardcode user-facing strings in JSX or TypeScript.** All visible text — form labels, placeholders, button labels, page titles, column headers, error messages, `aria-label` values — must come from the `t()` function provided by `react-i18next`.
- React components use `const { t } = useTranslation();`. Plain utility functions (under `src/utils/`) that produce user-visible strings use `i18n.t()` imported from `src/i18n.ts`.
- All English translations live in `src/locales/en.json`. Key convention: `<feature>.<screen>.<element>` (e.g. `auth.login.title`, `nav.editor`, `validation.email_required`). Adding a new language requires only a new `src/locales/<locale>.json` file and registering it in `src/i18n.ts`.
- When adding a new user-visible string: add the key to `src/locales/en.json` first, then reference it with `t('the.key')`. Never inline an English string directly.
- Plurals use the i18next `_one` / `_other` suffix convention, called with `t('key', { count: n })`.
- `ConfigProvider` in `src/main.tsx` must receive `locale={enUS}` so built-in Ant Design text (DatePicker, Pagination, Table, etc.) is also localised.
- `dayjs.locale('en')` must be called in `src/main.tsx` before the React tree mounts.
- The i18n bootstrap (`import './i18n'`) must be the **first import** in `src/main.tsx`.
- Type-safe keys: `src/i18n.d.ts` declares `CustomTypeOptions` so `t('nonexistent.key')` is a compile error. Do not disable or bypass this check.

### Loading, Empty, and Skeleton States

- Lists and tables render Ant Design `Skeleton` while loading — not a centred spinner.
- Empty states use a dedicated component (`<EmptyState title icon action>`) — not a bare "No data".
- Avoid layout shift: skeletons should match the dimensions of the loaded content.
- For mutations, show inline progress on the affected row (review approve/reject), not a global toast.

### Code Splitting

- Lazy-load each top-level route group (`pages/admin/*`, `pages/datasources/*`, `pages/reviews/*`) via `React.lazy` + `<Suspense>` in the router.
- The login page and `AppLayout` shell stay in the main bundle.
- Don't lazy-load shared components (`components/common/*`) — the cache cost outweighs the bundle saving.

### Frontend Security

- Never use `dangerouslySetInnerHTML`. SQL highlighting in read-only panels uses CodeMirror with a string `value`, not raw HTML.
- No `eval`, `new Function(...)`, or `setTimeout(stringArg)`.
- All outbound requests go through `src/api/client.ts` — never `fetch` directly. The centralised client enforces `withCredentials`, baseURL, and the refresh interceptor.
- Refresh-token cookie is `HttpOnly; Secure; SameSite=Strict` — frontend code never reads it. Token rotation is handled implicitly by the cookie + interceptor; treat each `/auth/refresh` 200 as authoritative.
- The backend sets a strict CSP (`default-src 'self'`) — no inline `<script>`, no remote CDN scripts, no inline-string event handlers in markup (`onClick={fn}` is fine; HTML-string handlers are not).
- Never log JWTs, session IDs, or datasource passwords (even in dev). Redact before `console.log`.
- For `<a target="_blank">` (audit detail, external links), always pair with `rel="noopener noreferrer"`.

### Theming

- Ant Design tokens drive all colour and typography — read them via `--af-*` CSS custom properties (configured in `src/utils/antdTheme.ts`). Never hardcode hex colours in components.
- Status and risk colours go through `src/utils/statusColors.ts` and `src/utils/riskColors.ts` — single source of truth, already covered by tests.
- Dark mode follows `prefers-color-scheme`; user preference (when added) lives in `preferencesStore` (Zustand), not `localStorage` directly.

### Demo-Mode Caveat

The current `frontend/` scaffold runs in **demo mode**: mocked auth (`authStore` persists to localStorage), mocked AI/schema, in-memory query store. The rules above describe the **production** patterns and apply when wiring real backend calls (tracked under [FE-09](https://github.com/partqam/accessflow/issues/80) and follow-ups). Don't "fix" demo code to match these rules unless the task explicitly says so.

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
    AiAnalysisResult analyze(String sql, DbType dbType, String schemaContext,
                             String language, UUID organizationId);
}
```

`schemaContext` may be `null` or empty when introspection is unavailable; the prompt template
substitutes `(no schema introspection available)` in that case.

- Adapters route their HTTP calls through **Spring AI 2.0** (`spring-ai-bom:2.0.0-M6`). The autowired `AiAnalyzerStrategy` is `AiAnalyzerStrategyHolder` — it builds `AnthropicChatModel` / `OpenAiChatModel` / `OllamaChatModel` per-org from the `ai_config` row, caches the delegate, and evicts on `AiConfigUpdatedEvent` (no Spring context refresh, no restart).
- Active provider per org is the `ai_config.provider` column. There is no `accessflow.ai.provider` property and no `@ConditionalOnProperty` on the strategy classes — they are plain classes, not Spring beans.
- Connection settings (API key, base URL, model, max-tokens, timeout) come from `ai_config`, not from `spring.ai.*` properties. `application.yml` sets `spring.ai.model.{chat,embedding,image,audio.speech,audio.transcription,moderation}=none` so no `ChatModel` is auto-built at startup.
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
