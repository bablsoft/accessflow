import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

let datasource: CreatedDatasource | null = null;
let adminAccessToken = '';

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.type(sql, { delay: 20 });
  await page.keyboard.press('Escape');
}

async function selectDatasource(page: Page): Promise<void> {
  await page.goto('/editor');
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page.locator('.ant-select-item-option').filter({ hasText: datasource!.name }).click();
  await page.waitForResponse(
    (r) => r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`) && r.ok(),
    { timeout: 15_000 },
  );
}

test.describe.serial('query dry-run from /editor (AF-445)', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminAccessToken);
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('admin dry-runs a SELECT and sees a non-committing execution plan', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page);
    await selectDatasource(page);

    await typeInEditor(page, 'SELECT 1');

    // The Dry run header button is available regardless of AI being enabled.
    const dryRunButton = page.getByRole('button', { name: 'Dry run' });
    await expect(dryRunButton).toBeEnabled();

    const dryRunResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' && r.url().endsWith('/api/v1/queries/dry-run'),
      { timeout: 15_000 },
    );
    await dryRunButton.click();

    const response = await dryRunResponse;
    expect(response.status()).toBe(200);
    const body = (await response.json()) as { supported?: boolean };
    expect(body.supported).toBe(true);

    // The right rail switches to the Plan tab and renders the estimated-impact
    // header plus the execution plan tree.
    await expect(page.getByText('Estimated impact')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('tree', { name: 'Execution plan' })).toBeVisible();
    // PostgreSQL EXPLAIN of `SELECT 1` yields a single "Result" plan node.
    await expect(page.getByRole('treeitem').first()).toBeVisible();

    // No query_request was created — we never left /editor.
    expect(new URL(page.url()).pathname).toBe('/editor');
  });

  test('Dry run is disabled when SQL is empty', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page);
    await selectDatasource(page);

    const dryRunButton = page.getByRole('button', { name: 'Dry run' });
    await expect(dryRunButton).toBeDisabled();

    await typeInEditor(page, 'SELECT 1');
    await expect(dryRunButton).toBeEnabled();
  });
});
