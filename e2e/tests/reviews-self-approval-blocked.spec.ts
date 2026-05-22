import { expect, test, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  loginViaApi,
  submitQueryViaApi,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

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

test.describe.serial('reviews self-approval blocked (AF-270)', () => {
  let adminAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // One-stage ADMIN plan: the bootstrap admin is the only eligible reviewer
    // *and* the submitter — this is the exact "self" scenario we want to lock
    // behind a regression.
    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF270 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF270 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  test('submitter sees no Approve affordance and direct API call returns 403', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // Submit via API (submitter = bootstrap admin) and wait for the
    // PENDING_AI → PENDING_REVIEW transition (AI is disabled on the
    // datasource, so the skip listener flips status asynchronously).
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1',
      'AF-270 self-approval blocked',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );

    const ctx = await browser.newContext();
    try {
      const page = await ctx.newPage();
      await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

      // UI gate #1: /reviews must filter the submitter's own query out of the
      // queue (ReviewQueuePage.tsx:45 → reviewable.filter(submitted_by !==
      // user)). Anchor on the full UUID so leftover PENDING_REVIEW rows from
      // other specs in the same run don't false-positive.
      await page.goto('/reviews');
      await page.waitForLoadState('networkidle');
      await expect(page.getByText(submitted.id, { exact: true })).toHaveCount(
        0,
        { timeout: 15_000 },
      );

      // UI gate #2: /queries/:id must not render the Approve button. The
      // entire decision panel is wrapped in `{canDecide && (...)}` so the
      // button is fully unmounted, not just disabled.
      await page.goto(`/queries/${submitted.id}`);
      await expect(
        page.getByRole('heading', { level: 1 }).getByText('Pending review'),
      ).toBeVisible({ timeout: 15_000 });
      await expect(
        page.getByRole('button', { name: 'Approve' }),
      ).toHaveCount(0);

      // Server-side enforcement: bypass the UI and POST directly. axios
      // throws on 4xx, so capture err.response.{status,data} and assert the
      // backend returns 403 FORBIDDEN (DefaultReviewService.java:130-131 →
      // GlobalExceptionHandler.java:165-168).
      const result = await page.evaluate(async (queryId) => {
        const client = (
          window as unknown as {
            __apiClient?: {
              post: (
                url: string,
                data: unknown,
              ) => Promise<{ status: number }>;
            };
          }
        ).__apiClient;
        if (!client) throw new Error('window.__apiClient is not exposed');
        try {
          const res = await client.post(
            `/api/v1/reviews/${queryId}/approve`,
            {},
          );
          return { ok: true as const, status: res.status };
        } catch (err) {
          const e = err as {
            response?: { status?: number; data?: { error?: string } };
          };
          return {
            ok: false as const,
            status: e.response?.status ?? 0,
            error: e.response?.data?.error ?? null,
          };
        }
      }, submitted.id);

      expect(result.ok).toBe(false);
      expect(result.status).toBe(403);
      if (!result.ok) {
        expect(result.error).toBe('FORBIDDEN');
      }
    } finally {
      await ctx.close();
    }
  });
});
