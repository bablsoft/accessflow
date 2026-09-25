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

// Query-shape deny-lists (#940): a grant that denies JOIN refuses a joined query with 403 before it
// is persisted — also when the join hides in a subquery — while a single-table query is accepted.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('datasource denied query shapes (#940)', () => {
  let adminAccessToken = '';
  let analystEmail = '';
  let analystId = '';
  let uiDatasource: CreatedDatasource | null = null;
  let apiDatasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    analystEmail = `af940-analyst-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminAccessToken, analystEmail, 'AF-940 Analyst', 'ANALYST');
    const token = await waitForInviteToken(request, analystEmail);
    await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, 'AF-940 Analyst');
    analystId = (await findUserByEmailViaApi(request, adminAccessToken, analystEmail)).id;

    uiDatasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF940 UI ${Date.now()}`,
    });
    apiDatasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF940 API ${Date.now()}`,
    });
    await grantPermissionViaApi(request, adminAccessToken, apiDatasource.id, analystId, {
      deniedShapes: ['JOIN'],
    });
  });

  test.afterAll(async ({ request }) => {
    for (const ds of [uiDatasource, apiDatasource]) {
      if (ds) await deleteDatasource(request, adminAccessToken, ds.id);
    }
  });

  test('an admin denies a query shape from the grant modal and the row shows it', async ({ page }) => {
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

    await grantDialog.getByRole('combobox', { name: 'Denied query shapes' }).click();
    await page.locator('.ant-select-item-option').filter({ hasText: /^Join$/ }).click();

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
    expect(grantResponse.request().postDataJSON().denied_shapes).toEqual(['JOIN']);

    const analystRow = page.locator('.ant-table-row').filter({ hasText: analystEmail });
    await expect(analystRow).toHaveCount(1, { timeout: 10_000 });
    await expect(analystRow.getByText('1 shape', { exact: true })).toBeVisible();
  });

  test('a joined query is refused with 403; a single-table query is accepted', async ({
    request,
  }) => {
    if (!apiDatasource) throw new Error('beforeAll did not create the API datasource');
    const analystToken = await loginViaApi(request, analystEmail, ANALYST_PASSWORD);
    const submit = (sql: string) =>
      request.post(`${apiBase()}/api/v1/queries`, {
        headers: { Authorization: `Bearer ${analystToken}` },
        data: { datasource_id: apiDatasource!.id, sql, justification: 'e2e: AF-940' },
      });

    for (const sql of [
      'SELECT c.id FROM public.customer c JOIN public.orders o ON o.customer_id = c.id',
      'SELECT id FROM public.customer WHERE id IN (SELECT o.id FROM public.orders o, public.items i WHERE i.order_id = o.id)',
    ]) {
      const res = await submit(sql);
      expect(res.status(), sql).toBe(403);
      expect((await res.json()).detail, sql).toMatch(/JOIN/);
    }

    const allowed = await submit('SELECT id, name FROM public.customer WHERE id = 1 ORDER BY name');
    expect(allowed.status()).toBe(202);
  });
});
