import { test, expect, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/datasources/connectors the page issues on mount so
// the catalog is rendered with real data rather than the loading skeleton.
async function waitForConnectorsReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/datasources\/connectors$/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

// AF-334 + AF-411 cover the connector marketplace at /admin/connectors:
//   1. Browse — the declarative connector catalog renders, grouped into SQL and
//      NoSQL sections (the bundled PostgreSQL connector shows "Installed";
//      ClickHouse — a CUSTOM-dialect connector — shows an Install button;
//      MongoDB appears as a bundled NoSQL connector, "Installed").
//   2. Search — filtering by name narrows the grid.
//   3. Install — clicking Install issues POST /connectors/{id}/install and the
//      UI surfaces the result. The actual driver download needs egress to
//      Maven Central, so the spec asserts the request is accepted and a result
//      toast appears (success when reachable, an error toast otherwise) rather
//      than coupling to network availability.
test.describe.serial('/admin/connectors — connector catalog', () => {
  test('1) browse the connector catalog grouped by SQL and NoSQL', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/connectors');
    await waitForConnectorsReady(page);

    await expect(page.getByRole('heading', { name: 'Connectors' })).toBeVisible();
    // SQL and NoSQL category sections both render.
    await expect(page.getByRole('heading', { name: 'SQL', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'NoSQL', exact: true })).toBeVisible();
    // PostgreSQL is bundled → READY → rendered as "Installed", no install button.
    const postgresCard = page.getByText('PostgreSQL', { exact: true }).first();
    await expect(postgresCard).toBeVisible();
    // ClickHouse is a CUSTOM-dialect connector → installable.
    await expect(page.getByText('ClickHouse', { exact: true })).toBeVisible();
    // MongoDB is the bundled NoSQL connector (its name + the green db-type tag both
    // read "MongoDB", so scope to the first match).
    await expect(page.getByText('MongoDB', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('Installed').first()).toBeVisible();
  });

  test('2) search narrows the catalog', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/connectors');
    await waitForConnectorsReady(page);

    await page.getByRole('searchbox', { name: 'Search connectors' }).fill('click');
    await expect(page.getByText('ClickHouse', { exact: true })).toBeVisible();
    await expect(page.getByText('PostgreSQL', { exact: true })).toHaveCount(0);
  });

  test('3) installing a connector issues the install request and surfaces a result', async ({
    page,
  }) => {
    // The connector catalog + install endpoint are stubbed at the network layer for THIS test
    // only (tests 1 and 2 above exercise the real backend). Two reasons: (a) the backend driver
    // cache is shared across the whole e2e suite, so a connector installed by an earlier spec —
    // or by a Playwright retry of this very test — would leave no Install button to click; and
    // (b) a real install downloads a JDBC JAR from Maven Central, coupling the test to egress and
    // to an 11 MB download. Stubbing keeps the install-wiring assertion deterministic and
    // idempotent while still driving the real page, mutation, and toast.
    const clickhouse = {
      id: 'clickhouse',
      db_type: 'CUSTOM',
      category: 'RELATIONAL',
      name: 'ClickHouse',
      icon_url: '/db-icons/clickhouse.svg',
      vendor: 'ClickHouse, Inc.',
      description: 'Column-oriented OLAP database.',
      documentation_url: null,
      default_port: 8123,
      default_ssl_mode: 'DISABLE',
      jdbc_url_template: 'jdbc:ch://{host}:{port}/{database_name}',
      driver_class: 'com.clickhouse.jdbc.ClickHouseDriver',
      driver_status: 'AVAILABLE',
      bundled: false,
    };

    await page.route('**/api/v1/datasources/connectors', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ connectors: [clickhouse] }),
        });
      } else {
        await route.fallback();
      }
    });

    let installRequested = false;
    await page.route('**/api/v1/datasources/connectors/clickhouse/install', async (route) => {
      installRequested = true;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ ...clickhouse, driver_status: 'READY' }),
      });
    });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/connectors');

    const installButton = page.getByRole('button', { name: /install/i });
    await expect(installButton).toBeVisible();
    await installButton.click();

    // The install mutation fired the POST and its onSuccess showed the success toast.
    await expect(page.getByText('Installed ClickHouse')).toBeVisible({ timeout: 10_000 });
    expect(installRequested).toBe(true);
  });
});
