import { expect, test, type Page } from '@playwright/test';

// AF-518: API connector masking & classification — an admin creates an API connector, configures a
// response-masking policy on the Masking tab, and a data-classification tag on the Classification
// tab. The end-to-end execute → redacted-snapshot path is covered by the backend integration test
// (ApiConnectorMaskingClassificationIntegrationTest), since it needs a live upstream API. Seeded
// admin comes from the bootstrap module.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const CONNECTOR_NAME = `Masking ${Date.now()}`;

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

test('admin configures connector masking policy and classification tag', async ({ page }) => {
  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  // Create a connector.
  await page.goto('/api-connectors');
  await page.waitForResponse(
    (r) => r.request().method() === 'GET' && r.url().includes('/api/v1/api-connectors') && r.ok(),
  );
  await page.getByRole('button', { name: /New connector/i }).click();
  await page.getByLabel('Name', { exact: true }).fill(CONNECTOR_NAME);
  await page.getByLabel('Base URL').fill('https://masking.example.com');
  await page.getByLabel('AI config').click();
  await page.getByTitle(/e2e-mock-openai/).click();
  const createResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/api-connectors') && r.ok(),
  );
  await page.getByRole('button', { name: 'Save' }).click();
  await createResponse;
  await page.waitForURL('**/api-connectors/*/settings', { timeout: 15_000 });

  // Masking tab → add a JSON-path masking policy.
  await page.getByRole('tab', { name: 'Masking' }).click();
  await page.getByRole('button', { name: 'Add policy' }).click();
  await page.getByLabel('Field reference', { exact: true }).fill('user.ssn');
  const maskingCreate = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' && r.url().includes('/masking-policies') && r.ok(),
  );
  await page.getByRole('button', { name: 'Save' }).click();
  await maskingCreate;
  await expect(page.getByText('user.ssn')).toBeVisible();

  // Classification tab → tag a field as PII.
  await page.getByRole('tab', { name: 'Classification' }).click();
  await page.getByRole('button', { name: 'Add tag' }).click();
  await page.getByLabel('Field reference', { exact: true }).fill('user.card');
  await page.getByLabel('Classifications').click();
  await page.getByTitle('PII', { exact: true }).click();
  // Close the dropdown so the Save button is clickable.
  await page.keyboard.press('Escape');
  const classificationCreate = page.waitForResponse(
    (r) =>
      r.request().method() === 'POST' && r.url().includes('/classification-tags') && r.ok(),
  );
  await page.getByRole('button', { name: 'Save' }).click();
  await classificationCreate;
  // `user.card` now appears in the classification tag table, its derivation-preview suggestion, and
  // the auto-derived masking policy — AntD keeps prior tab panels mounted — so scope to the first.
  await expect(page.getByText('user.card').first()).toBeVisible();
});
