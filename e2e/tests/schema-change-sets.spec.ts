import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  apiBase,
  createPostgresDatasource,
  grantPermissionViaApi,
  loginViaApi,
} from '../helpers/datasources';
import {
  createDeploymentEnvironmentViaApi,
  createDeploymentPipelineViaApi,
  deleteDeploymentPipelineViaApi,
  type CreatedDeploymentPipeline,
} from '../helpers/deployments';
import { login } from '../helpers/login';
import { expandNavSection } from '../helpers/nav';
import { waitForSchemaChangePromotionStatus } from '../helpers/schemaChange';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

async function meIdViaApi(request: APIRequestContext, token: string): Promise<string> {
  const res = await request.get(`${apiBase()}/api/v1/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) throw new Error(`GET /me failed: ${res.status()} ${await res.text()}`);
  return ((await res.json()) as { id: string }).id;
}

async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.getByTestId('statement-0').locator('.cm-content');
  await content.click();
  await page.keyboard.press('ControlOrMeta+a');
  await page.keyboard.press('Backspace');
  await page.keyboard.type(sql, { delay: 10 });
  await page.keyboard.press('Escape');
}

// The promotion runs through the request-group run job, whose ShedLock hold floors it at ~30s.
test.describe.configure({ timeout: 180_000 });

test.describe.serial('schema change sets & drift (#883, #884)', () => {
  const stamp = Date.now();
  const pipelineName = `schema-pipeline-${stamp}`;
  const setName = `orders-archive-${stamp}`;
  const devName = `dev-${stamp}`;
  const prodName = `prod-${stamp}`;
  let adminToken = '';
  let pipeline: CreatedDeploymentPipeline | null = null;
  let devEnvironmentId = '';
  let changeSetId = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const adminId = await meIdViaApi(request, adminToken);
    const devDatasource = await createPostgresDatasource(request, adminToken, {
      name: `Schema change E2E dev ${stamp}`,
    });
    // prod points at the built-in, table-less `postgres` database, so a drift scan of prod
    // against dev reports dev's `public` schema as missing there.
    const prodDatasource = await createPostgresDatasource(request, adminToken, {
      name: `Schema change E2E prod ${stamp}`,
      databaseName: 'postgres',
    });
    // Promotion requires can_ddl for everyone, admins included.
    for (const datasource of [devDatasource, prodDatasource]) {
      await grantPermissionViaApi(request, adminToken, datasource.id, adminId, { canDdl: true });
    }
    pipeline = await createDeploymentPipelineViaApi(request, adminToken, {
      name: pipelineName,
      aiAnalysisEnabled: false,
    });
    // require_review=false on both rungs: a plan-less datasource cannot enforce review, and the
    // promotion gate refuses that combination (422 REVIEW_UNENFORCEABLE).
    const dev = await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: devName,
      requireReview: false,
      datasourceId: devDatasource.id,
    });
    devEnvironmentId = dev.id;
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: prodName,
      requireReview: false,
      datasourceId: prodDatasource.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (pipeline) await deleteDeploymentPipelineViaApi(request, adminToken, pipeline.id);
  });

  test('authors a change set, refuses a data statement and explains the blocked rung', async ({
    page,
  }) => {
    await login(page);
    await expandNavSection(page, 'Workflow', 'Schema changes');
    await page.getByRole('link', { name: 'Change sets' }).click();
    await expect(page).toHaveURL(/\/schema-change-sets$/);

    await page.getByRole('button', { name: /New change set/ }).first().click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'New schema change set' });
    await dialog.getByLabel('Pipeline').click();
    await page.locator('.ant-select-item-option').filter({ hasText: pipelineName }).click();
    await dialog.getByLabel('Name').fill(setName);
    await dialog.getByRole('button', { name: 'Create' }).click();

    await expect(page).toHaveURL(/\/schema-change-sets\/[0-9a-f-]{36}$/);
    changeSetId = page.url().split('/').pop() ?? '';
    await page.getByRole('button', { name: /Add statement/ }).click();

    // A change set carries schema statements only: a DELETE is refused on save, pinned to it.
    await typeInEditor(page, `DELETE FROM e2e_schema_change_${stamp}`);
    await page.getByRole('button', { name: /Save statements/ }).click();
    await expect(page.getByTestId('statement-problems-0')).toContainText('is a DELETE statement');

    await typeInEditor(page, `CREATE TABLE IF NOT EXISTS e2e_schema_change_${stamp} (id INT)`);
    await page.getByRole('button', { name: /Save statements/ }).click();
    await expect(page.getByText(/Statements saved/)).toBeVisible();

    await expect(page.getByTestId(`ladder-rung-${devName}`)).toContainText('Ready to promote');
    await expect(page.getByTestId(`ladder-reason-${prodName}`)).toContainText(
      `Blocked — ${devName} not yet applied`,
    );
  });

  test('promotes the entry rung, freezes the statements and unblocks the next rung', async ({
    page,
    request,
  }) => {
    await login(page);
    await page.goto('/schema-change-sets');
    await page.getByText(setName).click();

    await page.getByRole('button', { name: new RegExp(`Promote to ${devName}`) }).click();
    await page.getByRole('tooltip').getByRole('button', { name: 'Promote' }).click();
    await expect(page.getByText(`Promotion to ${devName} submitted`)).toBeVisible();

    await expect(page.getByTestId('statements-frozen')).toBeVisible();
    await expect(page.getByRole('button', { name: /Add statement/ })).toHaveCount(0);

    // Not review-gated, so the group is approved and executed by the run job.
    await waitForSchemaChangePromotionStatus(
      request,
      adminToken,
      changeSetId,
      devEnvironmentId,
      'APPLIED',
    );
    await page.reload();
    await expect(page.getByTestId(`ladder-rung-${devName}`)).toContainText('Applied');
    await expect(page.getByTestId(`ladder-rung-${prodName}`)).toContainText('Ready to promote');
    await expect(
      page.getByRole('button', { name: new RegExp(`Promote to ${prodName}`) }),
    ).toBeVisible();
  });

  test('scans an environment and reads its drift finding', async ({ page }) => {
    await login(page);
    await page.goto('/schema-drift');
    await page.getByLabel('Pipeline').click();
    await page.locator('.ant-select-item-option').filter({ hasText: pipelineName }).click();
    await expect(page.getByTestId(`drift-environment-${devName}`)).toBeVisible();
    const prod = page.getByTestId(`drift-environment-${prodName}`);
    await expect(prod).toBeVisible();

    // No drift config is needed for an on-demand scan; it compares against the rung below.
    await prod.getByRole('button', { name: /Scan now/ }).click();
    await expect(page.getByText('Scan started')).toBeVisible();
    const finding = prod.getByRole('row').filter({ hasText: 'Missing in target' }).first();
    await expect(finding).toBeVisible({ timeout: 60_000 });
    await expect(finding).toContainText('public');
  });
});
