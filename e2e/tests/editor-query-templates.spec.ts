import { expect, test, type Page } from '@playwright/test';
import {
  apiBase,
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

// AF-364: save a SQL snippet as a template, reload the page, open the Templates
// drawer, fill placeholder values, and submit through the normal /editor flow.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const SUFFIX = `af364-${Date.now()}`;
const TEMPLATE_NAME = `Top users ${SUFFIX}`;

let datasource: CreatedDatasource | null = null;
let adminAccessToken = '';
let createdTemplateId: string | null = null;

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.type(sql, { delay: 20 });
  await page.keyboard.press('Escape');
}

async function pickDatasource(page: Page, name: string): Promise<void> {
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page.locator('.ant-select-item-option').filter({ hasText: name }).click();
}

test.describe.serial('query templates from /editor', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres AF-364 ${SUFFIX}`,
    });
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

  test('save a templated query, reload, load it back with placeholder values, and submit', async ({
    page,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // ── 1. Login and write a SQL template with two :placeholders.
    await loginViaUi(page);
    await page.goto('/editor');
    await pickDatasource(page, datasource.name);
    await page.waitForResponse(
      (r) => r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`) && r.ok(),
      { timeout: 15_000 },
    );

    const originalSql =
      'SELECT * FROM users WHERE country = :country LIMIT :limit';
    await typeInEditor(page, originalSql);

    // ── 2. Click "Save as template", fill the form, save.
    await page.getByRole('button', { name: 'Save as template' }).click();

    const saveDialog = page.getByRole('dialog').filter({ hasText: 'Save as template' });
    await saveDialog.getByLabel('Name').fill(TEMPLATE_NAME);

    // Capture the created template id so afterAll can delete it.
    const createPromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().endsWith('/api/v1/query-templates') &&
        r.ok(),
      { timeout: 15_000 },
    );
    await saveDialog.getByRole('button', { name: 'Save' }).click();
    const createRes = await createPromise;
    const createdBody = (await createRes.json()) as { id: string };
    createdTemplateId = createdBody.id;

    await expect(page.getByText('Template saved')).toBeVisible({ timeout: 5_000 });

    // ── 3. Reload — guarantees the editor SQL state is gone before we re-load.
    await page.reload();
    await pickDatasource(page, datasource.name);
    await page.waitForResponse(
      (r) => r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`) && r.ok(),
      { timeout: 15_000 },
    );

    // ── 4. Open the Templates drawer and click Open on our row.
    await page.getByRole('button', { name: 'Templates' }).click();
    const drawer = page.getByRole('dialog').filter({ hasText: 'Query templates' });
    const row = drawer.locator('.ant-list-item').filter({ hasText: TEMPLATE_NAME });
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.getByRole('button', { name: 'Open' }).click();

    // ── 5. The Load Template modal opens (placeholders detected) — fill both.
    const loadDialog = page.getByRole('dialog').filter({ hasText: 'Load template' });
    await expect(loadDialog).toBeVisible({ timeout: 5_000 });
    // Labels are rendered as `<code>:name</code>`. The inputs are antd Inputs.
    const inputs = loadDialog.getByRole('textbox');
    await inputs.nth(0).fill("'US'");
    await inputs.nth(1).fill('10');
    await loadDialog.getByRole('button', { name: 'Load' }).click();

    // ── 6. Editor now contains substituted SQL.
    await expect(page.locator('.cm-content')).toContainText("country = 'US'");
    await expect(page.locator('.cm-content')).toContainText('LIMIT 10');

    // ── 7. Submit goes through the normal workflow — navigation to /queries/<uuid>.
    await page
      .getByPlaceholder('Why are you running this query?')
      .fill('AF-364 e2e: template load + submit');
    await page.getByRole('button', { name: 'Submit for review' }).click();
    await page.waitForURL(/\/queries\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  });
});
