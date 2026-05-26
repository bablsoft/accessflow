import {
  test,
  expect,
  type APIRequestContext,
  type Browser,
  type Page,
} from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

// AF-281 covers the /admin/oauth2 CRUD surface end-to-end against the real
// backend. The standard e2e stack (docker-compose.e2e.yml) does NOT seed any
// oauth2_config rows, so each provider tab starts empty. The issue notes:
// "Repeat the enable/disable cycle for one provider per spec run is enough —
// the others are structurally identical." We pick GOOGLE — no Microsoft
// tenant ID, no GitHub read:org warning, no OIDC URL set — so the spec
// stays readable.

const GOOGLE_CLIENT_ID = 'e2e-google-client-id';
const GOOGLE_CLIENT_ID_UPDATED = 'e2e-google-client-id-2';
const GOOGLE_CLIENT_SECRET = 'e2e-google-client-secret';

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

// Gate before driving the OAuth2 form: wait for the GET that OAuth2ConfigPage
// issues on mount so the Form.useForm setFieldsValue effect has populated the
// inputs. Mirrors waitForSamlConfigLoaded in admin-saml-config.spec.ts.
async function waitForOAuth2ConfigLoaded(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/oauth2-config$/.test(r.url()) &&
      r.status() < 500,
    { timeout: 15_000 },
  );
}

// A NEW browser context bypasses both the Zustand auth store (in-memory) and
// TanStack Query's staleTime on ['oauth2Providers'] — so the LoginPage button
// state reflects the backend without waiting for the cache window. Each probe
// also tears the context down so we don't leak sessions.
async function probeLoginGoogleButton(browser: Browser): Promise<{
  visible: boolean;
}> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.goto('/login');
    // The button is rendered conditionally on GET /api/v1/auth/oauth2/providers;
    // wait for that probe to land before asserting either way.
    await page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/auth\/oauth2\/providers$/.test(r.url()) &&
        r.status() < 500,
      { timeout: 10_000 },
    );
    const button = page.getByRole('button', { name: /Continue with Google/i });
    const visible = await button.isVisible().catch(() => false);
    return { visible };
  } finally {
    await context.close();
  }
}

// Best-effort tear-down: drop the GOOGLE row so adjacent specs (and re-runs of
// this one) start from a clean slate. Tolerate non-2xx — test 1 may have failed
// before any config was written, in which case the DELETE returns 404.
async function deleteGoogleConfig(
  request: APIRequestContext,
  accessToken: string,
): Promise<void> {
  const res = await request.delete(
    `${apiBase()}/api/v1/admin/oauth2-config/GOOGLE`,
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(
      `OAuth2 GOOGLE config cleanup returned ${res.status()}: ${await res.text()}`,
    );
  }
}

// describe.serial because each scenario depends on the singleton GOOGLE
// oauth2_config row's state established by the previous one. The Playwright
// project is already workers=1, so this is belt-and-braces.
test.describe.serial('/admin/oauth2 — config CRUD (no provider roundtrip)', () => {
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (adminAccessToken) {
      await deleteGoogleConfig(request, adminAccessToken);
    }
  });

  test('1) initial load → tabs render, Google empty, login page has no Google button', async ({
    page,
    browser,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/oauth2');
    await waitForOAuth2ConfigLoaded(page);

    await expect(page.getByRole('heading', { name: 'OAuth providers' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Google' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'GitHub', exact: true })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Microsoft' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'GitLab', exact: true })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'OpenID Connect' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'GitHub Enterprise' })).toBeVisible();
    await expect(page.getByRole('tab', { name: 'GitLab (self-managed)' })).toBeVisible();

    // Google tab is the initial active tab — the Active switch should reflect
    // the unseeded "disabled" default, and both credentials fields should be
    // empty.
    const activeTab = page.locator('.ant-tabs-tabpane-active');
    await expect(activeTab.getByRole('switch')).not.toBeChecked();
    await expect(activeTab.getByLabel('Client ID')).toHaveValue('');
    await expect(activeTab.getByLabel('Client secret')).toHaveValue('');

    const probe = await probeLoginGoogleButton(browser);
    expect(probe.visible).toBe(false);
  });

  test('2) enable Google + save credentials → toast; /login shows Google button', async ({
    page,
    browser,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/oauth2');
    await waitForOAuth2ConfigLoaded(page);

    const activeTab = page.locator('.ant-tabs-tabpane-active');
    await activeTab.getByLabel('Client ID').fill(GOOGLE_CLIENT_ID);
    await activeTab.getByLabel('Client secret').fill(GOOGLE_CLIENT_SECRET);
    await activeTab.getByRole('switch').click();
    await expect(activeTab.getByRole('switch')).toBeChecked();

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/oauth2-config\/GOOGLE$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as {
      provider?: string;
      active?: boolean;
      client_id?: string;
      client_secret?: string;
    };
    expect(body.provider).toBe('GOOGLE');
    expect(body.active).toBe(true);
    expect(body.client_id).toBe(GOOGLE_CLIENT_ID);
    // Response masks the secret (POST/PUT echoes the GET shape).
    expect(body.client_secret).toBe('********');

    await expect(
      page.getByText('OAuth configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    const probe = await probeLoginGoogleButton(browser);
    expect(probe.visible).toBe(true);
  });

  test('3) reload → fields persisted, secret masked, mask-preserve roundtrip', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/oauth2');
    await waitForOAuth2ConfigLoaded(page);

    const activeTab = page.locator('.ant-tabs-tabpane-active');
    await expect(activeTab.getByLabel('Client ID')).toHaveValue(GOOGLE_CLIENT_ID);
    await expect(activeTab.getByLabel('Client secret')).toHaveValue('********');
    await expect(activeTab.getByRole('switch')).toBeChecked();

    // Edit only the Client ID. The frontend strips the masked secret from the
    // payload (`client_secret: undefined`), so the backend preserves the
    // existing ciphertext. We assert via the response shape that the secret is
    // still configured (masked) after the save.
    await activeTab.getByLabel('Client ID').fill(GOOGLE_CLIENT_ID_UPDATED);

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/oauth2-config\/GOOGLE$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as {
      client_id?: string;
      client_secret?: string;
    };
    expect(body.client_id).toBe(GOOGLE_CLIENT_ID_UPDATED);
    expect(body.client_secret).toBe('********');

    await expect(
      page.getByText('OAuth configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('4) copy redirect URI → clipboard contains backend callback + toast', async ({
    page,
    context,
  }) => {
    // Clipboard reads/writes require explicit permission grants in Chromium —
    // OAuth2ConfigPage uses navigator.clipboard.writeText() on the addon
    // button, and the assertion below calls navigator.clipboard.readText().
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/oauth2');
    await waitForOAuth2ConfigLoaded(page);

    const activeTab = page.locator('.ant-tabs-tabpane-active');
    await activeTab.getByRole('button', { name: 'Copy redirect URI' }).click();

    await expect(
      page.getByText('Redirect URI copied to clipboard', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboardText).toBe(
      `${apiBase()}/api/v1/auth/oauth2/callback/google`,
    );
  });

  test('5) disable Google → toast; /login no longer shows Google button', async ({
    page,
    browser,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/oauth2');
    await waitForOAuth2ConfigLoaded(page);

    const activeTab = page.locator('.ant-tabs-tabpane-active');
    // The previous tests left active=true.
    await expect(activeTab.getByRole('switch')).toBeChecked();
    await activeTab.getByRole('switch').click();
    await expect(activeTab.getByRole('switch')).not.toBeChecked();

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/oauth2-config\/GOOGLE$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as { active?: boolean };
    expect(body.active).toBe(false);

    await expect(
      page.getByText('OAuth configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    const probe = await probeLoginGoogleButton(browser);
    expect(probe.visible).toBe(false);
  });

  test('5b) GitHub Enterprise tab renders base URL field + activation without base_url → 422', async ({
    page,
    request,
  }) => {
    // Drop any pre-existing row so we start from the unseeded default.
    const delRes = await request.delete(
      `${apiBase()}/api/v1/admin/oauth2-config/GITHUB_ENTERPRISE`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect([200, 204, 404]).toContain(delRes.status());

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/oauth2');
    await waitForOAuth2ConfigLoaded(page);

    await page.getByRole('tab', { name: 'GitHub Enterprise' }).click();

    const activeTab = page.locator('.ant-tabs-tabpane-active');
    await expect(activeTab.getByLabel('Server base URL')).toBeVisible();
    await activeTab.getByLabel('Client ID').fill('ghe-id');
    await activeTab.getByLabel('Client secret').fill('ghe-secret');
    await activeTab.getByRole('switch').click();
    await expect(activeTab.getByRole('switch')).toBeChecked();

    // Submit with base_url left blank — Form.Item required rule should block
    // the request before the network round-trip.
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(
      activeTab.getByText('Server base URL is required.', { exact: false }),
    ).toBeVisible({ timeout: 10_000 });

    // Now fill an http:// URL — the frontend type:'url' rule passes
    // (http is a valid URL), but the backend rejects with 422 because
    // enterprise instances must be https.
    await activeTab.getByLabel('Server base URL').fill('http://gh.acme.corp');
    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/oauth2-config\/GITHUB_ENTERPRISE$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(422);
    const body = (await saveResponse.json()) as { error?: string };
    expect(body.error).toBe('OAUTH2_CONFIG_INVALID');

    // Best-effort cleanup so this test doesn't leak state.
    await request.delete(
      `${apiBase()}/api/v1/admin/oauth2-config/GITHUB_ENTERPRISE`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
  });

  test('6) save active with empty credentials → backend 422, error toast, form stays open', async ({
    page,
    request,
  }) => {
    // Start from a clean slate. The backend's PUT semantics treat null
    // client_id / client_secret as "don't modify", so to get the row into a
    // state where activating it would actually be invalid (clientId == null
    // and clientSecretEncrypted == null), we drop the row entirely first.
    // The form then renders the unseeded empty defaults.
    const delRes = await request.delete(
      `${apiBase()}/api/v1/admin/oauth2-config/GOOGLE`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect([200, 204, 404]).toContain(delRes.status());

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/oauth2');
    await waitForOAuth2ConfigLoaded(page);

    const activeTab = page.locator('.ant-tabs-tabpane-active');
    await expect(activeTab.getByLabel('Client ID')).toHaveValue('');
    await expect(activeTab.getByLabel('Client secret')).toHaveValue('');

    await activeTab.getByRole('switch').click();
    await expect(activeTab.getByRole('switch')).toBeChecked();

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/oauth2-config\/GOOGLE$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(422);
    const body = (await saveResponse.json()) as { error?: string };
    expect(body.error).toBe('OAUTH2_CONFIG_INVALID');

    await expect(page.locator('.ant-message-error').first()).toBeVisible({
      timeout: 10_000,
    });

    // Form stays open with the bad input still present so the user can
    // correct it — saveMutation.onError surfaces the toast but does not
    // navigate or reset the form.
    await expect(page).toHaveURL(/\/admin\/oauth2$/);
    await expect(activeTab.getByRole('switch')).toBeChecked();
    await expect(activeTab.getByLabel('Client ID')).toHaveValue('');
  });
});
