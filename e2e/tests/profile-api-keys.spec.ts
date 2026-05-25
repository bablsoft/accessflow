import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

// Bootstrap admin reconciled by the backend on every startup via
// ACCESSFLOW_BOOTSTRAP_ADMIN_* (see e2e/docker-compose.e2e.yml:120-122).
const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

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

// Best-effort cleanup: delete any leftover api keys whose name starts with the
// AF-286 prefix. Tolerates 404 (already revoked) and logs non-2xx as warnings.
// Revoked keys are NOT returned by GET /me/api-keys after a successful DELETE,
// so the list-based pass only catches rows the suite created and failed to
// revoke. Mirrors the shape of restoreAdminState in
// profile-display-and-password.spec.ts:32-90.
async function cleanupApiKeys(request: APIRequestContext, prefix: string): Promise<void> {
  let token: string;
  try {
    token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  } catch (err) {
    // eslint-disable-next-line no-console
    console.warn(`cleanupApiKeys: could not log in admin: ${err}`);
    return;
  }

  const listRes = await request.get(`${apiBase()}/api/v1/me/api-keys`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!listRes.ok()) {
    // eslint-disable-next-line no-console
    console.warn(
      `cleanupApiKeys: list returned ${listRes.status()}: ${await listRes.text()}`,
    );
    return;
  }
  const rows = (await listRes.json()) as Array<{ id: string; name: string }>;
  for (const row of rows) {
    if (!row.name.startsWith(prefix)) continue;
    const del = await request.delete(`${apiBase()}/api/v1/me/api-keys/${row.id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!del.ok() && del.status() !== 404) {
      // eslint-disable-next-line no-console
      console.warn(
        `cleanupApiKeys: DELETE ${row.id} returned ${del.status()}: ${await del.text()}`,
      );
    }
  }
}

test.describe.serial('AF-286 — /profile API keys CRUD', () => {
  // Per-suite uniqueness keeps reruns deterministic against the long-lived db.
  const SUFFIX = `af286-${Date.now()}`;
  const KEY_NAME_PREFIX = 'e2e-test-key-';
  const KEY_NAME = `${KEY_NAME_PREFIX}${SUFFIX}`;
  const KEY_NAME_DUP = `${KEY_NAME_PREFIX}dup-${SUFFIX}`;

  test.beforeEach(async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });
  });

  test('creates a key, reveals raw value once, copies it, then revokes', async ({
    page,
    context,
  }) => {
    // ApiKeysSection uses Typography.Paragraph copyable, which calls
    // navigator.clipboard.writeText() — the readText() assertion below requires
    // explicit permission grants in Chromium. Mirrors
    // admin-oauth2-config.spec.ts:231.
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);

    // Open create modal.
    await page.getByRole('button', { name: 'Create API key', exact: true }).click();

    const createDialog = page.getByRole('dialog', { name: 'Create a new API key' });
    await expect(createDialog).toBeVisible({ timeout: 5_000 });
    await createDialog.getByLabel('Key name', { exact: true }).fill(KEY_NAME);

    // Wait for the POST and read the raw key from the response.
    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/me\/api-keys$/.test(r.url()) &&
        r.status() === 201,
      { timeout: 10_000 },
    );
    await createDialog.getByRole('button', { name: 'Create API key', exact: true }).click();
    const createResponse = await createResponsePromise;
    const created = (await createResponse.json()) as {
      api_key: { id: string; name: string; key_prefix: string };
      raw_key: string;
    };
    expect(created.raw_key).toMatch(/^af_/);
    expect(created.api_key.name).toBe(KEY_NAME);
    expect(created.api_key.key_prefix).toMatch(/^af_/);

    // Issued-key dialog: warning, raw key visible, copy works.
    const issuedDialog = page.getByRole('dialog', { name: 'Copy your new API key' });
    await expect(issuedDialog).toBeVisible({ timeout: 5_000 });
    await expect(
      issuedDialog.getByText('This is the only time the key is shown.', { exact: false }),
    ).toBeVisible();
    await expect(issuedDialog.getByText(created.raw_key, { exact: false })).toBeVisible();

    await issuedDialog.locator('.ant-typography-copy').first().click();
    const clipboardText = await page.evaluate(() => navigator.clipboard.readText());
    expect(clipboardText).toBe(created.raw_key);

    // Close the issued-key dialog (destroyOnHidden) — the modal must be gone.
    // The dialog has two "Close"s: the AntD icon (aria-label="Close") and the
    // footer button; scope to the footer to avoid the strict-mode collision.
    await issuedDialog
      .locator('.ant-modal-footer')
      .getByRole('button', { name: 'Close', exact: true })
      .click();
    await expect(issuedDialog).toBeHidden({ timeout: 5_000 });

    // The keys table now contains the new row with the af_… prefix and a
    // Revoke action — locate the row by its key name then assert the prefix
    // cell renders the af_ prefix.
    const table = page.getByRole('table', { name: 'API keys' });
    await expect(table).toBeVisible({ timeout: 10_000 });
    const row = table.locator('tr').filter({ hasText: KEY_NAME });
    await expect(row).toBeVisible({ timeout: 10_000 });
    await expect(row).toContainText(created.api_key.key_prefix);

    // Revoke via the link button's aria-label (stable across copy changes).
    const revokeBtn = row.getByRole('button', {
      name: `Revoke API key ${KEY_NAME}`,
      exact: true,
    });
    await revokeBtn.click();

    const popover = page
      .locator('.ant-popover')
      .filter({ hasText: `Revoke API key "${KEY_NAME}"` });
    await expect(popover).toBeVisible({ timeout: 5_000 });

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/me/api-keys/${created.api_key.id}$`).test(r.url()) &&
        r.status() === 204,
      { timeout: 10_000 },
    );
    await popover.getByRole('button', { name: 'Revoke', exact: true }).click();
    await deleteResponsePromise;

    await expect(page.getByText('API key revoked.', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // The row stays in the table (revoked keys are still listed) but the
    // status switches to "Revoked" and the Revoke button disappears.
    await expect(row).toContainText('Revoked', { timeout: 10_000 });
    await expect(
      row.getByRole('button', { name: `Revoke API key ${KEY_NAME}`, exact: true }),
    ).toHaveCount(0);
  });

  test('blocks create when name is empty', async ({ page }) => {
    await page.getByRole('button', { name: 'Create API key', exact: true }).click();
    const createDialog = page.getByRole('dialog', { name: 'Create a new API key' });
    await expect(createDialog).toBeVisible({ timeout: 5_000 });

    // Collect any matching POSTs that fire to prove the request never went
    // out — antd Form.Item validation should reject before the mutation runs.
    const postsObserved: string[] = [];
    const listener = (req: import('@playwright/test').Request) => {
      if (
        req.method() === 'POST' &&
        /\/api\/v1\/me\/api-keys$/.test(req.url())
      ) {
        postsObserved.push(req.url());
      }
    };
    page.on('request', listener);

    await createDialog.getByRole('button', { name: 'Create API key', exact: true }).click();
    await expect(createDialog.getByText('Name is required.', { exact: true })).toBeVisible({
      timeout: 5_000,
    });

    // Give any in-flight request a beat to surface, then assert none fired.
    await page.waitForTimeout(500);
    page.off('request', listener);
    expect(postsObserved).toEqual([]);

    // No issued-key dialog, modal still open.
    await expect(
      page.getByRole('dialog', { name: 'Copy your new API key' }),
    ).toHaveCount(0);
    await expect(createDialog).toBeVisible();

    // Cancel to leave a clean state for the next test.
    await createDialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(createDialog).toBeHidden({ timeout: 5_000 });
  });

  test('shows an error alert when creating a duplicate name', async ({ page }) => {
    // Seed a key with the duplicate name via the UI; the create succeeds and
    // the issued-key dialog opens, which we close immediately.
    await page.getByRole('button', { name: 'Create API key', exact: true }).click();
    let createDialog = page.getByRole('dialog', { name: 'Create a new API key' });
    await expect(createDialog).toBeVisible({ timeout: 5_000 });
    await createDialog.getByLabel('Key name', { exact: true }).fill(KEY_NAME_DUP);

    const seedResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/me\/api-keys$/.test(r.url()) &&
        r.status() === 201,
      { timeout: 10_000 },
    );
    await createDialog.getByRole('button', { name: 'Create API key', exact: true }).click();
    await seedResponsePromise;

    const issuedDialog = page.getByRole('dialog', { name: 'Copy your new API key' });
    await expect(issuedDialog).toBeVisible({ timeout: 5_000 });
    await issuedDialog
      .locator('.ant-modal-footer')
      .getByRole('button', { name: 'Close', exact: true })
      .click();
    await expect(issuedDialog).toBeHidden({ timeout: 5_000 });

    // Now attempt a duplicate — backend rejects with 409 API_KEY_DUPLICATE_NAME.
    await page.getByRole('button', { name: 'Create API key', exact: true }).click();
    createDialog = page.getByRole('dialog', { name: 'Create a new API key' });
    await expect(createDialog).toBeVisible({ timeout: 5_000 });
    await createDialog.getByLabel('Key name', { exact: true }).fill(KEY_NAME_DUP);

    const dupResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/me\/api-keys$/.test(r.url()) &&
        r.status() === 409,
      { timeout: 10_000 },
    );
    await createDialog.getByRole('button', { name: 'Create API key', exact: true }).click();
    await dupResponsePromise;

    // ApiKeysSection closes its create modal on success only; on error it stays
    // open and surfaces the message via the section-level Alert. Scope to the
    // page (not the dialog) and only require a non-empty alert — the exact
    // wording flows through profileErrorMessage → ProblemDetail.detail, so we
    // avoid coupling the spec to the i18n string.
    const alert = page.getByRole('alert').filter({ hasText: /\S/ });
    await expect(alert.first()).toBeVisible({ timeout: 5_000 });

    // No second issued-key dialog appeared.
    await expect(
      page.getByRole('dialog', { name: 'Copy your new API key' }),
    ).toHaveCount(0);

    // Close the create modal so afterAll can clean up.
    await createDialog.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(createDialog).toBeHidden({ timeout: 5_000 });
  });

  test.afterAll(async ({ request }) => {
    await cleanupApiKeys(request, KEY_NAME_PREFIX);
  });
});
