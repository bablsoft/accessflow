import { expect, test, type Page } from '@playwright/test';
import { createPostgresDatasource, deleteDatasource, loginViaApi } from '../helpers/datasources';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af945-${Date.now()}`;
const HOOK_NAME = `OPA gate ${UNIQUE_SUFFIX}`;
const RENAMED_HOOK_NAME = `OPA gate renamed ${UNIQUE_SUFFIX}`;
const DS_NAME = `Hooked DS ${UNIQUE_SUFFIX}`;
// `.invalid` never resolves (RFC 2606): the save-time check accepts an unresolvable host, and the
// test call fails closed as a connection error without any network access.
const ENDPOINT = 'https://opa.e2e-decision-hook.invalid/v1/data/accessflow/decision';
const SECRET = 'e2e-decision-hook-secret-0123456789abcdef';

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function openDecisionHooks(page: Page): Promise<void> {
  await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  const listed = page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/decision-hooks(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
  await page.goto('/admin/decision-hooks');
  await listed;
}

function hookRow(page: Page, name: string) {
  return page.getByRole('row').filter({ hasText: name });
}

test.describe.configure({ timeout: 90_000 });

// #945 — the external policy decision hook admin surface. The hook is scoped to a throwaway
// datasource and created DISABLED, so it can never influence another spec's queries.
test.describe.serial('/admin/decision-hooks — external policy decision hook', () => {
  let adminAccessToken = '';
  let datasourceId: string | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const ds = await createPostgresDatasource(request, adminAccessToken, { name: DS_NAME });
    datasourceId = ds.id;
  });

  test.afterAll(async ({ request }) => {
    const res = await request.get(`${apiBase()}/api/v1/admin/decision-hooks`, {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
    });
    if (res.ok()) {
      const hooks = (await res.json()) as Array<{ id: string; datasource_id?: string | null }>;
      for (const hook of hooks.filter((h) => h.datasource_id === datasourceId)) {
        await request.delete(`${apiBase()}/api/v1/admin/decision-hooks/${hook.id}`, {
          headers: { Authorization: `Bearer ${adminAccessToken}` },
        });
      }
    }
    if (datasourceId) {
      await deleteDatasource(request, adminAccessToken, datasourceId);
    }
  });

  test('creates a disabled hook scoped to one datasource', async ({ page }) => {
    await openDecisionHooks(page);

    await page.getByRole('button', { name: /Add hook/ }).first().click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Add decision hook' }).first();
    await expect(modal).toBeVisible({ timeout: 10_000 });

    await modal.getByLabel('Name').fill(HOOK_NAME);
    await modal.getByLabel('Applies to').click();
    await modal.getByLabel('Applies to').fill(DS_NAME);
    await page.locator('.ant-select-item-option').filter({ hasText: DS_NAME }).click();
    await modal.getByLabel('Endpoint URL').fill(ENDPOINT);
    await modal.getByLabel('Signing secret').fill(SECRET);
    await modal.getByLabel('Enabled').click();

    const created = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/api\/v1\/admin\/decision-hooks$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Create hook' }).click();
    const response = await created;
    expect(response.status()).toBe(201);
    const body = (await response.json()) as Record<string, unknown>;
    expect(body.enabled).toBe(false);
    expect(body.datasource_id).toBe(datasourceId);
    expect(body.secret_configured).toBe(true);
    expect(body).not.toHaveProperty('secret');

    await expect(hookRow(page, HOOK_NAME)).toBeVisible({ timeout: 10_000 });
    await expect(hookRow(page, HOOK_NAME)).toContainText(DS_NAME);
  });

  test('a test request against an unreachable endpoint reports the failure', async ({ page }) => {
    await openDecisionHooks(page);

    const tested = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/api\/v1\/admin\/decision-hooks\/[^/]+\/test$/.test(r.url()),
      { timeout: 20_000 },
    );
    await hookRow(page, HOOK_NAME).getByRole('button', { name: /Send a test request/ }).click();
    const response = await tested;
    expect(response.status()).toBe(200);
    expect(((await response.json()) as { outcome: string }).outcome).toBe('FAILED');
    await expect(page.getByText(/The hook failed/)).toBeVisible({ timeout: 10_000 });
  });

  test('edits a hook without resending the secret', async ({ page }) => {
    await openDecisionHooks(page);

    await hookRow(page, HOOK_NAME).getByRole('button', { name: /Edit/ }).click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Edit decision hook' }).first();
    await expect(modal).toBeVisible({ timeout: 10_000 });
    await expect(modal.getByText('Leave empty to keep the current secret.')).toBeVisible();
    await modal.getByLabel('Name').fill(RENAMED_HOOK_NAME);

    const updated = page.waitForResponse(
      (r) => r.request().method() === 'PUT' && /\/api\/v1\/admin\/decision-hooks\/[^/]+$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Save changes' }).click();
    const response = await updated;
    expect(response.status()).toBe(200);
    expect(response.request().postDataJSON()).not.toHaveProperty('secret');

    await expect(hookRow(page, RENAMED_HOOK_NAME)).toBeVisible({ timeout: 10_000 });
  });

  test('deletes a hook after confirmation', async ({ page }) => {
    await openDecisionHooks(page);

    await hookRow(page, RENAMED_HOOK_NAME).getByRole('button', { name: /Delete/ }).click();
    const confirm = page.getByRole('dialog').filter({ hasText: 'Delete this decision hook?' });
    await expect(confirm).toBeVisible({ timeout: 10_000 });
    const deleted = page.waitForResponse(
      (r) => r.request().method() === 'DELETE' && /\/api\/v1\/admin\/decision-hooks\/[^/]+$/.test(r.url()),
      { timeout: 15_000 },
    );
    await confirm.getByRole('button', { name: 'Delete' }).click();
    expect((await deleted).status()).toBe(204);

    await expect(hookRow(page, RENAMED_HOOK_NAME)).toHaveCount(0, { timeout: 10_000 });
  });
});
