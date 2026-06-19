import { test, expect, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// AF-459 covers the auditor compliance dashboard (/admin/auditor):
//   1. the sidebar "Compliance reports" link routes to the dashboard and both report tabs render
//   2. exporting hits the signed-export endpoint with the compliance filename + signature headers
//
// The dashboard is gated to AUDITOR + ADMIN; ADMIN is the role the e2e bootstrap seeds, so the
// browser flow runs as ADMIN. The AUDITOR-vs-other-role gating (403 for ANALYST/READONLY, 200 for
// AUDITOR) and the export's audit-log chaining (COMPLIANCE_REPORT_EXPORTED) are exercised by
// ComplianceReportControllerIntegrationTest, which can seed arbitrary roles and read the audit log
// directly — the e2e bootstrap only seeds an admin.

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

  test('CSV export is signed with the compliance filename and signature headers', async ({ page }) => {
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

    // The export's chaining into the audit log (a COMPLIANCE_REPORT_EXPORTED row carrying the
    // content hash + signature) is asserted by ComplianceReportControllerIntegrationTest, which
    // reads the audit log directly — an authenticated cross-origin call from this browser context
    // would resolve against the frontend SPA, not the backend API.
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
