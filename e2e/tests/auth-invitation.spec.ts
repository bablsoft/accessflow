import { randomUUID } from 'node:crypto';
import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Mailcrab HTTP/JSON API is published to the host by docker-compose.e2e.yml so
// Playwright can poll it directly. SMTP (1025) stays internal to the compose
// network and is only reached by the backend.
const MAILCRAB_BASE = 'http://localhost:1080';

// Matches the URL DefaultUserInvitationService.buildAcceptUrl renders into the
// invitation email body: `${publicBaseUrl}/invite/{token}`.
const INVITE_URL_REGEX = /\/invite\/([A-Za-z0-9_-]+)/;

const INVITEE_PASSWORD = 'Invitee-Pwd!123';

interface MailcrabSummary {
  id: string;
  time?: number;
  to?: Array<{ email?: string } | string>;
  subject?: string;
}

interface MailcrabMessage extends MailcrabSummary {
  text?: string;
  html?: string;
}

// Purge any captured emails so the next `waitForInviteToken` probe is
// unambiguous about which message belongs to the test that just ran.
async function purgeMailcrab(request: APIRequestContext): Promise<void> {
  const res = await request.post(`${MAILCRAB_BASE}/api/delete-all`);
  if (!res.ok() && res.status() !== 404) {
    throw new Error(`Mailcrab purge failed: ${res.status()} ${await res.text()}`);
  }
}

function recipientMatches(summary: MailcrabSummary, recipient: string): boolean {
  if (!summary.to) return false;
  return summary.to.some((entry) => {
    if (typeof entry === 'string') {
      return entry.toLowerCase().includes(recipient.toLowerCase());
    }
    return entry.email?.toLowerCase() === recipient.toLowerCase();
  });
}

// Poll Mailcrab until a message addressed to `recipient` shows up, then extract
// the one-time token from the invite URL embedded in the body.
async function waitForInviteToken(
  request: APIRequestContext,
  recipient: string,
  timeoutMs = 15_000,
): Promise<string> {
  const deadline = Date.now() + timeoutMs;
  let lastError = '';
  while (Date.now() < deadline) {
    const listRes = await request.get(`${MAILCRAB_BASE}/api/messages`);
    if (listRes.ok()) {
      const summaries = (await listRes.json()) as MailcrabSummary[];
      const match = [...summaries]
        .reverse()
        .find((m) => recipientMatches(m, recipient));
      if (match) {
        const detailRes = await request.get(`${MAILCRAB_BASE}/api/message/${match.id}`);
        if (detailRes.ok()) {
          const detail = (await detailRes.json()) as MailcrabMessage;
          const body = `${detail.text ?? ''}\n${detail.html ?? ''}`;
          const m = body.match(INVITE_URL_REGEX);
          if (m) {
            return m[1];
          }
          lastError = `Email found for ${recipient} but no invite token URL in body`;
        } else {
          lastError = `Mailcrab GET /api/message/${match.id} returned ${detailRes.status()}`;
        }
      } else {
        lastError = `No Mailcrab message addressed to ${recipient} yet (${summaries.length} total)`;
      }
    } else {
      lastError = `Mailcrab GET /api/messages returned ${listRes.status()}`;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new Error(`Timed out waiting for invitation email: ${lastError}`);
}

// Drive the seeded admin through the login form. Same shape as the helper in
// datasource-create-wizard.spec.ts — kept inline so each spec is self-contained.
async function loginAsAdmin(page: Page): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(ADMIN_EMAIL);
  await page.locator('#login-password').fill(ADMIN_PASSWORD);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Open `/admin/users`, click the primary "Invite via email" Dropdown.Button, and
// fill the modal. Submits as ANALYST (the modal default — also the lowest-priv
// non-admin role) and waits for the success toast.
async function sendInvitation(page: Page, invitee: string): Promise<void> {
  await page.goto('/admin/users');
  await page.getByRole('button', { name: 'Invite via email', exact: true }).click();
  // The modal is rendered into an AntD portal; scope queries to the dialog.
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByText('Invite a teammate')).toBeVisible({ timeout: 10_000 });
  await dialog.getByLabel('Email').fill(invitee);
  // Role is ANALYST by default — leave the Select untouched.
  await dialog.getByRole('button', { name: 'Send invitation' }).click();
  await expect(page.getByText('Invitation email sent')).toBeVisible({ timeout: 10_000 });
}

// Token captured by the happy-path test (#3) and replayed by the next test (#4)
// to assert the already-accepted error state. Playwright runs the suite with
// workers=1 and tests within a describe execute in order, so sharing a let
// across tests is safe here.
let acceptedInviteToken: string | null = null;

test.describe('invitation acceptance flow', () => {
  test.beforeEach(async ({ request }) => {
    await purgeMailcrab(request);
  });

  // ── 1. Invalid token — preview endpoint returns 404, page renders alert + no form ──
  test('invalid token shows the error alert and no form', async ({ page }) => {
    await page.goto('/invite/this-token-does-not-exist');
    // The AcceptInvitePage routes invitation errors through `setupErrorMessage`,
    // which has no INVITATION_* mapping today — the alert ends up rendering the
    // ProblemDetail `title` ("Not Found"). Assert on the alert role + missing
    // form so we stay green if/when a follow-up adds friendly i18n copy.
    await expect(page.getByRole('alert')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByLabel('Password', { exact: true })).toHaveCount(0);
    await expect(
      page.getByLabel('Confirm password', { exact: true }),
    ).toHaveCount(0);
  });

  // ── 2. Mismatched confirm — inline validation fires; no accept request leaves ─────
  test('mismatched confirm blocks submission and surfaces inline error', async ({
    page,
    request,
  }) => {
    const invitee = `invitee-${randomUUID()}@e2e.local`;

    await loginAsAdmin(page);
    await sendInvitation(page, invitee);

    const token = await waitForInviteToken(request, invitee);
    await page.goto(`/invite/${token}`);
    await expect(
      page.getByText('You have been invited to E2E Test Org as ANALYST'),
    ).toBeVisible({ timeout: 10_000 });

    await page.getByLabel('Password', { exact: true }).fill('Pwd-One!123');
    await page.getByLabel('Confirm password', { exact: true }).fill('Pwd-Two!456');

    let acceptRequestCount = 0;
    const handler = (req: { url(): string; method(): string }): void => {
      if (
        req.method() === 'POST' &&
        /\/api\/v1\/auth\/invitations\/[^/]+\/accept/.test(req.url())
      ) {
        acceptRequestCount += 1;
      }
    };
    page.on('request', handler);
    try {
      await page.locator('button[type="submit"]').click();
      await expect(page.getByText('Passwords do not match.')).toBeVisible();
    } finally {
      page.off('request', handler);
    }

    expect(acceptRequestCount).toBe(0);
    expect(new URL(page.url()).pathname).toBe(`/invite/${token}`);
  });

  // ── 3. Happy path — admin invites in one context, invitee accepts + logs in ───────
  test('happy path: admin invites → invitee accepts → invitee logs in', async ({
    browser,
    request,
  }) => {
    const invitee = `invitee-${randomUUID()}@e2e.local`;

    const adminContext = await browser.newContext();
    const inviteeContext = await browser.newContext();
    try {
      // Admin context: log in and dispatch the invitation.
      const adminPage = await adminContext.newPage();
      await loginAsAdmin(adminPage);
      await sendInvitation(adminPage, invitee);

      // Pending-invitations table refreshes via TanStack Query invalidation; the
      // new row should land within a short timeout.
      const inviteeRow = adminPage.getByRole('row').filter({ hasText: invitee });
      await expect(inviteeRow).toBeVisible({ timeout: 10_000 });
      await expect(inviteeRow.getByText('Pending')).toBeVisible();

      // Scrape the one-time token out of the captured email.
      const token = await waitForInviteToken(request, invitee);

      // Invitee context: visit the accept page and assert the preview rendered.
      const inviteePage = await inviteeContext.newPage();
      await inviteePage.goto(`/invite/${token}`);
      await expect(
        inviteePage.getByText('You have been invited to E2E Test Org as ANALYST'),
      ).toBeVisible({ timeout: 10_000 });
      await expect(inviteePage.getByText(invitee)).toBeVisible();

      // Set a matching password and accept; the page should redirect to /login.
      await inviteePage
        .getByLabel('Password', { exact: true })
        .fill(INVITEE_PASSWORD);
      await inviteePage
        .getByLabel('Confirm password', { exact: true })
        .fill(INVITEE_PASSWORD);
      await inviteePage.locator('button[type="submit"]').click();
      await inviteePage.waitForURL('**/login', { timeout: 10_000 });

      // Log in as the brand-new user → land on /editor. This proves the invite
      // actually provisioned a working LOCAL user with the chosen password.
      await inviteePage.locator('#login-email').fill(invitee);
      await inviteePage.locator('#login-password').fill(INVITEE_PASSWORD);
      await inviteePage.locator('button[type="submit"]').click();
      await inviteePage.waitForURL('**/dashboard', { timeout: 15_000 });

      acceptedInviteToken = token;
    } finally {
      await adminContext.close();
      await inviteeContext.close();
    }
  });

  // ── 4. Replay an accepted token — preview endpoint returns 422, page errors out ───
  test('replaying an accepted token surfaces the error alert', async ({ page }) => {
    // Depends on test #3 having captured the token. Playwright runs serially
    // with workers=1, so by the time this test runs the let is populated.
    expect(
      acceptedInviteToken,
      'expected acceptedInviteToken to be set by the happy-path test',
    ).not.toBeNull();

    await page.goto(`/invite/${acceptedInviteToken}`);
    // Backend returns 422 INVITATION_ALREADY_ACCEPTED; setupErrorMessage maps
    // it to the ProblemDetail title ("Unprocessable Entity") today. Assert
    // alert role + missing form, same defensive shape as test #1.
    await expect(page.getByRole('alert')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByLabel('Password', { exact: true })).toHaveCount(0);
  });
});
