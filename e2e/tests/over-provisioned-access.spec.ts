// #625 — least-privilege intelligence: the over-provisioned access report.
//
// NOTE (repo memory: e2e port 5173 collision): the main e2e stack binds the
// frontend on host port 5173, which collides with a locally running dev app.
// Free the port (or set E2E_BASE_URL / E2E_API_BASE) before running locally.
//
// Covered here:
//   1. The report route is reachable by an admin and renders through the real API.
//   2. The filter controls drive the request (resource kind + recommendation reach the query).
//   3. The CSV export returns text/csv with a Content-Disposition filename and the
//      X-AccessFlow-Export-Truncated header, and carries the documented header row.
//   4. An analyst is redirected away — the route is gated on ACCESS_USAGE_REPORT_VIEW.
//
// Deliberately NOT covered here: the aggregation job actually producing rows.
// GrantUsageAggregationJob holds its ShedLock for `lockAtLeastFor = PT2M`, so its
// first post-startup tick cannot be induced within a spec's budget, and the job is
// not manually triggerable by design. The fold, the backfill, the cursor rules and
// the recommendation ladder are covered by
// backend/.../access/internal/DefaultGrantUsageAggregationServiceTest and
// GrantUsageRecommenderTest; the endpoint contract by
// OverProvisionedAccessControllerIntegrationTest.
import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  exportOverProvisionedCsvViaApi,
  inviteUserViaApi,
  listOverProvisionedGrantsViaApi,
  loginViaApi,
  waitForInviteToken,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';
const ROUTE = '/admin/over-provisioned-access';
const LIST_ENDPOINT = /\/api\/v1\/admin\/over-provisioned-access(\?|$)/;

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('over-provisioned access report (#625)', () => {
  let adminToken = '';
  let analystEmail = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    analystEmail = `af625-analyst-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminToken, analystEmail, '#625 Analyst', 'ANALYST');
    const token = await waitForInviteToken(request, analystEmail);
    await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, '#625 Analyst');
  });

  test('admin opens the report and the filters drive the request', async ({ browser }) => {
    const ctx = await browser.newContext();
    try {
      const page = await ctx.newPage();
      await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

      // Gate on the network: the table (or empty state) only renders once the query resolves.
      const [initial] = await Promise.all([
        page.waitForResponse((r) => LIST_ENDPOINT.test(r.url()) && r.ok(), { timeout: 15_000 }),
        page.goto(ROUTE),
      ]);
      expect(initial.ok()).toBe(true);

      await expect(page.getByRole('heading', { name: 'Over-provisioned access' })).toBeVisible();
      await expect(page.getByTestId('export-csv-button')).toBeVisible();

      // Filtering by resource kind must reach the backend as resource_kind, not stay client-side.
      const [filtered] = await Promise.all([
        page.waitForResponse(
          (r) => LIST_ENDPOINT.test(r.url()) && r.url().includes('resource_kind=DATASOURCE'),
          { timeout: 15_000 },
        ),
        (async () => {
          await page.getByLabel('Resource kind').click();
          await page.getByTitle('Datasource', { exact: true }).click();
        })(),
      ]);
      expect(filtered.ok()).toBe(true);

      // Same for the multi-select recommendation filter.
      const [byRecommendation] = await Promise.all([
        page.waitForResponse(
          (r) => LIST_ENDPOINT.test(r.url()) && r.url().includes('recommendation=NEVER_USED'),
          { timeout: 15_000 },
        ),
        (async () => {
          await page.getByLabel('Recommendation').click();
          await page.getByTitle('Never used', { exact: true }).click();
        })(),
      ]);
      expect(byRecommendation.ok()).toBe(true);
    } finally {
      await ctx.close();
    }
  });

  test('the report endpoint returns a well-formed page envelope', async ({ request }) => {
    const page = await listOverProvisionedGrantsViaApi(request, adminToken);

    expect(Array.isArray(page.content)).toBe(true);
    expect(typeof page.total_elements).toBe('number');

    // Any row present must carry the nullable fields explicitly — the response overrides the
    // global non_null Jackson default precisely so "unrestricted" and "never used" are
    // distinguishable from an omitted key.
    for (const row of page.content) {
      expect(row).toHaveProperty('granted_target_count');
      expect(row).toHaveProperty('days_since_last_use');
      expect(row.recommendation).toMatch(
        /^(INSUFFICIENT_DATA|NEVER_USED|STALE|OVER_SCOPED|ACTIVE)$/,
      );
    }
  });

  test('CSV export returns a download with the truncation header and the documented columns', async ({
    request,
  }) => {
    const { contentType, disposition, truncated, body } = await exportOverProvisionedCsvViaApi(
      request,
      adminToken,
    );

    expect(contentType).toContain('text/csv');
    expect(disposition).toMatch(
      /attachment;\s*filename="over-provisioned-access-\d{8}T\d{6}Z\.csv"/,
    );
    expect(truncated).toBe('false');
    expect(body.split('\r\n')[0]).toBe(
      'summary_id,resource_kind,resource_name,resource_id,user_email,user_display_name,' +
        'granted_at,expires_at,granted_target_count,used_target_count,unused_target_count,' +
        'used_targets,usage_count,first_used_at,last_used_at,observed_since,' +
        'days_since_last_use,usage_per_week,recommendation',
    );
  });

  test('an analyst is redirected away from the report', async ({ browser }) => {
    const ctx = await browser.newContext();
    try {
      const page = await ctx.newPage();
      await loginViaUi(page, analystEmail, ANALYST_PASSWORD);
      await page.goto(ROUTE);

      // AuthGuard sends an unauthorized user to their home path rather than showing a 403.
      await expect(page).not.toHaveURL(new RegExp(`${ROUTE}$`), { timeout: 15_000 });
    } finally {
      await ctx.close();
    }
  });
});
