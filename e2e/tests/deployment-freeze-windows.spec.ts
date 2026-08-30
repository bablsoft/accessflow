import { expect, test, type Page } from '@playwright/test';
import { apiBase, loginViaApi } from '../helpers/datasources';
import { activeTabPanel, clickTab } from '../helpers/ui';
import {
  createDeploymentPipelineViaApi,
  deleteDeploymentPipelineViaApi,
  type CreatedDeploymentPipeline,
} from '../helpers/deployments';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

async function openFreezeWindowsTab(page: Page, pipeline: CreatedDeploymentPipeline) {
  await page.goto(`/admin/deployment-pipelines/${pipeline.id}`);
  await expect(page.getByRole('heading', { name: pipeline.name })).toBeVisible({
    timeout: 15_000,
  });
  await clickTab(page, 'Freeze windows');
  return activeTabPanel(page);
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('deployment freeze windows (#696)', () => {
  let adminAccessToken = '';
  let pipeline: CreatedDeploymentPipeline | null = null;
  const createdWindowIds: string[] = [];

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    pipeline = await createDeploymentPipelineViaApi(request, adminAccessToken, {
      name: `e2e-freeze-696-${Date.now()}`,
      aiAnalysisEnabled: false,
    });
  });

  test.afterAll(async ({ request }) => {
    // Freeze windows scoped to the pipeline go with it; delete windows first anyway
    // so a failed pipeline delete doesn't leave org-global leftovers behind.
    for (const id of createdWindowIds) {
      const res = await request.delete(`${apiBase()}/api/v1/deployment-freeze-windows/${id}`, {
        headers: { Authorization: `Bearer ${adminAccessToken}` },
      });
      if (!res.ok() && res.status() !== 404) {
        // eslint-disable-next-line no-console
        console.warn(`freeze window cleanup skipped for ${id}: ${res.status()}`);
      }
    }
    if (pipeline) {
      await deleteDeploymentPipelineViaApi(request, adminAccessToken, pipeline.id);
    }
  });

  test('creates a recurring weekly window and renders the week strip', async ({ page }) => {
    if (!pipeline) throw new Error('pipeline not created in beforeAll');
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    const panel = await openFreezeWindowsTab(page, pipeline);

    await panel.getByRole('button', { name: 'Add freeze window' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Add freeze window' });
    await expect(dialog).toBeVisible();

    await dialog.getByRole('radio', { name: 'Weekly recurring' }).check();
    // Weekday multi-select.
    await dialog.locator('#days_of_week').click();
    await page.getByTitle('Saturday').first().click();
    await page.getByTitle('Sunday').first().click();
    await page.keyboard.press('Escape');
    // HH:mm time pickers accept typed values committed with Enter.
    await dialog.locator('#start_time').click();
    await dialog.locator('#start_time').fill('08:00');
    await page.keyboard.press('Enter');
    await dialog.locator('#end_time').click();
    await dialog.locator('#end_time').fill('18:00');
    await page.keyboard.press('Enter');
    await dialog.getByLabel('Reason').fill('weekend freeze');

    const createResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith('/api/v1/deployment-freeze-windows'),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save' }).click();
    const response = await createResponse;
    expect(response.status()).toBe(201);
    const created = (await response.json()) as { id: string; days_of_week: number[] };
    createdWindowIds.push(created.id);
    expect(created.days_of_week.sort()).toEqual([6, 7]);

    // The table shows the schedule summary and the recurring week strip appears.
    await expect(panel.locator('.ant-table-row', { hasText: 'weekend freeze' })).toBeVisible();
    await expect(panel.getByTestId('freeze-week-strip')).toBeVisible();
  });

  test('edits the window behavior to auto-reject', async ({ page }) => {
    if (!pipeline) throw new Error('pipeline not created in beforeAll');
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    const panel = await openFreezeWindowsTab(page, pipeline);

    const row = panel.locator('.ant-table-row', { hasText: 'weekend freeze' });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole('button', { name: 'Edit' }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: 'Edit freeze window' });
    await expect(dialog).toBeVisible();
    // Behavior select by its stable form input id (the label repeats in the table).
    await dialog.locator('#behavior').click();
    await page.getByTitle('Auto-reject').first().click();

    const updateResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.includes('/api/v1/deployment-freeze-windows/'),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save' }).click();
    const response = await updateResponse;
    expect(response.status()).toBe(200);
    expect(((await response.json()) as { behavior: string }).behavior).toBe('REJECT');
    await expect(row.getByText('Auto-reject')).toBeVisible({ timeout: 10_000 });
  });

  test('rejects a one-off window whose end precedes its start (client-side)', async ({ page }) => {
    if (!pipeline) throw new Error('pipeline not created in beforeAll');
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    const panel = await openFreezeWindowsTab(page, pipeline);

    await panel.getByRole('button', { name: 'Add freeze window' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Add freeze window' });
    await expect(dialog).toBeVisible();

    // One-off is the default mode. Type an inverted range into the date pickers.
    await dialog.locator('#starts_at').click();
    await dialog.locator('#starts_at').fill('2027-03-10 12:00:00');
    await page.keyboard.press('Enter');
    await dialog.locator('#ends_at').click();
    await dialog.locator('#ends_at').fill('2027-03-09 12:00:00');
    await page.keyboard.press('Enter');

    // Client validation blocks the submit — no POST is fired.
    const posts: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/deployment-freeze-windows')) {
        posts.push(r.url());
      }
    });
    await dialog.getByRole('button', { name: 'Save' }).click();
    await expect(dialog.getByText('End must be after start')).toBeVisible({ timeout: 10_000 });
    expect(posts).toHaveLength(0);
    await dialog.getByRole('button', { name: 'Cancel' }).click();
  });
});
