import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  createPostgresDatasource,
  deleteDatasource,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const SUBMITTER_PASSWORD = 'BreakGlass-Pwd!123';

async function meIdViaApi(request: APIRequestContext, token: string): Promise<string> {
  const res = await request.get(`${apiBase()}/api/v1/me`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) throw new Error(`GET /me failed: ${res.status()} ${await res.text()}`);
  return ((await res.json()) as { id: string }).id;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.type(sql, { delay: 20 });
  await page.keyboard.press('Escape');
}

async function pickDatasource(page: Page, ds: CreatedDatasource): Promise<void> {
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page.locator('.ant-select-item-option').filter({ hasText: ds.name }).click();
  await page.keyboard.press('Escape');
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('break-glass / emergency access (AF-385)', () => {
  let adminAccessToken = '';
  let submitterEmail = '';
  let datasource: CreatedDatasource | null = null;
  const justification = `prod is on fire ${randomUUID()}`;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The submitter must differ from the acknowledger (self-acknowledge is banned),
    // so provision a fresh ANALYST as the break-glass submitter and let the bootstrap
    // admin reconcile. A fresh user per run avoids the email uniqueness constraint.
    submitterEmail = `breakglass-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminAccessToken, submitterEmail, 'AF-385 Submitter', 'ANALYST');
    const inviteToken = await waitForInviteToken(request, submitterEmail);
    await acceptInvitationViaApi(request, inviteToken, SUBMITTER_PASSWORD, 'AF-385 Submitter');
    const submitterToken = await loginViaApi(request, submitterEmail, SUBMITTER_PASSWORD);
    const submitterId = await meIdViaApi(request, submitterToken);

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF385 ${Date.now()}`,
    });

    // Break-glass requires an explicit can_break_glass grant — for everyone.
    await grantPermissionViaApi(request, adminAccessToken, datasource.id, submitterId, {
      canRead: true,
      canBreakGlass: true,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('eligible user breaks glass → query executes immediately', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page, submitterEmail, SUBMITTER_PASSWORD);
    await page.goto('/editor');
    await pickDatasource(page, datasource);
    await page.waitForLoadState('networkidle');
    await typeInEditor(page, 'SELECT 1');

    // The Emergency access button is gated by GET /me/break-glass eligibility.
    const breakGlassButton = page.getByTestId('break-glass-button');
    await expect(breakGlassButton).toBeVisible({ timeout: 15_000 });
    await breakGlassButton.click();

    // Confirmation modal forces a justification before the danger confirm enables.
    const confirm = page.getByTestId('break-glass-confirm');
    await expect(confirm).toBeDisabled();
    await page.getByTestId('break-glass-justification').fill(justification);
    await expect(confirm).toBeEnabled();
    await confirm.click();

    // Synchronous execution → lands on the executed query detail page.
    await page.waitForURL(/\/queries\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Executed'),
    ).toBeVisible({ timeout: 15_000 });
  });

  test('admin sees the unreconciled event and acknowledges it', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/break-glass');

    // Default filter is PENDING_REVIEW (unreconciled). The row carries the justification.
    const row = page.getByRole('row').filter({ hasText: justification });
    await expect(row).toBeVisible({ timeout: 15_000 });

    await row.getByRole('button', { name: 'Acknowledge' }).click();

    // The acknowledge modal opens; confirm closes the retro-review.
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('button', { name: 'Acknowledge' }).click();

    await expect(page.getByText('Break-glass event acknowledged')).toBeVisible({
      timeout: 15_000,
    });
  });
});
