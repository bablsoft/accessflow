import { test, expect, type Page } from '@playwright/test';

// AF-498 — personalized dashboard. Covers the new default post-login landing, the four self-scoped
// widgets rendering, widget-visibility customization persisting across reloads, and the signed
// weekly-summary export download.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

async function waitForSummary(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/dashboard\/summary$/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

test.describe.serial('/dashboard personalized home', () => {
  test('is the default landing and renders the core widgets', async ({ page }) => {
    await loginViaUi(page);
    await waitForSummary(page);

    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await expect(page.getByTestId('dashboard-stat-pending')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-pendingApprovals')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-recentQueries')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-trends')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-suggestions')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-anomalies')).toBeVisible();
    // API Access Governance widgets + stat cards (AF-500).
    await expect(page.getByTestId('dashboard-stat-openApiRequests')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-recentApiRequests')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-apiRequestTrends')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-pendingApiApprovals')).toBeVisible();
  });

  test('hiding a widget persists across reloads', async ({ page }) => {
    await loginViaUi(page);
    await waitForSummary(page);

    await expect(page.getByTestId('dashboard-widget-trends')).toBeVisible();
    await page.getByRole('button', { name: 'Customize' }).click();
    await page.getByRole('checkbox', { name: 'Query trends' }).click();
    await expect(page.getByTestId('dashboard-widget-trends')).toBeHidden();

    await page.reload();
    await waitForSummary(page);
    await expect(page.getByTestId('dashboard-widget-trends')).toBeHidden();

    // Restore so the serial suite leaves a clean layout.
    await page.getByRole('button', { name: 'Customize' }).click();
    await page.getByRole('checkbox', { name: 'Query trends' }).click();
    await expect(page.getByTestId('dashboard-widget-trends')).toBeVisible();
  });

  test('exports the weekly summary as a signed PDF', async ({ page }) => {
    await loginViaUi(page);
    await waitForSummary(page);

    await page.getByRole('button', { name: 'Export this week' }).click();
    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 15_000 }),
      page.getByText('Export as PDF').click(),
    ]);
    expect(download.suggestedFilename()).toMatch(/dashboard-summary.*\.pdf/);
  });
});
