import { expect, test, type Page } from '@playwright/test';

// AF-500 / #506: OAuth2 token sourcing for API connectors. An admin creates a connector with
// auth_method=OAUTH2_CLIENT_CREDENTIALS pointed at a mock token server (WireMock `mock-ai`), then
// runs "Test connection" — which exercises the real backend token fetch — and expects success. The
// client secret is write-only and surfaces only as a "Configured" tag, never the value.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Reachable from the backend container (same compose network as the WireMock mock).
const MOCK_BASE = 'http://mock-ai:8080';
const TOKEN_URI = `${MOCK_BASE}/oauth/token`;

const CONNECTOR_NAME = `OAuth2 API ${Date.now()}`;

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

test('admin creates an OAuth2 client-credentials connector and test-connection fetches a token', async ({
  page,
}) => {
  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  await page.goto('/api-connectors');
  await page.waitForResponse(
    (r) => r.request().method() === 'GET' && r.url().includes('/api/v1/api-connectors') && r.ok(),
  );

  // Open the create modal and pick the OAuth2 client-credentials auth method.
  await page.getByRole('button', { name: /New connector/i }).click();
  await page.getByLabel('Name', { exact: true }).fill(CONNECTOR_NAME);
  await page.getByLabel('Base URL').fill(MOCK_BASE);
  await page.getByLabel('Auth method').click();
  await page.getByTitle('OAuth2 Client Credentials').click();

  // OAuth2 fields appear once the method is selected.
  await page.getByLabel('Token endpoint URL').fill(TOKEN_URI);
  await page.getByLabel('Client ID').fill('e2e-client');
  await page.getByLabel('Client secret').fill('e2e-secret');

  const createResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/api-connectors') && r.ok(),
  );
  await page.getByRole('button', { name: 'Save' }).click();
  await createResponse;
  await page.waitForURL('**/api-connectors/*/settings', { timeout: 15_000 });

  // The settings page shows the non-secret config and a "Configured" tag for the secret — never the value.
  await expect(page.getByLabel('Token endpoint URL')).toHaveValue(TOKEN_URI);
  await expect(page.getByText('Configured', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('e2e-secret')).toHaveCount(0);

  // Back on the list, "Test connection" performs the real token fetch against the mock token server.
  await page.goto('/api-connectors');
  const row = page.getByRole('row', { name: new RegExp(CONNECTOR_NAME) });
  await row.getByRole('button', { name: 'Test connection' }).click();
  await expect(page.getByText(/Connection OK/i)).toBeVisible({ timeout: 15_000 });
});
