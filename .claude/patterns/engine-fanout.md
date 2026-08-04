# Engine fan-out

**When to use:** Adding a value to `RowSecurityOperator`, `DbType`, `ColumnMaskType`, or any
`core.api` enum the engine plugins switch on.
**Canonical example:** `backend/src/main/java/com/bablsoft/accessflow/core/api/RowSecurityOperator.java:10` (9 values; every addition ripples across 11 Java switches + the frontend)
**Tests:** one `*RowSecurityApplierTest.java` per engine (9 files), plus `backend/src/test/java/com/bablsoft/accessflow/proxy/internal/RowSecurityRewriterTest.java`
**Related:** [engine-plugin.md](engine-plugin.md), [jpa-entity-migration.md](jpa-entity-migration.md), [backend-i18n.md](backend-i18n.md)

## Shape

One enum, twelve places. **Verified 2026-08-04** — re-derive with
`grep -rln RowSecurityOperator backend/src/main engines/*/src/main`:

| Site | File | Line | On a new value |
|---|---|---|---|
| Host — JSqlParser rewrite | `backend/.../proxy/internal/RowSecurityRewriter.java` | 352 | **compile error** |
| Host — lifecycle negation | `backend/.../lifecycle/internal/ErasurePredicateCompiler.java` | 130 | **compile error** |
| BigQuery | `engines/bigquery/.../BigQueryRowSecurityApplier.java` | 135 | **compile error** |
| Couchbase | `engines/couchbase/.../CouchbaseRowSecurityApplier.java` | 114 | **compile error** |
| Databricks | `engines/databricks/.../DatabricksRowSecurityApplier.java` | 162 | **compile error** |
| DynamoDB | `engines/dynamodb/.../DynamoDbRowSecurityApplier.java` | 90 | **compile error** |
| Elasticsearch (+OpenSearch) | `engines/elasticsearch/.../EsRowSecurityApplier.java` | 85 | **compile error** |
| MongoDB | `engines/mongodb/.../MongoRowSecurityApplier.java` | 83 | **compile error** |
| Snowflake | `engines/snowflake/.../SnowflakeRowSecurityApplier.java` | 115 | **compile error** |
| **Cassandra (+ScyllaDB)** | `engines/cassandra/.../CassandraRowSecurityApplier.java` | 94 | ⚠️ **silent** — has a `default` |
| **Neo4j** | `engines/neo4j/.../Neo4jRowSecurityApplier.java` | 140 | ⚠️ **silent** — has a `default` |
| Redis | — | — | *deliberately absent*: row predicates have no key-value meaning, so Redis fails closed on all of them (`RedisQueryExecutor.java:32`) |
| Frontend | `frontend/src/types/api.ts:1454`, `frontend/src/utils/enumLabels.ts:182`, `locales/*.json` `enums.row_security_operator` | | silent (union widens, label missing) |

**Nine of the eleven Java switches are exhaustive**, so the compiler finds them for you. The two
that aren't — Cassandra and Neo4j — are exactly the two that fail closed by design, so a missing
case degrades to "deny", not to "leak". That's the correct failure direction, but it is silent:
the operator simply stops working on those engines with no error.

### The unary-operator trap

`IS_NULL` takes no values. Every applier must handle unary operators **before** the empty-values
deny-all guard, or the guard fires and denies everything:

```java
// MongoRowSecurityApplier.java:74
if (directive.operator() == RowSecurityOperator.IS_NULL) {
    return new Document(field, null);          // handled FIRST
}
if (values.isEmpty()) {
    return DENY_ALL;                           // :78 — would otherwise swallow IS_NULL
}
```

`IS_NULL` is also **host-only**: it is used by lifecycle soft-delete read filtering and is never
persisted, so it does **not** appear in the `row_security_operator` PG enum. A new operator needs a
migration only if a row-security *policy* can store it.

## Required (acceptance checklist)

- [ ] All 11 Java switch sites updated — or an explicit fail-closed `default`/throw naming the
      operator. **Do not rely on the compiler**: Cassandra and Neo4j will not tell you.
- [ ] Unary operators handled before any empty-values guard in every applier.
- [ ] `frontend/src/types/api.ts` union + `frontend/src/utils/enumLabels.ts` array and label fn +
      the `enums.row_security_operator` key in **all seven** locale JSON files.
- [ ] Flyway `ALTER TYPE row_security_operator ADD VALUE …` **plus** the `.sql.conf` sidecar with
      `executeInTransaction=false` — *only if* the value can be persisted by a policy. Document
      which, in the enum's Javadoc, the way `IS_NULL` does.
- [ ] Each engine's applier test covers the new operator **and** its fail-closed path.
- [ ] Every engine builds:
      `mvn -f backend/pom.xml install -DskipTests && for d in engines/*/; do mvn -q -f "$d/pom.xml" test || echo "FAILED $d"; done`

## Anti-patterns

- **Trusting the compiler to find every site** → Cassandra and Neo4j have `default` branches. The
  build stays green and the operator silently does nothing on two engines.
- **Adding the case to the host rewriter only** → the relational path works, so it looks done. The
  nine engine plugins are separate Maven projects that are not built by `mvn -f backend/pom.xml`.
- **Handling the unary operator after the empty-values guard** → deny-all swallows it. The bug
  presents as "the policy blocks everything", which reads like a policy misconfiguration.
- **`ALTER TYPE … ADD VALUE` for a host-only operator** → puts a value in the PG enum that nothing
  writes, and every engine must then defend against reading it.
- **Widening the TypeScript union without adding the label** → the UI renders the raw
  `SCREAMING_SNAKE` value, and `locales.parity.test.ts` fails on six locales at once.
- **A "best-effort" translation on an engine that can't express the operator** → this is the
  product promise. If the predicate can't be proven to constrain the result, deny.

## Extending

`NotificationEventType` has the same shape and a wider surface — see
[notification-fanout.md](notification-fanout.md).

This is the textbook job for the `parallel-agents` skill: the 9 engine appliers are disjoint files
with no shared state. The files that **must not** be edited concurrently are the shared ones —
`types/api.ts`, `enumLabels.ts`, the seven locale JSONs, `messages.properties`, and
`db/migration/` (two agents will both pick the same `V<n>`).

Before starting, re-derive the table above rather than trusting these line numbers:

```bash
grep -rn 'switch' --include='*RowSecurityApplier.java' engines/*/src/main
```
