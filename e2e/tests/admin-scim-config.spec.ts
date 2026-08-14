import {
  test,
  expect,
  type APIRequestContext,
  type Page,
} from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

// #621 — the standard e2e stack seeds no scim_config row, so we start from
// "SCIM disabled / no tokens". The spec drives the /admin/scim page for
// config + token management, then exercises the SCIM protocol surface itself
// with the issued bearer token against the backend origin (page.request with
// a relative URL would hit the SPA, not the backend).

// Unique per run so re-running against a warm stack never collides.
const RUN_ID = Date.now();
const TOKEN_NAME = `e2e-${RUN_ID}`;
const SCIM_USER_EMAIL = `scim-e2e-${RUN_ID}@accessflow.test`;

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

// Gate before driving the form: wait for the GET that ScimConfigPage issues on
// mount so the Form.useForm setFieldsValue effect has populated the inputs.
// Mirrors waitForSamlConfigLoaded in admin-saml-config.spec.ts.
async function waitForScimConfigLoaded(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/scim-config$/.test(r.url()) &&
      r.status() < 500,
    { timeout: 15_000 },
  );
}

async function resetScimConfig(
  request: APIRequestContext,
  accessToken: string,
): Promise<void> {
  const res = await request.put(`${apiBase()}/api/v1/admin/scim-config`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { enabled: false },
  });
  if (!res.ok()) {
    // eslint-disable-next-line no-console
    console.warn(`SCIM config reset returned ${res.status()}: ${await res.text()}`);
  }
}

// #621 covers the /admin/scim surface plus the SCIM protocol end-to-end:
//   1. Initial state — SCIM disabled, defaults rendered, base URL visible.
//   2. Enable + save → toast; PUT round-trips through the real backend.
//   3. Create a bearer token → show-once modal; the raw value authenticates
//      against /scim/v2/ServiceProviderConfig.
//   4. Provision an Okta-shaped user over SCIM → 201; the user appears in the
//      admin users page.
//   5. Entra-shaped PATCH active=false → the user shows as inactive.
//   6. Revoke the token in the UI → the SCIM call now returns 401 in the SCIM
//      error envelope.
//
// describe.serial because each scenario depends on state established by the
// previous one; afterAll disables the config so adjacent specs are unaffected.
test.describe.serial('/admin/scim — config, tokens, and provisioning (#621)', () => {
  let adminAccessToken = '';
  let rawScimToken = '';
  let scimUserId = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (adminAccessToken) {
      await resetScimConfig(request, adminAccessToken);
    }
    // The provisioned user is deliberately left behind (deactivated by test 5):
    // AccessFlow never hard-deletes users, and the per-run unique email prevents
    // collisions on warm-stack reruns.
  });

  test('1) initial load → defaults rendered, SCIM disabled', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/scim');
    await waitForScimConfigLoaded(page);

    await expect(page.getByRole('heading', { name: 'SCIM Provisioning' })).toBeVisible();
    await expect(page.getByText('SCIM endpoint', { exact: true })).toBeVisible();
    await expect(page.getByText('Attribute mapping', { exact: true })).toBeVisible();
    await expect(page.getByText('Bearer tokens', { exact: true })).toBeVisible();
    await expect(page.getByText(/\/scim\/v2$/).first()).toBeVisible();
    // The Enabled switch reflects the unseeded "disabled" default.
    await expect(page.getByRole('switch')).not.toBeChecked();
  });

  test('2) enable + save → toast', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/scim');
    await waitForScimConfigLoaded(page);

    await page.getByRole('switch').click();
    await expect(page.getByRole('switch')).toBeChecked();

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/scim-config$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as { enabled?: boolean };
    expect(body.enabled).toBe(true);

    await expect(
      page.getByText('SCIM configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('3) create token → show-once modal; token authenticates against /scim/v2', async ({
    page,
    request,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/scim');
    await waitForScimConfigLoaded(page);

    await page.getByRole('button', { name: 'Create token' }).click();
    const createDialog = page.getByRole('dialog').filter({ hasText: 'Create SCIM token' });
    await createDialog.getByLabel('Token name').fill(TOKEN_NAME);

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/scim\/tokens$/.test(r.url()),
      { timeout: 15_000 },
    );
    await createDialog.getByRole('button', { name: 'Create token' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const created = (await createResponse.json()) as { raw_token?: string };
    expect(created.raw_token).toMatch(/^af_scim_/);
    rawScimToken = created.raw_token ?? '';

    // The show-once modal displays the same raw value and the copy warning.
    const issuedDialog = page.getByRole('dialog').filter({ hasText: 'SCIM token created' });
    await expect(issuedDialog.getByText(rawScimToken)).toBeVisible();
    await expect(
      issuedDialog.getByText('Copy this token now — it will not be shown again.'),
    ).toBeVisible();
    // Two buttons in the dialog have the accessible name "Close": AntD's X icon
    // (aria-label="Close", first in DOM) and the footer button — target the latter.
    await issuedDialog.getByRole('button', { name: 'Close' }).last().click();
    // AntD keeps the modal mounted through its close animation — wait for it to hide
    // so the token name below matches only the table row, not the modal's copy.
    await expect(issuedDialog).toBeHidden();

    // The token row lists only the prefix, never the raw value.
    await expect(
      page.getByLabel('Bearer tokens').getByText(TOKEN_NAME, { exact: true }),
    ).toBeVisible();

    // The raw token authenticates against the SCIM discovery endpoint.
    const spConfig = await request.get(`${apiBase()}/scim/v2/ServiceProviderConfig`, {
      headers: { Authorization: `Bearer ${rawScimToken}` },
    });
    expect(spConfig.status()).toBe(200);
    const spBody = (await spConfig.json()) as { patch?: { supported?: boolean } };
    expect(spBody.patch?.supported).toBe(true);
  });

  test('4) provision an Okta-shaped user over SCIM → appears in admin users', async ({
    page,
    request,
  }) => {
    const createRes = await request.post(`${apiBase()}/scim/v2/Users`, {
      headers: {
        Authorization: `Bearer ${rawScimToken}`,
        'Content-Type': 'application/scim+json',
      },
      data: {
        schemas: ['urn:ietf:params:scim:schemas:core:2.0:User'],
        userName: SCIM_USER_EMAIL,
        name: { givenName: 'Scim', familyName: 'User', formatted: 'Scim User' },
        displayName: 'Scim User',
        emails: [{ primary: true, value: SCIM_USER_EMAIL, type: 'work' }],
        externalId: `okta-${RUN_ID}`,
        active: true,
      },
    });
    expect(createRes.status()).toBe(201);
    const createdUser = (await createRes.json()) as {
      id: string;
      userName?: string;
      active?: boolean;
    };
    expect(createdUser.userName).toBe(SCIM_USER_EMAIL);
    expect(createdUser.active).toBe(true);
    scimUserId = createdUser.id;

    // A userName eq filter (the Okta dedupe probe) finds the user.
    const filterRes = await request.get(
      `${apiBase()}/scim/v2/Users?filter=${encodeURIComponent(
        `userName eq "${SCIM_USER_EMAIL}"`,
      )}`,
      { headers: { Authorization: `Bearer ${rawScimToken}` } },
    );
    expect(filterRes.status()).toBe(200);
    const filterBody = (await filterRes.json()) as { totalResults?: number };
    expect(filterBody.totalResults).toBe(1);

    // The provisioned user shows up in the admin users page.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await expect(page.getByText(SCIM_USER_EMAIL)).toBeVisible({ timeout: 15_000 });
  });

  test('5) Entra-shaped PATCH active=false → user deactivated', async ({ request }) => {
    const patchRes = await request.patch(`${apiBase()}/scim/v2/Users/${scimUserId}`, {
      headers: {
        Authorization: `Bearer ${rawScimToken}`,
        'Content-Type': 'application/scim+json',
      },
      data: {
        schemas: ['urn:ietf:params:scim:api:messages:2.0:PatchOp'],
        Operations: [{ op: 'Replace', path: 'active', value: 'False' }],
      },
    });
    expect(patchRes.status()).toBe(200);
    const patched = (await patchRes.json()) as { active?: boolean };
    expect(patched.active).toBe(false);

    // The admin API confirms the flag flipped (backend state, not UI cache).
    const adminUsers = await request.get(
      `${apiBase()}/api/v1/admin/users?size=100`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect(adminUsers.ok()).toBe(true);
    const adminBody = (await adminUsers.json()) as {
      content: Array<{ email: string; active: boolean }>;
    };
    const row = adminBody.content.find((u) => u.email === SCIM_USER_EMAIL);
    expect(row).toBeDefined();
    expect(row?.active).toBe(false);
  });

  test('6) revoke token in UI → SCIM call returns 401 envelope', async ({
    page,
    request,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/scim');
    await waitForScimConfigLoaded(page);

    await page.getByRole('button', { name: `Revoke token ${TOKEN_NAME}` }).click();
    const revokeResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        /\/api\/v1\/admin\/scim\/tokens\//.test(r.url()),
      { timeout: 15_000 },
    );
    // Popconfirm ok button.
    await page.getByRole('button', { name: 'Revoke', exact: true }).click();
    const revokeResponse = await revokeResponsePromise;
    expect(revokeResponse.status()).toBe(204);

    await expect(page.getByText('Token revoked', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    const scimRes = await request.get(`${apiBase()}/scim/v2/Users`, {
      headers: { Authorization: `Bearer ${rawScimToken}` },
    });
    expect(scimRes.status()).toBe(401);
    const errorBody = (await scimRes.json()) as { schemas?: string[]; status?: string };
    expect(errorBody.schemas).toContain('urn:ietf:params:scim:api:messages:2.0:Error');
    expect(errorBody.status).toBe('401');
  });
});
