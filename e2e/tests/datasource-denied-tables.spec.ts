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
const DENIED = 'public.salary';

// Table deny-lists (#939): a grant that allows the whole `public` schema but denies one table refuses
// a query on that table with 403 before it is persisted — also when the name is unqualified — while
// any other table in the schema, including one never seen before, is accepted.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('datasource denied tables (#939)', () => {
  let adminAccessToken = '';
  let analystEmail = '';
  let analystId = '';
  let uiDatasource: CreatedDatasource | null = null;
  let apiDatasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    analystEmail = `af939-analyst-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminAccessToken, analystEmail, 'AF-939 Analyst', 'ANALYST');
    const token = await waitForInviteToken(request, analystEmail);
    await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, 'AF-939 Analyst');
    analystId = (await findUserByEmailViaApi(request, adminAccessToken, analystEmail)).id;

    uiDatasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF939 UI ${Date.now()}`,
    });
    apiDatasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF939 API ${Date.now()}`,
    });
    await grantPermissionViaApi(request, adminAccessToken, apiDatasource.id, analystId, {
      allowedSchemas: ['public'],
      deniedTables: [DENIED],
    });
  });

  test.afterAll(async ({ request }) => {
    for (const ds of [uiDatasource, apiDatasource]) {
      if (ds) await deleteDatasource(request, adminAccessToken, ds.id);
    }
  });

  test('an admin denies a table from the grant modal and the row shows it', async ({ page }) => {
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

    const deniedCombobox = grantDialog.getByRole('combobox', { name: 'Denied tables' });
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
    expect(grantResponse.request().postDataJSON().denied_tables).toEqual([DENIED]);

    const analystRow = page.locator('.ant-table-row').filter({ hasText: analystEmail });
    await expect(analystRow).toHaveCount(1, { timeout: 10_000 });
    await expect(analystRow.getByText('1 entry', { exact: true })).toBeVisible();
  });

  test('a query on the denied table is refused with 403; other tables in the schema are accepted', async ({
    request,
  }) => {
    if (!apiDatasource) throw new Error('beforeAll did not create the API datasource');
    const analystToken = await loginViaApi(request, analystEmail, ANALYST_PASSWORD);
    const submit = (sql: string) =>
      request.post(`${apiBase()}/api/v1/queries`, {
        headers: { Authorization: `Bearer ${analystToken}` },
        data: { datasource_id: apiDatasource!.id, sql, justification: 'e2e: AF-939' },
      });

    for (const sql of [
      'SELECT * FROM public.salary',
      'SELECT * FROM salary',
      'SELECT c.id FROM public.customer c JOIN public.salary s ON s.id = c.id',
    ]) {
      const res = await submit(sql);
      expect(res.status(), sql).toBe(403);
      expect((await res.json()).detail, sql).toMatch(/salary/);
    }

    for (const sql of ['SELECT id FROM public.customer', 'SELECT id FROM public.brand_new_table']) {
      const allowed = await submit(sql);
      expect(allowed.status(), sql).toBe(202);
    }
  });
});
