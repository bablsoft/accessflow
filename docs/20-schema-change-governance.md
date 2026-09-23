# 20 — Schema Change Governance (epic #870)

AccessFlow governs three request surfaces: database queries, outbound API calls (`apigov`) and
CI/CD deployments (`deploygov`). **Schema changes fall between them.** A DDL statement can be
submitted as an ordinary query — the proxy classifies `QueryType.DDL` and the per-datasource
`can_ddl` flag gates it — but that is one statement, against one datasource, decided in
isolation. A **schema change set** is the missing unit: an ordered list of schema statements
**authored once**, validated at save time, reviewed once, and **promoted** along a `deploygov`
pipeline's environment ladder with the guarantee that production only ever receives a change
that already succeeded in staging — followed by a scheduled **drift** job that says when an
environment's live schema has wandered away from where it is supposed to be.

It lives in the `schemachange` Spring Modulith module (`com.bablsoft.accessflow.schemachange`),
laid out like `deploygov`. It depends on `core`, `deploygov`, `proxy`, `sqlreview`, `requestgroups`,
`audit` and `security` (for `JwtClaims`) through their `api/` and `events/` packages; nothing
depends on it yet, so the graph stays acyclic. It composes two
primitives the codebase already has: **deployment environments** (the ordered promotion targets
under a pipeline, each optionally bound to the datasource its schema changes land on, #877)
and **request groups** (a bundle of ordered members with aggregated AI analysis, union-of-approvers
review and an ordered executor — the shape a promotion takes, #880).

> **Delivery status.** In progress for the v2.7 milestone: the persistence foundation (#878), the
> **authoring half** — change-set CRUD, the DDL validation gate, freeze-on-promotion and the
> `/schema-change-sets` REST surface (#879) — **promotion** with the ladder gate, freeze-window
> check, request-group wiring and the post-apply snapshot (#880), and the **drift half** — the
> opt-in scan configuration, the scheduled job, the diff and the read API (#881) — are on `main`.
> The notification fan-out (#882), the web UI (#883) and the website sweep (#884) follow. Promotion
> and drift audit are in place; notifications are not, so a promotion waiting for approval and a
> drift finding nobody has opened are both currently silent.

> **The one sentence to remember.** A change set is a *set of schema statements*, not a
> transaction: each statement runs on its own, autocommit, so there is **no rollback at all** —
> which is exactly why every statement is checked before it is stored and the list is frozen the
> moment a promotion exists.

---

## Module layout

```
com.bablsoft.accessflow.schemachange/
├── api/
│   ├── SchemaChangeSetService            # list / get / create / update / replaceStatements / delete (#879)
│   ├── SchemaChangePromotionService      # promote / get / listForChangeSet / cancel (#880)
│   ├── SchemaDriftService                # drift read API + acknowledge + scan-now (#881)
│   ├── SchemaDriftConfigService          # per-pipeline opt-in configuration (#881)
│   ├── SchemaChangeSetView, SchemaChangeSetStatementView, SchemaChangeStatementFinding
│   ├── Create/UpdateSchemaChangeSetCommand, SchemaChangeSetStatementInput, SchemaChangeSetListFilter
│   ├── SchemaChangeSetStatus, SchemaChangePromotionStatus, SchemaDrift* enums
│   └── SchemaChangeException + one subclass per documented error code
├── events/SchemaChangePromotionStatusChangedEvent   # every promotion transition (#880)
└── internal/
    ├── config/SchemaChangeProperties     # accessflow.schemachange.max-statements
    ├── DefaultSchemaChangeSetService     # org-scoped CRUD, freeze + archive guards, checksum
    ├── DefaultSchemaChangePromotionService         # the promotion gate + request-group wiring (§6)
    ├── SchemaChangePromotionStatusListener         # projects the group's status back (§6)
    ├── SchemaChangePromotionStatusMapper           # the status table + the monotonic guard
    ├── SchemaChangeAuditWriter           # swallowing audit wrapper, the DeploygovAuditWriter shape
    ├── SchemaChangeStatementGate         # the validation gate (§2)
    ├── SchemaChangeStatementScanner      # JDK-only envelope / multi-statement pre-checks
    ├── SchemaChangeChecksum              # SHA-256 over the ordered, normalised statements
    ├── SchemaChangeSetSpecifications     # the nullable-filter listing (criteria API, not JPQL)
    ├── SchemaDriftScanService            # the scan chokepoint; runScan never throws (§7)
    ├── SchemaDriftScanStore              # the scan's REQUIRES_NEW boundaries, kept out of the service
    ├── SchemaDriftScanCoordinator        # one pipeline's config -> its per-environment scans, and the one stamp
    ├── SchemaDriftScanContextResolver    # resolves a manual scan's 404s before anything is written
    ├── SchemaDriftBaselineResolver       # the three baseline modes and every reason code (§7)
    ├── SchemaDriftDiffer                 # the pure diff: never descends past an absence (§7)
    ├── SchemaDriftFindingReconciler      # the finding lifecycle + the resolve-eligibility gate (§7)
    ├── SchemaDriftScanReason             # the stored, machine-readable reason codes
    ├── SchemaDriftSpecifications         # criteria-API listings, never JPQL against a PG enum
    ├── SchemaDriftPageAdapter            # PageRequest <-> Pageable (core's adapter is module-private)
    ├── scheduled/SchemaDriftJob          # @Scheduled + @SchedulerLock, drains schema_drift_configs
    ├── persistence/{entity,repo}         # V178 tables (#878), V180 schema_drift_configs + V181 finding version (#881)
    └── web/                              # SchemaChangeSetController, SchemaDriftController + records
```

The tables — `schema_change_sets`, `schema_change_set_statements`, `schema_change_set_promotions`,
`schema_drift_scans`, `schema_drift_findings` — and their five PG enums are documented in
[03-data-model.md → Schema change governance](03-data-model.md#schema-change-governance-schemachange-878--epic-870).
Every cross-module reference (organization, pipeline, environment, datasource, request group,
user) is a bare UUID with no foreign key, the `deploygov` convention, so a change set and its
promotion history survive deletion of what they name.

## 1. Authoring a change set

A change set belongs to one **pipeline** (`deploygov`) and has a `name` unique under that pipeline,
a `description`, a `status` and an ordered list of **statements**. The whole surface is gated by
one permission, **`SCHEMA_CHANGE_MANAGE`** (`WORKFLOW_ADMIN` group, granted to `ADMIN` only by
`V179`), and is organization-scoped: an id from another organization — change set or pipeline —
reads as `404`, never as `403`.

| Status | Meaning | Who sets it |
|---|---|---|
| `DRAFT` | Being authored. | `POST` creates here. |
| `ACTIVE` | Promoted at least once. | The promotion service (§6) — never the update endpoint. |
| `ARCHIVED` | Retired; can no longer be promoted or have its statements edited. | `PUT /{id}` with `status: ARCHIVED`. |

`PUT /{id}` updates `name` / `description` / `status` with null-means-unchanged semantics. The
only status an author may set by hand is `ARCHIVED` (from `DRAFT` or `ACTIVE`; the same value is a
no-op); any other target is `409 SCHEMA_CHANGE_SET_INVALID_STATUS_TRANSITION`. Un-archiving is
deliberately not offered in v1. Archiving protects the **statements** (`PUT …/statements` is
`409 SCHEMA_CHANGE_SET_ARCHIVED`), not the row: an archived set that was never promoted can still
be deleted, and name and description stay editable on archived and frozen sets alike. What refuses
`DELETE` is the freeze (§3), not the status.

`PUT /{id}/statements` replaces the **whole** ordered list (an empty list clears it). Replacement is
delete-then-reinsert — the `request_group_items` precedent — but through a bulk JPQL delete rather
than a derived one, which is what sidesteps the `UNIQUE (change_set_id, sequence_order)` reordering
problem: a derived entity delete would be flushed *after* the new inserts and trip the constraint
on the reused positions.

## 2. The validation gate

Every statement passes the gate, in list order, before anything is stored — on `POST` (initial
statements) and on `PUT …/statements`. The gate lives in `SchemaChangeStatementGate` and runs five
steps.

**Targets.** The pipeline's environments (`DeploymentEnvironmentLookupService.listByPipeline`, in
`sort_order`) that bind a `datasource_id` are the change set's targets, resolved through
`DatasourceAdminService.getForAdmin` inside the organization. A non-empty statement list on a
pipeline with no bound environment is `409 SCHEMA_CHANGE_SET_NO_TARGET_DATASOURCE`; an empty list
needs no target at all, so a set can be created before its ladder is wired. The binding is a bare
id with no foreign key, so a deleted datasource stays bound — that reads as
`409 SCHEMA_CHANGE_SET_TARGET_DATASOURCE_MISSING` (rebind or clear the environment), never as a
skipped rung: a rung the gate cannot see is one the ladder gate cannot count either. **Every bound rung is
consulted**, not only the entry rung: a change set walks the whole ladder, so a rule that only
blocks on `PRODUCTION` is caught at authoring rather than at the last promotion.

**Shape.** Two JDK-only pre-checks refuse text that could not be one autocommit statement: a
leading `BEGIN` / `START TRANSACTION` (after whitespace and comments) is
`422 SCHEMA_CHANGE_SET_STATEMENT_TRANSACTION_ENVELOPE`, and a `;` outside string literals, quoted
identifiers, comments and `$tag$ … $tag$` blocks — a trailing terminator is fine — is
`422 SCHEMA_CHANGE_SET_STATEMENT_MULTIPLE`. The proxy's own parser refuses both too
(`error.sql_multiple_statements`, `error.transaction_ddl_not_allowed`), but its messages describe
a *query*; these name the change-set context. `DO $$ BEGIN … END $$` starts with `DO`, so a
procedural block is not mistaken for an envelope; on SQL Server a leading `BEGIN … END` block *is*
read as one and must be unwrapped.

**Parse.** The statement is parsed through the engine-aware `proxy.api.QueryParser` for the
`DbType` of every distinct target datasource — JSqlParser for the relational engines, the plugin's
own parser for an engine-managed `DbType` such as MongoDB, so a change set targeting a
non-relational rung is parsed and classified by that engine and the parser-gap list below does
not describe it. A parse failure is
`422 SCHEMA_CHANGE_SET_STATEMENT_INVALID`, with the parser's own already-localized reason as the
second argument of the message ("Statement 3 of the change set could not be parsed: …").

**Classification — the decision this part had to make.** The proxy's DDL classifier is narrower
than real-world migrations: only the JSqlParser `create` / `alter` / `drop` / `truncate` statement
packages are `QueryType.DDL`, and everything else the parser accepts — `COMMENT ON`, `GRANT`,
`ALTER TYPE … ADD VALUE`, `REFRESH MATERIALIZED VIEW`, `MERGE`, `CALL`, and any statement JSqlParser
only recognises as *unsupported* — lands in `OTHER`. A strict `== DDL` gate would have refused
AccessFlow's own `V91__add_auditor_role.sql` (`ALTER TYPE … ADD VALUE`). The gate therefore
implements option **(a)**: a statement is admitted when it is **not** classified `SELECT` /
`INSERT` / `UPDATE` / `DELETE`; those four are `422 SCHEMA_CHANGE_SET_STATEMENT_DML` with the
classification on `queryType`. `DDL` *and* `OTHER` are admitted, and the stored `query_type`
records which. What this buys and what it costs, stated plainly:

- **Admitted that a strict gate would refuse:** `COMMENT ON`, `GRANT`, `ALTER TYPE … ADD VALUE`,
  `REFRESH MATERIALIZED VIEW`. (`CREATE FUNCTION` is classified `DDL` by JSqlParser and would have
  passed either way.)
- **Admitted that is not schema-only:** `MERGE`, `UPSERT`, `CALL`, session settings in the
  `SET name = value` form, and — because JSqlParser 5 falls back to an *unsupported statement*
  rather than an error for a good deal of unrecognised text — some malformed input. The gate is "not DML", not "is DDL": a change set cannot smuggle a
  `DELETE`, but a data backfill written as `MERGE` passes authoring. The controls that catch it are
  the ones every promotion already has (#880): per-member AI analysis, the deterministic SQL review
  rules at the request-group chokepoint, and human review. A strict per-organization mode is a
  possible follow-up; it was not built because it would make the feature unusable on real
  migrations before anyone asked for it.
- **Still refused, because the parser cannot read them** (checked against JSqlParser 5.3 at the
  time of writing): `DO $$ … $$` blocks, `REVOKE`, `CREATE EXTENSION`, `CREATE INDEX CONCURRENTLY`
  and the `SET name TO value` spelling. These are parser gaps, not policy — they surface as
  `…_STATEMENT_INVALID`, and widening the parser is a separate piece of work.

**Deterministic SQL review.** Finally the statement is evaluated by `sqlreview.api.SqlReviewService`
against the ruleset resolved for **each** target datasource (its environment's ruleset, else the
organization default, else none — [19-sql-review.md](19-sql-review.md)). The rule catalog covers
the relational engines only: a non-relational target answers *not applicable* with no findings, so
everything in this step — including the `drop_statement` default below — holds for relational
rungs and is simply absent for a plugin-engine rung. A `BLOCK` finding on any
statement against any target refuses the save with `422 SCHEMA_CHANGE_SET_STATEMENT_BLOCKED`,
whose body carries every blocking finding (`findings[]`, each with `statement_index`,
`datasource_id`, `rule_id`, `severity`, `line_number` and a `message` rendered into the caller's
locale). `WARN` findings do not refuse; they ride on the write response as `review_warnings` and
are **not persisted** — every read returns the list empty, and a later ruleset change is not
retro-applied. There is deliberately no second DDL-policy surface: `drop_statement`,
`truncate_statement`, `ddl_statement` and `protected_table` already exist in the catalog, and
their defaults matter here — once *any* ruleset resolves for a target, an unconfigured
`ddl_statement` runs at `WARN` (so every DDL statement warns) and `drop_statement` /
`truncate_statement` at `BLOCK` (so a `DROP TABLE` is refused until an admin lowers the rule).

> Why refuse at authoring when the rule chapter insists `BLOCK` *escalates, never rejects*? Because
> there is nobody to escalate to: a change set being saved has no reviewer, no request and no
> queue. The promotion (#880) submits the statements as request-group members and re-evaluates them
> at the normal chokepoint, where `BLOCK` behaves exactly as documented — it forces a human. The
> authoring refusal only guarantees that what reaches that chokepoint was never a statement the
> organisation had already said it would not auto-approve.

## 3. Freeze and checksum

Statements are mutable only while **every** promotion of the change set is absent, `FAILED` or
`CANCELLED`. Any `PENDING`, `IN_REVIEW`, `APPROVED`, `APPLIED` or `PARTIALLY_APPLIED` promotion
freezes both the statement list and the row itself: `PUT …/statements` and `DELETE` answer
`409 SCHEMA_CHANGE_SET_FROZEN` — archive the set instead. The probe is one query
(`existsByChangeSet_IdAndStatusIn` over the freezing set), evaluated inside the same transaction
as the write. `ARCHIVED` sets refuse statement edits separately (`409 SCHEMA_CHANGE_SET_ARCHIVED`).

`statements_checksum` is the SHA-256 hex over the ordered statements — each **normalised** (trimmed,
one trailing `;` removed) — joined by a newline, and `null` for a set without statements. The
normalised form is what is stored in `sql_text`, so the promotion service recomputes the identical
value from the rows it copies onto each promotion as evidence of exactly what was sent; without
that, "applied to staging" would mean nothing by the time production is asked. The same ordered
statements always produce the same checksum; swapping two changes it.

## 4. The statement cap

`ACCESSFLOW_SCHEMACHANGE_MAX_STATEMENTS` (default `50`) bounds the statement count of a change set,
enforced in the service as `400 SCHEMA_CHANGE_SET_STATEMENT_LIMIT` with `limit` and `actual`. It is
a cost control, not a style rule: a promotion analyses every statement as a request-group member
and each analysis re-introspects the datasource, so an uncapped set multiplies LLM calls and
customer-database round-trips by statement count *and* by environment count. It is a service-side
check rather than a Bean Validation `@Size` because a `@Size` cannot read a runtime property.

## 5. REST surface

Documented in [04-api-spec.md → Schema Change Governance](04-api-spec.md#schema-change-governance-879-880-881-epic-870):
`POST /schema-change-sets` (201), `GET /schema-change-sets` (page; `pipeline_id`, `status`),
`GET /{id}`, `PUT /{id}`, `PUT /{id}/statements`, `DELETE /{id}` (204). Wire names are snake_case;
`ProblemDetail` extension properties (`statementIndex`, `queryType`, `currentStatus`, …) are
camelCase like every other module's. The listing filter is a criteria-API `Specification` that
adds the `status` predicate only when set — a JPQL `(:status is null or s.status = :status)`
against the PG enum column fails with "could not determine data type of parameter".

## 6. Promotion (#880)

A **promotion** is one attempt to land a change set on one environment. It is created as a
`schema_change_set_promotions` row and executed as a `requestgroups` group — one `QUERY` member
per statement, in authored order, `continue_on_error = false` — which is what buys per-statement
AI analysis, the union-of-approvers review, the ordered executor and the existing audit trail
with no new member kind. The REST surface is
[04-api-spec.md → Promotions](04-api-spec.md#promotions-880).

### The gate

Twelve checks, in a fixed order, every one failing closed; nothing is written until all of them
pass. The order is pinned by `DefaultSchemaChangePromotionServiceTest` because it is observable —
a caller learns which check refused first.

1. **Change set** — resolved org-scoped: a foreign id is `404`, never `403`.
2. **Not archived** — `409 SCHEMA_CHANGE_SET_ARCHIVED`.
3. **Has statements** — `409 SCHEMA_CHANGE_SET_EMPTY`. An empty set has a `null` checksum and
   nothing to attest, and `statements_checksum` on the promotion row is `NOT NULL`.
4. **Environment on this pipeline** — through `DeploymentPipelineLookupService.findEnvironment`,
   so an environment of another pipeline or another organization is `404`.
5. **Environment binds a datasource** — a deploy-only rung is `422`; there is nothing to apply to.
6. **That datasource still exists** — the binding is a bare id with no FK, so a deleted datasource
   stays bound and is refused, never skipped.
7. **`can_ddl` on the target, for the promoting user** — see below.
8. **The ladder is a ladder** — the pipeline's `sort_order` values must be distinct. #877 made the
   column unique, but the gate asserts it anyway: over an all-equal column "every lower-ordered
   environment" is the empty set and the next check would pass vacuously, which is the one failure
   mode this feature must not have.
9. **The ladder gate** — every environment ("rung") with a lower `sort_order` **that binds a datasource**
   must record an `APPLIED` promotion of this change set. Unbound rungs are skipped (they are
   deploy-only); the first blocking rung is named in the refusal. `APPLIED` is the only status
   that counts.
10. **Freeze windows** — through `DeploymentFreezeLookupService`. Both `HOLD` and `REJECT` refuse a
    promotion, which is also what makes it fail closed: an unevaluable window degrades to `HOLD`.
11. **Review is enforceable** — see below.
12. **No open promotion** for this (change set, environment) pair. Pre-checked and, when two
    promoters race, translated from the `uq_schema_change_set_promotions_open` partial unique
    index to the same `409`. The row is flushed *before* the group is created, so a lost race
    never leaves an orphan request group.

### `can_ddl`, and why the group is created as an admin

Promotion requires an active `can_ddl` grant on the target datasource for the promoting user —
**for everyone, including organization admins**. It is checked here, against
`core.api.DatasourceUserPermissionLookupService.findFor`, which is a pure grant merge with no
admin concept.

The group is then created and submitted with `admin = true`, deliberately.
`requestgroups`' own per-member check exempts `QUERY_ADMIN` holders — the bypass this module must
not inherit — and, for a statement classified `OTHER` (`GRANT`, `COMMENT ON`,
`ALTER TYPE … ADD VALUE`), it would demand `can_write`, a DML permission that says nothing about
schema authority. So `schemachange` owns the authorization decision and applies it uniformly to
every statement, which is strictly stronger than what delegating would have produced. The `admin`
flag has no other effect: it is not persisted, and routing, review and execution never read it.

### Review strictness — a property of the datasource, not the environment

A group's review plan is resolved from the **target datasource** alone
(`GroupReviewPlanResolver` → `ReviewPlanLookupService.findForDatasource`). The environment's
`require_review`, `required_approvals` and `review_plan_id` are never consulted on this path, and
a datasource with no plan makes the group **auto-approve**. Left alone, an environment marked
`require_review = true` and bound to a plan-less datasource would apply DDL to production within a
minute, unreviewed.

Rather than document that as a caveat, promotion refuses it: if the environment requires review
and the target datasource has no review plan requiring human approval, the promotion is
`422 SCHEMA_CHANGE_PROMOTION_REVIEW_UNENFORCEABLE`. The fix is an admin's choice — attach a plan
that requires approval to the datasource, or clear `require_review` on the environment. What is
still true, and worth stating plainly: **the number of approvals and the approver set come from
the datasource's plan**, so a per-environment `required_approvals` override is not honoured by a
promotion.

### How an approved promotion actually runs

Approval does not execute anything: the group review service moves the group to `APPROVED` and
stops, and `ScheduledGroupRunJob` only picks up groups with `scheduled_for` set. A promotion
submitted with a null `scheduled_for` would therefore be approved and then sit forever.

Promotion submits the group with **`scheduled_for = now`**, so the existing ShedLock-guarded job
runs it on its next tick — at most `ACCESSFLOW_REQUESTGROUPS_RUN_POLL_INTERVAL` (default `PT1M`)
after approval. The alternative — listening for the `APPROVED` transition and calling `execute`
directly — was rejected because there is no event-publication registry in this deployment: a
process that dies between the commit and the async listener would lose the trigger and strand the
promotion. The job re-reads the database every tick and cannot lose it.

The one cost: `requestgroups` only allows cancelling an `APPROVED` group whose `scheduled_for` is
still in the future, so **an `APPROVED` promotion can no longer be cancelled**. `PENDING` and
`IN_REVIEW` promotions can.

### Status projection

`SchemaChangePromotionStatusListener` consumes `requestgroups.events.RequestGroupStatusChangedEvent`
and projects it:

| Group status | Promotion status |
|---|---|
| `PENDING_REVIEW` | `IN_REVIEW` |
| `APPROVED` | `APPROVED` |
| `EXECUTED` | `APPLIED` |
| `PARTIALLY_EXECUTED` | `PARTIALLY_APPLIED` |
| `FAILED` | `FAILED` |
| `REJECTED` / `TIMED_OUT` / `CANCELLED` | `CANCELLED` |
| `DRAFT` / `PENDING_AI` / `EXECUTING` | *(ignored)* |

Events for groups this module did not create are ignored. Two implementation details are load
bearing:

- It is **not** an `@ApplicationModuleListener`. The group's execution transitions are published
  from the scheduled job with no surrounding transaction, and a plain after-commit listener never
  sees those — the promotion would stop at `APPROVED`. The listener is
  `@Async @Transactional(REQUIRES_NEW) @TransactionalEventListener(AFTER_COMMIT, fallbackExecution = true)`,
  the `WorkflowMetricsListener` precedent. It stays asynchronous on purpose: in the fallback path
  a synchronous handler would run inline inside the group executor, where a failure could strand
  the group mid-run. Its whole body is wrapped in a catch-all that logs and continues.
- Transitions are **monotonic**. Events arrive asynchronously from two different threads and can
  reorder, so a promotion only ever moves forward and a terminal status is final. The row is read
  under a pessimistic lock so two listener threads and the cancel endpoint serialise on it.

### The post-apply snapshot

Once the `APPLIED` transition is **committed**, a second listener
(`SchemaChangePromotionSnapshotListener`) introspects the target through
`DatasourceAdminService.introspectSchemaForSystem` and stores the `DatabaseSchemaView` as JSON in
`schema_snapshot` with `snapshot_taken_at`. This is the `PROMOTION_SNAPSHOT` drift baseline #881
reads.

It is a separate listener on purpose: introspection opens a connection to the customer database,
and doing that inside the projection transaction would hold the promotion row's write lock and an
application connection across remote I/O — and would keep the applied status invisible to every
reader until the introspection finished. The follow-up is idempotent (it writes only while the
promotion is still `APPLIED` with no snapshot), so a promotion can legitimately read `APPLIED`
with a null snapshot for a moment, or permanently if the target was unreachable.

The honest caveat: this is the schema **as introspected shortly after** the DDL landed, not the
schema the DDL produced. Anything changed out of band in between — including by another
promotion to the same datasource — is baked into the baseline. That is why the timestamp is always
recorded, and why the snapshot is taken as close to the transition as possible. An introspection
failure (an unreachable target) leaves the snapshot null and the transition intact: losing the
baseline is recoverable, losing the status is not.

On `FAILED` / `PARTIALLY_APPLIED` the first failed member's message is copied onto
`error_message` — the group itself never records one.

### Known gaps

Two are worth knowing before relying on this path, and both are candidates for follow-up work
rather than defects in the gate:

- **The freeze window is evaluated at submission, not at execution.** A review-gated promotion can
  sit in `PENDING_REVIEW` for hours; if a freeze is declared in the meantime, approval still
  releases it to the run job. `deploygov`'s own gate re-evaluates the freeze at release time, and
  this path has no equivalent because the executor belongs to `requestgroups`.
- **A lost status projection is not retried.** The listener has no event registry behind it, so an
  exception (logged at `ERROR`) or a JVM restart between the group's commit and the async task
  leaves the promotion in a non-terminal status *after* its DDL may already have run — which
  freezes the change set and blocks every higher rung, with no in-module repair.

A `PARTIALLY_APPLIED` promotion is likewise a dead end by design: it freezes the change set (it is
not `FAILED` or `CANCELLED`) while the ladder counts only `APPLIED`, so the set can neither be
edited nor advanced. Half-migrated state is a human problem; author a new change set for the
remainder.

### Cancelling

`POST /schema-change-promotions/{id}/cancel` cancels a non-terminal promotion by cancelling its
group. `requestgroups` only lets the **submitter** cancel, so the call is made as the promoter;
the resulting `REQUEST_GROUP_CANCELLED` audit row therefore names the promoter rather than the
person who clicked cancel. The `SCHEMA_CHANGE_PROMOTION_CANCELLED` row written here names the real
actor and carries `cancelled_on_behalf_of_submitter: true`, so the two are reconcilable.

## 7. Schema drift (#881)

> ⚠️ **Read this before trusting a clean drift report.** Drift compares the engine-neutral
> `DatabaseSchemaView` every introspector produces, which carries only **schemas, tables, columns
> (name, type, nullable, primary key) and foreign keys**. Everything else is invisible to it:
>
> - **Not modelled at all:** indexes, `CHECK` and `UNIQUE` constraints, defaults, identity columns,
>   sequences, triggers, functions, comments, collations, partitioning, column order.
> - **Type precision:** the relational introspector reads `TYPE_NAME` alone, so
>   **`VARCHAR(50) → VARCHAR(255)` is undetectable** on most drivers, as is any precision or scale
>   change.
> - **Views:** the relational introspector requests `TABLE` only, so views are invisible there and
>   swapping a table for a view of the same name reads as a table *drop*. Snowflake, BigQuery and
>   Databricks *do* list views, as tables — so on those engines the same swap reads as no drift at
>   all.
> - **Case:** schema, table, column and type names are compared case-insensitively, because driver
>   case conventions differ by engine and server setting. The flip side is that renaming `Orders` to
>   `orders` in PostgreSQL is a real change drift does not report, and two tables whose names differ
>   only by case are treated as one.
> - **Foreign keys:** a `ForeignKey` is one record per column pair with no constraint name and no
>   target schema, so a composite key is indistinguishable from independent single-column ones, and a
>   cross-schema reference is ambiguous by target name alone.
>
> "No drift" means "nothing drifted in the fields we can see", and nothing stronger.

A scheduled job introspects each schema-bound environment, diffs it against a baseline, and records
findings an admin can acknowledge. It is deliberately **a job that stores findings**, not a read-time
computation like `deploygov`'s version drift (#742): introspection opens connections to customer
databases and cannot sit on a request path.

**Drift never writes.** It introspects and records. There is no corrective statement, no
auto-remediation endpoint, and there will not be one — a tool that both detects and silently fixes
schema divergence is a tool that can destroy data without a review.

### Turning it on

Scheduled scanning is **opt-in per pipeline and disabled by default**. One `schema_drift_configs` row
per pipeline carries `enabled`, the baseline mode, the designated baseline environment and a
per-pipeline `scan_interval_hours`; absence of a row means the same thing as `enabled = false`.

That the job *drains a table* rather than enumerating pipelines is not a stylistic echo of
`discovery`: `deploygov` exposes no cross-organization pipeline or environment listing —
`DeploymentPipelineLookupService` and `DeploymentEnvironmentLookupService` are both scoped to a
pipeline the caller already knows — so an opt-in row is the only thing that makes a scheduled scan
discoverable at all. Defaulting it off is the second half: drift opens connections to customer
databases on a timer, and an upgrade must never start doing that on an estate nobody asked it to.

Each enabled pipeline scans **every** environment that binds a datasource; deploy-only rungs are
skipped. The pipeline's `last_scan_at` and `last_scan_error` are stamped **once**, after the whole
ladder has been visited — the configuration is per pipeline but scans are per environment, and a
per-scan stamp would let one environment restart every sibling's interval and overwrite a sibling's
failure with its own success. A run in which another replica was holding one of the environments is
not stamped at all, so the pipeline stays due and the next tick retries it. `last_scan_error` names
every environment whose scan *failed*; an inapplicable engine or a missing baseline is a
configuration state, recorded on the scan row but not a failure of the run. An interval shorter than
the job's own poll (`ACCESSFLOW_SCHEMACHANGE_DRIFT_POLL_INTERVAL`, six hours by default) behaves like
the poll.

The opt-in governs the **scheduler only**. *Scan now* works on any schema-bound environment the
caller can see, configured or not (an unconfigured pipeline uses `PREVIOUS_ENVIRONMENT`), and it
never stamps the configuration, so it cannot postpone a scheduled run.

### Which engines can be diffed at all

| | Engines |
|---|---|
| **Catalog-backed** (diffed) | PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, `CUSTOM`, Cassandra, ScyllaDB, Elasticsearch, OpenSearch, Snowflake, BigQuery, Databricks |
| **Sampling** (`applicable = false`) | MongoDB, Redis, Couchbase, DynamoDB, Neo4j |

The five samplers read data rather than a catalog — 50 documents per collection, at most 1000 Redis
keys with a single sample key per prefix, `LIMIT 50` per Couchbase collection, a 50-row DynamoDB
`Scan` for non-key attributes, a server-side Neo4j graph sample. Two consecutive introspections of an
*unchanged* database can legitimately differ, so diffing them would flap forever. They record
`applicable = false` with zero findings, and **no connection is opened at all** — deliberately
distinct from an applicable scan that found nothing.

`SchemaDriftScanService.DETERMINISTIC_ENGINES` is an **allow-list**, so a `DbType` added later is
not applicable until somebody verifies its introspector. That is the safe direction to be wrong in,
and it mirrors `sqlreview`'s stated invariant. Note that engine-managed is not the same as sampling:
Cassandra, Elasticsearch, Snowflake, BigQuery and Databricks are plugins that read real catalogs, so
`QueryEngineCatalog.isEngineManaged` is the wrong test and must not be substituted.

### The three baselines

| Mode | Compares against | Opens a second connection |
|---|---|---|
| `PREVIOUS_ENVIRONMENT` *(default)* | the adjacent lower rung of the ladder **that binds a datasource** | yes |
| `BASELINE_ENVIRONMENT` | an admin-designated reference environment on the same pipeline | yes |
| `PROMOTION_SNAPSHOT` | the schema introspected right after the **newest** `APPLIED` promotion *here* | no |

"Adjacent" means adjacent **among bound rungs**: a deploy-only environment in the middle of the
ladder is skipped, not treated as a wall — the same rule the promotion ladder gate uses (§6).

The baseline is resolved **before** the scanned database is contacted, so a rung with nothing to
compare against — the lowest one in `PREVIOUS_ENVIRONMENT`, the designated one in
`BASELINE_ENVIRONMENT`, one with no snapshot yet — records its reason without opening a connection.

`PROMOTION_SNAPSHOT` is the mode that catches **out-of-band** changes, since it compares an
environment against its own last known-good state rather than against a sibling. It only ever uses
the **newest** applied promotion. If that promotion has no snapshot — it is taken asynchronously
just after the apply, and never if the target was unreachable then — the scan records
`BASELINE_SNAPSHOT_MISSING` rather than falling back to an older snapshot, which would report the
newest change set's own DDL as drift. A snapshot is also invalidated when the environment is rebound
to a different datasource.

Both live modes require the baseline and the scanned datasource to run the **same engine**. Without
that rule a PostgreSQL → MySQL ladder reports a type mismatch on essentially every column, forever —
the same flapping failure the sampling rule exists to prevent. It also means a sampling engine can
never appear as a baseline, since the scanned side is already known catalog-backed by then.

**A mode with no resolvable baseline records a scan with zero findings and a reason — never a silent
pass** — and resolves nothing, because nothing was compared. The reason codes are listed in
[04-api-spec.md](04-api-spec.md#schema-drift-881); they are stable machine-readable strings rather
than localized prose, because the row is written once by a background job and read afterwards in
every locale.

One of them deserves its own note. Because the `BASELINE_ENVIRONMENT` designation is **pipeline-wide**,
the job necessarily reaches the designated rung itself on every run. Comparing it against itself
would be vacuously clean — the one outcome this feature must never produce silently — so it is
recorded as `BASELINE_ENVIRONMENT_IS_TARGET`. The configuration write cannot refuse this, since it
does not know which rung will be scanned.

### What a finding is

`object_path` is `schema`, `schema.table` or `schema.table.column`; `expected_value` is the
baseline's side and `actual_value` the scanned environment's. Three rules shape the output:

- **Never descend past an absence.** A schema missing on one side is exactly one finding; a missing
  table is exactly one finding. A dropped 400-column table is one row, not 401, and its columns do
  not consume the table cap either.
- **One finding per `(path, kind)`.** A column both retyped and made nullable produces *two*
  findings on the same path. Each has its own remediation and its own resolve lifecycle: fixing the
  type must not silently close the nullability finding, and a combined finding would have to stuff a
  composite blob into `expected_value`.
- **Foreign keys hang off the owning column**, carrying that column's whole reference set on each
  side, so an added or retargeted key is one finding rather than a missing/unexpected pair.

Names may themselves contain dots: Elasticsearch and BigQuery flatten nested fields into dotted
column names (`customer.id`), and Elasticsearch index names often carry dates (`logs-2026.09.23`). A
path is therefore never split to find its table. The diff reports every table key it knew about, and
a stored finding belongs to the **longest** of them that prefixes its path.

Foreign keys have one more guard. The relational introspector swallows a failed `getImportedKeys`
and returns an empty list, and several catalog engines never report foreign keys at all — so "no
keys" and "the read failed" are indistinguishable. If **one whole side** reports none while the other
reports some, they are not compared and the scan records `FK_COMPARISON_SUPPRESSED`. Without that,
a single failed metadata read reports every foreign key in the estate as drift, on every scan,
forever. A *per-table* difference is still compared: one table losing its keys is real drift, and a
one-table failure self-resolves on the next scan.

### The lifecycle, and the one rule that matters most

A finding is keyed `(organization, environment, object_path, finding_kind)`. New findings open;
findings still observed have their scan pointer and `last_seen_at` moved; findings no longer observed
resolve.

Two judgement calls are worth stating outright:

- **An acknowledgement accepts the difference as it stands.** If a later scan sees the same object
  path with *different* values, the finding reopens. Accepting `staging is varchar where prod is
  text` is not accepting `it became int4`. This is the opposite of `discovery`'s permanent
  `CONFIRMED`/`DISMISSED`, and deliberately so: there the decided subject is a classification and the
  payload is a sample, here the payload *is* the subject. Case-only churn from a driver does not
  reopen anything.
- **A reappearing finding is reopened in place**, keeping its original `first_detected_at`. A second
  row would make the natural-key lookup permanently ambiguous, and "first ever observed" is precisely
  what makes a flapping object visible. The cost, stated plainly: the gap between resolution and
  recurrence is not recoverable from the finding row. It is recoverable from the scan history, since
  the finding points at the scan that last observed it.

**The rule that matters most: a scan only resolves what it could have re-observed** — a finding
under a table it actually compared, of a kind it actually compared.

- A table the table cap skipped, the time budget cut off, or the findings cap abandoned keeps its
  findings and its `last_seen_at` untouched — "we did not look" and "it is fixed" are different facts.
- A scan that suppressed foreign-key comparison leaves every `FOREIGN_KEY_MISMATCH` finding exactly
  as it was, acknowledgements included.
- A scan whose own schema comparison was cut short by the findings cap resolves nothing at all, and
  neither does one that failed or had no baseline.
- A finding under a table that **neither side has any more** — dropped from both databases, or
  under a schema one side lacks — resolves, because the schema comparison always covers it. So when a
  whole table is dropped, its old column findings resolve and the single table-level finding takes
  their place.

This is the `discovery` stale-sweep coupling (AF-659) one-for-one, and it is the subtlest part of the
design.

`findings_count` is everything the scan **observed** — new, reopened and still present, acknowledged
ones included — not just new ones, counted from the rows when the scan finishes. Because a finding
belongs to the scan that *last* observed it, listing an older scan's findings after a newer scan has
run returns only those nobody has seen since; the older scan keeps the count it recorded.

### Acknowledging while a scan runs

Findings carry an optimistic-lock version (`V181`). A scan reconciles row by row for the length of
its run, and an admin can acknowledge at any moment, so without it the scan's stale copy of an `OPEN`
finding would be written back over the acknowledgement — silently undoing it while its audit row
stayed. With it, the later write loses: a scan skips the row until its next run, and the acknowledge
endpoint answers `409 SCHEMA_DRIFT_CONCURRENT_UPDATE` and asks the caller to retry.

### One scan per environment, cluster-wide

Two locks. The job's own `@SchedulerLock` (`schemaDriftJob`) keeps one replica per tick; each
environment is then scanned under `schemaDriftScan:<environmentId>` for
`accessflow.schemachange.drift-scan-lock-at-most-for`. The second lock is what stops an on-demand
*Scan now* on one replica racing the tick on another, so the `409` means "running **somewhere**",
not "running on the node you reached".

The manual path takes the lock on the request thread and hands only the scan to an executor, so it
can answer synchronously. Three details there are load bearing rather than tidy: the scan row is
**committed** before the handoff (a row merely flushed in the caller's open transaction would be
invisible to the executor's own); a handoff that throws finishes the row immediately; and the
in-flight pre-check is **bounded by the lock's ceiling**. Together they mean no orphaned scan row —
from a lost race, a failed handoff or a replica that died mid-run — can keep an environment answering
`409` forever. The `404` for an unknown environment is always resolved before the `409`, so a busy
environment is indistinguishable from one the caller cannot see.

### Who can see what

Every drift endpoint requires `SCHEMA_CHANGE_MANAGE`, and *Scan now* introspects through
`introspectSchemaForSystem`, which is organization-scoped but **not** per-datasource
permission-gated. A holder of `SCHEMA_CHANGE_MANAGE` therefore sees the table and column names of
every datasource bound to an environment of their organization's pipelines, including ones they hold
no query grant on. That is deliberate — the permission governs schema across the whole ladder — but
it is worth knowing before granting it to a custom role. Promotion is
the only path with a per-datasource check (`can_ddl`, §6).

### Known gaps

- **No notifications.** #882 adds the fan-out; until then a drift finding is silent until somebody
  opens the worklist.
- **No retention on scans or findings.** Scan rows accumulate; a resolved finding is kept
  indefinitely so its history stays readable. Neither is pruned by the lifecycle module yet.
- **A very large estate can outlive the tick lock.** The job visits pipelines and their environments
  one after another under a two-hour tick lock; an estate whose scans take longer than that can see a
  second replica's tick start while the first is still working. The per-environment lock still
  prevents two scans of one environment, so the overlap costs duplicate work, not wrong findings.

## Audit & permissions

`SCHEMA_CHANGE_MANAGE` is the only permission this feature introduces. Promotion writes one audit
row per transition against the `schema_change_promotion` resource:
`SCHEMA_CHANGE_PROMOTION_SUBMITTED` and `_CANCELLED` carry the acting user and their request
provenance, while `_APPLIED`, `_PARTIALLY_APPLIED` and `_FAILED` are system rows with a null actor
and `trigger=request_group` — the `deploygov` convention. No migration was needed:
`audit_log.action` and `resource_type` are `VARCHAR(100)`.

Drift (#881) writes three more, against its own resource types (`schema_drift_scan`,
`schema_drift_finding`, `schema_drift_config`): `SCHEMA_DRIFT_SCAN_COMPLETED` once per scan of one
environment — a null actor with `trigger=schedule` for the job, the requesting user with
`trigger=manual` for *Scan now* — carrying `applicable`, `partial`, `findings_count`, `duration_ms`
and the `reason` code when one was recorded; and
`SCHEMA_DRIFT_FINDING_ACKNOWLEDGED` / `SCHEMA_DRIFT_CONFIG_UPDATED`, which always carry the acting
user. There is no remediation action, and there never will be: drift never writes.

Nothing is **notified** yet — #882 adds the notification fan-out, so a promotion waiting for
approval and a drift finding nobody has opened are both currently silent. Authoring CRUD audit follows the same "admin CRUD audit is a
follow-up" stance `deploygov` took.

## Out of scope (by design)

- **No transaction over a change set.** Each statement runs autocommit; a failure at statement 7 of
  12 leaves the environment half-migrated (`PARTIALLY_APPLIED`). The envelope check exists so no
  one is promised otherwise.
- **No strict DDL-only mode** in v1 — see §2 for what `OTHER` admits and why.
- **No un-archive**, no manual `ACTIVE`.
- **No `can_ddl` check at authoring.** Promotion enforces it on the target datasource for the
  promoting user (§6); authoring only needs `SCHEMA_CHANGE_MANAGE`.
- **No per-environment approval override.** Approval strictness comes from the target datasource's
  review plan; the environment can only require that one exists (§6).
- **No cancelling an approved promotion** — it is already queued for its run (§6).
- **No persisted review findings** for change sets — warnings are computed on the write and
  returned once.
- **No auto-remediation, ever.** Drift records what differs; correcting it is a change set like any
  other, reviewed like any other.
- **No drift on sampling engines**, and no retention on scans or findings yet (§7).
