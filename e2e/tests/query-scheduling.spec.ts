import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
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

// The scheduled-run job ticks once per second under e2e
// (ACCESSFLOW_WORKFLOW_SCHEDULED_RUN_POLL_INTERVAL=PT1S in docker-compose.e2e.yml).
// We schedule a few seconds out so submit → approve completes before the job fires.
const SCHEDULE_DELAY_SECONDS = 3;

// Submit → wait PENDING_REVIEW → approve → poll for EXECUTED can take ~10s
// once the scheduler kicks in; give a generous per-test budget.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('query scheduling (AF-345)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let approverAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    approverEmail = `sched-approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverEmail,
      'AF-345 Approver',
      'ADMIN',
    );
    const inviteToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(
      request,
      inviteToken,
      APPROVER_PASSWORD,
      'AF-345 Approver',
    );
    approverAccessToken = await loginViaApi(
      request,
      approverEmail,
      APPROVER_PASSWORD,
    );

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF345 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF345 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Scheduled query auto-executes via ScheduledQueryRunJob ───────────────
  test('approved scheduled query auto-executes when the scheduler fires', async ({
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const scheduledFor = new Date(
      Date.now() + SCHEDULE_DELAY_SECONDS * 1000,
    ).toISOString();
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1',
      'AF-345 schedule auto-execute',
      scheduledFor,
    );

    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );
    await approveQueryViaApi(request, approverAccessToken, submitted.id);

    // GET /queries/{id} should return scheduled_for echoed back.
    const detailRes = await request.get(
      `${apiBase()}/api/v1/queries/${submitted.id}`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect(detailRes.ok()).toBeTruthy();
    const detail = (await detailRes.json()) as {
      status: string;
      scheduled_for: string | null;
    };
    expect(detail.scheduled_for).not.toBeNull();
    expect(['APPROVED', 'EXECUTED']).toContain(detail.status);

    // Wait for the scheduler to fire (poll interval = 1s, scheduled +3s).
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'EXECUTED',
      20_000,
    );
  });

  // ── 2. Cancel a scheduled approved query → CANCELLED ────────────────────────
  test('submitter can cancel an approved scheduled query before it fires', async ({
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // Far enough out that the scheduler won't fire before we cancel.
    const scheduledFor = new Date(Date.now() + 600_000).toISOString();
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 2',
      'AF-345 cancel schedule',
      scheduledFor,
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'APPROVED',
    );

    const cancelRes = await request.post(
      `${apiBase()}/api/v1/queries/${submitted.id}/cancel`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect(cancelRes.status()).toBe(204);

    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'CANCELLED',
    );
  });
});
