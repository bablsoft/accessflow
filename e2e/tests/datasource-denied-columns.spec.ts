import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  createPostgresDatasource,
  deleteDatasource,
  findUserByEmailViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  waitForInviteToken,
  type CreatedDatasource,
} from '../helpers/datasources';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';
const DENIED = 'public.customer.national_id';

// Column-level authorization (#935): a query that reaches a denied column is refused with 403
// before it is persisted, while a query that stays off the column is accepted.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('datasource denied columns (#935)', () => {
  let adminAccessToken = '';
  let analystEmail = '';
  let analystId = '';
  let uiDatasource: CreatedDatasource | null = null;
  let apiDatasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    analystEmail = `af935-analyst-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminAccessToken, analystEmail, 'AF-935 Analyst', 'ANALYST');
    const token = await waitForInviteToken(request, analystEmail);
    await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, 'AF-935 Analyst');
    analystId = (await findUserByEmailViaApi(request, adminAccessToken, analystEmail)).id;

    uiDatasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF935 UI ${Date.now()}`,
    });
    apiDatasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF935 API ${Date.now()}`,
    });
    await grantPermissionViaApi(request, adminAccessToken, apiDatasource.id, analystId, {
      deniedColumns: [DENIED],
    });
  });

  test.afterAll(async ({ request }) => {
    for (const ds of [uiDatasource, apiDatasource]) {
      if (ds) await deleteDatasource(request, adminAccessToken, ds.id);
    }
  });

  test('an admin denies a column from the grant modal and the row shows it', async ({ page }) => {
    if (!uiDatasource) throw new Error('beforeAll did not create the UI datasource');
    const dsId = uiDatasource.id;

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await page.getByRole('tab', { name: /^Permissions · 0$/ }).click();
    await page.getByRole('button', { name: 'Grant access' }).first().click();
    const grantDialog = page.getByRole('dialog').filter({ hasText: 'Grant datasource access' });
    await expect(grantDialog).toBeVisible();

    const userCombobox = grantDialog.getByRole('combobox', { name: 'User' });
    await userCombobox.click();
    await userCombobox.fill(analystEmail);
    await page.locator('.ant-select-item-option').filter({ hasText: analystEmail }).click();

    const deniedCombobox = grantDialog.getByRole('combobox', { name: 'Denied columns' });
    await deniedCombobox.click();
    await deniedCombobox.fill(DENIED);
    await deniedCombobox.press('Enter');

    const [grantResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new RegExp(`/api/v1/datasources/${dsId}/permissions$`).test(r.url()),
        { timeout: 15_000 },
      ),
      grantDialog.getByRole('button', { name: 'Grant access' }).click(),
    ]);
    expect(grantResponse.status()).toBe(201);
    expect(grantResponse.request().postDataJSON().denied_columns).toEqual([DENIED]);

    const analystRow = page.locator('.ant-table-row').filter({ hasText: analystEmail });
    await expect(analystRow).toHaveCount(1, { timeout: 10_000 });
    await expect(analystRow.getByText('1 column', { exact: true })).toBeVisible();
  });

  test('a query reaching the denied column is refused with 403; one that does not is accepted', async ({
    request,
  }) => {
    if (!apiDatasource) throw new Error('beforeAll did not create the API datasource');
    const analystToken = await loginViaApi(request, analystEmail, ANALYST_PASSWORD);
    const submit = (sql: string) =>
      request.post(`${apiBase()}/api/v1/queries`, {
        headers: { Authorization: `Bearer ${analystToken}` },
        data: { datasource_id: apiDatasource!.id, sql, justification: 'e2e: AF-935' },
      });

    for (const sql of [
      'SELECT national_id FROM customer',
      "SELECT id FROM customer WHERE national_id = '1'",
      'SELECT * FROM customer',
    ]) {
      const res = await submit(sql);
      expect(res.status(), sql).toBe(403);
      expect((await res.json()).detail, sql).toContain(DENIED);
    }

    const allowed = await submit('SELECT id, name FROM customer');
    expect(allowed.status()).toBe(202);
  });
});
