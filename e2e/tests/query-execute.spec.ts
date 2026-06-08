import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const APPROVER_PASSWORD = 'Approver-Pwd!123';

// docker-compose.e2e.yml lowers ACCESSFLOW_PROXY_EXECUTION_STATEMENT_TIMEOUT to
// PT3S so test 3 (SELECT pg_sleep(5)) fires inside the Playwright timeout
// without bumping the per-test timeout. Keep these in sync.
const STATEMENT_TIMEOUT_SECONDS = 3;

async function loginViaUi(
  page: Page,
  email: string,
  password: string,
): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// CodeMirror's contenteditable doesn't accept the standard `.fill()`. Click +
// type matches the convention in query-submit.spec.ts and survives the
// dialect-aware autocomplete popup that opens on the first character.
async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.type(sql, { delay: 20 });
  await page.keyboard.press('Escape');
}

// Pick the seeded datasource on /editor. Unlike query-submit.spec.ts we
// can't wait on the schema introspection response — when the helper
// datasource is the only active one in the org, /editor auto-selects it on
// load and clicking the same option a second time doesn't re-fire the fetch
// or close the dropdown (AntD treats it as a no-op). Press Escape after the
// click to ensure the dropdown is dismissed regardless; the SQL we type
// doesn't rely on schema autocomplete.
async function pickDatasource(page: Page, ds: CreatedDatasource): Promise<void> {
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page
    .locator('.ant-select-item-option')
    .filter({ hasText: ds.name })
    .click();
  await page.keyboard.press('Escape');
}

// Submit via UI ending on /queries/<uuid>. Returns the new query id parsed
// from the URL so subsequent assertions can target the detail page.
async function submitViaEditor(
  page: Page,
  ds: CreatedDatasource,
  sql: string,
  justification: string,
): Promise<string> {
  await page.goto('/editor');
  await pickDatasource(page, ds);
  // The /editor onboarding banner re-fetches setup state on mount and
  // re-renders when the plan + datasource it just learned about flip the
  // "Create a review plan" / "Add your first datasource" steps to DONE. The
  // re-render blurs CodeMirror mid-type and drops subsequent keystrokes
  // (observed: only the first character lands). Wait for the network to be
  // idle so the re-render has already happened before we focus the editor.
  await page.waitForLoadState('networkidle');
  await typeInEditor(page, sql);
  await page
    .getByPlaceholder('Why are you running this query?')
    .fill(justification);
  await page.getByRole('button', { name: 'Submit for review' }).click();
  await page.waitForURL(/\/queries\/[0-9a-f-]{36}$/, { timeout: 15_000 });
  const match = page.url().match(/\/queries\/([0-9a-f-]{36})$/);
  if (!match) throw new Error(`Could not parse query id from ${page.url()}`);
  return match[1];
}

// Two contexts + multiple logins + reload + WS-free poll mean test 1 can
// take longer than the default 30s budget; test 3 also waits ~3s for the
// statement timeout to fire. Bump the per-test budget for this describe.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('query execute (happy path + failures, AF-267)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let approverAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The bootstrap admin is the submitter throughout the suite; we need a
    // second user with reviewer authority to approve. Self-approval is
    // rejected at DefaultReviewService:130, so the approver MUST differ from
    // the submitter. Provision a fresh user per run so re-runs don't trip the
    // (email) uniqueness constraint left over from prior invitation acceptances.
    approverEmail = `approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverEmail,
      'AF-267 Approver',
      'ADMIN',
    );
    const inviteToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(
      request,
      inviteToken,
      APPROVER_PASSWORD,
      'AF-267 Approver',
    );
    approverAccessToken = await loginViaApi(
      request,
      approverEmail,
      APPROVER_PASSWORD,
    );

    // A datasource without a review plan has its pending queries filtered
    // out by DefaultReviewService:189 and prepareDecision throws
    // ReviewerNotEligibleException (403). Seed a one-stage ADMIN-role plan so
    // both UI-driven (test 1) and API-driven (tests 2–3) approvals succeed.
    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF267 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF267 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Happy path: submit → approve via /reviews → execute → results table ─
  test('submitted query is approved via /reviews, executed, and renders results', async ({
    browser,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitterCtx = await browser.newContext();
    const approverCtx = await browser.newContext();
    try {
      const submitterPage = await submitterCtx.newPage();
      const approverPage = await approverCtx.newPage();

      await loginViaUi(submitterPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      const queryId = await submitViaEditor(
        submitterPage,
        datasource,
        'SELECT 1, 2, 3',
        'AF-267 happy-path execute',
      );

      // Detail page renders PENDING REVIEW once the AF-307 skip listener has
      // flipped the status (ai_analysis_enabled=false on the seeded datasource).
      await expect(
        submitterPage
          .getByRole('heading', { level: 1 })
          .getByText('Pending review'),
      ).toBeVisible({ timeout: 15_000 });

      // Approver context: open /reviews and approve the new row. ReviewCard
      // renders the full UUID in a mono span, so we anchor the locator on the
      // exact id to disambiguate when prior specs leave their own queries in
      // PENDING_REVIEW (`query-detail-cancel.spec.ts` deliberately does so).
      await loginViaUi(approverPage, approverEmail, APPROVER_PASSWORD);
      await approverPage.goto('/reviews');
      await expect(
        approverPage.getByText(queryId, { exact: true }),
      ).toBeVisible({ timeout: 15_000 });
      const reviewCard = approverPage
        .locator('div')
        .filter({ hasText: queryId })
        .filter({ has: approverPage.getByRole('button', { name: 'Approve' }) })
        .first();
      await reviewCard.getByRole('button', { name: 'Approve' }).click();
      // Toast text from reviews.on_approve.
      await expect(
        approverPage.getByText('Approved · forwarded to execution'),
      ).toBeVisible({ timeout: 10_000 });

      // Submitter sees APPROVED after re-fetching. Default staleTime keeps the
      // cached PENDING REVIEW for ~30s, so we trigger a refetch via reload.
      await submitterPage.reload();
      await expect(
        submitterPage.getByRole('heading', { level: 1 }).getByText('Approved'),
      ).toBeVisible({ timeout: 15_000 });

      // Execute. The button is only shown when canExecute === true, which the
      // submitter satisfies on an APPROVED query (QueryDetailPage.tsx:159-160).
      const executeButton = submitterPage.getByRole('button', {
        name: 'Execute now',
      });
      await expect(executeButton).toBeVisible();
      await executeButton.click();

      // Success toast from queries.detail.on_execute_success + status flip.
      await expect(submitterPage.getByText('Query executed')).toBeVisible({
        timeout: 15_000,
      });
      await expect(
        submitterPage.getByRole('heading', { level: 1 }).getByText('Executed'),
      ).toBeVisible({ timeout: 15_000 });

      // Results card title from queries.detail.card_results. The Results card
      // only mounts for status === 'EXECUTED' && query_type === 'SELECT'.
      await expect(
        submitterPage.getByText('Results', { exact: true }).first(),
      ).toBeVisible({ timeout: 10_000 });

      // AntD Table renders one row for SELECT 1, 2, 3. PostgreSQL labels each
      // unnamed projection as `?column?`, so we assert structural shape (>= 3
      // column headers, exactly 1 body row) instead of pinning to specific
      // header text.
      const headerCells = submitterPage.locator(
        '.ant-table-thead th.ant-table-cell',
      );
      await expect(headerCells).toHaveCount(3, { timeout: 10_000 });
      const bodyRows = submitterPage.locator(
        '.ant-table-tbody tr.ant-table-row',
      );
      await expect(bodyRows).toHaveCount(1);

      // Verify the rendered cells are 1, 2, 3 (in that order).
      const cellTexts = await bodyRows
        .first()
        .locator('td.ant-table-cell')
        .allInnerTexts();
      expect(cellTexts).toEqual(['1', '2', '3']);
    } finally {
      await submitterCtx.close();
      await approverCtx.close();
    }
  });

  // ── 2. Execute against a deleted datasource → FAILED + error toast ────────
  //
  // DATASOURCE_UNAVAILABLE (422) is thrown when the lookup service returns
  // empty — i.e. the row is missing or inactive. The lifecycle service
  // (DefaultQueryLifecycleService:135) catches it, marks the query FAILED,
  // and returns a 202 with status: 'FAILED', which the FE renders as the
  // "Execution failed" toast + status badge flip.
  test('execute against a deleted datasource → FAILED with error toast', async ({
    page,
    request,
  }) => {
    if (!reviewPlan) throw new Error('reviewPlan not created in beforeAll');
    const throwaway = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF267-DOWN ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      throwaway.id,
      'SELECT 99',
      'AF-267 deleted-datasource failure',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);

    // Drop the datasource so the execute path's findById() returns empty.
    await deleteDatasource(request, adminAccessToken, throwaway.id);

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Approved'),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Execute now' }).click();

    // Two matches without scoping: the AntD toast (role=alert) and the
    // timeline/banner copy that renders when status is FAILED. The toast is
    // the user-visible signal we care about here.
    await expect(
      page.getByRole('alert').getByText('Execution failed'),
    ).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Failed'),
    ).toBeVisible({ timeout: 15_000 });
  });

  // ── 3. Execute SELECT pg_sleep(5) → FAILED on statement timeout ───────────
  //
  // docker-compose.e2e.yml pins ACCESSFLOW_PROXY_EXECUTION_STATEMENT_TIMEOUT
  // to PT3S, so the 5s sleep is cancelled at ~3s. PostgreSQL surfaces this as
  // SQLState 57014, which SqlExceptionTranslator translates to
  // QueryExecutionTimeoutException → 504 inside DefaultQueryExecutor, then
  // caught by the lifecycle service like #2 above and surfaced as FAILED.
  test('execute SELECT pg_sleep(5) → FAILED on statement timeout', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT pg_sleep(5)',
      'AF-267 statement-timeout failure',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Approved'),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Execute now' }).click();

    // Allow a generous timeout: the SQL is cancelled at ~3s, then the round
    // trip + invalidation + re-render adds a few seconds. Stay well under the
    // 30s Playwright per-test budget.
    const failureTimeout = (STATEMENT_TIMEOUT_SECONDS + 10) * 1000;
    await expect(
      page.getByRole('alert').getByText('Execution failed'),
    ).toBeVisible({ timeout: failureTimeout });
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Failed'),
    ).toBeVisible({ timeout: failureTimeout });

    // AF-408: the execution-failure card surfaces the cause. "Error detail" is
    // the card's label (queries.detail.execution_error_label) and is unique —
    // it does not collide with the "Execution failed" toast or timeline label.
    // The statement-timeout path persists the timeout message into error_message.
    await expect(page.getByText('Error detail')).toBeVisible({
      timeout: failureTimeout,
    });
    await expect(
      page.getByText('The query could not be executed. The database returned:'),
    ).toBeVisible();
  });
});

