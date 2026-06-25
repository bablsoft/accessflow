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

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Two datasources + multiple API round-trips can exceed the default 30s budget.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('query replay in a test environment (AF-449)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let approverAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let source: CreatedDatasource | null = null;
  let target: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // Replay re-submits with the replaying user as the submitter; self-approval is
    // rejected, so the approver must differ. Provision a fresh ADMIN-role approver.
    approverEmail = `replay-approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminAccessToken, approverEmail, 'AF-449 Approver', 'ADMIN');
    const inviteToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(request, inviteToken, APPROVER_PASSWORD, 'AF-449 Approver');
    approverAccessToken = await loginViaApi(request, approverEmail, APPROVER_PASSWORD);

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Replay Plan AF449 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    source = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF449 SRC ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
    target = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF449 TEST ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (source) await deleteDatasource(request, adminAccessToken, source.id);
    if (target) await deleteDatasource(request, adminAccessToken, target.id);
  });

  test('executed query is replayed against a test datasource through the workflow', async ({
    page,
    request,
  }) => {
    if (!source || !target) throw new Error('datasources not created in beforeAll');

    // Drive the original query to EXECUTED via API so a snapshot is written.
    // `SELECT 1` references no tables, so the replay schema-compatibility gate
    // passes against any same-engine target.
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      source.id,
      'SELECT 1',
      'AF-449 original run',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    const executed = await executeQueryViaApi(request, adminAccessToken, submitted.id);
    expect(executed.status).toBe('EXECUTED');

    // Open the executed query's detail page and replay it.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Executed'),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Replay in test environment' }).click();

    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    await dialog.getByRole('combobox').click();
    await page
      .locator('.ant-select-item-option')
      .filter({ hasText: target.name })
      .click();
    await dialog.getByRole('button', { name: 'Replay', exact: true }).click();

    // Success toast + navigation to the NEW query request (distinct id), which
    // entered the normal workflow (not bypassed) — never EXECUTED on arrival.
    await expect(
      page.getByText('Query replayed and submitted for review'),
    ).toBeVisible({ timeout: 15_000 });
    await page.waitForURL(/\/queries\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    const newId = page.url().match(/\/queries\/([0-9a-f-]{36})$/)?.[1];
    expect(newId).toBeTruthy();
    expect(newId).not.toBe(submitted.id);

    // The replayed query is a fresh request awaiting review, proving approval
    // was not skipped.
    await expect(
      page.getByRole('heading', { level: 1 }).getByText(/Pending/),
    ).toBeVisible({ timeout: 15_000 });
  });
});
