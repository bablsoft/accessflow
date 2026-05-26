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

// CodeMirror's contenteditable doesn't accept .fill(). Mirrors the helper in
// query-execute.spec.ts; the editor onboarding banner reflows after the schema
// fetch resolves and drops keystrokes if we type too early, so wait for the
// network to be idle first.
async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.type(sql, { delay: 20 });
  await page.keyboard.press('Escape');
}

async function pickDatasource(page: Page, ds: CreatedDatasource): Promise<void> {
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page
    .locator('.ant-select-item-option')
    .filter({ hasText: ds.name })
    .click();
  await page.keyboard.press('Escape');
}

async function submitViaEditor(
  page: Page,
  ds: CreatedDatasource,
  sql: string,
  justification: string,
): Promise<string> {
  await page.goto('/editor');
  await pickDatasource(page, ds);
  await page.waitForLoadState('networkidle');
  await typeInEditor(page, sql);
  await page
    .getByPlaceholder('Why are you running this query?')
    .fill(justification);
  await page.getByRole('button', { name: 'Submit for review' }).click();
  await page.waitForURL(/\/queries\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  const match = page.url().match(/\/queries\/([0-9a-f-]{36})$/);
  if (!match) throw new Error(`Could not parse query id from ${page.url()}`);
  return match[1];
}

// Provisioning a fresh approver (via Mailcrab → invitation accept) takes 1–2s
// per user, and the 409 test depends on two distinct approvers. Bump the
// per-test budget so the slow startup doesn't push the happy path past 30s.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('reviews approve (AF-268)', () => {
  let adminAccessToken = '';
  let approverAEmail = '';
  let approverAPassword = APPROVER_PASSWORD;
  let approverBEmail = '';
  let approverBPassword = APPROVER_PASSWORD;
  let approverBAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The 409 test (test 2) requires two reviewers in addition to the
    // submitter: approver A races approver B's stale-cache click. Both are
    // ADMIN-role so the seeded one-stage ADMIN review plan accepts either.
    approverAEmail = `af268-approver-a-${randomUUID()}@e2e.local`;
    approverBEmail = `af268-approver-b-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);

    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverAEmail,
      'AF-268 Approver A',
      'ADMIN',
    );
    const tokenA = await waitForInviteToken(request, approverAEmail);
    await acceptInvitationViaApi(
      request,
      tokenA,
      approverAPassword,
      'AF-268 Approver A',
    );

    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverBEmail,
      'AF-268 Approver B',
      'ADMIN',
    );
    const tokenB = await waitForInviteToken(request, approverBEmail);
    await acceptInvitationViaApi(
      request,
      tokenB,
      approverBPassword,
      'AF-268 Approver B',
    );
    approverBAccessToken = await loginViaApi(
      request,
      approverBEmail,
      approverBPassword,
    );

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF268 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF268 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Happy path: submitter submits → approver approves from /reviews ────
  test('reviewer approves a query from /reviews and submitter sees APPROVED', async ({
    browser,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitterCtx = await browser.newContext();
    const approverCtx = await browser.newContext();
    try {
      const submitterPage = await submitterCtx.newPage();
      const approverPage = await approverCtx.newPage();

      await loginViaUi(submitterPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      const queryId = await submitViaEditor(
        submitterPage,
        datasource,
        'SELECT 1',
        'AF-268 happy-path approve',
      );

      await expect(
        submitterPage
          .getByRole('heading', { level: 1 })
          .getByText('Pending review'),
      ).toBeVisible({ timeout: 15_000 });

      await loginViaUi(approverPage, approverAEmail, approverAPassword);
      await approverPage.goto('/reviews');

      // The /reviews table renders the full UUID inside the row so prior
      // specs that leave queries in PENDING_REVIEW (e.g. cancel spec) don't
      // confuse the locator.
      await expect(
        approverPage.getByText(queryId, { exact: true }),
      ).toBeVisible({ timeout: 15_000 });
      const reviewRow = approverPage.getByRole('row').filter({ hasText: queryId });
      await reviewRow.getByRole('button', { name: 'Approve' }).click();

      // Toast from reviews.on_approve in en.json.
      await expect(
        approverPage.getByText('Approved · forwarded to execution'),
      ).toBeVisible({ timeout: 10_000 });

      // Row disappears once the pending-list invalidation refetches without
      // this row. The full-UUID match keeps this assertion specific.
      await expect(
        approverPage.getByText(queryId, { exact: true }),
      ).toHaveCount(0, { timeout: 10_000 });

      // Submitter sees APPROVED after refetching. Default staleTime keeps the
      // cached PENDING REVIEW for ~30s, so trigger a refetch via reload.
      await submitterPage.reload();
      await expect(
        submitterPage.getByRole('heading', { level: 1 }).getByText('Approved'),
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await submitterCtx.close();
      await approverCtx.close();
    }
  });

  // ── 2. Stale-cache 409: approver A clicks Approve on a query approver B ───
  //    already approved via API. Backend returns 409 QUERY_NOT_PENDING_REVIEW;
  //    FE maps it to errors.review_query_not_pending = "This query is no
  //    longer pending review." The current ReviewQueuePage only invalidates
  //    onSuccess, so the stale card stays until the user clicks Refresh —
  //    asserted here to lock current behaviour. A future PR may change this
  //    to onSettled; the spec will need to relax that step then.
  test('approving a query that was already approved surfaces 409 error toast', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 2',
      'AF-268 stale 409',
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
      await loginViaUi(approverPage, approverAEmail, approverAPassword);
      await approverPage.goto('/reviews');
      await expect(
        approverPage.getByText(submitted.id, { exact: true }),
      ).toBeVisible({ timeout: 15_000 });

      // Race: approver B (separate user, separate token) approves via API
      // before approver A's click. The query is now APPROVED server-side, but
      // approver A's cached card is unaware.
      await approveQueryViaApi(request, approverBAccessToken, submitted.id);

      const reviewRow = approverPage.getByRole('row').filter({ hasText: submitted.id });
      await reviewRow.getByRole('button', { name: 'Approve' }).click();

      await expect(
        approverPage.getByText('This query is no longer pending review.'),
      ).toBeVisible({ timeout: 10_000 });

      // Click Refresh to invalidate the stale card. After the refetch the
      // server omits the now-APPROVED query and the card disappears.
      await approverPage.getByRole('button', { name: 'Refresh' }).click();
      await expect(
        approverPage.getByText(submitted.id, { exact: true }),
      ).toHaveCount(0, { timeout: 10_000 });
    } finally {
      await approverCtx.close();
    }
  });

  // ── 3. Empty state once the queue is drained ──────────────────────────────
  //    The two prior tests resolved every PENDING_REVIEW query that this
  //    spec submitted. Other specs in the same `npm test` run could leave
  //    their own pending queries behind, so we don't depend on a globally
  //    empty queue; instead we assert from approver B's perspective. B has
  //    only ever approved (no own submissions), so any leftover pending
  //    queries from other specs were submitted by the bootstrap admin and
  //    will appear in B's queue — meaning we can't blindly assert "All
  //    caught up". Instead, drain B's view by approving everything they see
  //    via API, then assert the empty state renders.
  test('approver sees empty state once their queue is drained', async ({
    browser,
    request,
  }) => {
    // Drain any leftover PENDING_REVIEW queries visible to approver B via
    // the API. This is best-effort: queries B submitted themselves can't be
    // approved (self-approval is 403), but B has not submitted any in this
    // spec.
    const drainRes = await request.get(
      `${process.env.E2E_API_BASE ?? 'http://localhost:8080'}/api/v1/reviews/pending?size=100`,
      {
        headers: { Authorization: `Bearer ${approverBAccessToken}` },
      },
    );
    if (drainRes.ok()) {
      const page = (await drainRes.json()) as {
        content: Array<{ id: string }>;
      };
      for (const item of page.content) {
        try {
          await approveQueryViaApi(request, approverBAccessToken, item.id);
        } catch {
          // Skip items B can't approve (e.g. own submissions from a hypothetical
          // future spec). The UI assertion below will surface a real failure.
        }
      }
    }

    const approverCtx = await browser.newContext();
    try {
      const approverPage = await approverCtx.newPage();
      await loginViaUi(approverPage, approverBEmail, approverBPassword);
      await approverPage.goto('/reviews');
      // EmptyState title from reviews.empty_title.
      await expect(approverPage.getByText('All caught up')).toBeVisible({
        timeout: 15_000,
      });
    } finally {
      await approverCtx.close();
    }
  });
});
