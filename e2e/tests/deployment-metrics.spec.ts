import { expect, test, type Page } from '@playwright/test';
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
import { activeTabPanel } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Two full trigger → execute → outcome cycles.
test.describe.configure({ timeout: 120_000 });

test.describe.serial('deployment metrics (#987)', () => {
  const stamp = Date.now();
  // The org-wide numbers are shared across concurrently running specs, so every assertion is
  // narrowed to an environment name only this run uses.
  const prodName = `metrics-prod-${stamp}`;

  let adminToken = '';
  let pipeline: CreatedDeploymentPipeline | null = null;

  async function deploy(
    request: Parameters<typeof triggerDeploymentViaApi>[0],
    version: string,
    outcome: 'SUCCEEDED' | 'FAILED',
  ): Promise<void> {
    const triggered = await triggerDeploymentViaApi(request, adminToken, {
      pipelineId: pipeline!.id,
      environment: prodName,
      version,
    });
    await waitForDeploymentStatus(request, adminToken, triggered.id, 'APPROVED');
    await confirmDeploymentExecutionViaApi(request, adminToken, triggered.id);
    const reported = await reportDeploymentOutcomeViaApi(
      request,
      adminToken,
      triggered.id,
      outcome,
    );
    expect(reported.ok, `outcome ${outcome} rejected: ${reported.error}`).toBe(true);
  }

  /** Narrows the page to this run's environment through the searchable Select. */
  async function pickEnvironment(page: Page): Promise<void> {
    await page.getByLabel('Environment', { exact: true }).click();
    await page.getByLabel('Environment', { exact: true }).fill(prodName);
    await page.getByTitle(prodName, { exact: true }).click();
  }

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // AI off and review off: the subject is the metrics, not the approval gate.
    pipeline = await createDeploymentPipelineViaApi(request, adminToken, {
      name: `e2e-metrics-987-${stamp}`,
      provider: 'GENERIC',
      aiAnalysisEnabled: false,
    });
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: prodName,
      requireReview: false,
    });
    await deploy(request, '1.0.0', 'SUCCEEDED');
    await deploy(request, '1.0.1', 'FAILED');
  });

  test.afterAll(async ({ request }) => {
    if (pipeline) await deleteDeploymentPipelineViaApi(request, adminToken, pipeline.id);
  });

  test('shows the frequency and change failure rate for one environment', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/deployment-metrics');
    await expect(page.getByRole('heading', { name: 'Deployment metrics' })).toBeVisible();

    await pickEnvironment(page);

    await expect(page.getByTestId('metric-frequency-value')).toHaveText('2');
    await expect(page.getByTestId('metric-change-failure-value')).toHaveText('50%');

    const breakdown = page.getByTestId('metrics-breakdown');
    const row = breakdown.getByRole('row').filter({ hasText: prodName });
    await expect(row).toContainText(pipeline!.name);
    await expect(row).toContainText('50%');
    await expect(breakdown.getByRole('row')).toHaveCount(2); // header + this environment
  });

  test('opens the same numbers from the pipeline settings Metrics tab', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/admin/deployment-pipelines/${pipeline!.id}?tab=metrics`);

    // AntD keeps inactive panels mounted, so every assertion is scoped to the visible one.
    const panel = activeTabPanel(page);
    await expect(panel.getByTestId('metric-frequency-value')).toHaveText('2');
    await expect(panel.getByTestId('metric-change-failure-value')).toHaveText('50%');
    await expect(panel.getByTestId('metrics-breakdown')).toHaveCount(0);
  });

  test('serves one pipeline on the standalone per-pipeline route', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/deployment-metrics/${pipeline!.id}`);

    await expect(page.getByText(`Metrics — ${pipeline!.name}`)).toBeVisible();
    await expect(page.getByTestId('metric-frequency-value')).toHaveText('2');
  });
});
