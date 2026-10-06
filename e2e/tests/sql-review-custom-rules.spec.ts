import { expect, test, type Locator, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';
import { login, ADMIN_EMAIL, ADMIN_PASSWORD } from '../helpers/login';
import {
  deleteSqlReviewCustomRulesByPrefixViaApi,
  deleteSqlReviewRulesetViaApi,
  deleteSqlReviewRulesetsForEnvironmentViaApi,
  listSqlReviewCustomRulesViaApi,
} from '../helpers/sqlReview';

// #1011 — custom SQL review rules in the UI. Runs in the `serial` project: the TEST ruleset
// binding is stack-shared (one ruleset per environment per organization), and a custom rule joins
// the whole organization's catalog while it exists. The rule is created at default severity OFF so
// it only fires where this spec's TEST ruleset turns it on.
//
//   1. Admin creates a rule on the Custom rules tab: referenced_table glob AND has_where = false,
//      tests it against SQL in the drawer and sees the finding, then saves it.
//   2. Admin edits the rule's description (rule_id stays fixed); a fresh "Create rule" starts empty.
//   3. Admin creates a TEST ruleset that sets the custom rule to BLOCK.
//   4. Admin binds a fresh Postgres datasource to TEST.
//   5. An author typing an unbounded DELETE sees the custom finding as BLOCK; Submit stays enabled.
//   6. Admin deletes the rule from the Custom rules tab.

const RUN = `${Date.now()}`;
const RULE_PREFIX = 'custom_e2e1011_';
const SLUG = `e2e1011_${RUN}`;
const RULE_ID = `custom_${SLUG}`;
const RULE_NAME = `Unbounded writes ${RUN}`;
const RULE_MESSAGE = `Unbounded write on a guarded table (${RUN})`;
const RULE_DESCRIPTION = `Guards e2e tables (${RUN})`;
const TABLE_GLOB = 'e2e_guarded_*';
const TEST_SQL = 'DELETE FROM e2e_guarded_orders';
const RULESET_NAME = `Test rules ${RUN}`;
const DATASOURCE_NAME = `Test DS ${RUN}`;
const ENVIRONMENT = 'TEST';

async function pickSelectOption(page: Page, combobox: Locator, label: string) {
  await combobox.click();
  await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: label })
    .first()
    .click();
  // Let the popup close before the next select opens, or the next lookup can hit this one.
  await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0);
}

/**
 * Re-points a condition row's operand select, found by the operand label it currently shows. The
 * operand list is virtualised (off-screen options are not in the DOM), so walk it with the
 * keyboard. The active option is read through the combobox's own `aria-activedescendant` — other
 * selects' popups linger in the DOM after closing, so a page-wide "active option" lookup is ambiguous.
 */
async function changeOperand(page: Page, scope: Locator, from: string, to: string) {
  const select = scope.locator('.ant-select').filter({ hasText: from }).last();
  await select.click();
  const combobox = select.getByRole('combobox');
  const activeLabel = async () => {
    const id = await combobox.getAttribute('aria-activedescendant');
    return id ? page.locator(`[id="${id}"]`).getAttribute('aria-label') : null;
  };
  for (let i = 0; i < 30 && (await activeLabel()) !== to; i += 1) {
    await page.keyboard.press('ArrowDown');
  }
  expect(await activeLabel()).toBe(to);
  await page.keyboard.press('Enter');
  await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0);
}

test.describe.configure({ timeout: 120_000 });

test.describe.serial('SQL review — custom rules (#1011)', () => {
  let adminAccessToken = '';
  let datasource: CreatedDatasource | null = null;
  let rulesetId: string | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // A failed earlier run may have left the TEST binding or its rule behind.
    await deleteSqlReviewRulesetsForEnvironmentViaApi(request, adminAccessToken, ENVIRONMENT);
    await deleteSqlReviewCustomRulesByPrefixViaApi(request, adminAccessToken, RULE_PREFIX);
    datasource = await createPostgresDatasource(request, adminAccessToken, { name: DATASOURCE_NAME });
  });

  test.afterAll(async ({ request }) => {
    if (rulesetId) {
      await deleteSqlReviewRulesetViaApi(request, adminAccessToken, rulesetId);
    }
    await deleteSqlReviewRulesetsForEnvironmentViaApi(request, adminAccessToken, ENVIRONMENT);
    await deleteSqlReviewCustomRulesByPrefixViaApi(request, adminAccessToken, RULE_PREFIX);
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('admin composes a rule, tests it against SQL, and saves it', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/sql-review?tab=rules');
    await expect(page.getByRole('tab', { name: 'Custom rules', selected: true })).toBeVisible({
      timeout: 15_000,
    });

    await page.getByRole('button', { name: 'Create rule' }).first().click();
    const drawer = page.getByRole('dialog').filter({ hasText: 'Create custom rule' }).first();
    await expect(drawer).toBeVisible({ timeout: 10_000 });

    await drawer.getByLabel('Identifier').fill(SLUG);
    await drawer.getByLabel('Name', { exact: true }).fill(RULE_NAME);
    await drawer.getByLabel('Finding message').fill(RULE_MESSAGE);
    await pickSelectOption(page, drawer.getByRole('combobox', { name: 'Default severity' }), 'Off');

    // Row 1: the seeded "Statement type" row becomes a referenced-table glob.
    await changeOperand(page, drawer, 'Statement type', 'Referenced table');
    const globs = drawer.locator('.ant-select').filter({ hasText: 'e.g. billing.*' }).first();
    await globs.click();
    await page.keyboard.type(TABLE_GLOB);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Escape');

    // Row 2: "Has WHERE clause", left at its default "false" — no predicate at all.
    await drawer.getByRole('button', { name: 'Add condition' }).click();
    await changeOperand(page, drawer, 'Statement type', 'Has WHERE clause');

    // The test panel evaluates the unsaved draft; nothing is persisted.
    await drawer.locator('.cm-content').click();
    await page.keyboard.type(TEST_SQL, { delay: 10 });
    const testResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/api\/v1\/admin\/sql-review-rules\/test$/.test(r.url()),
      { timeout: 15_000 },
    );
    await drawer.getByRole('button', { name: /Run test/ }).click();
    expect((await testResponse).status()).toBe(200);
    const result = drawer.getByTestId('sql-review-rule-test-result');
    await expect(result.getByTestId('sql-review-finding')).toContainText(RULE_MESSAGE, {
      timeout: 10_000,
    });

    const createResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/api\/v1\/admin\/sql-review-rules$/.test(r.url()),
      { timeout: 15_000 },
    );
    await drawer.getByRole('button', { name: 'Create rule' }).click();
    const response = await createResponse;
    expect(response.status()).toBe(201);
    const created = (await response.json()) as { rule_id: string; condition: unknown };
    expect(created.rule_id).toBe(RULE_ID);
    expect(created.condition).toEqual({
      type: 'and',
      children: [
        { type: 'referenced_table', globs: [TABLE_GLOB] },
        { type: 'has_where', expected: false },
      ],
    });

    const panel = page.getByRole('tabpanel');
    await expect(panel.getByRole('row').filter({ hasText: RULE_ID })).toBeVisible({ timeout: 10_000 });
  });

  test('admin edits the rule, and a fresh create starts from an empty form', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/sql-review?tab=rules');
    const row = page.getByRole('tabpanel').getByRole('row').filter({ hasText: RULE_ID });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole('button', { name: new RegExp(`Edit ${RULE_NAME}`) }).click();
    const drawer = page.getByRole('dialog').filter({ hasText: `Edit ${RULE_NAME}` }).first();
    await expect(drawer).toBeVisible({ timeout: 10_000 });
    // rule_id is immutable: the slug is shown but disabled.
    await expect(drawer.getByLabel('Identifier')).toHaveValue(SLUG);
    await expect(drawer.getByLabel('Identifier')).toBeDisabled();
    await drawer.getByLabel('Description').fill(RULE_DESCRIPTION);

    const updateResponse = page.waitForResponse(
      (r) => r.request().method() === 'PUT' && /\/api\/v1\/admin\/sql-review-rules\/[0-9a-f-]{36}$/.test(r.url()),
      { timeout: 15_000 },
    );
    await drawer.getByRole('button', { name: 'Save changes' }).click();
    const updated = (await (await updateResponse).json()) as { rule_id: string; description?: string };
    expect(updated).toMatchObject({ rule_id: RULE_ID, description: RULE_DESCRIPTION });
    await expect(page.getByRole('tabpanel').getByText(RULE_DESCRIPTION)).toBeVisible({ timeout: 10_000 });

    // The drawer's form must not carry the edited rule into a new one.
    await page.getByRole('button', { name: 'Create rule' }).first().click();
    const createDrawer = page.getByRole('dialog').filter({ hasText: 'Create custom rule' }).first();
    await expect(createDrawer).toBeVisible({ timeout: 10_000 });
    await expect(createDrawer.getByLabel('Identifier')).toHaveValue('');
    await expect(createDrawer.getByLabel('Identifier')).toBeEnabled();
    await expect(createDrawer.getByLabel('Name', { exact: true })).toHaveValue('');
    await createDrawer.getByRole('button', { name: 'Cancel' }).click();
  });

  test('admin binds the custom rule to BLOCK in a TEST ruleset', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/sql-review');
    await page.getByRole('button', { name: 'Add ruleset' }).first().click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Add ruleset' }).first();
    await expect(modal).toBeVisible({ timeout: 10_000 });

    await modal.getByRole('textbox', { name: 'Name', exact: false }).first().fill(RULESET_NAME);
    await pickSelectOption(page, modal.getByRole('combobox', { name: 'Environment' }), 'Test');
    const customSection = modal.getByTestId('sql-review-custom-rule-severities');
    await expect(customSection.getByText(RULE_NAME)).toBeVisible({ timeout: 10_000 });
    await pickSelectOption(
      page,
      customSection.getByRole('combobox', { name: `Severity for ${RULE_NAME}` }),
      'Block',
    );

    const createResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/api\/v1\/admin\/sql-review-rulesets$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Create ruleset' }).click();
    const response = await createResponse;
    expect(response.status()).toBe(201);
    const created = (await response.json()) as {
      id: string;
      rules: { rule_id: string; severity: string }[];
    };
    rulesetId = created.id;
    expect(created.rules).toContainEqual({ rule_id: RULE_ID, severity: 'BLOCK', params: {} });
  });

  test('datasource is bound to TEST', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const dsId = datasource.id;
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${dsId}/settings`);
    const environment = page.getByRole('combobox', { name: 'Environment' });
    await expect(environment).toBeVisible({ timeout: 15_000 });
    await pickSelectOption(page, environment, 'Test');
    const [putResponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.request().method() === 'PUT' && new RegExp(`/api/v1/datasources/${dsId}$`).test(r.url()),
        { timeout: 15_000 },
      ),
      page.getByRole('button', { name: 'Save changes' }).click(),
    ]);
    expect(((await putResponse.json()) as { environment?: string }).environment).toBe(ENVIRONMENT);
  });

  test('author sees the custom finding as BLOCK while typing, and Submit stays enabled', async ({
    page,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/editor');
    const dsSelect = page.getByRole('combobox').first();
    await dsSelect.click();
    await page.locator('.ant-select-item-option').filter({ hasText: datasource.name }).click();
    await page.keyboard.press('Escape');
    await expect(page.locator('.cm-content')).toBeVisible({ timeout: 15_000 });
    // The onboarding banner re-renders after its setup-state fetch and blurs CodeMirror.
    await page.waitForLoadState('networkidle');

    const evaluation = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/api\/v1\/sql-review\/evaluate$/.test(r.url()) && r.ok(),
      { timeout: 15_000 },
    );
    await page.locator('.cm-content').click();
    await page.keyboard.type(TEST_SQL, { delay: 20 });
    await page.keyboard.press('Escape');
    await evaluation;

    const strip = page.getByTestId('sql-review-strip');
    await expect(strip).toBeVisible({ timeout: 10_000 });
    const custom = strip
      .locator('[data-testid="sql-review-finding"][data-severity="BLOCK"]')
      .filter({ hasText: RULE_MESSAGE });
    await expect(custom).toHaveCount(1, { timeout: 10_000 });

    // A BLOCK escalates to human review; it never disables submission.
    await expect(page.getByRole('button', { name: 'Submit for review' })).toBeEnabled();
  });

  test('admin deletes the custom rule', async ({ page, request }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/sql-review?tab=rules');
    const row = page.getByRole('tabpanel').getByRole('row').filter({ hasText: RULE_ID });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole('button', { name: new RegExp(`Delete ${RULE_NAME}`) }).click();
    const confirm = page.getByRole('dialog').filter({ hasText: 'Delete custom rule?' }).first();
    await expect(confirm).toBeVisible({ timeout: 10_000 });
    const deleteResponse = page.waitForResponse(
      (r) => r.request().method() === 'DELETE' && /\/api\/v1\/admin\/sql-review-rules\/[0-9a-f-]{36}$/.test(r.url()),
      { timeout: 15_000 },
    );
    await confirm.getByRole('button', { name: 'Delete' }).click();
    expect((await deleteResponse).status()).toBe(204);
    await expect(page.getByRole('tabpanel').getByRole('row').filter({ hasText: RULE_ID })).toHaveCount(0, {
      timeout: 10_000,
    });
    const remaining = await listSqlReviewCustomRulesViaApi(request, adminAccessToken);
    expect(remaining.find((r) => r.rule_id === RULE_ID)).toBeUndefined();
  });
});
