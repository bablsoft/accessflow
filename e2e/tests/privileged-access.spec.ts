// #968 — the privileged-access report: who can reach data without a permission row.
//
// NOTE (repo memory: e2e port 5173 collision): the main e2e stack binds the
// frontend on host port 5173, which collides with a locally running dev app.
// Free the port (or set E2E_BASE_URL / E2E_API_BASE) before running locally.
//
// Covered here:
//   1. The seeded admin — a QUERY_ADMIN holder with zero permission rows — appears,
//      labelled as a bypass carried by the system ADMIN role, with its query evidence.
//   2. A run-unique analyst holding can_break_glass appears with the datasource, DIRECT
//      provenance and a null expiry; the kind filter excludes them from QUERY_ADMIN.
//   3. An analyst whose only access is an ordinary can_read grant does NOT appear.
//   4. The page renders through the real API from the sidebar, and its filters reach the
//      request as query parameters.
//   5. An analyst is redirected away — the route is gated on the report permissions.
//
// Runs in the `parallel` project: every assertion is `user_id`-scoped on the seeded admin or
// a run-unique user, so concurrent specs adding their own admins or grants cannot flip it.
// (The over-provisioned sibling is serial because it asserts on org-wide aggregates.)
import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  acceptInvitationViaApi,
  createPostgresDatasource,
  findUserByEmailViaApi,
  grantPermissionViaApi,
  inviteUserViaApi,
  listPrivilegedAccessViaApi,
  loginViaApi,
  waitForInviteToken,
} from '../helpers/datasources';
import { login } from '../helpers/login';
import { expandNavSection } from '../helpers/nav';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const ANALYST_PASSWORD = 'Analyst-Pwd!123';
const ROUTE = '/admin/privileged-access';
const LIST_ENDPOINT = /\/api\/v1\/admin\/privileged-access(\?|$)/;

test.describe.configure({ timeout: 90_000 });

test.describe.serial('privileged-access report (#968)', () => {
  let adminToken = '';
  let adminId = '';
  let breakGlassEmail = '';
  let breakGlassId = '';
  let plainEmail = '';
  let plainId = '';
  let datasourceName = '';

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    adminId = (await findUserByEmailViaApi(request, adminToken, ADMIN_EMAIL)).id;

    const suffix = randomUUID();
    breakGlassEmail = `af968-bg-${suffix}@e2e.local`;
    plainEmail = `af968-plain-${suffix}@e2e.local`;
    for (const [email, name] of [
      [breakGlassEmail, '#968 Break-glass analyst'],
      [plainEmail, '#968 Plain analyst'],
    ] as const) {
      await inviteUserViaApi(request, adminToken, email, name, 'ANALYST');
      const token = await waitForInviteToken(request, email);
      await acceptInvitationViaApi(request, token, ANALYST_PASSWORD, name);
    }
    breakGlassId = (await findUserByEmailViaApi(request, adminToken, breakGlassEmail)).id;
    plainId = (await findUserByEmailViaApi(request, adminToken, plainEmail)).id;

    datasourceName = `Privileged E2E ${Date.now()}`;
    const datasource = await createPostgresDatasource(request, adminToken, { name: datasourceName });
    await grantPermissionViaApi(request, adminToken, datasource.id, breakGlassId, {
      canRead: true,
      canBreakGlass: true,
    });
    await grantPermissionViaApi(request, adminToken, datasource.id, plainId, { canRead: true });
  });

  test('the seeded admin appears as a QUERY_ADMIN bypass with no permission row', async ({
    request,
  }) => {
    const page = await listPrivilegedAccessViaApi(request, adminToken, { user_id: adminId });

    expect(page.total_elements).toBe(1);
    const row = page.content[0]!;
    expect(row.email).toBe(ADMIN_EMAIL);
    expect(row.bypass_kinds).toContain('QUERY_ADMIN');
    expect(row.query_admin?.role_name).toBe('ADMIN');
    expect(row.query_admin?.system_role).toBe(true);
    expect(row.system_role).toBe(true);
    // The nullable evidence fields must be present explicitly, never omitted.
    expect(row.evidence).toHaveProperty('last_submitted_at');
    expect(row.evidence).toHaveProperty('last_break_glass_at');
    expect(typeof row.evidence.submitted_query_count).toBe('number');
  });

  test('a break-glass holder appears with the datasource, provenance and expiry', async ({
    request,
  }) => {
    const page = await listPrivilegedAccessViaApi(request, adminToken, { user_id: breakGlassId });

    expect(page.total_elements).toBe(1);
    const row = page.content[0]!;
    expect(row.bypass_kinds).toEqual(['BREAK_GLASS']);
    expect(row.query_admin).toBeNull();
    expect(row.break_glass_grants).toHaveLength(1);
    expect(row.break_glass_grants[0]).toMatchObject({
      datasource_name: datasourceName,
      source_kind: 'DIRECT',
      group_name: null,
      expires_at: null,
    });

    // The kind filter selects rows: the same identity is not a QUERY_ADMIN row.
    const filtered = await listPrivilegedAccessViaApi(request, adminToken, {
      kind: 'QUERY_ADMIN',
      user_id: breakGlassId,
    });
    expect(filtered.total_elements).toBe(0);
  });

  test('a user whose only access is an ordinary grant does not appear', async ({ request }) => {
    const page = await listPrivilegedAccessViaApi(request, adminToken, { user_id: plainId });

    expect(page.total_elements).toBe(0);
    expect(page.content).toEqual([]);
  });

  test('admin reaches the report from the sidebar and the filters drive the request', async ({
    browser,
  }) => {
    const ctx = await browser.newContext();
    try {
      const page = await ctx.newPage();
      await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);

      await expandNavSection(page, 'Security & Access', 'Access control');
      const [initial] = await Promise.all([
        page.waitForResponse((r) => LIST_ENDPOINT.test(r.url()) && r.ok(), { timeout: 15_000 }),
        page.getByRole('link', { name: 'Privileged access' }).click(),
      ]);
      expect(initial.ok()).toBe(true);
      await expect(page).toHaveURL(new RegExp(`${ROUTE}$`));
      await expect(page.getByRole('heading', { name: 'Privileged access' })).toBeVisible();

      // Narrow to the run-unique break-glass analyst: the row must render through the real API.
      const [byUser] = await Promise.all([
        page.waitForResponse(
          (r) => LIST_ENDPOINT.test(r.url()) && r.url().includes(`user_id=${breakGlassId}`),
          { timeout: 15_000 },
        ),
        page.getByLabel('Filter by user id').fill(breakGlassId),
      ]);
      expect(byUser.ok()).toBe(true);
      await expect(page.getByText(breakGlassEmail)).toBeVisible();
      await expect(page.getByText(datasourceName, { exact: true })).toBeVisible();
      await expect(page.getByText('Break-glass', { exact: true })).toBeVisible();

      // The kind filter must reach the backend as `kind`, not stay client-side.
      const [byKind] = await Promise.all([
        page.waitForResponse(
          (r) => LIST_ENDPOINT.test(r.url()) && r.url().includes('kind=QUERY_ADMIN'),
          { timeout: 15_000 },
        ),
        (async () => {
          await page.getByLabel('Bypass kind').click();
          await page.getByTitle('Query admin', { exact: true }).click();
        })(),
      ]);
      expect(byKind.ok()).toBe(true);
      // A break-glass-only identity is not a QUERY_ADMIN row.
      await expect(page.getByText('No privileged access')).toBeVisible();
    } finally {
      await ctx.close();
    }
  });

  test('an analyst is redirected away from the report', async ({ browser }) => {
    const ctx = await browser.newContext();
    try {
      const page = await ctx.newPage();
      await login(page, plainEmail, ANALYST_PASSWORD);
      await page.goto(ROUTE);

      // AuthGuard sends an unauthorized user to their home path rather than showing a 403.
      await expect(page).not.toHaveURL(new RegExp(`${ROUTE}$`), { timeout: 15_000 });
    } finally {
      await ctx.close();
    }
  });
});
