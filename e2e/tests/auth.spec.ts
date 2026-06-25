import { test, expect, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Hits a protected endpoint via the in-page apiClient (which carries the access token
// header AND the refresh-cookie via withCredentials), so the response interceptor's
// 401-refresh-retry path is exercised end-to-end.
async function callProtectedEndpoint(page: Page): Promise<{ status: number }> {
  return page.evaluate(async () => {
    const client = (
      window as unknown as {
        __apiClient?: { get: (url: string) => Promise<{ status: number }> };
      }
    ).__apiClient;
    if (!client) throw new Error('window.__apiClient is not exposed');
    const res = await client.get('/api/v1/me');
    return { status: res.status };
  });
}

async function getAuthState(
  page: Page,
): Promise<{ user: unknown; accessToken: string | null }> {
  return page.evaluate(() => {
    const store = (
      window as unknown as {
        __authStore?: {
          getState: () => { user: unknown; accessToken: string | null };
        };
      }
    ).__authStore;
    if (!store) throw new Error('window.__authStore is not exposed');
    const s = store.getState();
    return { user: s.user, accessToken: s.accessToken };
  });
}

// Single sequential test — auth state lives in the page (in-memory Zustand store +
// HttpOnly refresh cookie), so we keep one page alive across all three scenarios.
test('login, transparent token refresh on 401, and logout', async ({ page }) => {
  // ── 1. Login redirects to /dashboard and a protected endpoint returns 200 ───────────
  await page.goto('/login');

  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();

  await page.waitForURL('**/dashboard', { timeout: 15_000 });

  const afterLogin = await getAuthState(page);
  expect(afterLogin.accessToken).toBeTruthy();
  expect(afterLogin.user).not.toBeNull();

  const firstCall = await callProtectedEndpoint(page);
  expect(firstCall.status).toBe(200);

  // ── 2. Corrupted access token → interceptor refreshes transparently ──────────────
  // The HttpOnly refresh cookie is untouched — the response interceptor in
  // src/api/client.ts should detect the 401, call /auth/refresh, rotate the token
  // via setSession, and retry the original request.
  await page.evaluate(() => {
    const store = (
      window as unknown as {
        __authStore?: { setState: (s: { accessToken: string }) => void };
      }
    ).__authStore;
    if (!store) throw new Error('window.__authStore is not exposed');
    store.setState({ accessToken: 'broken.invalid.jwt' });
  });

  // The 200 here is the proof: the request was sent with a corrupt token, the server
  // returned 401, the interceptor called /auth/refresh, rotated the token, and retried.
  // (We do NOT compare the new token to the old one — JWTs minted within the same
  // second have identical iat/exp and the signed string can be byte-identical.)
  const secondCall = await callProtectedEndpoint(page);
  expect(secondCall.status).toBe(200);

  const afterRefresh = await getAuthState(page);
  expect(afterRefresh.accessToken).toBeTruthy();
  expect(afterRefresh.accessToken).not.toBe('broken.invalid.jwt');

  // ── 3. Logout clears auth state and redirects to /login ──────────────────────────
  // Topbar user-menu trigger carries aria-label "Open user menu" (user_menu.open).
  await page.getByRole('button', { name: 'Open user menu' }).click();
  // Dropdown menuitem with text "Sign out" (user_menu.sign_out).
  await page.getByRole('menuitem', { name: 'Sign out' }).click();

  await page.waitForURL('**/login', { timeout: 10_000 });

  const afterLogout = await getAuthState(page);
  expect(afterLogout.user).toBeNull();
  expect(afterLogout.accessToken).toBeNull();
});
