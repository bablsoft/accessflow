# AccessFlow End-to-End Tests

Self-contained Playwright suite. It spins up Postgres + Redis + the AccessFlow
backend + frontend via [`docker-compose.e2e.yml`](docker-compose.e2e.yml), then
drives Chromium against `http://localhost:5173` to exercise the real auth flow.

The backend's [`bootstrap` module](../backend/src/main/java/com/bablsoft/accessflow/bootstrap/internal/)
seeds a deterministic admin on first start (idempotent — re-running compose
without volumes re-seeds it):

| Setting | Value |
|---------|-------|
| Organization | `E2E Test Org` |
| Email | `e2e@accessflow.test` |
| Password | `E2ePassword!123` |
| Role | `ADMIN` |

## Running locally

Requirements: Node 24, Docker (with Compose v2), and Playwright browsers
(installed on first run).

```bash
cd e2e
npm ci
npx playwright install --with-deps chromium

# Bring the full stack up — builds backend + frontend images from the working
# tree. First run takes ~3-4 min; subsequent runs use cached Docker layers.
npm run stack:up

# Run the suite (Chromium, baseURL=http://localhost:5173)
npm test

# Tear the stack down (drops the Postgres volume so the next run re-seeds)
npm run stack:down
```

Headed mode for debugging:

```bash
npm run test:headed
```

Tail backend / frontend logs while the stack is running:

```bash
npm run stack:logs
```

## How the stack wires together

- **Postgres** (`postgres:18`) on the compose network. Audit role provisioned
  via [`../deploy/postgres-init/01-audit-role.sql`](../deploy/postgres-init/01-audit-role.sql),
  mounted into `/docker-entrypoint-initdb.d/`.
- **Redis** (`redis:8-alpine`) on the compose network.
- **Backend** built from [`../backend/Dockerfile`](../backend/Dockerfile). Boots
  with `ACCESSFLOW_BOOTSTRAP_ENABLED=true`, which makes the bootstrap reconciler
  create the org + admin on `ApplicationReadyEvent`. Published on `localhost:8080`.
- **Frontend** built from [`../frontend/Dockerfile`](../frontend/Dockerfile),
  served by nginx. Published on `localhost:5173`. Its runtime config defaults
  `apiBaseUrl` to `http://localhost:8080`, which matches the published backend
  port — so the browser talks to the backend directly via CORS.

Every service has a healthcheck and the `npm run stack:up` script passes
`--wait`, so the command does not return until all four are healthy (or fails
fast).

## What the suite tests

`tests/auth.spec.ts` covers three sequenced scenarios:

1. **Login** with the seeded admin → redirected to `/editor` → a protected
   request (`GET /api/v1/users/me`) returns 200.
2. **Transparent refresh on 401** — the access token is intentionally corrupted
   via `window.__authStore.setState({ accessToken: 'broken' })`. The next
   protected request still succeeds because the response interceptor in
   [`frontend/src/api/client.ts`](../frontend/src/api/client.ts) catches the
   401, calls `/auth/refresh` with the HttpOnly cookie, retries, and rotates
   the in-memory token.
3. **Logout** clears the auth store and redirects to `/login`.

`tests/datasource-create-wizard.spec.ts` drives the four-step datasource
creation wizard:

1. Logs in as the seeded admin and opens `/datasources/new` via the
   **Add datasource** button on the list page.
2. Picks the bundled **PostgreSQL** option (asserting the `Bundled` badge).
3. Asserts the connection form is pre-filled with port `5432` and SSL mode
   `VERIFY_FULL`, then switches SSL mode to `DISABLE` so the bare `postgres:18`
   container (no TLS configured) accepts the JDBC handshake.
4. Fills name / host / database / username / password targeting the compose
   network's own Postgres (`postgres:5432/accessflow`).
5. Clicks **Save and test**, then **Test connection**, and asserts the success
   Alert shows a real `Connected · {ms} ms` latency.
6. Clicks **Next**, **Save and finish**, and asserts the wizard lands on
   `/datasources/{id}/settings` with the *Datasource created* toast.

## Adding new specs

Drop a new `*.spec.ts` under `tests/`. The suite runs serially with a single
worker because all scenarios share the one seeded admin — keep that contract
in mind when adding tests. If you need a fresh database between specs, tear
the stack down and bring it back up between runs.

## CI

The suite runs in the `e2e` job of [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)
whenever a PR touches `e2e/**`, `frontend/**`, or `backend/**`. The job is part
of the `CI Gate` aggregate, so it must be green for the gate to pass.
