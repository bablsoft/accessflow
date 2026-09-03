import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  inviteUserViaApi,
  loginViaApi,
  waitForInviteToken,
} from '../helpers/datasources';
import { ADMIN_EMAIL, ADMIN_PASSWORD, login } from '../helpers/login';

// #836 — release update check. The e2e stacks boot the backend with
// ACCESSFLOW_UPDATES_ENABLED=false, so the endpoint must answer a deterministic
// `UNKNOWN` without ever touching the network, and the sidebar must render the
// plain version (no changelog link). The "update available" rendering is covered
// by frontend/src/components/common/__tests__/VersionBadge.test.tsx — there is no
// deterministic manifest source inside the e2e stack to drive it from here.
const UPDATE_STATUS = `${apiBase()}/api/v1/system/update-status`;
const READER_PASSWORD = 'ReaderPassword!123';

test.describe('release update status (#836)', () => {
  // Two-user setup: invite → Mailcrab poll → accept can exceed the 30 s default under load.
  test.describe.configure({ timeout: 90_000 });

  let adminToken = '';
  const readerEmail = `af836-reader-${randomUUID()}@e2e.local`;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // A non-admin proves the endpoint is gated on authentication only.
    await inviteUserViaApi(request, adminToken, readerEmail, 'AF-836 Reader', 'ANALYST');
    const token = await waitForInviteToken(request, readerEmail);
    await acceptInvitationViaApi(request, token, READER_PASSWORD, 'AF-836 Reader');
  });

  test('answers UNKNOWN for an admin when the check is disabled', async ({ request }) => {
    const res = await request.get(UPDATE_STATUS, {
      headers: { Authorization: `Bearer ${adminToken}` },
    });
    expect(res.status()).toBe(200);
    const body = (await res.json()) as {
      status: string;
      update_available: boolean;
      latest_version?: string | null;
      changelog_url?: string | null;
      checked_at?: string | null;
    };
    expect(body.status).toBe('UNKNOWN');
    expect(body.update_available).toBe(false);
    // The API omits null fields (non_null inclusion), so normalise absent → null.
    expect(body.latest_version ?? null).toBeNull();
    expect(body.changelog_url ?? null).toBeNull();
    expect(body.checked_at ?? null).toBeNull();
  });

  test('is readable by a non-admin user', async ({ request }) => {
    const readerToken = await loginViaApi(request, readerEmail, READER_PASSWORD);
    const res = await request.get(UPDATE_STATUS, {
      headers: { Authorization: `Bearer ${readerToken}` },
    });
    expect(res.status()).toBe(200);
    const body = (await res.json()) as { status: string; update_available: boolean };
    expect(body.status).toBe('UNKNOWN');
    expect(body.update_available).toBe(false);
  });

  test('rejects anonymous callers', async ({ request }) => {
    const res = await request.get(UPDATE_STATUS);
    expect(res.status()).toBe(401);
  });

  test('sidebar shows the plain version and no changelog link', async ({ page }) => {
    await login(page, readerEmail, READER_PASSWORD);
    const brand = page.locator('.af-sidebar-brand');
    await expect(brand.getByText(/^v\d+\.\d+\.\d+/)).toBeVisible();
    await expect(brand.getByRole('link')).toHaveCount(0);
    await expect(page.locator('a[href*="/changelog/"]')).toHaveCount(0);
  });
});
