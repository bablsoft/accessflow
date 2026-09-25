import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  createDataBudgetViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  executeQueryViaApi,
  findUserByEmailViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type InvitedUser,
} from '../helpers/datasources';
import { login } from '../helpers/login';
import { findRowAcrossPages } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

// The e2e datasource points at AccessFlow's own database: `role_permissions` holds one row per
// ADMIN permission, comfortably more than the three-row budget used below.
const SELECT_PERMISSIONS = 'SELECT permission FROM role_permissions ORDER BY permission';

interface ResultPage {
  row_count: number;
  truncated: boolean;
  truncated_reason: string | null;
}

interface BudgetStatus {
  exhausted: boolean;
  remaining_rows?: number | null;
  budgets: { used_rows: number }[];
}

async function provisionAnalyst(
  request: APIRequestContext,
  adminToken: string,
  label: string,
): Promise<{ user: InvitedUser; email: string; token: string }> {
  const email = `${label}-${randomUUID()}@e2e.local`;
  await inviteUserViaApi(request, adminToken, email, `Budget ${label}`, 'ANALYST');
  const inviteToken = await waitForInviteToken(request, email);
  await acceptInvitationViaApi(request, inviteToken, ANALYST_PASSWORD, `Budget ${label}`);
  const token = await loginViaApi(request, email, ANALYST_PASSWORD);
  const user = await findUserByEmailViaApi(request, adminToken, email);
  return { user, email, token };
}

async function myStanding(
  request: APIRequestContext,
  token: string,
  datasourceId: string,
): Promise<BudgetStatus> {
  const res = await request.get(`${apiBase()}/api/v1/datasources/${datasourceId}/data-budgets/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) throw new Error(`Budget standing failed: ${res.status()} ${await res.text()}`);
  return (await res.json()) as BudgetStatus;
}

async function selectDatasource(page: Page, name: string): Promise<void> {
  await page.goto('/editor');
  await page.getByRole('combobox').first().click();
  await page.locator('.ant-select-item-option').filter({ hasText: name }).click();
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('per-user data-volume budgets (#942)', () => {
  let adminToken = '';
  let datasource: CreatedDatasource | null = null;
  let reviewAnalyst: { user: InvitedUser; email: string; token: string };
  let rejectAnalyst: { user: InvitedUser; email: string; token: string };

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    reviewAnalyst = await provisionAnalyst(request, adminToken, 'budget-review');
    rejectAnalyst = await provisionAnalyst(request, adminToken, 'budget-reject');

    // No human approval: an in-budget SELECT auto-approves, so any review below is the budget's.
    const plan = await createReviewPlanViaApi(request, adminToken, {
      name: `E2E Budget Plan ${Date.now()}`,
      requiresHumanApproval: false,
    });
    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E Budget ${Date.now()}`,
      reviewPlanId: plan.id,
    });
    for (const analyst of [reviewAnalyst, rejectAnalyst]) {
      await grantPermissionViaApi(request, adminToken, datasource.id, analyst.user.id, {
        canRead: true,
      });
    }
    await createDataBudgetViaApi(request, adminToken, datasource.id, {
      name: 'Three rows, then review',
      maxRows: 3,
      breachAction: 'REQUIRE_REVIEW',
      appliesToUserIds: [reviewAnalyst.user.id],
    });
    await createDataBudgetViaApi(request, adminToken, datasource.id, {
      name: 'Three rows, then reject',
      maxRows: 3,
      breachAction: 'REJECT',
      appliesToUserIds: [rejectAnalyst.user.id],
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  test('a read is capped at the allowance left, then the next one goes to review', async ({
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const first = await submitQueryViaApi(request, reviewAnalyst.token, datasource.id,
      SELECT_PERMISSIONS, '#942 data budget — first read');
    await waitForQueryStatus(request, reviewAnalyst.token, first.id, 'APPROVED');
    const outcome = await executeQueryViaApi(request, reviewAnalyst.token, first.id);
    expect(outcome.status).toBe('EXECUTED');
    const res = await request.get(`${apiBase()}/api/v1/queries/${first.id}/results`, {
      headers: { Authorization: `Bearer ${reviewAnalyst.token}` },
    });
    expect(res.ok()).toBe(true);
    const page = (await res.json()) as ResultPage;
    expect(page.row_count).toBe(3);
    expect(page.truncated_reason).toBe('DATA_BUDGET');

    const standing = await myStanding(request, reviewAnalyst.token, datasource.id);
    expect(standing.exhausted).toBe(true);
    expect(standing.budgets[0]?.used_rows).toBe(3);

    const second = await submitQueryViaApi(request, reviewAnalyst.token, datasource.id,
      SELECT_PERMISSIONS, '#942 data budget — over budget');
    await waitForQueryStatus(request, reviewAnalyst.token, second.id, 'PENDING_REVIEW');
  });

  test('a rejecting budget refuses the read once used up', async ({ request }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const first = await submitQueryViaApi(request, rejectAnalyst.token, datasource.id,
      SELECT_PERMISSIONS, '#942 data budget — first read');
    await waitForQueryStatus(request, rejectAnalyst.token, first.id, 'APPROVED');
    await executeQueryViaApi(request, rejectAnalyst.token, first.id);

    const second = await submitQueryViaApi(request, rejectAnalyst.token, datasource.id,
      SELECT_PERMISSIONS, '#942 data budget — refused');
    await waitForQueryStatus(request, rejectAnalyst.token, second.id, 'REJECTED');
  });

  test('the analyst sees their used-up budget in the editor', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    await login(page, reviewAnalyst.email, ANALYST_PASSWORD);
    await selectDatasource(page, datasource.name);

    const indicator = page.getByTestId('data-budget-indicator');
    await expect(indicator).toBeVisible({ timeout: 15_000 });
    await expect(indicator.getByText('Data budget used up')).toBeVisible();
    await expect(indicator.getByText('Three rows, then review')).toBeVisible();
  });

  test('admin creates a data budget via the Data budgets tab', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await page.getByRole('tab', { name: /Data budgets/ }).click();

    // The settings page renders tab content beside <Tabs>, not inside its tabpanel.
    await expect(page.getByText('Three rows, then review')).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Add budget' }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Name').fill('Weekly analyst cap');
    await dialog.getByLabel('Row limit').fill('50000');
    await dialog.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByText('Data budget saved')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('cell', { name: 'Weekly analyst cap', exact: true })).toBeVisible({
      timeout: 10_000,
    });
  });

  test('admin sees a user\'s data usage from the users page', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');

    // The users table has no server-side search; page through it (parallel specs add users).
    // The invitations table below lists the same email; the users table renders first.
    const row = page.getByRole('row').filter({ hasText: reviewAnalyst.email }).first();
    await findRowAcrossPages(page, row);
    // dispatchEvent: the sticky onboarding panel can overlay the row once the table scrolls.
    await row.getByRole('button', { name: 'Edit' }).dispatchEvent('click');
    await page.getByRole('menuitem', { name: /Data usage/ }).click();

    const drawer = page.getByRole('dialog').filter({ hasText: 'Data usage —' });
    await expect(drawer.getByText(datasource.name)).toBeVisible({ timeout: 15_000 });
    await expect(drawer.getByText('Three rows, then review')).toBeVisible();
    await expect(drawer.getByText('Used up')).toBeVisible();
  });
});
