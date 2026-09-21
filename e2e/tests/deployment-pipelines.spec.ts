import { expect, test } from '@playwright/test';
import { createPostgresDatasource, deleteDatasource, loginViaApi } from '../helpers/datasources';
import { activeTabPanel, clickTab, findRowAcrossPages } from '../helpers/ui';
import {
  createDeploymentPipelineViaApi,
  deleteDeploymentPipelineViaApi,
} from '../helpers/deployments';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

test.describe.configure({ timeout: 90_000 });

test.describe.serial('deployment pipeline administration (#696)', () => {
  let adminAccessToken = '';
  const createdPipelineIds: string[] = [];
  const createdDatasourceIds: string[] = [];

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
    for (const id of createdDatasourceIds) {
      await deleteDatasource(request, adminAccessToken, id);
    }
  });

  test('creates a pipeline through the admin UI and lands in its settings', async ({ page }) => {
    const name = `e2e-pipeline-ui-${Date.now()}`;
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
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
    for (const tab of [
      'General',
      'Environments',
      'Versions',
      'Permissions',
      'Freeze windows',
      'Routing policies',
      'CI setup',
    ]) {
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
    const datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `e2e-env-db-${Date.now()}`,
    });
    createdDatasourceIds.push(datasource.id);

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
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
    // The modal's Form can remount just after open (initialValues reset); a
    // fill that lands in that window is wiped and Save then trips the
    // required-Name validation instead of POSTing. Assert the value stuck.
    const envNameInput = envDialog.getByLabel('Name', { exact: true });
    await envNameInput.fill('staging');
    await expect(envNameInput).toHaveValue('staging');
    const envResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith(`/deployment-pipelines/${pipeline.id}/environments`),
      { timeout: 15_000 },
    );
    await envDialog.getByRole('button', { name: 'Save' }).click();
    const stagingBody = (await (await envResponse).json()) as {
      sort_order: number;
      datasource_id?: string | null;
    };
    const stagingRow = envPanel.locator('.ant-table-row', { hasText: 'staging' });
    await expect(stagingRow).toBeVisible();
    // A first environment with the Order field left as prefilled lands at 0, deploy-only (#877).
    expect(stagingBody.sort_order).toBe(0);
    // The backend omits null fields on the wire.
    expect(stagingBody.datasource_id ?? null).toBeNull();
    await expect(stagingRow).toContainText('Deploy-only (no database)');

    // A second environment bound to a database through the new Database select (#877). The
    // prefilled Order is the server's own default (one past the last rung), so the unique
    // (pipeline, sort_order) constraint is never tripped by the UI.
    await envPanel.getByRole('button', { name: 'Add environment' }).click();
    const prodDialog = page.getByRole('dialog').filter({ hasText: 'Add environment' });
    await expect(prodDialog).toBeVisible();
    const prodNameInput = prodDialog.getByLabel('Name', { exact: true });
    await prodNameInput.fill('production');
    await expect(prodNameInput).toHaveValue('production');
    await expect(prodDialog.getByLabel('Order')).toHaveValue('1');
    // The Form is named `deployment_environment`, so its control ids are namespaced.
    await prodDialog.locator('#deployment_environment_datasource_id').click();
    await prodDialog.locator('#deployment_environment_datasource_id').fill(datasource.name);
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .getByTitle(datasource.name)
      .first()
      .click();
    const prodResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith(`/deployment-pipelines/${pipeline.id}/environments`),
      { timeout: 15_000 },
    );
    await prodDialog.getByRole('button', { name: 'Save' }).click();
    const prodRaw = await prodResponse;
    expect(prodRaw.status()).toBe(201);
    const prodBody = (await prodRaw.json()) as { sort_order: number; datasource_id: string | null };
    expect(prodBody.sort_order).toBe(1);
    expect(prodBody.datasource_id).toBe(datasource.id);
    await expect(
      envPanel.locator('.ant-table-row', { hasText: 'production' }),
    ).toContainText(datasource.name);

    // Editing the bound environment onto a taken position is refused with the server's 409 detail.
    await envPanel
      .locator('.ant-table-row', { hasText: 'production' })
      .getByRole('button', { name: 'Edit' })
      .click();
    const editDialog = page.getByRole('dialog').filter({ hasText: 'Edit environment' });
    await expect(editDialog).toBeVisible();
    await expect(editDialog.getByLabel('Name', { exact: true })).toHaveValue('production');
    await editDialog.getByLabel('Order').fill('0');
    const conflictResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.includes(`/deployment-pipelines/${pipeline.id}/environments/`),
      { timeout: 15_000 },
    );
    await editDialog.getByRole('button', { name: 'Save' }).click();
    expect((await conflictResponse).status()).toBe(409);
    await expect(
      page.getByText('An environment with this sort order already exists on this pipeline'),
    ).toBeVisible();
    await editDialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(editDialog).toBeHidden();

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

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
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

  test('exposes the pipeline id as a copyable control on both pages (#771)', async ({
    page,
    request,
  }) => {
    const pipeline = await createDeploymentPipelineViaApi(request, adminAccessToken, {
      name: `e2e-pipeline-id-${Date.now()}`,
      aiAnalysisEnabled: false,
    });
    createdPipelineIds.push(pipeline.id);

    // The readText() assertion below is what needs a grant; the write does not.
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The list carries the truncated id. The pipeline list is unordered and org-shared, so the
    // row is not reliably on page 1 under the parallel project.
    await page.goto('/admin/deployment-pipelines');
    const row = page.locator('.ant-table-row', { hasText: pipeline.name });
    await findRowAcrossPages(page, row);
    await expect(row.getByTestId('pipeline-id')).toContainText(pipeline.id.slice(0, 8));

    // The settings header carries it in full — the point of the issue: no URL reading.
    await page.goto(`/admin/deployment-pipelines/${pipeline.id}`);
    await expect(page.getByRole('heading', { name: pipeline.name })).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByTestId('pipeline-id')).toHaveText(pipeline.id);

    // AntD derives the copy button's accessible name from the tooltip string.
    await page.getByRole('button', { name: 'Copy pipeline ID' }).click();
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toBe(pipeline.id);
  });
});
