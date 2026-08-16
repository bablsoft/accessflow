import { test, expect, type APIRequestContext } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';
import { getCurrentUserIdViaApi } from '../helpers/apiConnectors';

// Out-of-office reviewer delegation (#622). The bootstrap admin is the only guaranteed account, so
// these tests exercise the delegation lifecycle and its refusals through the self-service API plus
// the profile UI. Full cross-user eligibility (delegate sees the delegator's queue) needs a second
// seeded reviewer and is covered by the backend integration tests instead.
const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

interface DelegationRow {
  id: string;
  status: string;
  delegate: { id: string };
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

/** Leave no active delegations behind — they would widen eligibility for later specs. */
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
  test.afterEach(async ({ request }) => {
    const token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    await revokeAll(request, token);
  });

  test('rejects delegating to yourself', async ({ request }) => {
    const token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const selfId = await getCurrentUserIdViaApi(request, token);

    const res = await request.post(`${apiBase()}/api/v1/me/review-delegations`, {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        delegate_user_id: selfId,
        scope_kind: null,
        scope_id: null,
        reason: 'nope',
        starts_at: new Date(Date.now() + 60_000).toISOString(),
        ends_at: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      },
    });

    expect(res.status()).toBe(422);
    expect((await res.json()).error).toBe('ILLEGAL_REVIEW_DELEGATION');
  });

  test('rejects a window that ends before it starts', async ({ request }) => {
    const token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const candidates = await request.get(
      `${apiBase()}/api/v1/me/review-delegations/candidates`,
      { headers: { Authorization: `Bearer ${token}` } },
    );
    expect(candidates.ok()).toBeTruthy();
    const others = (await candidates.json()) as Array<{ id: string }>;
    test.skip(others.length === 0, 'needs a second user in the organization');

    const res = await request.post(`${apiBase()}/api/v1/me/review-delegations`, {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        delegate_user_id: others[0]!.id,
        scope_kind: null,
        scope_id: null,
        reason: null,
        starts_at: new Date(Date.now() + 7 * 86_400_000).toISOString(),
        ends_at: new Date(Date.now() + 60_000).toISOString(),
      },
    });

    expect(res.status()).toBe(422);
  });

  test('the candidate list never includes the caller', async ({ request }) => {
    const token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    const selfId = await getCurrentUserIdViaApi(request, token);

    const res = await request.get(`${apiBase()}/api/v1/me/review-delegations/candidates`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(res.ok()).toBeTruthy();
    const candidates = (await res.json()) as Array<{ id: string }>;
    expect(candidates.map((c) => c.id)).not.toContain(selfId);
  });

  test('the profile page shows the out-of-office card with its empty states', async ({ page }) => {
    await page.goto('/login');
    await page.locator('#login-email').fill(ADMIN_EMAIL);
    await page.locator('#login-password').fill(ADMIN_PASSWORD);
    await page.locator('button[type="submit"]').click();
    await page.waitForURL('**/dashboard', { timeout: 15_000 });

    await page.goto('/profile');

    const card = page
      .locator('.ant-card')
      .filter({ hasText: 'Out-of-office delegation' })
      .first();
    await expect(card).toBeVisible();
    await expect(card.getByText(/never act on a request you submitted/i)).toBeVisible();
    await expect(card.getByText("You haven't delegated your review duty.")).toBeVisible();
    await expect(
      card.getByText('Nobody has delegated their review duty to you.'),
    ).toBeVisible();
  });

  test('an admin can read the org-wide delegation listing', async ({ request }) => {
    const token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    const res = await request.get(`${apiBase()}/api/v1/admin/review-delegations`, {
      headers: { Authorization: `Bearer ${token}` },
    });

    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body).toHaveProperty('content');
    expect(Array.isArray(body.content)).toBeTruthy();
  });
});
