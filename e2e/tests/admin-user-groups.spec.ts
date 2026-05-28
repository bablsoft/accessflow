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

// Wait for the GET /api/v1/admin/groups that GroupsListPage issues on mount so
// the Table (or EmptyState) has real content before we drive the UI.
async function waitForGroupsListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/groups(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

test.describe('AF-353 — user groups admin CRUD', () => {
  test('admin can create, rename, and delete a user group', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    await page.goto('/admin/groups');
    await waitForGroupsListReady(page);
    await expect(
      page.getByRole('heading', { name: /User groups/i }),
    ).toBeVisible();

    // ── Create ────────────────────────────────────────────────────────────────
    await page.getByRole('button', { name: /Create group/i }).first().click();
    const createDialog = page
      .getByRole('dialog')
      .filter({ hasText: 'Create user group' });
    await expect(createDialog).toBeVisible({ timeout: 10_000 });
    await createDialog.getByLabel('Name').fill(GROUP_NAME);
    await createDialog
      .getByLabel('Description')
      .fill('Reviews queries against the billing datasource');
    await createDialog.getByRole('button', { name: /^Create$/ }).click();
    // destroyOnHidden makes sure the dialog is gone from the DOM after success.
    await expect(createDialog).toBeHidden({ timeout: 10_000 });
    await expect(page.locator(`text=${GROUP_NAME}`).first()).toBeVisible({
      timeout: 10_000,
    });

    // ── Rename ────────────────────────────────────────────────────────────────
    const row = page.locator('tr', { hasText: GROUP_NAME });
    await row.getByRole('button', { name: 'Edit' }).click();
    const editDialog = page
      .getByRole('dialog')
      .filter({ hasText: 'Edit user group' });
    await expect(editDialog).toBeVisible({ timeout: 10_000 });
    const nameField = editDialog.getByLabel('Name');
    await nameField.fill(RENAMED_GROUP_NAME);
    await editDialog.getByRole('button', { name: /Save/i }).click();
    await expect(editDialog).toBeHidden({ timeout: 10_000 });
    await expect(page.locator(`text=${RENAMED_GROUP_NAME}`).first()).toBeVisible({
      timeout: 10_000,
    });

    // ── Delete ────────────────────────────────────────────────────────────────
    const renamedRow = page.locator('tr', { hasText: RENAMED_GROUP_NAME });
    await renamedRow.getByRole('button', { name: 'Delete' }).click();
    // AntD Popconfirm renders detached in a portal. Scope to the popover that
    // carries the confirm copy so we don't re-click the row's icon button
    // (both have accessible name "Delete"). Same pattern as admin-ai-configs.
    const confirmPopover = page
      .locator('.ant-popover')
      .filter({ hasText: 'Memberships and reviewer assignments will be removed' });
    await expect(confirmPopover).toBeVisible({ timeout: 10_000 });
    await confirmPopover.getByRole('button', { name: /^Delete$/ }).click();
    await expect(page.locator(`text=${RENAMED_GROUP_NAME}`)).toHaveCount(0, {
      timeout: 10_000,
    });
  });
});
