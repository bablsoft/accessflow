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

// AF-334 covers the connector marketplace at /admin/connectors:
//   1. Browse — the declarative connector catalog renders (the bundled
//      PostgreSQL connector shows "Installed"; ClickHouse — a CUSTOM-dialect
//      connector — shows an Install button).
//   2. Search — filtering by name narrows the grid.
//   3. Install — clicking Install issues POST /connectors/{id}/install and the
//      UI surfaces the result. The actual driver download needs egress to
//      Maven Central, so the spec asserts the request is accepted and a result
//      toast appears (success when reachable, an error toast otherwise) rather
//      than coupling to network availability.
test.describe.serial('/admin/connectors — connector catalog', () => {
  test('1) browse the connector catalog', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/connectors');
    await waitForConnectorsReady(page);

    await expect(page.getByRole('heading', { name: 'Connectors' })).toBeVisible();
    // PostgreSQL is bundled → READY → rendered as "Installed", no install button.
    const postgresCard = page.getByText('PostgreSQL', { exact: true }).first();
    await expect(postgresCard).toBeVisible();
    // ClickHouse is a CUSTOM-dialect connector → installable.
    await expect(page.getByText('ClickHouse', { exact: true })).toBeVisible();
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
    test.setTimeout(90_000);
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/connectors');
    await waitForConnectorsReady(page);

    // Find the ClickHouse card's Install button. The card stacks the name and
    // an Install button; scope the button lookup to the catalog grid.
    const installButton = page
      .getByRole('button', { name: /install/i })
      .first();
    await expect(installButton).toBeVisible();

    const installResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/datasources\/connectors\/[a-z0-9-]+\/install$/.test(r.url()),
      { timeout: 75_000 },
    );
    await installButton.click();
    const response = await installResponse;
    // Request was accepted by the backend (200 installed, or 422 when the
    // driver JAR cannot be downloaded in this environment).
    expect([200, 422]).toContain(response.status());

    // Either outcome surfaces an Ant Design message toast.
    await expect(page.locator('.ant-message-notice').first()).toBeVisible({ timeout: 10_000 });
  });
});
