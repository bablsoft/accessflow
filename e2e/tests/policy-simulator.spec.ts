import { expect, test, type APIRequestContext } from '@playwright/test';
import {
  apiBase,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  executeQueryViaApi,
  loginViaApi,
  submitQueryViaApi,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const SUFFIX = `af630-${Date.now()}`;

/**
 * Policy simulator (AF-630) — the dry run an admin gets before saving a routing, row-security or
 * masking policy. Self-isolating: it creates its own datasource and corpus and cleans up, so it
 * stays in the parallel project.
 */
test.describe.configure({ timeout: 120_000 });

test.describe.serial('policy simulator (AF-630)', () => {
  let adminToken = '';
  let plan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    plan = await createReviewPlanViaApi(request, adminToken, {
      name: `E2E Simulator Plan ${SUFFIX}`,
      requiresHumanApproval: false,
      minApprovalsRequired: 1,
      approvalTimeoutHours: 24,
    });
    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E Simulator ${SUFFIX}`,
      reviewPlanId: plan.id,
    });
    // Seed a small corpus so the simulation has something to replay.
    await seedExecutedQuery(request, adminToken, datasource.id);
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  async function seedExecutedQuery(
    request: APIRequestContext,
    token: string,
    datasourceId: string,
  ): Promise<void> {
    const submitted = await submitQueryViaApi(
      request,
      token,
      datasourceId,
      'SELECT 1 AS email',
      'policy simulator corpus',
    );
    await waitForQueryStatus(request, token, submitted.id, 'APPROVED', 30_000);
    // Execute it for real: the row-security and masking corpora are EXECUTED rows only (masking
    // additionally needs a stored result set), so an approved-but-never-run query would leave both
    // empty and make every assertion below pass trivially.
    await executeQueryViaApi(request, token, submitted.id);
    await waitForQueryStatus(request, token, submitted.id, 'EXECUTED', 30_000);
  }

  test('replays traffic against a draft routing policy and reports the diff', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/routing-policies');

    await page.getByRole('button', { name: 'Add policy' }).first().click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Add routing policy' }).first();
    await expect(modal).toBeVisible({ timeout: 15_000 });
    await modal.getByLabel('Name').fill(`Simulated policy ${SUFFIX}`);

    // The Simulate button lives in the modal footer and opens the results drawer.
    const simulateResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/routing-policies\/simulate$/.test(r.url()),
      { timeout: 30_000 },
    );
    await modal.getByRole('button', { name: 'Run simulation' }).click();

    const drawer = page.getByRole('dialog').filter({ hasText: 'Simulate policy impact' }).first();
    await expect(drawer).toBeVisible({ timeout: 15_000 });
    // The drawer never runs on its own — a simulation takes a draft, so the admin asks for it.
    await drawer.getByRole('button', { name: 'Run simulation' }).click();

    const response = await simulateResponse;
    expect(response.status()).toBe(200);
    const body = (await response.json()) as {
      evaluated_count: number;
      caveats: string[];
      samples: unknown[];
    };
    // The seeded query is in the window, so the corpus is genuinely non-empty.
    expect(body.evaluated_count).toBeGreaterThan(0);
    // Every simulation is honest about the approximations it made.
    expect(body.caveats).toContain('MEMBERSHIP_STATE_CURRENT');

    // The drawer must actually render the numbers — a remount that discarded the result would
    // leave the shell behind with no stats.
    await expect(drawer.getByText('Queries replayed')).toBeVisible({ timeout: 15_000 });
    await expect(drawer.getByText(String(body.evaluated_count)).first()).toBeVisible({
      timeout: 15_000,
    });
  });

  test('nudges before saving a high-impact policy but never blocks the save', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/routing-policies');

    await page.getByRole('button', { name: 'Add policy' }).first().click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Add routing policy' }).first();
    await expect(modal).toBeVisible({ timeout: 15_000 });
    await modal.getByLabel('Name').fill(`Nudged policy ${SUFFIX}`);
    // Scope it to this spec's own datasource: an org-wide AUTO_REJECT would judge every other
    // parallel spec's DELETE traffic for as long as it exists.
    await modal.locator('#datasource_id').click();
    await page.getByTitle(`Postgres E2E Simulator ${SUFFIX}`, { exact: true }).click();
    // AUTO_REJECT decides without a human, so it trips the high-impact nudge.
    await modal.locator('#action').click();
    await page.getByTitle('Auto-reject', { exact: true }).click();

    await modal.getByRole('button', { name: 'Create policy' }).click();

    const confirm = page.getByRole('dialog').filter({ hasText: 'Simulate before saving?' });
    await expect(confirm).toBeVisible({ timeout: 15_000 });

    // "Save anyway" is always available — the nudge is advice, not a gate.
    const createResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/routing-policies$/.test(r.url()),
      { timeout: 30_000 },
    );
    await confirm.getByRole('button', { name: 'Save anyway' }).click();
    const created = await createResponse;
    expect(created.status()).toBe(201);

    const body = (await created.json()) as { id: string };
    await page.request.delete(`${apiBase()}/api/v1/admin/routing-policies/${body.id}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
  });

  test('dry-runs a draft row-security predicate from the datasource settings tab', async ({
    page,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await page.getByRole('tab', { name: /Row security/ }).click();

    await page.getByRole('button', { name: 'Add policy' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Add row security policy' }).first();
    await dialog.locator('#table_name').fill('public.demo');
    await dialog.locator('#column_name').fill('tenant_id');
    await dialog.locator('#value_expression').fill(':user.region');
    await dialog.getByText('Add row security policy').click();

    const simulateResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/row-security-policies\/simulate$/.test(r.url()),
      { timeout: 30_000 },
    );
    await dialog.getByRole('button', { name: 'Run simulation' }).click();

    const drawer = page.getByRole('dialog').filter({ hasText: 'Simulate policy impact' }).first();
    await expect(drawer).toBeVisible({ timeout: 15_000 });
    await drawer.getByRole('button', { name: 'Run simulation' }).click();

    const response = await simulateResponse;
    expect(response.status()).toBe(200);
    const body = (await response.json()) as {
      evaluated_count: number;
      unclassifiable_count: number;
      caveats: string[];
    };
    // The executed seed query is in the corpus, so this is a real classification, not an empty run.
    expect(body.evaluated_count).toBeGreaterThan(0);
    // A Postgres datasource classifies in-process, so nothing should be unclassifiable.
    expect(body.unclassifiable_count).toBe(0);
    expect(body.caveats).toContain('MEMBERSHIP_STATE_CURRENT');
    expect(body.caveats).not.toContain('ENGINE_CLASSIFICATION_UNAVAILABLE');

    await expect(drawer.getByText('Queries replayed')).toBeVisible({ timeout: 15_000 });
  });

  test('dry-runs a draft masking policy and reports the bare-name caveat', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await page.getByRole('tab', { name: /Masking/ }).click();

    await page.getByRole('button', { name: 'Add policy' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Add masking policy' }).first();
    await dialog.locator('#column_ref').fill('public.demo.email');
    await dialog.getByText('Add masking policy').click();

    const simulateResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/masking-policies\/simulate$/.test(r.url()),
      { timeout: 30_000 },
    );
    await dialog.getByRole('button', { name: 'Run simulation' }).click();

    const drawer = page.getByRole('dialog').filter({ hasText: 'Simulate policy impact' }).first();
    await expect(drawer).toBeVisible({ timeout: 15_000 });
    await drawer.getByRole('button', { name: 'Run simulation' }).click();

    const response = await simulateResponse;
    expect(response.status()).toBe(200);
    const body = (await response.json()) as {
      evaluated_count: number;
      newly_masked_count: number;
      caveats: string[];
    };
    // The seeded SELECT returns an `email` column and the draft masks `…​.email`, so the bare-name
    // match is genuinely exercised rather than asserted against an empty corpus.
    expect(body.evaluated_count).toBeGreaterThan(0);
    expect(body.newly_masked_count).toBeGreaterThan(0);
    // Stored results record a column name but no table, and the UI must say so.
    expect(body.caveats).toContain('COLUMN_MATCH_BARE_NAME');

    await expect(drawer.getByText('Queries replayed')).toBeVisible({ timeout: 15_000 });
  });
});
