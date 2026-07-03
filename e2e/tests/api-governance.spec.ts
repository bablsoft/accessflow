import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
} from '../helpers/datasources';
import { createApiConnectorViaApi } from '../helpers/apiConnectors';

// AF-500: API Access Governance — admin creates an API connector via the UI, uploads an OpenAPI
// schema, and sees the parsed operation catalog. Seeded admin comes from the bootstrap module.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const CONNECTOR_NAME = `Petstore ${Date.now()}`;

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

test('admin creates an API connector and uploads an OpenAPI schema', async ({ page }) => {
  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  // Connector list
  await page.goto('/api-connectors');
  await page.waitForResponse(
    (r) => r.request().method() === 'GET' && r.url().includes('/api/v1/api-connectors') && r.ok(),
  );

  // Create connector
  await page.getByRole('button', { name: /New connector/i }).click();
  await page.getByLabel('Name', { exact: true }).fill(CONNECTOR_NAME);
  await page.getByLabel('Base URL').fill('https://petstore.example.com');
  // AI analysis is on by default, so an AI config must be assigned (#512). Pick the seeded one.
  await page.getByLabel('AI config').click();
  await page.getByTitle(/e2e-mock-openai/).click();
  const createResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/api-connectors') && r.ok(),
  );
  await page.getByRole('button', { name: 'Save' }).click();
  await createResponse;

  // Lands on the connector settings page
  await page.waitForURL('**/api-connectors/*/settings', { timeout: 15_000 });

  // Schema tab → upload OpenAPI
  await page.getByRole('tab', { name: 'Schema' }).click();
  await page.getByPlaceholder('Schema document').fill(OPENAPI_DOC);
  const uploadResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes('/schemas') && r.ok(),
  );
  await page.getByRole('button', { name: 'Upload schema' }).click();
  await uploadResponse;

  // Operations tab shows the parsed catalog
  await page.getByRole('tab', { name: 'Operations' }).click();
  await expect(page.getByText('listPets')).toBeVisible();
  await expect(page.getByText('createPet')).toBeVisible();

  // Connector appears in the list with a present schema
  await page.goto('/api-connectors');
  await expect(page.getByText(CONNECTOR_NAME)).toBeVisible();
});

test('API Requests and API Reviews pages render their filter bars (#512)', async ({ page }) => {
  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  // API Requests list: aligned with Query History — search + status/connector/verb/risk filters,
  // plus the submitter column and trace/span id filters (#517).
  await page.goto('/api-requests');
  await expect(page.getByPlaceholder('Search by ID, connector, path')).toBeVisible();
  await expect(page.getByText('All statuses')).toBeVisible();
  await expect(page.getByText('All methods')).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'Submitter' })).toBeVisible();
  await expect(page.getByLabel('Trace ID')).toBeVisible();
  await expect(page.getByLabel('Span ID')).toBeVisible();

  // API Reviews queue: same filter pattern (connector/verb/risk + search).
  await page.goto('/api-reviews');
  await expect(page.getByPlaceholder('Search by connector, path')).toBeVisible();
  await expect(page.getByText('All connectors')).toBeVisible();
});

test('admin edits an API connector permission in place (AF-530)', async ({ page, request }) => {
  // Seed a second, usable user via invite + accept (bootstrap only seeds the admin).
  const memberEmail = `perm-edit-${Date.now()}@accessflow.test`;
  const adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  await purgeMailcrab(request);
  await inviteUserViaApi(request, adminToken, memberEmail, 'Perm Edit Member', 'ANALYST');
  const inviteToken = await waitForInviteToken(request, memberEmail);
  await acceptInvitationViaApi(request, inviteToken, ADMIN_PASSWORD);

  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  // Create a connector to grant on.
  const connectorName = `PermEdit ${Date.now()}`;
  await page.goto('/api-connectors');
  await page.getByRole('button', { name: /New connector/i }).click();
  await page.getByLabel('Name', { exact: true }).fill(connectorName);
  await page.getByLabel('Base URL').fill('https://perm-edit.example.com');
  await page.getByLabel('AI config').click();
  await page.getByTitle(/e2e-mock-openai/).click();
  const createResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/api-connectors') && r.ok(),
  );
  await page.getByRole('button', { name: 'Save' }).click();
  await createResponse;
  await page.waitForURL('**/api-connectors/*/settings', { timeout: 15_000 });

  // Permissions tab → grant the member read access. The user picker is an AntD Select whose
  // placeholder renders as an overlay span (not an input attribute). Since AF-530 added a
  // User/Group Segmented toggle (whose "User" option also carries the label "User"), open the
  // Select by its stable input id rather than the now-ambiguous label, then pick the option by title.
  await page.getByRole('tab', { name: 'Permissions' }).click();
  await page.locator('#user_id').click();
  await page.keyboard.type(memberEmail);
  await page.getByTitle(new RegExp(memberEmail)).click();
  const grantResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && /\/permissions$/.test(new URL(r.url()).pathname) && r.ok(),
  );
  await page.getByRole('button', { name: 'Share with user' }).click();
  await grantResponse;

  const grantedRow = page.getByRole('row').filter({ hasText: memberEmail });
  await expect(grantedRow).toBeVisible();

  // Edit the grant in place — toggle write on — and expect a PUT (not a revoke + re-grant). The
  // modal title carries the member's identity (display name if resolved, else the email), so match
  // only the static prefix.
  await grantedRow.getByRole('button', { name: 'Edit' }).click();
  const dialog = page.getByRole('dialog', { name: /Edit permission/ });
  await expect(dialog).toBeVisible();
  const switches = dialog.locator('button[role="switch"]');
  await switches.nth(1).click(); // can_write
  const updateResponse = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && /\/permissions\/[0-9a-f-]+$/.test(new URL(r.url()).pathname) && r.ok(),
  );
  await dialog.getByRole('button', { name: 'Save' }).click();
  await updateResponse;

  // The row now reflects write access; provenance stayed on the same permission id.
  await expect(grantedRow.getByText('✓')).toHaveCount(2);
});

test('admin grants and edits an API connector group permission (AF-564)', async ({ page, request }) => {
  const adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

  // Seed a connector (via API) and a user group to share it with.
  const connector = await createApiConnectorViaApi(request, adminToken, {
    name: `GroupEdit ${Date.now()}`,
  });
  const groupName = `GroupEditGrp ${Date.now()}`;
  const groupRes = await request.post(`${apiBase()}/api/v1/admin/groups`, {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { name: groupName },
  });
  expect(groupRes.ok()).toBeTruthy();

  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  await page.goto(`/api-connectors/${connector.id}/settings`);
  await page.getByRole('tab', { name: 'Permissions' }).click();

  // Flip the grant target to Group — the submit button label tracks it (Bug 1).
  await page.getByText('Group', { exact: true }).first().click();
  await expect(page.getByRole('button', { name: 'Share with group' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Share with user' })).toHaveCount(0);

  // Pick the group and grant.
  await page.locator('#group_id').click();
  await page.keyboard.type(groupName);
  await page.getByTitle(groupName).click();
  const grantResponse = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' &&
      /\/permissions\/groups$/.test(new URL(r.url()).pathname) &&
      r.ok(),
  );
  await page.getByRole('button', { name: 'Share with group' }).click();
  await grantResponse;

  const grantedRow = page.getByRole('row').filter({ hasText: groupName });
  await expect(grantedRow).toBeVisible();

  // Edit the group grant in place — toggle write on — and expect a PUT to the group path (Bug 2),
  // not a revoke + re-grant. The modal title carries the group name.
  await grantedRow.getByRole('button', { name: 'Edit' }).click();
  const dialog = page.getByRole('dialog', { name: /Edit group permission/ });
  await expect(dialog).toBeVisible();
  const switches = dialog.locator('button[role="switch"]');
  await switches.nth(1).click(); // can_write
  const updateResponse = page.waitForResponse(
    (r) =>
      r.request().method() === 'PUT' &&
      /\/permissions\/groups\/[0-9a-f-]+$/.test(new URL(r.url()).pathname) &&
      r.ok(),
  );
  await dialog.getByRole('button', { name: 'Save' }).click();
  await updateResponse;

  // The group row now reflects both read and write access on the same permission id.
  await expect(grantedRow.getByText('✓')).toHaveCount(2);
});

test('API editor shows the Postman-style composer and scheduling (#517)', async ({ page }) => {
  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  await page.goto('/api-editor');
  // The request composer exposes Params / Headers / Body tabs.
  await expect(page.getByRole('tab', { name: 'Params' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Headers' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Body' })).toBeVisible();

  // Body tab offers the raw / form-data / x-www-form-urlencoded / binary modes. AntD button-style
  // radios hide the underlying <input>, so assert the visible option labels instead of the role.
  await page.getByRole('tab', { name: 'Body' }).click();
  await expect(page.getByText('Form data', { exact: true })).toBeVisible();
  await expect(page.getByText('Binary', { exact: true })).toBeVisible();

  // Scheduled-run control is present.
  await expect(page.getByText('Schedule for later')).toBeVisible();
});
