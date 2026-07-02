import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  createPostgresDatasource,
  deleteDatasource,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
  type CreatedDatasource,
} from '../helpers/datasources';

async function createGroupViaApi(
  request: APIRequestContext,
  token: string,
  name: string,
): Promise<string> {
  const res = await request.post(`${apiBase()}/api/v1/admin/groups`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { name },
  });
  if (!res.ok()) {
    throw new Error(`create group failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()).id as string;
}

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Shared suffix lets the duplicate-name collision in test 2 stay unambiguous
// even when prior runs have left other `Postgres E2E …` rows in the org (the
// e2e stack keeps the database between `stack:up` cycles).
const UNIQUE_SUFFIX = `af273-${Date.now()}`;

const X_INITIAL_NAME = `Postgres E2E ${UNIQUE_SUFFIX} NAME-UPDATE`;
const X_RENAMED = `Postgres E2E ${UNIQUE_SUFFIX} RENAMED`;
const Y_NAME = `Postgres E2E ${UNIQUE_SUFFIX} DUPE-TARGET`;
const Z_NAME = `Postgres E2E ${UNIQUE_SUFFIX} BAD-CREDS`;
const A_NAME = `Postgres E2E ${UNIQUE_SUFFIX} PERMS`;
const B_NAME = `Postgres E2E ${UNIQUE_SUFFIX} GROUP-PERMS`;
const GROUP_NAME = `AF-530 Group ${UNIQUE_SUFFIX}`;
const WRONG_PASSWORD = 'wrong-password-af273';

const ANALYST_DISPLAY = 'AF-273 Analyst';
const ANALYST_EMAIL = `analyst-af273-${Date.now()}@accessflow.test`;
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Wait for the GET /api/v1/datasources/{id} that backs the settings page so
// the Configuration tab is rendered with initialValues populated. Mirrors the
// "page is interactive" gate datasource-list.spec.ts uses for the list view.
async function waitForSettingsReady(page: Page, dsId: string): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new RegExp(`/api/v1/datasources/${dsId}$`).test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
  await expect(page.getByText('Loading datasource…')).toHaveCount(0, { timeout: 10_000 });
}

async function waitForListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/datasources(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
  await expect(page.getByText('Loading datasources…')).toHaveCount(0, { timeout: 10_000 });
}

// AF-273 covers six scenarios on /datasources/:id/settings:
//   1. Update the datasource name → toast → list invalidation.
//   2. Update with a duplicate name → 409 → error toast.
//   3. Test-connection against a row with a wrong password → 422 → toast.
//   4. Permissions tab → Grant access modal → user appears in table.
//   5. Grant a permission that already exists → 409 → error toast,
//      table unchanged. (POST is mocked — see the comment on test 5.)
//   6. Revoke → confirm modal → row removed.
//
// Three deviations from the issue's literal script — the spec matches the
// implementation, not the issue body:
//
//   * Test 2 ("Update with a duplicate name → 409 → error banner"). The
//     settings page's update mutation onError now routes through
//     `showApiError` + `apiErrorMessage` (AF-556), so the toast surfaces the
//     backend's localized ProblemDetail `detail` ("A datasource with that name
//     already exists") rather than the generic `datasources.settings.save_error`
//     fallback. We assert the backend detail copy.
//
//   * Test 3 ("Test-connection with wrong password → failure alert"). The
//     wizard renders an AntD Alert, but the settings page's testMutation
//     onError shows a message.error toast. Since AF-556 that toast surfaces the
//     backend ProblemDetail `detail` ("Connection test failed") via
//     showApiError + apiErrorMessage, not the frontend `connection_failed`
//     copy. We assert the backend detail toast.
//
//   * Test 5 ("Grant a permission that already exists → 409"). PermissionMatrix's
//     eligible-users filter strips already-granted users from the dropdown
//     (DatasourceSettingsPage.tsx:686), so we cannot pick the analyst from
//     test 4 to trigger a real backend 409. We mock the POST with
//     page.route → 409 + DATASOURCE_PERMISSION_ALREADY_EXISTS body, then
//     drive the modal with any other eligible user. This exercises the
//     frontend error-mapping path (`datasourceGrantErrorMessage` →
//     `showApiError` in apiErrors.ts:162-178) — which is the real product
//     behaviour the issue's failure case is asking us to cover.

test.describe.serial('datasource settings — config + permissions', () => {
  let datasourceX: CreatedDatasource | null = null;
  let datasourceY: CreatedDatasource | null = null;
  let datasourceZ: CreatedDatasource | null = null;
  let datasourceA: CreatedDatasource | null = null;
  let datasourceB: CreatedDatasource | null = null;
  let groupId = '';
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    datasourceX = await createPostgresDatasource(request, adminAccessToken, {
      name: X_INITIAL_NAME,
    });
    datasourceY = await createPostgresDatasource(request, adminAccessToken, {
      name: Y_NAME,
    });
    datasourceZ = await createPostgresDatasource(request, adminAccessToken, {
      name: Z_NAME,
      password: WRONG_PASSWORD,
    });
    datasourceA = await createPostgresDatasource(request, adminAccessToken, {
      name: A_NAME,
    });
    datasourceB = await createPostgresDatasource(request, adminAccessToken, {
      name: B_NAME,
    });
    groupId = await createGroupViaApi(request, adminAccessToken, GROUP_NAME);

    // Provision an ANALYST so the grant flow has a target user other than
    // the admin (admin holds implicit access and is omitted from the grant
    // dropdown's "taken" check anyway — but the analyst is the realistic
    // grantee). Mirrors the invitation arrangement from
    // datasource-create-failures.spec.ts.
    await purgeMailcrab(request);
    await inviteUserViaApi(
      request,
      adminAccessToken,
      ANALYST_EMAIL,
      ANALYST_DISPLAY,
      'ANALYST',
    );
    const inviteToken = await waitForInviteToken(request, ANALYST_EMAIL);
    await acceptInvitationViaApi(request, inviteToken, ANALYST_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    // Best-effort — the helper swallows non-204/404 with a console.warn so a
    // stack already torn down (or a row a test already deactivated) doesn't
    // fail the suite.
    for (const ds of [datasourceX, datasourceY, datasourceZ, datasourceA, datasourceB]) {
      if (ds) {
        await deleteDatasource(request, adminAccessToken, ds.id);
      }
    }
    if (groupId) {
      await request
        .delete(`${apiBase()}/api/v1/admin/groups/${groupId}`, {
          headers: { Authorization: `Bearer ${adminAccessToken}` },
        })
        .catch(() => undefined);
    }
  });

  test('update name persists, toasts success, and invalidates the list', async ({ page }) => {
    if (!datasourceX) throw new Error('beforeAll did not create datasource X');
    const dsId = datasourceX.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    // The Configuration tab is the default active tab — the name field is
    // rendered immediately.
    const nameInput = page.locator('#name');
    await expect(nameInput).toHaveValue(X_INITIAL_NAME);
    await nameInput.fill(X_RENAMED);

    const [putResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new RegExp(`/api/v1/datasources/${dsId}$`).test(r.url()),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: 'Save changes' }).click(),
    ]);
    expect(putResponse.status()).toBe(200);

    // message.success(t('datasources.settings.save_success')).
    await expect(page.getByText('Datasource updated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // List invalidation — navigate to /datasources and assert the renamed
    // card is rendered (proves invalidateQueries on lists() refetched).
    await page.goto('/datasources');
    await waitForListReady(page);
    await expect(page.getByText(X_RENAMED, { exact: true })).toBeVisible();
    await expect(page.getByText(X_INITIAL_NAME, { exact: true })).toHaveCount(0);
  });

  test('updating to a duplicate name surfaces the 409 toast', async ({ page }) => {
    if (!datasourceX || !datasourceY) {
      throw new Error('beforeAll did not create datasources X and Y');
    }
    const dsId = datasourceX.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    // After test 1 the persisted name is X_RENAMED. Fill Y's name to collide
    // with the (organization_id, name) uniqueness constraint.
    const nameInput = page.locator('#name');
    await expect(nameInput).toHaveValue(X_RENAMED);
    await nameInput.fill(Y_NAME);

    const [putResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new RegExp(`/api/v1/datasources/${dsId}$`).test(r.url()),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: 'Save changes' }).click(),
    ]);
    expect(putResponse.status()).toBe(409);

    // updateMutation.onError surfaces the backend ProblemDetail `detail` via
    // showApiError + apiErrorMessage (AF-556). See deviation note in the
    // file-level comment.
    await expect(page.getByText('A datasource with that name already exists')).toBeVisible({
      timeout: 10_000,
    });
  });

  test('test-connection with wrong password renders the failure toast', async ({ page }) => {
    if (!datasourceZ) throw new Error('beforeAll did not create datasource Z');
    const dsId = datasourceZ.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    // Page-header "Test connection" button (DatasourceSettingsPage.tsx:176-181).
    const [testResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new RegExp(`/api/v1/datasources/${dsId}/test$`).test(r.url()),
        { timeout: 20_000 },
      ),
      page.getByRole('button', { name: 'Test connection' }).click(),
    ]);
    expect(testResponse.status()).toBe(422);

    // testMutation.onError surfaces the backend ProblemDetail `detail` via
    // showApiError + apiErrorMessage (AF-556); the 422
    // DATASOURCE_CONNECTION_TEST_FAILED detail is the localized
    // `error.datasource_connection_test_failed` = "Connection test failed". See
    // deviation note in the file-level comment for why we assert a toast rather
    // than an Alert.
    await expect(page.getByText('Connection test failed')).toBeVisible({
      timeout: 10_000,
    });
  });

  test('grants access to a user and the row appears in the permissions table', async ({
    page,
  }) => {
    if (!datasourceA) throw new Error('beforeAll did not create datasource A');
    const dsId = datasourceA.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    // Switch to the Permissions tab — label is `Permissions · 0` initially.
    // GET /permissions fires on page mount (parent permissionsQuery), parallel
    // with GET /datasources/{id}; an explicit waitForResponse here would race
    // (the response may already have arrived). The page-level "Grant access"
    // button is always rendered, so we use its visibility as the signal that
    // the tab content is mounted and click straight through.
    await page.getByRole('tab', { name: /^Permissions · 0$/ }).click();
    await expect(page.getByRole('button', { name: 'Grant access' })).toBeVisible();

    // Open the grant modal. The page-level button and the modal OK button
    // share the same English label ("Grant access"), so scope the second
    // click to the dialog.
    await page
      .getByRole('button', { name: 'Grant access' })
      .first()
      .click();
    const grantDialog = page
      .getByRole('dialog')
      .filter({ hasText: 'Grant datasource access' });
    await expect(grantDialog).toBeVisible();

    // The User Select renders options labelled `${display_name} (${email})`
    // when display_name is set. Click the combobox, type the email so the
    // AntD optionFilterProp="label" narrows the list to one match, then pick.
    const userCombobox = grantDialog.getByRole('combobox', { name: 'User' });
    await userCombobox.click();
    await userCombobox.fill(ANALYST_EMAIL);
    await page
      .locator('.ant-select-item-option')
      .filter({ hasText: ANALYST_EMAIL })
      .click();

    // Toggle break-glass on. The switch is the one whose Form.Item carries the
    // grant_perm_break_glass_label ("Can break-glass"); scope to its form item
    // so the read/write/DDL switches aren't matched.
    await grantDialog
      .locator('.ant-form-item')
      .filter({ hasText: 'Can break-glass' })
      .getByRole('switch')
      .click();

    // Submit. The modal OK button label is i18n grant_submit = "Grant access".
    const [grantResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new RegExp(`/api/v1/datasources/${dsId}/permissions$`).test(r.url()),
        { timeout: 15_000 },
      ),
      grantDialog.getByRole('button', { name: 'Grant access' }).click(),
    ]);
    expect(grantResponse.status()).toBe(201);
    // The POST body carries the break-glass capability.
    expect(grantResponse.request().postDataJSON().can_break_glass).toBe(true);

    // message.success(t('datasources.settings.grant_success')).
    await expect(page.getByText('Access granted', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // Modal closes after a successful grant (onSuccess → onClose()).
    await expect(grantDialog).toHaveCount(0);

    // Row visible — scope to the AntD table row so the duplicated email in
    // the user column (label + subtitle) doesn't trip strict-mode.
    const analystRow = page.locator('.ant-table-row').filter({ hasText: ANALYST_EMAIL });
    await expect(analystRow).toHaveCount(1, { timeout: 10_000 });

    // The Break-glass column shows the capability as enabled (PermCell check
    // icon). Resolve the column index from the header so the assertion is tied
    // to the right column, not a count of all check icons in the row.
    const headerCells = page.locator('.ant-table-thead th');
    const headerTexts = await headerCells.allInnerTexts();
    const bgIndex = headerTexts.findIndex((tx) => tx.trim() === 'Break-glass');
    expect(bgIndex).toBeGreaterThanOrEqual(0);
    await expect(analystRow.locator('td').nth(bgIndex).locator('.anticon-check')).toBeVisible();

    // Tab label flips to `Permissions · 1` once the permissions query refetches.
    await expect(page.getByRole('tab', { name: /^Permissions · 1$/ })).toBeVisible({
      timeout: 10_000,
    });
  });

  test('granting a duplicate permission shows the 409 toast and leaves the table unchanged', async ({
    page,
  }) => {
    if (!datasourceA) throw new Error('beforeAll did not create datasource A');
    const dsId = datasourceA.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    // Switch to Permissions tab. Label is now `Permissions · 1` (test 4 left
    // one grant behind). Wait for the analyst row instead of the GET — the
    // GET fires on mount and can land before any waitForResponse hook.
    await page.getByRole('tab', { name: /^Permissions · 1$/ }).click();
    const analystRow = page.locator('.ant-table-row').filter({ hasText: ANALYST_EMAIL });
    await expect(analystRow).toHaveCount(1, { timeout: 10_000 });

    // Mock the next POST /permissions → 409 with the real backend's error
    // code so datasourceGrantErrorMessage maps it to the
    // `errors.datasource_permission_already_exists` toast copy.
    const grantUrlRe = new RegExp(`/api/v1/datasources/${dsId}/permissions$`);
    await page.route('**/api/v1/datasources/**/permissions', async (route) => {
      const req = route.request();
      if (req.method() === 'POST' && grantUrlRe.test(req.url())) {
        await route.fulfill({
          status: 409,
          contentType: 'application/problem+json',
          body: JSON.stringify({
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            detail: 'Permission already exists for this user on this datasource',
            error: 'DATASOURCE_PERMISSION_ALREADY_EXISTS',
          }),
        });
        return;
      }
      return route.fallback();
    });

    // Open the modal and pick any eligible user — the admin always shows up
    // because admins have no explicit permission rows. The analyst from
    // test 4 is filtered out of the dropdown (existingUserIds filter).
    await page
      .getByRole('button', { name: 'Grant access' })
      .first()
      .click();
    const grantDialog = page
      .getByRole('dialog')
      .filter({ hasText: 'Grant datasource access' });
    await expect(grantDialog).toBeVisible();

    const userCombobox = grantDialog.getByRole('combobox', { name: 'User' });
    await userCombobox.click();
    await userCombobox.fill(ADMIN_EMAIL);
    await page
      .locator('.ant-select-item-option')
      .filter({ hasText: ADMIN_EMAIL })
      .click();

    const [grantResponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === 'POST' && grantUrlRe.test(r.url()),
        { timeout: 15_000 },
      ),
      grantDialog.getByRole('button', { name: 'Grant access' }).click(),
    ]);
    expect(grantResponse.status()).toBe(409);

    // showApiError → datasourceGrantErrorMessage →
    // errors.datasource_permission_already_exists toast copy.
    await expect(
      page.getByText('This user already has a permission row for this datasource.', {
        exact: true,
      }),
    ).toBeVisible({ timeout: 10_000 });

    // Close the modal — the failed mutation leaves it open (onSuccess →
    // onClose is the only close path other than the user's Cancel).
    await grantDialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(grantDialog).toHaveCount(0);

    // Table still shows exactly the analyst row from test 4; the admin row
    // is not present.
    await expect(analystRow).toHaveCount(1);
    await expect(
      page.locator('.ant-table-row').filter({ hasText: ADMIN_EMAIL }),
    ).toHaveCount(0);
    await expect(page.getByRole('tab', { name: /^Permissions · 1$/ })).toBeVisible();

    // Restore live network for the next test.
    await page.unroute('**/api/v1/datasources/**/permissions');
  });

  test('revoke removes the analyst row and flips the tab back to zero', async ({ page }) => {
    if (!datasourceA) throw new Error('beforeAll did not create datasource A');
    const dsId = datasourceA.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    await page.getByRole('tab', { name: /^Permissions · 1$/ }).click();
    // Wait for the analyst row to render — proves the permissions GET landed.
    const analystRow = page.locator('.ant-table-row').filter({ hasText: ANALYST_EMAIL });
    await expect(analystRow).toHaveCount(1, { timeout: 10_000 });

    // Row's "Revoke" button (perm_revoke). Only one row exists so the
    // selector is unambiguous.
    await page.getByRole('button', { name: 'Revoke' }).click();

    // modal.confirm with title "Revoke this user's access?" — okText "Revoke"
    // matches the row button label, so scope the OK click to the dialog.
    const dialog = page
      .getByRole('dialog')
      .filter({ hasText: "Revoke this user's access?" });
    await expect(dialog).toBeVisible();

    const [deleteResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'DELETE' &&
          new RegExp(`/api/v1/datasources/${dsId}/permissions/[0-9a-f-]{36}$`).test(r.url()),
        { timeout: 15_000 },
      ),
      dialog.getByRole('button', { name: 'Revoke' }).click(),
    ]);
    expect(deleteResponse.status()).toBe(204);

    // message.success(t('datasources.settings.revoke_success')).
    await expect(page.getByText('Access revoked', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // Row gone, tab label flips back to zero.
    await expect(analystRow).toHaveCount(0);
    await expect(page.getByRole('tab', { name: /^Permissions · 0$/ })).toBeVisible({
      timeout: 10_000,
    });
  });

  // AF-530: grant a whole group access; the group grant renders in its own
  // section of the permissions tab with the member count.
  test('grants access to a group and the group row appears', async ({ page }) => {
    if (!datasourceB) throw new Error('beforeAll did not create datasource B');
    const dsId = datasourceB.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    await waitForSettingsReady(page, dsId);

    await page.getByRole('tab', { name: /^Permissions · 0$/ }).click();
    await expect(page.getByRole('button', { name: 'Grant access' })).toBeVisible();

    await page.getByRole('button', { name: 'Grant access' }).first().click();
    const grantDialog = page.getByRole('dialog').filter({ hasText: 'Grant datasource access' });
    await expect(grantDialog).toBeVisible();

    // Flip the User / Group toggle to Group, then the user Select is swapped
    // for the group Select.
    await grantDialog.locator('.ant-segmented-item').filter({ hasText: 'Group' }).click();
    const groupCombobox = grantDialog.getByRole('combobox', { name: 'Group' });
    await groupCombobox.click();
    await groupCombobox.fill(GROUP_NAME);
    await page.locator('.ant-select-item-option').filter({ hasText: GROUP_NAME }).click();

    const [grantResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new RegExp(`/api/v1/datasources/${dsId}/permissions/groups$`).test(r.url()),
        { timeout: 15_000 },
      ),
      grantDialog.getByRole('button', { name: 'Grant access' }).click(),
    ]);
    expect(grantResponse.status()).toBe(201);
    expect(grantResponse.request().postDataJSON().group_id).toBe(groupId);

    await expect(page.getByText('Access granted', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(grantDialog).toHaveCount(0);

    // The group grant renders under the "Group grants" section with its name.
    await expect(page.getByText('Group grants', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    const groupRow = page.locator('.ant-table-row').filter({ hasText: GROUP_NAME });
    await expect(groupRow).toHaveCount(1, { timeout: 10_000 });
  });
});
