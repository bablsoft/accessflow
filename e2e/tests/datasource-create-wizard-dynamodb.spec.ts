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

// DynamoDB is the first engine whose connection is cloud credentials + region rather than host/port
// (AF-422). This spec drives the wizard's connection step for the DynamoDB connector and asserts the
// branch renders the cloud-credentials fields: host/port are hidden, database_name is relabelled as
// the AWS region, an optional custom endpoint appears, and username/password are relabelled as the
// access key id / secret access key. The engine's create / execute / row-security / masking
// behaviour is covered by the plugin's Testcontainers IT against DynamoDB Local — the e2e compose
// stack has no DynamoDB Local, so we stop at the (backend-free) form rendering rather than running a
// connection test.
test('DynamoDB wizard shows region + custom endpoint and hides host/port', async ({ page }) => {
  await login(page);

  await page.goto('/datasources');
  await page.getByRole('button', { name: 'Add datasource' }).click();
  await page.waitForURL('**/datasources/new', { timeout: 10_000 });

  // ── Type step — pick the DynamoDB connector (an installable NoSQL engine plugin) ──
  const dynamoOption = page.getByRole('radio', { name: /DynamoDB/i });
  await expect(dynamoOption).toBeVisible();
  await dynamoOption.click();

  // ── Connection step — cloud-credentials fields, no host/port ──────────────────────
  // host/port are not rendered for DynamoDB.
  await expect(page.locator('#host')).toHaveCount(0);
  await expect(page.locator('#port')).toHaveCount(0);

  // database_name is relabelled "AWS region" and stays required.
  const regionItem = page.locator('.ant-form-item').filter({ hasText: 'AWS region' });
  await expect(regionItem).toBeVisible();
  await expect(page.locator('#database_name')).toBeVisible();

  // The optional custom endpoint field (DynamoDB Local / VPC) is present.
  await expect(page.locator('.ant-form-item').filter({ hasText: 'Custom endpoint' })).toBeVisible();
  await expect(page.locator('#dynamodb_endpoint')).toBeVisible();

  // username / password are relabelled as the AWS access key id / secret access key.
  await expect(page.locator('.ant-form-item').filter({ hasText: 'Access key ID' })).toBeVisible();
  await expect(page.locator('.ant-form-item').filter({ hasText: 'Secret access key' })).toBeVisible();

  // The fields accept input (cloud credentials + region), proving the form is wired.
  await page.locator('#database_name').fill('us-east-1');
  await page.locator('#dynamodb_endpoint').fill('http://localhost:8000');
  await page.locator('#username').fill('AKIAEXAMPLE');
  await page.locator('#password').fill('secret-access-key');
  await expect(page.locator('#database_name')).toHaveValue('us-east-1');
});
