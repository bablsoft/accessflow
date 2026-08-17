import { test, expect, type APIRequestContext } from '@playwright/test';
import {
  acceptInvitationViaApi,
  findUserByEmailViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  waitForInviteToken,
} from '../helpers/datasources';
import { getCurrentUserIdViaApi } from '../helpers/apiConnectors';

// Out-of-office reviewer delegation (#622). The main stack seeds exactly one account, so this spec
// mints its own delegate via the invitation flow — without a second user the create path cannot be
// exercised at all, and the refusals below would be the only coverage.
//
// Cross-user *eligibility* (the delegate seeing the delegator's queue and approving with
// on-behalf-of provenance) needs a datasource, a review plan naming the delegator, and a third
// submitter; that shape is covered against real Postgres by the backend integration tests.
const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const DELEGATE_PASSWORD = 'DelegatePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

interface DelegationRow {
  id: string;
  status: string;
  reason: string | null;
  scope_kind: string | null;
  delegate: { id: string; email: string | null };
  delegator: { id: string };
}

async function listDelegations(
  request: APIRequestContext,
  token: string,
): Promise<{ granted: DelegationRow[]; received: DelegationRow[] }> {
  const res = await request.get(`${apiBase()}/api/v1/me/review-delegations`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(res.ok()).toBeTruthy();
  return res.json();
}

function window(offsetMinutes = 1, days = 7) {
  return {
    starts_at: new Date(Date.now() + offsetMinutes * 60_000).toISOString(),
    ends_at: new Date(Date.now() + days * 86_400_000).toISOString(),
  };
}

async function createDelegation(
  request: APIRequestContext,
  token: string,
  body: Record<string, unknown>,
) {
  return request.post(`${apiBase()}/api/v1/me/review-delegations`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { scope_kind: null, scope_id: null, reason: null, ...window(), ...body },
  });
}

/** Leave no open delegations behind — they would widen eligibility for later specs. */
async function revokeAll(request: APIRequestContext, token: string): Promise<void> {
  const { granted } = await listDelegations(request, token);
  for (const row of granted) {
    if (row.status === 'ACTIVE' || row.status === 'SCHEDULED') {
      await request.delete(`${apiBase()}/api/v1/me/review-delegations/${row.id}`, {
        headers: { Authorization: `Bearer ${token}` },
      });
    }
  }
}

test.describe('reviewer delegation', () => {
  let adminToken = '';
  let delegateEmail = '';
  let delegateId = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    delegateEmail = `af622-delegate-${Date.now()}@accessflow.test`;
    await purgeMailcrab(request);
    await inviteUserViaApi(request, adminToken, delegateEmail, 'AF-622 Delegate', 'REVIEWER');
    const token = await waitForInviteToken(request, delegateEmail);
    await acceptInvitationViaApi(request, token, DELEGATE_PASSWORD, 'AF-622 Delegate');
    const delegate = await findUserByEmailViaApi(request, adminToken, delegateEmail);
    delegateId = delegate.id;
  });

  test.afterEach(async ({ request }) => {
    await revokeAll(request, await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD));
  });

  test('create surfaces on both sides, then revoke retires it', async ({ request }) => {
    const created = await createDelegation(request, adminToken, {
      delegate_user_id: delegateId,
      reason: 'Annual leave',
    });
    expect(created.status()).toBe(201);
    const body = (await created.json()) as DelegationRow;
    expect(body.status).toBe('SCHEDULED');
    expect(body.reason).toBe('Annual leave');
    expect(body.delegate.id).toBe(delegateId);

    // The delegator sees it as granted...
    const mine = await listDelegations(request, adminToken);
    expect(mine.granted.map((d) => d.id)).toContain(body.id);

    // ...and the delegate sees the same row as received.
    const delegateToken = await loginViaApi(request, delegateEmail, DELEGATE_PASSWORD);
    const theirs = await listDelegations(request, delegateToken);
    expect(theirs.received.map((d) => d.id)).toContain(body.id);
    expect(theirs.granted).toHaveLength(0);

    // Revocation is soft — the row survives as evidence, with a REVOKED status.
    const revoked = await request.delete(
      `${apiBase()}/api/v1/me/review-delegations/${body.id}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(revoked.status()).toBe(204);
    const after = await listDelegations(request, adminToken);
    expect(after.granted.find((d) => d.id === body.id)?.status).toBe('REVOKED');
  });

  test('the delegate cannot revoke a delegation granted to them', async ({ request }) => {
    const created = await createDelegation(request, adminToken, { delegate_user_id: delegateId });
    expect(created.status()).toBe(201);
    const { id } = (await created.json()) as DelegationRow;

    const delegateToken = await loginViaApi(request, delegateEmail, DELEGATE_PASSWORD);
    const res = await request.delete(`${apiBase()}/api/v1/me/review-delegations/${id}`, {
      headers: { Authorization: `Bearer ${delegateToken}` },
    });

    // 404, not 403 — the endpoint never confirms someone else's delegation exists.
    expect(res.status()).toBe(404);
  });

  test('revoking twice is idempotent', async ({ request }) => {
    const created = await createDelegation(request, adminToken, { delegate_user_id: delegateId });
    const { id } = (await created.json()) as DelegationRow;
    const url = `${apiBase()}/api/v1/me/review-delegations/${id}`;
    const headers = { Authorization: `Bearer ${adminToken}` };

    expect((await request.delete(url, { headers })).status()).toBe(204);
    expect((await request.delete(url, { headers })).status()).toBe(204);
  });

  test('rejects delegating to yourself', async ({ request }) => {
    const selfId = await getCurrentUserIdViaApi(request, adminToken);

    const res = await createDelegation(request, adminToken, { delegate_user_id: selfId });

    expect(res.status()).toBe(422);
    expect((await res.json()).error).toBe('ILLEGAL_REVIEW_DELEGATION');
  });

  test('rejects a window that ends before it starts', async ({ request }) => {
    const res = await createDelegation(request, adminToken, {
      delegate_user_id: delegateId,
      starts_at: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      ends_at: new Date(Date.now() + 60_000).toISOString(),
    });

    expect(res.status()).toBe(422);
  });

  test('rejects a scope that does not resolve', async ({ request }) => {
    const res = await createDelegation(request, adminToken, {
      delegate_user_id: delegateId,
      scope_kind: 'DATASOURCE',
      scope_id: '00000000-0000-0000-0000-000000000000',
    });

    expect(res.status()).toBe(422);
  });

  test('the candidate list includes the delegate but never the caller', async ({ request }) => {
    const selfId = await getCurrentUserIdViaApi(request, adminToken);

    const res = await request.get(`${apiBase()}/api/v1/me/review-delegations/candidates`, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });

    expect(res.ok()).toBeTruthy();
    const ids = ((await res.json()) as Array<{ id: string }>).map((c) => c.id);
    expect(ids).toContain(delegateId);
    expect(ids).not.toContain(selfId);
  });

  test('an admin can read the org-wide delegation listing', async ({ request }) => {
    const created = await createDelegation(request, adminToken, { delegate_user_id: delegateId });
    const { id } = (await created.json()) as DelegationRow;

    const res = await request.get(
      `${apiBase()}/api/v1/admin/review-delegations?active_only=false`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );

    expect(res.ok()).toBeTruthy();
    const body = (await res.json()) as { content: DelegationRow[] };
    expect(body.content.map((d) => d.id)).toContain(id);
  });

  test('the profile page lists a delegation and can revoke it', async ({ page, request }) => {
    const created = await createDelegation(request, adminToken, {
      delegate_user_id: delegateId,
      reason: 'Annual leave',
    });
    expect(created.status()).toBe(201);

    await page.goto('/login');
    await page.locator('#login-email').fill(ADMIN_EMAIL);
    await page.locator('#login-password').fill(ADMIN_PASSWORD);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/dashboard', { timeout: 15_000 });
    await page.goto('/profile');

    const card = page.locator('.ant-card').filter({ hasText: 'Out-of-office delegation' }).first();
    await expect(card).toBeVisible();
    // Assert on the email, not the display name: inviteUserViaApi's no-roleId path posts a
    // camelCase `displayName` the snake_case API ignores, so invited users have none.
    await expect(card.getByText(delegateEmail).first()).toBeVisible();
    await expect(card.getByText('All review queues').first()).toBeVisible();

    // Exactly one revocable row exists — afterEach retires the rest, and revoked/expired rows
    // render no action.
    const revoke = card.getByRole('button', { name: 'Revoke' });
    await expect(revoke).toHaveCount(1);
    await revoke.click();
    // AntD Popconfirm renders detached in a portal — confirm via its primary button, matching
    // the idiom in access-requests.spec.ts rather than a bare OK role lookup.
    await page.locator('.ant-popconfirm-buttons .ant-btn-primary').click();

    // The row survives as evidence; only its action goes away.
    await expect(revoke).toHaveCount(0, { timeout: 10_000 });
  });
});
