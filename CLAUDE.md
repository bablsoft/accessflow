# CLAUDE.md — AccessFlow Agent Rules

This file is the authoritative guide for AI agents implementing AccessFlow. Read it entirely before writing any code. When in doubt, prefer the rules here over general best-practice intuition.

**Before writing code in an area, open [`.claude/patterns/README.md`](.claude/patterns/README.md)
and read the matching pattern.** This file holds the rules that are always true; the patterns hold
the per-area detail — canonical examples with `file:line`, acceptance checklists, and the
anti-patterns whose failure mode is silent. `docs/` remains the design reference for *what* to
build; the patterns describe *how*.

---

## Project at a Glance

AccessFlow is an open-source **database access governance platform**. It is a full query proxy between users and customer databases, enforcing configurable review and approval workflows before any query executes. Core capabilities: AI-powered query analysis, multi-stage human approval chains, role-based access control, a tamper-evident audit log, and real-time notifications.

**Supported engines.** The relational engines (PostgreSQL, MySQL, MariaDB, Oracle, SQL Server) run over pooled JDBC in-process; any other JDBC engine can be added by uploading its driver JAR (`db_type=CUSTOM`). Everything else is an **engine plugin** — a standalone shaded JAR implementing the `core.api.QueryEngine` SPI, resolved on demand through the connector catalog, SHA-256 verified, and discovered via `ServiceLoader`:

| Category | Engines |
|---|---|
| `RELATIONAL` | PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, `CUSTOM` (in-process JDBC) |
| `WAREHOUSE` | Snowflake, BigQuery, Databricks |
| `DOCUMENT` | MongoDB, Couchbase |
| `KEY_VALUE` | Redis, DynamoDB |
| `WIDE_COLUMN` | Cassandra, ScyllaDB *(same JAR)* |
| `SEARCH` | Elasticsearch, OpenSearch *(same JAR)* |
| `GRAPH` | Neo4j |

Each plugin has its own version line, pinned by URL + SHA-256 in `connectors/<id>/connector.json`; CI fails on pin drift. Per-engine specifics — dialect classifier, row-security shape and where it fails closed, connection model, shading — live in **`docs/05-backend.md`** (one section per engine). The host↔plugin contract and the add-an-engine checklist are **`docs/15-engine-sdk.md`**.

> Touching an engine? Read `.claude/patterns/engine-plugin.md` first, and
> `.claude/patterns/engine-fanout.md` before adding any `core.api` enum value.

AccessFlow ships as a single open-source product under Apache 2.0. Authentication uses JWT (RS256) with optional SAML 2.0 SSO and OAuth 2.0 / OIDC sign-in (built-in templates for Google, GitHub, GitHub Enterprise Server, Microsoft, GitLab, and self-managed GitLab; a generic `OIDC` provider type covers other IdPs — Keycloak, Auth0, Okta, Authentik, Zitadel — with admin-editable endpoint URLs persisted on the `oauth2_config` row). User and group lifecycle can be IdP-driven over SCIM 2.0 (`scim` module, `/scim/v2` with per-org bearer tokens — #621).

**Full design docs:** `docs/` — read them before implementing any feature. The authoritative references are:
- `docs/02-architecture.md` — system architecture and request flow
- `docs/03-data-model.md` — all entities, columns, enums, and indexes
- `docs/04-api-spec.md` — complete REST API and WebSocket spec
- `docs/05-backend.md` — proxy engine, workflow state machine, AI analyzer
- `docs/06-frontend.md` — directory structure, routing, state management
- `docs/07-security.md` — auth, authorization matrix, encryption rules
- `docs/11-development.md` — coding standards, testing strategy, Git workflow
- `README.md` (repo root) — public-facing project overview and quick start; keep in sync when changes affect setup, tech stack, features, project structure, or top-level documentation
- `website/` (repo root) — public marketing site (static HTML/CSS/JS, no build); keep in sync when changes affect the user-facing pitch, supported databases, AI providers, auth methods, features, roadmap, quick-start commands, or top-level URLs

---

## Repository Layout

```
accessflow/
├── backend/          # Spring Boot application (single Maven module)
├── engines/          # Engine plugins — engines/mongodb/, engines/couchbase/, engines/redis/, engines/cassandra/, engines/elasticsearch/, engines/dynamodb/, engines/neo4j/, engines/snowflake/, engines/bigquery/, engines/databricks/ (standalone Maven projects, shaded JARs)
├── terraform-provider/ # Terraform/OpenTofu provider (Go module, terraform-plugin-framework) — AF-452, source of truth; released to a dedicated terraform-provider-accessflow mirror repo
├── frontend/         # React / Vite / TypeScript SPA
├── connectors/       # Connector catalog — one manifest + logo per engine
├── deploy/           # Postgres init scripts (audit role, pgvector)
├── e2e/              # Playwright end-to-end suite + docker-compose.e2e.yml (+ .setup.yml and .sso.yml variants)
├── ci-templates/     # Reusable GitLab CI template + usage examples (AF-452)
├── charts/           # Helm charts — currently charts/accessflow/
├── docs/             # Design documentation
├── website/          # Public marketing site (static HTML/CSS/JS, no build step)
├── docker-compose.yml
└── .github/
    ├── actions/      # Reusable composite GitHub Actions (provision-datasource, run-query — AF-452)
    └── workflows/
```

The **Terraform/OpenTofu provider** (AF-452) lives in [`terraform-provider/`](terraform-provider/) — a standalone Go module (`github.com/bablsoft/terraform-provider-accessflow`) built on **terraform-plugin-framework** (latest stable; Go latest stable), with its own `go.mod` / CI / release. It is the source of truth, but the OpenTofu and HashiCorp registries require a repo named `NAMESPACE/terraform-provider-NAME`, so the [`release-terraform-provider.yml`](.github/workflows/release-terraform-provider.yml) workflow git-subtree-splits it into a dedicated **`bablsoft/terraform-provider-accessflow`** mirror repo (release-output only — never hand-edited) on a `terraform-provider-vX.Y.Z` tag. Reusable CI Actions live in [`.github/actions/`](.github/actions/) and [`ci-templates/`](ci-templates/). Full guide: [`docs/16-iac.md`](docs/16-iac.md).

The root [`docker-compose.yml`](docker-compose.yml) is intentionally a **zero-config demo stack** that ships insecure demo `JWT_PRIVATE_KEY` and `ENCRYPTION_KEY` defaults inline so `docker compose up` works on a fresh clone with no `.env` and no key generation. Do not "fix" the embedded keys — they are committed deliberately. The production-style compose (with real `.env` / generated keys / optional Ollama profile) lives in [`docs/09-deployment.md`](docs/09-deployment.md). The dev-loop infrastructure-only compose (Postgres + Redis + Mailcrab) is [`backend/docker-compose-dev.yml`](backend/docker-compose-dev.yml).

---

## Backend

### Runtime & Framework Versions

| Item | Version |
|------|---------|
| Java | **25** (not 21 — the pom.xml uses `<java.version>25</java.version>`) |
| Spring Boot | **4.1.0** (not 3.x — the actual parent POM) |
| Spring Modulith | 2.1.0 |
| PostgreSQL driver | latest compatible with Boot 4 |

---

### Architecture

#### Spring Modulith Structure

The project is a **single Maven module** with **Spring Modulith** enforcing logical module boundaries via package conventions. Do **not** split into Maven sub-modules. (The `engines/*` plugin projects are deliberately **outside** the application: standalone Maven projects that compile against the backend's plain JAR — the backend's `spring-boot-maven-plugin` uses `<classifier>exec</classifier>` so `mvn install` publishes the plain classes; the runnable fat jar is `*-exec.jar`.)

**Root package:** `com.bablsoft.accessflow`

Each business module lives under its own top-level sub-package. Modules communicate through **Spring application events** and **exposed `api` packages** — never by reaching into another module's `internal` sub-packages.

```
com.bablsoft.accessflow/
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
├── ai/             # AI analyzer strategy + adapters (OpenAI, Anthropic, Ollama, Hugging Face)
│   ├── api/
│   └── internal/
├── security/       # JWT config, Spring Security filters, SAML 2.0 SSO
│   ├── api/
│   └── internal/
├── notifications/  # Email, Slack, Webhook dispatchers
│   ├── api/
│   └── internal/
├── audit/          # Audit log service, ApplicationEvent consumers
│   ├── api/
│   └── internal/
├── compliance/     # Compliance reports + signed PDF/CSV exports over query snapshots (AF-459); result-export governance & DLP — export policies, watermarked result exports, RESULT_EXPORTED audit (#626)
│   ├── api/
│   └── internal/
├── dashboard/      # Personalized self-scoped dashboard: summary, query trends, AI-suggestion backlog, weekly digest/export (AF-498)
│   ├── api/
│   ├── events/
│   └── internal/
├── attestation/    # Access recertification: scheduled attestation campaigns over standing grants, certify/revoke worklist, CSV evidence (AF-384)
│   ├── api/
│   ├── events/
│   └── internal/
├── apigov/         # API Access Governance: govern outbound REST/SOAP/GraphQL/gRPC calls — connectors, schema ingestion, permissions, submit→AI→review→execute pipeline, masking, break-glass, text-to-API (AF-500); connector-level response masking policies + data-classification tags with auto-derived masking & AI-risk bump (schema-field/JSON-path/XML-path/regex matchers — AF-518)
│   ├── api/
│   ├── events/
│   └── internal/   # persistence, client (per-protocol exec + auth + prober), schema (parsers), routing, scheduled, web
├── deploygov/      # Deployment approval governance (epic AF-682): gate CI/CD deployments behind approval workflows. #684 landed the persistence foundation — pipelines/environments/freeze windows/requests/decisions/routing/grants (reusing query_status, submission_reason, decision, api_routing_action) + DEPLOYMENT_PIPELINE_MANAGE / DEPLOYMENT_REVIEW permissions; #688 adds the admin surface — pipeline/environment/permission/freeze-window CRUD services + controllers, the effective-permission resolver (most-permissive union of direct + unexpired group grants), and the fail-closed FreezeWindowEvaluator; #691 adds the submission half — the API-key-authenticated trigger endpoint (idempotent on the CI run), `ai.api.DeploymentAnalyzer` + the deploygov-side analysis listener, the typed-conditions DeploymentRoutingPolicyEngine with admin CRUD, and the transition-validating DeploymentRequestStateService; #692 adds the human half — the review decision endpoints (`/api/v1/deployment-reviews`, single-stage, idempotent, self-approval → 409, plan-approver eligibility without delegation), deployment break-glass (can_break_glass grant AND environment allow_break_glass, no admin bypass; force-approve, freeze bypass audited via DEPLOYMENT_BREAK_GLASS_EXECUTED, mandatory retro-review in break_glass_events via the synchronous workflow listener), and the plan-driven DeploymentTimeoutJob (`accessflow.deploygov.timeout-check`); #693 adds the machine contract — the fail-closed gate (`GET /api/v1/deployment-gate`, one pure releasable function: APPROVED, no active freeze window (HOLD or REJECT; break-glass exempt), scheduled_for passed; any error → not releasable; 404-never-403 visibility), confirm-execution (`APPROVED → EXECUTED`, releasable-gated, audited DEPLOYMENT_EXECUTED trigger=pipeline), idempotent outcome reporting (conflict → 409 DEPLOYMENT_OUTCOME_CONFLICT; FAILED flips EXECUTED → FAILED; ROLLED_BACK on a require_review environment opens a deployment_rollback_reviews follow-up the submitter can never acknowledge), and the notify-only ScheduledDeploymentReleaseJob (`accessflow.deploygov.release-check`, one-shot DeploymentReleasableEvent via release_notified_at); CI wrappers follow in later sub-issues
│   ├── api/
│   ├── events/
│   └── internal/   # persistence, services, resolver/evaluator, scheduled, web
├── requestgroups/  # Request chaining & grouping: bundle ordered query + API-call members into one grouped request — aggregated AI/review/approval (union of approvers, satisfy every plan), ordered executor with NO distributed rollback (AF-501)
│   ├── api/
│   ├── events/
│   └── internal/   # persistence, scheduled (run + timeout jobs), web
├── discovery/      # Automated sensitive-data discovery (AF-623): DiscoveryScanJob samples column data via the engine sampling path, regex+checksum detectors (email, PAN+Luhn, SSN, IBAN, phone) + optional fail-safe AI pass propose classification tags an admin confirms (AF-447 derivation) or dismisses
│   ├── api/
│   └── internal/   # config, persistence, detect (pure detectors), scheduled, web
├── scim/           # SCIM 2.0 provisioning server (#621): /scim/v2 Users+Groups behind a per-org bearer-token filter chain (@Order(0), SCIM error envelope), attribute-mapping config, show-once tokens; deactivation fans out via core.events.UserDeactivatedEvent (security revokes sessions, access revokes JIT grants)
│   ├── api/
│   └── internal/   # config (own SecurityFilterChain), persistence, protocol (wire records, filter/patch parsing), web (scim + admin controllers)
└── mcp/            # Spring AI stateless MCP server — @Tool callbacks for AI agents
    ├── api/
    └── internal/
```

#### Key Rules

- **Module boundaries are enforced.** `ApplicationModulesTest` must exist and pass in CI. Run it after every change: `mvn -f backend/pom.xml test -Dtest=ApplicationModulesTest`.
- **No cyclic dependencies between modules.** If two modules need each other, extract a shared interface or communicate through events.
- **`internal` sub-packages are module-private.** Only types in `api` (or the module root package) are accessible to other modules.
- **Spring configuration placement:** all `@Configuration` classes belong in the owning module's `internal` package.
- **Cross-module communication** uses `ApplicationEventPublisher` (fire-and-forget) or `@ApplicationModuleListener` (transactional event listeners). Direct injection of another module's internal beans is forbidden.
- **Module API purity.** Types in `com.bablsoft.accessflow.<module>.api` packages may import **only** `java.*`, `javax.*` (JDK), and other `com.bablsoft.accessflow.*` project types. No Spring, no Spring Data, no Spring Security, no Jackson, no Jakarta Servlet, no Hibernate, no JSqlParser, no Lombok, no third-party library at all — including in Javadoc references. The sole allowed third-party reference is `org.springframework.modulith.NamedInterface` on `package-info.java` (it's a meta-marker that designates the package as the module's exposed named interface — not a runtime contract type). For paginated reads, services accept `core.api.PageRequest` and return `core.api.PageResponse<T>` and adapt to Spring Data inside the service implementation (`core.internal.PageAdapter` for `core` itself; an `internal/web/SpringPageableAdapter` in each module's web layer for the controller side). Enforced by `ApiPackageDependencyTest` (ArchUnit) — the build fails when a new external import appears in any `api/` package. The rule matches `com.bablsoft.accessflow.*.api..` by wildcard, so a new module is covered automatically with no registration step. Run it after every change touching an api package: `mvn -f backend/pom.xml test -Dtest=ApiPackageDependencyTest`.

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

All endpoints `/api/v1/...`, `kebab-case`. Proper status codes (201 create, 202 async, 204
delete, 422 SQL parse error). All errors are RFC 9457 `ProblemDetail` via `@ControllerAdvice`.
Every controller method needs Springdoc `@Operation` + `@ApiResponse`. Request DTOs use Bean
Validation. **Document a new endpoint in `docs/04-api-spec.md` before writing it.**

→ `.claude/patterns/rest-controller.md`

#### Database & JPA

UUID PKs, `snake_case` tables, `TIMESTAMPTZ` timestamps, `@Access(AccessType.FIELD)`, explicit
`@Table`/`@Column`, `FetchType.LAZY` always, `@Version` where concurrent. PG enum types are
`snake_case` with **no** `_enum` suffix and the `columnDefinition` must match exactly.
`ddl-auto` is `validate` everywhere real.

→ `.claude/patterns/jpa-entity-migration.md`


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

Module-specific hierarchies over a common base. **Never catch `Exception` or `Throwable`
broadly** (the documented carve-out is a scheduled job's per-row `RuntimeException`). A global
`@ControllerAdvice` maps to `ProblemDetail`; module advices need
`@Order(Ordered.HIGHEST_PRECEDENCE)` or the security catch-all wins. Never expose stack traces.


#### Internationalisation (i18n)

**Never hardcode a user-facing string in Java.** Exception details and validation messages live
in `i18n/messages.properties`; Bean Validation uses `message = "{key}"`; handlers resolve through
`MessageSource` with `LocaleContextHolder.getLocale()` (`SecurityExceptionHandler` must use
`request.getLocale()`). A new key must be added to **all six** locale files —
`MessagesParityTest` enforces it. SLF4J log messages stay English.

→ `.claude/patterns/backend-i18n.md`

---


### Configuration

`application.yml` carries no secrets — `${ENV_VAR}` placeholders only.

**The operator reference is [docs/09-deployment.md](docs/09-deployment.md) — the single
authoritative copy of all ~168 env vars.** Don't duplicate it here; grep it instead.

Adding a knob: bind `accessflow.<module>.<kebab-name>` on a `<Module>Properties` record in
`<module>/internal/config/`; use an ISO-8601 `Duration` for cadences with the default inline
(`${accessflow.x.y:PT5M}`); document the row in `docs/09-deployment.md` in the same commit.
Spring's relaxed binding gives you the `UPPER_SNAKE_CASE` env form for free — never add an
`@Value` alias for it. Per-engine plugin tuning needs no host code at all: anything under
`accessflow.proxy.engines.<id>.*` is passed verbatim into the engine's `QueryEngineContext`,
reachable as `ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>`.

The five referenced by the non-negotiable Security Rules below: `ENCRYPTION_KEY` (32-byte hex,
AES-256-GCM for datasource credentials), `JWT_PRIVATE_KEY` (RSA-2048 PEM, RS256 signing),
`DB_URL`/`DB_USER`/`DB_PASSWORD`, `AUDIT_DB_USER`/`AUDIT_DB_PASSWORD` (the dedicated
audit-writer role — the app role has SELECT-only on `audit_log`), and `REDIS_URL`.

---

### Database Migrations (Flyway)

Flyway only, in `backend/src/main/resources/db/migration/`, named `V{n}__{snake_case}.sql`.
**Never modify a migration that already exists on `main`** — Flyway checksums applied migrations,
so a change makes every existing deployment fail to start, with no rollback. Every added column is
nullable or has a DEFAULT. `ALTER TYPE … ADD VALUE` needs a `.sql.conf` sidecar with
`executeInTransaction=false`.

→ `.claude/patterns/jpa-entity-migration.md`

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
             ↘ PENDING_REVIEW or APPROVED (if AI not required —
                                            datasource.ai_analysis_enabled=false;
                                            APPROVED only when plan.requires_human_approval=false)
  PENDING_AI → APPROVED  (routing-policy AUTO_APPROVE — AF-379)
  PENDING_AI → APPROVED  (grant-covered auto-approval — #582; an active APPROVED JIT grant with
                          pre_approve_queries=true covers the query (capability + table scope).
                          Loses to any matching routing policy, suppressed on HIGH/CRITICAL risk,
                          open anomaly, SQL re-parse failure, and the AI-failed path)
  PENDING_AI → APPROVED  (break-glass — submitter holds can_break_glass; bypasses AI + review,
                          submission_reason=EMERGENCY_ACCESS, no QuerySubmittedEvent — AF-385)
  PENDING_AI → REJECTED  (routing-policy AUTO_REJECT — AF-379; no review_decisions row,
                          audited via QueryAutoRejectedEvent)
  PENDING_REVIEW → APPROVED or REJECTED (external ticket resolution — AF-453; a channel with
                          bidirectional_sync=true maps a ServiceNow/Jira ticket resolution onto a
                          decision via workflow.api.ExternalDecisionService. System-attributed:
                          no review_decisions row, publishes QueryAutoApproved/RejectedEvent with
                          the ticket provenance as reason; no-op when a manual decision raced)
  PENDING_REVIEW → CANCELLED (submitter only)
  APPROVED       → CANCELLED (submitter only, when scheduled_for is set and the
                              deferred run has not yet fired — AF-345; for a recurring
                              series (recurrence_rule set, #627) also any QUERY_REVIEW
                              holder — the reviewer kill-switch stops the whole series)
  APPROVED       → EXECUTED  (ScheduledQueryRunJob at scheduled_for ≤ now() —
                              system-initiated, audit metadata trigger=scheduled)
  APPROVED       → EXECUTED / FAILED (recurring occurrence rows — #627. RecurringQueryRunJob
                              INSERTS child rows directly in APPROVED (submission_reason=
                              RECURRING, recurring_parent_id set, no QuerySubmittedEvent) and
                              executes them with trigger=recurring; the series parent stays
                              APPROVED for its lifetime and can never be executed manually.
                              Fail-closed: a failed per-tick recheck (permission gone/expired,
                              submitter inactive, SQL unparseable, datasource inactive) clears
                              recurrence_next_run_at, records recurrence_halted_reason, and
                              audits RECURRING_SERIES_HALTED)
  APPROVED       → EXECUTED  (break-glass run — audit action QUERY_BREAK_GLASS_EXECUTED — AF-385)
  APPROVED       → FAILED    (execution error)
  ```

  Illegal transitions must throw a domain exception, not silently succeed. **Break-glass /
  emergency access (AF-385):** a distinct submission mode gated by a per-user/per-datasource
  `can_break_glass` permission (required for everyone, including admins). It persists the query as
  `EMERGENCY_ACCESS` without publishing `QuerySubmittedEvent`, force-approves it
  (`PENDING_AI → APPROVED`), and executes it immediately through all the usual proxy guards
  (allow-list, masking, row-security, row caps). Compensating controls: instant fanout to all org
  admins (incl. PagerDuty), a prominent `QUERY_BREAK_GLASS_EXECUTED` audit row, and a mandatory
  retro-review tracked in `break_glass_events` (the executed query is never re-opened) that an admin
  — never the submitter — must acknowledge (`BREAK_GLASS_REVIEWED`). See
  [docs/05-backend.md](docs/05-backend.md) → "Break-glass / emergency access".

---

### Scheduled Jobs (clustered-safe)

**Every `@Scheduled` method MUST also carry `@SchedulerLock`.** Without it, a multi-replica
deployment runs the job once per replica per tick — for the erasure and retention jobs that is
data loss. Jobs live in `<module>/internal/scheduled/`, take a `Clock`, swallow per-row
`RuntimeException`s, and take their cadence from an ISO-8601 property with the default inline.
For non-periodic critical sections use `scheduling.api.DistributedLockService`.

→ `.claude/patterns/scheduled-job.md`; job registry in `docs/05-backend.md`

---

### Security Rules — Non-Negotiable

1. **No string-concatenation SQL** — `PreparedStatement` exclusively in the proxy engine.
2. **JSqlParser validation first** — parse every submitted SQL before any execution path. Reject unparseable SQL with HTTP 422. Multi-statement input is rejected, **except** for `BEGIN; … COMMIT;` envelopes wrapping a homogeneous INSERT/UPDATE/DELETE batch — those are executed under a single JDBC transaction (`autoCommit=false` + commit on success / rollback on `SQLException`). Inside the envelope, SELECT, DDL, `ROLLBACK`, `SAVEPOINT`, and nested `BEGIN` are all rejected with distinct 422 messages.
3. **Schema allow-list at AST level** — walk the parsed AST to validate referenced tables, not string matching.
4. **`password_encrypted` never in heap beyond pool init** — decrypt credentials once inside `QueryProxyService`, pass to HikariCP, do not store the plaintext.
5. **A user can never approve their own query**, regardless of role. Enforce in the workflow service, not just the UI.
6. **`@JsonIgnore` on all encrypted/sensitive fields** — entity-level, not just controller-level.
7. **CORS** — only the configured `CORS_ALLOWED_ORIGIN` is allowed. No wildcard in production.
8. **Refresh token cookies** — `HttpOnly; Secure; SameSite=Strict`.
9. **WebSocket handshake auth** — `/ws` is exempt from `JwtAuthenticationFilter`; the upgrade is authenticated by `realtime/internal/ws/JwtHandshakeInterceptor`, which calls the public `AccessTokenAuthenticator` (`security/api/`) on the `?token=<JWT>` query param. Same RSA key, same expiry rules — never a separate WS token. Browsers cannot set custom headers on a WS upgrade, which is why this path exists.

---

### Testing (Backend)

| Type | Suffix | Framework | Scope |
|------|--------|-----------|-------|
| Unit | `*Test.java` | JUnit 5 + Mockito | Single class, no Spring context |
| Integration | `*IntegrationTest.java` | `@SpringBootTest` + Testcontainers | Full context, real DB |
| Module | `*ModuleTest.java` | `@ApplicationModuleTest` | Single module isolation |
| Architecture | `ApplicationModulesTest`, `ApiPackageDependencyTest` | Modulith + ArchUnit | Boundary + api/ purity |

**Coverage ≥ 90% lines / ≥ 80% branches**, and **every concrete class ships its own test in the
same change** — do not assume coverage arrives from callers, because controller tests
`@MockitoBean` the service so the implementation never runs. Use the shared
`TestcontainersConfig` (it provisions the audit role and pgvector); **never H2**.

→ `.claude/patterns/backend-test-parity.md`

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

There is **no Maven wrapper** in this repo — use plain `mvn`, as CI does.

```bash
cd backend
mvn verify                       # full build + tests
mvn verify -Pcoverage            # with JaCoCo coverage report
mvn spring-boot:run              # run locally (requires env vars set)
mvn -q test -Dtest='ApplicationModulesTest,ApiPackageDependencyTest'  # architecture gates
```

Engine plugins build separately against the backend's installed plain jar:

```bash
mvn -f backend/pom.xml install -DskipTests   # publish the plain (non-exec) jar
mvn -f engines/<id>/pom.xml clean verify     # one plugin
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

React 19 / Vite / TypeScript SPA at `frontend/`, with real API wiring throughout (77 modules go
through `src/api/client.ts`). `src/mocks/` is test-only. Full reference: `docs/06-frontend.md`.

> Writing a page or a form? Read `.claude/patterns/frontend-page.md` and
> `.claude/patterns/frontend-form.md`. Touching a covered flow? `.claude/patterns/e2e-spec.md`.
### Tech Stack (required libraries — version: always latest stable)

For all frontend dependencies, pin to the **latest stable** version available on npm at the time of `npm install`. Re-verify with `npm view <pkg> version` before adding or upgrading. If a newer major has shipped since the last check, prefer it unless a specific incompatibility is documented in the same change. Do not substitute the libraries themselves — but always take the newest stable major of each.

| Technology | Snapshot (latest stable as of 2026-05-06) | Role |
|-----------|-------------------------------------------|------|
| React + ReactDOM | 19.x | UI framework |
| Vite + @vitejs/plugin-react | 8.x | Build tool |
| TypeScript | 6.x | Language (`strict: true`) |
| Ant Design | 6.x | Component library |
| @ant-design/charts | 2.x | Admin dashboard charts (AI analyses history) |
| Bklit UI (vendored) | shadcn registry `@bklit/*` | User-dashboard charts (AF-498 redesign) — area/line/ring/heatmap, vendored under `src/components/charts/`; pulls `@visx/*@4.0.1-alpha.0` (pinned as Bklit requires — sanctioned exception to the latest-stable rule) + `motion` |
| Tailwind CSS + @tailwindcss/vite | 4.x | **Only** for the vendored Bklit components — `src/styles/bklit.css` imports theme+utilities without preflight (AntD owns base styles) and binds dark mode to `[data-theme='dark']` |
| @xyflow/react | 12.x | ER diagram on `DatasourceSettingsPage` |
| dagre | 0.8.x | Auto-layout for the ER diagram graph |
| @dnd-kit/core + @dnd-kit/sortable + @dnd-kit/utilities | 6.x / 10.x / 3.x | Drag-and-drop reorder of personalized dashboard widgets (AF-498) |
| CodeMirror + @codemirror/lang-sql | 6.x | SQL editor (PostgreSQL/MySQL dialects) |
| @codemirror/lang-javascript + @codemirror/lang-json | 6.x | MongoDB query highlighting — shell (JS) and JSON-command modes |
| @codemirror/merge | 6.x | Side-by-side Git-style diff for saved-query version history (AF-442) |
| yjs + y-codemirror.next + y-protocols | 13.x / 0.3.x / 1.x | CRDT collaborative editing of a query in review — shared doc, remote cursors, awareness (AF-441) |
| vite-plugin-pwa | 1.x | PWA build — `injectManifest` mode; Workbox precaches the offline review-queue shell, custom `src/sw.ts` owns the push / notificationclick handlers (AF-444) |
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

### Non-negotiables

Everything else — state management, routing, WebSocket conventions, error envelopes, loading
states, code splitting, theming, a11y — is in `docs/06-frontend.md` and the pattern files. These
eight are the rules an agent needs *before* it knows which pattern to open:

1. **`strict: true`.** Never `as any` to silence a type error — fix the type. API shapes live in
   `src/types/api.ts`.
2. **Every user-visible string through `t()`** — labels, placeholders, titles, column headers,
   `aria-label`s, and backend enum values (via `src/utils/enumLabels.ts`, never an inline
   `{ value: 'EMAIL', label: 'EMAIL' }`).
3. **TanStack Query for all server data.** No `useEffect` fetching, and **no server data in
   Zustand** — only `authStore`, `notificationStore`, `preferencesStore` are legitimate.
4. **JWT access token in memory** (Zustand), never `localStorage`/`sessionStorage`. The refresh
   token is an `HttpOnly; Secure; SameSite=Strict` cookie the frontend never reads.
5. **Config via `getApiBaseUrl()`/`getWsUrl()`** from `src/config/runtimeConfig.ts` — never
   `import.meta.env` in a component, never `process.env`.
6. **All requests through `src/api/client.ts`** — never a bare `fetch`. 401 is the interceptor's
   job; components must not catch it.
7. **Never `dangerouslySetInnerHTML`, `eval`, or `new Function`.** No hardcoded hex colours —
   use the `--af-*` tokens, and `src/utils/{statusColors,riskColors}.ts` for status/risk.
   Carve-out: the **vendored Bklit chart components** (`src/components/charts/**`,
   `src/components/shimmering-text.tsx`) are third-party registry code — excluded from ESLint,
   re-vendorable, and themed exclusively through the CSS-variable bridge in
   `src/styles/bklit.css` (whose oklch chart ramps are the one sanctioned token extension
   outside `tokens.css`). App code imports them only via the `src/components/charts/index.ts`
   barrel and never edits vendored files beyond annotated local patches.
8. **Validation parity.** Every backend Bean Validation constraint has a matching `Form.Item`
   rule and vice versa, changed in the same commit
   (`.claude/patterns/frontend-form.md`).

Commands: `npm run lint`, `npm run typecheck`, `npm run test:coverage` (≥90% lines / ≥80%
branches), `npm run build`. Note `build` is **stricter than `typecheck`** — it enforces
`noUncheckedIndexedAccess` on test files too, so a green typecheck is not sufficient.

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
                             String costEstimateContext, String language, UUID aiConfigId);
}
```

`schemaContext` may be `null` or empty when introspection is unavailable; the prompt template
substitutes `(no schema introspection available)` in that case. `costEstimateContext` (AF-624)
carries the same contract for the pre-flight cost estimate — `null`/empty substitutes
`(no cost estimate available)`.

- Adapters route their HTTP calls through **Spring AI 2.0** (`spring-ai-bom:2.0.0`). The autowired `AiAnalyzerStrategy` is `AiAnalyzerStrategyHolder` — it builds `AnthropicChatModel` / `OpenAiChatModel` / `OllamaChatModel` per-org from the `ai_config` row, caches the delegate, and evicts on `AiConfigUpdatedEvent` (no Spring context refresh, no restart).
- The same Spring AI BOM also pins `spring-ai-starter-mcp-server-webmvc` (`2.0.0`) — the stateless MCP server starter used by the `mcp` module. The starter ships with `spring-ai-autoconfigure-mcp-server-common` as an explicit dependency in `backend/pom.xml` to work around a missing transitive in the starter POM (`McpServerStdioDisabledCondition` lives in the common artifact). See `docs/13-mcp.md` and `docs/05-backend.md` → "MCP server and user API keys".
- Active provider per org is the `ai_config.provider` column. There is no `accessflow.ai.provider` property and no `@ConditionalOnProperty` on the strategy classes — they are plain classes, not Spring beans.
- Connection settings (API key, base URL, model, max-tokens, timeout) come from `ai_config`, not from `spring.ai.*` properties. `application.yml` sets `spring.ai.model.{chat,embedding,image,audio.speech,audio.transcription,moderation}=none` so no `ChatModel` is auto-built at startup.
- The default system prompt template (`SystemPromptRenderer.DEFAULT_TEMPLATE`) is in `docs/05-backend.md` — use it verbatim; do not invent a different prompt. It uses named placeholders `{{db_type}}`, `{{schema_context}}`, `{{rag_context}}`, `{{cost_estimate}}` (AF-624 — the pre-flight estimate summary), `{{sql}}`, `{{language}}` (substituted at render time; `{{sql}}` replaced last). Admins may override the prompt **per `ai_config` row** via the nullable `system_prompt_template` column (blank ⇒ default); a custom template **must contain `{{sql}}`** or the service throws `AiConfigInvalidPromptException` (HTTP 400 `AI_CONFIG_INVALID_PROMPT`). The default is served to the admin UI via `GET /admin/ai-configs/prompt-default`.
- AI calls are asynchronous — publish a `QuerySubmittedEvent` and handle in the strategy asynchronously using virtual threads.
- The response must be parsed strictly as JSON matching the `AiAnalysisResult` schema. If the AI returns non-JSON or an unexpected schema, log and mark the analysis as failed; do not propagate the exception to the query request.
- For Anthropic: use `claude-sonnet-4-20250514` as the default model.
- For OpenAI: use `gpt-4o` as the default model.
- For Hugging Face: reuse `OpenAiAnalyzerStrategy` (OpenAI-compatible wire format) with the Inference Providers router base URL `https://router.huggingface.co/v1` (default; override with a local TGI / Dedicated Endpoint URL). Keyless-capable (placeholder key when no HF token is stored). Default model `meta-llama/Llama-3.3-70B-Instruct`.

---

## Notification System

- All notification delivery is **async and non-blocking** — failures must not affect query workflow state.
- `NotificationDispatcher` listens to Spring `ApplicationEvent` objects; never called synchronously from the workflow engine.
- Email bodies use Thymeleaf templates in `resources/templates/email/` — one template per event type.
- Slack messages use Block Kit format (see `docs/08-notifications.md`).
- Webhooks must include `X-AccessFlow-Signature: sha256=<HMAC-SHA256>` on every delivery.
- Webhook retry policy: 1 initial attempt + up to 3 scheduled retries at +30 s, +2 min, +10 min (4 total attempts). Retry delays are configurable via `accessflow.notifications.retry.{first,second,third}`. On exhaustion the dispatcher logs `ERROR`; audit-log integration is deferred until the audit module exists.
- Sensitive channel config fields (`smtp_password`, `webhook_secret`) must be AES-256 encrypted before persistence; never returned in API responses.

Adding a `NotificationEventType` or `NotificationChannelType` value touches seven switches, a
Thymeleaf template, fourteen message files and the frontend union — and two of the switches have a
`default`, so the compiler will not find them all → `.claude/patterns/notification-fanout.md`

---

## Git Workflow

Trunk-based: **`main` is the only long-lived branch.** There is no `develop`. Every
branch is cut from `main` and merged back into `main` by PR.

```
main                          → the trunk; production-ready, tagged releases
feature/AF-{n}-description    → from main   (new capability, tied to issue #n)
fix/AF-{n}-description        → from main   (bug fix, tied to issue #n)
chore/AF-{n}-description      → from main   (tooling, docs, release prep)
```

`gh-pages` is release output only (Helm index, connector bundle, engine jars) — never
hand-edited. `dependabot/*` branches are machine-generated and exempt from the naming rule.

Where no issue exists, a descriptive slug replaces the number
(`fix/AF-security-txt-expiry-guard`) — but the numbered form is strongly preferred, and
`impl-gh-issue` always produces it.

**PR requirements:** passing CI (the single required check is `CI / CI Gate`), ≥ 1 approval, PR description references the issue number. Checkstyle and Spotless run in Maven's `validate` phase, so any `mvn verify` already enforces them — see [docs/11-development.md](docs/11-development.md) → Coding Standards. There is no auto-formatter profile; match the surrounding file's style.

Branch names must match the pattern above. Commit messages should be imperative mood, ≤ 72 chars subject line.

---

## CI / CD

`.github/workflows/ci.yml` — one workflow, conditional area jobs gated by `dorny/paths-filter`,
so branch protection requires exactly **one** check.

- Area jobs: `backend`, `frontend` (includes the `website/` guards), `helm`, `e2e` (3-variant
  matrix), `connectors`, `engines` (10-engine matrix, fails on SHA pin drift), `terraform`,
  `actions`. Each runs only when its paths changed.
- **`CI / CI Gate` is the only check to mark required.** It `needs` every area job with
  `if: always()` and passes when each is `success` or `skipped`. Never require an individual
  area job — it reports `skipped` on unrelated PRs and would block them.

`.github/workflows/release.yml` is **manual** (`workflow_dispatch`) with a semver `version`
input. A `-suffix` (e.g. `1.2.3-beta.1`) makes it a pre-release: moving tag `:beta` instead of
`:latest`, GitHub "Pre-release" badge, and the stable connectors-index pointer on `gh-pages` is
left untouched. It bumps `backend/pom.xml` + `frontend/package.json` on a **detached** commit and
pushes only the tag, so `main` always reads `1.0.0-SNAPSHOT`. It publishes multi-arch GHCR images,
the reproducible engine jars, and the Helm chart (whose `version`/`appVersion` track the app
version 1:1 — the committed `0.1.0` is a placeholder).

`.github/workflows/release-terraform-provider.yml` is separate, fires on
`terraform-provider-vX.Y.Z`, and git-subtree-splits `terraform-provider/` into the
`bablsoft/terraform-provider-accessflow` mirror. Full detail: `docs/11-development.md`,
`docs/16-iac.md`.

Docker images: `backend/Dockerfile` (maven:3-eclipse-temurin-25-alpine → temurin:25-jre-alpine,
non-root) and `frontend/Dockerfile` (node:24-alpine → nginx:alpine, with `frontend/nginx.conf`
handling SPA routing and `no-store` on `runtime-config.js`).

---

## What to Avoid

Each line is a hard rule. Where a pattern file expands on it, follow the arrow.

**Backend**
- `@Autowired` field injection — constructor injection only *(Checkstyle-enforced)*.
- Returning JPA entities from controllers → `patterns/rest-controller.md`.
- `@Transactional` on a controller.
- **Modifying an existing Flyway migration** → `patterns/jpa-entity-migration.md` *(hook-blocked)*.
- `ddl-auto: create`/`update` outside Testcontainers tests.
- `@Scheduled` without `@SchedulerLock` → `patterns/scheduled-job.md` *(hook-blocked)*.
- A third-party import in an `api/` package → `patterns/modulith-module.md`.
- `com.fasterxml.jackson.databind` imports — Jackson 3 is `tools.jackson.*` *(Checkstyle-enforced)*.
  The annotation package `com.fasterxml.jackson.annotation.*` is unaffected and still correct.
- `System.out` / `printStackTrace` — SLF4J only *(Checkstyle-enforced)*.
- Hardcoded user-facing strings in Java → `patterns/backend-i18n.md`.
- Shipping a `Default*Service` without its test → `patterns/backend-test-parity.md`.

**Security**
- Storing the decrypted DB password beyond HikariCP pool init.
- Exposing `password_encrypted` in any response.
- Approving your own query — enforce in the service, not just the UI.
- Hard-coded secrets.

**Frontend**
- `useEffect` for data fetching — TanStack Query → `patterns/frontend-page.md`.
- JWT access tokens in `localStorage`/`sessionStorage`.
- `onError: () => message.error(t('…'))` that discards the server `detail` → `patterns/frontend-page.md`.

**Don't let these drift** — same commit set, always:
- `README.md`, when the pitch, tech-stack versions, quick-start, structure, or top-level
  features change.
- `e2e/tests/`, when a frontend change touches a covered route/form/selector, or adds a
  user-facing flow → `patterns/e2e-spec.md`.
- `website/` + its `sitemap.xml` `<lastmod>` and JSON-LD `dateModified`, and the
  `frontend/src/config/docs.ts` ↔ `website/app.js` anchor contract →
  `patterns/website-drift.md`.
- `docs/09-deployment.md`, when you add a config knob.

**Process**
- Multi-paragraph comments or doc comments on obvious methods.
- Features beyond what was requested; designing for hypothetical future requirements.