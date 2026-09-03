import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  createApiKeyViaApi,
  inviteUserViaApi,
  loginViaApi,
  revokeApiKeyViaApi,
  waitForInviteToken,
} from '../helpers/datasources';
import { getCurrentUserIdViaApi } from '../helpers/apiConnectors';
import {
  acknowledgeRollbackReviewViaApi,
  approveDeploymentViaApi,
  confirmDeploymentExecutionViaApi,
  createDeploymentEnvironmentViaApi,
  createDeploymentPipelineViaApi,
  deleteDeploymentPipelineViaApi,
  getDeploymentGateViaApi,
  grantDeploymentPermissionViaApi,
  listDeploymentRollbackReviewsViaApi,
  reportDeploymentOutcomeViaApi,
  triggerDeploymentViaApi,
  waitForDeploymentStatus,
  type CreatedDeploymentPipeline,
} from '../helpers/deployments';
import { login } from '../helpers/login';
import { findRowAcrossPages } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const SUBMITTER_PASSWORD = 'Submitter-Pwd!123';

// Provisioning the submitter (invite → Mailcrab → accept) takes 1–2s, so bump
// the per-test budget beyond the 30s default.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('deployment governance review flow (#696)', () => {
  let adminAccessToken = '';
  let submitterEmail = '';
  let submitterToken = '';
  let submitterApiKey = '';
  let submitterApiKeyId = '';
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

    // The submitter is a non-admin REVIEWER: admins bypass the can_trigger check, so they
    // cannot stand in here — and REVIEWER (not ANALYST) is what makes the self-approval ban
    // *observable*. The decision endpoints are gated on PERM_DEPLOYMENT_REVIEW, so an ANALYST
    // is turned away at method security with a 403 and never reaches the provenance check.
    // Holding the permission and still being refused is the guarantee worth testing.
    submitterEmail = `af696-submitter-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminAccessToken, submitterEmail, 'AF-696 Submitter', 'REVIEWER');
    const token = await waitForInviteToken(request, submitterEmail);
    await acceptInvitationViaApi(request, token, SUBMITTER_PASSWORD, 'AF-696 Submitter');
    submitterToken = await loginViaApi(request, submitterEmail, SUBMITTER_PASSWORD);
    const submitterId = await getCurrentUserIdViaApi(request, submitterToken);
    await grantDeploymentPermissionViaApi(request, adminAccessToken, pipeline.id, submitterId, {
      canTrigger: true,
    });
    // CI authenticates with an API key, not a bearer JWT. The key's owning user is the
    // submitter, so the same can_trigger grant and self-approval ban apply to it.
    const key = await createApiKeyViaApi(request, submitterToken, `af697-ci-${randomUUID()}`);
    submitterApiKey = key.rawKey;
    submitterApiKeyId = key.id;
  });

  test.afterAll(async ({ request }) => {
    // A non-expiring key on the shared seeded org would outlive the run.
    if (submitterApiKeyId && submitterToken) {
      await revokeApiKeyViaApi(request, submitterToken, submitterApiKeyId);
    }
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
      // Exercise the real machine path: X-API-Key through ApiKeyAuthenticationFilter.
      apiKey: submitterApiKey,
    });
    // Routing/AI-skip settles asynchronously off the submit event.
    await waitForDeploymentStatus(request, adminAccessToken, triggered.id, 'PENDING_REVIEW');

    // The gate is fail-closed while review is pending.
    const pendingGate = await getDeploymentGateViaApi(request, adminAccessToken, triggered.id);
    expect(pendingGate.releasable).toBe(false);

    const reviewerCtx = await browser.newContext();
    try {
      const reviewerPage = await reviewerCtx.newPage();
      await login(reviewerPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await reviewerPage.goto('/reviews?tab=deployments');

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
      await login(reviewerPage, ADMIN_EMAIL, ADMIN_PASSWORD);

      // Approve from the queue, then follow the row into the detail page.
      await reviewerPage.goto('/reviews?tab=deployments');
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

  // #770: the queue row navigates to the detail page, so the decision has to be reachable there
  // too — a reviewer who opens a deployment to read its metadata must not have to go back.
  test('a reviewer approves from the deployment detail page reached through the queue row', async ({
    browser,
    request,
  }) => {
    if (!pipeline) throw new Error('pipeline not created in beforeAll');

    const version = `2.4.5-${Date.now()}`;
    const triggered = await triggerDeploymentViaApi(request, submitterToken, {
      pipelineId: pipeline.id,
      environment: 'production',
      version,
      externalRunId: `run-${Date.now()}`,
      justification: 'AF-770 decide from the detail page',
    });
    await waitForDeploymentStatus(request, adminAccessToken, triggered.id, 'PENDING_REVIEW');

    const reviewerCtx = await browser.newContext();
    try {
      const reviewerPage = await reviewerCtx.newPage();
      await login(reviewerPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await reviewerPage.goto('/reviews?tab=deployments');

      // The queue is org-shared, so a concurrent spec can push this row past page 1.
      const row = reviewerPage.locator('.ant-table-row', { hasText: version });
      await findRowAcrossPages(reviewerPage, row);
      await row.first().click();
      await reviewerPage.waitForURL(`**/deployments/${triggered.id}`, { timeout: 15_000 });
      // Anchor on the detail page's own content before touching its controls: the queue's rows
      // (each with their own small Approve button) stay mounted through the SPA transition, and
      // a strict-mode violation is a hard failure that never retries.
      await expect(
        reviewerPage.getByText('AF-770 decide from the detail page'),
      ).toBeVisible({ timeout: 15_000 });

      // The eligibility flag on the detail response is what puts these here.
      const approve = reviewerPage.getByRole('button', { name: 'Approve' });
      await expect(approve).toBeVisible({ timeout: 15_000 });
      await expect(reviewerPage.getByRole('button', { name: 'Reject' })).toBeVisible();
      await approve.click();

      const dialog = reviewerPage.getByRole('dialog');
      await expect(dialog).toBeVisible();
      await dialog.locator('textarea').fill('AF-770 approved from the detail page');
      await dialog.getByRole('button', { name: 'Approve' }).click();

      await expect(
        reviewerPage.locator('.ant-message').getByText('Approved', { exact: true }),
      ).toBeVisible({ timeout: 10_000 });
      await expect(dialog).toBeHidden();
      // The page refreshes itself off the mutation's invalidation — no reload.
      await expect(
        reviewerPage.locator('.af-pill').getByText('Approved', { exact: true }),
      ).toBeVisible({ timeout: 15_000 });
      // Decided requests offer no further decision.
      await expect(reviewerPage.getByRole('button', { name: 'Approve' })).toHaveCount(0);
    } finally {
      await reviewerCtx.close();
    }

    await waitForDeploymentStatus(request, adminAccessToken, triggered.id, 'APPROVED');
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
      await login(submitterPage, submitterEmail, SUBMITTER_PASSWORD);

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
      await expect(submitterPage.getByRole('button', { name: 'Reject' })).toHaveCount(0);
      // ...and is told why, rather than left to wonder where the decision went (#770).
      await expect(
        submitterPage.getByText('You submitted this deployment — someone else must review it'),
      ).toBeVisible();
    } finally {
      await submitterCtx.close();
    }
  });

  // The machine half of the contract (#693): confirm-execution, outcome reporting, and the
  // rollback follow-up review the deployment's own submitter can never close. Driven entirely
  // over the API-key path a CI job actually uses, then acknowledged in the reviewer UI.
  test('CI confirms execution, reports a rollback, and an admin acknowledges the follow-up', async ({
    browser,
    request,
  }) => {
    if (!pipeline) throw new Error('pipeline not created in beforeAll');

    const version = `2.4.4-${Date.now()}`;
    const detail = `af697 rollback ${randomUUID()}`;
    const triggered = await triggerDeploymentViaApi(request, submitterToken, {
      pipelineId: pipeline.id,
      environment: 'production',
      version,
      externalRunId: `run-${Date.now()}`,
      apiKey: submitterApiKey,
    });
    await waitForDeploymentStatus(request, adminAccessToken, triggered.id, 'PENDING_REVIEW');

    // The submitter holds DEPLOYMENT_REVIEW, so this reaches the provenance check rather than
    // method security: the ban is about who submitted, not about who may review.
    const selfApprove = await approveDeploymentViaApi(request, submitterToken, triggered.id);
    expect(selfApprove.status).toBe(409);
    expect(selfApprove.error).toBe('DEPLOYMENT_REQUEST_SELF_APPROVAL');

    const decision = await approveDeploymentViaApi(
      request,
      adminAccessToken,
      triggered.id,
      'AF-697 e2e approval',
    );
    expect(decision.ok).toBe(true);
    expect(decision.resultingStatus).toBe('APPROVED');

    const gate = await getDeploymentGateViaApi(request, adminAccessToken, triggered.id);
    expect(gate.releasable).toBe(true);

    // The pipeline confirms it proceeded, then reports that it rolled the release back.
    const executed = await confirmDeploymentExecutionViaApi(
      request,
      submitterToken,
      triggered.id,
      submitterApiKey,
    );
    expect(executed.status).toBe('EXECUTED');

    const reported = await reportDeploymentOutcomeViaApi(
      request,
      submitterToken,
      triggered.id,
      'ROLLED_BACK',
      { detail, apiKey: submitterApiKey },
    );
    expect(reported.ok).toBe(true);

    // Repeating the same outcome is idempotent; a different one conflicts.
    const repeat = await reportDeploymentOutcomeViaApi(
      request,
      submitterToken,
      triggered.id,
      'ROLLED_BACK',
      { detail, apiKey: submitterApiKey },
    );
    expect(repeat.ok).toBe(true);
    const conflicting = await reportDeploymentOutcomeViaApi(
      request,
      submitterToken,
      triggered.id,
      'SUCCEEDED',
      { apiKey: submitterApiKey },
    );
    expect(conflicting.status).toBe(409);
    expect(conflicting.error).toBe('DEPLOYMENT_OUTCOME_CONFLICT');

    // A rollback on a review-required environment opens a follow-up review.
    const reviews = await listDeploymentRollbackReviewsViaApi(
      request,
      adminAccessToken,
      'PENDING_REVIEW',
    );
    const review = reviews.find((r) => r.deployment_request_id === triggered.id);
    expect(review, 'rollback review opened for the rolled-back deployment').toBeTruthy();

    // Same provenance rule on the follow-up review: the submitter holds DEPLOYMENT_REVIEW and
    // is still refused.
    const selfAck = await acknowledgeRollbackReviewViaApi(request, submitterToken, review!.id);
    expect(selfAck.status).toBe(409);
    expect(selfAck.error).toBe('DEPLOYMENT_ROLLBACK_REVIEW_SELF_ACKNOWLEDGE');

    const reviewerCtx = await browser.newContext();
    try {
      const reviewerPage = await reviewerCtx.newPage();
      await login(reviewerPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await reviewerPage.goto('/reviews?tab=rollbacks');

      const row = reviewerPage.locator('.ant-table-row', { hasText: detail });
      await expect(row.first()).toBeVisible({ timeout: 15_000 });
      await row.first().getByRole('button', { name: 'Acknowledge' }).click();

      const dialog = reviewerPage.getByRole('dialog');
      await expect(dialog).toBeVisible();
      await dialog.locator('textarea').fill('AF-697 e2e rollback acknowledged');
      await dialog.getByRole('button', { name: 'Acknowledge' }).click();

      await expect(
        reviewerPage.locator('.ant-message').getByText('Rollback acknowledged', { exact: true }),
      ).toBeVisible({ timeout: 10_000 });
    } finally {
      await reviewerCtx.close();
    }

    const afterAck = await listDeploymentRollbackReviewsViaApi(request, adminAccessToken, 'REVIEWED');
    expect(afterAck.some((r) => r.deployment_request_id === triggered.id)).toBe(true);
  });
});
