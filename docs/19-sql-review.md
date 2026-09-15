# 19 — Deterministic SQL Review Rules (epic #860)

AccessFlow judges a submitted query in two ways: the **AI analyzer** returns a risk score and
free-text issues, and **routing policies** collapse a typed condition tree into one auto-decision.
Neither tells the author, at the moment they are typing, that their SQL breaks a rule the
organisation has written down. **Deterministic SQL review rules** are the third judgement: a
**named rule catalog** evaluated over the parsed statement, whose violations are reported one by
one, carry a stable rule id, are configurable per environment, and appear in the editor as the
author types.

It lives in the `sqlreview` Spring Modulith module (`com.bablsoft.accessflow.sqlreview`), a peer
of `discovery` / `attestation` / `deploygov`. `workflow` and `requestgroups` depend on
`sqlreview.api`; the reverse dependency never exists, so there is no cycle. It reuses what is
already there — `proxy.api.SqlParserService` for the first parse, `core.api.GlobMatcher` for table
patterns, the audit log, the review state machine's decision chokepoint — and adds only three
tables, two PG enums, one permission and one datasource attribute.

> **Delivery status.** Complete on `main` for the v2.6 milestone (in progress): the persistence foundation and the
> `datasources.environment` attribute (#861), the rule engine and the fourteen built-in rules
> (#862), ruleset administration, the localized rule catalog and the read-only evaluation
> endpoint (#863), enforcement at the submission chokepoint with the reviewer surfaces (#864), the
> live editor lint and the `/admin/sql-review` page (#865), and this chapter (#866).

> **The one sentence to remember.** A `BLOCK` finding means *a person must look*. It suppresses
> every path that would have approved the query without a human and sends it to review. **It never
> rejects** — nothing in this feature introduces a new way for a query to be refused without a
> reviewer deciding it.

---

## Module layout

```
com.bablsoft.accessflow.sqlreview/
├── api/
│   ├── SqlReviewService                 # evaluate(org, datasource, sql) / evaluateForUser(...) — read-only
│   ├── SqlReviewFindingService          # recordForQuery / recordForGroupItem / blockingRuleIds / reads — read-write
│   ├── SqlReviewRulesetService          # ruleset CRUD behind /admin/sql-review-rulesets
│   ├── SqlReviewRuleCatalogService      # the localized catalog behind GET /sql-review/rules
│   ├── SqlReviewFindingRenderer         # rule_id + args → the reader-locale message
│   ├── SqlReviewResult / SqlReviewFinding / SqlReviewSeverity / SqlRuleCategory
│   ├── SqlReviewRulesetView / SqlReviewRuleConfigView / SqlReviewRuleView / SqlReviewRuleParamView
│   ├── Create/UpdateSqlReviewRulesetCommand
│   └── SqlReviewRulesetNotFoundException / SqlReviewRulesetConflictException / IllegalSqlReviewRulesetException
└── internal/
    ├── DefaultSqlReviewService          # applicability gate, ruleset resolution, parse, evaluate
    ├── DefaultSqlReviewFindingService   # persists findings wholesale per owner, answers blockingRuleIds
    ├── DefaultSqlReviewRulesetService   # uniqueness pre-checks, params validation, wholesale rule replace
    ├── DefaultSqlReviewRuleCatalogService / DefaultSqlReviewFindingRenderer
    ├── SqlReviewEvaluator               # pure: runs every resolved rule over every statement
    ├── SqlStatementParser               # re-parses statement slices with JSqlParser (api may not)
    ├── ResolvedRule / SqlRuleParamsCodec / SqlRuleParamsValidator / SqlReviewFindingArgsCodec
    ├── rules/                           # SqlRule SPI, SqlRuleCatalog, the fourteen *Rule classes, shared walkers
    ├── persistence/entity/              # SqlReviewRulesetEntity, SqlReviewRuleConfigEntity, QuerySqlReviewFindingEntity
    ├── persistence/repo/                # the three Spring Data repositories
    └── web/                             # AdminSqlReviewRulesetController, SqlReviewController, SqlReviewExceptionHandler, model/
```

The SPI (`internal/rules/SqlRule`) is deliberately **internal, not `api`**: rules walk the
JSqlParser AST, and `api/` packages may not import a third-party type
(`ApiPackageDependencyTest`). Storage is in [docs/03-data-model.md → SQL review](03-data-model.md#sql-review-sqlreview-861--epic-860);
the engine internals in [docs/05-backend.md → Deterministic SQL review rules](05-backend.md#deterministic-sql-review-rules-sqlreview-862);
the endpoints in [docs/04-api-spec.md](04-api-spec.md#sql-review-rulesets-adminsql-review-rulesets-sql_review_manage-863);
the authorization in [docs/07-security.md](07-security.md); the UI in
[docs/06-frontend.md → SQL review rulesets](06-frontend.md#sql-review-rulesets-865-epic-860).

---

## 1. Severity semantics

Each rule in a ruleset runs at one of three severities (PG enum `sql_review_severity`):

| Severity | Evaluated? | Recorded and shown? | Effect on the workflow |
|---|---|---|---|
| `OFF` | no | no | none |
| `WARN` | yes | yes — editor, query detail, reviewer queue, break-glass retro-review, request-group detail | **none** |
| `BLOCK` | yes | yes | the query **can never auto-approve** — it is forced to `PENDING_REVIEW` |

**`BLOCK` escalates; it never rejects.** At the `PENDING_AI` decision point a `BLOCK` finding
suppresses all three paths that would otherwise approve without a person:

- a routing policy's `AUTO_APPROVE` action,
- the grant-covered fast path (#582 — an active JIT grant with `pre_approve_queries=true`),
- the review plan's own fast path (`requires_human_approval=false`, or `auto_approve_reads` on a
  low-risk read).

Everything else is untouched. A routing `AUTO_REJECT` still rejects — a block never softens a
rejection into a review. `REQUIRE_APPROVALS` / `ESCALATE` keep their approval arithmetic. A plan
that already required a human review changes nothing, and writes no extra audit row.

Severities are re-stamped at evaluation time from the resolved ruleset, so a finding persisted on a
query carries the severity that applied **when it was submitted**; changing a ruleset later does
not rewrite history.

---

## 2. Environments and ruleset resolution

Every datasource carries an optional **environment** — `datasources.environment`, PG enum
`datasource_environment`: `DEVELOPMENT` | `TEST` | `STAGING` | `PRODUCTION`, nullable. It is set on
the datasource form (create wizard and settings page) under the existing `DATASOURCE_MANAGE`
permission — it is datasource configuration, not policy. Existing datasources stay `NULL`.

A **ruleset** (`sql_review_rulesets`) is a name, a description, an `enabled` flag and either one
environment or none. Two partial unique indexes enforce **one ruleset per environment per
organisation, plus at most one organisation-wide default** (the row with `environment IS NULL`).

Resolution, for a query on a datasource:

```
datasource.environment
  → the ruleset bound to that environment      (if one exists — enabled or not)
  → else the organisation default              (environment IS NULL)
  → else no rules at all                       (applicable, empty result)
```

Two consequences worth knowing:

- **A disabled ruleset resolves to *no rules* and does not fall through.** Disabling the
  `PRODUCTION` ruleset never silently re-enables the organisation default on production.
- **Rulesets are sparse.** Every catalog rule is evaluated whether or not the ruleset names it — a
  `sql_review_rule_configs` row exists only to change a rule's severity (or turn it `OFF`) or to
  set its params. An unlisted rule runs at its built-in default severity (table below). The admin
  UI writes a row only when the severity differs from the default or the rule carries params.

---

## 3. Applicability

Every rule is a pure function of the JSqlParser AST — no schema introspection, no live catalog
read, no connection to the datasource. The catalog therefore covers the **in-process relational
dialects only**: `POSTGRESQL`, `MYSQL`, `MARIADB`, `ORACLE`, `MSSQL` and `CUSTOM`.

For any other `DbType` — every engine plugin (MongoDB, Couchbase, Redis, Cassandra, ScyllaDB,
Elasticsearch, OpenSearch, DynamoDB, Neo4j, Snowflake, BigQuery, Databricks), and any engine added
later — evaluation returns **`applicable: false` with zero findings**, before the query is parsed
or a ruleset is loaded. This is deliberately **not fail-closed**: an engine having no rule support
must never make its queries harder to approve than they are today. The editor lint switches itself
off for those engines, and no finding rows are written for them.

The gate is an explicit allow-list in `DefaultSqlReviewService`, not "is this engine managed by a
plugin" — a `DbType` without a connector manifest would otherwise be misclassified.

---

## 4. The rule catalog

Fourteen statement-safety rules, in catalog order. *Default* is the severity an unconfigured rule
runs at; *Category* is descriptive only and never affects evaluation. The **Example** column is
one statement that violates the rule and the English message it renders (`sqlreview.rule.<id>.message`;
every locale file carries all three keys per rule, parity-checked by `MessagesParityTest`).

| Rule id | Category | Default | Fires when | Example violation → message |
|---|---|---|---|---|
| `select_star` | PERFORMANCE | WARN | a top-level select body (the statement, each set-operation branch, each CTE body) has a `*` or `t.*` select item. Subqueries in FROM/WHERE are not inspected — `EXISTS (SELECT * …)` is idiomatic | `SELECT * FROM orders LIMIT 10` → *SELECT \* fetches every column; list the columns you need* |
| `missing_where_on_update` | STATEMENT_SAFETY | **BLOCK** | `UPDATE` with no `WHERE` | `UPDATE orders SET status = 'closed'` → *UPDATE on orders has no WHERE clause and rewrites every row* |
| `missing_where_on_delete` | STATEMENT_SAFETY | **BLOCK** | `DELETE` with no `WHERE` | `DELETE FROM sessions` → *DELETE on sessions has no WHERE clause and removes every row* |
| `where_always_true` | STATEMENT_SAFETY | **BLOCK** | the `WHERE` of a `SELECT` / `UPDATE` / `DELETE` is a tautology — `TRUE`, `NOT FALSE`, a literal compared to itself (`1 = 1`), a numeric comparison that holds (`2 > 1`), a column compared to itself (`x = x`) — or has one as a **top-level `OR` disjunct**. `AND`-ed tautologies are harmless and ignored. This is the rule that stops the two above being trivially defeated | `DELETE FROM sessions WHERE 1 = 1` → *The WHERE clause '1 = 1' is true for every row and filters nothing* |
| `missing_limit_on_select` | PERFORMANCE | WARN | a `SELECT` reading from a table with no `LIMIT` / `TOP` / `FETCH FIRST` (`LIMIT ALL` and a bare `OFFSET` do not count). A table-less `SELECT 1` is skipped | `SELECT id FROM orders` → *The SELECT has no LIMIT, TOP or FETCH FIRST and may return every row* |
| `order_by_without_limit` | PERFORMANCE | WARN | `ORDER BY` with no row limit — on a `SELECT`, or the MySQL-style `UPDATE … ORDER BY` / `DELETE … ORDER BY` | `SELECT id FROM orders ORDER BY created_at` → *ORDER BY with no row limit sorts and returns the whole table* |
| `cross_join` | PERFORMANCE | WARN | in any select body: an explicit `CROSS JOIN`; a `JOIN` with neither `ON` nor `USING` (not `NATURAL`, not `APPLY`); or a comma join that no column-to-column comparison in the `WHERE` correlates, judged per join | `SELECT o.id FROM orders o, customers c LIMIT 10` → *The join with customers has no join condition and produces a Cartesian product* |
| `leading_wildcard_like` | PERFORMANCE | WARN | `LIKE` / `ILIKE` (negated or not) whose pattern starts with `%`, anywhere in the statement | `SELECT id FROM users WHERE email LIKE '%@example.com' LIMIT 10` → *The pattern '%@example.com' starts with a wildcard and forces a full scan* |
| `drop_statement` | SCHEMA_CHANGE | **BLOCK** | `DROP TABLE` / `DROP SCHEMA` / `DROP DATABASE`, or `ALTER TABLE … DROP COLUMN` (one finding per column). `DROP INDEX` / `DROP VIEW` are left to `ddl_statement` | `DROP TABLE legacy_users` → *The statement drops TABLE legacy_users* |
| `truncate_statement` | SCHEMA_CHANGE | **BLOCK** | `TRUNCATE` — one finding per table | `TRUNCATE audit_staging` → *The statement truncates audit_staging* |
| `ddl_statement` | SCHEMA_CHANGE | WARN | any CREATE / ALTER / DROP / TRUNCATE (the proxy's DDL definition). Broader than the two above | `ALTER TABLE orders ADD COLUMN note TEXT` → *The statement is DDL (ALTER) and changes the schema* |
| `disallowed_function` | STATEMENT_SAFETY | **BLOCK** | a call to a banned function anywhere in the statement, matched on the unqualified, case-insensitive name (`pg_catalog.PG_SLEEP(5)` is caught by `pg_sleep`). Param **`names`**; absent or empty falls back to the built-in `pg_sleep`, `sleep`, `benchmark`, `load_file` | `SELECT pg_sleep(30)` → *The statement calls the disallowed function pg_sleep* |
| `protected_table` | DATA_PROTECTION | **BLOCK** | a referenced table matches a configured glob, tried against the normalised `schema.table` name **and** the bare table name (`audit_log` also matches `public.audit_log`); one finding per table. Param **`globs`**; absent or empty → no findings | with `globs: ["payroll.*"]`: `SELECT * FROM payroll.salaries LIMIT 5` → *The statement touches protected table payroll.salaries (matches payroll.\*)* |
| `dml_without_transaction` | STATEMENT_SAFETY | WARN | `INSERT` / `UPDATE` / `DELETE` submitted outside a `BEGIN … COMMIT` envelope (the parser's `transactional` flag) | `UPDATE orders SET status = 'closed' WHERE id = 7` → *The data change is not wrapped in a BEGIN ... COMMIT transaction* |

Things that follow from the table:

- **Three schema-change rules overlap by design.** A bare `DROP TABLE` yields a `drop_statement`
  `BLOCK` *and* a `ddl_statement` `WARN`. An admin who wants one signal per DDL keeps
  `ddl_statement` and turns the two specific rules `OFF` — or the reverse.
- **`DROP DATABASE` is listed for the spec but unreachable**: JSqlParser 5.3 does not parse it, so
  the proxy rejects it with HTTP 422 before any rule runs.
- **Findings carry a line number** (one-based, from the construct's AST node) for a single
  statement. Every member of a `BEGIN … COMMIT` envelope is re-parsed from a deparsed slice, so its
  findings carry `line_number = null` and are located by `statement_index` instead.
- **A rule that throws is skipped for that statement** and logged at WARN. A rule bug can never
  block or fail a query.

**Params.** `params` is a JSON object of string arrays keyed by the rule's declared param —
`{"names": [...]}` for `disallowed_function`, `{"globs": [...]}` for `protected_table`; the twelve
other rules take none. `SqlRuleParamsValidator` refuses a malformed ruleset **when it is saved**
(422 `SQL_REVIEW_RULESET_INVALID`): an unknown or duplicated rule id, params on a parameterless
rule, an undeclared key, a required list that is absent (unless the param has built-in defaults,
as `names` does), an empty list or a blank entry, or an entry outside the param's own
`value_pattern` (`[A-Za-z0-9_$*.-]` for globs, `[A-Za-z0-9_$.-]` for function names — ASCII
only). A row that slipped in by other means is degraded, not fatal: undecodable stored params keep
their severity and run with no params, an unknown rule id is skipped, both logged.

**Messages are never stored in English.** A finding is `rule_id` + `args` (`table`, `predicate`,
`pattern`, `function`, `glob`, `object_type`, `name`, `statement_type`); the text is resolved per
reader through `MessageSource` in the reader's locale, so the same finding reads in French to a
French reviewer. The evaluation endpoint renders in the request locale (`Accept-Language`).

---

## 5. Where evaluation happens

Evaluation is **synchronous, at submission, before the AI is asked**. That is the point: the
findings exist for the review decision even when AI analysis is skipped
(`ai_analysis_enabled=false`) or fails — exactly the cases where a deterministic rule is the only
signal there is. Three chokepoints call `SqlReviewService.evaluate` and hand the result to
`SqlReviewFindingService`, which replaces the owner's `query_sql_review_findings` rows wholesale
in the caller's transaction (and writes nothing for a not-applicable engine or a clean result):

| Chokepoint | Owner key | Notes |
|---|---|---|
| `DefaultQuerySubmissionService.submit()` | `query_request_id` | right after `query_requests` is written, before `QuerySubmittedEvent`. Replay, MCP submission, scheduled queries and recurring-series **parents** all pass through it |
| `DefaultBreakGlassService.breakGlassExecute()` | `query_request_id` | **records, never gates** — see §6 |
| `DefaultRequestGroupService.submit()` | `request_group_item_id` | every `QUERY` member of a request group (AF-501), on both the normal and the break-glass branch |

**The guard.** The decision itself lives in `workflow.internal.QueryDecisionEvaluator` — the pure
function the state machine applies and the access simulator replays — and takes the verdict as an
**input**, `List<String> blockingRuleIds`. `QueryReviewStateMachine` reads it from the persisted
findings at all three entry points (`onAiCompleted`, `onAiSkipped`, `onAiFailed`);
`DefaultAccessSimulationService` evaluates its hypothetical SQL read-only and shapes the result the
same way, so a `SQL_REVIEW: MATCH` in a simulation is exactly the block a real submission would hit.

| Path | Without a block | With a `BLOCK` finding |
|---|---|---|
| Routing `AUTO_APPROVE` | `APPROVED`, `routing_decision` written | **`PENDING_REVIEW`** at the plan's default threshold; the policy still wins and is still written to `routing_decision`, as `ROUTING_AUTO_APPROVE_SUPPRESSED` |
| Routing `AUTO_REJECT` | `REJECTED` | `REJECTED` — untouched |
| Routing `REQUIRE_APPROVALS` / `ESCALATE` | `PENDING_REVIEW` | unchanged, same approval arithmetic |
| Grant fast path (#582) | `APPROVED` under the grant | the covering grant is declined and the request falls through to the plan |
| Plan `requires_human_approval=false` / `auto_approve_reads` | `APPROVED` | **`PENDING_REVIEW`** |
| Plan requires review / no plan / AI failed | `PENDING_REVIEW` | `PENDING_REVIEW` — nothing to suppress |

The decision trace (AF-859) gains a **`SQL_REVIEW`** step between `EFFECTIVE_PERMISSION` and
`ROUTING_POLICIES`: `MATCH` with `blocking_rule_ids` / `blocking_count`, else `NO_MATCH`; each
suppressed stage reports it in its reason and a `sql_review_suppressed` detail.

**Request groups** apply the same rule in `GroupAiAnalysisListener.route()`: one member's `BLOCK`
forces the whole group to `PENDING_REVIEW` when no member plan already required a human, audited
once against the group with `blocking_item_ids`.

---

## 6. Documented exemptions

Named so a reviewer does not have to ask:

- **Break-glass / emergency access (AF-385).** Bypasses AI and review by design. Findings are
  still computed and **recorded** — they ride on the mandatory `break_glass_events` retro-review
  (`sql_review_findings` on the break-glass log and detail) for the admin who must acknowledge it —
  but they never block. The compensating controls (admin fan-out incl. PagerDuty,
  `QUERY_BREAK_GLASS_EXECUTED`, the admin-only acknowledgement) are unchanged. Emergency access
  stays an emergency path.
- **Recurring occurrences (#627).** A recurring series is evaluated **once, at series creation**
  (the parent goes through the normal submission chokepoint). Occurrence rows are inserted
  directly in `APPROVED` by `RecurringQueryRunJob` and are deliberately not re-evaluated.
- **Re-analysis.** `POST /queries/{id}/reanalyze` re-runs the AI, not the rules; the findings
  recorded at submission stand.
- **Engine plugins.** `applicable: false`, zero findings, never a block (§3).
- **Routing `AUTO_REJECT`.** Still rejects; a block never overrides a rejection.

---

## 7. Editor, admin and reviewer surfaces

**Live lint in the editor (#865).** No button. `hooks/useSqlReviewLint.ts` evaluates the draft the
author has paused on (400 ms trailing debounce) through the read-only `POST /sql-review/evaluate`,
only for a selected relational datasource and a non-blank draft under the 100 000-character limit.
Findings render twice: as CodeMirror **diagnostics** in `SqlEditor` (`@codemirror/lint` — gutter
markers and hover tooltips, one shared surface with the AI issues) and in the **findings strip**
under the editor (severity pill, `L{n}` or "Statement N", the backend-localized message).
Unparseable mid-keystroke SQL is a quiet "Can't parse this yet" hint, never a toast. **Submit stays
enabled on a `BLOCK`** — the tooltip says how many blocking findings will require human approval.
The request-group member drawer inherits both surfaces.

**`/admin/sql-review` (#865).** The ruleset list — name, environment pill or *Organization
default*, an effective-severity summary (`N block · N warn · N off` across the whole catalog),
an `enabled` switch, edit / delete — and a create / edit modal whose rules table is **driven by
`GET /sql-review/rules`**, never a client-side list, so a rule added on the backend appears without
a frontend change. Each row shows the localized name / description / category, a severity select
with the built-in default beneath it, and a tags input per declared param. The nav entry **SQL
review** sits in the **Security & Access** nav group, next to **Routing policies**. Requires
`SQL_REVIEW_MANAGE`.

**Datasource form.** An optional **Environment** select (create wizard and settings page):
*Development* / *Test* / *Staging* / *Production*, or *Not set (organization default rules)*.

**Reviewer surfaces**, all rendered through `SqlReviewFindingRenderer` in the caller's locale:

| Surface | Field |
|---|---|
| Query detail (`GET /queries/{id}`) | `sql_review_findings[]` — a "SQL review findings" card |
| Review queue (`GET /reviews/pending`) | `sql_review_blocking_count` — an "N block" pill in the AI-risk cell |
| Break-glass log and detail | `sql_review_findings[]` |
| Request-group detail | `sql_review_findings[]` per `QUERY` member |

No `NotificationEventType` was added — deliberately. A blocking finding is visible on the query
detail and in the reviewer queue, which is enough, and a new event type touches eight exhaustive
switches, a Thymeleaf template and fourteen message files.

---

## 8. REST surface

All under `/api/v1`; the full contracts are in
[docs/04-api-spec.md](04-api-spec.md#sql-review-rulesets-adminsql-review-rulesets-sql_review_manage-863).

| Method | Path | Who | What |
|---|---|---|---|
| `POST` | `/sql-review/evaluate` | any signed-in user who can see the datasource | `{datasource_id, sql}` → `{applicable, findings[]}` with localized messages. Persists nothing, writes no audit row. 404 (never 403) for an invisible datasource; 422 `INVALID_SQL` for unparseable SQL — never an empty, clean-looking list |
| `GET` | `/sql-review/rules` | `SQL_REVIEW_MANAGE` | the catalog in catalog order, localized: `rule_id`, `category`, `default_severity`, `name`, `description`, `params[]` (`key`, `required`, `defaults`, `value_pattern`) |
| `GET` / `POST` | `/admin/sql-review-rulesets` | `SQL_REVIEW_MANAGE` | list / create (`201` + `Location`) |
| `GET` / `PUT` / `DELETE` | `/admin/sql-review-rulesets/{id}` | `SQL_REVIEW_MANAGE` | read / full replace (a `rules` list replaces the config set wholesale) / delete (`204`; findings already recorded on queries are untouched) |

Errors: 404 `SQL_REVIEW_RULESET_NOT_FOUND`, 409 `SQL_REVIEW_RULESET_ENVIRONMENT_CONFLICT` /
`SQL_REVIEW_RULESET_DEFAULT_CONFLICT` (pre-checked; a raced unique violation maps to the same
code), 422 `SQL_REVIEW_RULESET_INVALID`. The datasource's `environment` is written through the
normal datasource endpoints under `DATASOURCE_MANAGE`.

---

## Audit & permissions

- **`SQL_REVIEW_MANAGE`** — in `PermissionGroup.WORKFLOW_ADMIN` beside `ROUTING_POLICY_MANAGE`;
  held by the system `ADMIN` role only (seeded by `V171`). Gates ruleset CRUD and the rule catalog.
  The evaluation endpoint is deliberately **not** behind it: anyone who can see a datasource may
  lint against it, exactly as `POST /queries/analyze` and `POST /queries/dry-run` are authorized —
  and a datasource the caller cannot see is a 404, so the endpoint cannot be used to learn which
  ruleset or which protected-table globs a hidden datasource carries.
- **`SQL_REVIEW_RULESET_CREATED` / `_UPDATED` / `_DELETED`** — one row per admin mutation, written
  from the controller so `ip_address` / `user_agent` come from the live request; resource type
  `sql_review_ruleset`.
- **`SQL_REVIEW_BLOCKED`** — written by the state machine **only when a block actually changed the
  outcome**: `actor_id` null, `trigger=sql_review`, `blocking_rule_ids`, `suppressed_paths` (in
  evaluation order), `matched_policy_id` when routing was the path. A `WARN`, a rejection, an AI
  failure, or a plan that already required review writes no row — the findings are still on the
  detail. For a request group the row is against the group with `blocking_item_ids`.

---

## Out of scope (by design)

- **Naming and schema-design conventions** (table/column naming regexes, banned column types,
  require-primary-key, index-count ceilings) — they need introspected schema and belong in a
  follow-up.
- **Non-relational engines** — rules are JSqlParser-derived; plugin engines report
  `applicable: false`.
- **New `NotificationEventType` values.**
- **Org-defined environments** — the set is a fixed four-value enum, not a user-managed table.
- **Per-occurrence re-checks for recurring series.**
- **Hard rejection at submit** — `BLOCK` escalates to a human; it never rejects.
