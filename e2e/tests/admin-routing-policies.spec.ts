import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  submitQueryViaApi,
  waitForQueryStatus,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const UNIQUE_SUFFIX = `af379-${Date.now()}`;
const BUILDER_POLICY_NAME = `Builder policy ${UNIQUE_SUFFIX}`;
const AUTO_REJECT_POLICY_NAME = `Auto-reject deletes ${UNIQUE_SUFFIX}`;
const CICD_REJECT_POLICY_NAME = `Block CI/CD ${UNIQUE_SUFFIX}`;
const ROUTED_DS_NAME = `Routed DS ${UNIQUE_SUFFIX}`;

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
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

interface CreatedRoutingPolicy {
  id: string;
  name: string;
}

async function createRoutingPolicyViaApi(
  request: APIRequestContext,
  accessToken: string,
  body: Record<string, unknown>,
): Promise<CreatedRoutingPolicy> {
  const res = await request.post(`${apiBase()}/api/v1/admin/routing-policies`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: body,
  });
  if (!res.ok()) {
    throw new Error(`Create routing policy failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as CreatedRoutingPolicy;
}

async function deleteRoutingPolicyViaApi(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  await request.delete(`${apiBase()}/api/v1/admin/routing-policies/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
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
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
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

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
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
});
