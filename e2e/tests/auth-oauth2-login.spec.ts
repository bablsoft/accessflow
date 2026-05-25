import { test, expect, type Page } from '@playwright/test';

// Drives the full OAuth2 sign-in roundtrip against navikt/mock-oauth2-server,
// bundled in docker-compose.e2e.sso.yml. The bootstrap reconciler seeds an
// `oauth2_config` row of provider type OIDC at startup pointing at the mock
// (display_name="Mock OAuth2"), so the LoginPage renders a
// "Continue with Mock OAuth2" button without any test-only endpoints.
//
// The mock-oauth2-server publishes an interactive login form at
// /default/authorize with two inputs:
//   * <input name="username"> — becomes the `sub` claim
//   * <textarea name="claims"> — optional JSON claims override
// On submit it issues an authorization code redirect back to the backend's
// callback URL. The backend's success handler mints a one-time exchange code,
// the OAuthCallbackPage swaps it for a JWT pair, and React Router navigates
// to /editor.
//
// The hostname `mock-oauth2-server:8080` is resolved by both the backend (via
// docker DNS) and the browser (via Chromium's --host-resolver-rules MAP rule
// in playwright.sso.config.ts) — same dual-perspective trick the SAML spec
// uses for `saml-idp:8080`.

const OAUTH_USER_SUBJECT = 'oauth-user-1';
const OAUTH_EXPECTED_EMAIL = 'oauth-user@accessflow.test';
const OAUTH_EXPECTED_NAME = 'OAuth E2E User';

const CLAIMS_OVERRIDE = JSON.stringify({
  email: OAUTH_EXPECTED_EMAIL,
  email_verified: true,
  name: OAUTH_EXPECTED_NAME,
});

async function submitMockProviderForm(
  page: Page,
  username: string,
  claimsJson: string,
): Promise<void> {
  await page.locator('input[name="username"]').fill(username);
  await page.locator('textarea[name="claims"]').fill(claimsJson);
  await page.locator('input[type="submit"]').click();
}

interface MeResponseBody {
  email: string;
  auth_provider: string;
  role: string;
}

async function fetchMe(page: Page): Promise<{ status: number; data: MeResponseBody }> {
  return page.evaluate(async () => {
    const client = (
      window as unknown as {
        __apiClient?: {
          get: (url: string) => Promise<{ status: number; data: MeResponseBody }>;
        };
      }
    ).__apiClient;
    if (!client) throw new Error('window.__apiClient is not exposed');
    const res = await client.get('/api/v1/me');
    return { status: res.status, data: res.data };
  });
}

async function getAuthEmail(page: Page): Promise<string | null> {
  return page.evaluate(() => {
    const store = (
      window as unknown as {
        __authStore?: {
          getState: () => { user: { email: string } | null };
        };
      }
    ).__authStore;
    if (!store) throw new Error('window.__authStore is not exposed');
    return store.getState().user?.email ?? null;
  });
}

test.describe('OAuth2 login via mock provider', () => {
  test('happy path — provider roundtrip lands JIT-provisioned user on /editor', async ({ page }) => {
    await page.goto('/login');

    const oauthButton = page.getByRole('button', { name: /Mock OAuth2/i });
    await expect(oauthButton).toBeVisible();
    await oauthButton.click();

    // Wait for the mock-oauth2-server login form. The browser is remapped so
    // mock-oauth2-server:8080 resolves to 127.0.0.1:8086 (the published port).
    await page.waitForURL(/mock-oauth2-server:8080\/default\/authorize/, { timeout: 20_000 });
    await expect(page.locator('input[name="username"]')).toBeVisible();

    await submitMockProviderForm(page, OAUTH_USER_SUBJECT, CLAIMS_OVERRIDE);

    // Mock provider redirects through the backend's
    // /login/oauth2/code/oidc → success handler → /auth/oauth/callback?code=…
    // → OAuthCallbackPage exchanges the code → /editor. Generous timeout
    // because the JWK lookup adds a hop the first time.
    await page.waitForURL('**/editor', { timeout: 30_000 });

    const storedEmail = await getAuthEmail(page);
    expect(storedEmail).toBe(OAUTH_EXPECTED_EMAIL);

    const me = await fetchMe(page);
    expect(me.status).toBe(200);
    expect(me.data.email).toBe(OAUTH_EXPECTED_EMAIL);
    expect(me.data.auth_provider).toBe('OAUTH2');

    // OAuth2-provisioned users cannot enrol locally in TOTP (AF-287) — the
    // /profile page renders an info alert in place of the Enable 2FA button.
    // ProfilePage.tsx flips on profile.auth_provider === 'OAUTH2'; the visible
    // string is `profile.totp.oauth2_disabled` in src/locales/en.json.
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });
    await expect(
      page.getByText(
        "Two-factor authentication is managed by your OAuth provider. Enable it in that provider's account settings.",
      ),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Enable 2FA', exact: true })).toHaveCount(0);
  });

  test('failure — server-side OAuth2 rejection renders the callback error page', async ({ page }) => {
    // Direct-visit the callback with a known error code. This exercises the
    // OAuthCallbackPage error-rendering branch that the OAuth2LoginFailureHandler
    // produces when Spring OAuth2 rejects the upstream provider's response
    // (e.g. mock provider returns error=access_denied → mapped to
    // OAUTH2_LOGIN_FAILED on the redirect).
    await page.goto('/auth/oauth/callback?error=OAUTH2_LOGIN_FAILED');

    // The OAuthCallbackPage maps known error codes to localized messages
    // under the `auth.oauth_callback.error.<code-lowercased>` i18n key.
    await expect(
      page.getByRole('button', { name: /Back to sign in/i }),
    ).toBeVisible();

    await page.getByRole('button', { name: /Back to sign in/i }).click();
    await page.waitForURL('**/login', { timeout: 10_000 });
  });

  test('failure — tampered exchange code is rejected and the user lands back on /login', async ({ page }) => {
    // Direct-visit the callback with a fabricated code. The frontend POSTs
    // it to /api/v1/auth/oauth2/exchange; the backend rejects (no matching
    // entry in Redis). The axios interceptor sees the 401, tries to refresh
    // (no refresh cookie set — the user never authenticated), the refresh
    // also fails, and `onRefreshFailure()` clears auth state and navigates
    // to /login. The user must NOT end up on /editor.
    await page.goto('/auth/oauth/callback?code=this-code-is-not-in-redis');

    await page.waitForURL('**/login', { timeout: 15_000 });
    await expect(page).not.toHaveURL(/\/editor/);
  });
});
