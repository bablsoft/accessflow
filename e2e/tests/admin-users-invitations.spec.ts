import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Unique suffix per run keeps invitee emails unique against the long-lived
// e2e Postgres (the stack reuses volumes between `stack:up` cycles unless
// teardown runs with `-v`). Same pattern as admin-users-crud.spec.ts.
const UNIQUE_SUFFIX = `af276-${Date.now()}`;
const INVITEE_EMAIL = `e2e-invitee-${UNIQUE_SUFFIX}@e2e.local`;
const INVITEE_DISPLAY = `E2E Invitee ${UNIQUE_SUFFIX}`;
const ACCEPTED_INVITEE_EMAIL = `e2e-accepted-${UNIQUE_SUFFIX}@e2e.local`;
const ACCEPTED_INVITEE_DISPLAY = `E2E Accepted ${UNIQUE_SUFFIX}`;
const INVITEE_PASSWORD = 'Invitee-Pwd!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/admin/users that UsersPage issues on mount so the
// Table renders real rows (not the loading skeleton) before we drive the UI.
// Same gate pattern as admin-users-crud.spec.ts.
async function waitForUsersListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/users(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

// Open the pending-invitations row "⋯" actions menu. The button has
// aria-label="Edit" (admin.users i18n key `common.edit`, identical to the
// /admin/users user-row pattern documented in admin-users-crud.spec.ts).
async function openRowActionsMenu(row: ReturnType<Page['getByRole']>): Promise<void> {
  await row.getByRole('button', { name: 'Edit' }).click();
}

async function deleteUserViaApi(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/admin/users/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(
      `Admin user cleanup (${id}) returned ${res.status()}: ${await res.text()}`,
    );
  }
}

async function lookupUserIdByEmail(
  request: APIRequestContext,
  accessToken: string,
  email: string,
): Promise<string | null> {
  const res = await request.get(
    `${apiBase()}/api/v1/admin/users?page=0&size=200&sort=email,asc`,
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  if (!res.ok()) {
    return null;
  }
  const body = (await res.json()) as {
    content?: Array<{ id: string; email: string }>;
  };
  const match = (body.content ?? []).find(
    (u) => u.email.toLowerCase() === email.toLowerCase(),
  );
  return match?.id ?? null;
}

// AF-276 covers the /admin/users invitation lifecycle:
//   1. Invite via email → row appears with status PENDING.
//   2. Resend → toast "Invitation resent", row stays PENDING.
//   3. Revoke → row flips to status REVOKED, action menu items disabled
//      (DefaultUserInvitationService.revoke flips status; list() keeps the row).
//   4. Malformed email → inline validation, modal stays open, no POST issued.
//   5. Accepted invitation → Resend / Revoke menu items disabled in the UI AND
//      backend DELETE responds 422 INVITATION_ALREADY_ACCEPTED.
//
// `describe.serial` so tests 1→3 walk the same invitation through its lifecycle:
// the row created in test 1 is the row resent in test 2 is the row revoked in
// test 3. Playwright is already workers=1 in this project, so this is mostly
// belt-and-braces — same pattern as admin-users-crud.spec.ts.
test.describe.serial('/admin/users — invitation lifecycle', () => {
  let adminAccessToken = '';
  let invitationId: string | null = null;
  let acceptedInvitationId: string | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.beforeEach(async ({ request }) => {
    // Each test that scrapes a token starts from an empty mailbox. Tests that
    // don't touch Mailcrab pay a single HTTP roundtrip and don't care.
    await purgeMailcrab(request);
  });

  test.afterAll(async ({ request }) => {
    // Best-effort cleanup of the user the accept flow provisioned in test 5.
    // Look it up by email since acceptInvitationViaApi doesn't return the id.
    const acceptedUserId = await lookupUserIdByEmail(
      request,
      adminAccessToken,
      ACCEPTED_INVITEE_EMAIL,
    );
    if (acceptedUserId) {
      await deleteUserViaApi(request, adminAccessToken, acceptedUserId);
    }
  });

  test('1) invite via email → row appears with status PENDING', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();

    await page
      .getByRole('button', { name: 'Invite via email', exact: true })
      .click();

    const dialog = page.getByRole('dialog', { name: 'Invite a teammate' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    await dialog.getByLabel('Email').fill(INVITEE_EMAIL);
    await dialog.getByLabel('Display name').fill(INVITEE_DISPLAY);
    // Role defaults to ANALYST — leave the Select untouched.

    const inviteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/users\/invitations$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Send invitation' }).click();
    const inviteResponse = await inviteResponsePromise;
    expect(inviteResponse.status()).toBe(201);
    const body = (await inviteResponse.json()) as {
      id: string;
      email: string;
      role: string;
      status: string;
    };
    invitationId = body.id;
    expect(body.email).toBe(INVITEE_EMAIL);
    expect(body.role).toBe('ANALYST');
    expect(body.status).toBe('PENDING');

    // Success toast + modal closes + row renders in pending-invitations section
    // with status "Pending".
    await expect(page.getByText('Invitation email sent', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(dialog).toBeHidden({ timeout: 10_000 });

    const inviteeRow = page
      .getByRole('row')
      .filter({ hasText: INVITEE_EMAIL });
    await expect(inviteeRow).toBeVisible({ timeout: 10_000 });
    await expect(inviteeRow.getByText('Pending', { exact: true })).toBeVisible();
  });

  test('2) resend → toast appears, row stays PENDING', async ({ page }) => {
    test.skip(!invitationId, 'Test 1 must succeed to seed the invitation row');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);

    const inviteeRow = page
      .getByRole('row')
      .filter({ hasText: INVITEE_EMAIL });
    await expect(inviteeRow).toBeVisible({ timeout: 10_000 });

    await openRowActionsMenu(inviteeRow);

    const resendResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new RegExp(
          `/api/v1/admin/users/invitations/${invitationId}/resend$`,
        ).test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('menuitem', { name: 'Resend' }).click();
    const resendResponse = await resendResponsePromise;
    expect(resendResponse.status()).toBe(200);
    const body = (await resendResponse.json()) as { id: string; status: string };
    expect(body.id).toBe(invitationId);
    expect(body.status).toBe('PENDING');

    await expect(page.getByText('Invitation resent', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(inviteeRow).toBeVisible();
    await expect(inviteeRow.getByText('Pending', { exact: true })).toBeVisible();
  });

  test('3) revoke → row flips to REVOKED with disabled row actions', async ({ page }) => {
    test.skip(!invitationId, 'Test 1 must succeed to seed the invitation row');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);

    const inviteeRow = page
      .getByRole('row')
      .filter({ hasText: INVITEE_EMAIL });
    await expect(inviteeRow).toBeVisible({ timeout: 10_000 });

    await openRowActionsMenu(inviteeRow);
    await page.getByRole('menuitem', { name: 'Revoke' }).click();

    // modal.confirm renders a dialog whose title is the confirm title.
    // Body interpolates the invitee's email — filter by hasText for that body
    // line so we hit this specific confirm and not any other dialog.
    const confirmDialog = page
      .getByRole('dialog')
      .filter({ hasText: 'Revoke this invitation?' });
    await expect(confirmDialog).toBeVisible({ timeout: 10_000 });
    await expect(
      confirmDialog.getByText(
        `The invitation link for ${INVITEE_EMAIL} will stop working.`,
      ),
    ).toBeVisible();

    const revokeResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(
          `/api/v1/admin/users/invitations/${invitationId}$`,
        ).test(r.url()),
      { timeout: 15_000 },
    );
    await confirmDialog.getByRole('button', { name: 'Revoke' }).click();
    const revokeResponse = await revokeResponsePromise;
    expect(revokeResponse.status()).toBe(204);

    await expect(page.getByText('Invitation revoked', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    // DefaultUserInvitationService.revoke flips status to REVOKED but keeps the
    // row; the frontend list() returns every invitation regardless of status,
    // so the row stays visible with the new status and disabled row actions.
    await expect(inviteeRow.getByText('Revoked', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await openRowActionsMenu(inviteeRow);
    await expect(
      page.getByRole('menuitem', { name: 'Resend' }),
    ).toHaveAttribute('aria-disabled', 'true');
    await expect(
      page.getByRole('menuitem', { name: 'Revoke' }),
    ).toHaveAttribute('aria-disabled', 'true');
  });

  test('4) malformed email blocks submission with inline validation', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);

    await page
      .getByRole('button', { name: 'Invite via email', exact: true })
      .click();

    const dialog = page.getByRole('dialog', { name: 'Invite a teammate' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    await dialog.getByLabel('Email').fill('not-an-email');
    await dialog.getByLabel('Display name').fill(`Bad ${UNIQUE_SUFFIX}`);

    // Spy on outgoing POSTs to confirm client-side validation actually blocks
    // the request (same pattern as auth-invitation.spec.ts test #2).
    let inviteRequestCount = 0;
    const handler = (req: { url(): string; method(): string }): void => {
      if (
        req.method() === 'POST' &&
        /\/api\/v1\/admin\/users\/invitations$/.test(req.url())
      ) {
        inviteRequestCount += 1;
      }
    };
    page.on('request', handler);
    try {
      await dialog.getByRole('button', { name: 'Send invitation' }).click();
      await expect(
        dialog.getByText('Enter a valid email address.'),
      ).toBeVisible({ timeout: 5_000 });
    } finally {
      page.off('request', handler);
    }

    expect(inviteRequestCount).toBe(0);
    // Modal stays open so the user can fix the email and retry.
    await expect(dialog).toBeVisible();
  });

  test('5) accepted invitation → UI disables Resend/Revoke and backend rejects revoke with 422', async ({
    page,
    request,
  }) => {
    // Arrange: create a fresh invitation via API, scrape the token from Mailcrab,
    // and accept it via the public endpoint. After this the invitation row in
    // /admin/users should show status "Accepted" with disabled row actions.
    const invited = await inviteUserViaApi(
      request,
      adminAccessToken,
      ACCEPTED_INVITEE_EMAIL,
      ACCEPTED_INVITEE_DISPLAY,
      'ANALYST',
    );
    acceptedInvitationId = invited.id;

    const token = await waitForInviteToken(request, ACCEPTED_INVITEE_EMAIL);
    await acceptInvitationViaApi(
      request,
      token,
      INVITEE_PASSWORD,
      ACCEPTED_INVITEE_DISPLAY,
    );

    // UI assertion: open the row's "⋯" menu and confirm both actions are disabled.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/users');
    await waitForUsersListReady(page);

    // Accepting the invitation also provisions a row in the users table that
    // shares the email — scope row lookups to the invitations section by
    // walking up from its heading to the wrapping div (UsersPage.tsx:668).
    const invitationsSection = page
      .getByText('Pending invitations', { exact: true })
      .locator('..');
    const acceptedRow = invitationsSection
      .getByRole('row')
      .filter({ hasText: ACCEPTED_INVITEE_EMAIL });
    await expect(acceptedRow).toBeVisible({ timeout: 10_000 });
    await expect(acceptedRow.getByText('Accepted', { exact: true })).toBeVisible();

    await openRowActionsMenu(acceptedRow);
    // AntD renders disabled menu items with aria-disabled="true" — assert both.
    await expect(
      page.getByRole('menuitem', { name: 'Resend' }),
    ).toHaveAttribute('aria-disabled', 'true');
    await expect(
      page.getByRole('menuitem', { name: 'Revoke' }),
    ).toHaveAttribute('aria-disabled', 'true');
    // Close the menu so it doesn't bleed into the next assertion block.
    await page.keyboard.press('Escape');

    // Backend-contract assertion: DELETE directly to confirm 422 + the documented
    // error code. The UI never sends this request because the menu item is
    // disabled, but the backend is still the source of truth.
    const revokeResponse = await request.delete(
      `${apiBase()}/api/v1/admin/users/invitations/${acceptedInvitationId}`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    expect(revokeResponse.status()).toBe(422);
    const body = (await revokeResponse.json()) as { error?: string };
    expect(body.error).toBe('INVITATION_ALREADY_ACCEPTED');
  });
});
