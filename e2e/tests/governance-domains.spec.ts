import { test, expect, request as pwRequest, type Page } from '@playwright/test';
import { login, ADMIN_EMAIL, ADMIN_PASSWORD } from '../helpers/login';
import { loginViaApi } from '../helpers/datasources';
import { setGovernanceDomainsViaApi } from '../helpers/governanceDomains';

// #926 — the two governance-domain flags as a visibility signal. An org admin turns a domain off
// on /admin/governance-domains and the sidebar sub-sections, review-hub tabs and dashboard widgets
// for that domain stop being offered — while the routes stay reachable, so a deep link into the
// de-emphasised domain still works.
//
// SERIAL: this mutates the one seeded organization's config. `afterAll` restores both domains
// unconditionally — `describe.serial` abandons the remaining tests once one fails, so an inline
// restore at the end of a test body would be skipped exactly when it is needed most, leaving the
// whole stack without its Deployments navigation.

const SETTINGS = '/admin/governance-domains';

/** A standalone request context: `afterAll` has no `page`, and this must run even after a failure. */
async function restoreBothDomains(): Promise<void> {
  const context = await pwRequest.newContext();
  try {
    const token = await loginViaApi(context, ADMIN_EMAIL, ADMIN_PASSWORD);
    await setGovernanceDomainsViaApi(context, token, {
      governs_apis: true,
      governs_deployments: true,
    });
  } finally {
    await context.dispose();
  }
}

/** Flips the flags out-of-band, so a test can set up a state without driving the form. */
async function setDomains(page: Page, apis: boolean, deployments: boolean): Promise<void> {
  const token = await loginViaApi(page.request, ADMIN_EMAIL, ADMIN_PASSWORD);
  await setGovernanceDomainsViaApi(page.request, token, {
    governs_apis: apis,
    governs_deployments: deployments,
  });
}

test.describe.serial('/admin/governance-domains (#926)', () => {
  test.afterAll(async () => {
    await restoreBothDomains();
  });

  test('renders both switches on for the seeded organization', async ({ page }) => {
    await login(page);
    await page.goto(SETTINGS);

    await expect(page.getByRole('heading', { name: 'Governance domains' })).toBeVisible();
    await expect(page.getByRole('switch', { name: 'Govern outbound API calls' })).toBeChecked();
    await expect(page.getByRole('switch', { name: 'Gate CI/CD deployments' })).toBeChecked();
  });

  test('turning deployments off hides its nav, review tab and dashboard widgets', async ({
    page,
  }) => {
    await login(page);
    await page.goto(SETTINGS);
    await page.getByRole('switch', { name: 'Gate CI/CD deployments' }).click();
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
  });

  test('turning the domain back on restores the navigation', async ({ page }) => {
    await login(page);
    await setDomains(page, true, true);
    await page.goto(SETTINGS);

    await expect(page.getByRole('switch', { name: 'Govern outbound API calls' })).toBeChecked();
    await expect(page.getByRole('switch', { name: 'Gate CI/CD deployments' })).toBeChecked();
    await expect(
      page.getByRole('button', { name: /(Expand|Collapse) Deployments/ }).first(),
    ).toBeVisible();
  });
});
