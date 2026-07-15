import { test, expect, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  createRoleViaApi,
  deleteRoleViaApi,
  inviteUserViaApi,
  listRolesViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

// AF-522 — custom roles composed from the fixed permission catalog:
//   1. Admin creates a custom role through the /admin/roles permission-matrix UI.
//   2. A user invited with that role gets exactly its permissions: the sidebar
//      shows only the granted surfaces and AuthGuard blocks the rest — a mix
//      (query editor + audit log, but no user management) that none of the five
//      system roles could express.
//   3. System roles are immutable and an in-use custom role cannot be deleted.
const UNIQUE_SUFFIX = `af522-${Date.now()}`;
const ROLE_NAME = `E2E Steward ${UNIQUE_SUFFIX}`;
const STEWARD_EMAIL = `steward-${UNIQUE_SUFFIX}@e2e.local`;
const STEWARD_DISPLAY = `AF522 Steward ${UNIQUE_SUFFIX}`;
const STEWARD_PASSWORD = 'StewardPass!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

test.describe.serial('custom roles & permissions (AF-522)', () => {
  let adminToken = '';
  let roleId: string | null = null;
  let stewardUserId: string | null = null;

  test.beforeAll(async ({ request }) => {
    await purgeMailcrab(request);
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (stewardUserId) {
      const res = await request.delete(
        `${apiBase()}/api/v1/admin/users/${stewardUserId}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      if (!res.ok() && res.status() !== 404) {
        // eslint-disable-next-line no-console
        console.warn(`Steward cleanup returned ${res.status()}`);
      }
    }
    if (roleId) {
      await deleteRoleViaApi(request, adminToken, roleId);
    }
  });

  test('1) admin creates a custom role via the permission-matrix UI', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/roles');
    await expect(page.getByRole('heading', { name: 'Roles' })).toBeVisible({
      timeout: 15_000,
    });

    // The five immutable system roles render with the System tag.
    const adminRow = page.getByRole('row', { name: /Admin/ }).first();
    await expect(adminRow.getByText('System', { exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Create role' }).click();
    const drawer = page.getByRole('dialog');
    await expect(drawer).toBeVisible({ timeout: 10_000 });

    await drawer.getByLabel('Name').fill(ROLE_NAME);
    await drawer.getByLabel('Description').fill('Query steward with audit visibility');
    // A capability mix no system role offers: submit queries + view the audit
    // log, without any user-management permission.
    await drawer.getByRole('checkbox', { name: 'Submit SELECT queries' }).check();
    await drawer.getByRole('checkbox', { name: 'Submit DML queries' }).check();
    await drawer.getByRole('checkbox', { name: 'View the audit log' }).check();

    const createResponsePromise = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/api\/v1\/admin\/roles$/.test(r.url()),
      { timeout: 15_000 },
    );
    await drawer.getByRole('button', { name: 'Save' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as {
      id: string;
      name: string;
      system: boolean;
      permissions: string[];
    };
    roleId = body.id;
    expect(body.system).toBe(false);
    expect(body.permissions).toEqual(
      expect.arrayContaining(['QUERY_SUBMIT_SELECT', 'QUERY_SUBMIT_DML', 'AUDIT_LOG_VIEW']),
    );

    await expect(page.getByText('Role created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    const roleRow = page.getByRole('row', { name: new RegExp(ROLE_NAME) });
    await expect(roleRow).toBeVisible();
    await expect(roleRow.getByText('System', { exact: true })).toHaveCount(0);
  });

  test('2) a user on the custom role gets exactly its permissions', async ({
    page,
    request,
  }) => {
    test.skip(!roleId, 'Test 1 must succeed to create the role');

    const invitation = await inviteUserViaApi(
      request,
      adminToken,
      STEWARD_EMAIL,
      STEWARD_DISPLAY,
      null,
      roleId!,
    );
    expect(invitation).toBeTruthy();
    const token = await waitForInviteToken(request, STEWARD_EMAIL);
    await acceptInvitationViaApi(request, token, STEWARD_PASSWORD, STEWARD_DISPLAY);

    await loginViaUi(page, STEWARD_EMAIL, STEWARD_PASSWORD);

    // Granted surfaces are in the nav…
    await expect(page.getByRole('link', { name: 'Query editor' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Audit log' })).toBeVisible();
    // …ungranted admin surfaces are not.
    await expect(page.getByRole('link', { name: 'Users', exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Review queue' })).toHaveCount(0);

    // AUDIT_LOG_VIEW admits the steward to the audit log page…
    await page.goto('/admin/audit-log');
    await expect(page.getByRole('heading', { name: 'Audit log' })).toBeVisible({
      timeout: 15_000,
    });

    // …while USER_MANAGE-gated /admin/users bounces back to the permission-aware home.
    await page.goto('/admin/users');
    await page.waitForURL('**/dashboard', { timeout: 15_000 });

    // Resolve the provisioned user id for afterAll cleanup.
    const usersRes = await request.get(`${apiBase()}/api/v1/admin/users?size=200`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    const users = (await usersRes.json()) as {
      content: Array<{ id: string; email: string }>;
    };
    stewardUserId =
      users.content.find((u) => u.email === STEWARD_EMAIL.toLowerCase())?.id ??
      users.content.find((u) => u.email === STEWARD_EMAIL)?.id ??
      null;
  });

  test('3) system roles are immutable and in-use custom roles cannot be deleted', async ({
    request,
  }) => {
    test.skip(!roleId, 'Test 1 must succeed to create the role');

    // Deleting the custom role while the steward still holds it → 409 ROLE_IN_USE.
    const deleteRes = await request.delete(`${apiBase()}/api/v1/admin/roles/${roleId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(deleteRes.status()).toBe(409);
    expect(((await deleteRes.json()) as { error: string }).error).toBe('ROLE_IN_USE');

    // Editing a system role → 409 ROLE_SYSTEM_IMMUTABLE.
    const roles = await listRolesViaApi(request, adminToken);
    const reviewer = roles.find((r) => r.system && r.name === 'REVIEWER');
    expect(reviewer).toBeTruthy();
    const putRes = await request.put(`${apiBase()}/api/v1/admin/roles/${reviewer!.id}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
      data: { name: 'Hacked' },
    });
    expect(putRes.status()).toBe(409);
    expect(((await putRes.json()) as { error: string }).error).toBe(
      'ROLE_SYSTEM_IMMUTABLE',
    );
  });
});
