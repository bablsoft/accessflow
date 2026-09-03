import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  cancelQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  inviteUserViaApi,
  loginViaApi,
  submitQueryViaApi,
  waitForApprovalPrediction,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const APPROVER_PASSWORD = 'Approver-Pwd!123';

/**
 * The e2e org has no decided review history, so the per-org logistic model never clears its
 * quality gate and every query is scored as the `MODEL_NOT_SERVING` sentinel. That makes the
 * copy deterministic: the queue shows the unscored dash, the detail card the cold-start notice.
 */
const COLD_START_NOTICE = 'Not enough review history yet.';

/** Opens the "All pending" tab, which is not capped the way the default "Assigned to you" is. */
async function openAllPendingTab(page: Page): Promise<void> {
  await page.goto('/reviews');
  // Since #772 the Queries tab's sub-filter is an AntD Segmented control: its radio inputs are
  // visually hidden, so click the visible option label rather than the radio role.
  await page.locator('.ant-segmented-item', { hasText: /All pending/ }).click();
}

// Provisioning an approver via Mailcrab → invitation accept costs 1–2s, and each test drives two
// browser contexts. The default 30s budget is too tight for the whole chain.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('approval-outcome prediction (AF-645)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let approverAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;
  /** [queryId, submitter's token] — cancelled in afterAll so the queue is left as we found it. */
  const submittedQueries: Array<[string, string]> = [];

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    approverEmail = `af645-approver-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverEmail,
      'AF-645 Approver',
      'ADMIN',
    );
    const inviteToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(
      request,
      inviteToken,
      APPROVER_PASSWORD,
      'AF-645 Approver',
    );
    approverAccessToken = await loginViaApi(
      request,
      approverEmail,
      APPROVER_PASSWORD,
    );

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF645 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF645 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  // Cancel both queries rather than leaving them PENDING_REVIEW. Deleting the datasource only
  // soft-deletes it, so its queries would otherwise sit in every later reviewer's queue — and the
  // "Assigned to you" tab caps at eight rows, so leftovers eat the budget of the specs that follow.
  test.afterAll(async ({ request }) => {
    for (const [queryId, token] of submittedQueries) {
      await cancelQueryViaApi(request, token, queryId).catch(() => {
        /* already decided or gone — teardown must not fail the run */
      });
    }
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. The queue column and the detail card, seen by a reviewer ───────────
  test('reviewer sees the approval-likelihood column and the cold-start card', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1',
      'AF-645 queue column',
    );
    submittedQueries.push([submitted.id, adminAccessToken]);
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );

    const prediction = await waitForApprovalPrediction(
      request,
      approverAccessToken,
      submitted.id,
    );
    expect(prediction.skipped).toBe(true);
    expect(prediction.skipped_reason).toBe('MODEL_NOT_SERVING');
    // The API omits null fields rather than emitting them, so the sentinel reads as absent.
    expect(prediction.probability ?? null).toBeNull();

    await login(page, approverEmail, APPROVER_PASSWORD);
    // "All pending" rather than the default tab, which slices to the first eight rows — with other
    // specs' queries in the queue this one is not guaranteed to survive the cut.
    await openAllPendingTab(page);

    await expect(
      page.getByRole('columnheader', { name: 'Approval likelihood' }),
    ).toBeVisible({ timeout: 15_000 });

    // The queue renders the full UUID in the row, so this stays specific even when other specs
    // leave queries sitting in PENDING_REVIEW.
    await expect(page.getByText(submitted.id, { exact: true })).toBeVisible({
      timeout: 15_000,
    });
    const row = page.getByRole('row').filter({ hasText: submitted.id });
    await expect(row.getByTestId('approval-likelihood-empty')).toBeVisible();

    // Detail card. Navigate directly rather than clicking through the row — the row's own click
    // target is covered by the approve/reject specs.
    await page.goto(`/queries/${submitted.id}`);
    // DetailCard renders its title as a plain span; the queue's identically-worded column header
    // is on a different page, so this stays unambiguous.
    await expect(
      page.getByText('Approval likelihood', { exact: true }),
    ).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(COLD_START_NOTICE)).toBeVisible();
  });

  // ── 2. The anti-gaming gate: never your own request ───────────────────────
  test('a reviewer is not shown the prediction for a query they submitted', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // Submitted by the approver themselves — the case the gate exists for.
    const submitted = await submitQueryViaApi(
      request,
      approverAccessToken,
      datasource.id,
      'SELECT 2',
      'AF-645 own request',
    );
    submittedQueries.push([submitted.id, approverAccessToken]);
    await waitForQueryStatus(
      request,
      approverAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );
    // Read through the admin's token to prove a row really was scored — otherwise this test
    // would still pass if scoring had simply never run.
    await waitForApprovalPrediction(request, adminAccessToken, submitted.id);

    // The API withholds the block from the submitter, so the client has nothing to leak.
    const asSubmitter = await request.get(
      `${apiBase()}/api/v1/queries/${submitted.id}`,
      { headers: { Authorization: `Bearer ${approverAccessToken}` } },
    );
    expect(asSubmitter.ok()).toBe(true);
    expect(
      ((await asSubmitter.json()) as { approval_prediction?: unknown })
        .approval_prediction ?? null,
    ).toBeNull();

    await login(page, approverEmail, APPROVER_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);

    await expect(page.getByRole('heading', { level: 1 })).toBeVisible({
      timeout: 15_000,
    });
    await expect(
      page.getByText('Approval likelihood', { exact: true }),
    ).toHaveCount(0);
    await expect(page.getByText(COLD_START_NOTICE)).toHaveCount(0);

    // The queue never lists your own request either, so the column cannot leak it there. Assert on
    // the uncapped "All pending" tab, so an absent row means "excluded" rather than "past the cut".
    await openAllPendingTab(page);
    await expect(
      page.getByRole('columnheader', { name: 'Approval likelihood' }),
    ).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(submitted.id, { exact: true })).toHaveCount(0);
  });
});
