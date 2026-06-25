import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  executeQueryViaApi,
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

test.describe.configure({ timeout: 90_000 });

// AF-361 — successful repeated executions of the same SQL link via
// previous_run_id and surface a delta panel on QueryDetailPage.
test.describe.serial('query diff card (AF-361)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let approverAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // Self-approval is forbidden, so we need a second user. Match the pattern
    // in query-execute.spec.ts — random email per run so re-runs don't trip
    // the (email) uniqueness constraint.
    approverEmail = `approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverEmail,
      'AF-361 Approver',
      'ADMIN',
    );
    const inviteToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(
      request,
      inviteToken,
      APPROVER_PASSWORD,
      'AF-361 Approver',
    );
    approverAccessToken = await loginViaApi(
      request,
      approverEmail,
      APPROVER_PASSWORD,
    );

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF361 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF361 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // Submit + approve + execute the same SQL twice and assert the diff card
  // links the second run to the first. Both runs use SELECT 1, 2, 3 so the
  // rows-affected delta is exactly 0 ("no change"); the duration delta is
  // typically non-zero (a few ms either way) and the comparison link should
  // navigate back to the first run.
  test('second run links to first and renders the comparison card', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitFirst = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1, 2, 3',
      'AF-361 first run',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitFirst.id,
      'PENDING_REVIEW',
    );
    await approveQueryViaApi(request, approverAccessToken, submitFirst.id);
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitFirst.id,
      'APPROVED',
    );
    const firstOutcome = await executeQueryViaApi(
      request,
      adminAccessToken,
      submitFirst.id,
    );
    expect(firstOutcome.status).toBe('EXECUTED');

    // Whitespace + casing differ from the first run on purpose — the canonical
    // SQL helper strips comments + collapses whitespace + upper-cases, so the
    // canonical keys still match and the second run links to the first.
    const submitSecond = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      '  select 1, 2,   3  /* same query, reformatted */',
      'AF-361 second run',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitSecond.id,
      'PENDING_REVIEW',
    );
    await approveQueryViaApi(request, approverAccessToken, submitSecond.id);
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitSecond.id,
      'APPROVED',
    );
    const secondOutcome = await executeQueryViaApi(
      request,
      adminAccessToken,
      submitSecond.id,
    );
    expect(secondOutcome.status).toBe('EXECUTED');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitSecond.id}`);
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Executed'),
    ).toBeVisible({ timeout: 15_000 });

    // Card title comes from queries.detail.card_diff.
    const diffCard = page.locator('div', {
      has: page.getByText('Compare to previous run'),
    });
    await expect(diffCard.first()).toBeVisible({ timeout: 15_000 });

    // "View previous run" link navigates to the first run's detail page. The
    // link text comes from queries.detail.diff_previous_link.
    const prevLink = page.getByRole('link', { name: /View previous run/ });
    await expect(prevLink).toBeVisible();
    await prevLink.click();
    await page.waitForURL(new RegExp(`/queries/${submitFirst.id}$`), {
      timeout: 15_000,
    });
  });

  // A fresh SQL with no prior matching run renders the empty state instead
  // of deltas. The detail page still loads cleanly (no error toast).
  test('first run of a unique SQL shows the empty state', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      `SELECT 42 AS unique_marker_${Date.now()}`,
      'AF-361 unique first run',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'APPROVED',
    );
    const outcome = await executeQueryViaApi(
      request,
      adminAccessToken,
      submitted.id,
    );
    expect(outcome.status).toBe('EXECUTED');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Executed'),
    ).toBeVisible({ timeout: 15_000 });

    // Empty state copy comes from queries.detail.diff_empty.
    await expect(
      page.getByText('No previous run found to compare against'),
    ).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByRole('link', { name: /View previous run/ }),
    ).toHaveCount(0);
  });
});
