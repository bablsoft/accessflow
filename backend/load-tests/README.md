# Load tests

Manual [k6](https://k6.io) scripts — not wired into CI. Install k6 locally (`brew install k6`).

## concurrency-budget.js (issue #49)

Demonstrates that the global query-execution concurrency budget
(`accessflow.proxy.execution.max-concurrent` / `acquire-timeout`) holds under saturation: overflow
traffic gets `503 QUERY_CONCURRENCY_LIMIT` and successful throughput never exceeds
`max-concurrent / sleep-seconds` per second.

The script drives `POST /api/v1/queries/break-glass` with `SELECT pg_sleep(N)` because break-glass
executes **synchronously** through the full guarded `QueryExecutor.execute()` path (no async
workflow polling), giving each request a deterministic permit-hold time. A cheaper smoke
alternative is `GET /api/v1/datasources/{id}/sample-rows`, which exercises the guarded
`sampleTable()` path but without a controllable hold time.

### Setup

1. Start the backend with the budget tuned down so a laptop can saturate it:

   ```bash
   ACCESSFLOW_PROXY_EXECUTION_MAX_CONCURRENT=4 \
   ACCESSFLOW_PROXY_EXECUTION_ACQUIRE_TIMEOUT=1s \
   mvn spring-boot:run
   ```

2. Create (or reuse) a **PostgreSQL datasource** and grant the test user a permission row with
   `can_break_glass=true` on it (Datasource settings → Permissions, or the bootstrap module).
   `pg_sleep` requires a PostgreSQL target.

3. Grab a JWT for that user (`POST /api/v1/auth/login` → `access_token`).

### Run

```bash
k6 run \
  -e BASE_URL=http://localhost:8080 \
  -e TOKEN=<jwt> \
  -e DATASOURCE_ID=<uuid> \
  backend/load-tests/concurrency-budget.js
```

Optional knobs: `-e SLEEP_SECONDS=2` (permit hold time), `-e MAX_CONCURRENT=4` (must match the
backend env), `-e VUS=20`, `-e DURATION_SECONDS=60`.

### Pass criteria (enforced as k6 thresholds / summary check)

- `queries_rejected_concurrency count > 0` — the budget actually rejects overflow.
- `unexpected_status rate == 0` — every response is a 200 execution or a 503 whose `error` is
  `QUERY_CONCURRENCY_LIMIT`.
- `cap_holds` — completed executions ≤ `max_concurrent / sleep_seconds × duration × 1.25`
  (the script throws, i.e. exits non-zero, when violated).

Note: each break-glass run fans out admin notifications and opens a retro-review event — run this
against a disposable/local environment, never a shared one.
