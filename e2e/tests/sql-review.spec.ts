import { expect, test, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';
import { login, ADMIN_EMAIL, ADMIN_PASSWORD } from '../helpers/login';
import {
  deleteSqlReviewRulesetViaApi,
  deleteSqlReviewRulesetsForEnvironmentViaApi,
  listSqlReviewRulesetsViaApi,
} from '../helpers/sqlReview';

// #865 — deterministic SQL review in the UI. Runs in the `serial` project: the backend allows
// exactly one ruleset per environment per organization, so the STAGING binding this spec creates
// is stack-shared state. No other spec assigns a datasource environment, and an org with no
// ruleset evaluates to zero findings, so the binding is invisible to the parallel leg.
//
//   1. Admin creates a STAGING ruleset with `SELECT *` at BLOCK through the admin page.
//   2. Admin binds a fresh Postgres datasource to STAGING on its settings page (round-trip).
//   3. An author typing `SELECT *` in /editor sees the finding without pressing any button; the
//      Submit button stays enabled and its tooltip explains that a human will have to approve.
//   4. Admin edits the rule back to WARN and deletes the ruleset.

const UNIQUE_SUFFIX = `af865-${Date.now()}`;
const RULESET_NAME = `Staging rules ${UNIQUE_SUFFIX}`;
const DATASOURCE_NAME = `Staging DS ${UNIQUE_SUFFIX}`;
const ENVIRONMENT = 'STAGING';
// Rendered by the backend in the request locale (messages.properties → sqlreview.rule.select_star).
const SELECT_STAR_MESSAGE = 'SELECT * fetches every column; list the columns you need';

async function waitForRulesetsListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/sql-review-rulesets(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

async function pickSelectOption(page: Page, combobox: ReturnType<Page['getByRole']>, label: string) {
  await combobox.click();
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: label }).first().click();
}

async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.type(sql, { delay: 20 });
  await page.keyboard.press('Escape');
}

test.describe.configure({ timeout: 120_000 });

test.describe.serial('SQL review — editor lint and ruleset admin (#865)', () => {
  let adminAccessToken = '';
  let datasource: CreatedDatasource | null = null;
  let rulesetId: string | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // A failed earlier run may have left the STAGING binding behind; the create below would 409.
    await deleteSqlReviewRulesetsForEnvironmentViaApi(request, adminAccessToken, ENVIRONMENT);
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: DATASOURCE_NAME,
    });
  });

  test.afterAll(async ({ request }) => {
    if (rulesetId) {
      await deleteSqlReviewRulesetViaApi(request, adminAccessToken, rulesetId);
    }
    await deleteSqlReviewRulesetsForEnvironmentViaApi(request, adminAccessToken, ENVIRONMENT);
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('admin creates a STAGING ruleset with SELECT * set to BLOCK', async ({ page, request }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/sql-review');
    await waitForRulesetsListReady(page);

    await page.getByRole('button', { name: 'Add ruleset' }).first().click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Add ruleset' }).first();
    await expect(modal).toBeVisible({ timeout: 10_000 });

    await modal.getByRole('textbox', { name: 'Name', exact: false }).first().fill(RULESET_NAME);
    await pickSelectOption(page, modal.getByRole('combobox', { name: 'Environment' }), 'Staging');
    // The rule rows are driven by GET /sql-review/rules; the severity control is a Select.
    await pickSelectOption(page, modal.getByRole('combobox', { name: 'Severity for SELECT *' }), 'Block');

    const createResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/sql-review-rulesets$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Create ruleset' }).click();
    const response = await createResponse;
    expect(response.status()).toBe(201);
    const created = (await response.json()) as { id: string; rules: { rule_id: string; severity: string }[] };
    rulesetId = created.id;
    // Only the row that differs from its built-in default is stored.
    expect(created.rules).toEqual([{ rule_id: 'select_star', severity: 'BLOCK', params: {} }]);

    await expect(page.getByText(RULESET_NAME)).toBeVisible({ timeout: 10_000 });

    const rulesets = await listSqlReviewRulesetsViaApi(request, adminAccessToken);
    expect(rulesets.find((r) => r.id === rulesetId)?.environment).toBe(ENVIRONMENT);
  });

  test('datasource environment round-trips through the settings form', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const dsId = datasource.id;

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    const environment = page.getByRole('combobox', { name: 'Environment' });
    await expect(environment).toBeVisible({ timeout: 15_000 });
    // Fresh datasource: the explicit "not set" option is selected.
    const environmentItem = page.locator('.ant-form-item', { has: environment });
    await expect(environmentItem).toContainText('Not set');

    await pickSelectOption(page, environment, 'Staging');

    const [putResponse] = await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          new RegExp(`/api/v1/datasources/${dsId}$`).test(r.url()),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: 'Save changes' }).click(),
    ]);
    expect(putResponse.status()).toBe(200);
    expect(((await putResponse.json()) as { environment?: string }).environment).toBe(ENVIRONMENT);

    await page.reload();
    const reloaded = page.getByRole('combobox', { name: 'Environment' });
    await expect(reloaded).toBeVisible({ timeout: 15_000 });
    await expect(page.locator('.ant-form-item', { has: reloaded })).toContainText('Staging');
  });

  test('author sees the blocking finding appear while typing, and Submit stays enabled', async ({
    page,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/editor');
    const dsSelect = page.getByRole('combobox').first();
    await dsSelect.click();
    const schemaResponse = page.waitForResponse(
      (r) => r.url().includes(`/api/v1/datasources/${datasource!.id}/schema`) && r.ok(),
      { timeout: 15_000 },
    );
    await page.locator('.ant-select-item-option').filter({ hasText: datasource.name }).click();
    await schemaResponse;

    const evaluation = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/api\/v1\/sql-review\/evaluate$/.test(r.url()) && r.ok(),
      { timeout: 15_000 },
    );
    await typeInEditor(page, 'SELECT * FROM information_schema.tables');
    await evaluation;

    // No button was pressed: the finding lands after the typing pause.
    const strip = page.getByTestId('sql-review-strip');
    await expect(strip).toBeVisible({ timeout: 10_000 });
    await expect(strip.getByTestId('sql-review-blocking-count')).toHaveText('1 blocking', {
      timeout: 10_000,
    });
    const blockRow = strip.locator('[data-testid="sql-review-finding"][data-severity="BLOCK"]');
    await expect(blockRow).toHaveCount(1);
    await expect(blockRow).toContainText(SELECT_STAR_MESSAGE);
    // The same finding is a CodeMirror diagnostic: a lint gutter marker on line 1.
    await expect(page.locator('.cm-gutter-lint .cm-lint-marker-error')).toHaveCount(1);

    // Blocking escalates, it never rejects: Submit is enabled and the tooltip says why.
    const submit = page.getByRole('button', { name: 'Submit for review' });
    await expect(submit).toBeEnabled();
    await submit.hover();
    await expect(
      page.getByRole('tooltip', { name: /1 blocking rule finding — this query will require human approval\./ }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('admin lowers the rule to WARN, then deletes the ruleset', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/sql-review');
    await waitForRulesetsListReady(page);

    const row = page.getByRole('row').filter({ hasText: RULESET_NAME });
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.getByRole('button', { name: /Edit/ }).click();
    const modal = page.getByRole('dialog').filter({ hasText: `Edit ruleset · ${RULESET_NAME}` }).first();
    await expect(modal).toBeVisible({ timeout: 10_000 });
    await pickSelectOption(page, modal.getByRole('combobox', { name: 'Severity for SELECT *' }), 'Warn');

    const updateResponse = page.waitForResponse(
      (r) => r.request().method() === 'PUT' && /\/api\/v1\/admin\/sql-review-rulesets\/[0-9a-f-]{36}$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Save changes' }).click();
    const updated = (await (await updateResponse).json()) as { rules: { rule_id: string }[] };
    // WARN is select_star's built-in default, so the row is no longer stored at all.
    expect(updated.rules).toEqual([]);
    await expect(modal).toBeHidden({ timeout: 10_000 });

    await row.getByRole('button', { name: /Delete/ }).click();
    const confirm = page.getByRole('dialog').filter({ hasText: 'Delete ruleset?' }).first();
    await expect(confirm).toBeVisible({ timeout: 10_000 });
    const deleteResponse = page.waitForResponse(
      (r) => r.request().method() === 'DELETE' && /\/api\/v1\/admin\/sql-review-rulesets\/[0-9a-f-]{36}$/.test(r.url()),
      { timeout: 15_000 },
    );
    await confirm.getByRole('button', { name: 'Delete' }).click();
    expect((await deleteResponse).status()).toBe(204);
    rulesetId = null;
    await expect(page.getByRole('row').filter({ hasText: RULESET_NAME })).toHaveCount(0, { timeout: 10_000 });
  });
});
