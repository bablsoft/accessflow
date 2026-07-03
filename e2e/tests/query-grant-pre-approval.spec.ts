import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
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

// #582 — grant-covered query auto-approval. A JIT access request (AF-378) can
// opt into pre-approving queries: while the resulting grant is active, a query
// covered by its scope skips human review entirely (PENDING_AI → APPROVED after
// the AI-skipped hop) and the query detail shows the grant provenance. A grant
// WITHOUT the flag keeps the old behaviour — the query lands in PENDING_REVIEW.
//
// Policy-precedence (AUTO_REJECT beats the grant), risk gating, and expiry are
// covered by QueryReviewStateMachine{Test,IntegrationTest} on the backend;
// driving them through the UI would need AI configs or sub-minute grants.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const REQUESTER_PASSWORD = 'Requester-Pwd!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

async function submitAccessRequest(
  page: Page,
  datasourceName: string,
  opts: { preApprove: boolean },
): Promise<void> {
  await page.goto('/access-requests');
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page.locator('.ant-select-item-option').filter({ hasText: datasourceName }).click();
  await page.keyboard.press('Escape');
  if (opts.preApprove) {
    await page.getByText('Pre-approve queries under this grant').click();
  }
  await page.getByPlaceholder('Why do you need this access?').fill('Need temporary read access');
  await page.getByRole('button', { name: 'Submit request' }).click();
  await expect(page.getByText('Access request submitted')).toBeVisible({ timeout: 15_000 });
}

async function approveNewestAccessRequest(page: Page, requesterEmail: string): Promise<void> {
  await page.goto('/admin/access-requests');
  await expect(page.getByText(requesterEmail).first()).toBeVisible({ timeout: 15_000 });
  await page.getByRole('button', { name: 'Approve' }).first().click();
  await expect(page.getByText('Access request approved')).toBeVisible({ timeout: 15_000 });
}

test.describe.configure({ timeout: 120_000 });

test.describe.serial('grant-covered query auto-approval (#582)', () => {
  let preApprovedDs: CreatedDatasource | null = null;
  let plainDs: CreatedDatasource | null = null;
  let reviewPlan: CreatedReviewPlan | null = null;
  let adminToken = '';
  let requesterToken = '';
  const requesterEmail = `af582-requester-${randomUUID()}@e2e.local`;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `AF-582 Plan ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
    });
    preApprovedDs = await createPostgresDatasource(request, adminToken, {
      name: `AF-582 Pre-approved DS ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
    plainDs = await createPostgresDatasource(request, adminToken, {
      name: `AF-582 Plain DS ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminToken, requesterEmail, 'AF-582 Requester', 'ANALYST');
    const token = await waitForInviteToken(request, requesterEmail);
    await acceptInvitationViaApi(request, token, REQUESTER_PASSWORD, 'AF-582 Requester');
    requesterToken = await loginViaApi(request, requesterEmail, REQUESTER_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (preApprovedDs) await deleteDatasource(request, adminToken, preApprovedDs.id);
    if (plainDs) await deleteDatasource(request, adminToken, plainDs.id);
  });

  test('pre-approving grant: covered query executes without reviewer action', async ({
    page,
    request,
  }) => {
    // 1. Requester asks for access with the pre-approve checkbox ticked.
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await submitAccessRequest(page, preApprovedDs!.name, { preApprove: true });

    // 2. The approving admin sees exactly what they authorize — the blue tag.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/access-requests');
    await expect(page.getByText('Pre-approves queries').first()).toBeVisible({ timeout: 15_000 });
    await page.getByRole('button', { name: 'Approve' }).first().click();
    await expect(page.getByText('Access request approved')).toBeVisible({ timeout: 15_000 });

    // 3. A covered SELECT auto-approves with NO reviewer action (the datasource
    //    has no AI config, so the AI-skipped hop runs the grant fast-path).
    const query = await submitQueryViaApi(
      request,
      requesterToken,
      preApprovedDs!.id,
      'SELECT 1',
      'covered by AF-582 grant',
    );
    await waitForQueryStatus(request, requesterToken, query.id, 'APPROVED');

    // 4. It executes end-to-end, still without any reviewer involvement.
    const executed = await executeQueryViaApi(request, requesterToken, query.id);
    expect(executed.status).toBe('EXECUTED');

    // 5. The query detail shows the grant provenance (grant id + approver).
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await page.goto(`/queries/${query.id}`);
    await expect(page.getByText('Auto-approved under an access grant')).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByText(ADMIN_EMAIL, { exact: false }).first()).toBeVisible();
  });

  test('grant without the flag keeps human review', async ({ page, request }) => {
    // Same flow, checkbox left unchecked: the grant only conveys submission
    // rights — the query still routes to PENDING_REVIEW.
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await submitAccessRequest(page, plainDs!.name, { preApprove: false });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await approveNewestAccessRequest(page, requesterEmail);

    const query = await submitQueryViaApi(
      request,
      requesterToken,
      plainDs!.id,
      'SELECT 1',
      'not covered — plain grant',
    );
    await waitForQueryStatus(request, requesterToken, query.id, 'PENDING_REVIEW');

    // Cancel it so the pending row doesn't pollute the shared /reviews queue
    // for later specs (their row locators assume no unrelated pending queries).
    const cancel = await request.post(`${apiBase()}/api/v1/queries/${query.id}/cancel`, {
      headers: { Authorization: `Bearer ${requesterToken}` },
    });
    expect(cancel.ok()).toBe(true);
  });
});
