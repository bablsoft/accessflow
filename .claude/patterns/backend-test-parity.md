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
| `*IntegrationTest.java` | surefire | `@SpringBootTest` + Testcontainers, real Postgres |
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
- **Naming an integration test `*Test`** → there is no failsafe plugin; surefire runs everything in
  one fork, so the suffix no longer selects a runner. It still matters: it is how humans, the CI
  report and this pattern tell the two kinds apart, and `*IntegrationTest` is what the
  database-reset listener and the context-cache budget are reasoned about in terms of.
- **A `@SpringBootTest` for logic that needs no context** → seconds per test instead of
  milliseconds, and the whole suite shares one context cache.
- **Asserting only the happy path on a service with documented exceptions** → the exception
  branches are exactly what the ProblemDetail contract depends on.

## Test-context cache — two invariants

The suite once built **121 Spring contexts for 124 integration tests** (631 s, 46 % of the run)
because every class declared its own `@DynamicPropertySource`. Spring's
`DynamicPropertiesContextCustomizer` keys the context cache on the `Set<Method>` it finds on the
**test class hierarchy**, so a per-class method guarantees a cache miss.

1. **Shared test properties are registered globally, never on a test class.**
   The JWT and encryption keys come from `TestKeysContextCustomizerFactory`, registered in
   `src/test/resources/META-INF/spring.factories`. Its customizer is value-equal to every other
   instance, so it is one *stable* component of every context cache key instead of a per-class one.
   Values several tests share but some must override belong in
   `src/test/resources/application.properties` (ordinary config data, so an inline
   `@SpringBootTest(properties = …)` still wins).

   A `@DynamicPropertySource` on an `@ImportTestcontainers` holder — e.g.
   `MysqlDriverCacheTestcontainersConfig` — is also cache-key-free, because
   `DynamicPropertySourceMethodsImporter` turns it into a `DynamicPropertyRegistrar` *bean*.
   **But a bean is applied during `finishBeanFactoryInitialization`, which is too late for a
   servlet context**: under `webEnvironment = RANDOM_PORT`,
   `ServletWebServerApplicationContext.onRefresh()` starts Tomcat and builds the security filter
   chain first, so anything the filter chain reads is still unset and you get a context-load
   failure (`jwtServiceImpl` → "Missing key encoding"). Use the holder only for values that no
   `RANDOM_PORT` test needs; use a `ContextCustomizerFactory` when it must be there before refresh.

   A test class needs its own `@DynamicPropertySource` only for a genuinely per-class value — its
   own container's URL, a temp directory it created. Accept that it buys a private context, and
   prefer `@SpringBootTest(properties = …)` with **literal** values where you can: identical
   literals across two classes still *share* a context, which `@DynamicPropertySource` can never do.

2. **`spring.test.context.cache.maxSize` must exceed the distinct-context count.**
   `DefaultContextCache.put()` evicts the LRU context *before* loading the new one, and eviction
   closes the shared static Testcontainers instances
   (`TestcontainersLifecycleBeanPostProcessor` is a `DestructionAwareBeanPostProcessor`) out from
   under every context still cached. Exceed the ceiling and the suite fails with mass
   `Connection refused`, not slowness. It is pinned to 64 in the surefire
   `systemPropertyVariables` (it is read via `SpringProperties`, so it cannot live in a
   properties file). If you add contexts, check the `ContextCache` stats line before assuming
   there is headroom.

Because one Postgres now serves the whole run, `DatabaseResetTestExecutionListener` truncates
every table and re-seeds the system roles after each test class. Do not rely on a virgin database
in a `@BeforeAll` — rely on the listener, and keep your own cleanup idempotent.


## Extending

**Deleting an entity with a `@Version Instant` field that you pre-loaded** fails on Linux CI while
passing on macOS: Postgres truncates timestamps to microseconds, the JVM holds nanoseconds, and
the optimistic-lock comparison then mismatches. Re-read the entity immediately before deleting it.

The engine plugins have their own three-tier bar — unit, Testcontainers facade IT, and a
shaded-jar `ServiceLoader` IT. See [engine-plugin.md](engine-plugin.md).

Events published outside a transaction (`QueryExecutedEvent` is the known case) are silently
skipped by `@ApplicationModuleListener`, which is `AFTER_COMMIT`. To assert on one, use a plain
`@EventListener` and check synchronously inside a `@SpringBootTest`.
