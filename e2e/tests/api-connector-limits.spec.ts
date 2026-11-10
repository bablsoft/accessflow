import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  inviteUserViaApi,
  loginViaApi,
  waitForInviteToken,
} from '../helpers/datasources';
import {
  createApiConnectorViaApi,
  deleteApiConnectorViaApi,
  getCurrentUserIdViaApi,
  grantApiConnectorPermissionViaApi,
  submitApiRequestViaApi,
  waitForApiRequestStatus,
  type CreatedApiConnector,
} from '../helpers/apiConnectors';
import { login } from '../helpers/login';
import { activeTabPanel, clickTab, findRowAcrossPages } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const SUBMITTER_PASSWORD = 'Submitter-Pwd!123';

// Provisioning the submitter (invite → Mailcrab → accept) takes a few seconds.
test.describe.configure({ timeout: 120_000 });

/**
 * Connector limits and budget (#974): the admin sets a 1-call-per-minute limit and a per-call
 * price on the Limits & budget tab, the list badge reflects it, and a second execution inside the
 * same minute is answered with the 429 that leaves the request APPROVED and queued.
 */
test.describe.serial('api connector limits and budget (#974)', () => {
  let adminToken = '';
  let submitterEmail = '';
  let submitterToken = '';
  let connector: CreatedApiConnector | null = null;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // No AI config and require_review_reads=false (the default), so a GET lands APPROVED.
    connector = await createApiConnectorViaApi(request, adminToken, {
      name: `E2E Limits 974 ${randomUUID().slice(0, 8)}`,
      aiAnalysisEnabled: false,
    });

    submitterEmail = `fe974-submitter-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminToken, submitterEmail, 'FE-974 Submitter', 'ANALYST');
    const token = await waitForInviteToken(request, submitterEmail);
    await acceptInvitationViaApi(request, token, SUBMITTER_PASSWORD, 'FE-974 Submitter');
    submitterToken = await loginViaApi(request, submitterEmail, SUBMITTER_PASSWORD);
    const submitterId = await getCurrentUserIdViaApi(request, submitterToken);
    await grantApiConnectorPermissionViaApi(request, adminToken, connector.id, submitterId, {
      canRead: true,
    });
  });

  test.afterAll(async ({ request }) => {
    if (connector) await deleteApiConnectorViaApi(request, adminToken, connector.id);
  });

  test('admin sets limits on the tab and the list badge shows them', async ({ page }) => {
    if (!connector) throw new Error('connector not created in beforeAll');
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/api-connectors/${connector.id}/settings`);
    await clickTab(page, 'Limits & budget');

    const panel = activeTabPanel(page);
    await panel.getByLabel('Calls per minute').fill('1');
    await panel.getByLabel('Cost per call (units)').fill('0.5');
    await panel.getByRole('button', { name: 'Save limits' }).click();
    await expect(page.locator('.ant-message').getByText('Limits saved')).toBeVisible();

    await page.goto('/api-connectors');
    const row = page.getByRole('row').filter({ hasText: connector.name });
    await findRowAcrossPages(page, row);
    await expect(row.getByTestId('connector-limits-badge')).toHaveText('1 / min');
  });

  test('a second execution inside the minute is queued, not failed', async ({ browser, request }) => {
    if (!connector) throw new Error('connector not created in beforeAll');
    const submit = async (justification: string) => {
      const submitted = await submitApiRequestViaApi(request, submitterToken, {
        connectorId: connector!.id,
        verb: 'GET',
        requestPath: '/actuator/health',
        justification,
      });
      await waitForApiRequestStatus(request, adminToken, submitted.id, 'APPROVED');
      return submitted.id;
    };
    const first = await submit('FE-974 first call');
    const second = await submit('FE-974 second call');

    const ctx = await browser.newContext();
    try {
      const page = await ctx.newPage();
      await login(page, submitterEmail, SUBMITTER_PASSWORD);

      await page.goto(`/api-requests/${first}`);
      await page.getByRole('button', { name: 'Execute' }).click();
      await expect(page.locator('.af-pill').getByText('Executed', { exact: true })).toBeVisible({
        timeout: 15_000,
      });

      await page.goto(`/api-requests/${second}`);
      await page.getByRole('button', { name: 'Execute' }).click();
      // The 429 is a warning toast carrying the server's localized detail.
      await expect(page.locator('.ant-message-warning')).toBeVisible({ timeout: 10_000 });
      await expect(page.getByTestId('api-request-throttled')).toContainText('Queued — retries at', {
        timeout: 10_000,
      });
      await expect(page.locator('.af-pill').getByText('Approved', { exact: true })).toBeVisible();
      // A queued APPROVED request stays cancellable by its submitter. Cancelling also keeps the
      // run job from retrying (and charging) it before the usage test reads the spend.
      await page.getByRole('button', { name: 'Cancel' }).click();
      await expect(page.locator('.af-pill').getByText('Cancelled', { exact: true })).toBeVisible({
        timeout: 10_000,
      });
    } finally {
      await ctx.close();
    }
  });

  test('the usage panel attributes the one executed call', async ({ page }) => {
    if (!connector) throw new Error('connector not created in beforeAll');
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/api-connectors/${connector.id}/settings?tab=limits`);
    // One executed call at 0.5 units; the throttled (then cancelled) call is never charged. No budget is set, so
    // the panel shows the plain month-to-date figure instead of the ring.
    await expect(activeTabPanel(page).getByTestId('connector-usage-spend')).toContainText(
      '0.5 units',
      { timeout: 15_000 },
    );
  });
});
