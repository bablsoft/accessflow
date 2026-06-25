import { test, expect, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  createPostgresDatasource,
  deleteDatasource,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const TARGET_HOST = 'postgres';
const TARGET_DATABASE = 'accessflow';
const TARGET_USERNAME = 'accessflow';
const TARGET_PASSWORD = 'accessflow';
const WRONG_PASSWORD = 'wrong-password-af272';

// Suffix shared across the three tests so the duplicate-name collision is
// unambiguous even when prior runs have left other `Postgres E2E …` rows in
// the org (the e2e stack reuses the database between `stack:up` cycles).
const UNIQUE_SUFFIX = `af272-${Date.now()}`;
const DUPLICATE_NAME = `Compose Postgres ${UNIQUE_SUFFIX}`;

// Issue #272 mentions "EDITOR role" but AccessFlow's role enum is
// ADMIN | REVIEWER | ANALYST (UserRoleType.java). ANALYST is the lowest-priv
// non-admin role and the default the existing invitation modal picks, so it
// is the right stand-in for the "non-admin" scenario.
const ANALYST_EMAIL = `analyst-af272-${Date.now()}@accessflow.test`;
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Reach the "connection" step of the wizard with PostgreSQL selected and the
// VERIFY_FULL → DISABLE flip already applied (the bare postgres:18 container
// in docker-compose.e2e.yml has no TLS — same workaround as the happy-path
// spec at tests/datasource-create-wizard.spec.ts:60-64).
async function openConnectionStep(page: Page): Promise<void> {
  await page.goto('/datasources/new');
  const postgresOption = page.getByRole('radio', { name: /PostgreSQL/i });
  await expect(postgresOption).toBeVisible();
  await postgresOption.click();

  await page.getByRole('combobox', { name: /SSL mode/ }).click();
  await page.locator('.ant-select-item-option').filter({ hasText: /^Disable$/ }).click();
}

// Fill the connection form's required fields. Same `#name` / `#host` / … ids
// the happy-path spec relies on (AntD Form derives input ids from Form.Item
// `name` props).
async function fillConnection(
  page: Page,
  opts: { name: string; password: string },
): Promise<void> {
  await page.locator('#name').fill(opts.name);
  await page.locator('#host').fill(TARGET_HOST);
  await page.locator('#database_name').fill(TARGET_DATABASE);
  await page.locator('#username').fill(TARGET_USERNAME);
  await page.locator('#password').fill(opts.password);
}

// AF-272 covers the three real-world failure modes the happy-path spec doesn't:
//   1. Wrong password at the "Test" step → 422 failure Alert, Next stays disabled.
//   2. Duplicate name at the "Connection" step → 409 toast, wizard stays put.
//   3. Non-admin (ANALYST) visiting /datasources/new → AuthGuard bounces to /editor.
//
// Wired as a serial describe block because tests 2 & 3 depend on state arranged
// in beforeAll (a pre-existing datasource for the name collision and an
// invited ANALYST user for the redirect assertion). Playwright already runs
// workers=1 so this is mostly belt-and-braces.
test.describe.serial('datasource create wizard — failure paths', () => {
  let arrangedDatasource: CreatedDatasource | null = null;
  let testOneDatasourceId: string | null = null;
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // Test 2 — arrange a row whose name the wizard will collide with.
    arrangedDatasource = await createPostgresDatasource(request, adminAccessToken, {
      name: DUPLICATE_NAME,
    });

    // Test 3 — provision an ANALYST via the invitation flow. Mirrors the
    // exchange that auth-invitation.spec.ts drives via UI, but does it via
    // the API helpers because we're testing the AuthGuard redirect, not the
    // invitation UX.
    await purgeMailcrab(request);
    await inviteUserViaApi(
      request,
      adminAccessToken,
      ANALYST_EMAIL,
      'AF-272 Analyst',
      'ANALYST',
    );
    const inviteToken = await waitForInviteToken(request, ANALYST_EMAIL);
    await acceptInvitationViaApi(request, inviteToken, ANALYST_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (arrangedDatasource) {
      await deleteDatasource(request, adminAccessToken, arrangedDatasource.id);
    }
    if (testOneDatasourceId) {
      await deleteDatasource(request, adminAccessToken, testOneDatasourceId);
    }
  });

  test('wrong password keeps Next disabled and renders the failure Alert', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await openConnectionStep(page);

    await fillConnection(page, {
      name: `Postgres E2E ${UNIQUE_SUFFIX} BAD-CREDS`,
      password: WRONG_PASSWORD,
    });

    // "Save and test" POSTs /api/v1/datasources — the row is persisted with
    // the (wrong) password before the wizard advances to the test step. Grab
    // the id so afterAll can delete it.
    const [createResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          /\/api\/v1\/datasources(\?|$)/.test(r.url()),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: 'Save and test' }).click(),
    ]);
    expect(createResponse.status()).toBe(201);
    const created = (await createResponse.json()) as { id: string };
    testOneDatasourceId = created.id;

    const testButton = page.getByRole('button', { name: 'Test connection' });
    await expect(testButton).toBeVisible({ timeout: 15_000 });

    // Click Test → expect 422 from POST /api/v1/datasources/{id}/test.
    const [testResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          /\/api\/v1\/datasources\/[0-9a-f-]{36}\/test$/.test(r.url()),
        { timeout: 15_000 },
      ),
      testButton.click(),
    ]);
    expect(testResponse.status()).toBe(422);

    // Failure Alert title comes from the frontend i18n key
    // datasources.create.test_failure → "Connection failed"
    // (ConnectionTester.tsx:74-80). The description echoes the backend
    // ProblemDetail.detail; we only assert the title so the spec doesn't
    // couple to backend English copy.
    await expect(page.getByText('Connection failed', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // Next is gated on testResult.ok (only setTestResult enables it —
    // DatasourceCreateWizardPage.tsx:163-181). A failed test leaves it
    // disabled.
    await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled();
  });

  test('duplicate name surfaces the 409 toast and stays on the connection step', async ({ page }) => {
    if (!arrangedDatasource) throw new Error('beforeAll did not arrange the duplicate-name target');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await openConnectionStep(page);

    // Reuse the exact arranged name so the (organization_id, name) uniqueness
    // constraint fires on the next POST.
    await fillConnection(page, {
      name: arrangedDatasource.name,
      password: TARGET_PASSWORD,
    });

    const [createResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          /\/api\/v1\/datasources(\?|$)/.test(r.url()),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: 'Save and test' }).click(),
    ]);
    expect(createResponse.status()).toBe(409);

    // The 409 error code DATASOURCE_NAME_ALREADY_EXISTS is mapped to a
    // toast via datasourceCreateErrorMessage → showApiError
    // (apiErrors.ts:181-186, showApiError.tsx:24). The English copy is from
    // i18n key errors.datasource_name_already_exists.
    await expect(
      page.getByText(
        'A datasource with that name already exists in your organization. Pick a different name.',
        { exact: true },
      ),
    ).toBeVisible({ timeout: 10_000 });

    // Wizard must remain on the connection step — the "Save and test"
    // button is still the primary CTA; the test step's "Test connection"
    // button is NOT visible yet.
    await expect(page.getByRole('button', { name: 'Save and test' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Test connection' })).toHaveCount(0);
  });

  test('non-admin (ANALYST) visiting /datasources/new is redirected to their role home', async ({ page }) => {
    await loginViaUi(page, ANALYST_EMAIL, ANALYST_PASSWORD);

    await page.goto('/datasources/new');

    // AuthGuard with requireRole="ADMIN" replaces the wizard with a
    // <Navigate to={homePathForRole(role)} replace /> when the logged-in user isn't an
    // admin — /dashboard for a non-auditor. The redirect is silent — no 403 page.
    await expect(page).toHaveURL(/\/dashboard$/, { timeout: 10_000 });

    // Sanity check: the wizard's page title ("Add datasource", i18n key
    // datasources.create.title) must NOT be rendered.
    await expect(page.getByText('Add datasource', { exact: true })).toHaveCount(0);
  });
});
