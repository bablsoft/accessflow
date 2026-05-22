import {
  test,
  expect,
  type APIRequestContext,
  type Locator,
  type Page,
  type Route,
} from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Unique suffix keeps names unique across reruns against a long-lived database
// (the e2e stack reuses postgres data between `stack:up` cycles unless torn
// down with `-v`). Same pattern as AF-275 / AF-276 / AF-277.
const UNIQUE_SUFFIX = `af278-${Date.now()}`;
const SLACK_NAME = `e2e-slack-${UNIQUE_SUFFIX}`;
const WEBHOOK_NAME = `e2e-webhook-${UNIQUE_SUFFIX}`;
const DISCORD_NAME = `e2e-discord-${UNIQUE_SUFFIX}`;

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/admin/notification-channels that NotificationsPage
// issues on mount, so the cards (or empty state) render real content before we
// drive the UI. Same gate pattern as AF-277.
async function waitForChannelsListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/notification-channels(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

// Install a stub for POST /api/v1/admin/notification-channels/{id}/test that
// short-circuits the backend before it makes a real outbound HTTP call to
// Slack / Discord / a webhook target. Same shape as ai-configs.
async function stubTestEndpointOk(page: Page): Promise<void> {
  await page.route('**/api/v1/admin/notification-channels/*/test', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'OK', detail: 'Test notification dispatched' }),
    });
  });
}

async function stubTestEndpointError(
  page: Page,
  detail: string,
): Promise<void> {
  await page.route('**/api/v1/admin/notification-channels/*/test', async (route: Route) => {
    await route.fulfill({
      status: 502,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'about:blank',
        title: 'Bad Gateway',
        status: 502,
        detail,
        error: 'NOTIFICATION_DELIVERY_FAILED',
        timestamp: new Date().toISOString(),
      }),
    });
  });
}

interface CreatedChannel {
  id: string;
  name: string;
}

async function selectAntdOption(
  scope: Locator | Page,
  selectLabel: string | RegExp,
  optionLabel: string,
): Promise<void> {
  // Open the AntD Select by clicking its visible label / combobox cell. The
  // dropdown panel itself renders in a portal at the document root, so we
  // always look it up on `scope.page()` regardless of where the trigger lives.
  await scope.getByLabel(selectLabel).click();
  const root = 'page' in scope ? scope.page() : scope;
  await root
    .locator('.ant-select-item-option')
    .filter({ hasText: optionLabel })
    .first()
    .click();
}

async function deleteChannelViaApi(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/admin/notification-channels/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(
      `Notification channel cleanup (${id}) returned ${res.status()}: ${await res.text()}`,
    );
  }
}

// AF-278 covers the /admin/notifications CRUD + Test flows:
//   1. Create SLACK channel via modal → card appears.
//   2. Create WEBHOOK channel via modal → card appears.
//   3. Create DISCORD channel via modal → card appears.
//   4. Edit SLACK name → card reflects new name.
//   5. Row Test action on SLACK (stubbed OK) → success toast.
//   6. Row Test action on WEBHOOK (stubbed 502 ProblemDetail) → error toast.
//   7. Add channel with invalid URL → inline validation, no POST fired.
//   8. Delete SLACK via Popconfirm → card removed.
//   9. Delete WEBHOOK + DISCORD via Popconfirm → cards removed.
//
// describe.serial because the SLACK card moves through tests 1 → 4 → 5 → 8 and
// the WEBHOOK / DISCORD cards persist between tests 2/3 and the delete loop.
// The Playwright project is already workers=1, so this is belt-and-braces.
test.describe.serial('/admin/notifications — channel CRUD + test', () => {
  let adminAccessToken = '';
  const created = new Map<string, CreatedChannel>(); // logical-key → id+name
  let slackDisplayName = SLACK_NAME; // becomes `${SLACK_NAME}-renamed` in test 4

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    for (const c of created.values()) {
      await deleteChannelViaApi(request, adminAccessToken, c.id);
    }
  });

  test('1) create SLACK channel via modal → card appears', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    await page.getByRole('button', { name: 'Add channel' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add notification channel' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    await dialog.getByLabel('Name', { exact: true }).fill(SLACK_NAME);
    await selectAntdOption(dialog, 'Type', 'Slack');
    await dialog
      .getByLabel('Webhook URL')
      .fill('https://hooks.slack.com/services/T00000000/B00000000/XXXXXXXXXXXXXXXXXXXX');
    await dialog.getByLabel('Channel', { exact: true }).fill('#e2e');

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/notification-channels$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Create channel' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as { id: string; name: string };
    created.set('slack', { id: body.id, name: body.name });
    expect(body.name).toBe(SLACK_NAME);

    await expect(page.getByText('Channel created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(SLACK_NAME, { exact: true })).toBeVisible();
  });

  test('2) create WEBHOOK channel via modal → card appears', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    await page.getByRole('button', { name: 'Add channel' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add notification channel' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    await dialog.getByLabel('Name', { exact: true }).fill(WEBHOOK_NAME);
    await selectAntdOption(dialog, 'Type', 'Webhook');
    await dialog.getByLabel('URL', { exact: true }).fill('https://example.test/webhook');
    await dialog.getByLabel('Secret').fill(`s3cr3t-${UNIQUE_SUFFIX}`);
    await dialog.getByLabel('Timeout (seconds)').fill('10');

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/notification-channels$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Create channel' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as { id: string; name: string };
    created.set('webhook', { id: body.id, name: body.name });
    expect(body.name).toBe(WEBHOOK_NAME);

    await expect(page.getByText('Channel created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(WEBHOOK_NAME, { exact: true })).toBeVisible();
  });

  test('3) create DISCORD channel via modal → card appears', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    await page.getByRole('button', { name: 'Add channel' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add notification channel' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    await dialog.getByLabel('Name', { exact: true }).fill(DISCORD_NAME);
    await selectAntdOption(dialog, 'Type', 'Discord');
    await dialog
      .getByLabel('Discord webhook URL')
      .fill('https://discord.com/api/webhooks/1234567890/abcdefghijklmnop');
    await dialog.getByLabel('Override username').fill('AccessFlow E2E');

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/notification-channels$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Create channel' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as { id: string; name: string };
    created.set('discord', { id: body.id, name: body.name });
    expect(body.name).toBe(DISCORD_NAME);

    await expect(page.getByText('Channel created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(DISCORD_NAME, { exact: true })).toBeVisible();
  });

  test('4) edit SLACK channel name → card reflects new name', async ({ page }) => {
    const slack = created.get('slack');
    test.skip(!slack, 'Test 1 must succeed to seed the SLACK channel');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    // The card's Edit button shares its accessible name with every other card.
    // Scope to the card by finding the name text first and then the closest
    // ancestor; we just click the first Edit that surfaces the SLACK name in
    // the dialog title to confirm we hit the right one.
    const slackCard = page.locator('div', { hasText: SLACK_NAME }).filter({
      has: page.getByRole('button', { name: 'Edit' }),
    }).last();
    await slackCard.getByRole('button', { name: 'Edit' }).click();

    const editDialog = page.getByRole('dialog', {
      name: new RegExp(`Edit channel · ${escapeRegex(SLACK_NAME)}`),
    });
    await expect(editDialog).toBeVisible({ timeout: 10_000 });

    const renamed = `${SLACK_NAME}-renamed`;
    await editDialog.getByLabel('Name', { exact: true }).fill(renamed);

    const updateResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new RegExp(`/api/v1/admin/notification-channels/${slack!.id}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await editDialog.getByRole('button', { name: 'Save changes' }).click();
    const updateResponse = await updateResponsePromise;
    expect(updateResponse.status()).toBe(200);
    const body = (await updateResponse.json()) as { id: string; name: string };
    expect(body.name).toBe(renamed);

    await expect(page.getByText('Channel updated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(renamed, { exact: true })).toBeVisible();

    slackDisplayName = renamed;
    created.set('slack', { id: slack!.id, name: renamed });
  });

  test('5) row Test action on SLACK (stubbed OK) → success toast', async ({ page }) => {
    const slack = created.get('slack');
    test.skip(!slack, 'Test 1 must succeed to seed the SLACK channel');

    await stubTestEndpointOk(page);
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    const slackCard = page
      .locator('div', { hasText: slackDisplayName })
      .filter({ has: page.getByRole('button', { name: 'Test' }) })
      .last();

    const testResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new RegExp(`/api/v1/admin/notification-channels/${slack!.id}/test$`).test(r.url()),
      { timeout: 15_000 },
    );
    await slackCard.getByRole('button', { name: 'Test' }).click();
    expect((await testResponsePromise).status()).toBe(200);

    await expect(
      page.getByText('Test notification dispatched', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    await page.unroute('**/api/v1/admin/notification-channels/*/test');
  });

  test('6) row Test action on WEBHOOK (stubbed 502) → error toast', async ({ page }) => {
    const webhook = created.get('webhook');
    test.skip(!webhook, 'Test 2 must succeed to seed the WEBHOOK channel');

    await stubTestEndpointError(page, 'Test notification could not be delivered.');
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    const webhookCard = page
      .locator('div', { hasText: WEBHOOK_NAME })
      .filter({ has: page.getByRole('button', { name: 'Test' }) })
      .last();
    await webhookCard.getByRole('button', { name: 'Test' }).click();

    // adminErrorMessage maps error=NOTIFICATION_DELIVERY_FAILED to the i18n
    // string errors.notification_delivery_failed = "Test notification could
    // not be delivered.". Assert that literal text in the toast.
    await expect(
      page.getByText('Test notification could not be delivered.', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    await page.unroute('**/api/v1/admin/notification-channels/*/test');
  });

  test('7) create with invalid URL → inline validation, no POST fired', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    let postFired = false;
    const guard = (req: import('@playwright/test').Request): void => {
      if (
        req.method() === 'POST' &&
        /\/api\/v1\/admin\/notification-channels$/.test(req.url())
      ) {
        postFired = true;
      }
    };
    page.on('request', guard);

    try {
      await page.getByRole('button', { name: 'Add channel' }).click();
      const dialog = page.getByRole('dialog', { name: 'Add notification channel' });
      await expect(dialog).toBeVisible({ timeout: 10_000 });

      await dialog.getByLabel('Name', { exact: true }).fill(`e2e-invalid-${UNIQUE_SUFFIX}`);
      await selectAntdOption(dialog, 'Type', 'Slack');
      await dialog.getByLabel('Webhook URL').fill('not-a-url');
      await dialog.getByRole('button', { name: 'Create channel' }).click();

      // The Form.Item rule { required: true, type: 'url' } rejects submission.
      // AntD shows the field-level error explanation, and the modal stays open.
      await expect(dialog).toBeVisible();
      await expect(dialog.getByLabel('Webhook URL')).toHaveValue('not-a-url');

      // Settle for a tick so a stray POST would have time to be observed.
      await page.waitForTimeout(500);
      expect(postFired).toBe(false);

      await dialog.getByRole('button', { name: 'Cancel' }).click();
    } finally {
      page.off('request', guard);
    }
  });

  test('8) delete SLACK via Popconfirm → card removed', async ({ page }) => {
    const slack = created.get('slack');
    test.skip(!slack, 'Test 1 must succeed to seed the SLACK channel');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    const slackCard = page
      .locator('div', { hasText: slackDisplayName })
      .filter({ has: page.getByRole('button', { name: 'Delete channel' }) })
      .last();
    await slackCard.getByRole('button', { name: 'Delete channel' }).click();

    const popover = page
      .locator('.ant-popover')
      .filter({ hasText: 'Delete notification channel?' });
    await expect(popover).toBeVisible({ timeout: 10_000 });
    await expect(
      popover.getByText(`This will remove the channel named ${slackDisplayName}.`),
    ).toBeVisible();

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/admin/notification-channels/${slack!.id}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await popover.getByRole('button', { name: 'Delete' }).click();
    expect((await deleteResponsePromise).status()).toBe(204);

    await expect(
      page.getByText('Notification channel deleted', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(slackDisplayName, { exact: true })).toHaveCount(0, {
      timeout: 10_000,
    });

    created.delete('slack');
  });

  test('9) delete WEBHOOK + DISCORD via Popconfirm → cards removed', async ({ page }) => {
    const webhook = created.get('webhook');
    const discord = created.get('discord');
    test.skip(!webhook || !discord, 'Tests 2 and 3 must succeed to seed WEBHOOK + DISCORD');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/notifications');
    await waitForChannelsListReady(page);

    const targets: Array<{ key: 'webhook' | 'discord'; id: string; name: string }> = [
      { key: 'webhook', id: webhook!.id, name: WEBHOOK_NAME },
      { key: 'discord', id: discord!.id, name: DISCORD_NAME },
    ];

    for (const target of targets) {
      const card = page
        .locator('div', { hasText: target.name })
        .filter({ has: page.getByRole('button', { name: 'Delete channel' }) })
        .last();
      await card.getByRole('button', { name: 'Delete channel' }).click();

      const popover = page
        .locator('.ant-popover')
        .filter({ hasText: 'Delete notification channel?' });
      await expect(popover).toBeVisible({ timeout: 10_000 });

      const deleteResponsePromise = page.waitForResponse(
        (r) =>
          r.request().method() === 'DELETE' &&
          new RegExp(`/api/v1/admin/notification-channels/${target.id}$`).test(r.url()),
        { timeout: 15_000 },
      );
      await popover.getByRole('button', { name: 'Delete' }).click();
      expect((await deleteResponsePromise).status()).toBe(204);

      await expect(page.getByText(target.name, { exact: true })).toHaveCount(0, {
        timeout: 10_000,
      });
      created.delete(target.key);
    }
  });
});
