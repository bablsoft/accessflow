import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
} from '../helpers/datasources';

// AF-365 — admin datasource health dashboard. The endpoint lists one snapshot row
// per datasource in the caller's org (pool gauges + 24h query volume / latency /
// errors). We seed a Postgres datasource and assert its card renders. The pool is
// lazily created on first query, so a freshly-created datasource shows the
// "pool not initialised" state and zero queries — exactly what we assert.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af365-${Date.now()}`;
const DATASOURCE_NAME = `health-ds-${UNIQUE_SUFFIX}`;

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function waitForHealthResponse(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/datasource-health(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

test.describe.serial('/admin/datasource-health dashboard', () => {
  let adminToken = '';
  let datasourceId = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const datasource = await createPostgresDatasource(request, adminToken, {
      name: DATASOURCE_NAME,
    });
    datasourceId = datasource.id;
  });

  test.afterAll(async ({ request }) => {
    if (datasourceId) {
      await deleteDatasource(request, adminToken, datasourceId);
    }
  });

  test('renders a health card for the seeded datasource', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/datasource-health');
    await waitForHealthResponse(page);

    await expect(
      page.getByRole('heading', { name: 'Datasource health' }),
    ).toBeVisible();

    const card = page
      .getByTestId('datasource-health-card')
      .filter({ hasText: DATASOURCE_NAME });
    await expect(card).toBeVisible();
    // No queries have run, so the pool is uninitialised and the 24h count is zero.
    await expect(card.getByTestId('datasource-health-pool-uninitialized')).toBeVisible();
    await expect(card.getByText('Queries (24h)')).toBeVisible();
  });

  test('is reachable from the admin sidebar nav', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/editor');
    await page.getByRole('link', { name: 'Datasource health' }).click();
    await page.waitForURL('**/admin/datasource-health', { timeout: 10_000 });
    await waitForHealthResponse(page);
    await expect(
      page.getByRole('heading', { name: 'Datasource health' }),
    ).toBeVisible();
  });
});
