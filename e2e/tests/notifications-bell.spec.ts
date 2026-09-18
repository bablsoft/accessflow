import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  cancelQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  createRoleViaApi,
  deleteDatasource,
  deleteRoleViaApi,
  findUserByEmailViaApi,
  inviteUserViaApi,
  loginViaApi,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
  type RoleSummary,
} from '../helpers/datasources';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const REVIEWER_PASSWORD = 'Reviewer-Pwd!123';

// Provisioning the reviewer via Mailcrab (invite → wait → accept) plus seeding
// queries costs a few seconds; give the same headroom ws-realtime.spec.ts uses.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('notification bell — delete all (#611)', () => {
  let adminAccessToken = '';
  let reviewerEmail = '';
  let reviewerRole: RoleSummary | null = null;
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;
  const seededQueryIds: string[] = [];

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The bell under test belongs to a reviewer this spec owns outright, not to
    // the seeded admin. The spec asserts the inbox is EMPTY after "delete all",
    // and under the parallel project the admin's inbox never is: every
    // concurrent spec's role-ADMIN plan notifies it on submission, and the
    // AI_HIGH_RISK / escalation / break-glass / anomaly events fan out to every
    // org admin. A system REVIEWER would not do either — API_REQUEST_SUBMITTED
    // and plan-less deployment submissions notify every REVIEWER + ADMIN in
    // the org. So the reviewer holds a run-unique CUSTOM role with QUERY_REVIEW
    // (no role-scoped fan-out can name it) and is the plan's only approver by
    // user id: the two queries seeded below are the only things that can ever
    // land in this inbox.
    const suffix = randomUUID();
    reviewerRole = await createRoleViaApi(
      request,
      adminAccessToken,
      `AF-611 bell reviewer ${suffix}`,
      ['QUERY_REVIEW'],
      'notifications-bell.spec.ts — isolated inbox owner',
    );
    reviewerEmail = `notif-bell-reviewer-${suffix}@e2e.local`;
    await inviteUserViaApi(
      request,
      adminAccessToken,
      reviewerEmail,
      'AF-611 Bell Reviewer',
      null,
      reviewerRole.id,
    );
    const token = await waitForInviteToken(request, reviewerEmail);
    await acceptInvitationViaApi(request, token, REVIEWER_PASSWORD, 'AF-611 Bell Reviewer');
    const reviewer = await findUserByEmailViaApi(request, adminAccessToken, reviewerEmail);

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF611 ${Date.now()}`,
      approvers: [{ userId: reviewer.id, stage: 1 }],
      minApprovalsRequired: 1,
    });

    // The admin submits: QUERY_SUBMITTED is reviewer-targeted and excludes the
    // submitter, so the admin's own queries are exactly what fills the
    // reviewer's inbox. ADMINs have implicit datasource access — no grant.
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF611 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    // Leave the shared review queue as we found it. The seeded queries sit in
    // PENDING_REVIEW forever otherwise, and specs that approve a row on
    // /reviews (query-execute) match every Approve button on the page — extra
    // pending rows there turn their locator into a strict-mode violation.
    for (const id of seededQueryIds) {
      await cancelQueryViaApi(request, adminAccessToken, id);
    }
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
    // Best-effort: the role stays assigned to the reviewer (users are never
    // hard-deleted), so this 409s and the helper only logs it.
    if (reviewerRole) {
      await deleteRoleViaApi(request, adminAccessToken, reviewerRole.id);
    }
  });

  test('reviewer clears a populated inbox in one action and sees the empty state', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // Two queries → two reviewer notifications, so "delete all" is doing
    // strictly more than the single-row delete already covered by unit tests.
    for (const sql of ['SELECT 1', 'SELECT 2']) {
      const submitted = await submitQueryViaApi(
        request,
        adminAccessToken,
        datasource.id,
        sql,
        'AF-611 bell seed',
      );
      await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
      seededQueryIds.push(submitted.id);
    }

    await login(page, reviewerEmail, REVIEWER_PASSWORD);

    const bell = page.getByLabel('Notifications');
    // The badge is the unread count. The inbox is isolated (see beforeAll), so
    // it is exactly the two seeds — not merely "non-zero".
    await expect(page.locator('.ant-badge-count')).toHaveText('2', { timeout: 15_000 });

    await bell.click();
    const rows = page.locator('.af-notif-row');
    await expect(rows).toHaveCount(2, { timeout: 15_000 });
    await expect(rows.filter({ hasText: /E2E Admin submitted a query/ })).toHaveCount(2);

    // Trigger opens the Popconfirm; the mutation must not fire until confirmed.
    await page.getByRole('button', { name: 'Delete all' }).click();
    const popconfirm = page.locator('.ant-popconfirm');
    await expect(popconfirm).toBeVisible();
    await expect(
      popconfirm.getByText('Delete all notifications? This cannot be undone.'),
    ).toBeVisible();

    const deleteAllRequest = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        /\/api\/v1\/notifications$/.test(new URL(r.url()).pathname) &&
        r.status() === 204,
    );
    await popconfirm.getByRole('button', { name: 'Delete all' }).click();
    await deleteAllRequest;

    // Acceptance criteria: empty state + badge gone, with no manual refresh.
    await expect(page.getByText('No notifications yet.')).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.ant-badge-count')).toBeHidden();
    await expect(page.getByRole('button', { name: 'Delete all' })).toBeHidden();

    // The clear is durable, not just an optimistic cache edit.
    await page.reload();
    await page.getByLabel('Notifications').click();
    await expect(page.getByText('No notifications yet.')).toBeVisible({ timeout: 15_000 });
  });
});
