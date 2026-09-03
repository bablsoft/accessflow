# E2E spec

**When to use:** Any new user-facing flow, or a change to a route/form/selector an existing spec
touches.
**Canonical example:** `e2e/tests/attestation-campaign.spec.ts:42` (`test.describe.serial`, API-seeded fixtures, two-user setup)
**Helpers:** `e2e/helpers/datasources.ts` (1068 lines — the shared `*ViaApi` seeding layer), `e2e/helpers/ui.ts`, `e2e/helpers/apiConnectors.ts`, `e2e/helpers/nav.ts`
**CI:** `.github/workflows/ci.yml` — a 3-variant matrix (main / setup / sso)
**Related:** [frontend-page.md](frontend-page.md), `e2e/README.md`

## Shape

Three stacks, three configs, three ports:

| Stack | Compose file | Ports | Why it exists |
|---|---|---|---|
| main | `e2e/docker-compose.e2e.yml` | 5173 / 8080 | admin seeded via `bootstrap` |
| setup | `e2e/docker-compose.e2e.setup.yml` | 5174 / 8081 | **no** admin — drives the first-run wizard |
| sso | `e2e/docker-compose.e2e.sso.yml` | 5175 / 8082 / 8085 | + mock SimpleSAMLphp IdP |

Seeded admin on the main stack: `e2e@accessflow.test` / `E2ePassword!123`.

The main suite is two Playwright projects (`playwright.config.ts`): **`parallel`**
(the default — files run concurrently across workers, `fullyParallel` off so
in-file order and `describe.serial` survive) and **`serial`** (one file at a
time, after the parallel leg; membership = the `SERIAL_SPECS` list). A spec
goes in `serial` only when it mutates stack-shared state — org-singleton
config rows, the seeded admin's credentials/locale — or asserts on org-wide
aggregates (audit log, dashboards). Everything else must self-isolate and
stay in `parallel`. Log in via `helpers/login.ts` `login(page[, email, pwd])`
(API login + refresh-cookie boot), not a hand-rolled /login form drive.

```ts
// e2e/tests/attestation-campaign.spec.ts
import { createAttestationCampaignViaApi, createPostgresDatasource } from '../helpers/datasources';

// Two-user setup + UI table interactions; give the suite a generous budget.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('attestation campaigns (AF-384)', () => {
  test.beforeAll(async ({ request }) => {
    // Seed through the real API, never a test-only endpoint.
  });
});
```

## Required (acceptance checklist)

- [ ] Seed state through the `*ViaApi` helpers and the `bootstrap` module's env vars — **never a
      test-only endpoint**. Production code must not grow a backdoor for tests.
- [ ] New seeding logic goes in `e2e/helpers/`, not inline in the spec, so the next spec reuses it.
- [ ] `test.describe.serial` whenever later tests in the file depend on earlier ones' state.
      Cross-file safety is the project split: shared-org-state mutators and org-wide-aggregate
      readers go in `SERIAL_SPECS` (`playwright.config.ts`); everything else runs in the
      `parallel` project and must isolate itself with `Date.now()`/`randomUUID()`-suffixed names
      and run-unique invitee emails.
- [ ] `login(page[, email, password])` from `helpers/login.ts` for authentication — only specs
      whose subject is the login/credential flow itself drive the /login form.
- [ ] **No `purgeMailcrab` in `parallel`-project specs** — the mailbox is stack-shared; a purge
      destroys mail a concurrent spec is polling for. Unique recipients + the recipient-filtered
      `waitForInviteToken` make it unnecessary.
- [ ] Asserting "my row is in this org-shared paginated table" (users, review plans, /reviews
      queue) → `findRowAcrossPages(page, row)` from `helpers/ui.ts`, never a bare page-1
      `toBeVisible` — concurrent specs push rows past page 1.
- [ ] A plan whose approver's *queue view* the spec asserts on (emptiness, row counts) needs a
      `userId`-scoped approver entry, not `role: 'ADMIN'` — role-scoped plans put every
      concurrent spec's queries into that approver's queue.
- [ ] An explicit generous `test.describe.configure({ timeout })` for multi-user setups.
- [ ] Reaching a page through its **sidebar link** → `expandNavSection(page, group, section)`
      from `helpers/nav.ts` first. Sub-sections start collapsed; only the one holding the current
      route renders open, so a link inside a closed section is not in the DOM at all. Items in the
      top generic group (Dashboard, Review queue) and a group's own flat items need no expanding.
- [ ] Changing an `id`, `aria-label`, or visible label that a spec depends on → update the spec in
      the same commit set.
- [ ] A new route, auth path, or user-driven mutation → add a spec, or state in the PR description
      why it isn't worth covering. The default is "add a spec".

## Anti-patterns

- **A test-only endpoint to seed state** → it ships to production. Use the API and `bootstrap`.
- **`page.request.get('/api/...')` with a relative URL** → `page.request` is same-origin with the
  *frontend*, so a relative `/api/...` hits the SPA and returns HTML, not the backend. Use the
  request-fixture helpers with the API base.
- **`waitForResponse` on a back-navigation to a list** → TanStack Query serves it from cache, no
  request fires, and the wait hangs until timeout. Wait on the UI instead.
- **`getByText` inside AntD `Tabs`** → inactive panels stay mounted with `display:none`, so the
  match is ambiguous (strict-mode violation) and `.first()` may pick a hidden one, making
  `toBeVisible` time out. Scope with `getByRole('tabpanel')`.
- **Assuming a button's accessible name is its visible label** → AntD icon buttons expose the
  *icon's* `aria-label`; match with a regex, not an exact string.
- **Running the main stack while the local dev app is up** → both bind host port 5173. Free the
  port or set `E2E_BASE_URL` / `E2E_API_BASE`. This trap is called out at the top of
  `attestation-campaign.spec.ts:1`.
- **Sending a request body that omits a boolean** → an *absent* (not merely null) primitive
  `boolean` record param 500s under Jackson 3's `FAIL_ON_NULL_FOR_PRIMITIVES`. Send every boolean
  in hand-authored bodies. API JSON is `snake_case`.

## Extending

Local run:

```bash
cd e2e && npm ci && npx playwright install --with-deps chromium
npm run stack:up && npm test && npm run stack:down
```

`npm run test:setup` and `npm run test:sso` each manage their own stack via
`globalSetup`/`globalTeardown`, so they are single commands. The main config `testIgnore`s the
setup spec.

Backend changes count too: anything that flips behaviour for a covered flow — the login payload,
refresh-cookie semantics, the bootstrap reconciler — must update the spec in the same commit set.
