import { test, expect, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';

// All four scenarios share a single page and the /login URL — the form lives in
// an in-memory React tree, so we reset its state by reloading rather than by
// pinging the backend. This keeps the spec entirely DOM-driven and self-contained.
async function gotoLogin(page: Page): Promise<void> {
  await page.goto('/login');
  await expect(page.locator('#login-email')).toBeVisible();
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

test.describe('login failure modes', () => {
  test.beforeEach(async ({ page }) => {
    await gotoLogin(page);
  });

  test('empty email shows required error and does not fire a request', async ({ page }) => {
    await page.locator('#login-password').fill('AnyPassword!1');

    const requests = await countLoginRequests(page, async () => {
      await page.locator('button[type="submit"]').click();
      await expect(page.getByText('Email is required.')).toBeVisible();
    });

    expect(requests).toBe(0);
    expect(new URL(page.url()).pathname).toBe('/login');
  });

  test('invalid email format shows inline error and does not fire a request', async ({ page }) => {
    await page.locator('#login-email').fill('not-an-email');
    await page.locator('#login-password').fill('AnyPassword!1');

    const requests = await countLoginRequests(page, async () => {
      await page.locator('button[type="submit"]').click();
      await expect(page.getByText('Enter a valid email address.')).toBeVisible();
    });

    expect(requests).toBe(0);
    expect(new URL(page.url()).pathname).toBe('/login');
  });

  test('wrong password yields a 401 alert with trace id, clears password, keeps email', async ({
    page,
  }) => {
    await page.locator('#login-email').fill(ADMIN_EMAIL);
    await page.locator('#login-password').fill('WrongPassword!');
    await page.locator('button[type="submit"]').click();

    const alert = page.getByRole('alert').filter({ hasText: 'Invalid email or password.' });
    await expect(alert).toBeVisible();
    await expect(alert.getByText(/Trace ID:/i)).toBeVisible();

    // Email retained, password cleared.
    await expect(page.locator('#login-email')).toHaveValue(ADMIN_EMAIL);
    await expect(page.locator('#login-password')).toHaveValue('');
    expect(new URL(page.url()).pathname).toBe('/login');
  });

  test('server 500 surfaces an alert with the injected trace id', async ({ page }) => {
    const traceId = 'e2e-server-error-trace';
    // TraceIdFooter truncates ids > 12 chars to `${slice(0,8)}…${slice(-4)}`.
    const truncatedTrace = `${traceId.slice(0, 8)}…${traceId.slice(-4)}`;

    await page.route('**/api/v1/auth/login', async (route) => {
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Internal Server Error',
          status: 500,
          detail: 'Synthetic failure injected by e2e test.',
          traceId,
        }),
      });
    });

    try {
      await page.locator('#login-email').fill(ADMIN_EMAIL);
      await page.locator('#login-password').fill('AnyPassword!1');
      await page.locator('button[type="submit"]').click();

      const alert = page.getByRole('alert');
      await expect(alert.first()).toBeVisible();
      await expect(page.getByText(truncatedTrace)).toBeVisible();
      expect(new URL(page.url()).pathname).toBe('/login');
    } finally {
      await page.unroute('**/api/v1/auth/login');
    }
  });
});
