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
  waitForInviteToken,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';

// AF-378 — just-in-time time-bound access requests. End-to-end happy path:
// an analyst submits a scoped access request → an admin approves it in the
// queue → the grant materialises (the requester's row flips to APPROVED and
// shows a remaining-TTL chip) → the requester can cancel a still-pending one.
//
// The expiry→revoke half of the lifecycle (AccessGrantExpiryJob) is exercised
// by the backend AccessGrantLifecycleIntegrationTest (Testcontainers); driving
// it through the UI would need a sub-minute grant + fast poll and would be flaky.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const REQUESTER_PASSWORD = 'Requester-Pwd!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function submitAccessRequest(page: Page, datasourceName: string): Promise<void> {
  await page.goto('/access-requests');
  // Datasource Select is the first combobox in the request form.
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page.locator('.ant-select-item-option').filter({ hasText: datasourceName }).click();
  await page.keyboard.press('Escape');
  // Read capability + 4-hour duration are the form defaults; only justification is required.
  await page.getByPlaceholder('Why do you need this access?').fill('Need temporary read access');
  await page.getByRole('button', { name: 'Submit request' }).click();
  await expect(page.getByText('Access request submitted')).toBeVisible({ timeout: 15_000 });
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('access requests (AF-378)', () => {
  let datasource: CreatedDatasource | null = null;
  let reviewPlan: CreatedReviewPlan | null = null;
  let adminToken = '';
  const requesterEmail = `af378-requester-${randomUUID()}@e2e.local`;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // One-stage ADMIN review plan so the seeded e2e admin can approve.
    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `AF-378 Plan ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
    });
    datasource = await createPostgresDatasource(request, adminToken, {
      name: `AF-378 DS ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
    // Requester is a fresh ANALYST — distinct from the admin approver so the
    // self-approval block never trips, and ANALYST cannot reach the queue.
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminToken, requesterEmail, 'AF-378 Requester', 'ANALYST');
    const token = await waitForInviteToken(request, requesterEmail);
    await acceptInvitationViaApi(request, token, REQUESTER_PASSWORD, 'AF-378 Requester');
  });

  test.afterAll(async ({ request }) => {
    if (datasource) await deleteDatasource(request, adminToken, datasource.id);
  });

  test('requester submits, admin approves, grant materialises with a TTL', async ({ page }) => {
    const dsName = datasource!.name;

    // 1. Requester submits a scoped access request.
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await submitAccessRequest(page, dsName);
    // "My requests" shows the new PENDING row.
    await expect(page.getByText('Pending', { exact: true })).toBeVisible({ timeout: 15_000 });

    // 2. Admin approves it in the access-request queue.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/access-requests');
    await expect(page.getByText(requesterEmail)).toBeVisible({ timeout: 15_000 });
    await page.getByRole('button', { name: 'Approve' }).first().click();
    await expect(page.getByText('Access request approved')).toBeVisible({ timeout: 15_000 });

    // 3. Back as the requester: the grant is materialised — APPROVED + a TTL chip.
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await page.goto('/access-requests');
    await expect(page.getByText('Approved', { exact: true })).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/left$/)).toBeVisible({ timeout: 15_000 });
  });

  test('schema dropdown populates from the selected datasource (AF-389)', async ({ page }) => {
    // A non-admin requester holds no permission grant on the datasource, yet must be able to
    // introspect schema/table names to scope the request — served by the JIT-scoped endpoint
    // GET /access-requests/datasources/{id}/schema (not the permission-gated datasource schema).
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await page.goto('/access-requests');

    // Select the datasource (first combobox in the form).
    const dsSelect = page.getByRole('combobox').first();
    await dsSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: datasource!.name }).click();
    await page.keyboard.press('Escape');

    // Open the Schemas dropdown (second combobox) — the seeded datasource points at the
    // control-plane Postgres, whose `public` schema is always present.
    await page.getByRole('combobox').nth(1).click();
    await expect(
      page.locator('.ant-select-item-option').filter({ hasText: 'public' }),
    ).toBeVisible({ timeout: 15_000 });
  });

  test('requester can cancel a pending request', async ({ page }) => {
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await submitAccessRequest(page, datasource!.name);

    // Cancel the just-submitted PENDING request (row action opens a Popconfirm).
    await page.getByRole('button', { name: 'Cancel' }).first().click();
    // Confirm in the Popconfirm — the OK button is the primary one.
    await page.locator('.ant-popconfirm-buttons .ant-btn-primary').click();
    await expect(page.getByText('Access request cancelled')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Cancelled', { exact: true }).first()).toBeVisible({ timeout: 15_000 });
  });
});
