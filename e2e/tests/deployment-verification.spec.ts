import { expect, test } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';
import {
  confirmDeploymentExecutionViaApi,
  createDeploymentEnvironmentViaApi,
  createDeploymentPipelineViaApi,
  deleteDeploymentPipelineViaApi,
  reportDeploymentOutcomeViaApi,
  triggerDeploymentViaApi,
  waitForDeploymentStatus,
  type CreatedDeploymentPipeline,
} from '../helpers/deployments';
import { login } from '../helpers/login';
import { activeTabPanel, clickTab, findRowAcrossPages } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// WireMock (the mock-ai service) resolves from the backend container to a private address —
// allowed by the outbound policy — and answers an unmapped path with 404, so every sample of this
// check fails against `expected_status: 200`. An unresolvable host would be refused at save time.
const FAILING_HEALTH_URL = 'http://mock-ai:8080/verification-health';

// The backend samples every 5 s in the e2e stack (ACCESSFLOW_DEPLOYGOV_VERIFICATION_CHECK).
test.describe.configure({ timeout: 120_000 });

test.describe.serial('post-deployment verification (#1027)', () => {
  const stamp = Date.now();
  let adminToken = '';
  let pipeline: CreatedDeploymentPipeline | null = null;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // AI and review off: every trigger lands straight in APPROVED, so no second user is needed.
    pipeline = await createDeploymentPipelineViaApi(request, adminToken, {
      name: `e2e-verification-1027-${stamp}`,
      provider: 'GENERIC',
      aiAnalysisEnabled: false,
    });
  });

  test.afterAll(async ({ request }) => {
    if (pipeline) await deleteDeploymentPipelineViaApi(request, adminToken, pipeline.id);
  });

  test('an admin adds an HTTP check to an environment and it round-trips', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/admin/deployment-pipelines/${pipeline!.id}`);
    await expect(page.getByRole('heading', { name: pipeline!.name })).toBeVisible({
      timeout: 15_000,
    });

    await clickTab(page, 'Environments');
    const envPanel = activeTabPanel(page);
    await envPanel.getByRole('button', { name: 'Add environment' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Add environment' });
    await expect(dialog).toBeVisible();
    const nameInput = dialog.getByLabel('Name', { exact: true });
    await nameInput.fill('canary');
    await expect(nameInput).toHaveValue('canary');

    await dialog.getByRole('switch', { name: 'Verify after release' }).click();
    await dialog.getByLabel('Check name').fill('health');
    await dialog.getByLabel('Health URL').fill(FAILING_HEALTH_URL);
    await dialog.getByLabel('Body contains (optional)').fill('ok');

    const saved = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith(`/deployment-pipelines/${pipeline!.id}/environments`),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save' }).click();
    const response = await saved;
    expect(response.status(), await response.text()).toBe(201);
    const body = (await response.json()) as {
      verification?: { window: string; checks: { name: string; url?: string }[] };
    };
    expect(body.verification?.window).toBe('PT15M');
    expect(body.verification?.checks[0]).toMatchObject({ name: 'health', url: FAILING_HEALTH_URL });

    const row = envPanel.locator('.ant-table-row', { hasText: 'canary' });
    await expect(row).toContainText('Checks: 1');

    await row.getByRole('button', { name: 'Edit' }).click();
    const editDialog = page.getByRole('dialog').filter({ hasText: 'Edit environment' });
    await expect(editDialog.getByLabel('Name', { exact: true })).toHaveValue('canary');
    await expect(editDialog.getByRole('switch', { name: 'Verify after release' })).toBeChecked();
    await expect(editDialog.getByLabel('Check name')).toHaveValue('health');
    await expect(editDialog.getByLabel('Health URL')).toHaveValue(FAILING_HEALTH_URL);
    await expect(editDialog.getByLabel('Body contains (optional)')).toHaveValue('ok');
    await editDialog.getByRole('button', { name: 'Cancel' }).click();
  });

  test('a breached verification shows as failed, never as a rollback', async ({
    page,
    request,
  }) => {
    const environment = `verify-${stamp}`;
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline!.id, {
      name: environment,
      requireReview: false,
      verification: {
        window: 'PT1M',
        sample_interval: 'PT30S',
        failure_threshold: 1,
        checks: [{ kind: 'HTTP_HEALTH', name: 'health', url: FAILING_HEALTH_URL }],
      },
    });
    const triggered = await triggerDeploymentViaApi(request, adminToken, {
      pipelineId: pipeline!.id,
      environment,
      version: `9.9.${stamp % 1000}`,
    });
    await waitForDeploymentStatus(request, adminToken, triggered.id, 'APPROVED');
    await confirmDeploymentExecutionViaApi(request, adminToken, triggered.id);
    const reported = await reportDeploymentOutcomeViaApi(
      request,
      adminToken,
      triggered.id,
      'SUCCEEDED',
    );
    expect(reported.ok, `outcome rejected: ${reported.error}`).toBe(true);

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/deployments/${triggered.id}`);

    // The page polls while RUNNING; the first failed sample breaches a threshold of 1.
    await expect(
      page.getByText('Verification failed — the release is still running'),
    ).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText(/AccessFlow did not roll back/)).toBeVisible();
    const samples = page.getByRole('row').filter({ hasText: 'health' }).filter({ hasText: '404' });
    await expect(samples.first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('health: 1/1 failed samples').first()).toBeVisible();

    await page.goto('/deployments');
    const listRow = page.getByRole('row').filter({ hasText: pipeline!.name }).filter({
      hasText: environment,
    });
    await findRowAcrossPages(page, listRow);
    await expect(listRow).toContainText('succeeded');
    await expect(listRow).toContainText('verification failed');
  });
});
