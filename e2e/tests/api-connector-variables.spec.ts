import { expect, test, type Page } from '@playwright/test';

// AF-613: dynamic variables for API connectors — an admin creates a connector, declares an
// HMAC signing variable and an overridable nonce on the Variables tab, reorders them, and deletes
// one. The evaluate-and-sign path (including the vendor HMAC-over-auth-header-plus-body scheme) is
// covered by the backend integration test (ApiConnectorVariablesIntegrationTest), since it needs a
// live upstream API. Seeded admin comes from the bootstrap module.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const CONNECTOR_NAME = `Variables ${Date.now()}`;

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

test('admin configures connector dynamic variables', async ({ page }) => {
  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  // Create a connector.
  await page.goto('/api-connectors');
  await page.waitForResponse(
    (r) => r.request().method() === 'GET' && r.url().includes('/api/v1/api-connectors') && r.ok(),
  );
  await page.getByRole('button', { name: /New connector/i }).click();
  await page.getByLabel('Name', { exact: true }).fill(CONNECTOR_NAME);
  await page.getByLabel('Base URL').fill('https://variables.example.com');
  await page.getByLabel('AI config').click();
  await page.getByTitle(/e2e-mock-openai/).click();
  const createResponse = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/api-connectors') && r.ok(),
  );
  await page.getByRole('button', { name: 'Save' }).click();
  await createResponse;
  await page.waitForURL('**/api-connectors/*/settings', { timeout: 15_000 });

  // Ant Design keeps inactive tab panels mounted (display:none), so scope content assertions to the
  // active panel or a bare getByText can match a hidden duplicate.
  const activePanel = page.locator('.ant-tabs-tabpane-active');

  await page.getByRole('tab', { name: 'Variables' }).click();
  await expect(activePanel.getByText(/No variables yet/i)).toBeVisible();

  // An HMAC signature over the resolved Authorization header plus the body — the motivating vendor
  // scheme. Selecting the HMAC kind reveals the algorithm and secret fields.
  //
  // Every form interaction is scoped to the dialog: the Config tab stays mounted (display:none) and
  // has its own `Name` field, so a bare getByLabel('Name') resolves to that hidden input instead.
  await page.getByRole('button', { name: 'Add variable' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Name', { exact: true }).fill('signature');
  await dialog.getByLabel('Kind', { exact: true }).click();
  await page.getByTitle('HMAC signature', { exact: true }).click();
  await dialog.getByLabel('Expression').fill('{{request.headers.Authorization}}{{request.body}}');
  await dialog.getByLabel('Algorithm').click();
  await page.getByTitle('HMAC-SHA256', { exact: true }).click();
  await dialog.getByLabel('Shared secret').fill('e2e-shared-key');
  const signatureCreate = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes('/variables') && r.ok(),
  );
  await dialog.getByRole('button', { name: 'Save' }).click();
  await signatureCreate;
  await expect(activePanel.getByText('{{signature}}')).toBeVisible();
  // The stored secret is never returned — the row only reports that one exists.
  await expect(activePanel.getByText('Secret').first()).toBeVisible();

  // A random nonce the submitter may override per request.
  await page.getByRole('button', { name: 'Add variable' }).click();
  const nonceDialog = page.getByRole('dialog');
  await nonceDialog.getByLabel('Name', { exact: true }).fill('nonce');
  await nonceDialog.getByLabel('Kind', { exact: true }).click();
  await page.getByTitle('Random bytes', { exact: true }).click();
  await nonceDialog.getByRole('switch').click();
  const nonceCreate = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes('/variables') && r.ok(),
  );
  await nonceDialog.getByRole('button', { name: 'Save' }).click();
  await nonceCreate;
  await expect(activePanel.getByText('{{nonce}}')).toBeVisible();
  await expect(activePanel.getByText('Overridable')).toBeVisible();

  // Evaluation order is observable, so it is operator-controlled: move the nonce ahead of the
  // signature that will eventually reference it.
  const reorder = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && r.url().includes('/variables/order') && r.ok(),
  );
  await activePanel.getByRole('button', { name: 'Move earlier' }).last().click();
  await reorder;
  // AntD renders a hidden `ant-table-measure-row` as the first tbody row; match real data rows.
  await expect(activePanel.locator('tbody tr[data-row-key]').first()).toContainText('{{nonce}}');

  // Deleting an unreferenced variable succeeds.
  const remove = page.waitForResponse(
    (r) => r.request().method() === 'DELETE' && r.url().includes('/variables/') && r.ok(),
  );
  await activePanel.getByRole('button', { name: 'Delete' }).first().click();
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click();
  await remove;
  await expect(activePanel.getByText('{{nonce}}')).toBeHidden();
});
