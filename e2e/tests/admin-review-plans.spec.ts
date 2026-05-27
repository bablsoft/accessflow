import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  deleteReviewPlanViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const APPROVER_PASSWORD = 'Approver-Pwd!123';

// Unique suffix isolates plan + datasource names on reruns against the
// long-lived e2e Postgres. Same pattern as AF-277 / AF-282.
const UNIQUE_SUFFIX = `af283-${Date.now()}`;
const TWO_STAGE_PLAN_NAME = `Two-stage plan ${UNIQUE_SUFFIX}`;
const BOUND_DS_NAME = `Bound DS ${UNIQUE_SUFFIX}`;
const MULTI_STAGE_DS_NAME = `Multi-stage DS ${UNIQUE_SUFFIX}`;
// AF-349 — template-driven plan name (separate from the manual two-stage one).
const STRICT_TEMPLATE_PLAN_NAME = `Strict template ${UNIQUE_SUFFIX}`;

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/review-plans that ReviewPlansPage issues on mount,
// so the Table (or EmptyState) renders real content before we drive the UI.
// Same gate pattern as waitForAiConfigsListReady in AF-277.
async function waitForReviewPlansListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/review-plans(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

// Stale-dropdown DOM left over from a previously-closed AntD Select inside the
// same Modal makes `.ant-select-item-option` selectors flaky (the option that
// resolves first is from the wrong dropdown), and dispatchEvent doesn't reliably
// trigger AntD's selection handler. To stay on the happy path with the least
// UI fiddling, the spec lets every approver keep the form's default REVIEWER
// role and only changes the per-row stage. The two-stage-chain assertion in
// test 5 then invites REVIEWER users instead of ADMIN.

// PUT /api/v1/datasources/{id} pointing it at a different review plan. We use
// this to release the original plan's binding before deleting it.
//
// Worth noting: `DatasourceAdminServiceImpl.update` only writes review_plan_id
// when the inbound value is non-null (no `clearReviewPlan` flag exists, unlike
// `clearAiConfig` for the AI config field). That means a literal `{ review_plan_id:
// null }` body is silently a no-op — the only way to release a binding through
// the public API today is to rebind the datasource to a *different* plan. Test 8
// uses this helper with a throwaway plan to demonstrate the happy-path delete.
async function rebindReviewPlanViaApi(
  request: APIRequestContext,
  accessToken: string,
  datasourceId: string,
  newReviewPlanId: string,
): Promise<void> {
  const putRes = await request.put(`${apiBase()}/api/v1/datasources/${datasourceId}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { review_plan_id: newReviewPlanId },
  });
  if (!putRes.ok()) {
    throw new Error(
      `Rebind review plan failed: ${putRes.status()} ${await putRes.text()}`,
    );
  }
}

// Provisioning two fresh approvers (Mailcrab → invitation accept) takes 1–2s
// per user, the multi-stage test then submits a query and walks two approval
// stages. Bump the per-test budget so the slow startup doesn't push the spec
// past the default 30s. Mirrors AF-268's reviews-approve spec.
test.describe.configure({ timeout: 90_000 });

// AF-283 covers /admin/review-plans CRUD with multi-stage approvers:
//   1. Empty-state assertion (skipped when previous runs left plans behind).
//   2. Create a two-stage plan via the modal → table shows the row.
//   3. Edit the plan's description via the modal → row updates.
//   4. Bind the plan to a datasource via DatasourceSettingsPage.
//   5. Submit a query against that datasource → it advances through both
//      stages (API-driven; the UI side of multi-approver review is already
//      covered by AF-268's reviews-approve spec).
//   6. Delete an in-use plan → 409 → error toast.
//   7. Create with a duplicate name → 409 → error toast.
//   8. Unbind every datasource, then delete the plan → row removed.
//
// describe.serial because tests 2 → 3 → 4 → 5 → 6 → 7 → 8 walk the same plan
// through create → edit → bind → use → blocked-delete → duplicate → cleanup.
test.describe.serial('/admin/review-plans — CRUD with multi-stage approvers', () => {
  let adminAccessToken = '';
  let planId: string | null = null;
  let throwawayPlanId: string | null = null;
  let templatePlanId: string | null = null;
  let boundDatasourceId: string | null = null;
  let multiStageDatasourceId: string | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    // Best-effort cleanup. Datasource DELETE is a SOFT delete (sets
    // is_active=false) so the binding row remains — that means the plan
    // DELETE may return 409 REVIEW_PLAN_IN_USE if test 8 didn't run.
    // deleteReviewPlanViaApi tolerates 409 and 404, so the noise stays quiet
    // and the next `stack:down -v` clears the orphan binding.
    if (boundDatasourceId) {
      await deleteDatasource(request, adminAccessToken, boundDatasourceId);
      boundDatasourceId = null;
    }
    if (multiStageDatasourceId) {
      await deleteDatasource(request, adminAccessToken, multiStageDatasourceId);
      multiStageDatasourceId = null;
    }
    if (planId) {
      await deleteReviewPlanViaApi(request, adminAccessToken, planId);
      planId = null;
    }
    if (throwawayPlanId) {
      await deleteReviewPlanViaApi(request, adminAccessToken, throwawayPlanId);
      throwawayPlanId = null;
    }
    if (templatePlanId) {
      await deleteReviewPlanViaApi(request, adminAccessToken, templatePlanId);
      templatePlanId = null;
    }
  });

  test('1) empty state renders on a fresh stack with zero plans', async ({
    page,
    request,
  }) => {
    const listRes = await request.get(`${apiBase()}/api/v1/review-plans`, {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
    });
    expect(listRes.status()).toBe(200);
    // GET /api/v1/review-plans returns a paginated envelope { content, page, ... },
    // not a bare array.
    const existing = (await listRes.json()) as { content: Array<unknown> };
    test.skip(
      existing.content.length > 0,
      `Stack already has ${existing.content.length} review plans; empty-state assertion is only valid on a fresh database`,
    );

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/review-plans');
    await waitForReviewPlansListReady(page);

    await expect(
      page.getByRole('heading', { name: 'Review plans' }),
    ).toBeVisible();
    // EmptyState renders the description as plain text (key admin.review_plans.empty).
    await expect(
      page.getByText('No review plans yet — add one to start governing query review.'),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: 'Add review plan' }).first(),
    ).toBeVisible();
  });

  test('2) create a two-stage plan via the modal → table shows it', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/review-plans');
    await waitForReviewPlansListReady(page);

    await page.getByRole('button', { name: 'Add review plan' }).first().click();

    // Modal title from admin.review_plans.create_modal_title.
    const modal = page
      .getByRole('dialog')
      .filter({ hasText: 'Add review plan' })
      .first();
    await expect(modal).toBeVisible({ timeout: 10_000 });

    await modal.getByLabel('Name').fill(TWO_STAGE_PLAN_NAME);
    await modal.getByLabel('Description').fill('AF-283 two-stage description');

    // DEFAULT_VALUES seeds one approver row at stage 1 with role=REVIEWER —
    // leave it. Add a second row (defaults to role=REVIEWER, stage=1) and set
    // its stage to 2. See the helper comment above for why we don't touch the
    // role Select via the UI.
    await modal.getByRole('button', { name: 'Add approver' }).click();

    // Bump row 2's stage to 2. `.ant-input-number-input` is AntD's internal
    // class for the actual <input>; the four occurrences in this modal map to:
    //   [0] Minimum approvals    [1] Approval timeout (hours)
    //   [2] stage row 1          [3] stage row 2  (after add)
    const numberInputs = modal.locator('.ant-input-number-input');
    await numberInputs.last().fill('2');

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/review-plans$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Create plan' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as {
      id: string;
      name: string;
      approvers: Array<{ role: string | null; stage: number }>;
    };
    planId = body.id;
    expect(body.name).toBe(TWO_STAGE_PLAN_NAME);
    expect(body.approvers).toHaveLength(2);
    expect(body.approvers.map((a) => a.stage).sort()).toEqual([1, 2]);
    expect(body.approvers.every((a) => a.role === 'REVIEWER')).toBe(true);

    // Success toast from admin.review_plans.create_success.
    await expect(page.getByText('Review plan created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // Row visible in the table with 2 approvers.
    const planRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(TWO_STAGE_PLAN_NAME)),
    });
    await expect(planRow).toBeVisible();
    await expect(planRow.getByText('AF-283 two-stage description')).toBeVisible();
    // approvers_count i18n key renders the count as bare "{{count}}".
    await expect(planRow.locator('td').filter({ hasText: /^2$/ }).first()).toBeVisible();
  });

  test('3) edit the plan description via the modal → row updates', async ({
    page,
  }) => {
    test.skip(!planId, 'Test 2 must succeed to seed the plan');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/review-plans');
    await waitForReviewPlansListReady(page);

    const planRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(TWO_STAGE_PLAN_NAME)),
    });
    await expect(planRow).toBeVisible();
    await planRow.getByRole('button', { name: 'Edit' }).click();

    const modal = page
      .getByRole('dialog')
      .filter({ hasText: `Edit review plan · ${TWO_STAGE_PLAN_NAME}` })
      .first();
    await expect(modal).toBeVisible({ timeout: 10_000 });

    const newDescription = 'AF-283 description edited';
    await modal.getByLabel('Description').fill(newDescription);

    const updateResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new RegExp(`/api/v1/review-plans/${planId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Save changes' }).click();
    expect((await updateResponsePromise).status()).toBe(200);

    await expect(page.getByText('Review plan updated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // Row reflects the new description; the old text is gone.
    const updatedRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(TWO_STAGE_PLAN_NAME)),
    });
    await expect(updatedRow.getByText(newDescription)).toBeVisible();
    await expect(updatedRow.getByText('AF-283 two-stage description')).toHaveCount(0);
  });

  test('4) bind the plan to a datasource via DatasourceSettingsPage', async ({
    page,
    request,
  }) => {
    test.skip(!planId, 'Test 2 must succeed to seed the plan');

    // Arrange: a Postgres datasource with no plan attached. The UI step binds it.
    const datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: BOUND_DS_NAME,
    });
    boundDatasourceId = datasource.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);

    // Wait for the settings tab to render — the page issues a GET first.
    await expect(
      page.getByRole('heading', { name: new RegExp(escapeRegex(BOUND_DS_NAME)) }),
    ).toBeVisible({ timeout: 15_000 });

    // The "Review plan" Select is inside the Limits section.
    const planSelect = page
      .locator('.ant-form-item', { has: page.getByText('Review plan', { exact: true }) })
      .locator('.ant-select')
      .first();
    await planSelect.click();
    // The Select's options dropdown lists plan names. Only one Select is
    // opened in this test, so no stale-dropdown disambiguation needed —
    // .ant-select-item-option is uniquely the current dropdown's options.
    await page
      .locator('.ant-select-item-option')
      .filter({ hasText: new RegExp(escapeRegex(TWO_STAGE_PLAN_NAME)) })
      .first()
      .click();

    const updateResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new RegExp(`/api/v1/datasources/${datasource.id}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save changes' }).click();
    const updateResponse = await updateResponsePromise;
    expect(updateResponse.status()).toBe(200);
    const updatedDs = (await updateResponse.json()) as { review_plan_id: string };
    expect(updatedDs.review_plan_id).toBe(planId);

    await expect(page.getByText('Datasource updated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    // Reload the page to assert the binding persisted server-side. The Select
    // should render the plan name (not the placeholder).
    await page.reload();
    await expect(
      page.getByRole('heading', { name: new RegExp(escapeRegex(BOUND_DS_NAME)) }),
    ).toBeVisible({ timeout: 15_000 });
    const reloadedSelect = page
      .locator('.ant-form-item', { has: page.getByText('Review plan', { exact: true }) })
      .first();
    await expect(reloadedSelect.getByText(TWO_STAGE_PLAN_NAME)).toBeVisible({
      timeout: 10_000,
    });
  });

  test('5) submit a query against the bound datasource → two-stage chain advances', async ({
    request,
  }) => {
    test.skip(!planId, 'Test 2 must succeed to seed the plan');

    // Provision two REVIEWER approvers (matching the plan's stage-1 + stage-2
    // REVIEWER-role approvers — left as the form default; see test 2). The
    // bootstrap admin will be the submitter, so they're ineligible to approve
    // their own query. Mirrors AF-268's multi-approver setup.
    const approverAEmail = `af283-approver-a-${randomUUID()}@e2e.local`;
    const approverBEmail = `af283-approver-b-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);

    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverAEmail,
      'AF-283 Approver A',
      'REVIEWER',
    );
    const tokenA = await waitForInviteToken(request, approverAEmail);
    await acceptInvitationViaApi(request, tokenA, APPROVER_PASSWORD, 'AF-283 Approver A');
    const approverAToken = await loginViaApi(request, approverAEmail, APPROVER_PASSWORD);

    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverBEmail,
      'AF-283 Approver B',
      'REVIEWER',
    );
    const tokenB = await waitForInviteToken(request, approverBEmail);
    await acceptInvitationViaApi(request, tokenB, APPROVER_PASSWORD, 'AF-283 Approver B');
    const approverBToken = await loginViaApi(request, approverBEmail, APPROVER_PASSWORD);

    // A dedicated datasource bound to the plan. Using a fresh one (instead of
    // the test-4 datasource) keeps the unbind step in test 8 deterministic
    // even if a future spec rerun starts mid-stream.
    const datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: MULTI_STAGE_DS_NAME,
      reviewPlanId: planId ?? undefined,
    });
    multiStageDatasourceId = datasource.id;

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1',
      'AF-283 two-stage chain',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');

    // Stage 1 approval — approver A.
    await approveQueryViaApi(request, approverAToken, submitted.id);

    // Query should still be PENDING_REVIEW (stage advanced from 1 → 2, but
    // the chain isn't done). Read the detail and inspect review_decisions.
    const afterStage1Res = await request.get(
      `${apiBase()}/api/v1/queries/${submitted.id}`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect(afterStage1Res.status()).toBe(200);
    const afterStage1 = (await afterStage1Res.json()) as {
      status: string;
      review_decisions: Array<{ stage: number; decision: string }>;
    };
    expect(afterStage1.status).toBe('PENDING_REVIEW');
    expect(afterStage1.review_decisions).toHaveLength(1);
    expect(afterStage1.review_decisions[0]).toMatchObject({ stage: 1, decision: 'APPROVED' });

    // Stage 2 approval — approver B.
    await approveQueryViaApi(request, approverBToken, submitted.id);
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'APPROVED');

    const finalRes = await request.get(`${apiBase()}/api/v1/queries/${submitted.id}`, {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
    });
    const finalBody = (await finalRes.json()) as {
      status: string;
      review_decisions: Array<{ stage: number; decision: string }>;
    };
    expect(finalBody.status).toBe('APPROVED');
    expect(finalBody.review_decisions).toHaveLength(2);
    expect(
      finalBody.review_decisions.map((d) => d.stage).sort(),
    ).toEqual([1, 2]);
    expect(finalBody.review_decisions.every((d) => d.decision === 'APPROVED')).toBe(true);
  });

  test('6) delete an in-use plan → 409 → error toast', async ({ page }) => {
    test.skip(!planId, 'Test 2 must succeed to seed the plan');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/review-plans');
    await waitForReviewPlansListReady(page);

    const planRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(TWO_STAGE_PLAN_NAME)),
    });
    await expect(planRow).toBeVisible();
    await planRow.getByRole('button', { name: 'Delete' }).click();

    // AntD modal.confirm renders detached in a portal with role="dialog".
    const confirmModal = page
      .getByRole('dialog')
      .filter({ hasText: 'Delete this review plan?' })
      .first();
    await expect(confirmModal).toBeVisible({ timeout: 10_000 });
    await expect(
      confirmModal.getByText(`${TWO_STAGE_PLAN_NAME} will be removed. This is permanent.`),
    ).toBeVisible();

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/review-plans/${planId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await confirmModal.getByRole('button', { name: 'Delete' }).click();
    const deleteResponse = await deleteResponsePromise;
    expect(deleteResponse.status()).toBe(409);
    const body = (await deleteResponse.json()) as { error?: string };
    expect(body.error).toBe('REVIEW_PLAN_IN_USE');

    // Error toast from errors.review_plan_in_use.
    await expect(
      page.getByText(
        'This review plan is attached to a datasource and cannot be deleted.',
      ),
    ).toBeVisible({ timeout: 10_000 });

    // Row is still present — delete was rejected.
    await expect(planRow).toBeVisible();
  });

  test('7) create with a duplicate name → 409 → error toast', async ({ page }) => {
    test.skip(!planId, 'Test 2 must succeed to seed the plan');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/review-plans');
    await waitForReviewPlansListReady(page);

    await page.getByRole('button', { name: 'Add review plan' }).first().click();

    const modal = page
      .getByRole('dialog')
      .filter({ hasText: 'Add review plan' })
      .first();
    await expect(modal).toBeVisible({ timeout: 10_000 });
    await modal.getByLabel('Name').fill(TWO_STAGE_PLAN_NAME);

    // The default DEFAULT_VALUES already include one REVIEWER approver row,
    // so the form passes Bean Validation as-is and the POST reaches the name
    // uniqueness check.
    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/review-plans$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Create plan' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(409);
    const body = (await createResponse.json()) as { error?: string };
    expect(body.error).toBe('REVIEW_PLAN_NAME_ALREADY_EXISTS');

    await expect(
      page.getByText(
        'A review plan with that name already exists. Pick a different name.',
      ),
    ).toBeVisible({ timeout: 10_000 });

    // Modal stays open; the Name input still carries the typed value.
    await expect(modal.getByLabel('Name')).toHaveValue(TWO_STAGE_PLAN_NAME);

    // Close the modal so it doesn't bleed into the next test.
    await modal.getByRole('button', { name: 'Cancel' }).click();
  });

  test('8) delete after unbinding → row removed', async ({ page, request }) => {
    test.skip(!planId, 'Test 2 must succeed to seed the plan');

    // Create a throwaway plan and rebind every datasource that points at the
    // original plan to the throwaway. See rebindReviewPlanViaApi for why we
    // can't simply clear the binding via PUT { review_plan_id: null }.
    const throwaway = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `Throwaway plan ${UNIQUE_SUFFIX}`,
    });
    throwawayPlanId = throwaway.id;

    if (boundDatasourceId) {
      await rebindReviewPlanViaApi(
        request,
        adminAccessToken,
        boundDatasourceId,
        throwawayPlanId,
      );
    }
    if (multiStageDatasourceId) {
      await rebindReviewPlanViaApi(
        request,
        adminAccessToken,
        multiStageDatasourceId,
        throwawayPlanId,
      );
    }

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/review-plans');
    await waitForReviewPlansListReady(page);

    const planRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(TWO_STAGE_PLAN_NAME)),
    });
    await expect(planRow).toBeVisible();
    await planRow.getByRole('button', { name: 'Delete' }).click();

    const confirmModal = page
      .getByRole('dialog')
      .filter({ hasText: 'Delete this review plan?' })
      .first();
    await expect(confirmModal).toBeVisible({ timeout: 10_000 });

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/review-plans/${planId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await confirmModal.getByRole('button', { name: 'Delete' }).click();
    expect((await deleteResponsePromise).status()).toBe(204);

    await expect(page.getByText('Review plan deleted', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(planRow).toHaveCount(0, { timeout: 10_000 });

    // Plan is gone; null the id so afterAll doesn't double-delete.
    planId = null;
  });

  // AF-349 — picking a built-in template via the dropdown next to "Add review
  // plan" prefills the create modal and the resulting POST carries the
  // template's defaults (min_approvals=2 + two REVIEWER approver rows).
  test('9) create a plan from the "Strict" template → form prefills and POST persists defaults', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    // GET /api/v1/review-plans/templates fires on page mount; register the
    // listener BEFORE navigating so a fast in-flight response can't slip past
    // us. (Registering after page.goto + waitForReviewPlansListReady was racy
    // — the templates response often arrived before we started listening.)
    const templatesResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/review-plans\/templates$/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );
    await page.goto('/admin/review-plans');
    await waitForReviewPlansListReady(page);
    await templatesResponsePromise;

    // The Dropdown trigger is the second primary button in the Space.Compact
    // (right of "Add review plan"), labelled via aria-label.
    await page.getByRole('button', { name: 'Create from template' }).click();

    // The dropdown menu portals to document.body; pick the strict template item.
    await page
      .getByRole('menuitem')
      .filter({ hasText: 'Strict — writes need 2 approvals' })
      .click();

    const modal = page
      .getByRole('dialog')
      .filter({ hasText: 'Add review plan' })
      .first();
    await expect(modal).toBeVisible({ timeout: 10_000 });

    // Prefill assertions: min_approvals = 2, two approver rows (each row has
    // its own "Remove" icon button).
    const minApprovalsInput = modal.getByLabel('Minimum approvals');
    await expect(minApprovalsInput).toHaveValue('2');
    const removeButtons = modal.getByRole('button', { name: 'Remove' });
    await expect(removeButtons).toHaveCount(2);

    // Name is left blank — admin must fill it in.
    await modal.getByLabel('Name').fill(STRICT_TEMPLATE_PLAN_NAME);

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/review-plans$/.test(r.url()),
      { timeout: 15_000 },
    );
    await modal.getByRole('button', { name: 'Create plan' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as {
      id: string;
      name: string;
      min_approvals_required: number;
      requires_human_approval: boolean;
      auto_approve_reads: boolean;
      approvers: Array<{ role: string | null; stage: number }>;
    };
    templatePlanId = body.id;
    expect(body.name).toBe(STRICT_TEMPLATE_PLAN_NAME);
    expect(body.min_approvals_required).toBe(2);
    expect(body.requires_human_approval).toBe(true);
    expect(body.auto_approve_reads).toBe(false);
    expect(body.approvers).toHaveLength(2);
    expect(body.approvers.map((a) => a.stage).sort()).toEqual([1, 2]);
    expect(body.approvers.every((a) => a.role === 'REVIEWER')).toBe(true);

    await expect(page.getByText('Review plan created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    const planRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(STRICT_TEMPLATE_PLAN_NAME)),
    });
    await expect(planRow).toBeVisible();
  });
});
