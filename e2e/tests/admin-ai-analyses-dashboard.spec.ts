import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  listAiConfigsViaApi,
  loginViaApi,
  submitQueryViaApi,
  waitForQueryStatus,
} from '../helpers/datasources';

// AF-347 — admin AI analysis history dashboard. The mock-ai service in
// docker-compose.e2e.yml is a WireMock instance configured to return a canned
// OpenAI Chat Completions response. The bootstrap reconciler seeds
// `e2e-mock-openai` ai_config pointing at it. We:
//   1. Resolve the seeded ai_config id at runtime.
//   2. Create a Postgres datasource with ai_analysis_enabled=true bound to it.
//   3. Submit a handful of queries; the AI listener calls mock-ai → real
//      ai_analyses rows land in the DB.
//   4. Drive the /admin/ai-analyses page and assert the three charts render.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af347-${Date.now()}`;
const DATASOURCE_NAME = `ai-dashboard-ds-${UNIQUE_SUFFIX}`;

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function waitForStatsResponse(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/ai-analyses\/stats(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

test.describe.serial('/admin/ai-analyses dashboard renders seeded analyses', () => {
  let adminToken = '';
  let datasourceId = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const configs = await listAiConfigsViaApi(request, adminToken);
    const mock = configs.find((c) => c.name === 'e2e-mock-openai');
    if (!mock) {
      throw new Error(
        'Bootstrap-seeded e2e-mock-openai AI config not found — '
          + 'check docker-compose.e2e.yml ACCESSFLOW_BOOTSTRAP_AICONFIGS_0_* env vars.',
      );
    }
    const datasource = await createPostgresDatasource(request, adminToken, {
      name: DATASOURCE_NAME,
      aiAnalysisEnabled: true,
      aiConfigId: mock.id,
    });
    datasourceId = datasource.id;

    // Submit 3 queries. Each one triggers the AI listener which calls
    // mock-ai → produces a real ai_analyses row with the canned content.
    for (const sql of ['SELECT 1', 'SELECT 2', 'SELECT 3']) {
      const q = await submitQueryViaApi(request, adminToken, datasourceId, sql);
      // PENDING_REVIEW means the AI analysis has flushed and the workflow
      // transitioned past PENDING_AI.
      await waitForQueryStatus(request, adminToken, q.id, 'PENDING_REVIEW', 20_000);
    }
  });

  test.afterAll(async ({ request }) => {
    if (datasourceId) {
      await deleteDatasource(request, adminToken, datasourceId);
    }
  });

  test('renders three chart cards with seeded data', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/ai-analyses');
    await waitForStatsResponse(page);

    await expect(page.getByTestId('ai-analyses-risk-chart')).toBeVisible();
    await expect(page.getByTestId('ai-analyses-categories-chart')).toBeVisible();
    await expect(page.getByTestId('ai-analyses-submitters-chart')).toBeVisible();
  });

  test('datasource filter scopes to the seeded datasource', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/ai-analyses');
    await waitForStatsResponse(page);

    // AntD Select renders an input with role=combobox; the listbox is in a
    // body-level portal. `showSearch` is on so we can narrow with a substring.
    const combobox = page
      .locator('[data-testid="ai-analyses-datasource"]')
      .getByRole('combobox');
    await combobox.click();
    await combobox.fill(UNIQUE_SUFFIX);

    const refetch = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        r.url().includes('/api/v1/admin/ai-analyses/stats') &&
        r.url().includes(`datasourceId=${datasourceId}`) &&
        r.ok(),
      { timeout: 10_000 },
    );
    await page
      .locator('.ant-select-item-option')
      .filter({ hasText: DATASOURCE_NAME })
      .first()
      .click();
    await refetch;

    await expect(page.getByTestId('ai-analyses-risk-chart')).toBeVisible();
  });

  test('shows empty state when range is years in the past', async ({ page }) => {
    await loginViaUi(page);
    await page.goto('/admin/ai-analyses');
    await waitForStatsResponse(page);

    // Register the refetch listener BEFORE driving the picker so a fast
    // response can't slip past us. AntD RangePicker's onChange fires once
    // BOTH inputs commit, so the picker close + the refetch are the same tick.
    const refetchPromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/admin\/ai-analyses\/stats(\?|$)/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );

    // Set both range inputs to a historical year (e.g. 2020) that contains no analyses.
    const rangePicker = page.locator('[data-testid="ai-analyses-range"]');
    const inputs = rangePicker.locator('input');
    await inputs.nth(0).click();
    await inputs.nth(0).fill('2020-01-01 00:00:00');
    await inputs.nth(0).press('Enter');
    await inputs.nth(1).click();
    await inputs.nth(1).fill('2020-02-01 00:00:00');
    await inputs.nth(1).press('Enter');
    // Some AntD builds debounce the commit until focus leaves the picker —
    // click outside to force-close, which fires onChange and the refetch.
    await page.locator('body').click({ position: { x: 5, y: 5 } });

    await refetchPromise;

    await expect(page.getByText('No AI analyses in this window')).toBeVisible({
      timeout: 5_000,
    });
  });
});
