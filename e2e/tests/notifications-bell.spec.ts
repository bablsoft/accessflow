import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  cancelQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  findUserByEmailViaApi,
  grantPermissionViaApi,
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
const SUBMITTER_PASSWORD = 'Submitter-Pwd!123';

// loginViaUi mirrors the file-local helper in ws-realtime.spec.ts — no shared
// module exists yet, so duplicating matches the current convention.
async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Provisioning the submitter via Mailcrab (invite → wait → accept) plus seeding
// queries costs a few seconds; give the same headroom ws-realtime.spec.ts uses.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('notification bell — delete all (#611)', () => {
  let adminAccessToken = '';
  let submitterEmail = '';
  let submitterAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;
  const seededQueryIds: string[] = [];

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The bell under test is the *admin's*. QUERY_SUBMITTED notifications are
    // reviewer-targeted, so a separate submitter is what puts rows in the
    // admin's inbox — the admin submitting their own query would not.
    submitterEmail = `notif-bell-submitter-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(
      request,
      adminAccessToken,
      submitterEmail,
      'AF-611 Bell Submitter',
      'ANALYST',
    );
    const token = await waitForInviteToken(request, submitterEmail);
    await acceptInvitationViaApi(
      request,
      token,
      SUBMITTER_PASSWORD,
      'AF-611 Bell Submitter',
    );
    submitterAccessToken = await loginViaApi(request, submitterEmail, SUBMITTER_PASSWORD);

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF611 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF611 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });

    // A non-admin submitter cannot see the datasource without an explicit
    // grant — submitting would 404 rather than notify the reviewers.
    const submitter = await findUserByEmailViaApi(request, adminAccessToken, submitterEmail);
    await grantPermissionViaApi(
      request,
      adminAccessToken,
      datasource.id,
      submitter.id,
      { canRead: true },
    );
  });

  test.afterAll(async ({ request }) => {
    // Leave the shared review queue as we found it. The seeded queries sit in
    // PENDING_REVIEW forever otherwise, and specs that approve a row on
    // /reviews (query-execute) match every Approve button on the page — extra
    // pending rows there turn their locator into a strict-mode violation.
    for (const id of seededQueryIds) {
      await cancelQueryViaApi(request, submitterAccessToken, id);
    }
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('admin clears a populated inbox in one action and sees the empty state', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // Two queries → two reviewer notifications, so "delete all" is doing
    // strictly more than the single-row delete already covered by unit tests.
    for (const sql of ['SELECT 1', 'SELECT 2']) {
      const submitted = await submitQueryViaApi(
        request,
        submitterAccessToken,
        datasource.id,
        sql,
        'AF-611 bell seed',
      );
      await waitForQueryStatus(
        request,
        submitterAccessToken,
        submitted.id,
        'PENDING_REVIEW',
      );
      seededQueryIds.push(submitted.id);
    }

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    const bell = page.getByLabel('Notifications');
    // The badge is the unread count; it must be non-zero before we clear.
    await expect(page.locator('.ant-badge-count')).toBeVisible({ timeout: 15_000 });

    await bell.click();
    await expect(page.getByText(/AF-611 Bell Submitter|submitted a query/).first()).toBeVisible({
      timeout: 15_000,
    });

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
