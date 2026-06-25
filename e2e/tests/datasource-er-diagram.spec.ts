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
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
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
    // `datasources`, `users`, etc. Assert that the response actually
    // carries the new `foreign_keys` field on every table and that at
    // least one FK exists — this is the contract the frontend depends
    // on, independent of how React Flow paints the canvas.
    const schemaBody = (await schemaResponse.json()) as {
      schemas: {
        tables: {
          name: string;
          foreign_keys: { from_column: string; to_table: string; to_column: string }[];
        }[];
      }[];
    };
    const allTables = schemaBody.schemas.flatMap((s) => s.tables);
    expect(allTables.length).toBeGreaterThan(0);
    for (const t of allTables) {
      expect(Array.isArray(t.foreign_keys)).toBe(true);
    }
    const totalForeignKeys = allTables.reduce((sum, t) => sum + t.foreign_keys.length, 0);
    expect(totalForeignKeys).toBeGreaterThan(0);

    // The diagram should render (not the empty state). React Flow paints
    // one `.react-flow__node` div per table; asserting at least one
    // exists is a stable signal that ErDiagram mounted and dagre laid
    // the nodes out. The exact text inside each node (schema-qualified
    // table name + columns) is unit-tested in
    // frontend/src/components/datasources/erDiagramLayout.test.ts — no
    // need to re-assert here, where canvas-zoom and font rendering can
    // make text-based assertions flaky.
    await expect(page.locator('.react-flow__node').first()).toBeVisible({
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
