import { test, expect, type Page } from '@playwright/test';
import { login } from '../helpers/login';

// AF-906. `/admin/help-agent` is an org-singleton row that gates a launcher visible to every
// signed-in user, so this spec deliberately never leaves the agent switched on: it reads the
// defaults and drives one save the server is expected to reject. It still runs in the `serial`
// project — if an enable ever did land, the fixed launcher would appear in every concurrent
// spec's viewport, which is not a failure mode worth risking for parallelism.

async function waitForConfigLoaded(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/help-agent$/.test(r.url()) &&
      r.status() < 500,
    { timeout: 15_000 },
  );
}

function switchInItem(page: Page, labelText: string) {
  return page.locator('.ant-form-item').filter({ hasText: labelText }).getByRole('switch');
}

test.describe.serial('/admin/help-agent — help assistant settings', () => {
  test('1) initial load → defaults render with the assistant off', async ({ page }) => {
    await login(page);
    await page.goto('/admin/help-agent');
    await waitForConfigLoaded(page);

    await expect(page.getByRole('heading', { name: 'Help assistant' })).toBeVisible();
    await expect(switchInItem(page, 'Enable the help assistant')).not.toBeChecked();

    // The bundled corpus is reported even before anything has been indexed.
    await expect(page.getByText('Bundled with this build')).toBeVisible();
    await expect(page.getByText('Never indexed')).toBeVisible();
  });

  test('2) enabling with no AI configuration → server explains why, nothing is saved',
    async ({ page }) => {
      await login(page);
      await page.goto('/admin/help-agent');
      await waitForConfigLoaded(page);

      await switchInItem(page, 'Enable the help assistant').click();

      const savePromise = page.waitForResponse(
        (r) => r.request().method() === 'PUT' && /\/api\/v1\/admin\/help-agent$/.test(r.url()),
        { timeout: 15_000 },
      );
      await page.getByRole('button', { name: /Save changes/ }).click();
      const saveResponse = await savePromise;
      expect(saveResponse.status()).toBe(400);

      // The toast carries the backend's own localized `detail`, not a generic failure message.
      await expect(
        page.getByText('Select an AI configuration before enabling the help agent'),
      ).toBeVisible({ timeout: 10_000 });

      // And the row is untouched: a reload still shows the assistant off.
      await page.reload();
      await waitForConfigLoaded(page);
      await expect(switchInItem(page, 'Enable the help assistant')).not.toBeChecked();
    });

  test('3) the launcher stays hidden for an organization that never enabled it', async ({ page }) => {
    await login(page);
    const availability = page.waitForResponse((r) =>
      /\/api\/v1\/help-chat\/availability$/.test(r.url()),
    );
    await page.goto('/editor');
    await availability;

    await expect(page.getByRole('button', { name: 'Ask the help assistant' })).toHaveCount(0);
  });
});
