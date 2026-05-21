import { test, expect, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const API_BASE = process.env.E2E_API_BASE ?? 'http://localhost:8080';

// Shared across the four serial tests — the datasource is created once in
// beforeAll, used by tests 1–3 (test 4 stubs the list endpoint and doesn't
// need a real datasource), and deleted in afterAll.
let datasource: CreatedDatasource | null = null;
let adminAccessToken = '';

async function loginViaUi(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// CodeMirror typing. `.cm-content` is the contenteditable that owns the SQL
// document. Autocompletion is enabled in SqlEditor.tsx, so a popup pops while
// we type and Enter / Tab would accept a suggestion. The `delay` lets React
// settle each keystroke; the trailing Escape dismisses any popup before
// downstream assertions.
async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.type(sql, { delay: 20 });
  await page.keyboard.press('Escape');
}

// CodeMirror does not respond to a plain `clear()` — the textarea/locator
// isn't a native input. Select-all + Backspace is the documented pattern.
async function clearEditor(page: Page): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.press('Backspace');
}

test.describe.serial('query submission from /editor', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminAccessToken);
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Happy path — pick datasource, type SQL, submit, land on detail page ───────
  test('admin submits SELECT 1 and lands on /queries/<uuid>', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page);

    // Wait for the schema-tree GET on the seeded datasource to complete. The
    // editor auto-selects datasources[0] when nothing is picked, so we just
    // wait on whichever schema endpoint the page fires. The wizard spec may
    // have left its own datasource behind in earlier serial specs — we sort
    // alphabetically after "Compose Postgres …" with "Postgres E2E …", which
    // pushes ours later in the list, so we explicitly switch to our row.
    await page.goto('/editor');

    // Pick our datasource explicitly (the AntD Select in SchemaTree). Clicking
    // the combobox opens the dropdown; we then click the option matching the
    // name we created in beforeAll.
    const dsSelect = page.getByRole('combobox').first();
    await dsSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: datasource.name }).click();

    // Wait for the schema fetch keyed by our datasource id. r.ok() filters
    // out the cached 304-style hits when re-running.
    await page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`) && r.ok(),
      { timeout: 15_000 },
    );

    await typeInEditor(page, 'SELECT 1');

    // Toolbar's char-count line is a sanity check that CodeMirror picked up
    // the keystrokes — `SELECT 1` is 8 chars.
    await expect(page.getByText(/8 chars/)).toBeVisible();

    // Justification — locate by placeholder (the "Justification" text in the
    // page is a plain <label>, not a Form.Item, so getByLabel won't bind).
    // Filling it is hygiene; submit is gated by sqlNonEmpty only.
    await page
      .getByPlaceholder('Why are you running this query?')
      .fill('e2e smoke test for query submission');

    // Submit button label = "Submit for review" (en.json:280). It must be
    // enabled because ai_analysis_enabled=false on the seeded datasource
    // means analysis isn't required (QueryEditorPage.tsx:106-107).
    const submitButton = page.getByRole('button', { name: 'Submit for review' });
    await expect(submitButton).toBeEnabled();

    // Click → toast first, then URL. The toast has duration: 2.5s
    // (QueryEditorPage.tsx:72) and the navigation unmounts the editor;
    // reversing the order risks asserting an already-dismissed toast.
    await submitButton.click();
    await expect(page.getByText(/Query submitted · /)).toBeVisible({ timeout: 15_000 });

    await page.waitForURL(/\/queries\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    expect(new URL(page.url()).pathname).toMatch(/^\/queries\/[0-9a-f-]{36}$/);
  });

  // ── 2. Empty SQL — Submit is disabled until at least one char is typed ───────────
  test('Submit is disabled when SQL is empty', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page);
    await page.goto('/editor');

    // Same explicit datasource pick as test #1 so we don't depend on list
    // ordering.
    const dsSelect = page.getByRole('combobox').first();
    await dsSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: datasource.name }).click();
    await page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`) && r.ok(),
      { timeout: 15_000 },
    );

    const submitButton = page.getByRole('button', { name: 'Submit for review' });
    await expect(submitButton).toBeDisabled();

    // Type one char → button becomes enabled.
    await typeInEditor(page, 'X');
    await expect(submitButton).toBeEnabled();

    // Clear the editor → button goes back to disabled.
    await clearEditor(page);
    await expect(submitButton).toBeDisabled();
  });

  // ── 3. Malformed SQL — backend returns 422 INVALID_SQL ───────────────────────────
  //
  // Today the editor surfaces a toast on 422 INVALID_SQL (the generic
  // `editor.submit_error` from QueryEditorPage.tsx:76-79). The issue spec for
  // #264 asks for an inline gutter / line-column banner instead — that's a
  // separate frontend feature outside this E2E coverage task. Update this
  // assertion when that lands.
  test('malformed SQL renders the submit-error toast and stays on /editor', async ({
    page,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page);
    await page.goto('/editor');

    const dsSelect = page.getByRole('combobox').first();
    await dsSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: datasource.name }).click();
    await page.waitForResponse(
      (r) =>
        r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`) && r.ok(),
      { timeout: 15_000 },
    );

    await typeInEditor(page, 'SELEC 1');
    await page
      .getByPlaceholder('Why are you running this query?')
      .fill('e2e: should 422');

    // Wire up the response wait BEFORE clicking. We match the create endpoint
    // exactly — endsWith('/api/v1/queries') filters the POST out from any
    // GET /api/v1/queries (list) that may race with it.
    const responsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' && r.url().endsWith('/api/v1/queries'),
      { timeout: 15_000 },
    );

    await page.getByRole('button', { name: 'Submit for review' }).click();
    const response = await responsePromise;

    expect(response.status()).toBe(422);
    const body = (await response.json()) as { error?: string };
    expect(body.error).toBe('INVALID_SQL');

    // Toast `editor.submit_error` = "Could not submit query" (en.json:294).
    await expect(page.getByText('Could not submit query')).toBeVisible({
      timeout: 5_000,
    });

    // URL did not change.
    expect(new URL(page.url()).pathname).toBe('/editor');
  });

  // ── 4. Empty datasource list — empty state, no Submit button ─────────────────────
  test('empty datasource list shows the empty state and hides Submit', async ({ page }) => {
    // Mock the list endpoint to return an empty page. Glob is `datasources*`
    // (no `?`) so it matches `/api/v1/datasources?size=100` as well as any
    // future param-less GETs. Only GETs are stubbed — anything else falls
    // through to the real backend.
    await page.route('**/api/v1/datasources*', async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          content: [],
          total_elements: 0,
          total_pages: 0,
          page: 0,
          size: 100,
          first: true,
          last: true,
          number_of_elements: 0,
          empty: true,
        }),
      });
    });

    await loginViaUi(page);
    await page.goto('/editor');

    // Empty-state copy from datasources.list.empty (en.json:407).
    await expect(
      page.getByText('No datasources yet — add one to get started.'),
    ).toBeVisible({ timeout: 10_000 });

    // The early `if (!ds) return …` branch in QueryEditorPage.tsx:94-100
    // renders only the empty-state — no PageHeader, no Submit button.
    await expect(
      page.getByRole('button', { name: 'Submit for review' }),
    ).toHaveCount(0);
  });
});

// Keep this on a single closing line for parity with the other auth specs.
void API_BASE; // referenced via helpers; explicit `void` silences noUnusedLocals.
