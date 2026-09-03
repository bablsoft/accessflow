import { test, expect } from '@playwright/test';
import {
  acceptInvitationViaApi,
  createRoleViaApi,
  inviteUserViaApi,
  loginViaApi,
  waitForInviteToken,
} from '../helpers/datasources';
import { ADMIN_EMAIL, ADMIN_PASSWORD, login } from '../helpers/login';

// #772 — the unified review queue. `/reviews` is one page with a tab per kind of request the
// viewer may review (queries, API requests, deployments, rollbacks). Tabs are permission-gated —
// a reviewer without a permission gets no tab, not an empty one — and the pre-#772 queue URLs
// redirect into the matching tab. The per-queue decision flows themselves are covered by
// reviews-*.spec.ts, api-request-review.spec.ts and deployment-review.spec.ts.

const API_REVIEWER_PASSWORD = 'ApiReviewer!123';

test.describe('unified review queue (#772)', () => {
  test('the seeded admin sees one tab per queue and switching tabs syncs ?tab=', async ({ page }) => {
    await login(page);
    await page.goto('/reviews');

    await expect(page.getByRole('heading', { name: 'Review queue' })).toBeVisible();
    // A bare /reviews is not rewritten — the first visible tab is simply shown.
    await expect(page).toHaveURL(/\/reviews$/);

    const queries = page.getByRole('tab', { name: /^Queries · \d+$/ });
    const api = page.getByRole('tab', { name: /^API requests · \d+$/ });
    const deployments = page.getByRole('tab', { name: /^Deployments · \d+$/ });
    const rollbacks = page.getByRole('tab', { name: /^Rollbacks · \d+$/ });
    await expect(queries).toBeVisible();
    await expect(api).toBeVisible();
    await expect(deployments).toBeVisible();
    await expect(rollbacks).toBeVisible();
    await expect(page.getByRole('tab')).toHaveCount(4);
    await expect(queries).toHaveAttribute('aria-selected', 'true');
    // The push-approvals opt-in belongs to the Queries tab only.
    await expect(page.getByRole('button', { name: /push approvals/i })).toBeVisible();

    await deployments.click();
    await expect(page).toHaveURL(/\/reviews\?tab=deployments$/);
    await expect(deployments).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByRole('button', { name: /push approvals/i })).toHaveCount(0);

    await api.click();
    await expect(page).toHaveURL(/\/reviews\?tab=api$/);
    await expect(page.getByPlaceholder('Search by connector, path')).toBeVisible();

    await rollbacks.click();
    await expect(page).toHaveURL(/\/reviews\?tab=rollbacks$/);
    await expect(rollbacks).toHaveAttribute('aria-selected', 'true');
    // The Deployments body is unmounted when its tab is not active.
    await expect(page.getByPlaceholder('Search pipeline, environment or version')).toHaveCount(0);
  });

  test('legacy queue URLs redirect into the matching tab', async ({ page }) => {
    await login(page);

    await page.goto('/api-reviews');
    await expect(page).toHaveURL(/\/reviews\?tab=api$/);
    await expect(page.getByRole('tab', { name: /^API requests/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    await page.goto('/deployment-reviews');
    await expect(page).toHaveURL(/\/reviews\?tab=deployments$/);
    await expect(page.getByRole('tab', { name: /^Deployments/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    await page.goto('/deployment-reviews?tab=rollbacks');
    await expect(page).toHaveURL(/\/reviews\?tab=rollbacks$/);
    await expect(page.getByRole('tab', { name: /^Rollbacks/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    // An unknown tab falls back to the first one the viewer may see.
    await page.goto('/reviews?tab=bogus');
    await expect(page).toHaveURL(/\/reviews\?tab=queries$/);
  });

  test('a reviewer holding only API_REQUEST_REVIEW sees only the API tab', async ({
    page,
    request,
  }) => {
    const suffix = Date.now();
    const adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const role = await createRoleViaApi(
      request,
      adminToken,
      `API reviewer ${suffix}`,
      ['QUERY_SUBMIT_SELECT', 'API_REQUEST_REVIEW'],
      'Review hub permission scoping (#772)',
    );
    const email = `api-reviewer-${suffix}@accessflow.test`;
    await inviteUserViaApi(request, adminToken, email, 'API Reviewer', null, role.id);
    const inviteToken = await waitForInviteToken(request, email);
    await acceptInvitationViaApi(request, inviteToken, API_REVIEWER_PASSWORD, 'API Reviewer');

    await login(page, email, API_REVIEWER_PASSWORD);

    // The single Review queue nav entry is any-of the three review permissions.
    await expect(page.getByRole('link', { name: 'Review queue' })).toBeVisible();

    // A deployment deep link they may not see lands on the API tab instead — no empty tab.
    await page.goto('/reviews?tab=deployments');
    await expect(page).toHaveURL(/\/reviews\?tab=api$/);
    await expect(page.getByRole('tab')).toHaveCount(1);
    await expect(page.getByRole('tab', { name: /^API requests · \d+$/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    await expect(page.getByPlaceholder('Search by connector, path')).toBeVisible();
    await expect(page.getByRole('button', { name: /push approvals/i })).toHaveCount(0);
  });
});
