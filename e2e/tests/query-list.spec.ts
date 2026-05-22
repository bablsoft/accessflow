import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const API_BASE = process.env.E2E_API_BASE ?? 'http://localhost:8080';

// Shared across the serial tests — provisioned once in beforeAll, torn down
// in afterAll. The two datasources let us prove the datasource filter
// actually narrows the result set; the three queries give us two rows in
// PENDING_REVIEW (one per datasource) and a third in CANCELLED to prove the
// status filter narrows as well.
//
// Status is PENDING_REVIEW (not PENDING_AI) because the seeded datasources
// use ai_analysis_enabled=false. Per AF-307, DefaultAiAnalyzerService
// publishes AiAnalysisSkippedEvent in that case, and QueryReviewStateMachine
// advances the query to PENDING_REVIEW (no review plan is seeded → safe
// default). The transition runs in an @ApplicationModuleListener after the
// submit transaction commits, so beforeAll polls the API until each query
// has settled into the expected status before the assertions run.
let datasourceA: CreatedDatasource | null = null;
let datasourceB: CreatedDatasource | null = null;
let queryAId = '';
let queryBId = '';
let queryCancelledId = '';
let adminAccessToken = '';

// Unique substring tucked into datasourceA's name so we can prove the
// client-side free-text search filter narrows the list. Only one admin
// exists in this stack, so typing the submitter email matches every row;
// using a datasource-name substring exercises the same `qr.datasource.name`
// branch of the filter (QueryListPage.tsx:104) and actually narrows.
const UNIQUE_SUFFIX = `af265-${Date.now()}`;

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

interface SubmittedQuery {
  id: string;
  status: string;
}

async function submitQuery(
  request: APIRequestContext,
  accessToken: string,
  datasourceId: string,
  sql: string,
  justification: string,
): Promise<SubmittedQuery> {
  const res = await request.post(`${API_BASE}/api/v1/queries`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { datasource_id: datasourceId, sql, justification },
  });
  if (!res.ok()) {
    throw new Error(`Submit query failed: ${res.status()} ${await res.text()}`);
  }
  const json = (await res.json()) as { id: string; status: string };
  return json;
}

async function cancelQuery(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.post(`${API_BASE}/api/v1/queries/${id}/cancel`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok()) {
    throw new Error(`Cancel query failed: ${res.status()} ${await res.text()}`);
  }
}

// Submit returns PENDING_AI immediately; the AF-307 skip listener flips the
// status to PENDING_REVIEW asynchronously. Poll GET /api/v1/queries/{id}
// until the status matches `expected` (or we hit the timeout).
async function waitForQueryStatus(
  request: APIRequestContext,
  accessToken: string,
  id: string,
  expected: string,
  timeoutMs = 10_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const res = await request.get(`${API_BASE}/api/v1/queries/${id}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (res.ok()) {
      const body = (await res.json()) as { status: string };
      last = body.status;
      if (body.status === expected) return;
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(
    `Query ${id} did not reach status ${expected} within ${timeoutMs}ms (last seen: ${last || '<no response>'})`,
  );
}

// Wait until the GET /api/v1/queries response that backs the list page has
// landed and the page-level Skeleton has been replaced by the real table
// body. Anchors every subsequent assertion to a settled DOM.
async function waitForListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/queries(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
  // The Skeleton lives inside the same scroll container; once it's gone the
  // Table has mounted with the real rows (or the empty-state).
  await expect(page.locator('.ant-skeleton-active')).toHaveCount(0, {
    timeout: 10_000,
  });
}

test.describe.serial('query list filters + CSV export on /queries', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasourceA = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E ${UNIQUE_SUFFIX} A`,
    });
    datasourceB = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E B ${Date.now()}`,
    });

    // q1 on A, q2 on B → both settle into PENDING_REVIEW once the AF-307
    // skip listener fires. q3 on A → cancelled to give us a second status
    // (CANCELLED) without needing a second user to approve.
    const q1 = await submitQuery(
      request,
      adminAccessToken,
      datasourceA.id,
      'SELECT 1',
      'e2e/query-list q1',
    );
    queryAId = q1.id;
    const q2 = await submitQuery(
      request,
      adminAccessToken,
      datasourceB.id,
      'SELECT 2',
      'e2e/query-list q2',
    );
    queryBId = q2.id;
    const q3 = await submitQuery(
      request,
      adminAccessToken,
      datasourceA.id,
      'SELECT 3',
      'e2e/query-list q3 (will cancel)',
    );
    queryCancelledId = q3.id;
    await cancelQuery(request, adminAccessToken, q3.id);

    // Wait for the async PENDING_AI → PENDING_REVIEW transition on q1/q2
    // before any assertion runs. q3 is already terminal (CANCELLED) from
    // the synchronous cancel call above.
    await waitForQueryStatus(request, adminAccessToken, queryAId, 'PENDING_REVIEW');
    await waitForQueryStatus(request, adminAccessToken, queryBId, 'PENDING_REVIEW');
  });

  test.afterAll(async ({ request }) => {
    // Best-effort cleanup — the helper only logs on non-204/404.
    if (datasourceA) {
      await deleteDatasource(request, adminAccessToken, datasourceA.id);
    }
    if (datasourceB) {
      await deleteDatasource(request, adminAccessToken, datasourceB.id);
    }
  });

  // Filter-mechanic tests live in one Playwright test so AntD Select state
  // accumulates the way a user would actually drive it. Splitting them would
  // force us to re-pick each filter in every test and re-wait on the GET.
  test('status + datasource + search filters narrow the list incrementally', async ({
    page,
  }) => {
    if (!datasourceA || !datasourceB) throw new Error('beforeAll did not create datasources');

    await loginViaUi(page);
    await page.goto('/queries');
    await waitForListReady(page);

    // ── Baseline: all 3 of our seeded queries are present. ───────────────
    // QueryListPage renders the id column as <span class="mono muted">
    // {id.slice(0,8)}</span> — exact-string assertion on the 8-char prefix
    // is unambiguous because UUID prefixes don't collide in practice and
    // we control all three ids in beforeAll.
    await expect(
      page.getByText(queryAId.slice(0, 8), { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(queryBId.slice(0, 8), { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(queryCancelledId.slice(0, 8), { exact: true }),
    ).toBeVisible();

    // ── Status filter → Pending review. ──────────────────────────────────
    // QueryListPage builds dropdown labels via queryStatusLabel(t, status) which
    // resolves to `enums.query_status.PENDING_REVIEW` = "Pending review". The
    // filter strip's first combobox is the status Select. q1/q2 settled into
    // PENDING_REVIEW in beforeAll (AF-307: skip path advances out of PENDING_AI).
    const statusSelect = page.getByRole('combobox').nth(0);
    await statusSelect.click();
    await page
      .locator('.ant-select-item-option')
      .filter({ hasText: /^Pending review$/ })
      .click();
    await waitForListReady(page);

    await expect(
      page.getByText(queryAId.slice(0, 8), { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(queryBId.slice(0, 8), { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(queryCancelledId.slice(0, 8), { exact: true }),
    ).toHaveCount(0);

    // ── Datasource filter → datasource A. ────────────────────────────────
    // Fourth combobox in the strip (status, type, risk, datasource).
    const datasourceSelect = page.getByRole('combobox').nth(3);
    await datasourceSelect.click();
    await page
      .locator('.ant-select-item-option')
      .filter({ hasText: datasourceA.name })
      .click();
    await waitForListReady(page);

    await expect(
      page.getByText(queryAId.slice(0, 8), { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(queryBId.slice(0, 8), { exact: true }),
    ).toHaveCount(0);

    // ── Free-text search narrows further (client-side, over the current
    //    server page). UNIQUE_SUFFIX is only present in datasourceA.name,
    //    so the same row stays visible — but this proves the search input
    //    is wired up and the client-side filter executes. We follow with a
    //    string that matches nothing to prove it can also remove all rows.
    const search = page.getByPlaceholder('Search SQL, ID or submitter…');
    await search.fill(UNIQUE_SUFFIX);
    await expect(
      page.getByText(queryAId.slice(0, 8), { exact: true }),
    ).toBeVisible();

    await search.fill('zzz-no-match-zzz');
    await expect(
      page.getByText(queryAId.slice(0, 8), { exact: true }),
    ).toHaveCount(0);

    // Clear so the next test doesn't inherit a filtered state via shared
    // route state (page is per-test so this is belt-and-braces).
    await search.fill('');
  });

  test('clicking a row navigates to /queries/<uuid>', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/queries');
    await waitForListReady(page);

    // Locate the table row whose visible cells include q1's id prefix and
    // click it. onRow wires the navigation onto the <tr>.
    await page
      .locator('tr.ant-table-row')
      .filter({ hasText: queryAId.slice(0, 8) })
      .click();

    await page.waitForURL(new RegExp(`/queries/${queryAId}$`), {
      timeout: 15_000,
    });
    expect(new URL(page.url()).pathname).toBe(`/queries/${queryAId}`);
  });

  test('Export CSV triggers a download with backend timestamp filename', async ({
    page,
    context,
  }) => {
    // playwright.config.ts:33-34 sets extraHTTPHeaders={ Accept: 'application/json' }
    // so the API helpers get JSON-parsed responses. The browser inherits those
    // headers — and the CSV endpoint is declared @GetMapping(produces="text/csv"),
    // so Spring's content negotiation drops it and routes the request into
    // @GetMapping("/{id}") instead, where "export.csv" fails UUID parsing.
    // Override Accept on the page context so the browser advertises text/csv.
    await context.setExtraHTTPHeaders({ Accept: 'text/csv, */*' });

    await loginViaUi(page);
    await page.goto('/queries');
    await waitForListReady(page);

    // Wait on the export network response (rather than page.waitForEvent('download')
    // — Playwright doesn't fire that event for synthetic anchor.click() against
    // a Blob URL). The response carries the same Content-Disposition filename
    // that QueryListPage.tsx parses and forwards to the anchor.download
    // attribute, so asserting the header is equivalent to asserting the
    // suggested filename, but doesn't depend on the browser surfacing the
    // download to Playwright.
    const [response] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'GET' &&
          /\/api\/v1\/queries\/export\.csv(\?|$)/.test(r.url()),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: 'Export CSV' }).click(),
    ]);

    expect(response.status()).toBe(200);
    const disposition = response.headers()['content-disposition'] ?? '';
    // Backend filename pattern: queries-yyyyMMdd-HHmmss.csv (UTC).
    // DefaultQueryCsvExportService.java:25-26,52. The issue body says
    // `queries_YYYY-MM-DD.csv` — that's wrong; we assert the actual format.
    expect(disposition).toMatch(
      /attachment;\s*filename="queries-\d{8}-\d{6}\.csv"/,
    );
  });

  test('empty list renders AntD No data empty state', async ({ page }) => {
    // Stub the list GET to return an empty paginated page. The export
    // endpoint and other routes fall through to the real backend.
    await page.route('**/api/v1/queries**', async (route) => {
      const req = route.request();
      const url = req.url();
      if (req.method() !== 'GET' || !/\/api\/v1\/queries(\?|$)/.test(url)) {
        return route.fallback();
      }
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: [],
          total_elements: 0,
          total_pages: 0,
          page: 0,
          size: 20,
          first: true,
          last: true,
          number_of_elements: 0,
          empty: true,
        }),
      });
    });

    await loginViaUi(page);
    await page.goto('/queries');
    await waitForListReady(page);

    // AntD enUS locale renders the default emptyText as "No data".
    // ConfigProvider locale={enUS} is wired in src/main.tsx.
    await expect(
      page.locator('.ant-table-placeholder .ant-empty-description', {
        hasText: 'No data',
      }),
    ).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('tr.ant-table-row')).toHaveCount(0);
  });

  test('500 from the list endpoint does not crash the page', async ({
    page,
  }) => {
    // QueryListPage does not implement an explicit error state today
    // (see plan — useQuery is destructured for data + isLoading only). This
    // test prevents a regression where the 500 takes the SPA down or breaks
    // the table render. Adding a real error UI is out of scope for #265.
    await page.route('**/api/v1/queries**', async (route) => {
      const req = route.request();
      const url = req.url();
      if (req.method() !== 'GET' || !/\/api\/v1\/queries(\?|$)/.test(url)) {
        return route.fallback();
      }
      await route.fulfill({
        status: 500,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Internal Server Error',
          status: 500,
          detail: 'forced by e2e stub',
        }),
      });
    });

    await loginViaUi(page);
    await page.goto('/queries');

    // The page renders: header + filter strip + Table with empty data.
    // The page-level Skeleton clears once isLoading flips false (data
    // stays undefined on error → rows = []).
    await expect(page.getByRole('button', { name: 'Export CSV' })).toBeVisible(
      { timeout: 15_000 },
    );
    await expect(page.locator('.ant-skeleton-active')).toHaveCount(0, {
      timeout: 15_000,
    });
    await expect(
      page.locator('.ant-table-placeholder .ant-empty-description', {
        hasText: 'No data',
      }),
    ).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('tr.ant-table-row')).toHaveCount(0);
  });
});

void API_BASE; // referenced via helpers + the submit helpers above.
