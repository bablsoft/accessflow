import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Unique suffix isolates the seeded datasource on reruns against the long-lived
// e2e Postgres (the suite reuses one DB between `stack:up` cycles unless torn
// down with `-v`). Same pattern as AF-271 / AF-279.
const UNIQUE_SUFFIX = `af282-${Date.now()}`;
const DATASOURCE_NAME = `Audit log E2E ${UNIQUE_SUFFIX}`;

// AF-282 covers the four regression-prone interactions on /admin/audit-log:
//   1. recent activity (USER_LOGIN, DATASOURCE_CREATED) lands in the unfiltered list
//   2. filtering by action narrows the list
//   3. clicking a row opens the drawer and renders metadata JSON
//   4. the "Verify chain" button calls the verify endpoint and shows "Chain valid"
//   5. a filter matching no events shows the empty-state copy
//
// Backend-side tampering is intentionally skipped (per the issue) — that path
// belongs to backend integration tests and cannot be simulated by an e2e spec
// without raw DB writes.

let datasource: CreatedDatasource | null = null;
let adminAccessToken = '';

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/admin/audit-log response that backs the page. The
// table renders only after the query resolves, so subsequent locators are
// flaky without this gate.
async function waitForAuditListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/audit-log(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

// Drive the first AntD Select in the filters bar (the Action filter). The
// Select carries `showSearch + optionFilterProp="label"` because the action
// list has 20+ options that AntD virtualizes — without typing into the
// search input the desired option is never DOM-resident, so `.first()` on
// `.ant-select-item-option` times out. Typing the label narrows the virtual
// list to a single, immediately-rendered row.
async function selectActionFilter(page: Page, optionLabel: string): Promise<void> {
  const filtersBar = page.locator('.ant-select').first();
  await filtersBar.click();
  await filtersBar.locator('input').fill(optionLabel);
  await page
    .locator('.ant-select-item-option')
    .filter({ hasText: new RegExp(`^${optionLabel}$`) })
    .first()
    .click();
}

test.describe.serial('admin audit log — list, filter, drawer, chain verify', () => {
  test.beforeAll(async ({ request }) => {
    // loginViaApi emits a USER_LOGIN audit event; createPostgresDatasource
    // emits a DATASOURCE_CREATED event. Together they guarantee at least two
    // rows in the log before the spec runs, regardless of what prior runs left
    // behind.
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: DATASOURCE_NAME,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('shows USER_LOGIN and DATASOURCE_CREATED events in the unfiltered list', async ({
    page,
  }) => {
    await loginViaUi(page);
    await page.goto('/admin/audit-log');
    await waitForAuditListReady(page);

    const table = page.getByRole('table');
    await expect(table).toBeVisible();

    // Both seeded action types must be present. Use `.first()` because prior
    // runs can leave multiple of each in the long-lived DB.
    await expect(
      table.locator('tr').filter({ hasText: 'DATASOURCE_CREATED' }).first(),
    ).toBeVisible({ timeout: 10_000 });
    await expect(
      table.locator('tr').filter({ hasText: 'USER_LOGIN' }).first(),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('filtering by action DATASOURCE_CREATED narrows the list', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/audit-log');
    await waitForAuditListReady(page);

    const filteredResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        r.url().includes('/api/v1/admin/audit-log') &&
        r.url().includes('action=DATASOURCE_CREATED') &&
        r.ok(),
      { timeout: 15_000 },
    );
    await selectActionFilter(page, 'DATASOURCE_CREATED');
    await filteredResponse;

    const table = page.getByRole('table');
    await expect(table).toBeVisible();

    // Every visible action cell on the page must read DATASOURCE_CREATED after
    // the filter is applied. No USER_LOGIN cells should remain.
    await expect(
      table.locator('tr').filter({ hasText: 'DATASOURCE_CREATED' }).first(),
    ).toBeVisible();
    await expect(table.locator('tr', { hasText: 'USER_LOGIN' })).toHaveCount(0);
  });

  test('clicking a row opens the drawer and renders the metadata JSON', async ({
    page,
  }) => {
    await loginViaUi(page);
    await page.goto('/admin/audit-log');
    await waitForAuditListReady(page);

    // Filter to DATASOURCE_CREATED first so the row we click is the one we
    // created in beforeAll (the resource_id column carries our unique name).
    const filteredResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        r.url().includes('/api/v1/admin/audit-log') &&
        r.url().includes('action=DATASOURCE_CREATED') &&
        r.ok(),
      { timeout: 15_000 },
    );
    await selectActionFilter(page, 'DATASOURCE_CREATED');
    await filteredResponse;

    const table = page.getByRole('table');
    const firstDatasourceRow = table
      .locator('tr')
      .filter({ hasText: 'DATASOURCE_CREATED' })
      .first();
    await firstDatasourceRow.click();

    // Drawer renders in a portal; query by role.
    const drawer = page.getByRole('dialog');
    await expect(drawer).toBeVisible({ timeout: 10_000 });

    // The metadata Card renders the event metadata in a <pre>. Assert a non-empty
    // JSON object is present (leading `{`, trailing `}` after JSON.stringify).
    const metadata = drawer.locator('pre').first();
    await expect(metadata).toBeVisible();
    const metadataText = (await metadata.textContent()) ?? '';
    expect(metadataText.trim().startsWith('{')).toBe(true);
    expect(metadataText.trim().endsWith('}')).toBe(true);
    // Sanity: not the empty object — DATASOURCE_CREATED metadata always carries
    // at least one key (datasource name / db_type / …).
    expect(metadataText.replace(/\s/g, '')).not.toBe('{}');

    // Close the drawer to leave clean state for adjacent tests.
    await page.keyboard.press('Escape');
    await expect(drawer).toBeHidden();
  });

  test('Verify chain button reports Chain valid with rows checked', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/audit-log');
    await waitForAuditListReady(page);

    const verifyResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/admin\/audit-log\/verify(\?|$)/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );
    await page.getByTestId('verify-chain-button').click();
    const response = await verifyResponse;
    const body = (await response.json()) as { ok: boolean; rows_checked: number };
    expect(body.ok).toBe(true);
    expect(body.rows_checked).toBeGreaterThanOrEqual(2);

    const alert = page.getByTestId('verify-chain-result');
    await expect(alert).toBeVisible({ timeout: 10_000 });
    await expect(alert).toContainText('Chain valid');
    await expect(alert).toContainText('rows checked');
  });

  test('Export CSV button hits the export endpoint with the attachment filename', async ({
    page,
    context,
  }) => {
    // playwright.config.ts sets extraHTTPHeaders={ Accept: 'application/json' } so the
    // API helpers get JSON-parsed responses. The browser inherits that header — and
    // /export.csv is declared @GetMapping(produces="text/csv"), so Spring's content
    // negotiation would 406 the request. Override Accept on the page context so
    // axios advertises text/csv. Same workaround as the /queries CSV export spec.
    await context.setExtraHTTPHeaders({ Accept: 'text/csv, */*' });

    await loginViaUi(page);
    await page.goto('/admin/audit-log');
    await waitForAuditListReady(page);

    // Wait on the export network response rather than page.waitForEvent('download') —
    // Playwright does not fire that event for synthetic anchor.click() against a
    // Blob URL (the pattern AuditLogPage uses to trigger the file save). Asserting
    // the Content-Disposition header is equivalent to asserting the suggested
    // filename, but doesn't depend on the browser surfacing the download to
    // Playwright. Same workaround as the /queries CSV export spec.
    const [response] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          /\/api\/v1\/admin\/audit-log\/export\.csv(\?|$)/.test(r.url()),
        { timeout: 15_000 },
      ),
      page.getByTestId('export-csv-button').click(),
    ]);

    expect(response.status()).toBe(200);
    expect(response.headers()['content-type'] ?? '').toMatch(/^text\/csv/);
    expect(response.headers()['content-disposition'] ?? '').toMatch(
      /attachment;\s*filename="audit-log-\d{8}-\d{6}\.csv"/,
    );
    const body = await response.text();
    const lines = body.split('\r\n');
    expect(lines[0]).toBe(
      'timestamp,organization_id,actor_email,action,resource_type,resource_id,'
        + 'ip_address,user_agent,current_hash,previous_hash,metadata_json',
    );
    // beforeAll seeded a USER_LOGIN row via loginViaApi; the export must include it.
    expect(body).toContain('USER_LOGIN');

    // The export action is itself audited as AUDIT_LOG_EXPORTED — re-verify the chain
    // to confirm the new row chained cleanly.
    const verifyResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/admin\/audit-log\/verify(\?|$)/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );
    await page.getByTestId('verify-chain-button').click();
    const verifyOk = (await (await verifyResponse).json()) as { ok: boolean };
    expect(verifyOk.ok).toBe(true);
  });

  test('filter that matches no events renders the empty-state', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/audit-log');
    await waitForAuditListReady(page);

    // A random UUID that cannot match any real resource. Cast to RFC 4122 v4
    // shape so the backend's UUID binding accepts the param.
    const bogusUuid = '00000000-0000-4000-8000-000000000000';

    const emptyResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        r.url().includes('/api/v1/admin/audit-log') &&
        r.url().includes(`resourceId=${bogusUuid}`) &&
        r.ok(),
      { timeout: 15_000 },
    );
    await page.getByPlaceholder('Resource id').fill(bogusUuid);
    await emptyResponse;

    await expect(page.getByText('No audit events match the current filter.')).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByRole('table')).toHaveCount(0);
  });
});
