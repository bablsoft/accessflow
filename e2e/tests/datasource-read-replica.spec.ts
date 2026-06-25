import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af360-${Date.now()}`;
const REPLICA_DS_NAME = `Postgres E2E ${UNIQUE_SUFFIX} REPLICA`;

const REPLICA_JDBC_URL = 'jdbc:postgresql://postgres:5432/accessflow';
const REPLICA_USER = 'accessflow';
const REPLICA_PASSWORD = 'accessflow';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

async function waitForSettingsReady(page: Page, dsId: string): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      new RegExp(`/api/v1/datasources/${dsId}$`).test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
  await expect(page.getByText('Loading datasource…')).toHaveCount(0, { timeout: 10_000 });
}

// AF-360 — configure, test, save, and clear a read-replica through the
// settings page UI. The "replica" here points at the same compose Postgres as
// the primary; the routing path is exercised by backend integration tests in
// DefaultQueryExecutorReadReplicaIntegrationTest. What this spec proves is
// that the settings page card surfaces the three fields, the Test replica
// button calls /test-replica with live values, the save persists the
// replica via the update endpoint, and a blank URL clears the replica.

test.describe.serial('datasource settings — read replica card', () => {
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    const token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, token, { name: REPLICA_DS_NAME });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      const token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
      await deleteDatasource(request, token, datasource.id);
    }
  });

  test('configures, tests, saves, and clears a read replica', async ({ page }) => {
    if (!datasource) throw new Error('datasource fixture missing');
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await waitForSettingsReady(page, datasource.id);

    // The replica fields are present and initially empty.
    await expect(page.getByLabel('Replica JDBC URL')).toHaveValue('');
    await expect(page.getByLabel('Replica username')).toHaveValue('');

    // Fill the replica fields with live values.
    await page.getByLabel('Replica JDBC URL').fill(REPLICA_JDBC_URL);
    await page.getByLabel('Replica username').fill(REPLICA_USER);
    await page.getByLabel('Replica password').fill(REPLICA_PASSWORD);

    // Test replica should hit POST /datasources/{id}/test-replica and show success.
    const [testResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new RegExp(`/api/v1/datasources/${datasource!.id}/test-replica$`).test(r.url()),
      ),
      page.getByRole('button', { name: 'Test replica' }).click(),
    ]);
    expect(testResponse.ok()).toBeTruthy();
    // .first(): on a fast response the inline alert and the success toast are visible at once.
    await expect(page.getByText(/Replica connected ·/).first()).toBeVisible({ timeout: 10_000 });

    // Save changes — the PUT body should include the replica fields.
    const [saveResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new RegExp(`/api/v1/datasources/${datasource!.id}$`).test(r.url()),
      ),
      page.getByRole('button', { name: 'Save changes' }).click(),
    ]);
    expect(saveResponse.ok()).toBeTruthy();
    const savedBody = saveResponse.request().postDataJSON() as Record<string, unknown>;
    expect(savedBody.read_replica_jdbc_url).toBe(REPLICA_JDBC_URL);
    expect(savedBody.read_replica_username).toBe(REPLICA_USER);
    expect(savedBody.read_replica_password).toBe(REPLICA_PASSWORD);

    // Reload and verify the URL + username persisted (the password never round-trips).
    await page.reload();
    await waitForSettingsReady(page, datasource.id);
    await expect(page.getByLabel('Replica JDBC URL')).toHaveValue(REPLICA_JDBC_URL);
    await expect(page.getByLabel('Replica username')).toHaveValue(REPLICA_USER);

    // Clear the URL and save — the backend clear-on-blank rule should null all three.
    await page.getByLabel('Replica JDBC URL').fill('');
    const [clearResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new RegExp(`/api/v1/datasources/${datasource!.id}$`).test(r.url()),
      ),
      page.getByRole('button', { name: 'Save changes' }).click(),
    ]);
    expect(clearResponse.ok()).toBeTruthy();
    const clearedBody = clearResponse.request().postDataJSON() as Record<string, unknown>;
    expect(clearedBody.read_replica_jdbc_url).toBe('');

    await page.reload();
    await waitForSettingsReady(page, datasource.id);
    await expect(page.getByLabel('Replica JDBC URL')).toHaveValue('');
    await expect(page.getByLabel('Replica username')).toHaveValue('');
  });
});
