import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  approveQueryViaApi,
  createClassificationTagViaApi,
  createExportPolicyViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  executeQueryViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  apiBase,
  type CreatedDatasource,
  type CreatedReviewPlan,
  type InvitedUser,
} from '../helpers/datasources';
import type { APIRequestContext } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

// public.users.email carries a PII classification tag (seeded in beforeAll), so a
// DENY_CLASSIFIED policy scoped to the analyst denies their export while the
// org-wide WATERMARK policy stamps everyone else's.
const CLASSIFIED_SELECT = 'SELECT email FROM users ORDER BY email LIMIT 1';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

async function provisionAnalyst(
  request: APIRequestContext,
  adminToken: string,
): Promise<{ user: InvitedUser; token: string }> {
  const email = `export-analyst-${randomUUID()}@e2e.local`;
  await inviteUserViaApi(request, adminToken, email, 'Export Analyst', 'ANALYST');
  const inviteToken = await waitForInviteToken(request, email);
  await acceptInvitationViaApi(request, inviteToken, ANALYST_PASSWORD, 'Export Analyst');
  const token = await loginViaApi(request, email, ANALYST_PASSWORD);
  const res = await request.get(`${apiBase()}/api/v1/admin/users?size=200`, {
    headers: { Authorization: `Bearer ${adminToken}` },
  });
  if (!res.ok()) throw new Error(`List users failed: ${res.status()}`);
  const body = (await res.json()) as { content: InvitedUser[] };
  const found = body.content.find((u) => u.email === email);
  if (!found) throw new Error(`User ${email} not found after invitation`);
  return { user: found, token };
}

/** Submit → approve (as admin) → execute; returns the executed query id. */
async function runQuery(
  request: APIRequestContext,
  submitterToken: string,
  adminToken: string,
  datasourceId: string,
): Promise<string> {
  const submitted = await submitQueryViaApi(
    request,
    submitterToken,
    datasourceId,
    CLASSIFIED_SELECT,
    'AF-626 export governance',
  );
  await waitForQueryStatus(request, submitterToken, submitted.id, 'PENDING_REVIEW');
  await approveQueryViaApi(request, adminToken, submitted.id);
  const outcome = await executeQueryViaApi(request, submitterToken, submitted.id);
  expect(outcome.status).toBe('EXECUTED');
  return submitted.id;
}

test.describe.configure({ timeout: 120_000 });

test.describe.serial('result-export governance (#626)', () => {
  let adminToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;
  let analyst: { user: InvitedUser; token: string };
  let analystQueryId = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    await purgeMailcrab(request);

    analyst = await provisionAnalyst(request, adminToken);

    reviewPlan = await createReviewPlanViaApi(request, adminToken, {
      name: `E2E Export Plan ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E Export ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });

    await grantPermissionViaApi(request, adminToken, datasource.id, analyst.user.id, {
      canRead: true,
    });

    // The classified column that arms DENY_CLASSIFIED (no derived masking — the
    // export test wants the classification, not the mask).
    await createClassificationTagViaApi(request, adminToken, datasource.id, {
      tableName: 'public.users',
      columnName: 'email',
      classifications: ['PII'],
      applyMasking: false,
    });

    // Most-restrictive-wins: the analyst is denied on classified results, while
    // everyone (admins included) gets a watermark.
    await createExportPolicyViaApi(request, adminToken, datasource.id, {
      mode: 'DENY_CLASSIFIED',
      appliesToUserIds: [analyst.user.id],
    });
    await createExportPolicyViaApi(request, adminToken, datasource.id, {
      mode: 'WATERMARK',
    });

    analystQueryId = await runQuery(request, analyst.token, adminToken, datasource.id);
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  // ── 1. Denied exporter: decision says no, download 403s ───────────────────
  test('denied analyst gets allowed=false and a 403 on download', async ({ request }) => {
    const decisionRes = await request.get(
      `${apiBase()}/api/v1/queries/${analystQueryId}/results/export-decision`,
      { headers: { Authorization: `Bearer ${analyst.token}` } },
    );
    expect(decisionRes.ok()).toBe(true);
    const decision = (await decisionRes.json()) as {
      allowed: boolean;
      effective_mode: string;
      classifications_present: string[];
    };
    expect(decision.allowed).toBe(false);
    expect(decision.effective_mode).toBe('DENY_CLASSIFIED');
    expect(decision.classifications_present).toContain('PII');

    const exportRes = await request.get(
      `${apiBase()}/api/v1/queries/${analystQueryId}/results/export?format=CSV`,
      { headers: { Authorization: `Bearer ${analyst.token}` } },
    );
    expect(exportRes.status()).toBe(403);
    const problem = (await exportRes.json()) as { error: string };
    expect(problem.error).toBe('RESULT_EXPORT_DENIED');
  });

  // ── 2. Watermarked exporter: signed download with stamp rows ──────────────
  test('admin download is signed, watermarked, and audited filename-stamped', async ({
    request,
  }) => {
    const res = await request.get(
      `${apiBase()}/api/v1/queries/${analystQueryId}/results/export?format=CSV`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(res.status()).toBe(200);
    expect(res.headers()['content-disposition']).toMatch(
      /attachment; filename="query-results-[0-9a-f]{8}-\d{8}T\d{6}Z\.csv"/,
    );
    expect(res.headers()['x-accessflow-signature']).toBeTruthy();
    expect(res.headers()['x-accessflow-signature-algorithm']).toBe('SHA256withRSA');
    expect(res.headers()['x-accessflow-content-sha256']).toMatch(/^[0-9a-f]{64}$/);

    const body = await res.text();
    // WATERMARK applies to everyone — header and footer stamp rows frame the CSV.
    expect(body.startsWith(`AccessFlow export | ${ADMIN_EMAIL} | `)).toBe(true);
    expect(body).toContain(`query ${analystQueryId}`);
    expect(body).toContain('AccessFlow export end | 1 rows');
  });

  // ── 3. Denied analyst sees a disabled export button on the detail page ────
  test('denied analyst sees a disabled export button with the reason', async ({ page }) => {
    await loginViaUi(page, analyst.user.email, ANALYST_PASSWORD);
    await page.goto(`/queries/${analystQueryId}`);

    // The denied reason rides on the button's accessible name (aria-label).
    const button = page.getByRole('button', { name: /PII/ });
    await expect(button).toBeVisible({ timeout: 15_000 });
    await expect(button).toBeDisabled();
  });

  // ── 4. Admin configures an export policy through the Export policy tab ────
  test('admin creates an export policy via the Export policy tab UI', async ({ page }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);

    await page.getByRole('tab', { name: /Export policy/ }).click();

    // The two API-seeded policies are listed.
    await expect(page.getByText('Deny when classified').first()).toBeVisible({
      timeout: 15_000,
    });

    await page.getByRole('button', { name: 'Add policy' }).click();
    const dialog = page.getByRole('dialog');

    // Default mode is WATERMARK — the live preview renders the stamp template.
    await expect(dialog.getByText(/AccessFlow export \| /).first()).toBeVisible();
    await expect(dialog.getByText(/AccessFlow export end \| /).first()).toBeVisible();

    // Switch to ROW_CAP: the conditional row-cap field appears and the preview
    // footer picks up the cap provenance — the mode-scoped form path the API
    // seeding above does not exercise. (Also keeps this policy distinguishable
    // from the seeded org-wide WATERMARK one.)
    // AntD 6 renders the clickable dropdown items as `.ant-select-item-option` divs;
    // role=option matches rc-select's visually-hidden a11y stubs instead.
    await dialog.getByRole('combobox').first().click();
    await page.locator('.ant-select-item-option').filter({ hasText: 'Row cap' }).click();
    await dialog.getByRole('spinbutton').fill('500');
    await expect(dialog.getByText(/capped at 500/).first()).toBeVisible();

    await dialog.getByRole('button', { name: 'Save' }).click();

    await expect(page.getByText('Export policy saved')).toBeVisible({ timeout: 10_000 });
    // Three policies now: the two seeded + the new row-cap row.
    await expect(page.getByRole('tab', { name: /Export policy · 3/ })).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText('Capped at 500 rows').first()).toBeVisible({ timeout: 10_000 });
  });
});
