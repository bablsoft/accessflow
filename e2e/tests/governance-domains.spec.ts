import { test, expect, type Page } from '@playwright/test';
import { login } from '../helpers/login';
import { apiBase } from '../helpers/datasources';

// #926 — the two governance-domain flags as a visibility signal. An org admin turns a domain off
// on /admin/governance-domains and the sidebar sub-sections, review-hub tabs and dashboard
// widgets for that domain stop being offered — while the routes stay reachable, so a deep link
// into the de-emphasised domain still works.
//
// SERIAL: this mutates the one seeded organization's config. Every step restores both domains
// before it finishes, and the final test asserts the restored state.

const SETTINGS = '/admin/governance-domains';

async function setDomains(page: Page, apis: boolean, deployments: boolean): Promise<void> {
  const res = await page.context().request.put(`${apiBase()}/api/v1/admin/governance-domains`, {
    data: { governs_apis: apis, governs_deployments: deployments },
  });
  expect(res.ok(), `PUT governance-domains failed: ${res.status()}`).toBe(true);
}

test.describe.serial('/admin/governance-domains (#926)', () => {
  test('renders both switches on for the seeded organization', async ({ page }) => {
    await login(page);
    await page.goto(SETTINGS);

    await expect(page.getByRole('heading', { name: 'Governance Domains' })).toBeVisible();
    await expect(page.getByRole('switch', { name: 'Govern API access' })).toBeChecked();
    await expect(page.getByRole('switch', { name: 'Govern deployments' })).toBeChecked();
  });

  test('turning deployments off hides its nav, review tab and dashboard widgets', async ({
    page,
  }) => {
    await login(page);
    await page.goto(SETTINGS);
    await page.getByRole('switch', { name: 'Govern deployments' }).click();
    await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === 'PUT' && /governance-domains$/.test(r.url()) && r.ok(),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: 'Save' }).click(),
    ]);

    // The nav reacts on this render — no reload, no token refresh.
    await expect(
      page.getByRole('button', { name: /(Expand|Collapse) Deployments/ }),
    ).toHaveCount(0);
    // The API sub-sections are untouched.
    await expect(page.getByRole('button', { name: /(Expand|Collapse) API/ }).first()).toBeVisible();

    await page.goto('/reviews');
    await expect(page.getByRole('tab', { name: /Deployments/ })).toHaveCount(0);
    await expect(page.getByRole('tab', { name: /Queries/ })).toBeVisible();

    await page.goto('/dashboard');
    await expect(page.getByTestId('dashboard-widget-recentQueries')).toBeVisible();
    await expect(page.getByTestId('dashboard-widget-myDeployments')).toHaveCount(0);
    await expect(page.getByTestId('dashboard-widget-deploymentVersions')).toHaveCount(0);
    await expect(page.getByTestId('dashboard-stat-openDeployments')).toHaveCount(0);

    await setDomains(page, true, true);
  });

  test('a deep link into a switched-off domain still works', async ({ page }) => {
    await login(page);
    await setDomains(page, true, false);
    await page.reload();

    // The route is registered and the permission untouched, so the page renders — hiding a
    // domain must never 403 anyone.
    await page.goto('/deployments');
    await expect(page.getByRole('heading', { name: 'Deployments' })).toBeVisible();

    // An explicit ?tab= into the de-emphasised queue keeps its tab.
    await page.goto('/reviews?tab=deployments');
    await expect(page).toHaveURL(/tab=deployments/);
    await expect(page.getByRole('tab', { name: /Deployments/ })).toBeVisible();

    await setDomains(page, true, true);
  });

  test('restores both domains for the rest of the suite', async ({ page }) => {
    await login(page);
    await setDomains(page, true, true);
    await page.goto(SETTINGS);

    await expect(page.getByRole('switch', { name: 'Govern API access' })).toBeChecked();
    await expect(page.getByRole('switch', { name: 'Govern deployments' })).toBeChecked();
    await expect(
      page.getByRole('button', { name: /(Expand|Collapse) Deployments/ }).first(),
    ).toBeVisible();
  });
});
