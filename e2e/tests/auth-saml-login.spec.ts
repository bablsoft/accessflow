import { test, expect, type Page } from '@playwright/test';

// Drives the full SAML SSO dance against the mock SimpleSAMLphp IdP
// bundled in docker-compose.e2e.sso.yml. The bootstrap reconciler seeds the
// saml_config row at startup (idpMetadataUrl points at the IdP via the
// docker-network alias; entityID inside that metadata is
// http://localhost:8085/...), so the LoginPage renders the
// "Continue with SAML SSO" button without any test-only endpoints.
//
// Demo IdP users are baked into kristophjunge/test-saml-idp:
//   user1 / user1pass  (email user1@example.com)
//   user2 / user2pass  (email user2@example.com)
//
// The spec runs serially (workers: 1 from playwright.sso.config.ts) so the
// JIT-provisioned user1 row stays consistent across scenarios.

const IDP_USERNAME = 'user1';
const IDP_PASSWORD = 'user1pass';
const IDP_EXPECTED_EMAIL = 'user1@example.com';

// SimpleSAMLphp's login form: an HTML form with `<input name="username">`,
// `<input name="password">`, and a `<button>Login</button>` (no explicit
// type="submit" attribute — it's the only button on the page, accessible as
// a button-by-name "Login"). The form posts to the IdP's authproc handler,
// which then signs a SAMLResponse and HTTP-POSTs it back to the backend's
// ACS URL (browser follows the auto-submit form).
async function submitIdpForm(page: Page, username: string, password: string): Promise<void> {
  await page.locator('input[name="username"]').fill(username);
  await page.locator('input[name="password"]').fill(password);
  await page.getByRole('button', { name: 'Login' }).click();
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

test.describe('SAML SSO login', () => {
  test('happy path — IdP roundtrip lands JIT-provisioned user on /editor', async ({ page }) => {
    await page.goto('/login');

    const samlButton = page.getByRole('button', { name: /SAML SSO/i });
    await expect(samlButton).toBeVisible();
    await samlButton.click();

    // Wait for SimpleSAMLphp's login form on the IdP origin. The URL uses
    // `saml-idp:8080` (matching the docker-network hostname the backend
    // fetched metadata via); Chromium's host-resolver-rules in
    // playwright.sso.config.ts remap that to the host's port 8085.
    await page.waitForURL(/saml-idp:8080\/simplesaml\//, { timeout: 20_000 });
    await expect(page.locator('input[name="username"]')).toBeVisible();

    await submitIdpForm(page, IDP_USERNAME, IDP_PASSWORD);

    // IdP redirects through the backend ACS, success handler mints a code,
    // SamlCallbackPage swaps it for a JWT pair, and React Router navigates
    // to /editor. The SAML roundtrip is several hops — leave generous time.
    await page.waitForURL('**/dashboard', { timeout: 30_000 });

    const storedEmail = await getAuthEmail(page);
    expect(storedEmail).toBe(IDP_EXPECTED_EMAIL);

    const me = await fetchMe(page);
    expect(me.status).toBe(200);
    expect(me.data.email).toBe(IDP_EXPECTED_EMAIL);
    expect(me.data.auth_provider).toBe('SAML');

    // SAML-provisioned users cannot enrol locally in TOTP (AF-287) — the
    // /profile page renders an info alert in place of the Enable 2FA button.
    // ProfilePage.tsx flips on profile.auth_provider === 'SAML'; the visible
    // string is `profile.totp.saml_disabled` in src/locales/en.json.
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });
    await expect(
      page.getByText(
        'Two-factor authentication is managed by your SAML identity provider.',
      ),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Enable 2FA', exact: true })).toHaveCount(0);
  });

  test('failure — wrong IdP password keeps the user on the IdP login form', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: /SAML SSO/i }).click();

    await page.waitForURL(/saml-idp:8080\/simplesaml\//, { timeout: 20_000 });
    await submitIdpForm(page, IDP_USERNAME, 'definitely-wrong-password');

    // SimpleSAMLphp re-renders its login form with an error message. The
    // exact wording can vary across SimpleSAMLphp versions, but the browser
    // never leaves the IdP origin and the form inputs remain visible.
    await expect(page).toHaveURL(/saml-idp:8080\/simplesaml\//);
    await expect(page.locator('input[name="username"]')).toBeVisible();
    await expect(page.locator('input[name="password"]')).toBeVisible();
    // The backend's callback page is never reached.
    await expect(page).not.toHaveURL(/\/auth\/saml\/callback/);
    await expect(page).not.toHaveURL(/\/editor/);
  });

  test('failure — server-side SAML rejection renders the callback error page', async ({ page }) => {
    // Direct-visit the callback with a known error code. This exercises the
    // SamlCallbackPage error-rendering branch the SamlLoginFailureHandler
    // produces when Spring SAML rejects a SAMLResponse (e.g. invalid
    // signature) — the redirect URL in that case is exactly this shape.
    await page.goto('/auth/saml/callback?error=SAML_SIGNATURE_INVALID');

    await expect(
      page.getByText(
        /SAML response signature did not match the configured IdP certificate/i,
      ),
    ).toBeVisible();

    const backButton = page.getByRole('button', { name: /Back to sign in/i });
    await expect(backButton).toBeVisible();
    await backButton.click();
    await page.waitForURL('**/login', { timeout: 10_000 });

    // Verify the generic-known-code branch as well.
    await page.goto('/auth/saml/callback?error=SAML_LOGIN_FAILED');
    await expect(
      page.getByText(/We could not complete the sign-in via SAML/i),
    ).toBeVisible();
  });
});
