import { generateSync as generateTotp } from 'otplib';
import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const API_BASE = process.env.E2E_API_BASE ?? 'http://localhost:8080';

// Filled in beforeAll once the admin is enrolled into TOTP. Module-scoped so
// every test in the describe can compute fresh codes from the same secret.
let totpSecret = '';

async function login(
  request: APIRequestContext,
  body: { email: string; password: string; totp_code?: string },
): Promise<string> {
  const res = await request.post(`${API_BASE}/api/v1/auth/login`, { data: body });
  if (!res.ok()) {
    throw new Error(`Login failed: ${res.status()} ${await res.text()}`);
  }
  const json = (await res.json()) as { access_token: string };
  return json.access_token;
}

async function gotoLogin(page: Page): Promise<void> {
  await page.goto('/login');
  await expect(page.locator('#login-email')).toBeVisible();
}

async function reachTotpStage(page: Page): Promise<void> {
  await gotoLogin(page);
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await expect(page.getByText('Two-factor authentication')).toBeVisible();
}

async function countLoginRequests(
  page: Page,
  action: () => Promise<void>,
): Promise<number> {
  let count = 0;
  const handler = (req: { url(): string; method(): string }) => {
    if (req.method() === 'POST' && req.url().includes('/api/v1/auth/login')) {
      count += 1;
    }
  };
  page.on('request', handler);
  try {
    await action();
  } finally {
    page.off('request', handler);
  }
  return count;
}

test.describe.serial('TOTP login challenge', () => {
  test.beforeAll(async ({ request }) => {
    // Enrol the seeded admin into TOTP via the real backend. We never touch the
    // UI here — this is fixture setup only.
    const accessToken = await login(request, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD });

    const enrollRes = await request.post(`${API_BASE}/api/v1/me/totp/enroll`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (!enrollRes.ok()) {
      throw new Error(`TOTP enroll failed: ${enrollRes.status()} ${await enrollRes.text()}`);
    }
    const enrolled = (await enrollRes.json()) as { secret: string };
    totpSecret = enrolled.secret;

    const confirmRes = await request.post(`${API_BASE}/api/v1/me/totp/confirm`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: { code: generateTotp({ secret: totpSecret }) },
    });
    if (!confirmRes.ok()) {
      throw new Error(`TOTP confirm failed: ${confirmRes.status()} ${await confirmRes.text()}`);
    }
  });

  test.afterAll(async ({ request }) => {
    // Restore the admin to no-2FA so subsequent specs in the suite continue to
    // log in unchanged. Mint a fresh JWT because the one from beforeAll has
    // likely expired (15-min TTL).
    if (!totpSecret) return;
    let accessToken: string;
    try {
      accessToken = await login(request, {
        email: ADMIN_EMAIL,
        password: ADMIN_PASSWORD,
        totp_code: generateTotp({ secret: totpSecret }),
      });
    } catch (err) {
      // If the admin is somehow already off TOTP, fall back to a plain login so
      // we don't leak state to the next spec.
      accessToken = await login(request, { email: ADMIN_EMAIL, password: ADMIN_PASSWORD });
    }

    const disableRes = await request.post(`${API_BASE}/api/v1/me/totp/disable`, {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: { current_password: ADMIN_PASSWORD },
    });
    if (!disableRes.ok() && disableRes.status() !== 422) {
      // 422 = TOTP not enabled — acceptable cleanup outcome.
      throw new Error(`TOTP disable failed: ${disableRes.status()} ${await disableRes.text()}`);
    }
  });

  test('valid TOTP code lands on /editor', async ({ page }) => {
    await reachTotpStage(page);

    await page.locator('input[autocomplete="one-time-code"]').fill(generateTotp({ secret: totpSecret }));
    await page.getByRole('button', { name: 'Verify and sign in' }).click();

    await page.waitForURL('**/dashboard', { timeout: 15_000 });
    expect(new URL(page.url()).pathname).toBe('/editor');
  });

  test('wrong TOTP code shows error banner and stays on TOTP stage', async ({ page }) => {
    await reachTotpStage(page);

    // Compute a real code, then mutate the last digit so the value is always a
    // valid 6-digit string but never matches the current authenticator window.
    const validCode = generateTotp({ secret: totpSecret });
    const lastDigit = Number(validCode.at(-1));
    const wrongCode = `${validCode.slice(0, -1)}${(lastDigit + 1) % 10}`;

    await page.locator('input[autocomplete="one-time-code"]').fill(wrongCode);
    await page.getByRole('button', { name: 'Verify and sign in' }).click();

    const alert = page.getByRole('alert').filter({ hasText: 'not valid' });
    await expect(alert).toBeVisible();
    await expect(page.getByText('Two-factor authentication')).toBeVisible();
    expect(new URL(page.url()).pathname).toBe('/login');
  });

  test('back button returns to credentials stage with email retained', async ({ page }) => {
    await reachTotpStage(page);

    await page.getByRole('button', { name: 'Back to sign-in' }).click();

    await expect(page.getByText('Sign in to your workspace')).toBeVisible();
    await expect(page.locator('#login-email')).toHaveValue(ADMIN_EMAIL);
  });

  test('malformed TOTP code triggers inline validation, no request fires', async ({ page }) => {
    await reachTotpStage(page);

    // Too-short code → pattern rule fires, no network call.
    const shortInputRequests = await countLoginRequests(page, async () => {
      await page.locator('input[autocomplete="one-time-code"]').fill('12');
      await page.getByRole('button', { name: 'Verify and sign in' }).click();
      await expect(
        page.getByText('Enter the 6-digit code from your authenticator.'),
      ).toBeVisible();
    });
    expect(shortInputRequests).toBe(0);

    // Empty submit → required rule fires, also no network call.
    const emptyInputRequests = await countLoginRequests(page, async () => {
      await page.locator('input[autocomplete="one-time-code"]').fill('');
      await page.getByRole('button', { name: 'Verify and sign in' }).click();
      await expect(page.getByText('Verification code is required.')).toBeVisible();
    });
    expect(emptyInputRequests).toBe(0);

    expect(new URL(page.url()).pathname).toBe('/login');
  });
});
