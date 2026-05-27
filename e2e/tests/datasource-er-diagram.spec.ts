import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af348-${Date.now()}`;
const DS_NAME = `Postgres E2E ${UNIQUE_SUFFIX} ER-DIAGRAM`;

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

// AF-348 — schema explorer ER diagram tab.
//
// The e2e Postgres container hosts AccessFlow's own database, which has
// foreign keys across `permissions → users`, `permissions → datasources`,
// and similar tables. Pointing a customer datasource at this same database
// gives the ER diagram a non-empty graph to render — exactly what we need
// to assert that the new tab works end-to-end without relying on a custom
// seed schema. The diagram body itself (xyflow + dagre) is unit-tested in
// frontend/src/components/datasources/erDiagramLayout.test.ts; this spec
// asserts only that the tab mounts, the schema GET fires, and the rendered
// content references at least one known seeded table by name.
test.describe('datasource ER diagram tab', () => {
  let datasource: CreatedDatasource | null = null;
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: DS_NAME,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('renders the ER diagram tab and draws nodes for FK-related tables', async ({ page }) => {
    if (!datasource) throw new Error('beforeAll did not create the datasource');
    const dsId = datasource.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    // Switch to the ER diagram tab. Label is the i18n key
    // datasources.settings.tab_er_diagram ("ER diagram").
    const tab = page.getByRole('tab', { name: 'ER diagram' });
    await expect(tab).toBeVisible();

    const [schemaResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          new RegExp(`/api/v1/datasources/${dsId}/schema$`).test(r.url()),
        { timeout: 30_000 },
      ),
      tab.click(),
    ]);
    expect(schemaResponse.status()).toBe(200);

    // AccessFlow's internal schema has FKs across `permissions`,
    // `datasources`, `users`, etc. — the diagram should render (not the
    // empty state). xyflow paints each table node with a header that
    // includes the qualified name, so `public.users` shows the literal
    // table name "users" in the DOM. We assert that one such table
    // appears, scoped to nodes inside the React Flow viewport so the
    // assertion does not accidentally match unrelated UI chrome.
    const viewport = page.locator('.react-flow__viewport');
    await expect(viewport).toBeVisible({ timeout: 15_000 });
    await expect(viewport.getByText('users', { exact: true }).first()).toBeVisible({
      timeout: 15_000,
    });

    // Empty-state guard — if backend FK introspection silently fails, the
    // tab falls back to EmptyState with this title. Assert it is absent so
    // a regression in the introspection wire-through is loud.
    await expect(
      page.getByText('No foreign keys found', { exact: true }),
    ).toHaveCount(0);
  });
});
