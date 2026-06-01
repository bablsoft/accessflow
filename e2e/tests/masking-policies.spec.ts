import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createMaskingPolicyViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  executeQueryViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  apiBase,
  type CreatedDatasource,
  type CreatedReviewPlan,
  type InvitedUser,
} from '../helpers/datasources';
import type { APIRequestContext } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

// SELECT a single email so both submitters read the same row; a non-revealed
// requester sees the PARTIAL mask (no '@', since only the last 4 chars survive)
// and a revealed requester sees the full address (contains '@').
const MASKED_SELECT = 'SELECT email FROM users ORDER BY email LIMIT 1';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function provisionAnalyst(
  request: APIRequestContext,
  adminToken: string,
  label: string,
): Promise<{ user: InvitedUser; token: string }> {
  const email = `${label}-${randomUUID()}@e2e.local`;
  await inviteUserViaApi(request, adminToken, email, `Masking ${label}`, 'ANALYST');
  const inviteToken = await waitForInviteToken(request, email);
  await acceptInvitationViaApi(request, inviteToken, ANALYST_PASSWORD, `Masking ${label}`);
  const token = await loginViaApi(request, email, ANALYST_PASSWORD);
  // inviteUserViaApi returns the created user's id; re-fetch via login is token-only,
  // so resolve the id from the invitation response captured below.
  const user = await inviteLookup(request, adminToken, email);
  return { user, token };
}

// The invitation POST returns {id,email,role}; capture it by re-inviting is not
// idempotent, so instead resolve the id from the admin user list.
async function inviteLookup(
  request: APIRequestContext,
  adminToken: string,
  email: string,
): Promise<InvitedUser> {
  const res = await request.get(`${apiBase()}/api/v1/admin/users?size=200`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  if (!res.ok()) throw new Error(`List users failed: ${res.status()}`);
  const body = (await res.json()) as { content: InvitedUser[] };
  const found = body.content.find((u) => u.email === email);
  if (!found) throw new Error(`User ${email} not found after invitation`);
  return found;
}

async function resultsBody(
  request: APIRequestContext,
  token: string,
  queryId: string,
): Promise<string> {
  const res = await request.get(`${apiBase()}/api/v1/queries/${queryId}/results`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) throw new Error(`Fetch results failed: ${res.status()} ${await res.text()}`);
  return res.text();
}

async function runAndFetch(
  request: APIRequestContext,
  submitterToken: string,
  adminToken: string,
  datasourceId: string,
): Promise<string> {
  const submitted = await submitQueryViaApi(
    request,
    submitterToken,
    datasourceId,
    MASKED_SELECT,
    'AF-381 masking enforcement',
  );
  await waitForQueryStatus(request, submitterToken, submitted.id, 'PENDING_REVIEW');
  // Admin approves (admin != the analyst submitter, so self-approval guard passes).
  await approveQueryViaApi(request, adminToken, submitted.id);
  const outcome = await executeQueryViaApi(request, submitterToken, submitted.id);
  expect(outcome.status).toBe('EXECUTED');
  return resultsBody(request, submitterToken, submitted.id);
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('dynamic data masking policies (AF-381)', () => {
  let adminToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;
  let submitterA: { user: InvitedUser; token: string };
  let submitterB: { user: InvitedUser; token: string };

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    await purgeMailcrab(request);

    submitterA = await provisionAnalyst(request, adminToken, 'masked');
    submitterB = await provisionAnalyst(request, adminToken, 'revealed');

    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `E2E Masking Plan ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E Masking ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });

    await grantPermissionViaApi(request, adminToken, datasource.id, submitterA.user.id, {
      canRead: true,
    });
    await grantPermissionViaApi(request, adminToken, datasource.id, submitterB.user.id, {
      canRead: true,
    });

    // PARTIAL mask on the email column; reveal only submitter B by user id.
    await createMaskingPolicyViaApi(request, adminToken, datasource.id, {
      columnRef: 'public.users.email',
      strategy: 'PARTIAL',
      strategyParams: { visible_suffix: '4' },
      revealToUserIds: [submitterB.user.id],
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  // ── 1. Non-revealed submitter sees the masked value ───────────────────────
  test('non-revealed submitter sees the masked email', async ({ request }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const body = await runAndFetch(request, submitterA.token, adminToken, datasource.id);
    // PARTIAL keeps only the last 4 chars, so the '@' and domain are masked away.
    expect(body).not.toContain('@');
    expect(body).toContain('*');
  });

  // ── 2. Revealed submitter sees the unmasked value ─────────────────────────
  test('revealed submitter sees the full email', async ({ request }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const body = await runAndFetch(request, submitterB.token, adminToken, datasource.id);
    expect(body).toContain('@');
  });

  // ── 3. Admin configures a masking policy through the Masking tab ──────────
  test('admin creates a masking policy via the Masking tab UI', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);

    await page.getByRole('tab', { name: /Masking/ }).click();

    // The API-seeded PARTIAL policy is listed.
    await expect(page.getByText('public.users.email').first()).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Add policy' }).click();

    // Live preview is fully client-side: FULL strategy (the default) renders '***'.
    await expect(page.getByText('***', { exact: true })).toBeVisible();

    await page.getByPlaceholder('schema.table.column').fill('public.users.name');
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByText('Masking policy saved')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('public.users.name').first()).toBeVisible({ timeout: 10_000 });
  });
});
