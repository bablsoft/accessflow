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

test.describe('Platform organization management', () => {
  test('platform admin can create, configure, and disable an organization', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The Platform nav group is visible only to platform admins. Wait on rendered content
    // (not a network response) so the assertions survive TanStack Query's client-side cache.
    await page.goto('/admin/organizations');
    await expect(page.getByRole('heading', { name: 'Organizations' })).toBeVisible();
    // The bootstrap-seeded org loads and renders.
    await expect(page.getByText('E2E Test Org')).toBeVisible({ timeout: 15_000 });

    // Create a new organization with quotas.
    await page.getByRole('button', { name: /Add organization/ }).click();
    await expect(page.locator('.ant-modal-title', { hasText: 'Create organization' })).toBeVisible();
    await page.getByLabel('Name').fill(NEW_ORG_NAME);
    await page.getByLabel('Max datasources').fill('3');
    await page.locator('.ant-modal').getByRole('button', { name: 'Create' }).click();

    await expect(page.locator('.ant-modal-title', { hasText: 'Create organization' })).toBeHidden();
    await expect(page.getByText(NEW_ORG_NAME)).toBeVisible();

    // Open its detail page and verify the usage + settings cards render.
    await page.getByRole('button', { name: NEW_ORG_NAME }).click();
    await page.waitForURL('**/admin/organizations/**');
    await expect(page.getByText('Usage')).toBeVisible();
    await expect(page.getByText('Settings & quotas')).toBeVisible();

    // Back to the list (SPA navigation — the list renders from cache, no refetch).
    await page.getByRole('button', { name: /Back to organizations/ }).click();
    await page.waitForURL((url) => url.pathname === '/admin/organizations');
    const row = page.locator('tr', { hasText: NEW_ORG_NAME });
    await expect(row).toBeVisible();

    // Disable the org (confirm in the AntD modal.confirm dialog), then re-enable it.
    await row.getByRole('button', { name: 'Disable' }).click();
    const confirmModal = page
      .getByRole('dialog')
      .filter({ hasText: 'Disable this organization?' })
      .first();
    await expect(confirmModal).toBeVisible({ timeout: 10_000 });
    await confirmModal.getByRole('button', { name: 'Disable' }).click();
    await expect(row.getByText('Disabled')).toBeVisible();

    await row.getByRole('button', { name: 'Enable' }).click();
    await expect(row.getByText('Enabled')).toBeVisible();
  });
});
