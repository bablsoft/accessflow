import { test, expect, type APIRequestContext, type Page, type Route } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

// AF-333: the standard e2e stack (docker-compose.e2e.yml) does NOT seed a
// langfuse_config row, so we start from "Langfuse disabled / no credentials".
// Each save round-trips through the real backend (no stubs); only the
// "Test connection" button is stubbed, because there is no reachable Langfuse
// server in CI and we just want to assert the button → toast wiring.
const HOST = 'https://lf.e2e.test';
const PUBLIC_KEY = 'pk-lf-e2e';
const SECRET_KEY = 'sk-lf-e2e-secret';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Gate before driving the form: wait for the GET that LangfuseConfigPage issues
// on mount so the Form.useForm setFieldsValue effect has populated the inputs.
async function waitForLangfuseConfigLoaded(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/langfuse-config$/.test(r.url()) &&
      r.status() < 500,
    { timeout: 15_000 },
  );
}

function switchInItem(page: Page, labelText: string) {
  return page
    .locator('.ant-form-item')
    .filter({ hasText: labelText })
    .getByRole('switch');
}

async function resetLangfuseConfig(request: APIRequestContext, accessToken: string): Promise<void> {
  const res = await request.put(`${apiBase()}/api/v1/admin/langfuse-config`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { enabled: false, host: '', public_key: '', secret_key: '' },
  });
  if (!res.ok()) {
    // eslint-disable-next-line no-console
    console.warn(`Langfuse config reset returned ${res.status()}: ${await res.text()}`);
  }
}

test.describe.serial('/admin/langfuse — config CRUD', () => {
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (adminAccessToken) {
      await resetLangfuseConfig(request, adminAccessToken);
    }
  });

  test('1) initial load → form renders with Langfuse disabled', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/langfuse');
    await waitForLangfuseConfigLoaded(page);

    await expect(page.getByRole('heading', { name: 'Langfuse' })).toBeVisible();
    await expect(page.getByText('Connection', { exact: true })).toBeVisible();
    await expect(page.getByText('Features', { exact: true })).toBeVisible();
    await expect(switchInItem(page, 'Enabled')).not.toBeChecked();
  });

  test('2) enable + save credentials → 200, secret masked, success toast', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/langfuse');
    await waitForLangfuseConfigLoaded(page);

    await switchInItem(page, 'Enabled').click();
    await page.getByLabel('Host URL').fill(HOST);
    await page.getByLabel('Public key').fill(PUBLIC_KEY);
    await page.getByLabel('Secret key').fill(SECRET_KEY);

    const savePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' && /\/api\/v1\/admin\/langfuse-config$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await savePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as {
      enabled?: boolean;
      host?: string;
      public_key?: string;
      secret_key?: string;
    };
    expect(body.enabled).toBe(true);
    expect(body.host).toBe(HOST);
    expect(body.public_key).toBe(PUBLIC_KEY);
    // The secret is never echoed back in clear text — it is masked.
    expect(body.secret_key).toBe('********');

    await expect(
      page.getByText('Langfuse configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('3) reload → fields persisted, secret masked', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/langfuse');
    await waitForLangfuseConfigLoaded(page);

    await expect(switchInItem(page, 'Enabled')).toBeChecked();
    await expect(page.getByLabel('Host URL')).toHaveValue(HOST);
    await expect(page.getByLabel('Public key')).toHaveValue(PUBLIC_KEY);
    await expect(page.getByLabel('Secret key')).toHaveValue('********');
  });

  test('4) test connection button → success toast (stubbed)', async ({ page }) => {
    await page.route('**/api/v1/admin/langfuse-config/test', async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ status: 'OK', message: 'Successfully connected to Langfuse' }),
      });
    });
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/langfuse');
    await waitForLangfuseConfigLoaded(page);

    await page.getByRole('button', { name: 'Test connection' }).click();
    await expect(
      page.getByText('Successfully connected to Langfuse', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('5) malformed host URL → inline error, no PUT fires', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/langfuse');
    await waitForLangfuseConfigLoaded(page);

    let putFired = false;
    page.on('request', (req) => {
      if (req.method() === 'PUT' && /\/api\/v1\/admin\/langfuse-config$/.test(req.url())) {
        putFired = true;
      }
    });

    await page.getByLabel('Host URL').fill('not-a-url');
    await page.getByRole('button', { name: 'Save' }).click();

    const hostField = page
      .locator('.ant-form-item')
      .filter({ has: page.getByLabel('Host URL') });
    await expect(
      hostField.locator('.ant-form-item-explain-error').first(),
    ).toContainText('Enter a valid URL', { timeout: 5_000 });

    expect(putFired).toBe(false);
  });
});
