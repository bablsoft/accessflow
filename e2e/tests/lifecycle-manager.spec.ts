// AF-499 / AF-519 — Data Lifecycle Manager end-to-end (retention rules + review-plan-based erasure).
//
// NOTE (repo memory: e2e port 5173 collision): the main e2e stack binds the
// frontend on host port 5173, which collides with a locally running dev app.
// Free the port (or set E2E_BASE_URL / E2E_API_BASE) before running locally.
//
// Pattern (mirrors attestation-campaign.spec.ts): seed via API, drive the
// governance decisions (dry-run preview, erasure approve) through the UI.
import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  createPostgresDatasource,
  createRetentionPolicyViaApi,
  deleteDatasource,
  findUserByEmailViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  loginViaApi,
  submitErasureViaApi,
  waitForInviteToken,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

test.describe.configure({ timeout: 90_000 });

test.describe.serial('data lifecycle manager (AF-499)', () => {
  let adminToken = '';
  let datasource: CreatedDatasource | null = null;
  let policyName = '';
  let submitterEmail = '';
  let submitterToken = '';
  let subjectEmail = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E AF499 ${Date.now()}`,
    });

    policyName = `AF499 Retention ${randomUUID().slice(0, 8)}`;
    await createRetentionPolicyViaApi(request, adminToken, datasource.id, {
      name: policyName,
      targetTable: 'orders',
      action: 'SOFT_DELETE',
    });

    // A submitter who can see the datasource (so it appears in their list).
    submitterEmail = `af499-sub-${randomUUID()}@e2e.local`;
    await inviteUserViaApi(request, adminToken, submitterEmail, 'AF-499 Submitter', 'ANALYST');
    const token = await waitForInviteToken(request, submitterEmail);
    await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, 'AF-499 Submitter');
    const submitter = await findUserByEmailViaApi(request, adminToken, submitterEmail);
    await grantPermissionViaApi(request, adminToken, datasource.id, submitter.id, {
      canRead: true,
    });
    submitterToken = await loginViaApi(request, submitterEmail, ANALYST_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  test('admin sees the retention policy and runs a dry-run preview', async ({ browser }) => {
    const ctx = await browser.newContext();
    try {
      const page = await ctx.newPage();
      await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
      await page.goto('/admin/lifecycle/policies');

      const row = page.getByRole('row', { name: new RegExp(policyName) });
      await expect(row).toBeVisible({ timeout: 15_000 });

      await row.getByRole('button', { name: 'Preview' }).click();
      const dialog = page.getByRole('dialog');
      await expect(dialog.getByText('Dry-run preview')).toBeVisible({ timeout: 10_000 });
    } finally {
      await ctx.close();
    }
  });

  test('submitter files an erasure request and an admin approves it', async ({ request, browser }) => {
    subjectEmail = `subject-${randomUUID()}@example.com`;
    await submitErasureViaApi(request, submitterToken, datasource!.id, subjectEmail);

    // Submitter sees their request in the self-service list, and the reworked page (AF-519) offers
    // the shared erasure-configuration form (target table + conditions + raw WHERE).
    const subCtx = await browser.newContext();
    try {
      const page = await subCtx.newPage();
      await loginViaUi(page, submitterEmail, ANALYST_PASSWORD);
      await page.goto('/lifecycle/erasure');
      await expect(page.getByText(subjectEmail, { exact: true })).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText('Conditions', { exact: true }).first()).toBeVisible();
    } finally {
      await subCtx.close();
    }

    // Admin approves it from the review-plan-based review queue (AF-519: /lifecycle/erasure-reviews;
    // an admin is the backstop reviewer). Async scope detection → PENDING_REVIEW first.
    const adminCtx = await browser.newContext();
    try {
      const page = await adminCtx.newPage();
      await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

      const row = page.getByRole('row', { name: new RegExp(subjectEmail) });
      await expect(async () => {
        await page.goto('/lifecycle/erasure-reviews');
        await expect(row).toBeVisible({ timeout: 3_000 });
      }).toPass({ timeout: 30_000 });

      await row.getByRole('button', { name: 'Approve' }).click();
      await expect(page.getByText('Erasure approved')).toBeVisible({ timeout: 10_000 });
    } finally {
      await adminCtx.close();
    }
  });
});
