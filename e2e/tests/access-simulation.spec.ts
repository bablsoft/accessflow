// #1066 — the access simulation page: the query decision trace and the reverse access index.
//
// NOTE (repo memory: e2e port 5173 collision): the main e2e stack binds the
// frontend on host port 5173, which collides with a locally running dev app.
// Free the port (or set E2E_BASE_URL / E2E_API_BASE) before running locally.
//
// Covered here:
//   1. From the sidebar, trace an UPDATE for a run-unique analyst on a run-unique datasource that
//      carries a datasource-scoped AUTO_REJECT routing policy: the routing step lists that policy
//      as matched and decisive, and the resulting status is the policy's action (Rejected).
//   2. The trace is read-only — no query_requests row appears on the datasource.
//   3. The reverse index lists the analyst as able to write to the table.
//   4. An analyst is redirected away — the route is gated on the admin/auditor permissions.
//
// Runs in the `parallel` project: the datasource, the analyst, the table name and the policy
// (scoped to the datasource via datasource_id) are all run-unique, so no concurrent spec's query
// can match the policy and no assertion reads an org-wide aggregate.
import { randomUUID } from 'node:crypto';
import { expect, test, type Locator, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  countQueriesViaApi,
  createPostgresDatasource,
  createRoutingPolicyViaApi,
  deleteDatasource,
  deleteRoutingPolicyViaApi,
  findUserByEmailViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  waitForInviteToken,
} from '../helpers/datasources';
import { login } from '../helpers/login';
import { expandNavSection } from '../helpers/nav';
import { activeTabPanel, clickTab } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';
const ROUTE = '/admin/access-simulations';

// Both tabs stay mounted, so a label query is scoped to the visible panel.
async function pickOption(page: Page, scope: Locator, label: string, search: string): Promise<void> {
  const select = scope.getByLabel(label);
  await select.click();
  await select.fill(search);
  await page.locator('.ant-select-item-option').filter({ hasText: search }).first().click();
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('access simulation (#1066)', () => {
  const suffix = randomUUID().slice(0, 8);
  const table = `e2e_trace_${suffix}`;
  const policyName = `Trace reject updates ${suffix}`;
  const analystEmail = `af1066-${suffix}@e2e.local`;
  let adminToken = '';
  let datasourceId = '';
  let datasourceName = '';
  let policyId = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    await inviteUserViaApi(request, adminToken, analystEmail, '#1066 Trace analyst', 'ANALYST');
    const token = await waitForInviteToken(request, analystEmail);
    await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, '#1066 Trace analyst');
    const analystId = (await findUserByEmailViaApi(request, adminToken, analystEmail)).id;

    datasourceName = `Trace E2E ${suffix}`;
    const datasource = await createPostgresDatasource(request, adminToken, { name: datasourceName });
    datasourceId = datasource.id;
    await grantPermissionViaApi(request, adminToken, datasourceId, analystId, {
      canRead: true,
      canWrite: true,
    });

    // Priority is unique per organization; a run-unique value keeps concurrent specs apart.
    const policy = await createRoutingPolicyViaApi(request, adminToken, {
      name: policyName,
      datasource_id: datasourceId,
      priority: 100_000 + Math.floor(Math.random() * 800_000),
      enabled: true,
      action: 'AUTO_REJECT',
      reason: 'e2e: updates on this datasource are rejected',
      condition: { type: 'query_type', any_of: ['UPDATE'] },
    });
    policyId = policy.id;
  });

  test.afterAll(async ({ request }) => {
    if (policyId) await deleteRoutingPolicyViaApi(request, adminToken, policyId);
    if (datasourceId) await deleteDatasource(request, adminToken, datasourceId);
  });

  test('traces an UPDATE to the routing policy that decides it, and writes nothing', async ({
    browser,
    request,
  }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    await expandNavSection(page, 'Security & Access', 'Access control');
    await page.getByRole('link', { name: 'Access simulation' }).click();
    await expect(page).toHaveURL(ROUTE);
    await expect(page.getByRole('heading', { name: 'Access simulation' })).toBeVisible();

    const panel = activeTabPanel(page);
    await pickOption(page, panel, 'Simulated user', analystEmail);
    await pickOption(page, panel, 'Datasource', datasourceName);

    await panel.locator('.cm-content').click();
    await page.keyboard.type(`UPDATE ${table} SET status = 1 WHERE id = 1`, { delay: 10 });
    await page.keyboard.press('Escape');

    const traced = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/admin/access-simulations'),
      { timeout: 15_000 },
    );
    await panel.getByRole('button', { name: 'Trace' }).click();
    expect((await traced).status()).toBe(200);

    await expect(panel.getByTestId('decision-trace-status')).toHaveText('Rejected');
    const routing = panel.getByTestId('trace-step-ROUTING_POLICIES');
    const row = routing.getByRole('row').filter({ hasText: policyName });
    await expect(row).toBeVisible();
    // Matched and decisive both render "Yes".
    await expect(row.getByText('Yes')).toHaveCount(2);

    // Read-only: the trace created no query request on the datasource.
    expect(await countQueriesViaApi(request, adminToken, datasourceId)).toBe(0);

    // Reverse index: the analyst's direct write grant covers the table.
    await clickTab(page, 'Who has access');
    const index = activeTabPanel(page);
    await pickOption(page, index, 'Datasource', datasourceName);
    await index.getByLabel('Table').fill(table);
    const looked = page.waitForResponse(
      (r) => r.request().method() === 'GET' && r.url().includes('/api/v1/admin/effective-access?'),
      { timeout: 15_000 },
    );
    await index.getByRole('button', { name: 'Show access' }).click();
    expect((await looked).status()).toBe(200);
    const analystRow = index.getByRole('row').filter({ hasText: analystEmail });
    await expect(analystRow).toBeVisible();
    await expect(analystRow.getByText('Direct permission')).toBeVisible();

    await context.close();
  });

  test('an analyst cannot reach the page', async ({ browser }) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    await login(page, analystEmail, ANALYST_PASSWORD);
    await page.goto(ROUTE);
    await expect(page).not.toHaveURL(ROUTE);
    await context.close();
  });
});
