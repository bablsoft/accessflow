import { expect, test, type Page } from '@playwright/test';

// AF-456: super-admin multi-organization management. The bootstrap admin is provisioned as a
// platform admin (platform_admin=true), so it can reach the cross-org management UI.
const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Unique suffix isolates the created org on reruns against the long-lived e2e Postgres.
const SUFFIX = `af456-${Date.now()}`;
const NEW_ORG_NAME = `Acme ${SUFFIX}`;

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Gate UI assertions on the GET the list page issues on mount.
async function waitForOrgsListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/platform\/organizations(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

test.describe('Platform organization management', () => {
  test('platform admin can create, configure, and disable an organization', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The Platform nav group is visible only to platform admins.
    await page.goto('/admin/organizations');
    await waitForOrgsListReady(page);
    await expect(page.getByRole('heading', { name: 'Organizations' })).toBeVisible();
    // The bootstrap-seeded org is listed.
    await expect(page.getByText('E2E Test Org')).toBeVisible();

    // Create a new organization with quotas.
    await page.getByRole('button', { name: /Add organization/ }).click();
    await expect(page.locator('.ant-modal-title', { hasText: 'Create organization' })).toBeVisible();
    await page.getByLabel('Name').fill(NEW_ORG_NAME);
    await page.getByLabel('Max datasources').fill('3');
    await page.locator('.ant-modal').getByRole('button', { name: 'Create' }).click();

    await expect(page.locator('.ant-modal-title', { hasText: 'Create organization' })).toBeHidden();
    await expect(page.getByText(NEW_ORG_NAME)).toBeVisible();

    // Open its detail page and verify the usage card renders.
    await page.getByRole('button', { name: NEW_ORG_NAME }).click();
    await page.waitForURL('**/admin/organizations/**');
    await expect(page.getByText('Usage')).toBeVisible();
    await expect(page.getByText('Settings & quotas')).toBeVisible();

    // Back to the list, disable then re-enable the org.
    await page.getByRole('button', { name: /Back to organizations/ }).click();
    await waitForOrgsListReady(page);

    const row = page.locator('tr', { hasText: NEW_ORG_NAME });
    await row.getByRole('button', { name: 'Disable' }).click();
    await page.getByRole('button', { name: 'Disable' }).last().click(); // confirm modal
    await expect(row.getByText('Disabled')).toBeVisible();

    await row.getByRole('button', { name: 'Enable' }).click();
    await expect(row.getByText('Enabled')).toBeVisible();
  });
});
