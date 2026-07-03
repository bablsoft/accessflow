import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
} from '../helpers/datasources';
import {
  createApiConnectorViaApi,
  deleteApiConnectorViaApi,
  getCurrentUserIdViaApi,
  grantApiConnectorPermissionViaApi,
  submitApiRequestViaApi,
  waitForApiRequestStatus,
  type CreatedApiConnector,
} from '../helpers/apiConnectors';

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

test.describe.serial('api request review (#567)', () => {
  let adminAccessToken = '';
  let submitterEmail = '';
  let submitterToken = '';
  let connector: CreatedApiConnector | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    connector = await createApiConnectorViaApi(request, adminAccessToken, {
      name: `E2E API Connector 567 ${Date.now()}`,
    });

    // The submitter is a non-admin ANALYST so it differs from the reviewing
    // bootstrap admin (canDecide requires submitter !== reviewer). It needs an
    // explicit can_write grant to submit against the connector.
    submitterEmail = `fe567-submitter-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminAccessToken, submitterEmail, 'FE-567 Submitter', 'ANALYST');
    const token = await waitForInviteToken(request, submitterEmail);
    await acceptInvitationViaApi(request, token, SUBMITTER_PASSWORD, 'FE-567 Submitter');

    // The invitation response carries the invitation id, not the user id, so
    // resolve the real user id from the submitter's own token before granting.
    submitterToken = await loginViaApi(request, submitterEmail, SUBMITTER_PASSWORD);
    const submitterId = await getCurrentUserIdViaApi(request, submitterToken);
    await grantApiConnectorPermissionViaApi(
      request,
      adminAccessToken,
      connector.id,
      submitterId,
      { canWrite: true },
    );
  });

  test.afterAll(async ({ request }) => {
    if (connector) {
      await deleteApiConnectorViaApi(request, adminAccessToken, connector.id);
    }
  });

  test('admin approves a PENDING_REVIEW API request from the detail page', async ({
    browser,
    request,
  }) => {
    if (!connector) throw new Error('connector not created in beforeAll');

    // Submitter posts a write op via API. With require_review_writes=true (the
    // connector default) and no AI config seeded, it routes straight to
    // PENDING_REVIEW.
    const submitted = await submitApiRequestViaApi(request, submitterToken, {
      connectorId: connector.id,
      verb: 'POST',
      requestPath: '/anything',
      justification: 'FE-567 approve from detail page',
    });
    // Routing is async (AI skip runs on an event), so the immediate submit
    // response may still be PENDING_AI — poll until it settles to PENDING_REVIEW.
    await waitForApiRequestStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');

    const reviewerCtx = await browser.newContext();
    try {
      const reviewerPage = await reviewerCtx.newPage();
      await loginViaUi(reviewerPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await reviewerPage.goto(`/api-requests/${submitted.id}`);

      // The inline reviewer decision block renders Approve/Reject for a
      // PENDING_REVIEW request viewed by a reviewer who isn't the submitter.
      const approveButton = reviewerPage.getByRole('button', { name: 'Approve' });
      await expect(approveButton).toBeVisible({ timeout: 15_000 });
      await expect(reviewerPage.getByRole('button', { name: 'Reject' })).toBeVisible();
      await approveButton.click();

      // Comment modal (shared with ApiReviewQueuePage); comment is optional.
      const dialog = reviewerPage.getByRole('dialog');
      await expect(dialog).toBeVisible();
      await dialog.getByRole('button', { name: 'OK' }).click();

      // Success toast (apiGov.reviews.approved = "Approved"); scope to the
      // message container so it doesn't collide with the refetched status pill.
      await expect(
        reviewerPage.locator('.ant-message').getByText('Approved', { exact: true }),
      ).toBeVisible({ timeout: 10_000 });

      // After the detail refetch the status flips to APPROVED.
      await reviewerPage.reload();
      await expect(reviewerPage.getByText('Approved', { exact: true })).toBeVisible({
        timeout: 15_000,
      });
      await expect(
        reviewerPage.getByRole('button', { name: 'Approve' }),
      ).toHaveCount(0, { timeout: 10_000 });
    } finally {
      await reviewerCtx.close();
    }
  });
});
