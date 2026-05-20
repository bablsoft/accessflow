import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Mailcrab HTTP/JSON API is published to the host by docker-compose.e2e.yml so
// Playwright can poll it directly. SMTP (1025) stays internal to the compose
// network and is only reached by the backend.
const MAILCRAB_BASE = 'http://localhost:1080';

const RESET_URL_REGEX = /\/reset-password\/([A-Za-z0-9_-]+)/;

interface MailcrabSummary {
  id: string;
  time?: number;
  to?: Array<{ email?: string } | string>;
  subject?: string;
}

interface MailcrabMessage extends MailcrabSummary {
  text?: string;
  html?: string;
}

// Purge any captured emails so the next `waitForResetToken` probe is
// unambiguous about which message belongs to the test that just ran.
// Mailcrab clears the inbox via POST /api/delete-all (DELETE /api/messages
// returns 405).
async function purgeMailcrab(request: APIRequestContext): Promise<void> {
  const res = await request.post(`${MAILCRAB_BASE}/api/delete-all`);
  if (!res.ok() && res.status() !== 404) {
    throw new Error(`Mailcrab purge failed: ${res.status()} ${await res.text()}`);
  }
}

function recipientMatches(summary: MailcrabSummary, recipient: string): boolean {
  if (!summary.to) return false;
  return summary.to.some((entry) => {
    if (typeof entry === 'string') {
      return entry.toLowerCase().includes(recipient.toLowerCase());
    }
    return entry.email?.toLowerCase() === recipient.toLowerCase();
  });
}

// Poll Mailcrab until a message addressed to `recipient` shows up, then extract
// the one-time token from the reset URL embedded in the body. Mailcrab returns
// the body as `text` and/or `html`; either is fine — the URL is in both.
async function waitForResetToken(
  request: APIRequestContext,
  recipient: string,
  timeoutMs = 15_000,
): Promise<string> {
  const deadline = Date.now() + timeoutMs;
  let lastError = '';
  while (Date.now() < deadline) {
    const listRes = await request.get(`${MAILCRAB_BASE}/api/messages`);
    if (listRes.ok()) {
      const summaries = (await listRes.json()) as MailcrabSummary[];
      const match = [...summaries]
        .reverse()
        .find((m) => recipientMatches(m, recipient));
      if (match) {
        const detailRes = await request.get(`${MAILCRAB_BASE}/api/message/${match.id}`);
        if (detailRes.ok()) {
          const detail = (await detailRes.json()) as MailcrabMessage;
          const body = `${detail.text ?? ''}\n${detail.html ?? ''}`;
          const m = body.match(RESET_URL_REGEX);
          if (m) {
            return m[1];
          }
          lastError = `Email found for ${recipient} but no reset token URL in body`;
        } else {
          lastError = `Mailcrab GET /api/message/${match.id} returned ${detailRes.status()}`;
        }
      } else {
        lastError = `No Mailcrab message addressed to ${recipient} yet (${summaries.length} total)`;
      }
    } else {
      lastError = `Mailcrab GET /api/messages returned ${listRes.status()}`;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Timed out waiting for password reset email: ${lastError}`);
}

// Drives the /forgot-password page the same way a real user would — fills the
// email field, clicks the primary submit, and waits for the success copy.
async function requestResetViaUi(page: Page, email: string): Promise<void> {
  await page.goto('/forgot-password');
  await page.getByLabel('Email').fill(email);
  await page.locator('button[type="submit"]').click();
  await expect(page.getByText('Check your email')).toBeVisible({ timeout: 10_000 });
}

// Drives the /reset-password/{token} page to actually consume a token. Used by
// the afterAll cleanup so the rest of the e2e suite gets the seeded password
// back, regardless of which scenarios above ran.
async function consumeResetToken(
  page: Page,
  token: string,
  newPassword: string,
): Promise<void> {
  await page.goto(`/reset-password/${token}`);
  // Wait for the preview to load (subtitle renders only when preview resolves).
  await expect(
    page.getByText(/You are resetting the password for/),
  ).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('New password', { exact: true }).fill(newPassword);
  await page.getByLabel('Confirm new password', { exact: true }).fill(newPassword);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/login', { timeout: 10_000 });
  await expect(
    page.getByText('Password updated. Sign in to continue.'),
  ).toBeVisible();
}

test.describe('forgot-password → reset-password full flow', () => {
  test.beforeEach(async ({ request }) => {
    await purgeMailcrab(request);
  });

  // ── 1. Invalid token — no DB lookup hits, error alert renders, no form ──────────
  test('invalid token shows the not-found alert and no form', async ({ page }) => {
    await page.goto('/reset-password/this-token-does-not-exist');
    // Server returns 404 PASSWORD_RESET_NOT_FOUND; util maps it to this copy.
    const alert = page
      .getByRole('alert')
      .filter({ hasText: 'This reset link is invalid.' });
    await expect(alert).toBeVisible({ timeout: 10_000 });
    await expect(
      page.getByLabel('New password', { exact: true }),
    ).toHaveCount(0);
  });

  // ── 2. Mismatched confirm — inline validation fires; no reset request leaves ────
  test('mismatched confirm blocks submission and surfaces inline error', async ({
    page,
    request,
  }) => {
    await requestResetViaUi(page, ADMIN_EMAIL);
    const token = await waitForResetToken(request, ADMIN_EMAIL);

    await page.goto(`/reset-password/${token}`);
    await expect(
      page.getByText(`You are resetting the password for ${ADMIN_EMAIL}`),
    ).toBeVisible({ timeout: 10_000 });

    await page.getByLabel('New password', { exact: true }).fill('Pwd-One!123');
    await page
      .getByLabel('Confirm new password', { exact: true })
      .fill('Pwd-Two!456');

    let resetRequestCount = 0;
    const handler = (req: { url(): string; method(): string }): void => {
      if (
        req.method() === 'POST' &&
        /\/api\/v1\/auth\/password\/reset\//.test(req.url())
      ) {
        resetRequestCount += 1;
      }
    };
    page.on('request', handler);
    try {
      await page.locator('button[type="submit"]').click();
      await expect(page.getByText('Passwords do not match.')).toBeVisible();
    } finally {
      page.off('request', handler);
    }

    expect(resetRequestCount).toBe(0);
    expect(new URL(page.url()).pathname).toBe(`/reset-password/${token}`);
  });

  // ── 3. Happy path — full flow, including login with the new password ───────────
  test('happy path: forgot link → email → reset → success banner → login', async ({
    page,
    request,
  }) => {
    const NEW_PASSWORD = 'Pwd-Happy!123';

    // From /login, click the "Forgot?" link to land on /forgot-password.
    await page.goto('/login');
    await page.getByRole('link', { name: 'Forgot?' }).click();
    await page.waitForURL('**/forgot-password', { timeout: 5_000 });

    // Submit the admin email through the form.
    await page.getByLabel('Email').fill(ADMIN_EMAIL);
    await page.locator('button[type="submit"]').click();
    await expect(page.getByText('Check your email')).toBeVisible({ timeout: 10_000 });

    // Pull the token out of the captured email.
    const token = await waitForResetToken(request, ADMIN_EMAIL);

    // Visit /reset-password/{token}; preview should mention the admin email.
    await page.goto(`/reset-password/${token}`);
    await expect(
      page.getByText(`You are resetting the password for ${ADMIN_EMAIL}`),
    ).toBeVisible({ timeout: 10_000 });

    // Fill matching new password + confirm, submit, wait for the redirect.
    await page.getByLabel('New password', { exact: true }).fill(NEW_PASSWORD);
    await page
      .getByLabel('Confirm new password', { exact: true })
      .fill(NEW_PASSWORD);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/login', { timeout: 10_000 });

    // Success banner ships from React Router state, not a query param.
    await expect(
      page.getByText('Password updated. Sign in to continue.'),
    ).toBeVisible();

    // Log in with the brand-new password → land on /editor.
    await page.locator('#login-email').fill(ADMIN_EMAIL);
    await page.locator('#login-password').fill(NEW_PASSWORD);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/editor', { timeout: 15_000 });
  });

  // ── 4. Replay a used token — server rejects, UI surfaces the invalid-link alert ─
  test('replaying a used token surfaces the invalid-link alert', async ({
    page,
    request,
  }) => {
    const NEW_PASSWORD = 'Pwd-Replay!789';

    // Mint + consume a fresh token through the UI.
    await requestResetViaUi(page, ADMIN_EMAIL);
    const token = await waitForResetToken(request, ADMIN_EMAIL);
    await consumeResetToken(page, token, NEW_PASSWORD);

    // Now navigate to the same token again — the preview endpoint returns 422
    // PASSWORD_RESET_ALREADY_USED; util maps it to the "already used" copy.
    await page.goto(`/reset-password/${token}`);
    const alert = page
      .getByRole('alert')
      .filter({ hasText: 'This reset link has already been used.' });
    await expect(alert).toBeVisible({ timeout: 10_000 });
    await expect(
      page.getByLabel('New password', { exact: true }),
    ).toHaveCount(0);
  });

  // Put the admin password back where the rest of the suite expects it.
  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    const request = context.request;
    try {
      await purgeMailcrab(request);
      await requestResetViaUi(page, ADMIN_EMAIL);
      const token = await waitForResetToken(request, ADMIN_EMAIL);
      await consumeResetToken(page, token, ADMIN_PASSWORD);
    } finally {
      await context.close();
    }
  });
});
