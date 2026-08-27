import { expect, test, type Page } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';
import { activeTabPanel, clickTab } from '../helpers/ui';
import {
  createDeploymentPipelineViaApi,
  deleteDeploymentPipelineViaApi,
} from '../helpers/deployments';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('deployment pipeline administration (#696)', () => {
  let adminAccessToken = '';
  const createdPipelineIds: string[] = [];

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    for (const id of createdPipelineIds) {
      try {
        await deleteDeploymentPipelineViaApi(request, adminAccessToken, id);
      } catch (err) {
        // eslint-disable-next-line no-console
        console.warn(`pipeline cleanup skipped for ${id}: ${String(err)}`);
      }
    }
  });

  test('creates a pipeline through the admin UI and lands in its settings', async ({ page }) => {
    const name = `e2e-pipeline-ui-${Date.now()}`;
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/deployment-pipelines');

    await page.getByRole('button', { name: 'Add pipeline' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Add deployment pipeline' });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel('Name', { exact: true }).fill(name);

    // Arm the response wait before clicking so the 201 isn't missed.
    const createResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith('/api/v1/deployment-pipelines'),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Create pipeline' }).click();
    const response = await createResponse;
    expect(response.status()).toBe(201);
    const created = (await response.json()) as { id: string };
    createdPipelineIds.push(created.id);

    // Create navigates straight into the settings page.
    await page.waitForURL(`**/admin/deployment-pipelines/${created.id}`, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name })).toBeVisible();
    for (const tab of ['General', 'Environments', 'Permissions', 'Freeze windows', 'Routing policies', 'CI setup']) {
      await expect(page.getByRole('tab', { name: tab })).toBeVisible();
    }
  });

  test('adds an environment and a user permission grant on the settings tabs', async ({
    page,
    request,
  }) => {
    const pipeline = await createDeploymentPipelineViaApi(request, adminAccessToken, {
      name: `e2e-pipeline-tabs-${Date.now()}`,
      aiAnalysisEnabled: false,
    });
    createdPipelineIds.push(pipeline.id);

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/admin/deployment-pipelines/${pipeline.id}`);
    await expect(page.getByRole('heading', { name: pipeline.name })).toBeVisible({
      timeout: 15_000,
    });

    // Environments tab — inactive AntD panes stay mounted, so scope via the active panel.
    await clickTab(page, 'Environments');
    const envPanel = activeTabPanel(page);
    await envPanel.getByRole('button', { name: 'Add environment' }).click();
    const envDialog = page.getByRole('dialog').filter({ hasText: 'Add environment' });
    await expect(envDialog).toBeVisible();
    await envDialog.getByLabel('Name', { exact: true }).fill('staging');
    const envResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith(`/deployment-pipelines/${pipeline.id}/environments`),
      { timeout: 15_000 },
    );
    await envDialog.getByRole('button', { name: 'Save' }).click();
    expect((await envResponse).status()).toBe(201);
    await expect(envPanel.locator('.ant-table-row', { hasText: 'staging' })).toBeVisible();

    // Permissions tab — grant the admin's own user can_trigger via the form.
    await clickTab(page, 'Permissions');
    const permPanel = activeTabPanel(page);
    // Open the user Select by its stable form-item input id (label text repeats in the tables).
    await permPanel.locator('#user_id').click();
    // The suite accumulates users in the shared org, so the admin is not reliably in the first
    // page of options on a re-used stack — type to filter (the Select searches the option label)
    // and pick from the open dropdown rather than from anywhere on the page.
    await permPanel.locator('#user_id').fill(ADMIN_EMAIL);
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .getByTitle(new RegExp(ADMIN_EMAIL))
      .first()
      .click();
    const grantResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith(`/deployment-pipelines/${pipeline.id}/permissions`),
      { timeout: 15_000 },
    );
    await permPanel.getByRole('button', { name: 'Grant', exact: true }).click();
    expect((await grantResponse).status()).toBe(201);
    await expect(
      permPanel.locator('.ant-table-row', { hasText: ADMIN_EMAIL }),
    ).toBeVisible({ timeout: 10_000 });

    // CI setup tab — the snippet embeds this pipeline's id, ready to paste.
    await clickTab(page, 'CI setup');
    const ciPanel = activeTabPanel(page);
    await expect(ciPanel.getByTestId('ci-snippet')).toContainText(pipeline.id);
  });

  test('deletes a pipeline from the list', async ({ page, request }) => {
    const pipeline = await createDeploymentPipelineViaApi(request, adminAccessToken, {
      name: `e2e-pipeline-delete-${Date.now()}`,
    });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/deployment-pipelines');
    const row = page.locator('.ant-table-row', { hasText: pipeline.name });
    await expect(row).toBeVisible({ timeout: 15_000 });

    const deleteResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new URL(r.url()).pathname.endsWith(`/deployment-pipelines/${pipeline.id}`),
      { timeout: 15_000 },
    );
    await row.getByRole('button', { name: 'Delete' }).click();
    // Popconfirm renders in a portal; its confirm button shares the Delete label.
    await page.locator('.ant-popover').getByRole('button', { name: 'Delete' }).click();
    expect((await deleteResponse).status()).toBe(204);
    await expect(page.locator('.ant-table-row', { hasText: pipeline.name })).toHaveCount(0, {
      timeout: 10_000,
    });
  });
});
