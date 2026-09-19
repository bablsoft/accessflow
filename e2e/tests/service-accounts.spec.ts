import { expect, test } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';
import { login } from '../helpers/login';
import { expandNavSection } from '../helpers/nav';
import {
  deactivateServiceAccountViaApi,
  findServiceAccountByEmailViaApi,
} from '../helpers/serviceAccounts';
import { activeTabPanel, clickTab, findRowAcrossPages } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Declared by ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS_0_* in docker-compose.e2e.yml. Read-only for
// this spec: it is never mutated, so the parallel project can run it alongside everything else.
const BOOTSTRAP_BOT_EMAIL = 'ci-bot@accessflow.test';
const BOOTSTRAP_BOT_NAME = 'E2E CI bot';
const BOOTSTRAP_KEY_NAME = 'e2e';

test.describe.configure({ timeout: 90_000 });

test.describe('service-accounts admin UI (#875)', () => {
  let adminAccessToken = '';
  const createdAccountIds: string[] = [];

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    for (const id of createdAccountIds) {
      try {
        await deactivateServiceAccountViaApi(request, adminAccessToken, id);
      } catch (err) {
        // eslint-disable-next-line no-console
        console.warn(`service account cleanup skipped for ${id}: ${String(err)}`);
      }
    }
  });

  test('creates an account from the sidebar, issues and rotates a key, restricts its tools', async ({
    page,
  }) => {
    const stamp = Date.now();
    const email = `e2e-bot-${stamp}@accessflow.test`;
    const displayName = `E2E bot ${stamp}`;

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await expandNavSection(page, 'Security & Access', 'Identity');
    await page.getByRole('link', { name: 'Service accounts' }).click();
    await expect(page).toHaveURL(/\/admin\/service-accounts$/);
    await expect(page.getByRole('heading', { name: 'Service accounts' })).toBeVisible();

    // Create through the modal — READONLY is preselected, the reviewer role would warn.
    await page.getByRole('button', { name: 'Create service account' }).first().click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Create service account' });
    await expect(dialog).toBeVisible();
    await dialog.getByLabel('Email', { exact: true }).fill(email);
    await dialog.getByLabel('Display name', { exact: true }).fill(displayName);
    await dialog.getByLabel('Description', { exact: true }).fill('nightly reporting');
    const createResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith('/api/v1/admin/service-accounts'),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Create', exact: true }).click();
    const response = await createResponse;
    expect(response.status()).toBe(201);
    const created = (await response.json()) as { id: string; role_name: string };
    createdAccountIds.push(created.id);
    expect(created.role_name).toBe('READONLY');

    // Create lands in the settings page on the overview tab.
    await page.waitForURL(`**/admin/service-accounts/${created.id}`, { timeout: 15_000 });
    await expect(page.getByRole('heading', { name: displayName })).toBeVisible();
    for (const tab of ['Overview', 'API keys', 'MCP tools', 'Limits', 'On-behalf-of principals', 'Activity']) {
      await expect(page.getByRole('tab', { name: tab })).toBeVisible();
    }
    await expect(page.getByTestId('bootstrap-banner')).toHaveCount(0);

    // Issue a key: the plaintext appears once, in the show-once modal.
    await clickTab(page, 'API keys');
    await activeTabPanel(page).getByRole('button', { name: 'Issue key' }).click();
    const issueDialog = page.getByRole('dialog').filter({ hasText: 'Issue an API key' });
    await issueDialog.getByLabel('Key name').fill('github-actions');
    await issueDialog.getByRole('button', { name: 'Issue key' }).click();
    const issued = page.getByRole('dialog').filter({ hasText: 'Copy the new API key' });
    await expect(issued).toBeVisible();
    await expect(issued.getByTestId('issued-raw-key')).toContainText(/^af_/);
    // The modal's X icon is also named "Close"; the footer button is the one the copy flow uses.
    await issued.locator('.ant-modal-footer').getByRole('button', { name: 'Close' }).click();
    const keyRow = activeTabPanel(page).getByRole('row').filter({ hasText: 'github-actions' });
    await expect(keyRow).toBeVisible();
    await expect(keyRow.getByText('Active')).toBeVisible();

    // Rotate it with a 12-hour grace: the replacement is shown once, the old key's end is stated.
    await keyRow.getByRole('button', { name: 'Rotate' }).click();
    const rotateDialog = page.getByRole('dialog').filter({ hasText: 'Rotate API key' });
    await rotateDialog.getByLabel('Grace period (hours)').fill('12');
    await rotateDialog.getByRole('button', { name: 'Rotate' }).click();
    const rotated = page.getByRole('dialog').filter({ hasText: 'Copy the replacement API key' });
    await expect(rotated).toBeVisible();
    await expect(rotated.getByTestId('superseded-key-note')).toContainText('keeps working until');
    await rotated.locator('.ant-modal-footer').getByRole('button', { name: 'Close' }).click();
    await expect(
      activeTabPanel(page).getByRole('row').filter({ hasText: 'github-actions' }),
    ).toHaveCount(2);

    // Restrict the MCP tools to one and check it survives a reload.
    await clickTab(page, 'MCP tools');
    const toolsPanel = activeTabPanel(page);
    await expect(toolsPanel.getByTestId('tools-enforcement-note')).toContainText('tools/list');
    await toolsPanel.getByRole('radio', { name: 'Only the selected tools' }).check();
    await expect(toolsPanel.getByTestId('tools-none-warning')).toBeVisible();
    // AntD hides the native input; the label text is the click target.
    await toolsPanel.getByText('validate_sql', { exact: true }).click();
    await expect(toolsPanel.getByRole('checkbox', { name: /validate_sql/ })).toBeChecked();
    const saveTools = page.waitForResponse(
      (r) => r.request().method() === 'PUT' && r.url().includes(`/admin/service-accounts/${created.id}`),
      { timeout: 15_000 },
    );
    await toolsPanel.getByRole('button', { name: 'Save allow-list' }).click();
    expect((await saveTools).status()).toBe(200);
    await expect(page.getByText('MCP tool allow-list updated')).toBeVisible();

    await page.reload();
    await clickTab(page, 'MCP tools');
    await expect(activeTabPanel(page).getByRole('checkbox', { name: /validate_sql/ })).toBeChecked();
    await expect(activeTabPanel(page).getByRole('checkbox', { name: /submit_query/ })).not.toBeChecked();

    // The list shows the restricted count and the UI badge.
    await page.getByRole('button', { name: 'Back to service accounts' }).click();
    const listRow = page.getByRole('row').filter({ hasText: email });
    await findRowAcrossPages(page, listRow);
    // The catalog size is the server's; only the allowed count is this spec's business.
    await expect(listRow.getByText(/^1 \/ \d+ tools$/)).toBeVisible();
    await expect(listRow.getByTestId('managed-by-tag')).toHaveText('UI');
  });

  test('renders the bootstrap-managed account read-only with its declared key locked', async ({
    page,
    request,
  }) => {
    const bot = await findServiceAccountByEmailViaApi(request, adminAccessToken, BOOTSTRAP_BOT_EMAIL);
    expect(bot, `bootstrap account ${BOOTSTRAP_BOT_EMAIL} is seeded by docker-compose.e2e.yml`).toBeDefined();
    expect(bot!.managed_by).toBe('BOOTSTRAP');

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/admin/service-accounts/${bot!.id}`);

    await expect(page.getByRole('heading', { name: BOOTSTRAP_BOT_NAME })).toBeVisible();
    await expect(page.getByTestId('bootstrap-banner')).toContainText('ACCESSFLOW_BOOTSTRAP_SERVICE_ACCOUNTS');
    const overview = activeTabPanel(page);
    await expect(overview.getByLabel('Display name', { exact: true })).toBeDisabled();
    await expect(overview.getByLabel('Description', { exact: true })).toBeEnabled();

    await clickTab(page, 'API keys');
    const keyRow = activeTabPanel(page).getByRole('row').filter({ hasText: BOOTSTRAP_KEY_NAME });
    await expect(keyRow.getByTestId('bootstrap-declared-tag')).toHaveText('Declared');
    const actions = keyRow.getByTestId('declared-key-actions');
    await expect(actions.getByRole('button', { name: 'Revoke' })).toBeDisabled();
    await expect(actions.getByRole('button', { name: 'Rotate' })).toBeDisabled();
    await actions.hover();
    await expect(page.getByRole('tooltip')).toContainText('re-import');
  });

  test('badges the agent on the users page and filters by principal type', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();

    const filterResponse = page.waitForResponse(
      (r) => r.url().includes('/api/v1/admin/users') && r.url().includes('principal_type=SERVICE_ACCOUNT'),
      { timeout: 15_000 },
    );
    await page.getByRole('combobox', { name: 'People and service accounts' }).click();
    await page.getByTitle('Service accounts only').click();
    expect((await filterResponse).ok()).toBe(true);

    const botRow = page.getByRole('row').filter({ hasText: BOOTSTRAP_BOT_EMAIL });
    await findRowAcrossPages(page, botRow);
    await expect(botRow.getByTestId('principal-type-tag')).toHaveText('Service account');
    await expect(page.getByRole('row').filter({ hasText: ADMIN_EMAIL })).toHaveCount(0);
  });
});
