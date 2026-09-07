import { test, expect, type Page, type Request } from '@playwright/test';

// First-run setup wizard end-to-end.
//
// This spec runs against the dedicated variant stack
// (docker-compose.e2e.setup.yml on ports 5174/8081), which boots WITHOUT a
// pre-seeded admin (`ACCESSFLOW_BOOTSTRAP_ENABLED=false`). The frontend's
// GET /api/v1/auth/setup-status returns `setup_required: true`, so visiting
// the root sends the user through the three-step wizard:
//   step 1 → account (org + admin email + password; collect only, no request)
//   step 2 → governance domains (AF-898; the step that actually POSTs /auth/setup)
//   step 3 → system SMTP (optional, skippable)
//
// The wizard is the worst-possible regression target — nothing else works if
// setup is broken. Drive it linearly via one long test (same shape as
// auth.spec.ts) so each step builds on the previous state. The variant stack
// has no admin until section C completes, so re-running this spec without
// `npm run test:setup` (which tears the stack down via globalTeardown) would
// land on /login instead of /setup.

const ORG_NAME = 'Setup Wizard Org';
const ADMIN_EMAIL = 'setup-admin@e2e.local';
const ADMIN_DISPLAY = 'Setup Admin';
const ADMIN_PASSWORD = 'SetupAdmin!123';

// Counts requests matching `matches` that are observed while `action` runs.
// Mirrors the helper in auth-login-failures.spec.ts so failure modes can
// assert "no request left the browser" without coupling to specific URLs.
async function countMatchingRequests(
  page: Page,
  matches: (req: Request) => boolean,
  action: () => Promise<void>,
): Promise<number> {
  let count = 0;
  const handler = (req: Request): void => {
    if (matches(req)) count += 1;
  };
  page.on('request', handler);
  try {
    await action();
  } finally {
    page.off('request', handler);
  }
  return count;
}

test('first-run setup wizard: redirect, validation, domain selection, SMTP retry then save', async ({
  page,
}) => {
  // ── A. Bare `/` redirects to /setup, step 1 (account) renders ──────────────
  await page.goto('/');
  await page.waitForURL('**/setup', { timeout: 15_000 });
  await expect(page.getByText('Create the first admin')).toBeVisible();

  // ── B. Password too short → inline validation; no POST /auth/setup ─────────
  await page.getByLabel('Organization name').fill(ORG_NAME);
  await page.getByLabel('Email').fill(ADMIN_EMAIL);
  await page.getByLabel('Display name').fill(ADMIN_DISPLAY);
  await page.getByLabel('Password', { exact: true }).fill('Short1!');
  await page.getByLabel('Confirm password', { exact: true }).fill('Short1!');

  const shortPasswordPosts = await countMatchingRequests(
    page,
    (req) =>
      req.method() === 'POST' && /\/api\/v1\/auth\/setup$/.test(req.url()),
    async () => {
      await page.getByRole('button', { name: /Continue/ }).click();
      await expect(
        page.getByText('Password must be 8–128 characters.'),
      ).toBeVisible();
    },
  );
  expect(shortPasswordPosts).toBe(0);
  expect(new URL(page.url()).pathname).toBe('/setup');

  // ── C. Fix the password → advance to step 2 (governance domains) ───────────
  // The account step only collects: /auth/setup is one-shot and must carry the
  // domain answer, so no request leaves the browser here either (AF-898).
  await page.getByLabel('Password', { exact: true }).fill(ADMIN_PASSWORD);
  await page.getByLabel('Confirm password', { exact: true }).fill(ADMIN_PASSWORD);

  const accountStepPosts = await countMatchingRequests(
    page,
    (req) =>
      req.method() === 'POST' && /\/api\/v1\/auth\/setup$/.test(req.url()),
    async () => {
      await page.getByRole('button', { name: /Continue/ }).click();
      await expect(page.getByText('What will you govern?')).toBeVisible({
        timeout: 15_000,
      });
    },
  );
  expect(accountStepPosts).toBe(0);

  // Both domains default to off; database governance is always on and has no switch.
  const governApis = page.getByLabel('Govern outbound API calls');
  const governDeployments = page.getByLabel('Gate CI/CD deployments');
  await expect(governApis).not.toBeChecked();
  await expect(governDeployments).not.toBeChecked();
  expect(new URL(page.url()).pathname).toBe('/setup');

  // ── C1. Back returns to step 1 with what was typed still there ────────────
  // A server-side rejection (409 EMAIL_ALREADY_EXISTS) surfaces on this step but is fixed on
  // the previous one, so the way back has to work and has to preserve the form.
  await page.getByRole('button', { name: /^Back$/ }).click();
  await expect(page.getByText('Create the first admin')).toBeVisible();
  await expect(page.getByLabel('Organization name')).toHaveValue(ORG_NAME);
  await expect(page.getByLabel('Email')).toHaveValue(ADMIN_EMAIL);
  await page.getByRole('button', { name: /Continue/ }).click();
  await expect(page.getByText('What will you govern?')).toBeVisible();

  // ── C2. Opt into both domains → this is the click that creates the admin ───
  await governApis.click();
  await governDeployments.click();
  await page.getByRole('button', { name: /^Create admin$/ }).click();

  // The page stays on /setup but swaps the form to the SMTP step. The header
  // copy is the load-bearing assertion — both buttons exist on this step.
  await expect(page.getByText('Configure system SMTP (optional)')).toBeVisible({
    timeout: 15_000,
  });
  await expect(
    page.getByRole('button', { name: 'Save & finish' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: 'Skip for now' }),
  ).toBeVisible();
  expect(new URL(page.url()).pathname).toBe('/setup');

  // ── D. SMTP host empty → inline validation; no PUT /admin/system-smtp ──────
  // Fill port + from_address with valid values so the only remaining failure
  // mode is the empty host. AntD's InputNumber takes a number-typed value;
  // .fill('587') puts the string through the spinbutton, which AntD coerces
  // to 587 on blur.
  await page.getByLabel('SMTP port').fill('587');
  await page.getByLabel('From address').fill('noreply@example.com');

  const emptyHostPuts = await countMatchingRequests(
    page,
    (req) =>
      req.method() === 'PUT' && req.url().includes('/api/v1/admin/system-smtp'),
    async () => {
      await page.getByRole('button', { name: 'Save & finish' }).click();
      await expect(page.getByText('SMTP host is required.')).toBeVisible();
    },
  );
  expect(emptyHostPuts).toBe(0);

  // ── E. Server-side failure → error banner + retry path intact ──────────────
  // Mock the PUT to return a 422 ProblemDetail so we exercise the alert render
  // without depending on a backend SMTP failure mode. setupErrorMessage prefers
  // body.title over body.detail when no error code matches, so the banner
  // surfaces the title text.
  const MOCKED_ERROR_TITLE = 'SMTP server rejected the configuration';
  await page.route('**/api/v1/admin/system-smtp', async (route) => {
    if (route.request().method() === 'PUT') {
      await route.fulfill({
        status: 422,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: MOCKED_ERROR_TITLE,
          detail: 'Could not reach the SMTP host you provided.',
          status: 422,
          error: 'VALIDATION_ERROR',
          traceId: 'e2e-smtp-422-trace',
        }),
      });
      return;
    }
    await route.continue();
  });

  await page.getByLabel('SMTP host').fill('smtp.invalid.example');
  await page.getByRole('button', { name: 'Save & finish' }).click();

  const errorBanner = page
    .getByRole('alert')
    .filter({ hasText: MOCKED_ERROR_TITLE });
  await expect(errorBanner).toBeVisible({ timeout: 10_000 });
  // User is still on /setup; Skip is still available — this is the
  // "user can retry or skip" branch from the spec.
  expect(new URL(page.url()).pathname).toBe('/setup');
  await expect(
    page.getByRole('button', { name: 'Skip for now' }),
  ).toBeEnabled();

  // ── F. Drop the mock, real Save & finish → /queries (Branch B happy path) ──
  await page.unroute('**/api/v1/admin/system-smtp');
  await page.getByLabel('SMTP host').fill('smtp.example.com');
  await page.getByRole('button', { name: 'Save & finish' }).click();
  await page.waitForURL('**/queries', { timeout: 15_000 });

  // Authenticated as the freshly-created admin. Mirrors auth.spec.ts:9-20:
  // window.__apiClient is exposed for e2e use in all builds (see
  // frontend/src/api/client.ts:36-38) and carries the access token + refresh
  // cookie, so this is a real authenticated call against the variant backend.
  const me = await page.evaluate(async () => {
    const c = (
      window as unknown as {
        __apiClient?: {
          get: (
            url: string,
          ) => Promise<{
            status: number;
            data: { email: string; role: string };
          }>;
        };
      }
    ).__apiClient;
    if (!c) throw new Error('window.__apiClient is not exposed');
    return c.get('/api/v1/me');
  });
  expect(me.status).toBe(200);
  expect(me.data.email).toBe(ADMIN_EMAIL);
  expect(me.data.role).toBe('ADMIN');

  // ── G. The onboarding checklist reflects the domains just opted into ───────
  // Both domains were switched on in section C2, and the fresh admin holds every
  // permission, so the widget renders five steps — the three database-governance
  // ones plus the two per-domain "create your first X" steps, last and in order.
  const checklist = page.getByRole('region', { name: 'Setup progress' });
  await expect(checklist).toBeVisible({ timeout: 15_000 });
  await expect(checklist.getByText('0/5')).toBeVisible();
  const steps = checklist.getByRole('listitem');
  await expect(steps).toHaveCount(5);
  await expect(steps.nth(3)).toContainText('Create your first API connector');
  await expect(steps.nth(4)).toContainText('Create your first deployment pipeline');
  await expect(
    steps.nth(3).getByRole('link'),
  ).toHaveAttribute('href', '/api-connectors');
  await expect(
    steps.nth(4).getByRole('link'),
  ).toHaveAttribute('href', '/admin/deployment-pipelines');
});
