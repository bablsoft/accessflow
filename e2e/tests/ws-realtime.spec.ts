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

// loginViaUi / submitViaEditor / pickDatasource / typeInEditor mirror the
// helpers in reviews-approve.spec.ts. They're file-local there too; no shared
// module exists yet, so duplicating here matches the current convention.
async function loginViaUi(
  page: Page,
  email: string,
  password: string,
): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

async function typeInEditor(page: Page, sql: string): Promise<void> {
  const content = page.locator('.cm-content');
  await content.click();
  await page.keyboard.type(sql, { delay: 20 });
  await page.keyboard.press('Escape');
}

async function pickDatasource(page: Page, ds: CreatedDatasource): Promise<void> {
  const dsSelect = page.getByRole('combobox').first();
  await dsSelect.click();
  await page
    .locator('.ant-select-item-option')
    .filter({ hasText: ds.name })
    .click();
  await page.keyboard.press('Escape');
}

async function submitViaEditor(
  page: Page,
  ds: CreatedDatasource,
  sql: string,
  justification: string,
): Promise<string> {
  await page.goto('/editor');
  await pickDatasource(page, ds);
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

// Provisioning the approver via Mailcrab (invite → wait → accept) costs ~1s;
// add headroom over the 30s per-test default so the two-context happy path
// has plenty of budget for cross-context WS round-trips.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('ws realtime cache invalidation (AF-289)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let approverAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // A separate approver is required: the workflow service rejects
    // self-approval, so the bootstrap admin can submit but not approve their
    // own query. Mirrors the AF-268 pattern but only one approver is needed
    // because this spec doesn't exercise the race / 409 path.
    approverEmail = `ws-realtime-approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverEmail,
      'AF-289 WS Approver',
      'ADMIN',
    );
    const token = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(
      request,
      token,
      APPROVER_PASSWORD,
      'AF-289 WS Approver',
    );
    approverAccessToken = await loginViaApi(
      request,
      approverEmail,
      APPROVER_PASSWORD,
    );

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF289 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF289 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Happy path: approval on /reviews flips the submitter's pill via WS ──
  //    No page.reload() between submission and assertion — the only way the
  //    pill can flip within the poll window is the WS frame triggering
  //    queryClient.invalidateQueries(['queries','detail',queryId]). The
  //    existing reviews-approve happy-path spec reloads instead, so it would
  //    still pass with the WS layer deleted; this test closes that hole.
  test('reviewer approves on /reviews → submitter pill flips to Approved without reload', async ({
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
        'SELECT 1',
        'AF-289 ws realtime',
      );

      // PageHeader title contains the id + StatusPill (QueryDetailPage:181);
      // the localized PENDING_REVIEW label is "Pending review" (en.json).
      await expect(
        submitterPage
          .getByRole('heading', { level: 1 })
          .getByText('Pending review'),
      ).toBeVisible({ timeout: 15_000 });

      // Sanity-check the test hook is loaded before relying on the WS path.
      // The singleton is created at bundle eval; this also waits for the React
      // tree to mount so RealtimeBridge has fired websocketManager.connect().
      await submitterPage.waitForFunction(
        () =>
          (window as unknown as { __websocketManager?: unknown })
            .__websocketManager !== undefined,
      );

      await loginViaUi(approverPage, approverEmail, APPROVER_PASSWORD);
      await approverPage.goto('/reviews');
      await expect(
        approverPage.getByText(queryId, { exact: true }),
      ).toBeVisible({ timeout: 15_000 });
      // /reviews renders an Ant Design <Table> — anchor on the row that
      // contains this query's full UUID so other pending rows left over by
      // earlier specs don't confuse the locator.
      const reviewRow = approverPage.getByRole('row').filter({ hasText: queryId });
      await reviewRow.getByRole('button', { name: 'Approve' }).click();

      // ── The load-bearing assertion. No reload(). ──
      // Issue spec calls for a 5s window; intervals stay tight so we catch
      // the flip soon after the WS frame lands.
      await expect
        .poll(
          () =>
            submitterPage
              .getByRole('heading', { level: 1 })
              .getByText('Approved')
              .isVisible(),
          { timeout: 5_000, intervals: [250, 500, 1_000] },
        )
        .toBe(true);
    } finally {
      await submitterCtx.close();
      await approverCtx.close();
    }
  });

  // ── 2. Sanity check: with the WS disconnected, the pill must NOT flip. ────
  //    Proves Test 1 isn't trivially passing on some other code path (e.g. a
  //    background refetch or component re-render). Hard sleep is correct here
  //    because we are asserting the *absence* of a state change.
  test('with WS disconnected on submitter page, the pill does not auto-update', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 2',
      'AF-289 ws sanity',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );

    const submitterCtx = await browser.newContext();
    try {
      const submitterPage = await submitterCtx.newPage();
      await loginViaUi(submitterPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await submitterPage.goto(`/queries/${submitted.id}`);
      await expect(
        submitterPage
          .getByRole('heading', { level: 1 })
          .getByText('Pending review'),
      ).toBeVisible({ timeout: 15_000 });

      // Kill the WS. disconnect() sets intentionallyClosed=true so the
      // exponential-backoff reconnect loop stays suppressed for the rest of
      // the test (websocketManager.ts:35-44).
      await submitterPage.evaluate(() => {
        const ws = (
          window as unknown as { __websocketManager?: { disconnect: () => void } }
        ).__websocketManager;
        if (!ws) throw new Error('window.__websocketManager is not exposed');
        ws.disconnect();
      });

      // Approve via API. The backend transitions to APPROVED and publishes
      // the WS frame — which goes nowhere because there is no socket.
      await approveQueryViaApi(request, approverAccessToken, submitted.id);

      // 4s is comfortably longer than the WS round-trip the happy path uses
      // (sub-second in practice) but far shorter than the default staleTime
      // (30s), so a background refetch can't rescue this assertion.
      await submitterPage.waitForTimeout(4_000);

      await expect(
        submitterPage
          .getByRole('heading', { level: 1 })
          .getByText('Pending review'),
      ).toBeVisible();

      // Confirm the backend really did flip — a manual reload triggers a
      // fresh fetch, and the pill becomes Approved. This rules out the
      // alternative "backend never approved" failure mode.
      await submitterPage.reload();
      await expect(
        submitterPage.getByRole('heading', { level: 1 }).getByText('Approved'),
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await submitterCtx.close();
    }
  });
});
