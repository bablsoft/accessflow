import { randomUUID } from 'node:crypto';
import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import {
  createPostgresDatasource,
  deleteDatasource,
  loginViaApi,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const API_BASE = process.env.E2E_API_BASE ?? 'http://localhost:8080';

// Mailcrab HTTP/JSON API — same host port exposed by docker-compose.e2e.yml the
// auth-invitation spec uses. The invitation flow in test 2 needs it to scrape a
// one-time token out of the captured email.
const MAILCRAB_BASE = 'http://localhost:1080';
const INVITE_URL_REGEX = /\/invite\/([A-Za-z0-9_-]+)/;
const INVITEE_PASSWORD = 'Invitee-Pwd!123';

// Shared across the three serial tests — provisioned once in beforeAll, torn
// down in afterAll. Each test submits its own query so they can't contaminate
// each other (one cancels, one is viewed by a non-submitter, one is left at
// PENDING_REVIEW so the stubbed-409 path can be exercised).
let datasource: CreatedDatasource | null = null;
let adminAccessToken = '';

interface MailcrabSummary {
  id: string;
  to?: Array<{ email?: string } | string>;
}

interface MailcrabMessage extends MailcrabSummary {
  text?: string;
  html?: string;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

async function submitQuery(
  request: APIRequestContext,
  accessToken: string,
  datasourceId: string,
  sql: string,
): Promise<{ id: string; status: string }> {
  const res = await request.post(`${API_BASE}/api/v1/queries`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { datasource_id: datasourceId, sql, justification: 'e2e/query-detail-cancel' },
  });
  if (!res.ok()) {
    throw new Error(`Submit query failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as { id: string; status: string };
}

// Submit returns PENDING_AI immediately; the AF-307 skip listener flips the
// status to PENDING_REVIEW asynchronously when ai_analysis_enabled=false.
// Poll GET /api/v1/queries/{id} until the status matches `expected`. Identical
// to the helper in tests/query-list.spec.ts.
async function waitForQueryStatus(
  request: APIRequestContext,
  accessToken: string,
  id: string,
  expected: string,
  timeoutMs = 10_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const res = await request.get(`${API_BASE}/api/v1/queries/${id}`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (res.ok()) {
      const body = (await res.json()) as { status: string };
      last = body.status;
      if (body.status === expected) return;
    }
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(
    `Query ${id} did not reach status ${expected} within ${timeoutMs}ms (last seen: ${last || '<no response>'})`,
  );
}

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
      const match = [...summaries].reverse().find((m) => recipientMatches(m, recipient));
      if (match) {
        const detailRes = await request.get(`${MAILCRAB_BASE}/api/message/${match.id}`);
        if (detailRes.ok()) {
          const detail = (await detailRes.json()) as MailcrabMessage;
          const body = `${detail.text ?? ''}\n${detail.html ?? ''}`;
          const m = body.match(INVITE_URL_REGEX);
          if (m) return m[1];
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

test.describe.serial('query detail page — view + submitter cancel (AF-266)', () => {
  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF266 ${Date.now()}`,
    });
  });

  test.afterAll(async ({ request }) => {
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Happy path: submitter cancels through the Popconfirm ───────────────
  test('admin submits, opens detail, cancels via Popconfirm → status flips to CANCELLED', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQuery(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 1',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);

    // statusLabel() replaces '_' with ' ', so PENDING_REVIEW renders as
    // "PENDING REVIEW" inside the StatusPill which lives in the <h1>.
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Pending review'),
    ).toBeVisible({ timeout: 15_000 });

    // AF-307 follow-up: the seeded datasource has ai_analysis_enabled=false,
    // so the AI stage must render the "skipped" surface (timeline label,
    // card title, and card body), NOT the "Awaiting analysis…" fallback.
    await expect(page.getByText('AI analysis skipped', { exact: true })).toBeVisible();
    await expect(page.getByText('AI analysis (skipped)', { exact: true })).toBeVisible();
    await expect(page.getByText(/AI analysis was skipped/)).toBeVisible();
    await expect(page.getByText('Awaiting analysis…')).toHaveCount(0);

    // Open the Popconfirm — the underlying Button still carries the
    // "Cancel query" label, Popconfirm only adds an OK/Cancel pair in a portal.
    await page.getByRole('button', { name: 'Cancel query' }).click();
    await page.getByRole('button', { name: 'OK' }).click();

    // Status pill flips → CANCELLED.
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Cancelled'),
    ).toBeVisible({ timeout: 15_000 });

    // Timeline shows the "Cancelled" stage. After AF-315 the StatusPill also
    // renders "Cancelled" (sentence-case translation), so we scope this assertion
    // to assert at least one match exists; the heading-level pill is asserted
    // separately above.
    await expect(page.getByText('Cancelled', { exact: true }).first()).toBeVisible();

    // List page reflects the new status. Match the row by the 8-char id prefix
    // QueryListPage renders in the id column (see query-list.spec.ts).
    await page.goto('/queries');
    await page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/queries(\?|$)/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );
    const row = page.locator('tr.ant-table-row').filter({ hasText: submitted.id.slice(0, 8) });
    await expect(row).toBeVisible();
    await expect(row.getByText('Cancelled', { exact: true })).toBeVisible();
  });

  // ── 2. Non-submitter (invited ADMIN) viewing the detail page ──────────────
  test('non-submitter (second admin) viewing the detail page sees no Cancel button', async ({
    browser,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    // Bootstrap admin is the submitter; the second admin we invite below is
    // not. Fresh query so test 1's CANCELLED row doesn't muddy the assertion
    // (we need the status pill to read PENDING REVIEW for the second admin's
    // view).
    //
    // The invitee is provisioned as ADMIN, not ANALYST, because an ANALYST
    // with no explicit datasource permission can't view another user's query
    // (GET /api/v1/queries/{id} returns 404). ADMIN has org-wide visibility,
    // which is all we need to exercise canCancel === false for a non-submitter.
    const submitted = await submitQuery(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 2',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');

    await purgeMailcrab(request);

    // Random email so a re-run doesn't collide with the row left behind by the
    // previous run — invitation acceptance provisions a real user we don't
    // clean up (matches the auth-invitation spec's behavior).
    const invitee = `af266-admin-${randomUUID()}@e2e.local`;
    const adminCtx = await browser.newContext();
    const inviteeCtx = await browser.newContext();
    try {
      const adminPage = await adminCtx.newPage();
      await loginViaUi(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);
      await adminPage.goto('/admin/users');
      await adminPage
        .getByRole('button', { name: 'Invite via email', exact: true })
        .click();
      const dialog = adminPage.getByRole('dialog');
      await expect(dialog.getByText('Invite a teammate')).toBeVisible({ timeout: 10_000 });
      await dialog.getByLabel('Email').fill(invitee);

      // Pick Admin from the Role select (default is Analyst). The AntD Select
      // renders its options into a portal; after AF-315 the option labels go
      // through `enumLabels.ts` so they render the translated "Admin" string,
      // not the raw enum value.
      await dialog.getByLabel('Role').click();
      await adminPage
        .locator('.ant-select-item-option')
        .filter({ hasText: /^Admin$/ })
        .click();

      await dialog.getByRole('button', { name: 'Send invitation' }).click();
      await expect(adminPage.getByText('Invitation email sent')).toBeVisible({
        timeout: 10_000,
      });

      const token = await waitForInviteToken(request, invitee);

      const inviteePage = await inviteeCtx.newPage();
      await inviteePage.goto(`/invite/${token}`);
      await inviteePage
        .getByLabel('Password', { exact: true })
        .fill(INVITEE_PASSWORD);
      await inviteePage
        .getByLabel('Confirm password', { exact: true })
        .fill(INVITEE_PASSWORD);
      await inviteePage.locator('button[type="submit"]').click();
      await inviteePage.waitForURL('**/login', { timeout: 10_000 });

      await inviteePage.locator('#login-email').fill(invitee);
      await inviteePage.locator('#login-password').fill(INVITEE_PASSWORD);
      await inviteePage.locator('button[type="submit"]').click();
      await inviteePage.waitForURL('**/editor', { timeout: 15_000 });

      await inviteePage.goto(`/queries/${submitted.id}`);
      await expect(
        inviteePage.getByRole('heading', { level: 1 }).getByText('Pending review'),
      ).toBeVisible({ timeout: 15_000 });

      // canCancel === false for non-submitter, so the Button never renders.
      await expect(
        inviteePage.getByRole('button', { name: 'Cancel query' }),
      ).toHaveCount(0);
    } finally {
      await adminCtx.close();
      await inviteeCtx.close();
    }
  });

  // ── 3. 409 from cancel endpoint surfaces an error toast ───────────────────
  test('409 QUERY_NOT_CANCELLABLE renders an error toast and leaves status untouched', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQuery(
      request,
      adminAccessToken,
      datasource.id,
      'SELECT 3',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');

    // Stub the POST to return 409. Driving the backend into a real 409 from the
    // UI would require racing approve-while-viewing — canCancel hides the
    // button as soon as status leaves PENDING_*, so the only deterministic way
    // to exercise the new onError path is to fake the response.
    await page.route(`**/api/v1/queries/${submitted.id}/cancel`, async (route) => {
      if (route.request().method() !== 'POST') return route.fallback();
      await route.fulfill({
        status: 409,
        contentType: 'application/problem+json',
        body: JSON.stringify({
          type: 'about:blank',
          title: 'Conflict',
          status: 409,
          detail: 'Query is not cancellable',
          error: 'QUERY_NOT_CANCELLABLE',
          currentStatus: 'APPROVED',
        }),
      });
    });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Pending review'),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Cancel query' }).click();
    await page.getByRole('button', { name: 'OK' }).click();

    // Toast copy from errors.query_not_cancellable in en.json.
    await expect(
      page.getByText('This query can no longer be cancelled (it has already advanced).'),
    ).toBeVisible({ timeout: 5_000 });

    // Status pill unchanged — mutation never reached the real backend.
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Pending review'),
    ).toBeVisible();
  });
});

void API_BASE; // referenced via helpers; explicit `void` silences noUnusedLocals.
