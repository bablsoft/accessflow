import { test, expect, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// The e2e docker-compose stack reaches its own Postgres container as
// `postgres:5432` over the e2e-network bridge. The `accessflow` user owns the
// `accessflow` database (Flyway runs as that user on startup), so a `SELECT 1`
// from the backend container will always succeed once we connect.
const TARGET_HOST = 'postgres';
const TARGET_DATABASE = 'accessflow';
const TARGET_USERNAME = 'accessflow';
const TARGET_PASSWORD = 'accessflow';

async function login(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Happy-path coverage for the four-step datasource creation wizard. Drives the
// real backend + a real Postgres (the same container the control-plane uses,
// reachable as `postgres:5432` on the compose network).
//
// One deviation from the issue's literal script: the PostgreSQL driver ships
// with `default_ssl_mode = VERIFY_FULL`, which the wizard correctly pre-fills.
// We assert that pre-fill, then change ssl_mode to DISABLE before clicking
// Test — the bare `postgres:18` image in `docker-compose.e2e.yml` has no TLS
// configured, so VERIFY_FULL would fail the JDBC handshake. Provisioning TLS
// on the dockerized Postgres is out of scope for this issue.
test('admin creates a PostgreSQL datasource through the wizard', async ({ page }) => {
  await login(page);

  // ── 1. Land on the datasource list and open the wizard ──────────────────────────
  await page.goto('/datasources');
  await page.getByRole('button', { name: 'Add datasource' }).click();
  await page.waitForURL('**/datasources/new', { timeout: 10_000 });

  // ── 2. Type step — the bundled PostgreSQL option advertises "Bundled" ───────────
  // The radio button's accessible name comes from its visible text content
  // (display_name + description + status badge label). Match "PostgreSQL" loosely.
  const postgresOption = page.getByRole('radio', { name: /PostgreSQL/i });
  await expect(postgresOption).toBeVisible();
  await expect(postgresOption).toContainText('Bundled');
  await postgresOption.click();

  // ── 3. Connection step — defaults are 5432 + VERIFY_FULL ────────────────────────
  const portInput = page.getByRole('spinbutton', { name: /Port/ });
  await expect(portInput).toHaveValue('5432');

  // AntD 6's Select renders the chosen value as a text node next to the combobox
  // (the form item contains both "SSL mode" and the translated SslMode label —
  // "Verify full" after AF-315 routed enum labels through `enumLabels.ts`).
  const sslFormItem = page.locator('.ant-form-item').filter({ hasText: 'SSL mode' });
  await expect(sslFormItem).toContainText('Verify full');

  // Switch to Disable so the connection to the bare postgres:18 container succeeds.
  // AntD 6's Select renders dropdown items as `.ant-select-item-option` divs,
  // not native role="option" — target by class.
  await page.getByRole('combobox', { name: /SSL mode/ }).click();
  await page.locator('.ant-select-item-option').filter({ hasText: /^Disable$/ }).click();
  await expect(sslFormItem).toContainText('Disable');
  await expect(sslFormItem).not.toContainText('Verify full');

  // Fill the remaining connection fields. AntD Form generates input IDs from
  // each Form.Item's `name`, so #name / #host / #database_name / #username /
  // #password are stable and unambiguous (the "Name" label substring otherwise
  // collides with "Database name" and "Username").
  //
  // The datasource name is timestamped so re-running the spec against an
  // already-seeded stack (without `stack:down -v` in between) doesn't trip the
  // (org_id, name) uniqueness constraint.
  const datasourceName = `Compose Postgres ${Date.now()}`;
  await page.locator('#name').fill(datasourceName);
  await page.locator('#host').fill(TARGET_HOST);
  await page.locator('#database_name').fill(TARGET_DATABASE);
  await page.locator('#username').fill(TARGET_USERNAME);
  await page.locator('#password').fill(TARGET_PASSWORD);

  // Submit the connection form — POSTs /api/v1/datasources, advances to test step.
  await page.getByRole('button', { name: 'Save and test' }).click();

  // ── 4. Test step — run the connection test and assert latency_ms ────────────────
  const testButton = page.getByRole('button', { name: 'Test connection' });
  await expect(testButton).toBeVisible({ timeout: 15_000 });
  await testButton.click();

  // Success Alert renders `Connected · {{ms}} ms` from `datasources.create.test_success`.
  await expect(page.getByText(/Connected · \d+ ms/)).toBeVisible({ timeout: 15_000 });

  // ── 5. Settings step — Next is only enabled after a successful test ─────────────
  const nextButton = page.getByRole('button', { name: 'Next' });
  await expect(nextButton).toBeEnabled();
  await nextButton.click();

  // The settings form is pre-filled from the persisted datasource; submit as-is.
  await page.getByRole('button', { name: 'Save and finish' }).click();

  // ── 6. Land on the settings page with a success toast ───────────────────────────
  await page.waitForURL(/\/datasources\/[0-9a-f-]{36}\/settings$/, { timeout: 15_000 });
  await expect(page.getByText('Datasource created')).toBeVisible();
});
