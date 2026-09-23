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
  await page.keyboard.type(sql, { delay: 10 });
  await page.keyboard.press('Escape');
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('schema change sets & drift (#883)', () => {
  const stamp = Date.now();
  const pipelineName = `schema-pipeline-${stamp}`;
  const setName = `orders-archive-${stamp}`;
  const devName = `dev-${stamp}`;
  const prodName = `prod-${stamp}`;
  let adminToken = '';
  let pipeline: CreatedDeploymentPipeline | null = null;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const adminId = await meIdViaApi(request, adminToken);
    const datasource = await createPostgresDatasource(request, adminToken, {
      name: `Schema change E2E ${stamp}`,
    });
    // Promotion requires can_ddl for everyone, admins included.
    await grantPermissionViaApi(request, adminToken, datasource.id, adminId, { canDdl: true });
    pipeline = await createDeploymentPipelineViaApi(request, adminToken, {
      name: pipelineName,
      aiAnalysisEnabled: false,
    });
    // require_review=false on both rungs: a plan-less datasource cannot enforce review, and the
    // promotion gate refuses that combination (422 REVIEW_UNENFORCEABLE).
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: devName,
      requireReview: false,
      datasourceId: datasource.id,
    });
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: prodName,
      requireReview: false,
      datasourceId: datasource.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (pipeline) await deleteDeploymentPipelineViaApi(request, adminToken, pipeline.id);
  });

  test('authors a change set and explains the blocked ladder rung', async ({ page }) => {
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
    await page.getByRole('button', { name: /Add statement/ }).click();
    await typeInEditor(page, `CREATE TABLE IF NOT EXISTS e2e_schema_change_${stamp} (id INT)`);
    await page.getByRole('button', { name: /Save statements/ }).click();
    await expect(page.getByText(/Statements saved/)).toBeVisible();

    await expect(page.getByTestId(`ladder-rung-${devName}`)).toContainText('Ready to promote');
    await expect(page.getByTestId(`ladder-reason-${prodName}`)).toContainText(
      `Blocked — ${devName} not yet applied`,
    );
  });

  test('promotes the entry rung and freezes the statements', async ({ page }) => {
    await login(page);
    await page.goto('/schema-change-sets');
    await page.getByText(setName).click();

    await page.getByRole('button', { name: new RegExp(`Promote to ${devName}`) }).click();
    await page.getByRole('tooltip').getByRole('button', { name: 'Promote' }).click();
    await expect(page.getByText(`Promotion to ${devName} submitted`)).toBeVisible();

    await expect(page.getByTestId('statements-frozen')).toBeVisible();
    await expect(page.getByRole('button', { name: /Add statement/ })).toHaveCount(0);
  });

  test('lists the pipeline environments on the drift page', async ({ page }) => {
    await login(page);
    await page.goto('/schema-drift');
    await page.getByLabel('Pipeline').click();
    await page.locator('.ant-select-item-option').filter({ hasText: pipelineName }).click();
    await expect(page.getByTestId(`drift-environment-${devName}`)).toBeVisible();
    await expect(page.getByTestId(`drift-environment-${prodName}`)).toBeVisible();
  });
});
