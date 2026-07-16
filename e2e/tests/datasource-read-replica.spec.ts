import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af457-${Date.now()}`;
const REPLICA_DS_NAME = `Postgres E2E ${UNIQUE_SUFFIX} REPLICA`;

const REPLICA_JDBC_URL = 'jdbc:postgresql://postgres:5432/accessflow';
const REPLICA_JDBC_URL_B = 'jdbc:postgresql://postgres:5432/accessflow?ApplicationName=replica-b';
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

// AF-457 — configure, test, save, and remove read-replica endpoints through
// the settings page UI, plus the SELECT result-cache opt-in. The "replicas"
// here point at the same compose Postgres as the primary; routing,
// load-balancing, health failover, and cache correctness are exercised by
// backend integration tests (DefaultQueryExecutorReadReplicaIntegrationTest,
// SelectResultCacheIntegrationTest). What this spec proves is that the
// settings page renders the dynamic endpoint list, the per-row Test replica
// button calls /test-replica with live values, the save persists the
// read_replicas array + cache settings via the update endpoint, and removing
// every endpoint saves an empty list.

test.describe.serial('datasource settings — read replicas & performance', () => {
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

  test('adds, tests, saves, and removes replica endpoints', async ({ page }) => {
    if (!datasource) throw new Error('datasource fixture missing');
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await waitForSettingsReady(page, datasource.id);

    // No endpoints yet — only the add button.
    await expect(page.getByRole('button', { name: 'Add replica endpoint' })).toBeVisible();
    await expect(page.getByLabel('Replica JDBC URL')).toHaveCount(0);

    // Add the first endpoint and fill it with live values.
    await page.getByRole('button', { name: 'Add replica endpoint' }).click();
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

    // Add a second endpoint.
    await page.getByRole('button', { name: 'Add replica endpoint' }).click();
    await expect(page.getByLabel('Replica JDBC URL')).toHaveCount(2);
    await page.getByLabel('Replica JDBC URL').nth(1).fill(REPLICA_JDBC_URL_B);
    await page.getByLabel('Replica username').nth(1).fill(REPLICA_USER);
    await page.getByLabel('Replica password').nth(1).fill(REPLICA_PASSWORD);

    // Enable the SELECT result cache with a custom TTL.
    await page.getByLabel('Cache SELECT results').click();
    await page.getByLabel('Cache TTL (seconds)').fill('120');

    // Save changes — the PUT body should carry the endpoint list + cache settings.
    const [saveResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new RegExp(`/api/v1/datasources/${datasource!.id}$`).test(r.url()),
      ),
      page.getByRole('button', { name: 'Save changes' }).click(),
    ]);
    expect(saveResponse.ok()).toBeTruthy();
    const savedBody = saveResponse.request().postDataJSON() as {
      read_replicas: { id?: string; jdbc_url: string; username?: string; password?: string }[];
      result_cache_enabled: boolean;
      result_cache_ttl_seconds: number;
    };
    expect(savedBody.read_replicas).toHaveLength(2);
    expect(savedBody.read_replicas[0]!.jdbc_url).toBe(REPLICA_JDBC_URL);
    expect(savedBody.read_replicas[0]!.username).toBe(REPLICA_USER);
    expect(savedBody.read_replicas[0]!.password).toBe(REPLICA_PASSWORD);
    expect(savedBody.read_replicas[1]!.jdbc_url).toBe(REPLICA_JDBC_URL_B);
    expect(savedBody.result_cache_enabled).toBe(true);
    expect(savedBody.result_cache_ttl_seconds).toBe(120);

    // Reload and verify both endpoints + cache settings persisted (passwords never round-trip).
    await page.reload();
    await waitForSettingsReady(page, datasource.id);
    await expect(page.getByLabel('Replica JDBC URL')).toHaveCount(2);
    await expect(page.getByLabel('Replica JDBC URL').first()).toHaveValue(REPLICA_JDBC_URL);
    await expect(page.getByLabel('Replica JDBC URL').nth(1)).toHaveValue(REPLICA_JDBC_URL_B);
    await expect(page.getByLabel('Cache SELECT results')).toBeChecked();
    await expect(page.getByLabel('Cache TTL (seconds)')).toHaveValue('120');

    // Re-test a persisted endpoint without re-typing the password — the request
    // carries replica_id so the backend falls back to the stored secret.
    const [retestResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          new RegExp(`/api/v1/datasources/${datasource!.id}/test-replica$`).test(r.url()),
      ),
      page.getByRole('button', { name: 'Test replica' }).first().click(),
    ]);
    expect(retestResponse.ok()).toBeTruthy();
    const retestBody = retestResponse.request().postDataJSON() as Record<string, unknown>;
    expect(typeof retestBody.replica_id).toBe('string');
    expect(retestBody.password).toBeUndefined();

    // Remove both endpoints and save — the PUT body carries an empty list.
    await page.getByRole('button', { name: 'Remove' }).first().click();
    await page.getByRole('button', { name: 'Remove' }).first().click();
    await expect(page.getByLabel('Replica JDBC URL')).toHaveCount(0);
    const [clearResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new RegExp(`/api/v1/datasources/${datasource!.id}$`).test(r.url()),
      ),
      page.getByRole('button', { name: 'Save changes' }).click(),
    ]);
    expect(clearResponse.ok()).toBeTruthy();
    const clearedBody = clearResponse.request().postDataJSON() as { read_replicas: unknown[] };
    expect(clearedBody.read_replicas).toEqual([]);

    await page.reload();
    await waitForSettingsReady(page, datasource.id);
    await expect(page.getByLabel('Replica JDBC URL')).toHaveCount(0);
  });
});
