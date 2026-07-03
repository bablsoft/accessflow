import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  apiBase,
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

// AF-442: version history & diff for saved query templates. Seed two versions via the
// API (create → update), then drive the editor UI: open the Templates drawer, open a
// template's History tab, confirm the side-by-side diff renders, and restore the first
// version (which must create a new version, preserving history).

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const SUFFIX = `af442-${Date.now()}`;
const TEMPLATE_NAME = `Versioned query ${SUFFIX}`;

let datasource: CreatedDatasource | null = null;
let adminAccessToken = '';
let createdTemplateId: string | null = null;

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

async function pickDatasource(page: Page, name: string): Promise<void> {
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page.locator('.ant-select-item-option').filter({ hasText: name }).click();
}

async function seedTemplateWithTwoVersions(request: APIRequestContext): Promise<string> {
  const headers = { Authorization: `Bearer ${adminAccessToken}` };
  // v1 — CREATED
  const createRes = await request.post(`${apiBase()}/api/v1/query-templates`, {
    headers,
    data: {
      name: TEMPLATE_NAME,
      body: 'SELECT 1 FROM users',
      description: 'first revision',
      tags: ['af442'],
      visibility: 'PRIVATE',
    },
  });
  expect(createRes.ok()).toBeTruthy();
  const id = ((await createRes.json()) as { id: string }).id;
  // v2 — UPDATED (content change records a new version)
  const updateRes = await request.put(`${apiBase()}/api/v1/query-templates/${id}`, {
    headers,
    data: {
      name: TEMPLATE_NAME,
      body: 'SELECT 2 FROM users',
      description: 'second revision',
      tags: ['af442'],
      visibility: 'PRIVATE',
    },
  });
  expect(updateRes.ok()).toBeTruthy();
  return id;
}

test.describe.serial('query template version history from /editor', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres AF-442 ${SUFFIX}`,
    });
    createdTemplateId = await seedTemplateWithTwoVersions(request);
  });

  test.afterAll(async ({ request }) => {
    if (createdTemplateId) {
      await request.delete(`${apiBase()}/api/v1/query-templates/${createdTemplateId}`, {
        headers: { Authorization: `Bearer ${adminAccessToken}` },
      });
    }
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('open History, see the diff between two versions, and restore the first', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page);
    await page.goto('/editor');
    // Register the schema wait BEFORE picking — the fetch can complete before a
    // later-registered listener attaches (localhost round-trips).
    const schemaResponse = page.waitForResponse(
      (r) => r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`) && r.ok(),
      { timeout: 15_000 },
    );
    await pickDatasource(page, datasource.name);
    await schemaResponse;

    // Open the Templates drawer and click History on our row. Match the toolbar button's full
    // accessible name ("book" icon + label) so it doesn't collide with the schema-tree buttons
    // for the `query_templates` table in the editor sidebar (AF-443).
    await page.getByRole('button', { name: 'book Templates' }).click();
    const drawer = page.getByRole('dialog').filter({ hasText: 'Query templates' });
    const row = drawer.locator('.ant-list-item').filter({ hasText: TEMPLATE_NAME });
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.getByRole('button', { name: 'History' }).click();

    // The detail drawer opens on the History tab — diff renders, two versions listed.
    const detail = page.getByRole('dialog').filter({ hasText: 'Version history' });
    await expect(detail).toBeVisible({ timeout: 10_000 });
    await expect(detail.getByTestId('sql-diff-view')).toBeVisible({ timeout: 10_000 });
    const versionRows = detail.locator('.ant-list-item');
    await expect(versionRows).toHaveCount(2, { timeout: 10_000 });

    // Restore the first version (v1). Its row contains the "v1" label.
    const v1Row = versionRows.filter({ hasText: 'v1' });
    const restorePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/versions\/[0-9a-f-]{36}\/restore$/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );
    await v1Row.getByRole('button', { name: 'Restore this version' }).click();
    // exact match: the "Templates" toolbar button's accessible name ("book Templates")
    // contains "ok" as a substring, so a non-exact name would be ambiguous.
    await page.getByRole('button', { name: 'OK', exact: true }).click();
    await restorePromise;

    await expect(page.getByText('Template restored to the selected version')).toBeVisible({
      timeout: 10_000,
    });

    // History now has a third (RESTORED) version — restore preserves history.
    await expect(versionRows).toHaveCount(3, { timeout: 10_000 });
  });
});
