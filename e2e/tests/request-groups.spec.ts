import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  deleteReviewPlanViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';

// AF-501: Request chaining & grouping. An analyst bundles two ordered database-query steps into one
// request group, submits it as a single element, an independent reviewer approves the bundle, and it
// executes as an ordered sequence whose per-step progress is shown live on the detail page.
//
// Seeding note: the e2e bootstrap seeds only the admin user and no datasources, so this spec creates
// its own datasources + review plan via the API helpers (mirroring reviews-approve.spec.ts). Both
// query steps target the same compose Postgres because the stack only ships one backing database;
// the ordering/grouping behaviour under test does not depend on the steps hitting different engines.

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const APPROVER_PASSWORD = 'Approver-Pwd!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// CodeMirror's contenteditable doesn't accept .fill(); type into the focused editor. The Nth editor
// on the builder page corresponds to step N (each member card embeds its own SqlEditor).
async function typeInEditor(page: Page, index: number, sql: string): Promise<void> {
  const content = page.locator('.cm-content').nth(index);
  await content.click();
  await page.keyboard.type(sql, { delay: 15 });
  await page.keyboard.press('Escape');
}

// Picks an option from the most-recently-rendered datasource <Select> on the builder. The member
// card's datasource picker is the only combobox inside its card, so scoping by the step testid keeps
// us from clicking an earlier step's select.
async function pickDatasourceForStep(
  page: Page,
  stepIndex: number,
  ds: CreatedDatasource,
): Promise<void> {
  const card = page.getByTestId(`group-member-${stepIndex}`);
  // The datasource Select is searchable: type the name to filter the (virtualized, org-wide) list
  // down to our datasource so the option is rendered before we click it.
  const combo = card.getByRole('combobox').first();
  await combo.click();
  await combo.fill(ds.name);
  await page.locator('.ant-select-item-option').filter({ hasText: ds.name }).click();
  await page.keyboard.press('Escape');
}

// Approve the group through a reviewer token that is NOT the submitter (self-approval is blocked).
async function approveGroupViaApi(
  request: APIRequestContext,
  reviewerToken: string,
  groupId: string,
): Promise<void> {
  const res = await request.post(`${apiBase()}/api/v1/request-groups/${groupId}/approve`, {
    headers: { Authorization: `Bearer ${reviewerToken}` },
    data: { comment: 'e2e approve' },
  });
  if (!res.ok()) {
    throw new Error(`Approve group failed: ${res.status()} ${await res.text()}`);
  }
}

async function getGroupViaApi(
  request: APIRequestContext,
  token: string,
  groupId: string,
): Promise<{ status: string }> {
  const res = await request.get(`${apiBase()}/api/v1/request-groups/${groupId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) {
    throw new Error(`Get group failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as { status: string };
}

async function waitForGroupStatus(
  request: APIRequestContext,
  token: string,
  groupId: string,
  expected: string,
  timeoutMs = 20_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const body = await getGroupViaApi(request, token, groupId);
    last = body.status;
    if (body.status === expected) return;
    await new Promise((r) => setTimeout(r, 250));
  }
  throw new Error(`Group ${groupId} did not reach ${expected} within ${timeoutMs}ms (last: ${last})`);
}

test.describe('request groups (AF-501)', () => {
  let adminToken: string;
  let reviewerToken: string;
  let datasource: CreatedDatasource | null = null;
  let reviewPlan: CreatedReviewPlan | null = null;

  const approverEmail = `group-reviewer-${randomUUID().slice(0, 8)}@accessflow.test`;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // Provision an independent reviewer so the bundle can be approved by someone other than the
    // submitter (self-approval is blocked server-side).
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminToken, approverEmail, 'Group Reviewer', 'REVIEWER');
    const token = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(request, token, APPROVER_PASSWORD, 'Group Reviewer');
    reviewerToken = await loginViaApi(request, approverEmail, APPROVER_PASSWORD);

    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `Group Plan ${Date.now()}`,
      approvers: [{ role: 'REVIEWER', stage: 1 }],
    });
    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Group DS ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) await deleteDatasource(request, adminToken, datasource.id);
    if (reviewPlan) await deleteReviewPlanViaApi(request, adminToken, reviewPlan.id);
  });

  test('build, submit, approve, and execute a two-step group with ordered progress', async ({
    page,
    request,
  }) => {
    const ds = datasource!;
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    // Builder: add two database-query steps.
    await page.goto('/request-groups/new');
    const groupName = `E2E Group ${Date.now()}`;
    await page.locator('#group-name-input').fill(groupName);

    const addStep = page.getByRole('button', { name: 'Add step' });
    await addStep.click();
    await page.getByRole('menuitem', { name: 'Database query' }).click();
    await addStep.click();
    await page.getByRole('menuitem', { name: 'Database query' }).click();

    await expect(page.getByTestId('group-member-0')).toBeVisible();
    await expect(page.getByTestId('group-member-1')).toBeVisible();

    await pickDatasourceForStep(page, 0, ds);
    await page.waitForLoadState('networkidle');
    await typeInEditor(page, 0, 'SELECT 1');

    await pickDatasourceForStep(page, 1, ds);
    await typeInEditor(page, 1, 'SELECT 2');

    // Submit the whole group; backend creates it (DRAFT) and transitions it into the AI/review path.
    const submitResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && /\/request-groups\/[0-9a-f-]+\/submit$/.test(r.url()),
    );
    await page.getByRole('button', { name: 'Submit', exact: true }).click();
    await page.waitForURL(/\/request-groups\/[0-9a-f-]{36}$/, { timeout: 15_000 });
    await submitResponse;

    const groupId = page.url().split('/').pop() as string;

    // The detail page renders the ordered sequence, one card per step.
    await expect(page.getByTestId('group-step-0')).toBeVisible();
    await expect(page.getByTestId('group-step-1')).toBeVisible();

    // An independent reviewer approves the bundle, then it executes as one ordered sequence.
    await waitForGroupStatus(request, adminToken, groupId, 'PENDING_REVIEW');
    await approveGroupViaApi(request, reviewerToken, groupId);
    await waitForGroupStatus(request, adminToken, groupId, 'APPROVED');

    const executeResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith(`/request-groups/${groupId}/execute`),
    );
    await page.reload();
    await page.getByRole('button', { name: 'Execute', exact: true }).click();
    await executeResponse;

    // Sequence runs to completion; both steps end EXECUTED (asserted via API to avoid racing the WS
    // refresh), and the detail page reflects a terminal group status.
    await waitForGroupStatus(request, adminToken, groupId, 'EXECUTED');
    await page.reload();
    await expect(page.getByTestId('group-step-0')).toBeVisible();
    await expect(page.getByTestId('group-step-1')).toBeVisible();
    await expect(page.getByText(groupName)).toBeVisible();
  });

  test('the request groups list shows the new group action and table', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/request-groups');
    await expect(page.getByRole('button', { name: /New group/i })).toBeVisible();
    await expect(page.getByPlaceholder('Search groups…')).toBeVisible();
  });
});
