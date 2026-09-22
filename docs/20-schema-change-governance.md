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
> `/schema-change-sets` REST surface (#879) — and **promotion** with the ladder gate, freeze-window
> check, request-group wiring and the post-apply snapshot (#880) are on `main`. The schema drift
> job (#881), the notification fan-out (#882), the web UI (#883) and the website sweep (#884)
> follow. Promotion audit is in place; notifications are not, so a promotion waiting for approval
> is currently silent.

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
│   ├── SchemaDriftService                # declared; implemented by #881
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
    ├── persistence/{entity,repo}         # V178 tables (#878)
    └── web/                              # SchemaChangeSetController + records + exception handler
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

Documented in [04-api-spec.md → Schema Change Governance](04-api-spec.md#schema-change-governance-879-880-epic-870):
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

## Audit & permissions

`SCHEMA_CHANGE_MANAGE` is the only permission this feature introduces. Promotion writes one audit
row per transition against the `schema_change_promotion` resource:
`SCHEMA_CHANGE_PROMOTION_SUBMITTED` and `_CANCELLED` carry the acting user and their request
provenance, while `_APPLIED`, `_PARTIALLY_APPLIED` and `_FAILED` are system rows with a null actor
and `trigger=request_group` — the `deploygov` convention. No migration was needed:
`audit_log.action` and `resource_type` are `VARCHAR(100)`.

Nothing is **notified** yet — #882 adds the notification fan-out, so a promotion waiting for
approval is currently silent. Authoring CRUD audit follows the same "admin CRUD audit is a
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
