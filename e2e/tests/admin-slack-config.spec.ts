import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const DEFAULT_API_BASE = 'http://localhost:8080';

const APP_ID = `A-E2E-${Date.now()}`;
const DEFAULT_CHANNEL = 'C0E2E0001';

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

// The page issues GET /api/v1/admin/slack-app-config on mount: 200 when configured,
// 404 when unconfigured — both flow into the loaded state, so accept status < 500.
async function waitForSlackConfigLoaded(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/slack-app-config$/.test(r.url()) &&
      r.status() < 500,
    { timeout: 15_000 },
  );
}

// AF-362 — Slack app admin config + self-service account linking.
// describe.serial: the second test reads the configured state from the first;
// afterAll deletes the slack_app_config row so reruns against a long-lived
// e2e database stay idempotent.
test.describe.serial('AF-362 — Slack integration', () => {
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }: { request: APIRequestContext }) => {
    if (adminAccessToken) {
      await request.delete(`${apiBase()}/api/v1/admin/slack-app-config`, {
        headers: { Authorization: `Bearer ${adminAccessToken}` },
      });
    }
  });

  // Asserts attributes rather than clicking: the link targets the live public docs site,
  // and following it would make this suite depend on the network.
  test('0) the page header deep-links to the matching section of the public docs', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/slack');
    await waitForSlackConfigLoaded(page);

    const docsLink = page.getByRole('link', { name: /view docs/i });
    await expect(docsLink).toBeVisible();
    await expect(docsLink).toHaveAttribute(
      'href',
      'https://accessflow.bablsoft.com/docs/#cfg-slack',
    );
    await expect(docsLink).toHaveAttribute('target', '_blank');
    await expect(docsLink).toHaveAttribute('rel', 'noopener noreferrer');
  });

  test('1) admin configures the Slack app → save succeeds and secrets are masked on reload', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/slack');
    await waitForSlackConfigLoaded(page);

    await expect(page.getByRole('heading', { name: 'Slack app' })).toBeVisible();

    await page.getByLabel('App ID').fill(APP_ID);
    await page.getByLabel('Default channel ID').fill(DEFAULT_CHANNEL);
    await page.getByLabel('Bot token').fill('xoxb-e2e-bot-token');
    await page.getByLabel('Signing secret').fill('e2e-signing-secret');

    const saveResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/slack-app-config$/.test(r.url()),
      { timeout: 15_000 },
    );
    // The Save button carries a CheckOutlined icon, so its accessible name is "check Save";
    // match on the substring rather than exact text.
    await page.getByRole('button', { name: 'Save' }).click();
    const response = await saveResponse;
    expect(response.status()).toBe(200);
    const body = (await response.json()) as { has_bot_token?: boolean; app_id?: string };
    expect(body.has_bot_token).toBe(true);
    expect(body.app_id).toBe(APP_ID);

    await expect(
      page.getByText('Slack app configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    // Reload: app_id persists, secrets come back masked (never the real value).
    await page.reload();
    await waitForSlackConfigLoaded(page);
    await expect(page.getByLabel('App ID')).toHaveValue(APP_ID);
    await expect(page.getByLabel('Bot token')).toHaveValue('********');
  });

  test('2) user generates a Slack link code from their profile', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/profile');

    const slackCard = page.locator('.ant-card').filter({ hasText: 'Slack account' });
    await expect(slackCard).toBeVisible({ timeout: 10_000 });

    const codeResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/integrations\/slack\/link-codes$/.test(r.url()),
      { timeout: 15_000 },
    );
    await slackCard.getByRole('button', { name: 'Generate link code' }).click();
    expect((await codeResponse).status()).toBe(201);

    // The issued code is shown as a ready-to-paste slash command.
    await expect(slackCard.getByText(/\/accessflow link /)).toBeVisible({ timeout: 10_000 });
  });
});
