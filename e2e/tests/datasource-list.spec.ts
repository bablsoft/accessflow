import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// AF-271 covers three regression-prone interactions on /datasources:
//   1. client-side name search narrows the visible cards
//   2. delete reaches the backend and the list reflects the new state
//   3. the empty-state copy renders when the org has no datasources
//
// Three deviations from the issue's literal script — the spec must match the
// implementation, not the issue body:
//
//   * The issue says "click delete on one card → popconfirm". There is no
//     card-level delete control. DsCard's only click target is onClick={onOpen}
//     which navigates to /datasources/:id/settings (DatasourceListPage.tsx).
//     Delete lives on the settings page as a Modal.confirm, not a Popconfirm
//     (DatasourceSettingsPage.tsx:112-122). This spec drives that real flow.
//
//   * The issue's failure case "delete a datasource that has queries against
//     it → backend may 409 → error toast" cannot be tested:
//     DatasourceAdminServiceImpl.deactivate() flips active=false and returns
//     204 unconditionally — there is no dependent-query guard, no 409 branch.
//     Adding one would be a feature change, out of scope for an e2e issue.
//
//   * Soft-delete leaves the card visible with an `inactive` pill rather than
//     removing it (DsCard pill switch lines 168-188 + listForAdmin returns all
//     rows regardless of active). Assert the pill flip, not card absence.

// Shared across the serial tests. Test 2 sets datasourceB = null after the
// UI-driven soft-delete so afterAll skips it (the helper logs a console.warn
// on 404 — quiet teardown is nicer).
let datasourceA: CreatedDatasource | null = null;
let datasourceB: CreatedDatasource | null = null;
let adminAccessToken = '';

// Names embed the suffix so the search-narrows assertion can use a substring
// (ALPHA vs BRAVO) that distinguishes A from B even when prior runs have left
// other `Postgres E2E …` datasources in the org (the e2e stack reuses one
// database between `stack:up` cycles).
const UNIQUE_SUFFIX = `af271-${Date.now()}`;

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/datasources response that backs the list page and
// the page-level "Loading datasources…" copy to disappear. The page also
// fires GET /api/v1/review-plans, but card rendering is gated only on the
// datasources query — don't wait on review-plans.
async function waitForListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/datasources(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
  await expect(page.getByText('Loading datasources…')).toHaveCount(0, {
    timeout: 10_000,
  });
}

test.describe.serial('datasource list — search, delete-via-settings, empty state', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasourceA = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E ${UNIQUE_SUFFIX} ALPHA`,
    });
    datasourceB = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E ${UNIQUE_SUFFIX} BRAVO`,
    });
  });

  test.afterAll(async ({ request }) => {
    // Best-effort — the helper swallows non-204/404 with a console.warn so a
    // stack already torn down (or a row test 2 already deleted) doesn't fail
    // the suite.
    if (datasourceA) {
      await deleteDatasource(request, adminAccessToken, datasourceA.id);
    }
    if (datasourceB) {
      await deleteDatasource(request, adminAccessToken, datasourceB.id);
    }
  });

  test('lists arranged datasources and search narrows by name', async ({ page }) => {
    if (!datasourceA || !datasourceB) throw new Error('beforeAll did not create datasources');

    await loginViaUi(page);
    await page.goto('/datasources');
    await waitForListReady(page);

    // Both cards visible — DsCard renders the datasource name in plain text
    // inside the card body (DatasourceListPage.tsx:155).
    await expect(page.getByText(datasourceA.name, { exact: true })).toBeVisible();
    await expect(page.getByText(datasourceB.name, { exact: true })).toBeVisible();

    // Client-side filter (DatasourceListPage.tsx:35-38) — no network wait
    // needed. Type a substring unique to ALPHA.
    const search = page.getByPlaceholder('Search datasources…');
    await search.fill('ALPHA');
    await expect(page.getByText(datasourceA.name, { exact: true })).toBeVisible();
    await expect(page.getByText(datasourceB.name, { exact: true })).toHaveCount(0);

    // No-match string → grid's filtered.length === 0 branch fires
    // (DatasourceListPage.tsx:97-100). The same empty copy is reused whether
    // the underlying list is empty or just filtered.
    await search.fill('zzz-no-match-zzz');
    await expect(
      page.getByText('No datasources yet — add one to get started.'),
    ).toBeVisible();

    // Reset so the next test inherits a clean filter.
    await search.fill('');
  });

  test('delete via settings page navigates back and marks card inactive', async ({ page }) => {
    if (!datasourceA || !datasourceB) throw new Error('beforeAll did not create datasources');
    const bravoName = datasourceB.name;
    const bravoId = datasourceB.id;

    await loginViaUi(page);
    await page.goto('/datasources');
    await waitForListReady(page);

    // Click the BRAVO card → settings page. DsCard's root <div> handles the
    // click; targeting the visible name is unambiguous.
    await page.getByText(bravoName, { exact: true }).click();
    await page.waitForURL(new RegExp(`/datasources/${bravoId}/settings$`), {
      timeout: 15_000,
    });

    // Page-level "Soft-delete datasource" button (DatasourceSettingsPage.tsx:444).
    await page.getByRole('button', { name: 'Soft-delete datasource' }).click();

    // Modal.confirm renders an AntD dialog. The title is i18n-keyed
    // datasources.settings.delete_confirm_title = "Soft-delete this datasource?".
    const dialog = page.getByRole('dialog').filter({ hasText: 'Soft-delete this datasource?' });
    await expect(dialog).toBeVisible();

    // okText = datasources.settings.soft_delete = "Soft-delete datasource" —
    // the SAME label as the page-level button, so the modal-OK selector must
    // be scoped to the dialog.
    const [deleteResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'DELETE' &&
          new RegExp(`/api/v1/datasources/${bravoId}$`).test(r.url()),
        { timeout: 15_000 },
      ),
      dialog.getByRole('button', { name: 'Soft-delete datasource' }).click(),
    ]);
    expect(deleteResponse.status()).toBe(204);

    // Mutation success calls navigate('/datasources') + invalidateQueries on
    // the list key (DatasourceSettingsPage.tsx:100-104).
    await page.waitForURL('**/datasources', { timeout: 10_000 });
    await waitForListReady(page);

    // message.success(t('datasources.settings.delete_success')).
    await expect(page.getByText('Datasource deactivated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // Soft-delete keeps the card visible with the `inactive` pill swapped in
    // (DsCard active/inactive branch lines 168-188). Chain two filters to
    // match the card uniquely by (name, pill-text).
    const bravoInactiveCard = page
      .locator('div')
      .filter({ hasText: bravoName })
      .filter({ has: page.getByText('inactive', { exact: true }) })
      .first();
    await expect(bravoInactiveCard).toBeVisible();

    // ALPHA must still be active — proves we didn't accidentally tear down both.
    const alphaActiveCard = page
      .locator('div')
      .filter({ hasText: datasourceA.name })
      .filter({ has: page.getByText('active', { exact: true }) })
      .first();
    await expect(alphaActiveCard).toBeVisible();

    // afterAll already handled B; null it out so the helper doesn't re-DELETE
    // a deactivated row (the backend would 404, which the helper just logs —
    // but quiet teardown is nicer).
    datasourceB = null;
  });

  test('empty list renders empty-state copy', async ({ page }) => {
    // Stub the GET to return an empty page. Important: only intercept
    // GET /api/v1/datasources(?...) — fallback for everything else so
    // GET /api/v1/datasources/{id}, GET /api/v1/datasources/{id}/permissions,
    // and the review-plans GET still hit the real backend.
    await page.route('**/api/v1/datasources**', async (route) => {
      const req = route.request();
      const url = req.url();
      if (req.method() !== 'GET' || !/\/api\/v1\/datasources(\?|$)/.test(url)) {
        return route.fallback();
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: [],
          total_elements: 0,
          total_pages: 0,
          page: 0,
          size: 100,
          first: true,
          last: true,
          number_of_elements: 0,
          empty: true,
        }),
      });
    });

    await loginViaUi(page);
    await page.goto('/datasources');
    await waitForListReady(page);

    await expect(
      page.getByText('No datasources yet — add one to get started.', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
    // No cards rendered (every arranged datasource name starts with "Postgres E2E").
    await expect(page.getByText(/Postgres E2E/)).toHaveCount(0);
  });
});
