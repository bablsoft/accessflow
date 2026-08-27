import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
} from '../helpers/datasources';
import { getCurrentUserIdViaApi } from '../helpers/apiConnectors';
import {
  createDeploymentEnvironmentViaApi,
  createDeploymentPipelineViaApi,
  deleteDeploymentPipelineViaApi,
  getDeploymentGateViaApi,
  grantDeploymentPermissionViaApi,
  triggerDeploymentViaApi,
  waitForDeploymentStatus,
  type CreatedDeploymentPipeline,
} from '../helpers/deployments';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const SUBMITTER_PASSWORD = 'Submitter-Pwd!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Provisioning the submitter (invite → Mailcrab → accept) takes 1–2s, so bump
// the per-test budget beyond the 30s default.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('deployment governance review flow (#696)', () => {
  let adminAccessToken = '';
  let submitterEmail = '';
  let submitterToken = '';
  let pipeline: CreatedDeploymentPipeline | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // AI disabled so the trigger lands deterministically in PENDING_REVIEW.
    pipeline = await createDeploymentPipelineViaApi(request, adminAccessToken, {
      name: `e2e-deploy-696-${Date.now()}`,
      provider: 'GITHUB_ACTIONS',
      aiAnalysisEnabled: false,
    });
    await createDeploymentEnvironmentViaApi(request, adminAccessToken, pipeline.id, {
      name: 'production',
      requiredApprovals: 1,
    });

    // The submitter is a non-admin ANALYST (admins bypass the can_trigger check,
    // and self-approval is blocked — the reviewing bootstrap admin must differ).
    submitterEmail = `af696-submitter-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminAccessToken, submitterEmail, 'AF-696 Submitter', 'ANALYST');
    const token = await waitForInviteToken(request, submitterEmail);
    await acceptInvitationViaApi(request, token, SUBMITTER_PASSWORD, 'AF-696 Submitter');
    submitterToken = await loginViaApi(request, submitterEmail, SUBMITTER_PASSWORD);
    const submitterId = await getCurrentUserIdViaApi(request, submitterToken);
    await grantDeploymentPermissionViaApi(request, adminAccessToken, pipeline.id, submitterId, {
      canTrigger: true,
    });
  });

  test.afterAll(async ({ request }) => {
    if (pipeline) {
      try {
        await deleteDeploymentPipelineViaApi(request, adminAccessToken, pipeline.id);
      } catch (err) {
        // Requests created by the tests may reference the pipeline; unique names
        // keep reruns collision-free even when cleanup can't delete it.
        // eslint-disable-next-line no-console
        console.warn(`deployment pipeline cleanup skipped: ${String(err)}`);
      }
    }
  });

  test('CI trigger → admin approves in the queue → gate answers releasable', async ({
    browser,
    request,
  }) => {
    if (!pipeline) throw new Error('pipeline not created in beforeAll');

    const version = `2.4.1-${Date.now()}`;
    const triggered = await triggerDeploymentViaApi(request, submitterToken, {
      pipelineId: pipeline.id,
      environment: 'production',
      version,
      externalRunId: `run-${Date.now()}`,
      justification: 'AF-696 e2e review flow',
    });
    // Routing/AI-skip settles asynchronously off the submit event.
    await waitForDeploymentStatus(request, adminAccessToken, triggered.id, 'PENDING_REVIEW');

    // The gate is fail-closed while review is pending.
    const pendingGate = await getDeploymentGateViaApi(request, adminAccessToken, triggered.id);
    expect(pendingGate.releasable).toBe(false);

    const reviewerCtx = await browser.newContext();
    try {
      const reviewerPage = await reviewerCtx.newPage();
      await loginViaUi(reviewerPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await reviewerPage.goto('/deployment-reviews');

      const row = reviewerPage.locator('.ant-table-row', { hasText: version });
      await expect(row.first()).toBeVisible({ timeout: 15_000 });
      await row.first().getByRole('button', { name: 'Approve' }).click();

      const dialog = reviewerPage.getByRole('dialog');
      await expect(dialog).toBeVisible();
      await dialog
        .locator('textarea')
        .fill('AF-696 e2e approval');
      await dialog.getByRole('button', { name: 'Approve' }).click();

      await expect(
        reviewerPage.locator('.ant-message').getByText('Approved', { exact: true }),
      ).toBeVisible({ timeout: 10_000 });
    } finally {
      await reviewerCtx.close();
    }

    await waitForDeploymentStatus(request, adminAccessToken, triggered.id, 'APPROVED');
    const gate = await getDeploymentGateViaApi(request, adminAccessToken, triggered.id);
    expect(gate.status).toBe('APPROVED');
    expect(gate.frozen).toBe(false);
    expect(gate.releasable).toBe(true);
  });

  test('the deployment detail page shows the decision and the releasability banner', async ({
    browser,
    request,
  }) => {
    if (!pipeline) throw new Error('pipeline not created in beforeAll');

    const version = `2.4.2-${Date.now()}`;
    const triggered = await triggerDeploymentViaApi(request, submitterToken, {
      pipelineId: pipeline.id,
      environment: 'production',
      version,
      externalRunId: `run-${Date.now()}`,
    });
    await waitForDeploymentStatus(request, adminAccessToken, triggered.id, 'PENDING_REVIEW');

    const reviewerCtx = await browser.newContext();
    try {
      const reviewerPage = await reviewerCtx.newPage();
      await loginViaUi(reviewerPage, ADMIN_EMAIL, ADMIN_PASSWORD);

      // Approve from the queue, then follow the row into the detail page.
      await reviewerPage.goto('/deployment-reviews');
      const row = reviewerPage.locator('.ant-table-row', { hasText: version });
      await expect(row.first()).toBeVisible({ timeout: 15_000 });
      await row.first().getByRole('button', { name: 'Approve' }).click();
      const dialog = reviewerPage.getByRole('dialog');
      await expect(dialog).toBeVisible();
      await dialog.getByRole('button', { name: 'Approve' }).click();
      await expect(
        reviewerPage.locator('.ant-message').getByText('Approved', { exact: true }),
      ).toBeVisible({ timeout: 10_000 });

      await reviewerPage.goto(`/deployments/${triggered.id}`);
      // Status pill flips to Approved and the gate banner reports releasability.
      await expect(
        reviewerPage.locator('.af-pill').getByText('Approved', { exact: true }),
      ).toBeVisible({ timeout: 15_000 });
      await expect(
        reviewerPage.getByText(/Approved and releasable/),
      ).toBeVisible({ timeout: 15_000 });
      // The approvals card reflects the granted decision. The same sentence also renders as
      // the timeline's review stage, so match the first occurrence rather than both.
      await expect(reviewerPage.getByText('1 of 1 approvals').first()).toBeVisible();
    } finally {
      await reviewerCtx.close();
    }
  });

  test('the submitter sees their own deployment in /deployments and cannot review it', async ({
    browser,
    request,
  }) => {
    if (!pipeline) throw new Error('pipeline not created in beforeAll');

    const version = `2.4.3-${Date.now()}`;
    const triggered = await triggerDeploymentViaApi(request, submitterToken, {
      pipelineId: pipeline.id,
      environment: 'production',
      version,
      externalRunId: `run-${Date.now()}`,
    });
    await waitForDeploymentStatus(request, adminAccessToken, triggered.id, 'PENDING_REVIEW');

    const submitterCtx = await browser.newContext();
    try {
      const submitterPage = await submitterCtx.newPage();
      await loginViaUi(submitterPage, submitterEmail, SUBMITTER_PASSWORD);

      await submitterPage.goto('/deployments');
      const row = submitterPage.locator('.ant-table-row', { hasText: version });
      await expect(row.first()).toBeVisible({ timeout: 15_000 });
      await row.first().click();

      await submitterPage.waitForURL(`**/deployments/${triggered.id}`, { timeout: 15_000 });
      // The submitter gets the cancel action, never the reviewer decision buttons.
      await expect(
        submitterPage.getByRole('button', { name: 'Cancel deployment' }),
      ).toBeVisible({ timeout: 15_000 });
      await expect(submitterPage.getByRole('button', { name: 'Approve' })).toHaveCount(0);
    } finally {
      await submitterCtx.close();
    }
  });
});
