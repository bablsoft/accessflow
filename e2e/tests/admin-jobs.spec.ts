import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  inviteUserViaApi,
  loginViaApi,
  waitForInviteToken,
} from '../helpers/datasources';
import { login } from '../helpers/login';

// #923: the read-only scheduled-job monitor. The bootstrap admin is provisioned as a platform
// admin; an invited org ADMIN is not, and must neither see the nav entry nor reach the route.
const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ORG_ADMIN_PASSWORD = 'OrgAdminPass!123';

test.describe.serial('Scheduled jobs monitor (#923)', () => {
  // beforeAll invites and activates a second user through Mailcrab — give it room on a slow CI.
  test.describe.configure({ timeout: 90_000 });
  const SUFFIX = `af923-${Date.now()}`;
  const ORG_ADMIN_EMAIL = `org-admin-${SUFFIX}@e2e.local`;
  const ORG_ADMIN_DISPLAY = `AF923 Org Admin ${SUFFIX}`;

  test.beforeAll(async ({ request }) => {
    const adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    await inviteUserViaApi(request, adminToken, ORG_ADMIN_EMAIL, ORG_ADMIN_DISPLAY, 'ADMIN');
    const token = await waitForInviteToken(request, ORG_ADMIN_EMAIL);
    await acceptInvitationViaApi(request, token, ORG_ADMIN_PASSWORD, ORG_ADMIN_DISPLAY);
  });

  test('platform admin sees the job registry and opens a job history', async ({ page }) => {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    // The Platform group is always expanded; the entry is visible only to platform admins.
    await page.getByRole('link', { name: /Scheduled jobs/ }).click();
    await page.waitForURL('**/admin/jobs');
    await expect(page.getByRole('heading', { name: 'Scheduled jobs' })).toBeVisible();

    // Scheduling runs in the e2e stack, so the registry lists the real jobs.
    const row = page.locator('tr', { hasText: 'QueryTimeoutJob' });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row.getByText('workflow')).toBeVisible();

    await row.getByRole('button', { name: 'QueryTimeoutJob' }).click();
    const drawer = page.getByRole('dialog').filter({ hasText: 'QueryTimeoutJob — execution history' });
    await expect(drawer).toBeVisible();
    // Either recorded runs or the explicit empty state — never a load error.
    await expect(
      drawer.getByRole('row').nth(1).or(drawer.getByText('No recorded executions')),
    ).toBeVisible({ timeout: 15_000 });
    await expect(drawer.getByText('Could not load the execution history')).toHaveCount(0);
  });

  test('org admin who is not a platform admin has no nav entry and is redirected', async ({ page }) => {
    await login(page, ORG_ADMIN_EMAIL, ORG_ADMIN_PASSWORD);

    await expect(page.getByRole('link', { name: /Scheduled jobs/ })).toHaveCount(0);

    await page.goto('/admin/jobs');
    await page.waitForURL('**/dashboard', { timeout: 10_000 });
    await expect(page.getByRole('heading', { name: 'Scheduled jobs' })).toHaveCount(0);
  });
});
