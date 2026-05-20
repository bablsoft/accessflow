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
- **Mailcrab** (`marlonb/mailcrab:latest`) — SMTP catcher. The backend's
  bootstrap reconciler points system SMTP at `mailcrab:1025`, so any
  transactional email the product sends (today: password-reset emails) is
  captured here instead of leaving the stack. Mailcrab's HTTP/JSON API is
  published on `localhost:1080` so `tests/auth-forgot-password.spec.ts` can
  poll it for the rendered email and scrape the one-time token from the URL.
- **Backend** built from [`../backend/Dockerfile`](../backend/Dockerfile). Boots
  with `ACCESSFLOW_BOOTSTRAP_ENABLED=true`, which makes the bootstrap reconciler
  create the org + admin (and seed system SMTP pointing at Mailcrab) on
  `ApplicationReadyEvent`. Published on `localhost:8080`.
- **Frontend** built from [`../frontend/Dockerfile`](../frontend/Dockerfile),
  served by nginx. Published on `localhost:5173`. Its runtime config defaults
  `apiBaseUrl` to `http://localhost:8080`, which matches the published backend
  port — so the browser talks to the backend directly via CORS.

Every service has a healthcheck and the `npm run stack:up` script passes
`--wait`, so the command does not return until all five are healthy (or fails
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

`tests/auth-login-failures.spec.ts` covers the four failure modes of the
login form so regressions in `LoginPage` error rendering or
`authStore.login` error mapping fail loudly:

1. **Empty email** — submitting with the email blank surfaces the AntD
   `Form.Item` "Email is required." error and fires no `POST /auth/login`.
2. **Invalid email format** — `not-an-email` triggers "Enter a valid email
   address." and fires no request.
3. **Wrong password (real 401)** — the seeded admin email plus
   `WrongPassword!` returns 401; the spec asserts the `role="alert"` banner
   with "Invalid email or password.", the `TraceIdFooter` label, that the
   password field is cleared, and that the email is retained.
4. **Server 500 (intercepted)** — `page.route()` injects a 500 with a
   synthetic `application/problem+json` body carrying a known `traceId`.
   The spec asserts the alert banner renders and that
   `TraceIdFooter` shows the truncated trace id.

`tests/auth-forgot-password.spec.ts` covers the password-reset recovery path
end-to-end, including the SMTP send + email scrape via Mailcrab:

1. **Invalid token** — visiting `/reset-password/this-token-does-not-exist`
   renders the generic "This reset link is invalid or has expired." Alert and
   no password form.
2. **Mismatched confirm** — fills `password` and `confirm_password` with
   different values, asserts the inline "Passwords do not match." AntD
   validation error fires, and confirms no `POST /api/v1/auth/password/reset/`
   request leaves the browser.
3. **Happy path** — clicks the *Forgot?* link on `/login`, submits the admin
   email, polls Mailcrab (`GET http://localhost:1080/api/message/{id}`) for
   the rendered email, extracts the one-time token from the reset URL with
   `/\/reset-password\/([A-Za-z0-9_-]+)/`, sets a new password, asserts the
   "Password updated. Sign in to continue." banner on `/login`, and logs in
   with the new password to confirm it landed.
4. **Replay used token** — consumes a fresh token, then re-visits the same
   URL and asserts the server-rejected (USED, HTTP 422) state renders the
   invalid-link Alert.

A `test.afterAll` mints one final reset token and applies it to put the
admin password back to `E2ePassword!123` so the rest of the suite stays
unaffected.

`tests/auth-invitation.spec.ts` covers the admin → invitee onboarding loop
end-to-end, including the SMTP send + email scrape via Mailcrab:

1. **Invalid token** — visiting `/invite/this-token-does-not-exist` renders the
   error alert and no password form.
2. **Mismatched confirm** — admin invites a fresh address, the spec scrapes the
   token from Mailcrab, opens `/invite/{token}`, fills mismatched passwords, and
   asserts the inline "Passwords do not match." validation fires while no
   `POST /api/v1/auth/invitations/{token}/accept` request leaves the browser.
3. **Happy path** — two browser contexts (admin + invitee). Admin logs in, opens
   the **Invite via email** modal, sends an invitation for a per-run unique
   address, and asserts the pending-invitations table updates. The spec pulls
   the token from Mailcrab, the invitee context visits `/invite/{token}`,
   asserts the preview greeting renders ("You have been invited to E2E Test Org
   as ANALYST"), sets a new password, gets redirected to `/login`, and finally
   logs in as the brand-new user to confirm it lands on `/editor`.
4. **Replay accepted token** — re-visits the same token after acceptance and
   asserts the server-rejected (`INVITATION_ALREADY_ACCEPTED`, HTTP 422) state
   renders the error alert.

Test scenarios are kept isolated by using `invitee-${randomUUID()}@e2e.local` as
the invited address so CI retries and partial re-runs don't trip the
`DUPLICATE_PENDING_INVITATION` (409) or `EMAIL_ALREADY_EXISTS` (409) guards.
Tests 3 and 4 share the captured token via a module-scoped `let`, which is
safe because Playwright runs the suite serially with `workers: 1`.

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

`tests/auth-setup-wizard.spec.ts` runs against a **separate variant stack**
(`docker-compose.e2e.setup.yml` on ports 5174/8081) that boots WITHOUT a
pre-seeded admin (`ACCESSFLOW_BOOTSTRAP_ENABLED=false`). The frontend's
`GET /api/v1/auth/setup-status` then reports `setup_required: true`, so a
visit to `/` redirects to the first-run wizard. The spec drives the wizard
end-to-end:

1. **Redirect** — visiting `/` lands the user on `/setup`.
2. **Inline validation** — submitting step 1 with a 7-character password
   surfaces the AntD "Password must be 8–128 characters." error and fires
   no `POST /api/v1/auth/setup`.
3. **Account submit** — fixing the password and clicking **Create admin**
   creates the org + admin (the variant stack's first), authenticates the
   browser, and reveals step 2 (SMTP).
4. **SMTP host required** — clicking **Save & finish** with the host field
   blank surfaces the inline "SMTP host is required." error and fires no
   `PUT /api/v1/admin/system-smtp`.
5. **Server-side failure → banner + retry** — `page.route()` mocks the PUT
   to return a 422 ProblemDetail; the spec asserts the AntD alert renders
   with the mocked title and that the **Skip for now** button remains
   enabled (the "user can retry or skip" branch from the issue spec).
6. **Real Save → /queries** — un-routing the mock and clicking
   **Save & finish** with a legitimate host persists the SMTP row, navigates
   to `/queries`, and a follow-up `window.__apiClient.get('/api/v1/me')`
   call returns 200 with the brand-new admin email and `role: 'ADMIN'`.

This is the only spec that runs against a stack *without* the seeded admin —
keep it separate from the rest of the suite.

`tests/auth-saml-login.spec.ts` runs against a **third variant stack**
([`docker-compose.e2e.sso.yml`](docker-compose.e2e.sso.yml) on ports 5175 /
8082 / 8085) that boots the same seeded admin **plus** a
[`kristophjunge/test-saml-idp`](https://github.com/kristophjunge/docker-test-saml-idp)
container (SimpleSAMLphp). The backend's `ACCESSFLOW_BOOTSTRAP_SAML_*` env
vars point at the IdP — the [`SamlReconciler`](../backend/src/main/java/com/bablsoft/accessflow/bootstrap/internal/reconcile/SamlReconciler.java)
upserts the row on `ApplicationReadyEvent`, so `GET /api/v1/auth/saml/enabled`
reports `true` and the LoginPage renders the **Continue with SAML SSO**
button. SimpleSAMLphp's `SIMPLESAMLPHP_BASEURLPATH` is set to the absolute
URL `http://localhost:8085/simplesaml/` so the metadata it emits is
browser-followable from the host, even though the backend fetches it via the
docker-network alias `saml-idp:8080`. Demo IdP users are baked into the
image:

| Username | Password   | Email               |
|----------|-----------|----------------------|
| `user1`  | `user1pass` | `user1@example.com` |
| `user2`  | `user2pass` | `user2@example.com` |

The spec drives three scenarios:

1. **Happy path** — clicks **Continue with SAML SSO**, completes the IdP
   login as `user1`, and asserts the browser lands on `/editor` with a
   JIT-provisioned user (`auth_provider: 'SAML'`) from
   `GET /api/v1/me`.
2. **Wrong IdP password** — submits the IdP form with a wrong password and
   asserts the browser stays on the IdP origin (never reaches the AccessFlow
   callback).
3. **Server-side SAML rejection** — direct-visits
   `/auth/saml/callback?error=SAML_SIGNATURE_INVALID` (and
   `?error=SAML_LOGIN_FAILED`) to exercise the
   [`SamlCallbackPage`](../frontend/src/pages/auth/SamlCallbackPage.tsx)
   error-rendering branch the [`SamlLoginFailureHandler`](../backend/src/main/java/com/bablsoft/accessflow/security/internal/saml/SamlLoginFailureHandler.java)
   produces when Spring SAML rejects the response.

### Running the setup-wizard spec

The variant stack is managed automatically by Playwright's
`globalSetup`/`globalTeardown` in `playwright.setup.config.ts`, so a single
command drives the whole thing:

```bash
cd e2e
npm run test:setup
```

If you want to drive the variant stack yourself (debugging, or running with
`test:headed` against `--config=playwright.setup.config.ts`):

```bash
npm run stack:setup:up    # builds + brings the variant stack up
npm run stack:setup:logs  # tail logs
npm run stack:setup:down  # tear down, drop the Postgres volume
```

The variant stack uses **different host ports** from the main stack (5174 for
the frontend, 8081 for the backend) so both can coexist if you want to run
both suites locally without bouncing one. Mailcrab is omitted from the
variant — the wizard's SMTP step persists the row but does not dial out.

### Running the SAML SSO spec

The SSO-variant stack is also managed via Playwright's
`globalSetup`/`globalTeardown` in
[`playwright.sso.config.ts`](playwright.sso.config.ts):

```bash
cd e2e
npm run test:sso
```

To drive the variant stack yourself (debugging, or `test:headed` against
`--config=playwright.sso.config.ts`):

```bash
npm run stack:sso:up    # builds + brings the SSO variant up (frontend 5175,
                        # backend 8082, mock IdP 8085)
npm run stack:sso:logs  # tail logs (backend + IdP + frontend + postgres + redis)
npm run stack:sso:down  # tear down, drop the Postgres volume
```

Distinct host ports (5175 / 8082 / 8085) and a distinct compose project
name (`accessflow-e2e-sso`) so this variant can coexist with the main
(5173 / 8080) and setup-wizard (5174 / 8081) stacks.

## Adding new specs

Drop a new `*.spec.ts` under `tests/`. The suite runs serially with a single
worker because all scenarios share the one seeded admin — keep that contract
in mind when adding tests. If you need a fresh database between specs, tear
the stack down and bring it back up between runs.

Specs that need a different stack belong in their own variant config:

- **No-admin stack** — `playwright.setup.config.ts` (today only
  `auth-setup-wizard.spec.ts`).
- **Mock SAML IdP stack** — `playwright.sso.config.ts` (today only
  `auth-saml-login.spec.ts`).

When you add another spec to one of these, update the variant config's
`testMatch` AND the main `playwright.config.ts` `testIgnore`. The variant
stacks rebuild on every `npm run test:setup` / `npm run test:sso` because
`globalTeardown` runs `down -v`.

## CI

The suite runs in the `e2e` job of [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)
whenever a PR touches `e2e/**`, `frontend/**`, or `backend/**`. The job runs
**three** suites sequentially: first `npm test` against the main stack, then
`npm run test:setup` against the setup-variant stack, then `npm run test:sso`
against the SSO-variant stack (each variant manages its own stack via its
config's `globalSetup`/`globalTeardown`). The job is part of the `CI Gate`
aggregate, so it must be green for the gate to pass.
