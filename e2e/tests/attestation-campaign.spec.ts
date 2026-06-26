// AF-384 — access recertification / attestation campaigns end-to-end.
//
// NOTE (repo memory: e2e port 5173 collision): the main e2e stack binds the
// frontend on host port 5173, which collides with a locally running dev app.
// Free the port (or set E2E_BASE_URL / E2E_API_BASE) before running locally.
import { randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  createAttestationCampaignViaApi,
  createPostgresDatasource,
  deleteDatasource,
  exportAttestationEvidenceCsvViaApi,
  findUserByEmailViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  listAttestationItemsViaApi,
  listPermissionsViaApi,
  loginViaApi,
  openAttestationCampaignViaApi,
  purgeMailcrab,
  waitForInviteToken,
  type CreatedAttestationCampaign,
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

// Two-user setup + UI table interactions; give the suite a generous budget.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('attestation campaigns (AF-384)', () => {
  let adminToken = '';
  let datasource: CreatedDatasource | null = null;
  let campaign: CreatedAttestationCampaign | null = null;
  let keepEmail = '';
  let revokeEmail = '';
  let revokeUserId = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    await purgeMailcrab(request);

    datasource = await createPostgresDatasource(request, adminToken, {
      name: `Postgres E2E AF384 ${Date.now()}`,
    });

    // Two ANALYST subjects, each granted read access to the datasource.
    keepEmail = `af384-keep-${randomUUID()}@e2e.local`;
    revokeEmail = `af384-revoke-${randomUUID()}@e2e.local`;

    for (const [email, display] of [
      [keepEmail, 'AF-384 Keep'],
      [revokeEmail, 'AF-384 Revoke'],
    ]) {
      await inviteUserViaApi(request, adminToken, email, display, 'ANALYST');
      const token = await waitForInviteToken(request, email);
      await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, display);
    }

    const keepUser = await findUserByEmailViaApi(request, adminToken, keepEmail);
    const revokeUser = await findUserByEmailViaApi(request, adminToken, revokeEmail);
    revokeUserId = revokeUser.id;

    await grantPermissionViaApi(request, adminToken, datasource.id, keepUser.id, {
      canRead: true,
    });
    await grantPermissionViaApi(request, adminToken, datasource.id, revokeUser.id, {
      canRead: true,
    });

    // Create a DATASOURCE-scoped campaign and open it (snapshots the two grants).
    campaign = await createAttestationCampaignViaApi(request, adminToken, {
      name: `AF-384 Campaign ${Date.now()}`,
      scope: 'DATASOURCE',
      datasourceId: datasource.id,
      pendingDefault: 'KEEP',
    });
    await openAttestationCampaignViaApi(request, adminToken, campaign.id);
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminToken, datasource.id);
    }
  });

  test('admin reviewer certifies one item and revokes another via the UI', async ({
    browser,
    request,
  }) => {
    if (!campaign || !datasource) throw new Error('setup did not complete');

    const items = await listAttestationItemsViaApi(request, adminToken, campaign.id);
    expect(items.length).toBeGreaterThanOrEqual(2);
    const keepItem = items.find((i) => i.subject_user_email === keepEmail);
    const revokeItem = items.find((i) => i.subject_user_email === revokeEmail);
    expect(keepItem).toBeTruthy();
    expect(revokeItem).toBeTruthy();

    const ctx = await browser.newContext();
    try {
      const page = await ctx.newPage();
      // The bootstrap admin is also a valid attestation reviewer (hasAnyRole
      // REVIEWER/ADMIN), and is not the subject of either item, so the
      // self-subject guard does not apply.
      await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
      await page.goto('/reviews/attestations');

      // Both subjects render as rows.
      await expect(page.getByText(keepEmail, { exact: true })).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText(revokeEmail, { exact: true })).toBeVisible();

      // Certify the keep row. Scope to its table row so we hit the right button.
      const keepRow = page.getByRole('row', { name: new RegExp(keepEmail) });
      await keepRow.getByRole('button', { name: 'Certify' }).click();
      await expect(page.getByText('Access certified')).toBeVisible({ timeout: 10_000 });

      // Revoke the revoke row — opens the modal which requires a comment.
      const revokeRow = page.getByRole('row', { name: new RegExp(revokeEmail) });
      await revokeRow.getByRole('button', { name: 'Revoke' }).click();

      const modal = page.getByRole('dialog');
      await expect(modal.getByText('Revoke access')).toBeVisible({ timeout: 5_000 });
      const confirm = modal.getByRole('button', { name: /^Revoke$/ });
      await expect(confirm).toBeDisabled();
      await modal
        .getByPlaceholder(/Reason for revoking this access/)
        .fill('no longer needs prod access');
      await expect(confirm).toBeEnabled();
      await confirm.click();

      await expect(page.getByText('Access revoked')).toBeVisible({ timeout: 10_000 });
    } finally {
      await ctx.close();
    }

    // Backend assertion: the revoked subject's grant is gone; the certified
    // subject's grant remains. Use the request fixture (cross-origin safe).
    await expect
      .poll(
        async () => {
          const perms = await listPermissionsViaApi(request, adminToken, datasource!.id);
          return perms.some((p) => p.user_id === revokeUserId);
        },
        { timeout: 10_000 },
      )
      .toBe(false);

    const perms = await listPermissionsViaApi(request, adminToken, datasource.id);
    expect(perms.some((p) => p.user_email === keepEmail)).toBe(true);
  });

  test('evidence export returns a CSV containing both subject emails', async ({ request }) => {
    if (!campaign) throw new Error('setup did not complete');

    const { contentType, body } = await exportAttestationEvidenceCsvViaApi(
      request,
      adminToken,
      campaign.id,
    );
    expect(contentType).toContain('text/csv');
    expect(body).toContain(keepEmail);
    expect(body).toContain(revokeEmail);
  });
});
