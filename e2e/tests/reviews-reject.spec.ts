import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const APPROVER_PASSWORD = 'Approver-Pwd!123';

async function loginViaUi(
  page: Page,
  email: string,
  password: string,
): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Two-context tests + invitation roundtrips + status polls run longer than
// the default 30s budget. Matches the timeout used by query-execute.spec.ts.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('reviews reject + request-changes (AF-269)', () => {
  let adminAccessToken = '';
  let approverAEmail = '';
  let approverAToken = '';
  let approverBEmail = '';
  let approverBToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    approverAEmail = `af269-approver-a-${randomUUID()}@e2e.local`;
    approverBEmail = `af269-approver-b-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);

    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverAEmail,
      'AF-269 Approver A',
      'ADMIN',
    );
    const tokenA = await waitForInviteToken(request, approverAEmail);
    await acceptInvitationViaApi(
      request,
      tokenA,
      APPROVER_PASSWORD,
      'AF-269 Approver A',
    );
    approverAToken = await loginViaApi(
      request,
      approverAEmail,
      APPROVER_PASSWORD,
    );

    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverBEmail,
      'AF-269 Approver B',
      'ADMIN',
    );
    const tokenB = await waitForInviteToken(request, approverBEmail);
    await acceptInvitationViaApi(
      request,
      tokenB,
      APPROVER_PASSWORD,
      'AF-269 Approver B',
    );
    approverBToken = await loginViaApi(
      request,
      approverBEmail,
      APPROVER_PASSWORD,
    );

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF269 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF269 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Reject with comment via /reviews modal ─────────────────────────────
  test('reject from /reviews requires a comment and surfaces it on the timeline', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 11',
      'AF-269 reject happy path',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );

    const approverCtx = await browser.newContext();
    try {
      const approverPage = await approverCtx.newPage();
      await loginViaUi(approverPage, approverAEmail, APPROVER_PASSWORD);
      await approverPage.goto('/reviews');

      await expect(
        approverPage.getByText(submitted.id, { exact: true }),
      ).toBeVisible({ timeout: 15_000 });
      const reviewRow = approverPage.getByRole('row').filter({ hasText: submitted.id });
      await reviewRow.getByRole('button', { name: 'Reject' }).click();

      // Modal opens with the textarea + a disabled Reject confirm.
      await expect(
        approverPage.getByRole('dialog').getByText('Reject query'),
      ).toBeVisible({ timeout: 5_000 });
      const modal = approverPage.getByRole('dialog');
      const modalConfirm = modal.getByRole('button', { name: 'Reject' });
      await expect(modalConfirm).toBeDisabled();

      const rejectComment = 'too risky · production hours';
      await modal
        .getByPlaceholder(/Explain why this query is being rejected/)
        .fill(rejectComment);
      await expect(modalConfirm).toBeEnabled();
      await modalConfirm.click();

      await expect(
        approverPage.getByText('Rejected · submitter notified'),
      ).toBeVisible({ timeout: 10_000 });
      // Card disappears after the pending-list invalidation.
      await expect(
        approverPage.getByText(submitted.id, { exact: true }),
      ).toHaveCount(0, { timeout: 10_000 });

      // Submitter view: reload /queries/:id and assert REJECTED + timeline comment.
      const submitterCtx = await browser.newContext();
      try {
        const submitterPage = await submitterCtx.newPage();
        await loginViaUi(submitterPage, ADMIN_EMAIL, ADMIN_PASSWORD);
        await submitterPage.goto(`/queries/${submitted.id}`);
        await expect(
          submitterPage
            .getByRole('heading', { level: 1 })
            .getByText('Rejected'),
        ).toBeVisible({ timeout: 15_000 });
        // ApprovalTimeline wraps the comment in literal double-quotes.
        await expect(
          submitterPage.getByText(`"${rejectComment}"`),
        ).toBeVisible({ timeout: 10_000 });
      } finally {
        await submitterCtx.close();
      }
    } finally {
      await approverCtx.close();
    }
  });

  // ── 2. Request changes from /queries/:id + submitter sees the banner ──────
  test('request changes keeps PENDING_REVIEW and surfaces a banner to the submitter', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 22',
      'AF-269 request-changes happy path',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );

    const reviewerComment = 'add LIMIT 100';
    const approverCtx = await browser.newContext();
    try {
      const approverPage = await approverCtx.newPage();
      await loginViaUi(approverPage, approverAEmail, APPROVER_PASSWORD);
      await approverPage.goto(`/queries/${submitted.id}`);

      await expect(
        approverPage
          .getByRole('heading', { level: 1 })
          .getByText('Pending review'),
      ).toBeVisible({ timeout: 15_000 });

      const textarea = approverPage.getByPlaceholder(
        /Optional comment for the submitter/,
      );
      await textarea.fill(reviewerComment);
      await approverPage
        .getByRole('button', { name: /Request changes/ })
        .click();

      await expect(
        approverPage.getByText('Requested changes · submitter notified'),
      ).toBeVisible({ timeout: 10_000 });
      // Status pill stays PENDING REVIEW; the decision panel disappears
      // because the latest decision is REQUESTED_CHANGES, but the query is
      // still PENDING_REVIEW for the next round of submitter edits.
      await expect(
        approverPage
          .getByRole('heading', { level: 1 })
          .getByText('Pending review'),
      ).toBeVisible({ timeout: 5_000 });
    } finally {
      await approverCtx.close();
    }

    // Submitter context — reloads /queries/:id and sees the banner.
    const submitterCtx = await browser.newContext();
    try {
      const submitterPage = await submitterCtx.newPage();
      await loginViaUi(submitterPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await submitterPage.goto(`/queries/${submitted.id}`);

      await expect(
        submitterPage.getByText('Changes requested'),
      ).toBeVisible({ timeout: 15_000 });
      await expect(
        submitterPage.getByText(new RegExp(reviewerComment)),
      ).toBeVisible({ timeout: 5_000 });
    } finally {
      await submitterCtx.close();
    }
  });

  // ── 3. Reject button on /queries/:id is disabled when comment is empty ────
  test('reject button is disabled until the reviewer types a comment', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 33',
      'AF-269 reject-disabled',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );

    const approverCtx = await browser.newContext();
    try {
      const approverPage = await approverCtx.newPage();
      await loginViaUi(approverPage, approverAEmail, APPROVER_PASSWORD);
      await approverPage.goto(`/queries/${submitted.id}`);

      // The Reject button in the reviewer decision panel.
      const rejectButton = approverPage.getByRole('button', { name: /Reject/ });
      await expect(rejectButton).toBeVisible({ timeout: 15_000 });
      await expect(rejectButton).toBeDisabled();

      const textarea = approverPage.getByPlaceholder(
        /Optional comment for the submitter/,
      );
      // Whitespace only stays disabled — guards the trim() check.
      await textarea.fill('   ');
      await expect(rejectButton).toBeDisabled();

      await textarea.fill('not safe');
      await expect(rejectButton).toBeEnabled();
    } finally {
      await approverCtx.close();
    }
  });

  // ── 4. Request changes after the query is already approved → 409 toast ────
  //   Approver A opens /queries/:id while PENDING_REVIEW. Approver B (separate
  //   user) approves via API → status is now APPROVED. Approver A types a
  //   comment and clicks Request Changes from the stale page → backend
  //   responds with 409 QUERY_NOT_PENDING_REVIEW; FE maps to
  //   errors.review_query_not_pending.
  test('request-changes against an already-approved query surfaces 409', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 44',
      'AF-269 request-changes stale 409',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );

    const approverCtx = await browser.newContext();
    try {
      const approverPage = await approverCtx.newPage();
      await loginViaUi(approverPage, approverAEmail, APPROVER_PASSWORD);
      await approverPage.goto(`/queries/${submitted.id}`);
      await expect(
        approverPage
          .getByRole('heading', { level: 1 })
          .getByText('Pending review'),
      ).toBeVisible({ timeout: 15_000 });

      // Approver B approves via API — race to make A's page stale.
      await approveQueryViaApi(request, approverBToken, submitted.id);

      const textarea = approverPage.getByPlaceholder(
        /Optional comment for the submitter/,
      );
      await textarea.fill('still want changes');
      await approverPage
        .getByRole('button', { name: /Request changes/ })
        .click();

      await expect(
        approverPage.getByText('This query is no longer pending review.'),
      ).toBeVisible({ timeout: 10_000 });
    } finally {
      await approverCtx.close();
    }

    // Silence unused-var lint for the approverAToken variable; the spec only
    // logs in via UI for context 1, but other contexts may reuse it.
    void approverAToken;
  });
});
