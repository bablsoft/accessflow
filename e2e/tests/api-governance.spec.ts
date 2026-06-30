import { expect, test, type Page } from '@playwright/test';

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

test('API editor shows the Postman-style composer and scheduling (#517)', async ({ page }) => {
  await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  await page.goto('/api-editor');
  // The request composer exposes Params / Headers / Body tabs.
  await expect(page.getByRole('tab', { name: 'Params' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Headers' })).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Body' })).toBeVisible();

  // Body tab offers the raw / form-data / x-www-form-urlencoded / binary modes.
  await page.getByRole('tab', { name: 'Body' }).click();
  await expect(page.getByRole('radio', { name: 'Form data' })).toBeVisible();
  await expect(page.getByRole('radio', { name: 'Binary' })).toBeVisible();

  // Scheduled-run control is present.
  await expect(page.getByText('Schedule for later')).toBeVisible();
});
