import { generateSync as generateTotp } from 'otplib';
import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

// Bootstrap admin reconciled by the backend on every startup via
// ACCESSFLOW_BOOTSTRAP_ADMIN_* (see e2e/docker-compose.e2e.yml:120-122).
// If afterEach fails to disable TOTP, the next `npm run stack:up` self-heals.
const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Best-effort cleanup that ensures TOTP is OFF for the next test. Mirrors the
// shape of restoreAdminState in profile-display-and-password.spec.ts and the
// afterAll cleanup in auth-totp-login.spec.ts:80-106. If TOTP is enabled,
// we need a fresh 6-digit code to log in (no backup-code fallback here — we
// always have the secret module-scoped); if it's already disabled, plain
// login is enough. All failures are logged, never thrown, so a leftover
// cleanup error doesn't mask the actual test result.
async function disableTotpIfEnabled(
  request: APIRequestContext,
  secret: string,
): Promise<void> {
  if (!secret) {
    // Nothing to clean up — TOTP was never enabled in this test.
    return;
  }
  let token: string | null = null;
  try {
    const res = await request.post(`${apiBase()}/api/v1/auth/login`, {
      data: {
        email: ADMIN_EMAIL,
        password: ADMIN_PASSWORD,
        totp_code: generateTotp({ secret }),
      },
    });
    if (res.ok()) {
      token = ((await res.json()) as { access_token: string }).access_token;
    }
  } catch {
    // Fall through to the plain-login retry below.
  }
  if (!token) {
    try {
      token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn(`disableTotpIfEnabled: cannot log in: ${err}`);
      return;
    }
  }
  const dis = await request.post(`${apiBase()}/api/v1/me/totp/disable`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { current_password: ADMIN_PASSWORD },
  });
  if (!dis.ok() && dis.status() !== 422) {
    // 422 = TOTP_NOT_ENABLED — acceptable cleanup outcome.
    // eslint-disable-next-line no-console
    console.warn(
      `disableTotpIfEnabled: disable returned ${dis.status()}: ${await dis.text()}`,
    );
  }
}

test.describe.serial('AF-287 — /profile TOTP enrollment + disable', () => {
  // Module-scoped so the afterEach cleanup can mint a fresh code after the
  // happy-path test pulls the secret out of the enrollment dialog. Reset to
  // empty after a successful disable so subsequent tests start clean.
  let totpSecret = '';

  test.beforeEach(async ({ page }) => {
    // Every test starts with TOTP disabled (afterEach guarantees it) so plain
    // login works without a TOTP code.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterEach(async ({ request }) => {
    await disableTotpIfEnabled(request, totpSecret);
    totpSecret = '';
  });

  test('enrolls via UI, confirms with otplib code, saves backup codes, then disables via UI', async ({
    page,
  }) => {
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });

    await page.getByRole('button', { name: 'Enable 2FA', exact: true }).click();

    const enrollDialog = page.getByRole('dialog', {
      name: 'Enable two-factor authentication',
    });
    await expect(enrollDialog).toBeVisible({ timeout: 5_000 });

    // Step 0 — QR + readonly secret textarea. Wait for the enroll mutation to
    // populate the secret, then capture it. inputValue() works against AntD's
    // Input.TextArea (it renders a native <textarea readonly>).
    const secretBox = enrollDialog.locator('textarea[readonly]');
    await expect(secretBox).toBeVisible({ timeout: 10_000 });
    const secret = await secretBox.inputValue();
    expect(secret).toMatch(/^[A-Z2-7]+=*$/); // base32 alphabet
    totpSecret = secret;

    await enrollDialog.getByRole('button', { name: 'Next', exact: true }).click();

    // Step 1 — fill the 6-digit code derived from the captured secret.
    const code = generateTotp({ secret });
    await enrollDialog.locator('input[autocomplete="one-time-code"]').fill(code);
    await enrollDialog
      .getByRole('button', { name: 'Verify and continue', exact: true })
      .click();

    // Step 2 — backup codes block, copy + saved-ack, then Done.
    const codesBlock = enrollDialog.locator('pre[aria-label="backup-codes"]');
    await expect(codesBlock).toBeVisible({ timeout: 10_000 });
    const codesText = await codesBlock.innerText();
    expect(codesText.trim().split('\n')).toHaveLength(10);

    await enrollDialog.getByLabel("I've saved these codes").check();
    await enrollDialog.getByRole('button', { name: 'Done', exact: true }).click();

    await expect(enrollDialog).toBeHidden({ timeout: 5_000 });

    // Page reflects totp_enabled=true: Disable replaces Enable.
    await expect(page.getByRole('button', { name: 'Disable 2FA', exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByRole('button', { name: 'Enable 2FA', exact: true })).toHaveCount(0);

    // Now disable via UI.
    await page.getByRole('button', { name: 'Disable 2FA', exact: true }).click();
    const disableDialog = page.getByRole('dialog', {
      name: 'Disable two-factor authentication',
    });
    await expect(disableDialog).toBeVisible({ timeout: 5_000 });

    // Both the page's "Password" card and the disable dialog render a
    // Form.Item with the same `current_password` field name, which means two
    // <input> elements share an id and the label-id linkage breaks for
    // getByLabel inside the dialog. Use the unique autocomplete attribute
    // instead, scoped to the dialog locator so it's unambiguous.
    await disableDialog.locator('input[autocomplete="current-password"]').fill(ADMIN_PASSWORD);
    await disableDialog
      .getByRole('button', { name: 'Disable 2FA', exact: true })
      .click();

    await expect(
      page.getByText('Two-factor authentication disabled.', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
    await expect(disableDialog).toBeHidden({ timeout: 5_000 });

    // TOTP is off now — clear the cleanup secret so afterEach is a no-op.
    totpSecret = '';

    // Page reverts to showing Enable.
    await expect(page.getByRole('button', { name: 'Enable 2FA', exact: true })).toBeVisible({
      timeout: 10_000,
    });
  });

  test('wrong verify code keeps the enrollment modal open and leaves TOTP off', async ({
    page,
  }) => {
    await page.goto('/profile');
    await expect(page.getByRole('heading', { name: 'Profile settings' })).toBeVisible({
      timeout: 10_000,
    });

    await page.getByRole('button', { name: 'Enable 2FA', exact: true }).click();

    const enrollDialog = page.getByRole('dialog', {
      name: 'Enable two-factor authentication',
    });
    await expect(enrollDialog).toBeVisible({ timeout: 5_000 });

    // Wait for the enroll mutation to render the secret before stepping forward.
    await expect(enrollDialog.locator('textarea[readonly]')).toBeVisible({
      timeout: 10_000,
    });
    await enrollDialog.getByRole('button', { name: 'Next', exact: true }).click();

    // Submit a code that's syntactically valid but will not match any TOTP
    // window for the freshly-issued secret.
    const responsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/me\/totp\/confirm$/.test(r.url()) &&
        r.status() === 422,
      { timeout: 10_000 },
    );
    await enrollDialog.locator('input[autocomplete="one-time-code"]').fill('000000');
    await enrollDialog
      .getByRole('button', { name: 'Verify and continue', exact: true })
      .click();
    await responsePromise;

    // Error alert is rendered inside the dialog; modal stays on step 1.
    await expect(enrollDialog.getByRole('alert').first()).toBeVisible({ timeout: 5_000 });
    await expect(enrollDialog.locator('input[autocomplete="one-time-code"]')).toBeVisible();
    // Step 2 (backup codes) must NOT have rendered.
    await expect(enrollDialog.locator('pre[aria-label="backup-codes"]')).toHaveCount(0);

    // Close the modal and confirm TOTP did not flip on.
    await page.keyboard.press('Escape');
    await expect(enrollDialog).toBeHidden({ timeout: 5_000 });
    await expect(page.getByRole('button', { name: 'Enable 2FA', exact: true })).toBeVisible({
      timeout: 10_000,
    });
  });

  test('wrong password on disable shows an error alert and keeps the modal open', async ({
    page,
    request,
  }) => {
    // Enable TOTP out-of-band so the UI test has something to disable.
    const apiToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const enrollRes = await request.post(`${apiBase()}/api/v1/me/totp/enroll`, {
      headers: { Authorization: `Bearer ${apiToken}` },
    });
    expect(enrollRes.ok()).toBeTruthy();
    const enrolled = (await enrollRes.json()) as { secret: string };
    totpSecret = enrolled.secret;

    const confirmRes = await request.post(`${apiBase()}/api/v1/me/totp/confirm`, {
      headers: { Authorization: `Bearer ${apiToken}` },
      data: { code: generateTotp({ secret: enrolled.secret }) },
    });
    expect(confirmRes.ok()).toBeTruthy();

    // The TanStack `meKeys.current` cache was populated when /profile loaded
    // pre-enrollment; reload to pick up totp_enabled=true.
    await page.goto('/profile');
    await expect(page.getByRole('button', { name: 'Disable 2FA', exact: true })).toBeVisible({
      timeout: 10_000,
    });

    await page.getByRole('button', { name: 'Disable 2FA', exact: true }).click();
    const disableDialog = page.getByRole('dialog', {
      name: 'Disable two-factor authentication',
    });
    await expect(disableDialog).toBeVisible({ timeout: 5_000 });

    await disableDialog
      .locator('input[autocomplete="current-password"]')
      .fill('WrongPassword!1');

    const responsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/me\/totp\/disable$/.test(r.url()) &&
        r.status() === 422,
      { timeout: 10_000 },
    );
    await disableDialog
      .getByRole('button', { name: 'Disable 2FA', exact: true })
      .click();
    await responsePromise;

    // The modal-scoped alert surfaces the PASSWORD_INCORRECT detail via
    // profileErrorMessage. Match the i18n string used by AF-285 for the same
    // backend error so any future detail-string tweak fails one place.
    await expect(
      disableDialog
        .getByRole('alert')
        .filter({ hasText: 'The current password is incorrect.' }),
    ).toBeVisible({ timeout: 5_000 });

    // Modal stays open; TOTP is still on (afterEach will clean it up).
    await expect(disableDialog).toBeVisible();
  });
});
