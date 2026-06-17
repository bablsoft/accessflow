import { test, expect, type Page } from '@playwright/test';
import {
  createMaskingPolicyViaApi,
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af443-${Date.now()}`;
const DS_NAME = `Postgres E2E ${UNIQUE_SUFFIX} SAMPLE`;

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function waitForSettingsReady(page: Page, dsId: string): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new RegExp(`/api/v1/datasources/${dsId}$`).test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
  await expect(page.getByText('Loading datasource…')).toHaveCount(0, { timeout: 10_000 });
}

// AF-443 — enhanced schema exploration: searchable object tree + sample-data preview.
//
// The e2e Postgres container hosts AccessFlow's own database, so a customer
// datasource pointed at it introspects real tables (`users`, `datasources`, …)
// with real rows (the bootstrap-seeded admin). We attach a masking policy on
// `public.users.email` that reveals nobody, then drive the Schema tab: filter
// the tree, preview the `users` table, and assert the sample renders through
// the governance path — the email column is badged and shows `***`, never the
// raw value. The RLS/masking parity is unit- and integration-tested in the
// backend (DefaultQueryExecutorPostgresIntegrationTest) and the engine ITs;
// this spec asserts the UI flow end-to-end.
test.describe('datasource schema explorer — searchable tree + sample preview', () => {
  let datasource: CreatedDatasource | null = null;
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminAccessToken, { name: DS_NAME });
    await createMaskingPolicyViaApi(request, adminAccessToken, datasource.id, {
      columnRef: 'public.users.email',
      strategy: 'FULL',
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('filters the object tree and previews a masking-aware sample', async ({ page }) => {
    if (!datasource) throw new Error('beforeAll did not create the datasource');
    const dsId = datasource.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    // Open the Schema tab (label = i18n key datasources.settings.tab_schema).
    const [schemaResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new RegExp(`/api/v1/datasources/${dsId}/schema$`).test(r.url()),
        { timeout: 30_000 },
      ),
      page.getByRole('tab', { name: 'Schema' }).click(),
    ]);
    expect(schemaResponse.status()).toBe(200);

    // Cross-hierarchy filter — typing a table name narrows the tree to it.
    await page.getByPlaceholder(/filter schemas, tables, columns/i).fill('users');
    await expect(page.getByRole('button', { name: 'users', exact: true })).toBeVisible();

    // Preview the `users` table → sample-rows GET fires and the drawer opens.
    const [sampleResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new RegExp(`/api/v1/datasources/${dsId}/sample-rows`).test(r.url()),
        { timeout: 30_000 },
      ),
      page.getByRole('button', { name: 'Preview data for users' }).click(),
    ]);
    expect(sampleResponse.status()).toBe(200);

    // The drawer renders the governance banner and the masked email column.
    await expect(
      page.getByText(/row-level security and column masking are applied/i),
    ).toBeVisible({ timeout: 15_000 });
    // The email column carries the masked-column lock (its accessible name) and
    // its cells render the masked value only — never a raw address.
    await expect(
      page.getByLabel('Column masked by access policy').first(),
    ).toBeVisible();
    await expect(page.getByText('***').first()).toBeVisible();
    await expect(page.getByText(ADMIN_EMAIL)).toHaveCount(0);
  });
});
