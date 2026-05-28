import { expect, test, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Unique suffix keeps group names stable across reruns against the long-lived
// e2e Postgres instance.
const SUFFIX = `af353-${Date.now()}`;
const GROUP_NAME = `Billing Reviewers ${SUFFIX}`;
const RENAMED_GROUP_NAME = `Billing Reviewers v2 ${SUFFIX}`;

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

test.describe('AF-353 — user groups admin CRUD', () => {
  test('admin can create, rename, and delete a user group', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    await page.goto('/admin/groups');
    await expect(
      page.getByRole('heading', { name: /User groups/i }),
    ).toBeVisible();

    // Create
    await page.getByRole('button', { name: /Create group/i }).first().click();
    const createDialog = page.getByRole('dialog');
    await createDialog.getByLabel('Name').fill(GROUP_NAME);
    await createDialog
      .getByLabel('Description')
      .fill('Reviews queries against the billing datasource');
    await createDialog.getByRole('button', { name: /Create/i }).click();
    await expect(page.locator(`text=${GROUP_NAME}`).first()).toBeVisible({ timeout: 10_000 });

    // Rename
    const row = page.locator('tr', { hasText: GROUP_NAME });
    await row.getByRole('button', { name: /Edit/i }).click();
    const editDialog = page.getByRole('dialog');
    const nameField = editDialog.getByLabel('Name');
    await nameField.fill('');
    await nameField.fill(RENAMED_GROUP_NAME);
    await editDialog.getByRole('button', { name: /Save/i }).click();
    await expect(page.locator(`text=${RENAMED_GROUP_NAME}`).first()).toBeVisible({
      timeout: 10_000,
    });

    // Delete
    const renamedRow = page.locator('tr', { hasText: RENAMED_GROUP_NAME });
    await renamedRow.getByRole('button', { name: /Delete/i }).click();
    await page.getByRole('button', { name: /^Delete$/ }).click();
    await expect(page.locator(`text=${RENAMED_GROUP_NAME}`)).toHaveCount(0, {
      timeout: 10_000,
    });
  });
});
