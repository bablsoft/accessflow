import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  inviteUserViaApi,
  loginViaApi,
  waitForInviteToken,
} from '../helpers/datasources';
import {
  approveApiReviewViaApi,
  createApiConnectorViaApi,
  deleteApiConnectorViaApi,
  executeApiRequestViaApi,
  getCurrentUserIdViaApi,
  grantApiConnectorPermissionViaApi,
  submitApiRequestViaApi,
  waitForApiRequestRouted,
  type CreatedApiConnector,
} from '../helpers/apiConnectors';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const SUBMITTER_PASSWORD = 'Submitter-Pwd!123';

test.describe.configure({ timeout: 90_000 });

test.describe.serial('api request replay and response diff (#888)', () => {
  let adminToken = '';
  let submitterEmail = '';
  let submitterToken = '';
  let connector: CreatedApiConnector | null = null;
  let secondRunId = '';

  /** Submits GET /actuator/health (public JSON) and drives it to EXECUTED. */
  async function executedHealthCall(request: APIRequestContext): Promise<string> {
    if (!connector) throw new Error('connector not created in beforeAll');
    const submitted = await submitApiRequestViaApi(request, submitterToken, {
      connectorId: connector.id,
      verb: 'GET',
      requestPath: '/actuator/health',
      justification: 'AF-888 replay and diff',
    });
    const routed = await waitForApiRequestRouted(request, adminToken, submitted.id);
    if (routed === 'PENDING_REVIEW') await approveApiReviewViaApi(request, adminToken, submitted.id);
    expect(await executeApiRequestViaApi(request, submitterToken, submitted.id)).toBe('EXECUTED');
    return submitted.id;
  }

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // No AI config is seeded, so analysis is off to keep routing deterministic.
    connector = await createApiConnectorViaApi(request, adminToken, {
      name: `E2E API Replay 888 ${Date.now()}`,
      aiAnalysisEnabled: false,
    });

    submitterEmail = `af888-submitter-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminToken, submitterEmail, 'AF-888 Submitter', 'ANALYST');
    const invite = await waitForInviteToken(request, submitterEmail);
    await acceptInvitationViaApi(request, invite, SUBMITTER_PASSWORD, 'AF-888 Submitter');
    submitterToken = await loginViaApi(request, submitterEmail, SUBMITTER_PASSWORD);
    const submitterId = await getCurrentUserIdViaApi(request, submitterToken);
    await grantApiConnectorPermissionViaApi(request, adminToken, connector.id, submitterId, {
      canRead: true,
    });

    await executedHealthCall(request);
    secondRunId = await executedHealthCall(request);
  });

  test.afterAll(async ({ request }) => {
    if (connector) await deleteApiConnectorViaApi(request, adminToken, connector.id);
  });

  test('the second run compares against the first and can be replayed', async ({ page, request }) => {
    await login(page, submitterEmail, SUBMITTER_PASSWORD);
    await page.goto(`/api-requests/${secondRunId}`);

    // The diff card resolves the previous run of the same connector + path.
    await expect(page.getByText('Compare to previous run')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByRole('link', { name: 'Open the previous run' })).toBeVisible({
      timeout: 15_000,
    });

    await page.getByRole('button', { name: /Replay/ }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Replay this API call?' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Replay' }).click();

    await page.waitForURL((url) => /\/api-requests\/[0-9a-f-]{36}$/.test(url.pathname) && !url.pathname.endsWith(secondRunId), {
      timeout: 15_000,
    });
    const newId = page.url().split('/').pop() ?? '';
    expect(newId).not.toBe(secondRunId);
    // The new request carries the server-set justification naming its source.
    await expect(page.getByText(`Replay of API request ${secondRunId}`)).toBeVisible({ timeout: 15_000 });

    const res = await request.get(`${apiBase()}/api/v1/api-requests/${newId}`, {
      headers: { Authorization: `Bearer ${submitterToken}` },
    });
    expect(res.status()).toBe(200);
    const body = (await res.json()) as { status: string; request_path: string; connector_id: string };
    expect(body.request_path).toBe('/actuator/health');
    expect(body.connector_id).toBe(connector?.id);
    expect(['PENDING_AI', 'PENDING_REVIEW', 'APPROVED']).toContain(body.status);
  });
});
