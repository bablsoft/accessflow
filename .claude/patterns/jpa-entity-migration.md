# JPA entity + Flyway migration

**When to use:** Any schema change, or any new persisted type.
**Canonical example:** `backend/src/main/java/com/bablsoft/accessflow/discovery/internal/persistence/entity/DiscoveryFindingEntity.java:25`
**Migrations:** `backend/src/main/resources/db/migration/V129__create_discovery.sql:1` (new tables + enums), `V126__add_databricks_db_type.sql` + its `.sql.conf` sidecar (enum value add)
**Tests:** `backend/src/test/java/com/bablsoft/accessflow/core/internal/persistence/entity/RowSecurityPolicyEntityTest.java`
**Related:** [modulith-module.md](modulith-module.md), [backend-test-parity.md](backend-test-parity.md), `docs/03-data-model.md`

## Shape

```java
// discovery/internal/persistence/entity/DiscoveryFindingEntity.java:25
@Entity
@Table(name = "discovery_finding")
@Access(AccessType.FIELD)
public class DiscoveryFindingEntity {

    @Id
    private UUID id;

    @Column(name = "table_name", nullable = false, columnDefinition = "text")
    private String tableName;

    // A PostgreSQL enum column needs all three annotations. columnDefinition must match
    // the SQL type name EXACTLY — snake_case, no _enum suffix.
    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "data_classification")
    private DataClassification classification;

    @Version
    private Instant updatedAt;      // optimistic locking on concurrently-mutable rows
}
```

Migration filenames are `V{n}__{snake_case}.sql`, double underscore, and open with a comment
naming the issue and the intent:

```sql
-- AF-623: automated sensitive-data discovery & classification scanning. A scheduled scanner
-- samples column data through the existing per-engine sampling path, ...

CREATE TYPE discovery_finding_status AS ENUM ('PENDING', 'CONFIRMED', 'DISMISSED');

CREATE TABLE discovery_finding (
    id              UUID PRIMARY KEY,
    table_name      TEXT NOT NULL,
    status          discovery_finding_status NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Adding a value to an existing enum needs a sidecar file.** Postgres cannot add an enum value
inside a transaction, and Flyway wraps migrations in one by default:

```sql
-- V126__add_databricks_db_type.sql
ALTER TYPE db_type ADD VALUE IF NOT EXISTS 'DATABRICKS';
```
```properties
# V126__add_databricks_db_type.sql.conf   <- same basename + .conf, REQUIRED
executeInTransaction=false
```

## Required (acceptance checklist)

- [ ] Class name ends in `Entity`, lives in `<module>/internal/persistence/entity/`, `UUID` PK,
      `@Access(AccessType.FIELD)`, explicit `@Table(name=…)` and `@Column(nullable=…)`.
- [ ] Repository is a Spring Data interface in `<module>/internal/persistence/repo/`.
- [ ] Every PG enum column carries `@Enumerated(EnumType.STRING)` +
      `@JdbcType(PostgreSQLEnumJdbcType.class)` + a `columnDefinition` matching the SQL type name
      exactly (`snake_case`, **no** `_enum` suffix).
- [ ] `@JsonIgnore` on every encrypted/sensitive field, at the **entity**, not just the response model.
- [ ] `FetchType.LAZY` everywhere; fetch eagerly per-query via `@EntityGraph` or a join fetch.
- [ ] `@Version` on anything that can be concurrently modified.
- [ ] New migration number is `max + 1`. Find it with:
      `ls backend/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -rn | head -1`
- [ ] Every added column is **nullable or has a DEFAULT** (zero-downtime deploys).
- [ ] `ALTER TYPE … ADD VALUE` has its `.sql.conf` sidecar with `executeInTransaction=false`.
- [ ] `docs/03-data-model.md` updated in the same commit.

## Anti-patterns

- **Editing a migration that already exists on `main`** → Flyway stores a checksum per applied
  migration. Changing the file makes *every existing deployment* fail at startup, with no
  rollback. Always write a new forward migration. `.claude/hooks/flyway-guard.sh` hard-blocks this.
- **`ALTER TYPE … ADD VALUE` without the `.sql.conf`** → passes on an empty test DB, fails on a
  real Postgres with "ALTER TYPE ... ADD cannot run inside a transaction block".
- **`columnDefinition = "discovery_finding_status_enum"`** → the SQL type has no `_enum` suffix;
  Hibernate's validation fails at boot with a type mismatch.
- **`@Enumerated` without `@JdbcType(PostgreSQLEnumJdbcType.class)`** → Hibernate binds the enum as
  `varchar` and Postgres rejects the implicit cast to the enum type.
- **`ADD COLUMN … NOT NULL` with no DEFAULT** → the migration fails on any non-empty table, and
  breaks rolling deploys where old pods still insert without the column.
- **`spring.jpa.hibernate.ddl-auto` anything but `validate`** → outside Testcontainers this
  silently diverges the schema from the migration history.
- **Two branches both claiming `V130`** → Flyway refuses to start on duplicate versions. Re-check
  the max at rebase time, not just at branch time.

## Extending

Adding a `DbType` value is the widest case: the enum, a migration + sidecar, a
`validateDriverChoice` branch if the connection model isn't host/port/db/user/pass, i18n keys in
all seven message files, and the frontend union + labels. See [engine-plugin.md](engine-plugin.md)
for the full sequence.

For a *new* PG enum type, create it with `CREATE TYPE … AS ENUM` in the same migration as the
table that uses it — that keeps the type and its first consumer atomic, and there's no sidecar
needed because `CREATE TYPE` is transactional.
