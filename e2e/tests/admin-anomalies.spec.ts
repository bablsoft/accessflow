import { test, expect, type Page } from '@playwright/test';
import { login } from '../helpers/login';

// AF-383 — admin behavioural-anomaly (UBA) dashboard. A populated dashboard requires a rolling
// per-(user,datasource) baseline built from several hours of audit history (min-sample-size
// windows) before the detector activates, which can't be seeded within a CI run — that path is
// covered by BehaviorAnomalyDetectionIntegrationTest on the backend. This spec covers the
// user-facing flow that IS exercisable: the admin nav entry, the /admin/anomalies route + guard,
// the list API call, and the empty-state render.


async function waitForAnomaliesResponse(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/anomalies(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

test.describe.serial('/admin/anomalies dashboard', () => {
  test('admin reaches the anomalies dashboard from the sidebar', async ({ page }) => {
    await login(page);
    await page.getByRole('link', { name: 'Anomalies' }).click();
    await page.waitForURL('**/admin/anomalies', { timeout: 15_000 });
    await waitForAnomaliesResponse(page);

    await expect(page.getByRole('heading', { name: 'Anomalies' })).toBeVisible();
  });

  test('renders the empty state when no anomalies have been detected', async ({ page }) => {
    await login(page);
    await page.goto('/admin/anomalies');
    await waitForAnomaliesResponse(page);

    // No baseline can be seeded in CI, so the detector produces nothing → empty state.
    await expect(page.getByText('No anomalies')).toBeVisible({ timeout: 5_000 });
    // The status filter (defaulting to OPEN) is present so an admin can widen the view.
    await expect(page.getByTestId('anomalies-status')).toBeVisible();
  });
});
