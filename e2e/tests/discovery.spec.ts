import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  deleteReviewPlanViaApi,
  executeQueryViaApi,
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
const APPROVER_PASSWORD = 'ApproverPassword!123';

// Unique scratch table so re-runs against a long-lived stack DB never collide.
const TABLE = `discovery_e2e_${Date.now()}`;

interface DiscoveryFindingRow {
  id: string;
  schema_name: string | null;
  table_name: string;
  column_name: string;
  classification: string;
  detector: string;
  status: string;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// The scan runs asynchronously after "Scan now"; poll the findings API until the
// seeded email column surfaces as a PENDING EMAIL/PII finding.
async function waitForEmailFinding(
  request: APIRequestContext,
  token: string,
  datasourceId: string,
): Promise<DiscoveryFindingRow> {
  const deadline = Date.now() + 60_000;
  for (;;) {
    const res = await request.get(
      `${apiBase()}/api/v1/datasources/${datasourceId}/discovery/findings?status=PENDING&size=100`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    if (res.ok()) {
      const body = (await res.json()) as { content: DiscoveryFindingRow[] };
      const match = body.content.find(
        (f) => f.table_name === TABLE && f.column_name === 'customer_email',
      );
      if (match) return match;
    }
    if (Date.now() > deadline) {
      throw new Error(`No EMAIL finding for ${TABLE}.customer_email within 60s`);
    }
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
}

test.describe.configure({ timeout: 120_000 });

test.describe.serial('sensitive-data discovery (AF-623)', () => {
  let adminAccessToken = '';
  let approverAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  async function runApproved(request: APIRequestContext, sql: string): Promise<void> {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      sql,
      'AF-623 discovery seed data',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    const exec = await executeQueryViaApi(request, adminAccessToken, submitted.id);
    expect(exec.status).toBe('EXECUTED');
  }

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // Second user with reviewer authority — self-approval is rejected.
    const approverEmail = `approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminAccessToken, approverEmail, 'AF-623 Approver', 'ADMIN');
    const inviteToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(request, inviteToken, APPROVER_PASSWORD, 'AF-623 Approver');
    approverAccessToken = await loginViaApi(request, approverEmail, APPROVER_PASSWORD);

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF623 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E Discovery ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });

    // Seed a table whose email column the scan must flag: the detectors need at
    // least 5 non-null string samples, so insert 6 rows.
    await runApproved(
      request,
      `CREATE TABLE ${TABLE} (id integer PRIMARY KEY, customer_email text NOT NULL)`,
    );
    await runApproved(
      request,
      `INSERT INTO ${TABLE} (id, customer_email) VALUES ` +
        "(1, 'alice@example.com'), (2, 'bob@example.com'), (3, 'carol@example.com'), " +
        "(4, 'dave@example.com'), (5, 'erin@example.com'), (6, 'frank@example.com')",
    );
  });

  test.afterAll(async ({ request }) => {
    // Best-effort teardown; uniquely-named objects don't break re-runs anyway.
    try {
      await runApproved(request, `DROP TABLE IF EXISTS ${TABLE}`);
    } catch {
      // ignore
    }
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
    if (reviewPlan) {
      try {
        await deleteReviewPlanViaApi(request, adminAccessToken, reviewPlan.id);
      } catch {
        // plan may still be referenced; ignore
      }
    }
  });

  test('enable discovery, scan now, and see the proposed finding', async ({ page, request }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await page.getByRole('tab', { name: /Discovery/ }).click();

    // Enable scheduled discovery and save.
    await expect(page.getByText('Discovery settings')).toBeVisible({ timeout: 15_000 });
    await page.getByRole('switch').first().click();
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText('Discovery settings saved')).toBeVisible({ timeout: 10_000 });

    // Kick an immediate scan and wait (via API) for the seeded column to surface.
    await page.getByRole('button', { name: 'Scan now' }).click();
    await expect(page.getByText('Discovery scan started')).toBeVisible({ timeout: 10_000 });
    const finding = await waitForEmailFinding(request, adminAccessToken, datasource.id);
    expect(finding.classification).toBe('PII');
    expect(finding.detector).toBe('EMAIL');

    // The worklist shows the finding after a reload.
    await page.goto(`/datasources/${datasource.id}/settings`);
    await page.getByRole('tab', { name: /Discovery/ }).click();
    await expect(
      page.getByText(`public.${TABLE}.customer_email`).first(),
    ).toBeVisible({ timeout: 15_000 });
  });

  test('bulk-confirming the finding applies the tag and derives masking', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/datasources/${datasource.id}/settings`);
    await page.getByRole('tab', { name: /Discovery/ }).click();

    const row = page.getByRole('row', { name: new RegExp(`public\\.${TABLE}\\.customer_email`) });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.getByRole('checkbox').check();

    await page.getByRole('button', { name: 'Confirm selected' }).click();
    await expect(page.getByText('1 finding decided')).toBeVisible({ timeout: 15_000 });

    // The confirmed finding leaves the PENDING filter.
    await expect(
      page.getByRole('row', { name: new RegExp(`public\\.${TABLE}\\.customer_email`) }),
    ).toHaveCount(0);

    // Confirming applied the AF-447 tag…
    await page.getByRole('tab', { name: /Classification/ }).click();
    await expect(
      page.getByText(`public.${TABLE}.customer_email`).first(),
    ).toBeVisible({ timeout: 15_000 });

    // …and auto-derived a masking policy for the column.
    await page.getByRole('tab', { name: /Masking/ }).click();
    await expect(
      page.getByText(`public.${TABLE}.customer_email`).first(),
    ).toBeVisible({ timeout: 15_000 });

    // API cross-check: the finding is now CONFIRMED.
    const res = await request.get(
      `${apiBase()}/api/v1/datasources/${datasource.id}/discovery/findings?status=CONFIRMED&size=100`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect(res.ok()).toBe(true);
    const body = (await res.json()) as { content: DiscoveryFindingRow[] };
    expect(
      body.content.some((f) => f.table_name === TABLE && f.column_name === 'customer_email'),
    ).toBe(true);
  });
});
