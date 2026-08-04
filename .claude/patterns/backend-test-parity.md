# Backend test parity

**When to use:** Every new concrete backend class. This is CLAUDE.md's most-violated rule.
**Canonical example:** `backend/src/test/java/com/bablsoft/accessflow/discovery/internal/DefaultDiscoveryConfigServiceTest.java`
**Testcontainers:** `backend/src/test/java/com/bablsoft/accessflow/TestcontainersConfig.java:18`
**Specifications:** `backend/src/test/java/com/bablsoft/accessflow/audit/internal/AuditLogSpecificationsTest.java`
**Related:** all backend patterns; `docs/11-development.md` → Testing Strategy

## Shape

| Suffix | Runner | Scope |
|---|---|---|
| `*Test.java` | surefire | Single class, Mockito, **no Spring context** |
| `*IntegrationTest.java` | failsafe | `@SpringBootTest` + Testcontainers, real Postgres |
| `*ModuleTest.java` | surefire | `@ApplicationModuleTest`, one module in isolation |

The shared container config — **use it, don't roll your own**:

```java
// TestcontainersConfig.java:18
public final class TestcontainersConfig {

    @ServiceConnection
    public static PostgreSQLContainer postgres = new PostgreSQLContainer("pgvector/pgvector:pg18")
            .withCommand("postgres", "-c", "max_connections=500")
            .withInitScript("db/test-init-audit-roles.sql");

    @ServiceConnection(name = "redis")
    public static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine")
            .withExposedPorts(6379);
}
```

It is `pgvector/pgvector:pg18` (not plain `postgres`) because `V69` creates a `vector` column, and
it runs `test-init-audit-roles.sql` because `V38` expects the `accessflow_audit` role to already
exist. A hand-rolled `PostgreSQLContainer` fails on both.

## Required (acceptance checklist)

- [ ] New `Default*Service` → a `Default*ServiceTest` **in the same change**: one `@Test` per
      public method, covering the happy path, every documented exception, and every distinct
      branch (status guard, null check, role check). Mockito, no Spring context.
- [ ] New `*Specifications` helper → a test that mocks `Root`, `CriteriaQuery`, `CriteriaBuilder`
      and verifies each filter field independently **and** the no-filter path.
- [ ] New record/DTO with a static `from(...)` or a validating constructor → a focused test
      covering the null-input branch and each conditional.
- [ ] Adding a method to an existing service → extend that service's existing `*Test` in the same
      change, not a follow-up.
- [ ] Integration tests use `@ImportTestcontainers(TestcontainersConfig.class)`. **Never H2.**
- [ ] Coverage ≥ 90% lines / ≥ 80% branches. The author sets the coverage of the touched class;
      JaCoCo is only a backstop.

## Anti-patterns

- **Assuming coverage arrives from the caller** → this is *the* failure mode the parity rule
  exists for. Controller integration tests almost always `@MockitoBean` the service interface, so
  the `Default*` implementation never executes. Other services mock their collaborators too. By
  the time JaCoCo notices, the under-tested class is already merged.
- **Rolling your own `PostgreSQLContainer`** → misses the audit role (`V38` fails) and the
  `vector` extension (`V69` fails), and you'll spend an hour on migration errors that have
  nothing to do with your change.
- **H2 as a Postgres stand-in** → the schema uses PG enums, `TIMESTAMPTZ`, and `pgvector`. H2
  models none of them, so a green H2 test proves nothing.
- **Naming an integration test `*Test`** → surefire runs it in the unit phase, where no container
  exists. The suffix selects the runner.
- **A `@SpringBootTest` for logic that needs no context** → seconds per test instead of
  milliseconds, and the whole suite shares one context cache.
- **Asserting only the happy path on a service with documented exceptions** → the exception
  branches are exactly what the ProblemDetail contract depends on.

## Extending

**Deleting an entity with a `@Version Instant` field that you pre-loaded** fails on Linux CI while
passing on macOS: Postgres truncates timestamps to microseconds, the JVM holds nanoseconds, and
the optimistic-lock comparison then mismatches. Re-read the entity immediately before deleting it.

The engine plugins have their own three-tier bar — unit, Testcontainers facade IT, and a
shaded-jar `ServiceLoader` IT. See [engine-plugin.md](engine-plugin.md).

Events published outside a transaction (`QueryExecutedEvent` is the known case) are silently
skipped by `@ApplicationModuleListener`, which is `AFTER_COMMIT`. To assert on one, use a plain
`@EventListener` and check synchronously inside a `@SpringBootTest`.
