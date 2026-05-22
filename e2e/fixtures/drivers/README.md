# E2E custom-driver fixtures (AF-274)

These fixtures back [`e2e/tests/admin-custom-drivers.spec.ts`](../../tests/admin-custom-drivers.spec.ts),
which exercises the `/admin/drivers` upload + delete-in-use guard.

| File | Purpose |
|---|---|
| `test-driver.jar` | Minimal **valid** JDBC driver JAR. One class, `com.accessflow.e2e.StubDriver`, implementing `java.sql.Driver` with default impls. Passes the backend `URLClassLoader` probe in [`DefaultCustomJdbcDriverService`](../../../backend/src/main/java/com/bablsoft/accessflow/core/internal/DefaultCustomJdbcDriverService.java) (class found, implements `Driver`, has a public no-arg constructor). The spec computes its SHA-256 at runtime via Node `crypto`, so the binary may be regenerated without touching test code. |
| `not-a-driver.jar` | **Invalid** fixture — plain text bytes with a `.jar` extension. The frontend dragger filters by filename only, so this travels to the backend, where the probe fails and the API returns `422 CUSTOM_DRIVER_INVALID_JAR`. |
| `src/com/accessflow/e2e/StubDriver.java` | Source for `test-driver.jar`. Kept in-tree so the binary can be rebuilt without external state. |

## What goes inside `test-driver.jar`

```
META-INF/MANIFEST.MF
com/accessflow/e2e/StubDriver.class
```

No `META-INF/services/java.sql.Driver` — the backend doesn't read it.
No `static` initializer in `StubDriver` either, so the probe-load has
zero side effects (it does **not** register itself with `DriverManager`).

## Regenerating `test-driver.jar`

Any JDK ≥ 11 will do (`--release 11` keeps the class file portable):

```bash
cd e2e/fixtures/drivers
rm -rf build
mkdir build
javac --release 11 -d build src/com/accessflow/e2e/StubDriver.java
jar cf test-driver.jar -C build .
rm -rf build
unzip -l test-driver.jar   # sanity check
```

The resulting JAR should be ~1 KB and contain exactly the two entries
listed above. The spec recomputes the SHA-256 on every run, so no test
constants need updating after a rebuild.

## Why not download a real driver at test time?

A real PostgreSQL driver JAR is ~1 MB and would need to be pulled from
Maven Central (or committed as a binary blob), coupling the e2e
package to either a network round-trip or a non-trivial committed
artefact. The stub driver is ≤ 2 KB, fully self-contained, and only
needs to satisfy the backend's probe — it is never actually asked to
open a connection. `POST /api/v1/datasources` does not test
connectivity on create (see
[`DatasourceAdminServiceImpl.create`](../../../backend/src/main/java/com/bablsoft/accessflow/core/internal/DatasourceAdminServiceImpl.java)),
which is what makes the in-use guard test feasible without a real DB
behind the stub.
