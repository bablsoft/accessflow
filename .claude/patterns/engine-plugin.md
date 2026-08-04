# Engine plugin

**When to use:** Adding or modifying an `engines/<id>/` plugin.
**Canonical example:** `engines/redis/pom.xml:15` (the version line and the comment that governs it); SPI impl at `engines/redis/src/main/java/com/bablsoft/accessflow/engine/redis/RedisQueryEngine.java:24`
**Manifest:** `connectors/redis/connector.json`
**Contrast case** (no vendor driver at all — pure `java.net.http`): `engines/databricks/`
**SPI contract:** `backend/src/main/java/com/bablsoft/accessflow/core/api/QueryEngine.java`
**Tests:** `engines/redis/src/test/java/**` — see the three tiers below
**Related:** [jpa-entity-migration.md](jpa-entity-migration.md), [backend-test-parity.md](backend-test-parity.md), **`docs/15-engine-sdk.md` (the authoritative guide)**

## Shape

Each plugin is a **standalone Maven project outside the application**, compiled against the
backend's plain (non-exec) jar and shipped as a shaded JAR that the connector catalog downloads and
SHA-256-verifies at runtime.

```
engines/redis/
├── pom.xml
└── src/
    ├── main/java/com/bablsoft/accessflow/engine/redis/
    │   ├── RedisQueryEngine.java            # implements core.api.QueryEngine
    │   ├── RedisCommandParser.java          # dialect -> QueryType classification
    │   ├── RedisQueryExecutor.java
    │   ├── RedisRowSecurityApplier.java     # fails CLOSED on anything unprovable
    │   ├── RedisSchemaIntrospector.java
    │   ├── RedisExceptionTranslator.java
    │   └── RedisEngineSettings.java
    ├── main/resources/META-INF/services/
    │   └── com.bablsoft.accessflow.core.api.QueryEngine   # one line: the impl FQCN
    └── test/java/...
```

The version line is the load-bearing part of the POM:

```xml
<!-- engines/redis/pom.xml:8 -->
<artifactId>accessflow-engine-redis</artifactId>
<!--
  The plugin has its OWN version line, independent of the application release version
  (release.yml never runs versions:set here). The connector manifest
  (connectors/redis/connector.json) pins this version + the SHA-256 of the shaded JAR;
  bump BOTH together whenever the engine (or a core.api type it compiles against) changes.
-->
<version>1.0.1</version>
```
```xml
<maven.compiler.release>25</maven.compiler.release>
<!-- Reproducible build: the shaded JAR's SHA-256 is pinned in the connector manifest. -->
<project.build.outputTimestamp>2026-01-01T00:00:00Z</project.build.outputTimestamp>
```

`accessflow` and `slf4j-api` are `provided` — shading them breaks the classloader contract. Every
*other* shared library is relocated under the engine's own namespace:

```xml
<relocation>
    <pattern>org.apache.commons.pool2</pattern>
    <shadedPattern>com.bablsoft.accessflow.engine.redis.shaded.org.apache.commons.pool2</shadedPattern>
</relocation>
```

The SPI impl is `final`, has a **public no-arg constructor** (ServiceLoader needs it), and does its
wiring in `initialize()` rather than via DI:

```java
// RedisQueryEngine.java:24
public final class RedisQueryEngine implements QueryEngine {
    public RedisQueryEngine() { }                       // :35 — ServiceLoader contract
    @Override public String engineId() { return "redis"; }
    @Override public void initialize(QueryEngineContext context) { ... }   // :44
}
```

## Required (acceptance checklist)

- [ ] Folder basename == connector id == `engineId()` return value. All automation derives from it:
      the `engines/*` loops, the CI matrix at `.github/workflows/ci.yml:424`, the manifest path.
- [ ] `com.bablsoft.accessflow:accessflow` and `slf4j-api` at **`provided`** scope.
- [ ] `project.build.outputTimestamp` fixed, so the build is bit-exact reproducible.
- [ ] Every third-party library except the vendor driver relocated under
      `com.bablsoft.accessflow.engine.<id>.shaded.*`.
- [ ] `META-INF/services/com.bablsoft.accessflow.core.api.QueryEngine` names the impl class.
- [ ] **Row security fails closed** on any shape the engine cannot provably rewrite. Masking goes
      through the shared `core.api.ColumnMasker` — never a bespoke implementation.
- [ ] Three test tiers present: unit (parser / settings / translator / result mapper /
      row-security applier), a Testcontainers facade IT driving the whole SPI, and
      `ShadedJarServiceLoaderIT` loading the built `*-all.jar` in an isolated `URLClassLoader`.
- [ ] **Re-pin in the same PR**: bump `<version>` → `mvn -f engines/<id>/pom.xml clean package` →
      `shasum -a 256 engines/<id>/target/*-all.jar` → update `url`, `fileName` and `sha256` in
      `connectors/<id>/connector.json`.
- [ ] `node .github/scripts/check-engine-pins.mjs <id>` and
      `node .github/scripts/validate-connectors.mjs` both clean.
- [ ] Engine id added to the `engines` matrix in `.github/workflows/ci.yml:424`.

Build order — the plugin compiles against the *installed* plain jar, so this comes first:

```bash
mvn -f backend/pom.xml install -DskipTests
mvn -f engines/<id>/pom.xml clean verify
```

## Anti-patterns

- **Bumping `<version>` without re-pinning `connector.json`** (or vice versa) → CI fails on SHA
  drift, and a half-landed pin means the catalog downloads a JAR whose hash doesn't match.
  These two files are one commit, always.
- **Shading `accessflow` or `slf4j-api`** → the plugin loads its own copy of `core.api` in an
  isolated classloader, so `instanceof QueryEngine` against the host's interface is false and
  ServiceLoader silently finds nothing.
- **Leaving a shared library un-relocated** → two engines that both bundle Jackson or Netty at
  different versions collide in the host JVM.
- **Changing `project.build.outputTimestamp`, or bumping the test BOM** → the shaded jar's SHA-256
  changes even with identical source, and the pin check fails. The `spring-boot-dependencies` BOM
  leaks into the shade; treat any BOM bump as a re-pin.
- **Row security that "best-effort" rewrites an unsupported shape** → this is the whole product
  promise. If you can't prove the predicate constrains the result, deny. Redis is the documented
  extreme: row predicates have no key-value meaning, so it fails closed on all of them.
- **A private masking implementation** → drifts from `ColumnMasker` and silently stops redacting a
  type the shared one learned about.
- **Using `DriverManager`** → unusable across the plugin's isolated classloader. Instantiate the
  driver directly (see `engines/snowflake/`).

## Extending

**Two engines, one JAR** is an established pattern when the wire protocol is compatible: the
Cassandra plugin registers a second provider with `engineId="scylladb"`, and the Elasticsearch
plugin one with `engineId="opensearch"`. Each still gets its own `connectors/<id>/connector.json`
and its own config lane.

**Per-engine tuning needs no host code.** Anything under `accessflow.proxy.engines.<id>.*` is
passed verbatim into `QueryEngineContext`'s config map, so operators reach it as
`ACCESSFLOW_PROXY_ENGINES_<ID>_<KEY>`. Document the keys; don't add host-side properties.

Adding a whole new engine touches ~12 areas beyond the plugin itself (`DbType`, migration +
sidecar, `validateDriverChoice` when the connection model isn't host/port/db/user/pass, i18n ×7,
the frontend union + `engineModes` + icon, `docs/14-connectors.md`, `docs/05-backend.md`,
`website/`, the CI matrix). Use the `add-engine` skill, which walks the full sequence, and read
`docs/15-engine-sdk.md` first.
