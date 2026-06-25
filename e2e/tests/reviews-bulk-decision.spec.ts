import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
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
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Two-user setup + table interactions, give the suite a generous budget.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('reviews bulk decide (AF-346)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    approverEmail = `af346-approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);

    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverEmail,
      'AF-346 Approver',
      'ADMIN',
    );
    const token = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(
      request,
      token,
      APPROVER_PASSWORD,
      'AF-346 Approver',
    );

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF346 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF346 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('reviewer bulk-approves multiple queries; self-submitted row lands on FORBIDDEN', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // Three submitter-owned queries the approver can decide on.
    const submitted: { id: string }[] = [];
    for (let i = 1; i <= 3; i += 1) {
      const q = await submitQueryViaApi(
        request,
        adminAccessToken,
        datasource.id,
        `SELECT ${i}`,
        `AF-346 bulk #${i}`,
      );
      await waitForQueryStatus(
        request,
        adminAccessToken,
        q.id,
        'PENDING_REVIEW',
      );
      submitted.push({ id: q.id });
    }

    // One query submitted BY the approver themselves so the self-approval
    // guard kicks in for that single row — expected to land on FORBIDDEN
    // while the other three SUCCEED.
    const approverAccessToken = await loginViaApi(
      request,
      approverEmail,
      APPROVER_PASSWORD,
    );
    const ownQuery = await submitQueryViaApi(
      request,
      approverAccessToken,
      datasource.id,
      'SELECT 99',
      'AF-346 self-submitted',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      ownQuery.id,
      'PENDING_REVIEW',
    );

    const approverCtx = await browser.newContext();
    try {
      const approverPage = await approverCtx.newPage();
      await loginViaUi(approverPage, approverEmail, APPROVER_PASSWORD);
      await approverPage.goto('/reviews');

      // Each submitted query renders as a row with the full UUID visible.
      for (const { id } of submitted) {
        await expect(
          approverPage.getByText(id, { exact: true }),
        ).toBeVisible({ timeout: 15_000 });
      }

      // Defense-in-depth filter: the approver's own query is NOT in the queue
      // (ReviewQueuePage filters submitted_by === user.id), so it cannot be
      // selected from the UI. We verify the per-row FORBIDDEN behaviour
      // separately by calling the bulk endpoint directly.
      await expect(
        approverPage.getByText(ownQuery.id, { exact: true }),
      ).toHaveCount(0);

      // Select all three submitted queries via the header select-all checkbox.
      // The header checkbox is the first checkbox in the table.
      const checkboxes = approverPage.getByRole('checkbox');
      await checkboxes.first().check();

      // Sticky bar shows the count and Approve selected.
      await expect(
        approverPage.getByText(/3 selected/),
      ).toBeVisible({ timeout: 5_000 });

      await approverPage
        .getByRole('button', { name: 'Approve selected' })
        .first()
        .click();

      // Modal opens. APPROVE doesn't require a comment — click confirm.
      const modal = approverPage.getByRole('dialog');
      await expect(
        modal.getByText('Approve selected queries'),
      ).toBeVisible({ timeout: 5_000 });
      await modal.getByRole('button', { name: 'Approve selected' }).click();

      // Toast summarises 3 successes, 0 failures.
      await expect(
        approverPage.getByText(/3 decided · 0 failed/),
      ).toBeVisible({ timeout: 10_000 });

      // All three rows leave the queue after invalidation.
      for (const { id } of submitted) {
        await expect(
          approverPage.getByText(id, { exact: true }),
        ).toHaveCount(0, { timeout: 10_000 });
      }
    } finally {
      await approverCtx.close();
    }

    // Backend semantics: posting the approver's OWN query through the bulk
    // endpoint must land on FORBIDDEN without breaking the batch. We verify
    // that contract directly against the API.
    const stillPending = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 100',
      'AF-346 mixed bulk row',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      stillPending.id,
      'PENDING_REVIEW',
    );

    const apiBase = process.env.E2E_API_BASE ?? 'http://localhost:8080';
    const res = await request.post(`${apiBase}/api/v1/reviews/bulk`, {
      headers: { Authorization: `Bearer ${approverAccessToken}` },
      data: {
        query_ids: [ownQuery.id, stillPending.id],
        decision: 'APPROVED',
      },
    });
    expect(res.status()).toBe(200);
    const body = (await res.json()) as {
      results: Array<{
        query_request_id: string;
        status: string;
        error_code?: string;
      }>;
    };
    expect(body.results).toHaveLength(2);
    const ownRow = body.results.find((r) => r.query_request_id === ownQuery.id);
    const okRow = body.results.find(
      (r) => r.query_request_id === stillPending.id,
    );
    expect(ownRow?.status).toBe('FORBIDDEN');
    expect(okRow?.status).toBe('SUCCESS');
  });

  test('bulk reject requires a comment in the modal', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 77',
      'AF-346 bulk reject comment guard',
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
      await loginViaUi(approverPage, approverEmail, APPROVER_PASSWORD);
      await approverPage.goto('/reviews');
      await expect(
        approverPage.getByText(submitted.id, { exact: true }),
      ).toBeVisible({ timeout: 15_000 });

      // Select-all picks up the single row.
      await approverPage.getByRole('checkbox').first().check();
      await expect(approverPage.getByText(/1 selected/)).toBeVisible();

      await approverPage
        .getByRole('button', { name: 'Reject selected' })
        .first()
        .click();

      const modal = approverPage.getByRole('dialog');
      await expect(
        modal.getByText('Reject selected queries'),
      ).toBeVisible({ timeout: 5_000 });

      const confirm = modal.getByRole('button', { name: 'Reject selected' });
      await expect(confirm).toBeDisabled();

      await modal
        .getByPlaceholder(/Comment that will apply/)
        .fill('out of business hours');
      await expect(confirm).toBeEnabled();

      await confirm.click();

      await expect(
        approverPage.getByText(/1 decided · 0 failed/),
      ).toBeVisible({ timeout: 10_000 });
      await expect(
        approverPage.getByText(submitted.id, { exact: true }),
      ).toHaveCount(0, { timeout: 10_000 });
    } finally {
      await approverCtx.close();
    }
  });
});
