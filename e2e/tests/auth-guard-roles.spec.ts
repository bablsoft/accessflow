import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const ANALYST_PASSWORD = 'AnalystPass!123';

// AF-288 — assert the React `AuthGuard` (frontend/src/components/common/AuthGuard.tsx)
// redirects:
//   1. No session → /login.
//   2. Session but role not in `requireRole` → /editor.
//   3. Session and role allowed → render the route.
//
// The backend's RBAC layer also returns 403 for non-admins hitting admin
// endpoints — that's covered indirectly by admin-users-invitations.spec.ts,
// admin-users-crud.spec.ts, and reviews-self-approval-blocked.spec.ts. This
// spec is single-purpose: the React guard. Adding a redundant 403 assertion
// here would dilute that focus.
//
// Note on the invitee role: the issue body says "EDITOR-role", but the actual
// Role enum is READONLY | ANALYST | REVIEWER | ADMIN. ANALYST is the default
// invitation role (DefaultUserInvitationService) and the realistic non-admin /
// non-reviewer role.

async function loginViaUi(
  page: Page,
  email: string,
  password: string,
): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function provisionAnalystUser(
  request: APIRequestContext,
  adminToken: string,
  email: string,
  displayName: string,
): Promise<void> {
  await inviteUserViaApi(request, adminToken, email, displayName, 'ANALYST');
  const token = await waitForInviteToken(request, email);
  await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, displayName);
}

test.describe.serial('AuthGuard role-based redirects (AF-288)', () => {
  // Unique-suffix pattern matches admin-users-invitations.spec.ts — keeps the
  // invited email unique across reruns against the long-lived e2e Postgres
  // (the stack reuses volumes between `stack:up` cycles unless teardown runs
  // with `-v`).
  const UNIQUE_SUFFIX = `af288-${Date.now()}`;
  const ANALYST_EMAIL = `analyst-${UNIQUE_SUFFIX}@e2e.local`;
  const ANALYST_DISPLAY = `AF288 Analyst ${UNIQUE_SUFFIX}`;

  test.beforeAll(async ({ request }) => {
    await purgeMailcrab(request);
    const adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    await provisionAnalystUser(request, adminToken, ANALYST_EMAIL, ANALYST_DISPLAY);
  });

  test('anonymous visiting /editor is redirected to /login', async ({ page }) => {
    await page.goto('/editor');
    await page.waitForURL('**/login', { timeout: 10_000 });
    // Asserting the form rendered (not just the URL) catches the case where
    // the SPA navigates but the LoginPage itself fails to mount.
    await expect(page.locator('#login-email')).toBeVisible();
    await expect(page.locator('#login-password')).toBeVisible();
  });

  test('ANALYST visiting /admin/users is redirected to /editor', async ({ page }) => {
    await loginViaUi(page, ANALYST_EMAIL, ANALYST_PASSWORD);

    await page.goto('/admin/users');
    // `<Navigate to="/editor" replace />` collapses history, so the bad URL
    // never sticks — wait for the redirect, then assert the authenticated
    // shell (AppLayout's sidebar) rendered. The QueryEditorPage's <h1>
    // is conditional (only when a datasource is selected), but the sidebar
    // nav link is unconditional and only appears for authenticated users.
    await page.waitForURL('**/editor', { timeout: 10_000 });
    await expect(
      page.getByRole('link', { name: 'Query editor' }),
    ).toBeVisible();
  });

  test('ANALYST visiting /reviews is redirected to /editor', async ({ page }) => {
    await loginViaUi(page, ANALYST_EMAIL, ANALYST_PASSWORD);

    await page.goto('/reviews');
    await page.waitForURL('**/editor', { timeout: 10_000 });
    await expect(
      page.getByRole('link', { name: 'Query editor' }),
    ).toBeVisible();
  });

  test('ADMIN can access /editor, /reviews, and /admin/users', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    // /editor — UI login already lands here. Assert URL + sidebar-nav so we
    // notice if the post-login redirect ever changes. The QueryEditorPage <h1>
    // is conditional on having a datasource selected; the sidebar nav link is
    // not — it only renders for authenticated users in the AppLayout shell.
    await expect(page).toHaveURL(/\/editor$/);
    await expect(
      page.getByRole('link', { name: 'Query editor' }),
    ).toBeVisible();

    // /reviews — guard is requireRole=['REVIEWER','ADMIN']. PageHeader emits
    // the <h1> unconditionally on this page.
    await page.goto('/reviews');
    await expect(page).toHaveURL(/\/reviews$/);
    await expect(
      page.getByRole('heading', { level: 1, name: 'Review queue' }),
    ).toBeVisible();

    // /admin/users — guard is requireRole='ADMIN'. <h1> is unconditional.
    await page.goto('/admin/users');
    await expect(page).toHaveURL(/\/admin\/users$/);
    await expect(
      page.getByRole('heading', { level: 1, name: 'Users' }),
    ).toBeVisible();
  });
});
