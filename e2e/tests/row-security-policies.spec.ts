import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  createRowSecurityPolicyViaApi,
  deleteDatasource,
  executeQueryViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  setUserAttributesViaApi,
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

// SELECT every email from AccessFlow's own users table. The row-security policy
// filters this to `email = :user.email_filter`, so a scoped analyst only sees the
// single row matching the email stored in their admin-set attribute.
const SELECT_EMAILS = 'SELECT email FROM users ORDER BY email';

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
  await inviteUserViaApi(request, adminToken, email, `RowSec ${label}`, 'ANALYST');
  const inviteToken = await waitForInviteToken(request, email);
  await acceptInvitationViaApi(request, inviteToken, ANALYST_PASSWORD, `RowSec ${label}`);
  const token = await loginViaApi(request, email, ANALYST_PASSWORD);
  const user = await userLookup(request, adminToken, email);
  return { user, token };
}

async function userLookup(
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
    SELECT_EMAILS,
    'AF-380 row security enforcement',
  );
  await waitForQueryStatus(request, submitterToken, submitted.id, 'PENDING_REVIEW');
  await approveQueryViaApi(request, adminToken, submitted.id);
  const outcome = await executeQueryViaApi(request, submitterToken, submitted.id);
  expect(outcome.status).toBe('EXECUTED');
  const res = await request.get(`${apiBase()}/api/v1/queries/${submitted.id}/results`, {
    headers: { Authorization: `Bearer ${submitterToken}` },
  });
  if (!res.ok()) throw new Error(`Fetch results failed: ${res.status()} ${await res.text()}`);
  return res.text();
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('row-level security policies (AF-380)', () => {
  let adminToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;
  let scopedAnalyst: { user: InvitedUser; token: string };
  let unscopedAnalyst: { user: InvitedUser; token: string };

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    await purgeMailcrab(request);

    scopedAnalyst = await provisionAnalyst(request, adminToken, 'scoped');
    unscopedAnalyst = await provisionAnalyst(request, adminToken, 'unscoped');

    // Only the scoped analyst gets an email_filter attribute; the unscoped analyst
    // has none, so the :user.email_filter variable resolves to nothing → fail-closed.
    await setUserAttributesViaApi(request, adminToken, scopedAnalyst.user.id, {
      email_filter: scopedAnalyst.user.email,
    });

    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `E2E RowSec Plan ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E RowSec ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });

    await grantPermissionViaApi(request, adminToken, datasource.id, scopedAnalyst.user.id, {
      canRead: true,
    });
    await grantPermissionViaApi(request, adminToken, datasource.id, unscopedAnalyst.user.id, {
      canRead: true,
    });

    // Filter the users table to rows whose email equals the submitter's email_filter
    // attribute. Applies to all ANALYSTs.
    await createRowSecurityPolicyViaApi(request, adminToken, datasource.id, {
      tableName: 'users',
      columnName: 'email',
      operator: 'EQUALS',
      valueType: 'VARIABLE',
      valueExpression: ':user.email_filter',
      appliesToRoles: ['ANALYST'],
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  // ── 1. Scoped analyst only sees the rows the predicate authorises ──────────
  test('scoped analyst sees only their own row', async ({ request }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const body = await runAndFetch(request, scopedAnalyst.token, adminToken, datasource.id);
    // Only the analyst's own email passes the predicate; the admin's does not.
    expect(body).toContain(scopedAnalyst.user.email);
    expect(body).not.toContain(ADMIN_EMAIL);
    expect(body).not.toContain(unscopedAnalyst.user.email);
  });

  // ── 2. Fail-closed: an unresolvable variable yields zero rows ──────────────
  test('analyst without the attribute sees no rows (fail-closed)', async ({ request }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const body = await runAndFetch(request, unscopedAnalyst.token, adminToken, datasource.id);
    // The :user.email_filter variable resolves to nothing → always-false predicate.
    expect(body).not.toContain('@');
  });

  // ── 3. Admin configures a policy through the Row security tab ──────────────
  test('admin creates a row-security policy via the Row security tab UI', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);

    await page.getByRole('tab', { name: /Row security/ }).click();

    // The API-seeded policy is listed (predicate shows the variable name).
    await expect(page.getByText(/user\.email_filter/).first()).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Add policy' }).click();
    const dialog = page.getByRole('dialog');

    // AntD Form assigns each field an id matching its `name`.
    await dialog.locator('#table_name').fill('public.demo');
    await dialog.locator('#column_name').fill('tenant_id');
    await dialog.locator('#value_expression').fill(':user.region');
    // Blur the AutoComplete so its option dropdown can't intercept the Save click.
    await dialog.getByText('Add row security policy').click();
    await dialog.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByText('Row security policy saved')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('public.demo').first()).toBeVisible({ timeout: 10_000 });
  });
});
