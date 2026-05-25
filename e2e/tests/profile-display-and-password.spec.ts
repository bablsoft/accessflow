import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

// Bootstrap admin reconciled by the backend on every startup via
// ACCESSFLOW_BOOTSTRAP_ADMIN_* (see e2e/docker-compose.e2e.yml:120-122).
// If afterAll fails to restore state, the next `npm run stack:up` self-heals.
const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ORIG_DISPLAY_NAME = 'E2E Admin';

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Best-effort cleanup that restores both the admin's display name and
// password back to the bootstrap defaults via API. Tolerates either the
// "tests changed the password" path or the "tests bailed before changing"
// path — whichever creds work, we use to reach `/me/password` and
// `/me/profile`. All failures are logged, never thrown, so a leftover
// cleanup error doesn't mask the actual test result. Same shape as
// resetLocalizationState in admin-languages.spec.ts:34-58.
async function restoreAdminState(
  request: APIRequestContext,
  candidatePasswords: string[],
): Promise<void> {
  let token: string | null = null;
  let workingPassword: string | null = null;
  for (const pwd of candidatePasswords) {
    try {
      token = await loginViaApi(request, ADMIN_EMAIL, pwd);
      workingPassword = pwd;
      break;
    } catch {
      // Try the next candidate.
    }
  }
  if (!token || !workingPassword) {
    // eslint-disable-next-line no-console
    console.warn(
      `restoreAdminState: could not log in with any candidate password (${candidatePasswords.length} tried)`,
    );
    return;
  }

  if (workingPassword !== ADMIN_PASSWORD) {
    const pwdRes = await request.post(`${apiBase()}/api/v1/me/password`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { current_password: workingPassword, new_password: ADMIN_PASSWORD },
    });
    if (!pwdRes.ok()) {
      // eslint-disable-next-line no-console
      console.warn(
        `restoreAdminState: password reset returned ${pwdRes.status()}: ${await pwdRes.text()}`,
      );
      // Even if reset failed, fall through and try to reset display name with
      // a fresh login below — `workingPassword` is still the valid one.
    } else {
      // Sessions are revoked on password change — re-login with the restored
      // password so the subsequent display_name PUT has a valid token.
      try {
        token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
      } catch (err) {
        // eslint-disable-next-line no-console
        console.warn(`restoreAdminState: re-login after password reset failed: ${err}`);
        return;
      }
    }
  }

  const profileRes = await request.put(`${apiBase()}/api/v1/me/profile`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { display_name: ORIG_DISPLAY_NAME },
  });
  if (!profileRes.ok()) {
    // eslint-disable-next-line no-console
    console.warn(
      `restoreAdminState: display-name reset returned ${profileRes.status()}: ${await profileRes.text()}`,
    );
  }
}

test.describe.serial('AF-285 — /profile display name + password change', () => {
  // Per-suite uniqueness keeps reruns deterministic against the long-lived db.
  const SUFFIX = `af285-${Date.now()}`;
  const NEW_DISPLAY = `E2E Admin ${SUFFIX}`;
  const NEW_PASSWORD = `E2eNewPwd!${SUFFIX}`;

  test.beforeEach(async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test('updates display name and reflects it in the topbar', async ({ page }) => {
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });

    const displayNameInput = page.getByLabel('Display name', { exact: true });
    await displayNameInput.fill(NEW_DISPLAY);

    // The Profile card and the topbar both render visible "Save" buttons —
    // scope to the Profile card explicitly. The page is built from cards
    // whose AntD title text is "Profile" (see profile.display_name.title).
    const saveBtn = page
      .locator('.ant-card', { hasText: 'Profile' })
      .getByRole('button', { name: 'Save', exact: true })
      .first();
    await saveBtn.click();

    await expect(page.getByText('Profile updated.', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // Topbar reflects the new name without a reload — DisplayNameForm
    // synchronously updates authStore.user on success.
    await expect(page.locator('.af-user-menu-name')).toContainText(NEW_DISPLAY, {
      timeout: 10_000,
    });
  });

  test('blocks submit when new password and confirmation do not match', async ({ page }) => {
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });

    await page.getByLabel('Current password', { exact: true }).fill(ADMIN_PASSWORD);
    await page.getByLabel('New password', { exact: true }).fill('Password!Aaaa');
    await page.getByLabel('Confirm new password', { exact: true }).fill('Password!Bbbb');

    await page.getByRole('button', { name: 'Update password', exact: true }).click();

    await expect(page.getByText('Passwords do not match.', { exact: true })).toBeVisible({
      timeout: 5_000,
    });

    // The mutation never fires when AntD form validation rejects — assert no
    // toast appeared and no redirect happened. The success toast (when it
    // would fire) starts with "Password updated."
    await expect(page.getByText(/^Password updated\./)).toHaveCount(0);
    expect(page.url()).toContain('/profile');
  });

  test('shows error alert when current password is wrong', async ({ page }) => {
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });

    await page.getByLabel('Current password', { exact: true }).fill('WrongCurrent!');
    await page.getByLabel('New password', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirm new password', { exact: true }).fill(NEW_PASSWORD);

    const responsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/me\/password$/.test(r.url()) &&
        r.status() === 422,
      { timeout: 10_000 },
    );
    await page.getByRole('button', { name: 'Update password', exact: true }).click();
    await responsePromise;

    await expect(
      page.getByRole('alert').filter({ hasText: 'The current password is incorrect.' }),
    ).toBeVisible({ timeout: 5_000 });
    expect(page.url()).toContain('/profile');
  });

  test('changes password successfully, redirects to /login, allows re-login', async ({ page }) => {
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });

    await page.getByLabel('Current password', { exact: true }).fill(ADMIN_PASSWORD);
    await page.getByLabel('New password', { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel('Confirm new password', { exact: true }).fill(NEW_PASSWORD);

    await page.getByRole('button', { name: 'Update password', exact: true }).click();

    // Partial-match toast — the full string contains a curly apostrophe in
    // "you'll" which is fragile across locales. Prefix is stable.
    await expect(page.getByText(/^Password updated\./)).toBeVisible({ timeout: 10_000 });

    await page.waitForURL('**/login', { timeout: 10_000 });

    // Re-login with the new password proves the rotation actually took effect.
    await loginViaUi(page, ADMIN_EMAIL, NEW_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    // Try the new password first (success path), fall back to the original
    // (any earlier failure left the password untouched).
    await restoreAdminState(request, [NEW_PASSWORD, ADMIN_PASSWORD]);
  });
});
