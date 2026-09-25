import { expect, test, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  createRoutingPolicyViaApi,
  deleteDatasource,
  deleteRoutingPolicyViaApi,
  loginViaApi,
  type CreatedRoutingPolicy,
  submitQueryViaApi,
  waitForQueryStatus,
} from '../helpers/datasources';
import { login } from '../helpers/login';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af379-${Date.now()}`;
const BUILDER_POLICY_NAME = `Builder policy ${UNIQUE_SUFFIX}`;
const AUTO_REJECT_POLICY_NAME = `Auto-reject deletes ${UNIQUE_SUFFIX}`;
const CICD_REJECT_POLICY_NAME = `Block CI/CD ${UNIQUE_SUFFIX}`;
const JOIN_REJECT_POLICY_NAME = `Block joins ${UNIQUE_SUFFIX}`;
const BYTES_BUILDER_POLICY_NAME = `Escalate big scans ${UNIQUE_SUFFIX}`;
const BYTES_REJECT_POLICY_NAME = `Block big scans ${UNIQUE_SUFFIX}`;
const ROUTED_DS_NAME = `Routed DS ${UNIQUE_SUFFIX}`;

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function waitForRoutingPoliciesListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/routing-policies(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

test.describe.configure({ timeout: 90_000 });

// AF-379 — policy-as-code routing engine:
//   1. Create a routing policy through the guided condition builder (UI) → row appears.
//   2. An AUTO_REJECT policy on DELETE auto-rejects a matching query (API submit) and the
//      query detail page surfaces the matched policy.
test.describe.serial('/admin/routing-policies — routing engine', () => {
  let adminAccessToken = '';
  let datasourceId: string | null = null;
  const createdPolicyIds: string[] = [];

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const ds = await createPostgresDatasource(request, adminAccessToken, {
      name: ROUTED_DS_NAME,
    });
    datasourceId = ds.id;
  });

  test.afterAll(async ({ request }) => {
    for (const id of createdPolicyIds) {
      await deleteRoutingPolicyViaApi(request, adminAccessToken, id);
    }
    if (datasourceId) {
      await deleteDatasource(request, adminAccessToken, datasourceId);
    }
  });

  test('creates a routing policy via the guided builder', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/routing-policies');
    await waitForRoutingPoliciesListReady(page);

    await page.getByRole('button', { name: 'Add policy' }).first().click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Add routing policy' }).first();
    await expect(modal).toBeVisible({ timeout: 10_000 });
    await modal.getByLabel('Name').fill(BUILDER_POLICY_NAME);

    // The builder seeds a sensible default condition (query type ∈ {DELETE}) and a
    // REQUIRE_APPROVALS action, so the happy path only needs a name + submit.
    const createResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/routing-policies$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Create policy' }).click();
    const response = await createResponse;
    expect(response.status()).toBe(201);
    const created = (await response.json()) as CreatedRoutingPolicy;
    createdPolicyIds.push(created.id);

    await expect(page.getByText(BUILDER_POLICY_NAME)).toBeVisible({ timeout: 10_000 });
  });

  test('auto-rejects a matching query and shows the matched policy on the detail page', async ({
    page,
    request,
  }) => {
    // Priority 0 so this AUTO_REJECT wins the first-match-by-priority scan ahead of the
    // REQUIRE_APPROVALS policy the builder test left at priority 1.
    const policy = await createRoutingPolicyViaApi(request, adminAccessToken, {
      name: AUTO_REJECT_POLICY_NAME,
      priority: 0,
      enabled: true,
      action: 'AUTO_REJECT',
      reason: 'payroll deletes are blocked',
      condition: { type: 'query_type', any_of: ['DELETE'] },
    });
    createdPolicyIds.push(policy.id);

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasourceId as string,
      'DELETE FROM accounts WHERE id = 1',
      'e2e: routing auto-reject',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'REJECTED', 20_000);

    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);
    await page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new RegExp(`/api/v1/queries/${submitted.id}(\\?|$)`).test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );

    await expect(page.getByText('Auto-decided by routing policy')).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText(AUTO_REJECT_POLICY_NAME)).toBeVisible({ timeout: 10_000 });
  });

  // AF-446 — a CI/CD-origin condition matches a submission carrying the X-AccessFlow-CI header.
  // A SELECT (the DELETE-only policies above don't match) submitted with the header hits this
  // AUTO_REJECT policy; the same SELECT without the header does not.
  test('matches the cicd_origin condition only when the X-AccessFlow-CI header is set', async ({
    request,
  }) => {
    const policy = await createRoutingPolicyViaApi(request, adminAccessToken, {
      name: CICD_REJECT_POLICY_NAME,
      priority: 2,
      enabled: true,
      action: 'AUTO_REJECT',
      reason: 'CI/CD submissions are blocked',
      condition: { type: 'cicd_origin', expected: true },
    });
    createdPolicyIds.push(policy.id);

    const ciRes = await request.post(`${apiBase()}/api/v1/queries`, {
      headers: { Authorization: `Bearer ${adminAccessToken}`, 'X-AccessFlow-CI': 'true' },
      data: {
        datasource_id: datasourceId as string,
        sql: 'SELECT 1 FROM accounts',
        justification: 'e2e: ci-origin routing',
      },
    });
    expect(ciRes.ok()).toBeTruthy();
    const ciQuery = (await ciRes.json()) as { id: string };
    await waitForQueryStatus(request, adminAccessToken, ciQuery.id, 'REJECTED', 20_000);
  });

  // #940 — a query_shape condition matches a joined query and leaves a single-table one alone.
  test('matches the query_shape condition on a joined query only', async ({ request }) => {
    const policy = await createRoutingPolicyViaApi(request, adminAccessToken, {
      name: JOIN_REJECT_POLICY_NAME,
      // Scoped to this spec's datasource with a run-unique priority: an org-wide AUTO_REJECT on
      // every JOIN would reject the joins other specs run concurrently.
      datasource_id: datasourceId as string,
      priority: 100_000 + Math.floor(Math.random() * 800_000),
      enabled: true,
      action: 'AUTO_REJECT',
      reason: 'joins are blocked',
      condition: { type: 'query_shape', any_of: ['JOIN'] },
    });
    createdPolicyIds.push(policy.id);

    const joined = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasourceId as string,
      'SELECT a.id FROM accounts a JOIN orders o ON o.account_id = a.id',
      'e2e: query-shape routing',
    );
    await waitForQueryStatus(request, adminAccessToken, joined.id, 'REJECTED', 20_000);

    const simple = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasourceId as string,
      'SELECT id FROM accounts WHERE id = 1',
      'e2e: query-shape routing',
    );
    await expect
      .poll(
        async () => {
          const res = await request.get(`${apiBase()}/api/v1/queries/${simple.id}`, {
            headers: { Authorization: `Bearer ${adminAccessToken}` },
          });
          return ((await res.json()) as { status: string }).status;
        },
        { timeout: 20_000 },
      )
      .not.toMatch(/^(PENDING_AI|REJECTED)$/);
  });

  // #941 — the builder offers the estimated_bytes_scanned operand and sends raw bytes.
  test('builds an estimated-bytes-scanned condition entered in TB', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/routing-policies');
    await waitForRoutingPoliciesListReady(page);

    await page.getByRole('button', { name: 'Add policy' }).first().click();
    const modal = page.getByRole('dialog').filter({ hasText: 'Add routing policy' }).first();
    await expect(modal).toBeVisible({ timeout: 10_000 });
    await modal.getByLabel('Name').fill(BYTES_BUILDER_POLICY_NAME);
    await modal.getByLabel('Priority').fill(String(100_000 + Math.floor(Math.random() * 800_000)));

    // The seeded row is "Query type"; switch its operand.
    await modal.locator('.ant-select').filter({ hasText: 'Query type' }).first().click();
    // The operand list is virtualised — off-screen options are not in the DOM — so walk it with
    // the keyboard until the active option is the one we want.
    const active = page.locator('.ant-select-item-option-active');
    for (let i = 0; i < 30; i += 1) {
      if ((await active.textContent()) === 'Estimated bytes scanned') break;
      await page.keyboard.press('ArrowDown');
    }
    await expect(active).toHaveText('Estimated bytes scanned');
    await page.keyboard.press('Enter');
    const bytes = modal.getByLabel('Bytes scanned');
    await expect(bytes).toHaveValue('1');
    await bytes.fill('2');

    const createResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/routing-policies$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Create policy' }).click();
    const response = await createResponse;
    expect(response.status()).toBe(201);
    const created = (await response.json()) as CreatedRoutingPolicy & {
      condition: { children?: { type: string; operator: string; value: number }[] };
    };
    createdPolicyIds.push(created.id);
    expect(created.condition.children?.[0]).toEqual({
      type: 'estimated_bytes_scanned',
      operator: 'GT',
      value: 2_000_000_000_000,
    });
  });

  // #941 — PostgreSQL reports no bytes estimate, so the condition fails closed and never fires.
  test('an estimated_bytes_scanned condition never fires without a bytes estimate', async ({
    request,
  }) => {
    const policy = await createRoutingPolicyViaApi(request, adminAccessToken, {
      name: BYTES_REJECT_POLICY_NAME,
      datasource_id: datasourceId as string,
      priority: 100_000 + Math.floor(Math.random() * 800_000),
      enabled: true,
      action: 'AUTO_REJECT',
      reason: 'scans are too large',
      condition: { type: 'estimated_bytes_scanned', operator: 'GTE', value: 0 },
    });
    createdPolicyIds.push(policy.id);

    const query = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasourceId as string,
      'SELECT id FROM accounts WHERE id = 2',
      'e2e: bytes-scanned routing',
    );
    await expect
      .poll(
        async () => {
          const res = await request.get(`${apiBase()}/api/v1/queries/${query.id}`, {
            headers: { Authorization: `Bearer ${adminAccessToken}` },
          });
          return ((await res.json()) as { status: string }).status;
        },
        { timeout: 20_000 },
      )
      .not.toMatch(/^(PENDING_AI|REJECTED)$/);
  });
});
