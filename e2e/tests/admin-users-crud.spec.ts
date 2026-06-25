import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Shared suffix keeps emails unique across reruns against a long-lived
// database (the e2e stack reuses postgres data between `stack:up` cycles
// when teardown doesn't run with `-v`). The same suffix gates both the
// primary user (created via UI) and the duplicate-email arrange user.
const UNIQUE_SUFFIX = `af275-${Date.now()}`;
const PRIMARY_USER_EMAIL = `e2e-user-${UNIQUE_SUFFIX}@e2e.local`;
const PRIMARY_USER_DISPLAY = `E2E User ${UNIQUE_SUFFIX}`;
const DUPLICATE_USER_EMAIL = `e2e-dupe-${UNIQUE_SUFFIX}@e2e.local`;
const NEW_USER_PASSWORD = 'TestPassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Wait for the GET /api/v1/admin/users that UsersPage issues on mount so the
// Table renders real rows (not the loading skeleton) before we drive the UI.
// Same gate pattern as the AF-274 driver list ready check.
async function waitForUsersListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/users(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

// The PageHeader exposes a Dropdown.Button — the primary button fires
// "Invite via email", and the chevron next to it opens a menu whose sole
// item is "Create with password". The chevron has no accessible name, so
// target it through the AntD class scoped to the dropdown-button group that
// contains "Invite via email" — page header also renders a language
// switcher that uses the same .ant-dropdown-trigger class.
async function openCreateWithPasswordModal(page: Page): Promise<void> {
  const inviteGroup = page.locator('.ant-dropdown-button').filter({
    hasText: 'Invite via email',
  });
  await inviteGroup.locator('button.ant-dropdown-trigger').click();
  await page.getByRole('menuitem', { name: 'Create with password' }).click();
  await expect(page.getByRole('dialog', { name: 'Invite user' })).toBeVisible({
    timeout: 10_000,
  });
}

// Open the row's "⋯" actions menu. The button has `aria-label="Edit"`
// (UsersPage.tsx:387 — admittedly an odd label, but stable). Once the menu
// is open we click the desired item by its visible role.
async function openRowActionsMenu(row: ReturnType<Page['getByRole']>): Promise<void> {
  await row.getByRole('button', { name: 'Edit' }).click();
}

// Drive the AntD Select bound to the "Role" Form.Item: open the listbox by
// clicking the combobox, then click the option labeled with the i18n role
// label ("Analyst", "Reviewer", etc. from enums.role.* in en.json).
async function selectRoleInDialog(page: Page, roleLabel: string): Promise<void> {
  // The combobox is inside the currently-open dialog. There are no other
  // open comboboxes at this point, so .first() inside the dialog is safe.
  const dialog = page.getByRole('dialog');
  await dialog.locator('.ant-select').first().click();
  // The listbox renders detached in a portal, not inside the dialog.
  await page
    .locator('.ant-select-item-option')
    .filter({ hasText: new RegExp(`^${escapeRegex(roleLabel)}$`) })
    .first()
    .click();
}

async function deleteUserViaApi(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/admin/users/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(
      `Admin user cleanup (${id}) returned ${res.status()}: ${await res.text()}`,
    );
  }
}

async function createUserViaApi(
  request: APIRequestContext,
  accessToken: string,
  payload: { email: string; password: string; display_name?: string; role: string },
): Promise<{ id: string }> {
  const res = await request.post(`${apiBase()}/api/v1/admin/users`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: payload,
  });
  if (!res.ok()) {
    throw new Error(`Create user via API failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as { id: string };
}

// AF-275 covers the /admin/users CRUD flows:
//   1. Create-with-password via UI → row appears.
//   2. Edit role via UI (ANALYST → REVIEWER) → row reflects the new role.
//   3. Deactivate via UI confirm-modal → row flips to "inactive".
//   4. Duplicate email rejected with 409 EMAIL_ALREADY_EXISTS.
//   5. Self-deactivate blocked with 422 ILLEGAL_USER_OPERATION.
//
// `describe.serial` so tests 1→3 walk the same user through its lifecycle:
// the row created in test 1 is the row edited in test 2 is the row
// deactivated in test 3. Playwright is already workers=1 in this project,
// so this is mostly belt-and-braces.
test.describe.serial('/admin/users — create + edit + deactivate', () => {
  let adminAccessToken = '';
  let primaryUserId: string | null = null;
  let duplicateUserId: string | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    // Best-effort cleanup. After test 3 the primary user is already
    // deactivated; DELETEing a deactivated user is idempotent (sets
    // active=false again). 404 is swallowed in case a previous step never
    // captured the id.
    if (primaryUserId) {
      await deleteUserViaApi(request, adminAccessToken, primaryUserId);
      primaryUserId = null;
    }
    if (duplicateUserId) {
      await deleteUserViaApi(request, adminAccessToken, duplicateUserId);
      duplicateUserId = null;
    }
  });

  test('1) create user with password (role ANALYST) via UI', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();

    await openCreateWithPasswordModal(page);

    const dialog = page.getByRole('dialog', { name: 'Invite user' });
    await dialog.getByLabel('Email').fill(PRIMARY_USER_EMAIL);
    await dialog.getByLabel('Initial password').fill(NEW_USER_PASSWORD);
    await dialog.getByLabel('Display name').fill(PRIMARY_USER_DISPLAY);
    // Form initialValues default the role to ANALYST, which matches what
    // we want for the create step — we still explicitly select it to make
    // the test self-describing and survive any future default change.
    await selectRoleInDialog(page, 'Analyst');

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/users$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Send invite' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as {
      id: string;
      email: string;
      role: string;
      active: boolean;
    };
    primaryUserId = body.id;
    expect(body.email).toBe(PRIMARY_USER_EMAIL);
    expect(body.role).toBe('ANALYST');
    expect(body.active).toBe(true);

    // Success toast + modal closes + row renders with role "Analyst" and
    // status "active".
    await expect(page.getByText('User created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByRole('dialog', { name: 'Invite user' })).toBeHidden({
      timeout: 10_000,
    });
    const primaryRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(PRIMARY_USER_EMAIL)),
    });
    await expect(primaryRow).toBeVisible();
    await expect(primaryRow.getByText('Analyst', { exact: true })).toBeVisible();
    await expect(primaryRow.getByText('active', { exact: true })).toBeVisible();
  });

  test('2) edit user role from ANALYST to REVIEWER via UI', async ({ page }) => {
    test.skip(!primaryUserId, 'Test 1 must succeed to seed the row');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);

    const primaryRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(PRIMARY_USER_EMAIL)),
    });
    await expect(primaryRow).toBeVisible();

    await openRowActionsMenu(primaryRow);
    await page.getByRole('menuitem', { name: 'Edit' }).click();

    const dialog = page.getByRole('dialog', {
      name: new RegExp(`Edit · ${escapeRegex(PRIMARY_USER_EMAIL)}`),
    });
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    // The select shows the current role label ("Analyst"). Change to "Reviewer".
    await selectRoleInDialog(page, 'Reviewer');

    const updateResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new RegExp(`/api/v1/admin/users/${primaryUserId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save changes' }).click();
    const updateResponse = await updateResponsePromise;
    expect(updateResponse.status()).toBe(200);
    const body = (await updateResponse.json()) as { role: string; active: boolean };
    expect(body.role).toBe('REVIEWER');
    expect(body.active).toBe(true);

    await expect(page.getByText('User updated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(dialog).toBeHidden({ timeout: 10_000 });
    await expect(primaryRow.getByText('Reviewer', { exact: true })).toBeVisible();
    // Should no longer show the old role.
    await expect(primaryRow.getByText('Analyst', { exact: true })).toHaveCount(0);
  });

  test('3) deactivate user via confirm modal → row flips to inactive', async ({
    page,
  }) => {
    test.skip(!primaryUserId, 'Test 1 must succeed to seed the row');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);

    const primaryRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(PRIMARY_USER_EMAIL)),
    });
    await expect(primaryRow).toBeVisible();
    await expect(primaryRow.getByText('active', { exact: true })).toBeVisible();

    await openRowActionsMenu(primaryRow);
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();

    // modal.confirm renders a dialog whose title is the confirm title.
    // Body interpolates the user's email — filter by hasText for that body
    // line so we hit this specific confirm and not any other dialog.
    const confirmDialog = page
      .getByRole('dialog')
      .filter({ hasText: 'Deactivate this user?' });
    await expect(confirmDialog).toBeVisible({ timeout: 10_000 });
    await expect(
      confirmDialog.getByText(
        `${PRIMARY_USER_EMAIL} will be deactivated and signed out of all sessions.`,
      ),
    ).toBeVisible();

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/admin/users/${primaryUserId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await confirmDialog.getByRole('button', { name: 'Deactivate' }).click();
    const deleteResponse = await deleteResponsePromise;
    expect(deleteResponse.status()).toBe(204);

    await expect(page.getByText('User deactivated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    // Row stays in the list but flips to inactive.
    await expect(primaryRow).toBeVisible();
    await expect(primaryRow.getByText('inactive', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
  });

  test('4) creating a user with a duplicate email returns 409 EMAIL_ALREADY_EXISTS', async ({
    page,
    request,
  }) => {
    // Arrange via API so the UI path can focus on the conflict assertion.
    const created = await createUserViaApi(request, adminAccessToken, {
      email: DUPLICATE_USER_EMAIL,
      password: NEW_USER_PASSWORD,
      display_name: `Dupe ${UNIQUE_SUFFIX}`,
      role: 'ANALYST',
    });
    duplicateUserId = created.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);

    await openCreateWithPasswordModal(page);
    const dialog = page.getByRole('dialog', { name: 'Invite user' });
    await dialog.getByLabel('Email').fill(DUPLICATE_USER_EMAIL);
    await dialog.getByLabel('Initial password').fill(NEW_USER_PASSWORD);
    await selectRoleInDialog(page, 'Analyst');

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/users$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Send invite' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(409);
    const body = (await createResponse.json()) as { error?: string };
    expect(body.error).toBe('EMAIL_ALREADY_EXISTS');

    // Toast text comes from errors.email_already_exists.
    await expect(
      page.getByText('A user with that email already exists.'),
    ).toBeVisible({ timeout: 10_000 });

    // Modal stays open so the user can fix the email and retry — same UX
    // contract as the AF-274 invalid-JAR test.
    await expect(dialog).toBeVisible();
  });

  test('5) admin cannot deactivate themselves (422 ILLEGAL_USER_OPERATION)', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);

    const adminRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(ADMIN_EMAIL)),
    });
    await expect(adminRow).toBeVisible();
    await expect(adminRow.getByText('active', { exact: true })).toBeVisible();

    await openRowActionsMenu(adminRow);
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();

    const confirmDialog = page
      .getByRole('dialog')
      .filter({ hasText: 'Deactivate this user?' });
    await expect(confirmDialog).toBeVisible({ timeout: 10_000 });

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        /\/api\/v1\/admin\/users\/[0-9a-f-]{36}$/.test(r.url()),
      { timeout: 15_000 },
    );
    await confirmDialog.getByRole('button', { name: 'Deactivate' }).click();
    const deleteResponse = await deleteResponsePromise;
    expect(deleteResponse.status()).toBe(422);
    const body = (await deleteResponse.json()) as { error?: string };
    expect(body.error).toBe('ILLEGAL_USER_OPERATION');

    // Toast text comes from errors.illegal_user_operation_admin.
    await expect(
      page.getByText(
        'This user change is not permitted (admins cannot demote or deactivate themselves).',
      ),
    ).toBeVisible({ timeout: 10_000 });

    // Row stays active — the deactivation was blocked.
    await expect(adminRow.getByText('active', { exact: true })).toBeVisible();
  });
});
