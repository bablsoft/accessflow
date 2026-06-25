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

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Real OS push delivery (a phone receiving a notification) is not reproducible in CI, so this spec
// covers the two parts that are: the PWA install surface (manifest + service worker + the push
// opt-in control), and the one-tap decide landing — driving /reviews/{id}/decide directly, asserting
// the step-up gate commits an approval and that a submitter is blocked from approving their own
// query from the push channel.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('mobile PWA + one-tap push decide (AF-444)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    approverEmail = `af444-approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);

    await inviteUserViaApi(request, adminAccessToken, approverEmail, 'AF-444 Approver', 'ADMIN');
    const token = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(request, token, APPROVER_PASSWORD, 'AF-444 Approver');

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF444 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF444 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('app is installable as a PWA and the review queue offers push opt-in', async ({ page }) => {
    await loginViaUi(page, approverEmail, APPROVER_PASSWORD);

    // Manifest is served and well-formed.
    const manifestRes = await page.request.get('/manifest.webmanifest');
    expect(manifestRes.ok()).toBeTruthy();
    const manifest = (await manifestRes.json()) as { name: string; display: string };
    expect(manifest.name).toBe('AccessFlow');
    expect(manifest.display).toBe('standalone');

    // The offline-shell service worker is served and the document links the manifest. (Live SW
    // activation in headless Chromium is timing-dependent, so we assert the install surface
    // deterministically rather than polling navigator.serviceWorker.)
    const swRes = await page.request.get('/sw.js');
    expect(swRes.ok()).toBeTruthy();
    await expect(page.locator('link[rel="manifest"]')).toHaveCount(1);

    // The push opt-in control is present on the review queue.
    await page.goto('/reviews');
    await expect(page.getByRole('button', { name: 'Enable push approvals' })).toBeVisible({
      timeout: 15_000,
    });
  });

  test('reviewer commits an approval from the decide page after step-up', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1',
      'AF-444 push decide approve',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');

    const approverCtx = await browser.newContext();
    try {
      const approverPage = await approverCtx.newPage();
      await loginViaUi(approverPage, approverEmail, APPROVER_PASSWORD);

      // The notificationclick deep link the service worker opens.
      await approverPage.goto(`/reviews/${submitted.id}/decide?action=approve`);

      // The decide page loads the pending query and renders the step-up password gate.
      const credential = approverPage.getByLabel('Confirm your password');
      await expect(credential).toBeVisible({ timeout: 15_000 });
      await credential.fill(APPROVER_PASSWORD);
      await approverPage.getByRole('button', { name: 'Approve' }).click();

      // Success toast from reviews.decide.approved, then redirect to /reviews.
      await expect(approverPage.getByText('Query approved')).toBeVisible({ timeout: 10_000 });
      await approverPage.waitForURL('**/reviews', { timeout: 10_000 });

      // Server-side state confirms the decision committed.
      await waitForQueryStatus(request, adminAccessToken, submitted.id, 'APPROVED');
    } finally {
      await approverCtx.close();
    }
  });

  test('submitter is blocked from approving their own query on the decide page', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 2',
      'AF-444 push decide self-approval',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');

    // The admin is the submitter here.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/reviews/${submitted.id}/decide?action=approve`);

    // The decide page surfaces the self-approval block and offers no step-up form.
    await expect(page.getByText("You can't review your own query.")).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByLabel('Confirm your password')).toHaveCount(0);
  });
});
