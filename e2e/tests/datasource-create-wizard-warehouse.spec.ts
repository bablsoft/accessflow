import { test, expect, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

async function openWizard(page: Page): Promise<void> {
  await page.goto('/datasources');
  await page.getByRole('button', { name: 'Add datasource' }).click();
  await page.waitForURL('**/datasources/new', { timeout: 10_000 });
}

// The cloud data-warehouse engines (AF-629) each remap the connection fields to a native auth
// model: Snowflake (account host + password-or-private-key + optional jdbc:snowflake:// override),
// BigQuery (GCP project + service-account JSON, no host/port/username), and Databricks (workspace
// host + PAT + required warehouse HTTP path). The engines' execute / row-security / masking
// behaviour is covered by each plugin's own test suite — the e2e compose stack has no warehouse
// emulators, so these specs stop at the (backend-free) form rendering, like the DynamoDB spec.

test('Snowflake wizard shows account host + URL override and hides port', async ({ page }) => {
  await login(page);
  await openWizard(page);

  const option = page.getByRole('radio', { name: /Snowflake/i });
  await expect(option).toBeVisible();
  await option.click();

  // Account host replaces host; port is never asked (always 443). (getByLabel — the URL-override
  // field's help text also mentions the account host, so a form-item text filter is ambiguous.)
  await expect(page.getByLabel('Account host')).toBeVisible();
  await expect(page.locator('#port')).toHaveCount(0);

  // Database stays required; the optional JDBC URL override carries warehouse/role/schema params.
  await expect(page.locator('#database_name')).toBeVisible();
  await expect(page.locator('#snowflake_url_override')).toBeVisible();

  // The credential field accepts a password or an unencrypted PKCS#8 private-key PEM.
  await expect(
    page.locator('.ant-form-item').filter({ hasText: 'Password or private key (PEM)' }),
  ).toBeVisible();

  await page.locator('#host').fill('xy1.eu-central-1.snowflakecomputing.com');
  await page.locator('#database_name').fill('ANALYTICS');
  await page.locator('#username').fill('accessflow_svc');
  await page.locator('#password').fill('hunter2');
  await expect(page.locator('#database_name')).toHaveValue('ANALYTICS');
});

test('BigQuery wizard shows GCP project + service-account JSON and hides host/port/username', async ({
  page,
}) => {
  await login(page);
  await openWizard(page);

  const option = page.getByRole('radio', { name: /BigQuery/i });
  await expect(option).toBeVisible();
  await option.click();

  // Cloud-credentials model: no host/port/username fields.
  await expect(page.locator('#host')).toHaveCount(0);
  await expect(page.locator('#port')).toHaveCount(0);
  await expect(page.locator('#username')).toHaveCount(0);

  // database_name is relabelled as the GCP project (optionally project.dataset).
  await expect(page.locator('.ant-form-item').filter({ hasText: 'GCP project' })).toBeVisible();
  await expect(page.locator('#database_name')).toBeVisible();

  // The optional emulator endpoint and the service-account JSON textarea are present.
  await expect(page.locator('#bigquery_endpoint')).toBeVisible();
  await expect(
    page.locator('.ant-form-item').filter({ hasText: 'Service account key (JSON)' }),
  ).toBeVisible();

  await page.locator('#database_name').fill('my-project.analytics');
  await page.locator('#password').fill('{"type":"service_account"}');
  await expect(page.locator('#database_name')).toHaveValue('my-project.analytics');
});

test('Databricks wizard requires the warehouse HTTP path and hides port/username', async ({
  page,
}) => {
  await login(page);
  await openWizard(page);

  const option = page.getByRole('radio', { name: /Databricks/i });
  await expect(option).toBeVisible();
  await option.click();

  // Workspace host replaces host; port and username are never asked (PAT auth over 443).
  await expect(page.locator('.ant-form-item').filter({ hasText: 'Workspace host' })).toBeVisible();
  await expect(page.locator('#port')).toHaveCount(0);
  await expect(page.locator('#username')).toHaveCount(0);

  // The warehouse HTTP path is a required field; the catalog is optional.
  await expect(
    page.locator('.ant-form-item').filter({ hasText: 'Warehouse HTTP path' }),
  ).toBeVisible();
  await expect(
    page.locator('.ant-form-item').filter({ hasText: 'Catalog (Unity Catalog)' }),
  ).toBeVisible();

  await page.locator('#host').fill('adb-123.azuredatabricks.net');
  await page.locator('#databricks_http_path').fill('/sql/1.0/warehouses/abc123def456');
  await page.locator('#password').fill('dapi-example-token');
  await expect(page.locator('#databricks_http_path')).toHaveValue(
    '/sql/1.0/warehouses/abc123def456',
  );
});
