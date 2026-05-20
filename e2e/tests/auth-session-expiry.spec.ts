import { test, expect, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

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

// Complements tests/auth.spec.ts, which covers the success branch of the Axios
// 401-refresh-retry loop (corrupt access token → /auth/refresh succeeds → request
// is replayed). This spec covers the failure branch: the refresh cookie has been
// revoked out-of-band, so /auth/refresh itself returns 401. The interceptor's
// onRefreshFailure handler must clear the auth store, surface a session-expired
// toast, and drive a soft SPA redirect to /login via the navigation bridge.
//
// The issue lists two scenarios ("logout from another context" and "refresh
// interceptor exhausts retries"). Both converge on the same onRefreshFailure
// path — the interceptor has no multi-attempt semantics today — so a single
// scenario covers both.
test('refresh failure with revoked cookie clears state, toasts, redirects to /login', async ({
  page,
  context,
}) => {
  // ── 1. Log in via the UI and land on /editor ─────────────────────────────────────
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });

  const beforeFailure = await getAuthState(page);
  expect(beforeFailure.accessToken).toBeTruthy();
  expect(beforeFailure.user).not.toBeNull();

  // ── 2. Revoke the refresh cookie out-of-band ─────────────────────────────────────
  // clearCookies() drops the HttpOnly refresh cookie at the browser level — the
  // backend's stored refresh token may still be valid, but without the cookie the
  // /auth/refresh request travels without credentials and the server replies 401.
  // This models any scenario where the cookie becomes invalid (logout from another
  // tab, admin revocation, cookie expiry, browser-side eviction).
  await context.clearCookies();

  // ── 3. Force the next request to be a 401 ────────────────────────────────────────
  // The in-memory access token is still good for ~15 minutes; corrupting it
  // guarantees the response interceptor enters its refresh branch.
  await page.evaluate(() => {
    const store = (
      window as unknown as {
        __authStore?: { setState: (s: { accessToken: string }) => void };
      }
    ).__authStore;
    if (!store) throw new Error('window.__authStore is not exposed');
    store.setState({ accessToken: 'broken.invalid.jwt' });
  });

  // ── 4. Fire a protected request via the in-page apiClient ────────────────────────
  // The chain: GET /api/v1/me → 401 → POST /api/v1/auth/refresh (no cookie) → 401
  // → onRefreshFailure() → store.clear() + message.error(t('auth.session_expired')).
  // The request itself rejects — swallow inside the page so the test can continue
  // to its assertions.
  await page.evaluate(async () => {
    const client = (
      window as unknown as {
        __apiClient?: { get: (url: string) => Promise<unknown> };
      }
    ).__apiClient;
    if (!client) throw new Error('window.__apiClient is not exposed');
    try {
      await client.get('/api/v1/me');
    } catch {
      // expected — refresh fails
    }
  });

  // ── 5. Toast renders and navigationBridge redirects to /login ────────────────────
  // The AntD message renders into multiple wrappers inside .ant-message-notice;
  // scope by class so the locator matches the single message container.
  await expect(
    page.locator('.ant-message-notice', { hasText: 'Session expired' }),
  ).toBeVisible({ timeout: 10_000 });
  await page.waitForURL('**/login', { timeout: 10_000 });

  // ── 6. Auth store is cleared ─────────────────────────────────────────────────────
  const afterFailure = await getAuthState(page);
  expect(afterFailure.user).toBeNull();
  expect(afterFailure.accessToken).toBeNull();
});
