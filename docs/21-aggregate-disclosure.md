# 21 — Aggregate Disclosure Guard (design, #943)

Every row-limiting control AccessFlow ships bounds the rows a query **returns**. None of them
bounds what a query **discloses**. This chapter is a design: it chooses one clearly bounded property,
says where it would be enforced, and states what that property does **not** protect against. It
also recommends shipping **detection before enforcement**.

> **Status.** *Design — proposed, not implemented.* Nothing proposed here exists on `main`; the existing
> controls it builds on are described as such in §0, §5 and §6. The implementation is split into follow-up issues (§8).
> The chapter was written to be disagreed with: §7 lists the open questions.

> **The one sentence to remember.** For the query shapes it accepts, a minimum group size stops
> *one query* from reporting on fewer than *k* individuals. It refuses the shapes it cannot reason
> about, and it does nothing about a user who compares two queries. So it must never be described,
> in the UI or anywhere else, as k-anonymity or as a privacy guarantee.

---

## 0. The problem

Two queries that every existing control lets through:

```sql
-- 1 row returned; confirms that a specific person is a customer
SELECT COUNT(*) FROM customer WHERE national_id = '850101-1234';

-- one row per city; for a city with one resident, the "average" is that resident's salary
SELECT city, AVG(salary) FROM employee GROUP BY city;
```

| Control | Why it does not help |
|---|---|
| Row caps (`max_rows_per_query`, `row_limit_override`, row-limit policies #934) | Both results are tiny. |
| Data budgets (#942) and the bytes-scanned cap (#941) | Budgets count delivered rows and result bytes, and the cap counts bytes a warehouse scans. Both queries deliver almost nothing, and a probe can be made as narrow as the user likes. |
| Column masking (`restricted_columns`, AF-381 policies) | Masking rewrites **output values** (see [Column-level masking](05-backend.md#column-level-masking)). `national_id` is only used in the `WHERE` clause and `salary` only inside `AVG`, so there is nothing to mask. |
| Row-level security (RLS, AF-380) | It restricts *which* rows are visible. The user may legitimately see every employee, just not individual salaries. |
| `denied_columns` (#935) | It **does** catch both queries when the column is denied (§6). But a denied column is gone for every purpose, including legitimate aggregates. |
| `denied_shapes: [AGGREGATE]` (#940) | It catches both queries, and every other aggregate as well. It is a per-grant blunt instrument, not a disclosure control. |

## 1. Threat model and vocabulary

- **Subject.** The individual a row is about (a customer, a patient, an employee). Rows and subjects
  are not the same thing: 50 transactions by one customer are 50 rows and **one** subject. Any
  control that counts rows can be defeated by grouping over a table with many rows per subject.
- **Subject key.** The column that identifies a subject in a table (for example `customer_id`). The
  database cannot infer it reliably, so an admin must declare it.
- **Group size.** The number of **distinct subjects** contributing to one output row of an aggregate
  query.
- **An aggregate without `GROUP BY` is a single group.** `COUNT(*) … WHERE national_id = '…'` has
  exactly one group, and its size is the number of matching subjects. This rule is what brings the
  first headline query under the property. Without it, a minimum-group-size check that only looks at
  `GROUP BY` would miss the most common probing query of all.
- **Adversary.** A user who holds a legitimate grant on the datasource, submits queries through the
  normal path, and wants to learn a fact about an individual that their grant does not show directly.
  AccessFlow's other controls (permissions, masking, row-level security, review) still apply to them. This design
  adds one more.

## 2. The property on offer

Stated exactly, so that it can be tested:

> For a SELECT that contains an aggregate and references a **governed table** (a table with a declared
> subject key and a configured minimum *k*), every aggregate in every output row is computed from
> values contributed by at least *k* distinct subjects, counted by the subject key over the rows the
> submitter is allowed to see. A row that falls below *k* for any of its aggregates is **removed**
> from the result. It is not zeroed or masked, because a zero count is itself a disclosure.

"Contributed" is load-bearing. The count is per aggregate and ignores NULLs, which is how the
aggregate itself treats them: `AVG(bonus)` over a group of 1,000 employees in which only one has a
non-NULL `bonus` is that employee's bonus, so its contributor count is 1, not 1,000. `COUNT(*)`
counts every subject in the group.

This is the smallest property that is useful and still honest. The guard **refuses** the following
shapes with 422 instead of trying to apply the property to them. This fails closed, following the
precedent of `ROW_SECURITY_UNREWRITABLE`:

| Shape | Why it is refused |
|---|---|
| An aggregate whose argument is anything but a bare column or `*`, or that carries `FILTER (WHERE …)` or `WITHIN GROUP` | Conditional aggregation isolates one subject inside a large group in a single query: `COUNT(*) FILTER (WHERE national_id = '…')` and `SUM(CASE WHEN id = 42 THEN salary END)` both have a group of every row in the table. `CASE`, `IF`, `COALESCE`, arithmetic and scalar functions inside the argument are all refused for the same reason. |
| `ROLLUP`, `CUBE`, `GROUPING SETS` | Subtotal rows let a suppressed group be recomputed by subtraction within one result. |
| Window functions over a governed table | `COUNT(*) OVER (PARTITION BY …)` is a per-row aggregate that no group filter can see. |
| Aggregates in a subquery, CTE or derived table | The outer query can re-expose an inner small group, for example by filtering on it. |
| Set operations (`UNION` / `INTERSECT` / `EXCEPT`) | Each branch would need its own proof, and the branches can be differenced against each other. |
| `HAVING` | Whether a group appears at all becomes the answer. `HAVING MAX(id) = 42` over a department of 500 reveals that employee 42 works there, and the group is far above *k*. |
| An unanalysed statement (`shapesAnalyzed = false`) | Fails closed, as `DeniedShapes.rejected` already does. |

Aggregates that are not over a governed table are left alone. No subject key means no property, and
the property is not guessed.

## 3. Enforcement point (for the enforcement release)

Issue #943 suggests enforcing post-fetch in the masker path. **That is not enough on its own**, for
two reasons:

1. **A result row does not carry the group size.** `city, AVG(salary)` contains no count. The masker
   (`proxy/internal/JdbcResultRowMapper` + `ColumnMaskResolver`) sees values, and cannot know
   how many subjects produced each one.
2. **A post-fetch count is a row count, not a subject count.** Even `COUNT(*)` in the select list
   counts rows. Only `COUNT(DISTINCT <subject key>)` counts subjects.

The enforcement therefore has two halves:

- **Refusal (at submission).** The §2 refusals are pure AST checks, so they run at submission with
  the other shape and column gates (`DatasourcePermissionVerifier`, as `denied_shapes` does), and a
  refused query never reaches a reviewer. The rewrite below re-asserts them at execution, because
  the configuration may change between approval and a scheduled or recurring run.
- **Rewrite (pre-execution).** A pure JSqlParser rewrite in `proxy.internal` adds one hidden output
  column per aggregate: `COUNT(DISTINCT <subject key>)` for `COUNT(*)`, and
  `COUNT(DISTINCT CASE WHEN <col> IS NOT NULL THEN <subject key> END)` for an aggregate over `<col>`.
  It is modelled on `RowSecurityRewriter`: it re-parses, it is a no-op when no governed table is
  referenced, and it throws a dedicated unrewritable exception (mapped to 422) for any §2 shape. The
  rewrite runs **after** the row-security rewrite, so *k* counts only the rows the submitter can see.
  Counting rows they cannot see would inflate the group size.
- **Filter (post-fetch).** `JdbcResultRowMapper.materialize(...)` drops every row in which any
  hidden count is below *k*, then drops the hidden columns, before masking. This happens before
  `SelectResultCache` stores the result and before the result is persisted to
  `query_request_results`, so a suppressed value never lands in AccessFlow's own database.
- **Cache key.** The cache holds final post-mask results, keyed by the rewritten SQL, binds, mask
  directives and row cap (see [SELECT result caching](05-backend.md#select-result-caching-af-457)).
  The rewritten SQL carries the subject key, but *k* is applied after the fetch and appears nowhere
  in it. *k* must therefore join the cache key, or changing it would keep serving results filtered
  under the old *k* until the entry expires.
- **Audit.** `QUERY_EXECUTED` metadata gains a `suppressed_group_count`. It records how many rows were
  removed, never their values or their grouping keys.
- **Scope.** Relational engines only, as with `denied_columns` and `denied_shapes`. Configuring a
  subject key on an engine-managed `DbType` is refused at configuration time (422
  `…_NOT_SUPPORTED`). An engine plugin would need an SPI extension with its own per-engine
  rewrite, and that is out of scope. Because the rewrite sits in the executor, it applies to every
  run: interactive, scheduled, recurring, request-group members and break-glass alike.

## 4. Explicit non-goals

The property in §2 does **not** protect against any of the following. The product must say so
wherever the feature is configured.

- **Differencing across submissions.** This is the classic *tracker* attack:
  `COUNT(*) WHERE dept = 'X'` minus `COUNT(*) WHERE dept = 'X' AND id <> <victim>` reveals whether the
  victim is in department X (with `SUM(salary)`, it reveals their salary), and both groups are large. A per-query control cannot see this attack. Defending against it
  requires analysing each user's whole query history, which is a far larger undertaking and is not
  proposed here.
- **Differencing within one result** through overlapping groups that the §2 refusals do not catch.
  The refusals remove the obvious forms (`FILTER`, conditional arguments, grouping sets), but the
  design does not claim that no single accepted query can be differenced against itself.
- **Aggregate-plus-total leakage.** A separate query for the total, combined with a suppressed
  breakdown, recovers the suppressed group when exactly one group was removed, and the combined
  value of the removed groups otherwise. Complementary suppression (hiding extra groups so the
  suppressed one cannot be derived) is not offered.
- **Aggregates that return an individual's value.** `MIN`, `MAX`, `MEDIAN`, `MODE`, `PERCENTILE_*`
  and `ANY_VALUE` return one subject's value, and the list-building aggregates (`STRING_AGG`,
  `GROUP_CONCAT`, `LISTAGG`, `ARRAY_AGG`, `COLLECT`, `XMLAGG`, the `JSON[B]_AGG` / `JSON_ARRAYAGG` /
  `JSON_OBJECTAGG` family) return every value in the group. This holds even in a group of a thousand:
  `MAX(salary)` is the top earner's salary. Refusing them is a reasonable v2 option (§7), but it is
  not part of the minimum-group-size property.
- **Background knowledge.** An `AVG` over *k* subjects, where the user already knows *k − 1* of the
  values, reveals the last one.
- **Noise or differential privacy.** No randomisation is proposed.
- **Non-aggregate queries.** Row-level reads are governed by permissions, masking and RLS, not by
  this guard.
- **User-defined aggregates.** Aggregates are detected by name (the #940 list in
  `QueryShapeDetector`). A custom aggregate is caught only when it carries `FILTER` / `WITHIN GROUP`
  or sits beside a `GROUP BY`; a bare `my_agg(salary)` with no `GROUP BY` is not recognised.
- **Engine plugins** (see §3).

## 5. Recommendation: v1 detects, it does not enforce

**Ship detection first.** Flag aggregate-shaped queries that touch classified data and route them to
a human reviewer. Defer the §2–§3 enforcement until detection data shows it is wanted.

### What already exists (zero code, available today)

The #940 `query_shape` routing leaf and the `referenced_table` leaf already compose into an
escalation policy for tables an admin names by hand:

```json
{
  "name": "Aggregates over customer data need a second reviewer",
  "priority": 50,
  "condition": {
    "type": "and",
    "children": [
      { "type": "query_shape", "any_of": ["AGGREGATE", "GROUP_BY"] },
      { "type": "referenced_table", "globs": ["crm.customer", "hr.*"] }
    ]
  },
  "action": "ESCALATE",
  "required_approvals": 1
}
```

A routing policy beats the grant-covered auto-approval fast path, so a pre-approved just-in-time
(JIT) grant does not skip it. The recipe has three limits:

- **It fails open on a statement routing cannot analyse.** In routing, "fails closed" means a leaf
  evaluates to **false** when its signal is missing (see
  [Query-shape deny-lists](05-backend.md#query-shape-deny-lists-940)). That is safe for an
  `AUTO_APPROVE` policy and the opposite for an `ESCALATE` one: when the shape could not be
  analysed, `query_shape` is false, the `and` is false, and the query is not escalated.
- **It sees nothing on a non-SQL datasource.** Routing re-parses the stored text with JSqlParser
  whatever the engine (`ConditionContextFactory`). A MongoDB or Redis command does not parse, so
  there are no referenced tables and no shapes, and neither leaf can match.
- **It cannot follow the classification tags** (AF-447 / AF-623 discovery). The admin must keep the
  globs in sync with the tags by hand.

### What v1 adds: one routing leaf

`ConditionNode.ClassificationReferenced(Set<DataClassification> anyOf)`, with JSON
`{"type": "classification_referenced", "any_of": ["PII", "PHI"]}`:

- **Signal.** `ConditionContextFactory` already re-parses the SQL. It would additionally resolve the
  datasource's `data_classification_tag` rows (read through `core.api.DataClassificationQueryService`)
  into a `Set<DataClassification>` on `ConditionContext`. Column-level tags match
  `SqlParseResult.referencedColumns` (#935). This covers **every** column position (`WHERE`,
  `GROUP BY` and aggregate arguments), so `national_id` in a predicate counts. Table-level tags match
  `referencedTables`.
- **Missing signal.** The leaf keeps the house convention: it is **false** when its signal is
  missing. When `columnsAnalyzed` is `false` it matches on table-level tags only, and when the
  re-parse failed it matches nothing. Making the leaf itself true on a missing signal would invert
  the convention and fail open in the other direction, under `not(…)` in an `AUTO_APPROVE` policy.
- **A second leaf for the missing signal.** `{"type": "sql_analysis_incomplete"}` is true when the
  routing re-parse failed or did not analyse shapes or columns. It turns "we could not tell" into
  something an escalation policy can match explicitly, instead of a silent false.
- **Composes.** The recommended policy, scoped to a relational datasource, is:

  ```
  or(sql_analysis_incomplete,
     and(query_shape ∈ {AGGREGATE, GROUP_BY},
         classification_referenced ∈ {PII, PHI, PCI}))  →  ESCALATE
  ```

  Scope it with `datasource_id`. On a non-SQL datasource every command is "incomplete", so an
  org-wide policy would escalate every MongoDB or Redis query. **v1 does not cover non-SQL
  engines**, and the chapter says so rather than implying otherwise.
- **Replays.** The policy simulator (AF-630) goes through the same factory, so an admin can see the
  blast radius before enabling it. One caveat joins the simulator's existing list: tags are read as
  they are now, not as they were when the query was submitted, the same approximation it already
  makes for role and group membership.
- **What it does not see.** Break-glass (AF-385) skips AI analysis and routing entirely, so an
  emergency query is never escalated by this policy. Its compensating controls (admin fan-out, the
  `QUERY_BREAK_GLASS_EXECUTED` audit row, mandatory retro-review) are the only check. A recurring
  series is routed once, when the parent is submitted, and its occurrences are not re-routed.

Why detection wins for v1:

1. **It cannot silently change results.** Suppression that looks like it worked but did not (§4) is
   worse than no control, because users rely on it. Escalation makes no claim about results at all.
2. **A human can see what a per-query filter cannot.** A reviewer looking at
   `COUNT(*) WHERE dept = 'X' AND id <> 42` right after an approved `COUNT(*) WHERE dept = 'X'` can
   recognise a tracker. No per-query rule can.
3. **It is almost entirely reuse:** #940 shapes, #935 column references, AF-447 tags, the routing
   engine and the simulator. The new code is one leaf, one context field and one resolver call.
4. **It covers the same ground as enforcement would, sooner.** Both are limited to SQL that
   JSqlParser reads; detection simply ships without a rewrite, a subject-key model or a cache change.

The cost is that an aggregate over classified data waits for a reviewer. That friction is intended.
An admin who finds it too broad narrows the leaf's classification set or the shape set, and the
simulator shows them what the narrowing changes.

## 6. Interaction with column authorization and masking

Issue #943 asks to settle this together with the column-authorization work. That work (#935) has
since shipped, and it answers the question for denials:

- **`denied_columns` already covers aggregation and predicates.** `SqlStatementInspector` records a
  `ColumnReference` for every column wherever it appears, including `WHERE`, `GROUP BY`, `HAVING`,
  aggregate arguments and `JOIN … USING`. `DeniedColumns.rejected` refuses the query if any of them
  is denied. So `COUNT(*) WHERE national_id = …` is refused when `national_id` is denied. The one
  exception by design is the bare `*` inside `COUNT(*)`, which is not a column wildcard (see
  [Column-level authorization](05-backend.md#column-level-authorization--denied-columns-935)).
  **The gap the issue suspected does not exist for denials.**
- **Masking cannot close it and should not try.** Masking acts on output values. A masked
  `national_id` can still be filtered on, grouped by and counted. Extending masking to predicates
  would make it a denial under another name. The guidance is: **if a column must not be probed, deny
  it; if it may be seen in rendered form, mask it.** Chapter 07 now says this next to the masking
  section.
- **`denied_shapes` remains the blunt per-grant instrument**, for example a contractor who may never
  aggregate. `classification_referenced` is the finer-grained, reviewable alternative.

## 7. Open questions for the reviewer

1. **Detection trigger set.** Should `classification_referenced` also match columns that are masked
   for the submitter but not tagged? The argument for: masking marks a column as sensitive. The
   argument against: it mixes a per-user signal into a per-query classification.
2. **Default *k*** for the enforcement release: 5 (common) or 10 (conservative)? Per-table only, or
   with a datasource default?
3. **Subject key default.** Should it be required, or default to the table's primary key? The primary
   key is wrong for fact tables (one row per transaction), which is exactly where a silent default hurts.
4. **AI hint.** Should the analyzer prompt call out "aggregate over classified data" explicitly? The
   classification risk bump (`ClassificationRiskBooster`) already raises the score whenever a tagged
   table is referenced, so this would mostly change the explanation text, not the score.
5. **Value-returning aggregates.** Should v2 refuse `MIN` / `MAX` / `MEDIAN` / the list-building
   aggregates over a governed column outright (§4)? That is stricter than the property requires,
   and it breaks common legitimate queries such as the most recent order date.
6. **One leaf or two.** Is `sql_analysis_incomplete` worth its own leaf, or should every
   missing-signal leaf gain an explicit `on_missing: true | false` field instead? The field is more
   general, but it changes the shape of every existing leaf.
7. **SQL review rule.** A `sqlreview` rule was considered and rejected for now. Rules are pure AST
   checks with no view of classification tags (see [chapter 19](19-sql-review.md)), so the rule would
   duplicate `query_shape` without adding the tags.

## 8. Follow-up issues (proposed)

1. **v1: `classification_referenced` and `sql_analysis_incomplete` routing leaves.** The leaves,
   the codec, the evaluator, the `ConditionContext` fields, and resolution in
   `ConditionContextFactory` (live and replay), with
   simulator and access-explainer support, the routing-policy form in the UI, docs and an e2e spec.
   There are no migrations: the condition is JSONB.
2. **v2 (deferred until v1 shows demand): minimum-group-size enforcement.** Per-table subject key and
   *k* configuration, the §2 refusals at submission, the per-aggregate counting rewrite, the
   post-fetch filter, *k* in the result-cache key, and the audit field, for relational engines only.
