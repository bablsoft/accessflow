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
import {
  createApiConnectorViaApi,
  deleteApiConnectorViaApi,
  type CreatedApiConnector,
} from '../helpers/apiConnectors';

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

// Since #559 the builder cards are compact summaries: "Add step" (or a card's Edit button) opens a
// full-parity authoring drawer — the same Query/API editor surfaces as /editor and /api-editor.
function editDrawer(page: Page) {
  return page.getByTestId('group-member-edit-drawer');
}

// Picks an option from a searchable AntD <Select> inside the drawer. The list is virtualized, so
// the desired option is only DOM-resident after typing the label narrows it (mirrors the proven
// helper in admin-audit-log.spec.ts); Enter selects the highlighted match cleanly.
async function pickTargetInDrawer(page: Page, name: string): Promise<void> {
  const select = editDrawer(page).locator('.ant-select').first();
  await select.scrollIntoViewIfNeeded();
  await select.click();
  const input = select.locator('input');
  await input.fill(name);
  await page
    .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: new RegExp(`^${name}$`) })
    .first()
    .waitFor();
  await input.press('ArrowDown');
  await input.press('Enter');
}

// CodeMirror's contenteditable doesn't accept .fill(); type into the drawer's focused editor,
// then verify the typed content stuck (the editor rebuilds when the schema-introspect response
// lands — it feeds autocomplete — and a rebuild mid-typing used to swallow keystrokes).
async function typeInDrawerEditor(page: Page, sql: string): Promise<void> {
  const content = editDrawer(page).locator('.cm-content').first();
  await content.click();
  await page.keyboard.type(sql, { delay: 15 });
  // Blur (not Escape — that would close the drawer) so the autocomplete popup dismisses.
  await content.blur();
  await expect(content).toContainText(sql);
}

// The first drawer-open for a datasource fires its schema-introspect fetch (later opens hit the
// TanStack cache). Wait for it so the editor isn't rebuilt mid-typing.
const schemaFetched = new WeakMap<Page, Set<string>>();

async function waitForSchemaOnFirstUse(
  page: Page,
  ds: CreatedDatasource,
  pick: () => Promise<void>,
): Promise<void> {
  const seen = schemaFetched.get(page) ?? new Set<string>();
  schemaFetched.set(page, seen);
  if (seen.has(ds.id)) {
    await pick();
    return;
  }
  const schemaResponse = page.waitForResponse(
    (r) => r.url().includes(`/datasources/${ds.id}/schema`),
    { timeout: 15_000 },
  );
  await pick();
  await schemaResponse;
  seen.add(ds.id);
}

async function closeDrawer(page: Page): Promise<void> {
  await page.getByTestId('group-member-edit-done').click();
  await expect(editDrawer(page)).toBeHidden();
}

// Adds a database-query step and authors it in the drawer (which opens automatically on add).
async function addQueryStep(page: Page, ds: CreatedDatasource, sql: string): Promise<void> {
  await page.getByRole('button', { name: 'Add step' }).click();
  await page.getByRole('menuitem', { name: 'Database query' }).click();
  await expect(editDrawer(page)).toBeVisible();
  await waitForSchemaOnFirstUse(page, ds, () => pickTargetInDrawer(page, ds.name));
  await editDrawer(page).locator('.cm-content').first().waitFor();
  await typeInDrawerEditor(page, sql);
  await closeDrawer(page);
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
  let connector: CreatedApiConnector | null = null;

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
    connector = await createApiConnectorViaApi(request, adminToken, {
      name: `Group Connector ${Date.now()}`,
    });
  });

  test.afterAll(async ({ request }) => {
    if (connector) await deleteApiConnectorViaApi(request, adminToken, connector.id);
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

    await addQueryStep(page, ds, 'SELECT 1');
    await addQueryStep(page, ds, 'SELECT 2');

    await expect(page.getByTestId('group-member-0')).toBeVisible();
    await expect(page.getByTestId('group-member-1')).toBeVisible();
    // The compact cards summarise the authored steps (#559).
    await expect(page.getByTestId('group-member-0')).toContainText('SELECT 1');
    await expect(page.getByTestId('group-member-1')).toContainText('SELECT 2');

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

    // Each step is an expandable panel: expanding surfaces the request card (SqlBlock) and the
    // AI-analysis section (no AI provider is configured in the e2e stack, so the not-available
    // note renders), collapsing hides the body again.
    await page.getByTestId('group-step-0-toggle').click();
    await expect(page.getByTestId('group-step-0-body')).toBeVisible();
    await expect(page.getByTestId('group-step-0-body')).toContainText('SELECT 1');
    await expect(page.getByTestId('group-step-0-ai')).toBeVisible();
    await page.getByTestId('group-step-0-toggle').click();
    await expect(page.getByTestId('group-step-0-body')).toHaveCount(0);

    // An independent reviewer approves the bundle, then it executes as one ordered sequence.
    await waitForGroupStatus(request, adminToken, groupId, 'PENDING_REVIEW');

    // Self-approval is blocked server-side, and there is no client-side gate — so the submitting
    // admin still sees the Approve button. Attempting it must surface the backend's localized
    // ProblemDetail `detail` in the toast, not a generic "Something went wrong" (issue #556).
    await page.reload();
    await page.getByRole('button', { name: 'Approve', exact: true }).click();
    await page.getByRole('button', { name: 'OK', exact: true }).click();
    await expect(page.getByText('You cannot approve your own request group')).toBeVisible();
    await expect(page.getByText('Something went wrong. Please try again.')).toHaveCount(0);
    await page.keyboard.press('Escape'); // dismiss the decision modal

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

  // #559: an API_CALL step is authored with the full composer (params / headers / RAW body), saved
  // as a DRAFT, and re-opened for editing via the detail page's Edit button — the composition must
  // round-trip intact (regression: the body used to be dropped on hydrate).
  test('round-trips an API step composition through draft save and re-edit', async ({
    page,
    request,
  }) => {
    const ds = datasource!;
    const conn = connector!;
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    await page.goto('/request-groups/new');
    const groupName = `E2E Draft Group ${Date.now()}`;
    await page.locator('#group-name-input').fill(groupName);

    await addQueryStep(page, ds, 'SELECT 1');

    // API step: connector + method/path + a query param, a header, and a RAW JSON body.
    await page.getByRole('button', { name: 'Add step' }).click();
    await page.getByRole('menuitem', { name: 'API call' }).click();
    const drawer = editDrawer(page);
    await expect(drawer).toBeVisible();
    await pickTargetInDrawer(page, conn.name);
    await drawer.getByLabel('Method').fill('POST');
    await drawer.getByLabel('Path').fill('/api/v1/echo');

    // AntD keeps inactive tab panes mounted (hidden), so scope every interaction to the active pane.
    const activePane = drawer.locator('.ant-tabs-tabpane-active');
    await activePane.getByRole('button', { name: 'Add' }).click();
    await activePane.getByLabel('Key').fill('dryRun');
    await activePane.getByLabel('Value').fill('true');

    await drawer.getByRole('tab', { name: 'Headers' }).click();
    await activePane.getByRole('button', { name: 'Add' }).click();
    await activePane.getByLabel('Key').fill('X-Trace');
    await activePane.getByLabel('Value').fill('e2e-1');

    await drawer.getByRole('tab', { name: 'Body' }).click();
    await activePane.locator('textarea').fill('{"subject":"e2e"}');
    await closeDrawer(page);

    await expect(page.getByTestId('group-member-1')).toContainText('POST /api/v1/echo');

    // Save the draft; the response carries the created group id.
    const saveResponse = page.waitForResponse(
      (r) => r.request().method() === 'POST' && r.url().endsWith('/api/v1/request-groups'),
    );
    await page.getByRole('button', { name: 'Save draft' }).click();
    const saveRes = await saveResponse;
    expect(saveRes.status()).toBe(201);
    const created = (await saveRes.json()) as { id: string };
    const groupId = created.id;

    // The API composition is persisted and returned on the detail response (#559 backend).
    const saved = await request.get(`${apiBase()}/api/v1/request-groups/${groupId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(saved.ok()).toBeTruthy();
    const savedBody = (await saved.json()) as {
      items: {
        target_kind: string;
        request_body: string | null;
        request_headers: Record<string, string>;
        query_params: Record<string, string>;
        body_type: string | null;
      }[];
    };
    const apiItem = savedBody.items.find((i) => i.target_kind === 'API_CALL')!;
    expect(apiItem.request_body).toBe('{"subject":"e2e"}');
    expect(apiItem.request_headers).toMatchObject({ 'X-Trace': 'e2e-1' });
    expect(apiItem.query_params).toMatchObject({ dryRun: 'true' });
    expect(apiItem.body_type).toBe('RAW');

    // Re-open the draft for editing from the detail page (own DRAFT → Edit button).
    await page.goto(`/request-groups/${groupId}`);
    await page.getByTestId('group-edit-button').click();
    await page.waitForURL(`**/request-groups/${groupId}/edit`);
    await expect(page.locator('#group-name-input')).toHaveValue(groupName);

    // The API step re-opens with the full composition intact.
    await page.getByTestId('group-member-1-edit').click();
    await expect(drawer).toBeVisible();
    await expect(drawer.getByLabel('Method')).toHaveValue('POST');
    await expect(drawer.getByLabel('Path')).toHaveValue('/api/v1/echo');
    await expect(activePane.getByLabel('Key')).toHaveValue('dryRun');
    await expect(activePane.getByLabel('Value')).toHaveValue('true');
    await drawer.getByRole('tab', { name: 'Headers' }).click();
    await expect(activePane.getByLabel('Key')).toHaveValue('X-Trace');
    await drawer.getByRole('tab', { name: 'Body' }).click();
    await expect(activePane.locator('textarea')).toHaveValue('{"subject":"e2e"}');
    await closeDrawer(page);

    // Cleanup: drafts are deletable by their submitter.
    const del = await request.delete(`${apiBase()}/api/v1/request-groups/${groupId}`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(del.ok()).toBeTruthy();
  });

  test('the request groups list shows the new group action and table', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/request-groups');
    await expect(page.getByRole('button', { name: /New group/i })).toBeVisible();
    await expect(page.getByPlaceholder('Search groups…')).toBeVisible();
  });
});
