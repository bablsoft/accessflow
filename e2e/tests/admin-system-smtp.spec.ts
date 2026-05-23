import {
  test,
  expect,
  type APIRequestContext,
  type Page,
  type Route,
} from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Unique suffix keeps the from_name mutation visually distinct across reruns
// against a long-lived database (the e2e stack reuses postgres data between
// `stack:up` cycles unless torn down with `-v`). Same pattern as AF-278.
const UNIQUE_SUFFIX = `af279-${Date.now()}`;
const NEW_FROM_NAME = `AccessFlow E2E ${UNIQUE_SUFFIX}`;

const DEFAULT_API_BASE = 'http://localhost:8080';

// Bootstrap-seeded SMTP values from e2e/docker-compose.e2e.yml — restored in
// afterAll so adjacent specs (auth-invitation, auth-forgot-password) that rely
// on a working mailcrab path stay green when this spec runs first.
const SEED_HOST = 'mailcrab';
const SEED_PORT = 1025;
const SEED_TLS = false;
const SEED_FROM_ADDRESS = 'noreply@accessflow.test';
const SEED_FROM_NAME = 'AccessFlow E2E';

const TEST_ENDPOINT_GLOB = '**/api/v1/admin/system-smtp/test';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/admin/system-smtp that SystemSmtpCard issues on
// mount, so the card renders real content before we drive it. The endpoint
// returns 200 when configured and 404 when unconfigured — both flow into the
// card's "loaded" state, so accept any response with status < 500.
async function waitForSmtpLoaded(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/system-smtp$/.test(r.url()) &&
      r.status() < 500,
    { timeout: 15_000 },
  );
}

async function stubTestEndpointOk(page: Page): Promise<void> {
  await page.route(TEST_ENDPOINT_GLOB, async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: '{}',
    });
  });
}

// Stub a 502 ProblemDetail with no `title` field. The frontend's
// adminErrorMessage (frontend/src/utils/apiErrors.ts:132-160) does not map
// SYSTEM_SMTP_DELIVERY_FAILED, so it falls through to body.title → body.detail.
// Omitting `title` from the body forces the body.detail branch, giving a
// deterministic toast string for the assertion.
async function stubTestEndpointError(page: Page, detail: string): Promise<void> {
  await page.route(TEST_ENDPOINT_GLOB, async (route: Route) => {
    await route.fulfill({
      status: 502,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'about:blank',
        status: 502,
        detail,
        error: 'SYSTEM_SMTP_DELIVERY_FAILED',
        timestamp: new Date().toISOString(),
      }),
    });
  });
}

async function restoreSeedSmtp(
  request: APIRequestContext,
  accessToken: string,
): Promise<void> {
  const res = await request.put(`${apiBase()}/api/v1/admin/system-smtp`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: {
      host: SEED_HOST,
      port: SEED_PORT,
      tls: SEED_TLS,
      from_address: SEED_FROM_ADDRESS,
      from_name: SEED_FROM_NAME,
    },
  });
  if (!res.ok()) {
    // eslint-disable-next-line no-console
    console.warn(
      `System SMTP restore returned ${res.status()}: ${await res.text()}`,
    );
  }
}

// AF-279 covers the /admin/notifications System SMTP card flows:
//   1. Edit existing config (real PUT) → success toast + card reflects new name.
//   2. Send test (stubbed OK) → success toast.
//   3. Send test (stubbed 502 ProblemDetail) → error toast with detail text.
//   4. Save with invalid port=0 → backend 400 VALIDATION_ERROR; modal stays
//      open, AntD error message renders.
//
// describe.serial because tests 2-4 depend on the configured state established
// in test 1, and afterAll restores the bootstrap-seeded SMTP row so adjacent
// specs (auth-invitation, auth-forgot-password) that depend on mailcrab
// delivery keep working. The Playwright project is already workers=1, so this
// is belt-and-braces.
test.describe.serial('/admin/notifications — System SMTP card', () => {
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (adminAccessToken) {
      await restoreSeedSmtp(request, adminAccessToken);
    }
  });

  test('1) edit existing config → success toast and card reflects new from_name', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForSmtpLoaded(page);

    const smtpCard = page.locator('.af-card').filter({ hasText: 'System SMTP' });
    await expect(smtpCard).toBeVisible({ timeout: 10_000 });

    await smtpCard.getByRole('button', { name: 'Edit' }).click();
    const dialog = page.getByRole('dialog', { name: 'System SMTP configuration' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    // The host / port / from_address fields are pre-filled from the seeded
    // mailcrab config; only the from display name changes here. Leaving
    // smtp_password as the "********" mask is correct — the frontend strips
    // it from the payload so the backend preserves the existing password.
    await expect(dialog.getByLabel('Host')).toHaveValue(SEED_HOST);
    await dialog.getByLabel('From display name (optional)').fill(NEW_FROM_NAME);

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/system-smtp$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as { from_name?: string };
    expect(body.from_name).toBe(NEW_FROM_NAME);

    await expect(page.getByText('System SMTP saved', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(dialog).toBeHidden({ timeout: 10_000 });
    await expect(smtpCard).toContainText(NEW_FROM_NAME);
  });

  test('2) send test (stubbed OK) → success toast', async ({ page }) => {
    await stubTestEndpointOk(page);
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForSmtpLoaded(page);

    const smtpCard = page.locator('.af-card').filter({ hasText: 'System SMTP' });
    await expect(smtpCard).toBeVisible({ timeout: 10_000 });

    const testResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/system-smtp\/test$/.test(r.url()),
      { timeout: 15_000 },
    );
    await smtpCard.getByRole('button', { name: 'Send test' }).click();
    expect((await testResponsePromise).status()).toBe(200);

    await expect(
      page.getByText('Test email accepted by the SMTP server', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    await page.unroute(TEST_ENDPOINT_GLOB);
  });

  test('3) send test (stubbed 502 ProblemDetail) → error toast renders detail', async ({
    page,
  }) => {
    const errorDetail = 'Test notification could not be delivered.';
    await stubTestEndpointError(page, errorDetail);
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForSmtpLoaded(page);

    const smtpCard = page.locator('.af-card').filter({ hasText: 'System SMTP' });
    await expect(smtpCard).toBeVisible({ timeout: 10_000 });

    await smtpCard.getByRole('button', { name: 'Send test' }).click();

    await expect(page.getByText(errorDetail, { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    await page.unroute(TEST_ENDPOINT_GLOB);
  });

  test('4) save with invalid port=0 → backend 400, modal stays open, error toast', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForSmtpLoaded(page);

    const smtpCard = page.locator('.af-card').filter({ hasText: 'System SMTP' });
    await expect(smtpCard).toBeVisible({ timeout: 10_000 });

    await smtpCard.getByRole('button', { name: 'Edit' }).click();
    const dialog = page.getByRole('dialog', { name: 'System SMTP configuration' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    // Port has only a client-side `required` rule (no min/max), so 0 passes
    // form validation and the request reaches the backend, which rejects it
    // with @Min(1) → 400 VALIDATION_ERROR.
    await dialog.getByLabel('Port').fill('0');

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/system-smtp$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(400);
    const body = (await saveResponse.json()) as { error?: string };
    expect(body.error).toBe('VALIDATION_ERROR');

    // saveMutation.onError does not close the modal — it renders an AntD
    // error toast and leaves the dialog open so the user can fix the value.
    await expect(dialog).toBeVisible();
    await expect(page.locator('.ant-message-error').first()).toBeVisible({
      timeout: 10_000,
    });

    await dialog.getByRole('button', { name: 'Cancel' }).click();
  });
});
