import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  findUserByEmailViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const APPROVER_PASSWORD = 'Approver-Pwd!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

interface OccurrencePage {
  items: {
    id: string;
    status: string;
    rows_affected: number | null;
    executed_at: string | null;
  }[];
  total_elements: number;
}

interface RecurringDetail {
  status: string;
  recurrence_rule: string | null;
  recurrence_until: string | null;
  recurrence_next_run_at: string | null;
  recurrence_halted_reason: string | null;
}

// The recurring job ticks once per second under e2e
// (ACCESSFLOW_WORKFLOW_RECURRING_RUN_POLL_INTERVAL=PT1S and
//  ACCESSFLOW_WORKFLOW_RECURRENCE_MIN_INTERVAL=PT1S in docker-compose.e2e.yml),
// so a PT2S interval yields multiple occurrences within a short window.
test.describe.configure({ timeout: 120_000 });

async function fetchDetail(
  request: import('@playwright/test').APIRequestContext,
  token: string,
  id: string,
): Promise<RecurringDetail> {
  const res = await request.get(`${apiBase()}/api/v1/queries/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok()).toBeTruthy();
  return (await res.json()) as RecurringDetail;
}

async function fetchOccurrences(
  request: import('@playwright/test').APIRequestContext,
  token: string,
  id: string,
): Promise<OccurrencePage> {
  const res = await request.get(
    `${apiBase()}/api/v1/queries/${id}/occurrences?page=0&size=50`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(res.ok()).toBeTruthy();
  return (await res.json()) as OccurrencePage;
}

// Poll the occurrence list — never waitForResponse (the recurring job drives the
// state, not a UI request).
async function waitForExecutedOccurrences(
  request: import('@playwright/test').APIRequestContext,
  token: string,
  parentId: string,
  minCount: number,
  timeoutMs = 40_000,
): Promise<OccurrencePage> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const page = await fetchOccurrences(request, token, parentId);
    const executed = page.items.filter((o) => o.status === 'EXECUTED');
    if (executed.length >= minCount) return page;
    if (Date.now() > deadline) {
      throw new Error(
        `Timed out waiting for ${minCount} EXECUTED occurrences of ${parentId}; ` +
          `have ${executed.length}`,
      );
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
}

test.describe.serial('recurring approved queries (#627)', () => {
  let adminAccessToken = '';
  let approverAccessToken = '';
  let analystAccessToken = '';
  let analystUserId = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    const approverEmail = `rec-approver-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminAccessToken, approverEmail, '627 Approver', 'ADMIN');
    const approverToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(request, approverToken, APPROVER_PASSWORD, '627 Approver');
    approverAccessToken = await loginViaApi(request, approverEmail, APPROVER_PASSWORD);

    const analystEmail = `rec-analyst-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminAccessToken, analystEmail, '627 Analyst', 'ANALYST');
    const analystToken = await waitForInviteToken(request, analystEmail);
    await acceptInvitationViaApi(request, analystToken, ANALYST_PASSWORD, '627 Analyst');
    analystAccessToken = await loginViaApi(request, analystEmail, ANALYST_PASSWORD);
    // The invitation id is NOT the user id — the user row is created at accept time.
    analystUserId = (await findUserByEmailViaApi(request, adminAccessToken, analystEmail)).id;

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan 627 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E 627 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Occurrence loop: approve once, occurrences execute, parent stays APPROVED ──
  test('approved recurring query executes occurrences while the parent stays APPROVED', async ({
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const until = new Date(Date.now() + 60_000).toISOString();
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1',
      '627 occurrence loop',
      undefined,
      { rule: 'PT2S', until },
    );

    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'APPROVED');

    const page = await waitForExecutedOccurrences(
      request,
      adminAccessToken,
      submitted.id,
      2,
    );
    // Assert on the EXECUTED subset — the newest row may be a just-inserted occurrence the
    // job has not finished executing yet.
    const executed = page.items.filter((o) => o.status === 'EXECUTED');
    expect(executed.length).toBeGreaterThanOrEqual(2);
    for (const occurrence of executed) {
      expect(occurrence.rows_affected).not.toBeNull();
      expect(occurrence.executed_at).not.toBeNull();
    }

    // The parent must still be APPROVED — occurrences carry the terminal statuses.
    const detail = await fetchDetail(request, adminAccessToken, submitted.id);
    expect(detail.status).toBe('APPROVED');
    expect(detail.recurrence_rule).toBe('PT2S');
    expect(detail.recurrence_halted_reason ?? null).toBeNull();
  });

  // ── 2. Reviewer kill-switch: a non-submitter reviewer cancels the series ─────────
  test('a reviewer can cancel a recurring series submitted by someone else', async ({
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const until = new Date(Date.now() + 600_000).toISOString();
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 2',
      '627 reviewer kill-switch',
      undefined,
      // First occurrence (+2 min) lands inside the 10-min expiry, but far enough out that
      // the series never fires before the reviewer cancels it.
      { rule: 'PT2M', until },
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'APPROVED');

    // The approver is not the submitter; the recurring kill-switch lets them cancel.
    const cancelRes = await request.post(
      `${apiBase()}/api/v1/queries/${submitted.id}/cancel`,
      { headers: { Authorization: `Bearer ${approverAccessToken}` } },
    );
    expect(cancelRes.status()).toBe(204);
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'CANCELLED');

    const detail = await fetchDetail(request, adminAccessToken, submitted.id);
    expect(detail.recurrence_next_run_at ?? null).toBeNull();
  });

  // ── 3. Fail-closed: revoking the submitter's permission halts the series ─────────
  test('revoking the submitter permission halts the series fail-closed', async ({
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const granted = await grantPermissionViaApi(
      request,
      adminAccessToken,
      datasource.id,
      analystUserId,
      { canRead: true },
    );

    const until = new Date(Date.now() + 600_000).toISOString();
    const submitted = await submitQueryViaApi(
      request,
      analystAccessToken,
      datasource.id,
      'SELECT 3',
      '627 fail-closed halt',
      undefined,
      { rule: 'PT2S', until },
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    await waitForExecutedOccurrences(request, adminAccessToken, submitted.id, 1);

    // Revoke the permission out from under the running series.
    const revokeRes = await request.delete(
      `${apiBase()}/api/v1/datasources/${datasource.id}/permissions/${granted.id}`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect(revokeRes.status()).toBe(204);

    // The next tick halts the series: cursor cleared, reason recorded, parent APPROVED.
    const deadline = Date.now() + 30_000;
    let detail = await fetchDetail(request, adminAccessToken, submitted.id);
    while ((detail.recurrence_halted_reason ?? null) === null && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 1000));
      detail = await fetchDetail(request, adminAccessToken, submitted.id);
    }
    // non_null serialization: an absent field means null — assert a real value arrived.
    expect(detail.recurrence_halted_reason ?? null).not.toBeNull();
    expect(detail.recurrence_next_run_at ?? null).toBeNull();
    expect(detail.status).toBe('APPROVED');

    // No further occurrences accumulate after the halt.
    const after = await fetchOccurrences(request, adminAccessToken, submitted.id);
    await new Promise((r) => setTimeout(r, 4000));
    const later = await fetchOccurrences(request, adminAccessToken, submitted.id);
    expect(later.total_elements).toBe(after.total_elements);
  });

  // ── 4. UI smoke: recurrence picker on the editor, occurrences card on detail ─────
  test('editor offers the recurrence picker and the detail page lists occurrences', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // A short series that has already produced occurrences for the detail-page check.
    const until = new Date(Date.now() + 60_000).toISOString();
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 4',
      '627 ui smoke',
      undefined,
      { rule: 'PT2S', until },
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    await waitForExecutedOccurrences(request, adminAccessToken, submitted.id, 1);

    await page.goto('/login');
    await page.locator('#login-email').fill(ADMIN_EMAIL);
    await page.locator('#login-password').fill(ADMIN_PASSWORD);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/dashboard');

    // Editor: the recurrence mode select is present and switching to interval mode
    // reveals the interval + series-end controls.
    await page.goto('/editor');
    const modeSelect = page.getByTestId('recurrence-mode');
    await expect(modeSelect).toBeVisible();
    await modeSelect.click();
    await page
      .locator('.ant-select-item-option')
      .filter({ hasText: 'Fixed interval' })
      .first()
      .click();
    await expect(page.getByTestId('recurrence-interval')).toBeVisible();
    await expect(page.getByTestId('recurrence-until')).toBeVisible();

    // Detail page: recurrence banner + occurrence table rows.
    await page.goto(`/queries/${submitted.id}`);
    await expect(page.getByTestId('recurrence-banner')).toBeVisible();
    await expect(page.getByTestId('occurrences-card')).toBeVisible();
    await expect(
      page
        .getByTestId('occurrences-card')
        .locator('tr.ant-table-row')
        .first(),
    ).toBeVisible();
  });
});
