import { test, expect, type Page } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// AF-459 covers the auditor compliance dashboard (/admin/auditor):
//   1. the sidebar "Compliance reports" link routes to the dashboard and both report tabs render
//   2. exporting hits the signed-export endpoint with the compliance filename + signature headers
//   3. the export is chained into the tamper-evident audit log (COMPLIANCE_REPORT_EXPORTED)
//
// The dashboard is gated to AUDITOR + ADMIN; ADMIN is the role the e2e bootstrap seeds, so the
// browser flow runs as ADMIN. The AUDITOR-vs-other-role gating (403 for ANALYST/READONLY, 200 for
// AUDITOR) is exercised by ComplianceReportControllerIntegrationTest, which can seed arbitrary
// roles directly — the e2e bootstrap only seeds an admin.

let adminAccessToken = '';

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function waitForClassifiedReport(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/compliance\/reports\/classified-access(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

test.describe.serial('auditor compliance dashboard', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test('sidebar link opens the dashboard with both report tabs', async ({ page }) => {
    await loginViaUi(page);
    await page.getByRole('link', { name: 'Compliance reports' }).click();
    await page.waitForURL('**/admin/auditor', { timeout: 15_000 });
    await waitForClassifiedReport(page);

    await expect(page.getByRole('heading', { name: 'Compliance reports' })).toBeVisible();
    await expect(page.getByText('Classified data access')).toBeVisible();
    await expect(page.getByText('Regulatory audit trail')).toBeVisible();
    await expect(page.getByRole('button', { name: /Export signed PDF/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /Export CSV/i })).toBeVisible();
  });

  test('CSV export is signed and chained into the audit log', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/auditor');
    await waitForClassifiedReport(page);

    // Synthetic anchor.click() on a Blob URL does not fire Playwright's 'download'
    // event, so assert on the export network response (same pattern as audit-log CSV).
    const [response] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          /\/api\/v1\/admin\/compliance\/reports\/export(\?|$)/.test(r.url()) &&
          r.url().includes('format=CSV'),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: /Export CSV/i }).click(),
    ]);

    expect(response.status()).toBe(200);
    expect(response.headers()['content-type'] ?? '').toMatch(/^text\/csv/);
    expect(response.headers()['content-disposition'] ?? '').toMatch(
      /attachment;\s*filename="compliance-classified-access-\d{8}T\d{6}Z\.csv"/,
    );
    expect(response.headers()['x-accessflow-signature'] ?? '').not.toBe('');
    expect(response.headers()['x-accessflow-content-sha256'] ?? '').toMatch(/^[0-9a-f]{64}$/);

    // The export is recorded in the audit log as COMPLIANCE_REPORT_EXPORTED.
    const auditResponse = await page.request.get(
      '/api/v1/admin/audit-log?action=COMPLIANCE_REPORT_EXPORTED&size=1',
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect(auditResponse.ok()).toBe(true);
    const body = (await auditResponse.json()) as { total_elements: number };
    expect(body.total_elements).toBeGreaterThanOrEqual(1);
  });

  test('switching to the regulatory trail tab refetches that report', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/auditor');
    await waitForClassifiedReport(page);

    const trailResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/admin\/compliance\/reports\/regulatory-audit-trail(\?|$)/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );
    await page.getByText('Regulatory audit trail').click();
    await trailResponse;
  });
});
