import { test, expect, type Page } from '@playwright/test';
import { login } from '../helpers/login';

// AF-498 — personalized dashboard. Covers the default post-login landing, the self-scoped widgets
// rendering (incl. the redesign's attestation/access-request/request-group widgets), widget
// visibility + reset-layout customization persisting across reloads, clickable stat tiles, the
// trends range control, and the signed weekly-summary export download.


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
    await login(page);
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
    // Redesign additions: attestation worklist, JIT access requests, request groups.
    await expect(page.getByTestId('dashboard-widget-attestationsDue')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-myAccessRequests')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-myRequestGroups')).toBeVisible();
    // Bklit chart widgets: risk mix ring + 90-day activity heatmap.
    await expect(page.getByTestId('dashboard-widget-riskMix')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-activityHeatmap')).toBeVisible();
  });

  test('shows the metric and range controls on the trends widget', async ({ page }) => {
    await login(page);
    await waitForSummary(page);

    const trends = page.getByTestId('dashboard-widget-trends');
    // exact: true — the chart title "Queries by status" also contains "by status".
    await expect(trends.getByText('By status', { exact: true })).toBeVisible();
    await expect(trends.getByText('30d', { exact: true })).toBeVisible();
    // Register the waiter before the click so a fast localhost response can't slip past it.
    await Promise.all([
      page.waitForResponse(
        (r) => /\/api\/v1\/dashboard\/my-query-trends/.test(r.url()) && r.ok(),
        { timeout: 15_000 },
      ),
      trends.getByText('7d', { exact: true }).click(),
    ]);
  });

  test('stat tiles navigate to the matching list page', async ({ page }) => {
    await login(page);
    await waitForSummary(page);

    await page.getByTestId('dashboard-stat-pending').click();
    await page.waitForURL('**/reviews', { timeout: 15_000 });
    await expect(page).toHaveURL(/\/reviews$/);
  });

  test('hiding a widget persists across reloads', async ({ page }) => {
    await login(page);
    await waitForSummary(page);

    await expect(page.getByTestId('dashboard-widget-trends')).toBeVisible();
    await page.getByRole('button', { name: 'Customize' }).click();
    // The whole menu row is the toggle target (the checkbox inside is presentational).
    await page.getByRole('menuitem', { name: 'Query trends' }).click();
    await expect(page.getByTestId('dashboard-widget-trends')).toBeHidden();

    await page.reload();
    await waitForSummary(page);
    await expect(page.getByTestId('dashboard-widget-trends')).toBeHidden();

    // Restore so the serial suite leaves a clean layout. The menu stays open across toggles.
    await page.getByRole('button', { name: 'Customize' }).click();
    await page.getByRole('menuitem', { name: 'Query trends' }).click();
    await expect(page.getByTestId('dashboard-widget-trends')).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('reset layout restores hidden widgets', async ({ page }) => {
    await login(page);
    await waitForSummary(page);

    await page.getByRole('button', { name: 'Customize' }).click();
    await page.getByRole('menuitem', { name: 'Anomaly alerts' }).click();
    await expect(page.getByTestId('dashboard-widget-anomalies')).toBeHidden();

    // Reset closes the menu and restores the default layout.
    await page.getByRole('menuitem', { name: 'Reset layout' }).click();
    await expect(page.getByTestId('dashboard-widget-anomalies')).toBeVisible();
  });

  test('exports the weekly summary as a signed PDF', async ({ page }) => {
    await login(page);
    await waitForSummary(page);

    await page.getByRole('button', { name: 'Export this week' }).click();
    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 15_000 }),
      page.getByText('Export as PDF').click(),
    ]);
    expect(download.suggestedFilename()).toMatch(/dashboard-summary.*\.pdf/);
  });
});
