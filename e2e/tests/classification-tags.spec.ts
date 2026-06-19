import { expect, test, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('data classification tagging (AF-447)', () => {
  let adminToken = '';
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E Classification ${Date.now()}`,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  test('tagging a column via the Classification tab derives a masking policy', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);

    await page.getByRole('tab', { name: /Classification/ }).click();
    await page.getByRole('button', { name: 'Add tag' }).click();

    const dialog = page.getByRole('dialog');
    const noteField = dialog.getByLabel('Note');

    // Free-text table + column (no dependency on the introspected schema). Click the
    // Note field between entries to dismiss any open AutoComplete/Select dropdown so it
    // can't intercept the next interaction (Escape would close the whole modal).
    await dialog.getByRole('combobox').nth(0).fill('public.users');
    await noteField.click();
    await dialog.getByRole('combobox').nth(1).fill('email');
    await noteField.click();
    // Multi-select: type to filter then Enter to pick the highlighted option. A direct
    // click on the rendered <div role="option"> is flaky — AntD's virtual-list dropdown
    // re-renders/repositions, so the option never settles into an actionable state.
    const classificationSelect = dialog.getByRole('combobox').nth(2);
    await classificationSelect.click();
    await classificationSelect.fill('PII');
    await page.keyboard.press('Enter');
    await noteField.click();

    await dialog.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByText('Classification tag added')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('public.users.email').first()).toBeVisible({ timeout: 10_000 });

    // The PII column tag auto-applied a masking policy — visible on the Masking tab.
    await page.getByRole('tab', { name: /Masking/ }).click();
    await expect(page.getByText('public.users.email').first()).toBeVisible({ timeout: 10_000 });
  });

  test('deleting a tag keeps the derived masking policy', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);

    await page.getByRole('tab', { name: /Classification/ }).click();
    await expect(page.getByText('public.users.email').first()).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Delete' }).first().click();
    const confirm = page.getByRole('dialog').filter({ hasText: 'Remove this classification tag?' });
    await confirm.getByRole('button', { name: 'Delete' }).click();
    await expect(page.getByText('Classification tag removed')).toBeVisible({ timeout: 10_000 });

    // Non-cascade: the derived masking policy survives.
    await page.getByRole('tab', { name: /Masking/ }).click();
    await expect(page.getByText('public.users.email').first()).toBeVisible({ timeout: 10_000 });
  });
});
