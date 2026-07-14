import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  createReviewPlanViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
  type CreatedReviewPlan,
} from '../helpers/datasources';
import {
  createApiConnectorViaApi,
  deleteApiConnectorViaApi,
  uploadApiSchemaViaApi,
  type CreatedApiConnector,
} from '../helpers/apiConnectors';

// AF-567 — "Request access" for API connectors. End-to-end: an analyst requests
// time-boxed access to an API connector (scoped to one operation) → an admin
// approves it in the shared queue → the grant materialises as a row on the
// connector's permissions tab with an expiry → an admin revokes it early and
// the row disappears. Expiry-by-job is covered by the backend
// AccessGrantLifecycleIntegrationTest (same as the datasource flow).

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const REQUESTER_PASSWORD = 'Requester-Pwd!123';

const OPENAPI_DOC = JSON.stringify({
  openapi: '3.0.0',
  info: { title: 'Petstore', version: '1.0.0' },
  paths: {
    '/pets': {
      get: { operationId: 'listPets', summary: 'List pets', responses: { '200': { description: 'ok' } } },
      post: { operationId: 'createPet', responses: { '201': { description: 'created' } } },
    },
  },
});

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

test.describe.configure({ timeout: 120_000 });

test.describe.serial('access requests for API connectors (AF-567)', () => {
  let connector: CreatedApiConnector | null = null;
  let reviewPlan: CreatedReviewPlan | null = null;
  let adminToken = '';
  const requesterEmail = `af567-requester-${randomUUID()}@e2e.local`;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // One-stage ADMIN review plan so the seeded e2e admin can approve.
    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `AF-567 Plan ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
    });
    connector = await createApiConnectorViaApi(request, adminToken, {
      name: `AF-567 Connector ${Date.now()}`,
      aiAnalysisEnabled: false,
      reviewPlanId: reviewPlan.id,
    });
    // The uploaded schema is what feeds the operation catalog of the allowed-operations selector.
    await uploadApiSchemaViaApi(request, adminToken, connector.id, { rawContent: OPENAPI_DOC });
    // Requester is a fresh ANALYST with NO permission on the connector — JIT access
    // exists precisely to obtain one — and distinct from the admin approver so the
    // self-approval block never trips.
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminToken, requesterEmail, 'AF-567 Requester', 'ANALYST');
    const token = await waitForInviteToken(request, requesterEmail);
    await acceptInvitationViaApi(request, token, REQUESTER_PASSWORD, 'AF-567 Requester');
  });

  test.afterAll(async ({ request }) => {
    if (connector) await deleteApiConnectorViaApi(request, adminToken, connector.id);
  });

  test('request → approve → grant on permissions tab → revoke', async ({ page, request }) => {
    const connectorName = connector!.name;

    // 1. Requester submits a connector access request scoped to one operation.
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await page.goto('/access-requests');
    // The Segmented control's radio inputs are visually hidden — click the visible label.
    await page.locator('.ant-segmented-item-label', { hasText: 'API Connection' }).click();
    // Connector picker is the first combobox once the connector branch is shown.
    await page.getByRole('combobox').first().click();
    await page.locator('.ant-select-item-option').filter({ hasText: connectorName }).click();
    await page.keyboard.press('Escape');
    // Scope to the read operation from the uploaded Petstore catalog.
    await page.getByRole('combobox').nth(1).click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'GET /pets' }).click();
    await page.keyboard.press('Escape');
    await page.getByPlaceholder('Why do you need this access?').fill('Temporary read access to the API');
    await page.getByRole('button', { name: 'Submit request' }).click();
    await expect(page.getByText('Access request submitted')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Pending', { exact: true }).first()).toBeVisible({ timeout: 15_000 });
    // The request row carries the API Connection kind tag and the operations-count tag.
    const myRow = page.getByRole('row').filter({ hasText: connectorName });
    await expect(myRow.getByText('API Connection')).toBeVisible();
    await expect(myRow.getByText('1 operation', { exact: true })).toBeVisible();

    // 2. Admin approves it in the shared access-request queue.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/access-requests');
    const queueRow = page.getByRole('row').filter({ hasText: requesterEmail });
    await expect(queueRow).toBeVisible({ timeout: 15_000 });
    await expect(queueRow.getByText('API Connection')).toBeVisible();
    await expect(queueRow.getByText(connectorName)).toBeVisible();
    await queueRow.getByRole('button', { name: 'Approve' }).click();
    await expect(page.getByText('Access request approved')).toBeVisible({ timeout: 15_000 });

    // 3. The grant materialised as a time-boxed row on the connector's permissions tab.
    await page.goto(`/api-connectors/${connector!.id}/settings`);
    await page.getByRole('tab', { name: 'Permissions' }).click();
    const permissionRow = page
      .locator('.ant-tabs-tabpane-active')
      .getByRole('row')
      .filter({ hasText: requesterEmail });
    await expect(permissionRow).toBeVisible({ timeout: 15_000 });
    // Time-boxed: the expiry cell is a concrete timestamp, not the '—' of a standing grant.
    await expect(permissionRow.getByText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/)).toBeVisible();

    // 4. Requester sees the APPROVED grant with a remaining-TTL chip.
    await loginViaUi(page, requesterEmail, REQUESTER_PASSWORD);
    await page.goto('/access-requests');
    await expect(page.getByText('Approved', { exact: true }).first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/left$/).first()).toBeVisible({ timeout: 15_000 });

    // 5. Admin revokes the grant early; the permission row disappears and the
    //    request lands in REVOKED for the requester.
    const requesterToken = await loginViaApi(request, requesterEmail, REQUESTER_PASSWORD);
    const mineRes = await request.get(`${apiBase()}/api/v1/access-requests?status=APPROVED`, {
      headers: { Authorization: `Bearer ${requesterToken}` },
    });
    expect(mineRes.ok()).toBeTruthy();
    const mine = (await mineRes.json()) as { content: Array<{ id: string; connector_id: string | null }> };
    const grant = mine.content.find((r) => r.connector_id === connector!.id);
    expect(grant).toBeTruthy();
    const revokeRes = await request.post(
      `${apiBase()}/api/v1/admin/access-requests/${grant!.id}/revoke`,
      { headers: { Authorization: `Bearer ${adminToken}` }, data: { comment: 'e2e early revoke' } },
    );
    expect(revokeRes.ok()).toBeTruthy();

    await page.reload();
    await expect(page.getByText('Revoked', { exact: true }).first()).toBeVisible({ timeout: 15_000 });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/api-connectors/${connector!.id}/settings`);
    await page.getByRole('tab', { name: 'Permissions' }).click();
    await expect(
      page.locator('.ant-tabs-tabpane-active').getByRole('row').filter({ hasText: requesterEmail }),
    ).toHaveCount(0, { timeout: 15_000 });
  });
});
