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
import { activeTabPanel, findRowAcrossPages } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Two full trigger → execute → outcome cycles plus a third for the rollback.
test.describe.configure({ timeout: 120_000 });

test.describe.serial('deployment version matrix & drift (#743)', () => {
  const stamp = Date.now();
  // Environment names AND tags carry the run stamp: the org-wide matrix is shared, so a bare
  // `prod` tag would pull in rows from any concurrently-running spec.
  const prodName = `prod-${stamp}`;
  const stagingName = `staging-${stamp}`;
  const prodTag = `prodtag-${stamp}`;
  const sharedTag = `acme-${stamp}`;

  let adminToken = '';
  let pipeline: CreatedDeploymentPipeline | null = null;

  /** Drives one deployment all the way to a reported outcome. */
  async function deploy(
    request: Parameters<typeof triggerDeploymentViaApi>[0],
    environment: string,
    version: string,
    outcome: 'SUCCEEDED' | 'FAILED' | 'ROLLED_BACK' = 'SUCCEEDED',
  ): Promise<string> {
    const triggered = await triggerDeploymentViaApi(request, adminToken, {
      pipelineId: pipeline!.id,
      environment,
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
    return triggered.id;
  }

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // AI off and review off: the subject here is the matrix, not the approval gate, so every
    // trigger lands straight in APPROVED and needs no second user to avoid self-approval.
    pipeline = await createDeploymentPipelineViaApi(request, adminToken, {
      name: `e2e-versions-743-${stamp}`,
      provider: 'GENERIC',
      aiAnalysisEnabled: false,
    });
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: prodName,
      requireReview: false,
      tags: [prodTag, sharedTag],
    });
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: stagingName,
      requireReview: false,
      tags: [sharedTag],
    });

    // prod first, so its executed_at is the older one and it ends up behind staging.
    await deploy(request, prodName, '2.3.9');
    await deploy(request, stagingName, '2.4.1');
  });

  test.afterAll(async ({ request }) => {
    if (pipeline) await deleteDeploymentPipelineViaApi(request, adminToken, pipeline.id);
  });

  test('shows both environments with their versions, tags and drift badges', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/deployment-versions');

    await findRowAcrossPages(page, page.getByRole('row').filter({ hasText: prodName }));
    const prodRow = page.getByRole('row').filter({ hasText: prodName });
    await expect(prodRow).toContainText('2.3.9');
    await expect(prodRow).toContainText(prodTag);
    // Both deploys land within the same second, so days_behind is 0 and the badge takes its
    // versions-only form. Never assert a day count here.
    await expect(prodRow).toContainText('1 version behind');

    const stagingRow = page.getByRole('row').filter({ hasText: stagingName });
    await findRowAcrossPages(page, stagingRow);
    await expect(stagingRow).toContainText('2.4.1');
    await expect(stagingRow).toContainText('Up to date');
  });

  test('narrows the matrix to one row by tag', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/deployment-versions');
    await findRowAcrossPages(page, page.getByRole('row').filter({ hasText: prodName }));

    await page.getByLabel('Tag', { exact: true }).click();
    await page.getByTitle(prodTag, { exact: true }).click();

    await expect(page.getByRole('row').filter({ hasText: prodName })).toBeVisible();
    await expect(page.getByRole('row').filter({ hasText: stagingName })).toHaveCount(0);
  });

  test('filters down to the environments that are behind', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/deployment-versions');
    await findRowAcrossPages(page, page.getByRole('row').filter({ hasText: prodName }));

    await page.getByLabel('Drift', { exact: true }).click();
    await page.getByTitle('Behind latest only', { exact: true }).click();

    await findRowAcrossPages(page, page.getByRole('row').filter({ hasText: prodName }));
    await expect(page.getByRole('row').filter({ hasText: stagingName })).toHaveCount(0);
  });

  test('lists every deployment to an environment in the history drawer', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    // ?tab= sync means the Versions tab needs no click on the overflowed 7-tab strip.
    await page.goto(`/admin/deployment-pipelines/${pipeline!.id}?tab=versions`);

    // AntD keeps inactive panels mounted, so every assertion is scoped to the visible one.
    const panel = activeTabPanel(page);
    const stagingRow = panel.getByRole('row').filter({ hasText: stagingName });
    await expect(stagingRow).toContainText('2.4.1');
    await stagingRow.getByRole('button', { name: 'History' }).click();

    const drawer = page.getByRole('dialog').filter({ hasText: 'Deployment history' });
    await expect(drawer).toContainText('2.4.1');
    await expect(drawer).toContainText('Executed');
    await expect(drawer).toContainText('succeeded');
  });

  test('reaches the same matrix through the standalone per-pipeline route', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/deployment-versions/${pipeline!.id}`);

    await expect(page.getByRole('row').filter({ hasText: prodName })).toContainText('2.3.9');
    await expect(page.getByRole('row').filter({ hasText: stagingName })).toContainText('2.4.1');
  });

  test('round-trips a tag through the admin environment editor', async ({ page }) => {
    const newTag = `tier1-${stamp}`;
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/admin/deployment-pipelines/${pipeline!.id}?tab=environments`);

    const panel = activeTabPanel(page);
    await panel.getByRole('row').filter({ hasText: stagingName }).getByRole('button', { name: 'Edit' }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: 'Edit environment' });
    await dialog.getByLabel('Tags').fill(newTag);
    await dialog.getByLabel('Tags').press('Enter');
    await dialog.getByRole('button', { name: 'Save' }).click();

    await expect(panel.getByRole('row').filter({ hasText: stagingName })).toContainText(newTag);

    // …and the matrix picks the new tag up.
    await page.goto('/deployment-versions');
    await findRowAcrossPages(page, page.getByRole('row').filter({ hasText: stagingName }));
    await expect(page.getByRole('row').filter({ hasText: stagingName })).toContainText(newTag);
  });

  test('flips the badge to "reverted" when an outcome is rolled back', async ({ page, request }) => {
    await deploy(request, stagingName, '2.5.0', 'ROLLED_BACK');

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/deployment-versions');
    await findRowAcrossPages(page, page.getByRole('row').filter({ hasText: stagingName }));

    // The tracker's single-level undo restores the version staging ran before 2.5.0.
    await expect(page.getByRole('row').filter({ hasText: stagingName })).toContainText(
      'reverted to 2.4.1',
    );
  });
});
