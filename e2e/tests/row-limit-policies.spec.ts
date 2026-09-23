import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  createRowLimitPolicyViaApi,
  deleteDatasource,
  executeQueryViaApi,
  findUserByEmailViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  apiBase,
  type CreatedDatasource,
  type CreatedReviewPlan,
  type InvitedUser,
} from '../helpers/datasources';
import type { APIRequestContext } from '@playwright/test';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

// The e2e datasource points at AccessFlow's own database: `users` holds at least the admin
// plus the two analysts provisioned below, and `role_permissions` holds one row per ADMIN
// permission, so both tables comfortably exceed every cap used here.
const SELECT_USERS = 'SELECT email FROM users ORDER BY email';
const SELECT_ROLE_PERMISSIONS = 'SELECT permission FROM role_permissions ORDER BY permission';
const SELECT_JOIN =
  'SELECT u.email, rp.permission FROM users u CROSS JOIN role_permissions rp ORDER BY 1, 2';

interface ResultPage {
  row_count: number;
  truncated: boolean;
  truncated_reason: string | null;
}

async function provisionAnalyst(
  request: APIRequestContext,
  adminToken: string,
  label: string,
): Promise<{ user: InvitedUser; token: string }> {
  const email = `${label}-${randomUUID()}@e2e.local`;
  await inviteUserViaApi(request, adminToken, email, `RowLimit ${label}`, 'ANALYST');
  const inviteToken = await waitForInviteToken(request, email);
  await acceptInvitationViaApi(request, inviteToken, ANALYST_PASSWORD, `RowLimit ${label}`);
  const token = await loginViaApi(request, email, ANALYST_PASSWORD);
  const user = await findUserByEmailViaApi(request, adminToken, email);
  return { user, token };
}

async function runAndFetch(
  request: APIRequestContext,
  submitterToken: string,
  adminToken: string,
  datasourceId: string,
  sql: string,
): Promise<ResultPage> {
  const submitted = await submitQueryViaApi(
    request,
    submitterToken,
    datasourceId,
    sql,
    '#934 per-table row limit enforcement',
  );
  await waitForQueryStatus(request, submitterToken, submitted.id, 'PENDING_REVIEW');
  await approveQueryViaApi(request, adminToken, submitted.id);
  const outcome = await executeQueryViaApi(request, submitterToken, submitted.id);
  expect(outcome.status).toBe('EXECUTED');
  const res = await request.get(`${apiBase()}/api/v1/queries/${submitted.id}/results`, {
    headers: { Authorization: `Bearer ${submitterToken}` },
  });
  if (!res.ok()) throw new Error(`Fetch results failed: ${res.status()} ${await res.text()}`);
  return (await res.json()) as ResultPage;
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('per-table row-limit policies (#934)', () => {
  let adminToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;
  let analystOne: { user: InvitedUser; token: string };
  let analystTwo: { user: InvitedUser; token: string };

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    analystOne = await provisionAnalyst(request, adminToken, 'limit-one');
    analystTwo = await provisionAnalyst(request, adminToken, 'limit-two');

    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `E2E RowLimit Plan ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });
    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E RowLimit ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
    for (const analyst of [analystOne, analystTwo]) {
      await grantPermissionViaApi(request, adminToken, datasource.id, analyst.user.id, {
        canRead: true,
      });
    }

    // Two users, different caps on the same table…
    await createRowLimitPolicyViaApi(request, adminToken, datasource.id, {
      tableName: 'users',
      maxRows: 1,
      appliesToUserIds: [analystOne.user.id],
    });
    await createRowLimitPolicyViaApi(request, adminToken, datasource.id, {
      tableName: 'users',
      maxRows: 2,
      appliesToUserIds: [analystTwo.user.id],
    });
    // …and a second table on the same datasource with its own cap for everyone.
    await createRowLimitPolicyViaApi(request, adminToken, datasource.id, {
      schemaName: 'public',
      tableName: 'role_permissions',
      maxRows: 5,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  test('two tables on one datasource enforce different caps for the same user', async ({
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const users = await runAndFetch(request, analystOne.token, adminToken, datasource.id,
      SELECT_USERS);
    expect(users.row_count).toBe(1);
    expect(users.truncated_reason).toBe('ROW_LIMIT');

    const perms = await runAndFetch(request, analystOne.token, adminToken, datasource.id,
      SELECT_ROLE_PERMISSIONS);
    expect(perms.row_count).toBe(5);
    expect(perms.truncated_reason).toBe('ROW_LIMIT');
  });

  test('two users enforce different caps on the same table', async ({ request }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const users = await runAndFetch(request, analystTwo.token, adminToken, datasource.id,
      SELECT_USERS);
    expect(users.row_count).toBe(2);
    expect(users.truncated).toBe(true);
  });

  test('a join across two limited tables takes the lowest cap', async ({ request }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const joined = await runAndFetch(request, analystTwo.token, adminToken, datasource.id,
      SELECT_JOIN);
    expect(joined.row_count).toBe(2);
    expect(joined.truncated_reason).toBe('ROW_LIMIT');
  });

  test('admin creates a row-limit policy via the Row limits tab UI', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await page.getByRole('tab', { name: /Row limits/ }).click();

    await expect(page.getByText('public.role_permissions')).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Add policy' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Table').fill('audit_log');
    await dialog.getByLabel('Max rows').fill('25');
    // Blur the AutoComplete so its option dropdown can't intercept the Save click.
    await dialog.getByText('Add row-limit policy').click();
    await dialog.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByText('Row-limit policy saved')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('cell', { name: 'audit_log', exact: true })).toBeVisible({
      timeout: 10_000,
    });
  });
});
