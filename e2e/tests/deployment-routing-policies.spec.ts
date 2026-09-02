import { expect, test } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';
import {
  createDeploymentEnvironmentViaApi,
  createDeploymentPipelineViaApi,
  createDeploymentRoutingPolicyViaApi,
  deleteDeploymentPipelineViaApi,
  deleteDeploymentRoutingPolicyViaApi,
  listDeploymentRoutingPoliciesViaApi,
  nextFreeRoutingPriorityViaApi,
  triggerDeploymentViaApi,
  waitForDeploymentStatus,
  type CreatedDeploymentPipeline,
} from '../helpers/deployments';
import { login } from '../helpers/login';
import { activeTabPanel } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Four of the seven tests drive a policy change through the UI and then wait on a real
// trigger → route → status round trip.
test.describe.configure({ timeout: 120_000 });

/**
 * Deployment routing policies (#691) end to end.
 *
 * PipelineRoutingPoliciesTab.test.tsx mocks `@/api/deploymentRoutingPolicies` wholesale, so it
 * proves the payload the modal builds and nothing past it. What only a live stack can show is the
 * seam on the other side: that the payload is accepted, that it round-trips back into the edit
 * modal unchanged, and that DeploymentReviewStateMachine.applyRouting actually turns it into an
 * approved / rejected / still-pending deployment.
 */
test.describe.serial('deployment routing policies (#691)', () => {
  const stamp = Date.now();
  // Run-unique: routing is evaluated org-wide, and the environment name is the condition every
  // policy here matches on. A bare `production` would collide with deployment-review.spec.ts.
  const envName = `rp-prod-${stamp}`;
  const policyName = `e2e-routing-${stamp}`;
  const showcaseName = `e2e-routing-conditions-${stamp}`;

  let adminToken = '';
  let pipeline: CreatedDeploymentPipeline | null = null;
  let basePriority = 0;
  const createdPolicyIds: string[] = [];

  /** Triggers a deployment into the spec's environment and returns its request id. */
  async function trigger(
    request: Parameters<typeof triggerDeploymentViaApi>[0],
    version: string,
  ): Promise<string> {
    const triggered = await triggerDeploymentViaApi(request, adminToken, {
      pipelineId: pipeline!.id,
      environment: envName,
      version,
    });
    return triggered.id;
  }

  /** Opens the settings page straight on the routing-policies tab and returns its panel. */
  async function openRoutingTab(page: Page): Promise<Locator> {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    // ?tab= drives the tab strip directly — with seven tabs it overflows a 1280px viewport.
    await page.goto(`/admin/deployment-pipelines/${pipeline!.id}?tab=routing-policies`);
    return activeTabPanel(page);
  }

  /** Picks an option out of the one open AntD Select dropdown. */
  async function pickOption(page: Page, label: string): Promise<void> {
    await page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')
      .getByTitle(label, { exact: true })
      .click();
  }

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // A leftover enabled *global* policy with no environment or version gate matches every
    // deployment in the org, so it would silently decide this spec's requests before any policy
    // created here is reached. Fail loudly rather than as an unexplained wrong status.
    const preexisting = await listDeploymentRoutingPoliciesViaApi(request, adminToken);
    const catchAll = preexisting.filter(
      (p) =>
        p.pipeline_id === null &&
        p.enabled &&
        // Every condition leaf empty — any single populated leaf (providers and days_of_week
        // included) is enough to keep it away from this spec's uniquely-named environment.
        p.conditions.environments.length === 0 &&
        p.conditions.providers.length === 0 &&
        p.conditions.version_globs.length === 0 &&
        p.conditions.days_of_week.length === 0 &&
        p.conditions.min_risk_level === null &&
        p.conditions.start_time === null,
    );
    expect(
      catchAll.map((p) => `${p.name} (priority ${p.priority})`),
      'an enabled org-global catch-all routing policy would decide this spec\'s deployments',
    ).toEqual([]);

    basePriority = await nextFreeRoutingPriorityViaApi(request, adminToken);

    // AI off so the request reaches routing with no risk signal, and the environment keeps its
    // default review gate on — that gate is the fallback tests 1 and 4 assert against.
    pipeline = await createDeploymentPipelineViaApi(request, adminToken, {
      name: `e2e-routing-691-${stamp}`,
      provider: 'GENERIC',
      aiAnalysisEnabled: false,
    });
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: envName,
      requiredApprovals: 1,
    });
  });

  test.afterAll(async ({ request }) => {
    // Policies first: deleting the pipeline does not cascade to them (no FK on pipeline_id), and
    // an orphan holds its priority against every later run.
    for (const id of createdPolicyIds) {
      await deleteDeploymentRoutingPolicyViaApi(request, adminToken, id);
    }
    if (pipeline) await deleteDeploymentPipelineViaApi(request, adminToken, pipeline.id);
  });

  test('routes to the environment review gate when no policy matches', async ({ request }) => {
    const id = await trigger(request, `1.0.0-${stamp}`);
    await waitForDeploymentStatus(request, adminToken, id, 'PENDING_REVIEW');
  });

  test('an AUTO_APPROVE policy created in the UI approves the next deployment', async ({
    page,
    request,
  }) => {
    const panel = await openRoutingTab(page);
    await panel.getByRole('button', { name: 'Add policy' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Add routing policy' });
    await expect(dialog).toBeVisible();

    // Field ids rather than labels: the form is named `deployment_routing_policy` precisely so its
    // fields do not collide with the General tab's own `name` field once that tab has been visited.
    await dialog.locator('#deployment_routing_policy_name').fill(policyName);
    await dialog.locator('#deployment_routing_policy_priority').fill(String(basePriority));
    await dialog.locator('#deployment_routing_policy_action').click();
    await pickOption(page, 'Auto-approve');
    // Tags-mode Select: the search input is a few pixels wide until the dropdown opens, and Enter
    // only commits a tag while it is open — click first, then type, then dismiss it so the
    // dropdown cannot intercept the modal footer click.
    const environments = dialog.locator('#deployment_routing_policy_environments');
    await environments.click();
    await environments.fill(envName);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Escape');
    // "Minimum AI risk" stays Any on purpose. With AI disabled the deployment carries no risk
    // level, and matchesRisk refuses any floor — a risk-gated policy here would never fire.

    const created = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new URL(r.url()).pathname.endsWith('/admin/deployment-routing-policies'),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Create policy' }).click();
    const createdRes = await created;
    expect(createdRes.status()).toBe(201);
    createdPolicyIds.push(((await createdRes.json()) as { id: string }).id);

    // Scope every assertion to the named row: the table is unpaginated and also lists every
    // org-global policy, so counts and bare text are not this spec's to assert.
    const row = panel.locator('.ant-table-row', { hasText: policyName });
    await expect(row).toContainText('Auto-approve');
    await expect(row).toContainText(`env ${envName}`);
    // First cell, not the whole row: the row text also carries the 13-digit run stamp.
    await expect(row.locator('td').first()).toHaveText(String(basePriority));

    const id = await trigger(request, `1.1.0-${stamp}`);
    await waitForDeploymentStatus(request, adminToken, id, 'APPROVED');
  });

  test('switching the action to AUTO_REJECT rejects the next deployment', async ({
    page,
    request,
  }) => {
    const panel = await openRoutingTab(page);
    const row = panel.locator('.ant-table-row', { hasText: policyName });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole('button', { name: 'Edit' }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: 'Edit routing policy' });
    await expect(dialog).toBeVisible();
    await dialog.locator('#deployment_routing_policy_action').click();
    await pickOption(page, 'Auto-reject');

    const saved = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.includes('/admin/deployment-routing-policies/'),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save policy' }).click();
    expect((await saved).status()).toBe(200);
    await expect(row).toContainText('Auto-reject');

    const id = await trigger(request, `1.2.0-${stamp}`);
    await waitForDeploymentStatus(request, adminToken, id, 'REJECTED');
  });

  test('disabling the policy hands the deployment back to the review gate', async ({
    page,
    request,
  }) => {
    const panel = await openRoutingTab(page);
    const row = panel.locator('.ant-table-row', { hasText: policyName });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole('button', { name: 'Edit' }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: 'Edit routing policy' });
    // AntD Switch renders role="switch" and carries the Form.Item id.
    await dialog.locator('#deployment_routing_policy_enabled').click();

    const saved = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.includes('/admin/deployment-routing-policies/'),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save policy' }).click();
    expect((await saved).status()).toBe(200);
    await expect(row).toContainText('Disabled');

    const id = await trigger(request, `1.3.0-${stamp}`);
    await waitForDeploymentStatus(request, adminToken, id, 'PENDING_REVIEW');
  });

  test('renders and round-trips the full typed condition set', async ({ page, request }) => {
    // Seeded over the wire, not through the modal: TimePicker.RangePicker only exposes its start
    // input by id, so the end of the window is unreachable from a Form.Item id.
    // min_risk_level HIGH also makes this policy structurally unable to fire (AI is off here),
    // which is what keeps it from deciding the deployments the other tests trigger.
    const seeded = await createDeploymentRoutingPolicyViaApi(request, adminToken, {
      name: showcaseName,
      action: 'REQUIRE_APPROVALS',
      requiredApprovals: 2,
      priority: basePriority + 1,
      pipelineId: pipeline!.id,
      conditions: {
        environments: [envName],
        providers: ['GENERIC'],
        min_risk_level: 'HIGH',
        version_globs: ['2.*'],
        days_of_week: [1, 2],
        start_time: '09:00',
        end_time: '17:00',
        timezone: 'Europe/Berlin',
      },
    });
    createdPolicyIds.push(seeded.id);

    const panel = await openRoutingTab(page);
    const row = panel.locator('.ant-table-row', { hasText: showcaseName });
    await expect(row).toBeVisible({ timeout: 15_000 });
    // Regex, not an exact string: LocalTime may serialize as HH:mm or HH:mm:ss and the summary
    // joins the window with an en dash.
    await expect(row).toContainText(
      new RegExp(`env ${envName} · Generic · risk ≥ High · 2\\.\\* · Monday, Tuesday · 09:00.17:00 Europe/Berlin`),
    );
    await expect(row).toContainText('Require approvals');

    // Prefill comes from the real wire payload, which the mocked unit test never sees.
    await row.getByRole('button', { name: 'Edit' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Edit routing policy' });
    await expect(dialog.locator('#deployment_routing_policy_name')).toHaveValue(showcaseName);
    await expect(dialog.locator('#deployment_routing_policy_required_approvals')).toHaveValue('2');
    await expect(dialog.locator('#deployment_routing_policy_time_range')).toHaveValue(/09:00/);
    await expect(dialog.getByText('Monday', { exact: true })).toBeVisible();
    await expect(dialog.getByText('Europe/Berlin', { exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toBeHidden();
  });

  test('refuses a priority another policy already holds and keeps the modal open', async ({
    page,
  }) => {
    const panel = await openRoutingTab(page);
    await panel.getByRole('button', { name: 'Add policy' }).click();
    const dialog = page.getByRole('dialog').filter({ hasText: 'Add routing policy' });
    await dialog.locator('#deployment_routing_policy_name').fill(`e2e-routing-dupe-${stamp}`);
    await dialog.locator('#deployment_routing_policy_priority').fill(String(basePriority));
    await dialog.getByRole('button', { name: 'Create policy' }).click();

    // 409 DEPLOYMENT_ROUTING_POLICY_PRIORITY_CONFLICT, surfaced with the server's own detail.
    await expect(
      page
        .locator('.ant-message-notice', {
          hasText: 'Another deployment routing policy already uses this priority',
        })
        .first(),
    ).toBeVisible({ timeout: 10_000 });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();
  });

  test('flips the disabled policy to global scope and deletes it', async ({ page }) => {
    const panel = await openRoutingTab(page);
    const row = panel.locator('.ant-table-row', { hasText: policyName });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole('button', { name: 'Edit' }).click();

    const dialog = page.getByRole('dialog').filter({ hasText: 'Edit routing policy' });
    // Safe only because test 4 left this policy disabled — an enabled global policy would decide
    // deployments belonging to every other spec in the org.
    await dialog.locator('#deployment_routing_policy_scoped').click();
    const saved = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new URL(r.url()).pathname.includes('/admin/deployment-routing-policies/'),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Save policy' }).click();
    expect((await saved).status()).toBe(200);
    await expect(row).toContainText('Global');

    const deleted = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new URL(r.url()).pathname.includes('/admin/deployment-routing-policies/'),
      { timeout: 15_000 },
    );
    await row.getByRole('button', { name: 'Delete' }).click();
    // The Popconfirm renders in a portal and its confirm button shares the row button's label.
    await page.locator('.ant-popover').getByRole('button', { name: 'Delete' }).click();
    expect((await deleted).status()).toBe(204);
    await expect(panel.locator('.ant-table-row', { hasText: policyName })).toHaveCount(0, {
      timeout: 10_000,
    });
  });
});
