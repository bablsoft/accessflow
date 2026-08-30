import { type Page } from '@playwright/test';
import { apiBase } from './datasources';

// The one admin the main e2e stack seeds (docker-compose.e2e.yml bootstrap).
export const ADMIN_EMAIL = 'e2e@accessflow.test';
export const ADMIN_PASSWORD = 'E2ePassword!123';

/**
 * Log the page's browser context in WITHOUT driving the /login form.
 *
 * `page.context().request` shares the browser context's cookie jar, so the
 * HttpOnly `refresh_token` Set-Cookie from `POST /api/v1/auth/login` lands
 * where the page can use it. The subsequent `goto('/dashboard')` boots the
 * SPA, whose BootGate silently exchanges the cookie for an access token —
 * exactly the same path a returning user's page reload takes.
 *
 * This replaces the copy-pasted per-spec `loginViaUi` helpers (AF-e2e
 * parallelism sweep): it skips the /login page load, the form fill, and the
 * client-side redirect, saving ~1-2s per call across ~250 call sites.
 *
 * Specs whose subject is the login flow itself (auth.spec.ts,
 * auth-login-failures, TOTP, SSO variants, password-reset re-login) must keep
 * driving the real form instead of using this helper.
 *
 * Not usable for TOTP-enrolled users — the login endpoint returns a challenge
 * instead of tokens; TOTP specs drive their own flow.
 */
export async function login(
  page: Page,
  email: string = ADMIN_EMAIL,
  password: string = ADMIN_PASSWORD,
): Promise<void> {
  const res = await page.context().request.post(`${apiBase()}/api/v1/auth/login`, {
    data: { email, password },
  });
  if (!res.ok()) {
    throw new Error(`API login for ${email} failed: ${res.status()} ${await res.text()}`);
  }
  // Wait for BootGate's silent refresh so a passing URL check below actually
  // proves an authenticated session, not just a not-yet-redirected /dashboard.
  const [refresh] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/v1/auth/refresh'), {
      timeout: 15_000,
    }),
    page.goto('/dashboard'),
  ]);
  if (!refresh.ok()) {
    throw new Error(
      `Silent refresh after API login for ${email} failed: ${refresh.status()}`,
    );
  }
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}
