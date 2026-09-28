import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  executeQueryViaApi,
  findUserByEmailViaApi,
  inviteUserViaApi,
  loginViaApi,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
  type InvitedUser,
} from '../helpers/datasources';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

// The e2e datasource points at AccessFlow's own database, whose `role_permissions` table holds one
// row per ADMIN permission — far more than the 5-row override granted below.
const SELECT_MANY = 'SELECT permission FROM role_permissions ORDER BY permission';

test.describe.configure({ timeout: 120_000 });

// #946 — a row-limit override granted through the UI is what the proxy enforces, the result says
// so without blaming the datasource, and the effective-permission explorer names its source.
test.describe.serial('effective-permission explorer and row-cap copy (#946)', () => {
  let adminToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;
  let analyst: InvitedUser;
  let analystEmail = '';
  let analystToken = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    analystEmail = `rowcap-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminToken, analystEmail, 'RowCap Analyst', 'ANALYST');
    const inviteToken = await waitForInviteToken(request, analystEmail);
    await acceptInvitationViaApi(request, inviteToken, ANALYST_PASSWORD, 'RowCap Analyst');
    analystToken = await loginViaApi(request, analystEmail, ANALYST_PASSWORD);
    analyst = await findUserByEmailViaApi(request, adminToken, analystEmail);

    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `E2E RowCap Plan ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });
    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E RowCap ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  test('admin grants a 5-row limit and the explorer traces the cap to that grant', async ({
    page,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await page.getByRole('tab', { name: /^Permissions · 0$/ }).click();
    await page.getByRole('button', { name: 'Grant access' }).first().click();
    const grantDialog = page.getByRole('dialog').filter({ hasText: 'Grant datasource access' });
    await expect(grantDialog).toBeVisible();

    const userCombobox = grantDialog.getByRole('combobox', { name: 'User' });
    await userCombobox.click();
    await userCombobox.fill(analystEmail);
    await page.locator('.ant-select-item-option').filter({ hasText: analystEmail }).click();

    const rowLimit = grantDialog.getByLabel('Row limit override');
    // At the datasource cap the override cannot take effect — a warning, not a block.
    await rowLimit.fill('1000');
    await expect(grantDialog.getByTestId('grant-row-limit-no-effect')).toBeVisible();
    await rowLimit.fill('5');
    await expect(grantDialog.getByTestId('grant-row-limit-no-effect')).toHaveCount(0);

    const [grantResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new RegExp(`/api/v1/datasources/${datasource!.id}/permissions$`).test(r.url()),
        { timeout: 15_000 },
      ),
      grantDialog.getByRole('button', { name: 'Grant access' }).click(),
    ]);
    expect(grantResponse.status()).toBe(201);
    expect(grantResponse.request().postDataJSON().row_limit_override).toBe(5);
    await expect(grantDialog).toHaveCount(0);

    await page.getByRole('tab', { name: 'Effective access' }).click();
    const userPicker = page.getByRole('combobox', { name: 'User' });
    await userPicker.click();
    // The picker accepts a pasted id, so the analyst is found however many users the stack holds.
    await userPicker.fill(analyst.id);
    await page.locator('.ant-select-item-option').filter({ hasText: analyst.id }).first().click();

    const card = page.getByTestId('explorer-row-cap');
    await expect(card.getByTestId('explorer-row-cap-value')).toHaveText('5', { timeout: 15_000 });
    await expect(card.getByTestId('explorer-row-cap-source')).toHaveAttribute(
      'data-source',
      'OVERRIDE',
    );
    await expect(card.getByTestId('explorer-row-cap-explanation')).toContainText('Direct grant');
  });

  test('the analyst gets 5 rows and truncation copy that names the effective limit', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      analystToken,
      datasource.id,
      SELECT_MANY,
      '#946 row cap truncation copy',
    );
    await waitForQueryStatus(request, analystToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, adminToken, submitted.id);
    const outcome = await executeQueryViaApi(request, analystToken, submitted.id);
    expect(outcome.status).toBe('EXECUTED');

    await login(page, analystEmail, ANALYST_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);

    await expect(page.locator('.ant-table-tbody tr.ant-table-row')).toHaveCount(5, {
      timeout: 15_000,
    });
    await expect(
      page.getByText(
        '5 rows returned (truncated at the effective row limit for this user on this datasource)',
      ),
    ).toBeVisible();
  });
});
