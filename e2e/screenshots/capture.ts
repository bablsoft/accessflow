// Screenshot capture script for AccessFlow website docs.
//
// Drives the running e2e stack (http://localhost:5173 frontend,
// http://localhost:8080 backend) and writes 46 PNGs into
// ../website/images/docs/ — the 38 existing screens (re-captured against the
// current build) plus 8 new v1.3 captures (light + dark each): the routing-policy
// admin page (AF-379), the access-requests queue (AF-378), and the datasource
// Masking (AF-381) + Row security (AF-380) settings tabs.
//
// Run from the e2e/ directory after `npm run stack:up`:
//   npx tsx screenshots/capture.ts
//
// One-off, deliberately top-down. Not a Playwright test — it would not gain
// from parallelism, and the data seeding wants strict ordering.

import { chromium, request as pwRequest, type Page } from '@playwright/test';
import {
  apiBase,
  loginViaApi,
  listAiConfigsViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  submitQueryViaApi,
  waitForQueryStatus,
  inviteUserViaApi,
  acceptInvitationViaApi,
  approveQueryViaApi,
  waitForInviteToken,
  purgeMailcrab,
} from '../helpers/datasources';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdirSync } from 'node:fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const OUT_DIR = path.resolve(__dirname, '../../website/images/docs');
mkdirSync(OUT_DIR, { recursive: true });

const BASE = 'http://localhost:5173';
const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const RUN_SUFFIX = Date.now();
const REVIEWER_EMAIL = `reviewer-${RUN_SUFFIX}@accessflow.test`;
const REVIEWER_PASSWORD = 'ReviewerPassword!123';

const VIEWPORT = { width: 1440, height: 900 };

async function seedData() {
  console.log('[seed] starting API seeding');
  const api = await pwRequest.newContext();
  const adminToken = await loginViaApi(api, ADMIN_EMAIL, ADMIN_PASSWORD);

  // 1. Find the mock AI config seeded by the bootstrap reconciler.
  const configs = await listAiConfigsViaApi(api, adminToken);
  const mockAi = configs.find((c) => c.name === 'e2e-mock-openai');
  if (!mockAi) throw new Error('Bootstrap mock AI config not found');

  // 2. Provision a reviewer (needed to approve admin's own queries — the
  //    workflow forbids self-approval).
  await purgeMailcrab(api).catch(() => {});
  const reviewer = await inviteUserViaApi(api, adminToken, REVIEWER_EMAIL, 'Sample Reviewer', 'REVIEWER');
  const reviewerToken = await waitForInviteToken(api, REVIEWER_EMAIL).then(async (token) => {
    await acceptInvitationViaApi(api, token, REVIEWER_PASSWORD, 'Sample Reviewer');
    return loginViaApi(api, REVIEWER_EMAIL, REVIEWER_PASSWORD);
  });

  // 3. Create a review plan with a REVIEWER-role approver so the seeded
  //    reviewer is eligible to approve queries.
  const plan = await createReviewPlanViaApi(api, adminToken, {
    name: `Standard review (single REVIEWER) ${RUN_SUFFIX}`,
    approvers: [{ role: 'REVIEWER', stage: 1 }],
    minApprovalsRequired: 1,
  });
  console.log(`[seed] review plan ${plan.id}`);

  // 4. Create datasource (Postgres pointing at the in-stack DB itself — gives
  //    the ER diagram a real schema to introspect).
  const ds = await createPostgresDatasource(api, adminToken, {
    name: `Sample Postgres (analytics) ${RUN_SUFFIX}`,
    aiAnalysisEnabled: true,
    aiConfigId: mockAi.id,
    reviewPlanId: plan.id,
  });
  console.log(`[seed] datasource ${ds.id}`);

  // 4. Submit a mix of queries.
  const sqls = [
    'SELECT id, email, display_name FROM users LIMIT 10',
    'SELECT * FROM query_requests ORDER BY submitted_at DESC LIMIT 20',
    'SELECT status, COUNT(*) FROM query_requests GROUP BY status',
    'SELECT * FROM audit_log ORDER BY occurred_at DESC LIMIT 50',
    'SELECT name, db_type FROM datasources',
  ];
  const submitted: { id: string; status: string }[] = [];
  for (const sql of sqls) {
    const q = await submitQueryViaApi(api, adminToken, ds.id, sql);
    await waitForQueryStatus(api, adminToken, q.id, 'PENDING_REVIEW', 20_000);
    submitted.push(q);
  }
  console.log(`[seed] ${submitted.length} queries reached PENDING_REVIEW`);

  // 5. Approve the first two via the reviewer (gives queries-list a mix of
  //    statuses; leaves the rest in PENDING_REVIEW for the queue + bulk shot).
  for (let i = 0; i < 2; i++) {
    await approveQueryViaApi(api, reviewerToken, submitted[i].id, 'LGTM');
  }
  console.log('[seed] 2 queries approved by reviewer');

  // 6. Schedule one query a couple of days out.
  const future = new Date(Date.now() + 2 * 86400_000).toISOString();
  await submitQueryViaApi(
    api,
    adminToken,
    ds.id,
    'SELECT pg_database_size(current_database())',
    'scheduled weekly health check',
    future,
  );
  console.log('[seed] scheduled query submitted');

  // 7. Seed user groups (AF-353) so /admin/groups renders a populated table,
  //    with the seeded reviewer as a member of the first group.
  const apiB = apiBase();
  const adminHeaders = { Authorization: `Bearer ${adminToken}` };
  const groupSpecs = [
    {
      name: `Data Platform Reviewers ${RUN_SUFFIX}`,
      description: 'Reviewers for production analytics datasources',
    },
    {
      name: `Analytics Team ${RUN_SUFFIX}`,
      description: 'Read-only analysts, synced from the IdP',
    },
  ];
  const groupIds: string[] = [];
  for (const g of groupSpecs) {
    const res = await api.post(`${apiB}/api/v1/admin/groups`, { headers: adminHeaders, data: g });
    if (res.ok()) groupIds.push(((await res.json()) as { id: string }).id);
    else console.warn(`  [warn] create group failed: ${res.status()} ${await res.text()}`);
  }
  if (groupIds[0]) {
    const res = await api.post(`${apiB}/api/v1/admin/groups/${groupIds[0]}/members`, {
      headers: adminHeaders,
      data: { user_id: reviewer.id },
    });
    if (!res.ok()) console.warn(`  [warn] add group member failed: ${res.status()}`);
  }
  console.log(`[seed] ${groupIds.length} groups`);

  // 8. Seed query templates (AF-364) so the editor Templates drawer lists rows.
  const templateSpecs = [
    {
      name: 'Active users (last 30 days)',
      body: 'SELECT id, email, display_name\nFROM users\nWHERE last_login_at > now() - INTERVAL :days\nORDER BY last_login_at DESC',
      description: 'Recently active accounts for the current datasource',
      tags: ['users', 'reporting'],
      visibility: 'TEAM',
      datasource_id: ds.id,
    },
    {
      name: 'Query volume by status',
      body: 'SELECT status, COUNT(*) AS total\nFROM query_requests\nGROUP BY status\nORDER BY total DESC',
      description: 'Daily review-queue health check',
      tags: ['ops'],
      visibility: 'PRIVATE',
      datasource_id: ds.id,
    },
  ];
  let tmplCount = 0;
  for (const tpl of templateSpecs) {
    const res = await api.post(`${apiB}/api/v1/query-templates`, { headers: adminHeaders, data: tpl });
    if (res.ok()) tmplCount++;
    else console.warn(`  [warn] create template failed: ${res.status()} ${await res.text()}`);
  }
  console.log(`[seed] ${tmplCount} query templates`);

  // 9. Seed v1.3 entities so the new admin pages render populated.
  //    Routing policies (AF-379) — ordered auto-decision rules. Created last so
  //    they can't retroactively re-route the queries submitted above.
  const routingSpecs = [
    {
      name: `Auto-approve safe reads ${RUN_SUFFIX}`,
      description: 'SELECT with a WHERE clause skips human review',
      datasource_id: ds.id,
      priority: 10,
      enabled: true,
      condition: {
        type: 'and',
        children: [
          { type: 'query_type', any_of: ['SELECT'] },
          { type: 'has_where', expected: true },
        ],
      },
      action: 'AUTO_APPROVE',
    },
    {
      name: `Escalate high-risk writes ${RUN_SUFFIX}`,
      description: 'High / critical AI risk forces an extra approver',
      datasource_id: ds.id,
      priority: 20,
      enabled: true,
      condition: {
        type: 'and',
        children: [
          { type: 'query_type', any_of: ['INSERT', 'UPDATE', 'DELETE'] },
          { type: 'risk_level', any_of: ['HIGH', 'CRITICAL'] },
        ],
      },
      action: 'ESCALATE',
      required_approvals: 1,
    },
  ];
  let routingCount = 0;
  for (const rp of routingSpecs) {
    const res = await api.post(`${apiB}/api/v1/admin/routing-policies`, { headers: adminHeaders, data: rp });
    if (res.ok()) routingCount++;
    else console.warn(`  [warn] create routing policy failed: ${res.status()} ${await res.text()}`);
  }
  console.log(`[seed] ${routingCount} routing policies`);

  // Dynamic data masking policy (AF-381) on the datasource — emails masked,
  // revealed only to ADMIN.
  {
    const res = await api.post(`${apiB}/api/v1/datasources/${ds.id}/masking-policies`, {
      headers: adminHeaders,
      data: {
        column_ref: 'public.users.email',
        strategy: 'EMAIL',
        reveal_to_roles: ['ADMIN'],
        enabled: true,
      },
    });
    if (res.ok()) console.log('[seed] masking policy');
    else console.warn(`  [warn] create masking policy failed: ${res.status()} ${await res.text()}`);
  }

  // Row-level security policy (AF-380) — users only see query_requests they own.
  {
    const res = await api.post(`${apiB}/api/v1/datasources/${ds.id}/row-security-policies`, {
      headers: adminHeaders,
      data: {
        table_name: 'public.query_requests',
        column_name: 'submitted_by',
        operator: 'EQUALS',
        value_type: 'VARIABLE',
        value_expression: ':user.id',
        applies_to_roles: [],
        applies_to_group_ids: [],
        applies_to_user_ids: [],
        enabled: true,
      },
    });
    if (res.ok()) console.log('[seed] row-security policy');
    else console.warn(`  [warn] create row-security policy failed: ${res.status()} ${await res.text()}`);
  }

  // JIT access request (AF-378) raised by the reviewer (a non-admin) so the
  // admin queue at /admin/access-requests shows a populated, pending row (the
  // requester can't self-approve, so it stays PENDING).
  {
    const res = await api.post(`${apiB}/api/v1/access-requests`, {
      headers: { Authorization: `Bearer ${reviewerToken}` },
      data: {
        datasource_id: ds.id,
        can_read: true,
        can_write: false,
        can_ddl: false,
        requested_duration: 'PT4H',
        justification: 'Temporary read access to analytics for incident triage',
      },
    });
    if (res.ok()) console.log('[seed] access request (pending)');
    else console.warn(`  [warn] create access request failed: ${res.status()} ${await res.text()}`);
  }

  await api.dispose();
  return { datasourceId: ds.id };
}

async function setTheme(page: Page, theme: 'light' | 'dark') {
  await page.evaluate((t) => {
    const k = 'af-preferences';
    const raw = localStorage.getItem(k);
    const stored = raw ? JSON.parse(raw) : { state: {}, version: 0 };
    stored.state = {
      ...(stored.state ?? {}),
      theme: t,
      // Collapse and skip the SetupProgress banner so it does not clutter
      // every screenshot.
      setupProgressCollapsed: true,
      setupProgressSkipped: ['review_plans', 'datasources', 'ai_provider'],
    };
    localStorage.setItem(k, JSON.stringify(stored));
  }, theme);
  await page.reload({ waitUntil: 'networkidle' });
  // Give Ant Design's <ConfigProvider> a tick to swap algorithm + tokens.
  await page.waitForTimeout(500);
}

async function loginUi(page: Page, email = ADMIN_EMAIL, password = ADMIN_PASSWORD) {
  // Clear any prior session so the auth interceptor doesn't auto-skip /login.
  await page.context().clearCookies();
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => {
    localStorage.removeItem('af-auth');
  }).catch(() => {});
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.locator('#login-email').waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type=submit]').click();
  await page.waitForURL('**/editor', { timeout: 30_000 });
}

async function shoot(page: Page, file: string) {
  const target = path.join(OUT_DIR, file);
  await page.screenshot({ path: target, fullPage: false });
  console.log(`  -> ${file}`);
}

// Capture one (or both) variant(s) of a target. `prep` runs before each
// capture so closing drawers / theme flips don't leave residual state.
async function capture(
  page: Page,
  baseName: string,
  prep: (p: Page) => Promise<void>,
  opts: { darkToo?: boolean } = {},
) {
  const { darkToo = true } = opts;
  await setTheme(page, 'light');
  await prep(page);
  await shoot(page, `${baseName}-light.png`);
  if (darkToo) {
    await setTheme(page, 'dark');
    await prep(page);
    await shoot(page, `${baseName}-dark.png`);
  }
}

async function dismissEverything(page: Page) {
  // Close any open Ant Design overlay so the next prep step starts clean.
  await page.keyboard.press('Escape').catch(() => {});
  await page.keyboard.press('Escape').catch(() => {});
}

async function gotoAndSettle(page: Page, route: string) {
  await page.goto(`${BASE}${route}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(300);
}

// ----------------------- per-page preps -----------------------

async function prepUsersInvite(page: Page) {
  await gotoAndSettle(page, '/admin/users');
  // The header has a `Dropdown.Button` whose main click is "Invite via email"
  // (UsersPage.tsx:224). The split-arrow opens "Create with password".
  await page.getByRole('button', { name: /^Invite via email$/i }).first().click();
  await page.locator('.ant-modal, .ant-drawer').first().waitFor({ state: 'visible' });
  await page.waitForTimeout(300);
}

async function prepDatasourcesCreate(page: Page) {
  await gotoAndSettle(page, '/datasources/new');
  await page.waitForTimeout(500);
}

async function prepReviewPlansCreate(page: Page) {
  await gotoAndSettle(page, '/admin/review-plans');
  // The "Add review plan" button opens an EMPTY drawer (or a templates dropdown
  // exists separately). We want the drawer.
  await page.getByRole('button', { name: 'Add review plan' }).first().click();
  await page.locator('.ant-modal, .ant-drawer').first().waitFor({ state: 'visible' });
  await page.waitForTimeout(300);
}

async function prepReviewPlansTemplates(page: Page) {
  await gotoAndSettle(page, '/admin/review-plans');
  // Aria-label is the templates dropdown trigger (the icon button next to Add).
  await page
    .locator('[aria-label*="template" i], button[aria-label*="Create from template" i]')
    .first()
    .click();
  await page.locator('.ant-dropdown-menu').first().waitFor({ state: 'visible' });
  await page.waitForTimeout(300);
}

async function prepAiConfigsCreate(page: Page) {
  await gotoAndSettle(page, '/admin/ai-configs/new');
  await page.waitForTimeout(500);
}

async function prepNotificationsCreate(page: Page) {
  await gotoAndSettle(page, '/admin/notifications');
  await page.getByRole('button', { name: 'Add channel' }).first().click();
  await page.locator('.ant-modal, .ant-drawer').first().waitFor({ state: 'visible' });
  await page.waitForTimeout(300);
}

async function prepSystemSmtpEdit(page: Page) {
  // SystemSmtpCard renders inline on /admin/notifications as a plain <div>
  // (not an antd Card). Find the title text "System SMTP" then click the
  // sibling Edit button to open the modal containing the editable form.
  await gotoAndSettle(page, '/admin/notifications');
  const title = page.getByText('System SMTP', { exact: true }).first();
  await title.waitFor({ state: 'visible', timeout: 10_000 });
  await title.scrollIntoViewIfNeeded();
  // The Edit button sits in a Space alongside the title. Scope to its
  // wrapper: nearest ancestor whose textContent has both "System SMTP" and a
  // button. Easiest: locate by role within the surrounding parent div.
  const editBtn = title
    .locator('xpath=ancestor::div[1]/..')
    .getByRole('button', { name: /^Edit$/i });
  if ((await editBtn.count()) > 0) {
    await editBtn.first().click();
    await page.locator('.ant-modal').first().waitFor({ state: 'visible' });
    await page.waitForTimeout(300);
  }
}

async function prepOAuth2Google(page: Page) {
  await gotoAndSettle(page, '/admin/oauth2');
  // Click the "Google" tab if tabs exist.
  const tab = page.getByRole('tab', { name: /Google/i });
  if (await tab.count()) await tab.first().click();
  // Click "Edit" to enter form mode if the page renders read-only by default.
  const editBtn = page.getByRole('button', { name: /Edit/i });
  if ((await editBtn.count()) > 0) await editBtn.first().click();
  await page.waitForTimeout(500);
}

async function prepSamlConfig(page: Page) {
  await gotoAndSettle(page, '/admin/saml');
  const editBtn = page.getByRole('button', { name: /Edit/i });
  if ((await editBtn.count()) > 0) await editBtn.first().click();
  await page.waitForTimeout(500);
}

async function prepEditor(page: Page) {
  await gotoAndSettle(page, '/editor');
  // Pick the seeded datasource if a select exists.
  const dsSelect = page.locator('[data-testid="editor-datasource"]').or(page.locator('.ant-select').first());
  if (await dsSelect.count()) {
    try {
      await dsSelect.first().click();
      const opt = page.locator('.ant-select-item-option').filter({ hasText: /Sample Postgres/i }).first();
      if (await opt.count()) await opt.click();
    } catch { /* ignore — editor may render without a selector */ }
  }
  // Type sample SQL into CodeMirror.
  const cm = page.locator('.cm-content').first();
  if (await cm.count()) {
    await cm.click();
    await page.keyboard.type('SELECT u.id, u.email, COUNT(q.id) AS query_count\nFROM users u\nLEFT JOIN query_requests q ON q.submitted_by = u.id\nGROUP BY u.id, u.email\nORDER BY query_count DESC\nLIMIT 25;', { delay: 1 });
  }
  await page.waitForTimeout(800);
}

async function prepEditorSchedule(page: Page) {
  await prepEditor(page);
  // Click the schedule date picker to open the calendar.
  const picker = page.locator('.ant-picker').first();
  if (await picker.count()) {
    await picker.click();
    await page.waitForTimeout(400);
  }
}

async function prepQueriesList(page: Page) {
  await gotoAndSettle(page, '/queries');
  await page.waitForTimeout(800);
}

let currentUser: 'admin' | 'reviewer' = 'admin';

async function ensureAdminSession(page: Page) {
  if (currentUser === 'admin') return;
  await loginUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
  currentUser = 'admin';
}

async function ensureReviewerSession(page: Page) {
  // Switch session if the current user isn't the reviewer. The reviews queue
  // filters to queries the current user can approve — for the bootstrap admin
  // (ADMIN role) on a plan with REVIEWER-role approvers, the queue is empty.
  // Logging in as the reviewer surfaces the seeded queries. Defaults to the
  // reviewer seedData() provisioned this run; env vars override for SKIP_SEED.
  const reviewerEmail = process.env.REVIEWER_EMAIL ?? REVIEWER_EMAIL;
  const reviewerPassword = process.env.REVIEWER_PASSWORD ?? REVIEWER_PASSWORD;
  if (currentUser === 'reviewer') return;
  await loginUi(page, reviewerEmail, reviewerPassword);
  currentUser = 'reviewer';
}

async function prepReviewsQueue(page: Page) {
  await gotoAndSettle(page, '/reviews');
  // Click "All pending" tab to ensure we see queries regardless of assignment.
  const allPending = page.getByRole('tab', { name: /All pending/i });
  if (await allPending.count()) {
    await allPending.first().click();
    await page.waitForTimeout(500);
  }
  await page.waitForTimeout(800);
}

async function prepReviewsBulk(page: Page) {
  await prepReviewsQueue(page);
  // Use the header "select all" checkbox to select every visible row in one
  // click — avoids the row click hijacking that misroutes per-row clicks to
  // the row's navigation handler.
  const selectAll = page
    .locator('.ant-table-thead .ant-table-selection-column input[type="checkbox"]')
    .first();
  if (await selectAll.count()) {
    await selectAll.check({ force: true }).catch(() => {});
  } else {
    // Fall back: tick the first two row checkboxes by their cell.
    const rowChecks = page.locator(
      '.ant-table-tbody .ant-table-selection-column input[type="checkbox"]',
    );
    const n = await rowChecks.count();
    for (let i = 0; i < Math.min(2, n); i++) {
      await rowChecks.nth(i).check({ force: true }).catch(() => {});
    }
  }
  // Wait for the bulk action bar to appear.
  await page.waitForTimeout(800);
}

async function prepAuditLog(page: Page) {
  await gotoAndSettle(page, '/admin/audit-log');
  await page.waitForTimeout(800);
}

async function prepAiAnalyses(page: Page) {
  await gotoAndSettle(page, '/admin/ai-analyses');
  await page.waitForTimeout(1500); // charts need a beat to render
}

async function prepDriversList(page: Page) {
  await gotoAndSettle(page, '/admin/drivers');
  await page.waitForTimeout(500);
}

async function prepDatasourcesErDiagram(page: Page, datasourceId: string) {
  await gotoAndSettle(page, `/datasources/${datasourceId}/settings`);
  // Wait for the page to load tabs.
  await page.waitForTimeout(500);
  const tab = page.getByRole('tab', { name: /ER Diagram|Entity|Diagram/i }).first();
  if (await tab.count()) {
    await tab.click();
  }
  // Wait for xyflow nodes to appear.
  await page.locator('.react-flow__node, .react-flow').first().waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});
  await page.waitForTimeout(2000);
}

async function prepDatasourceHealth(page: Page) {
  await gotoAndSettle(page, '/admin/datasource-health');
  await page.waitForTimeout(1500); // pool rings + 24h aggregate need a beat
}

async function prepSlackConfig(page: Page) {
  await gotoAndSettle(page, '/admin/slack');
  await page.waitForTimeout(500);
}

async function prepGroupsList(page: Page) {
  await gotoAndSettle(page, '/admin/groups');
  await page.waitForTimeout(500);
}

async function prepEditorTemplates(page: Page) {
  await prepEditor(page);
  // The editor actions slot exposes a "Templates" button (editor.templates_button)
  // next to "Save as template"; open the drawer listing the seeded templates.
  // Non-exact match: AntD prepends the BookOutlined icon's aria-label to the
  // button's accessible name, and "Save as template" (singular) never matches
  // the plural /Templates/.
  const btn = page.getByRole('button', { name: /Templates/ }).first();
  if (await btn.count()) {
    await btn.click();
    await page.locator('.ant-drawer-content').first().waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(800);
  }
}

async function prepRoutingPolicies(page: Page) {
  await gotoAndSettle(page, '/admin/routing-policies');
  await page.waitForTimeout(800);
}

async function prepAccessRequestsQueue(page: Page) {
  await gotoAndSettle(page, '/admin/access-requests');
  await page.waitForTimeout(800);
}

async function prepDatasourcesMasking(page: Page, datasourceId: string) {
  await gotoAndSettle(page, `/datasources/${datasourceId}/settings`);
  await page.waitForTimeout(500);
  const tab = page.getByRole('tab', { name: /Masking/i }).first();
  if (await tab.count()) await tab.click();
  await page.waitForTimeout(800);
}

async function prepDatasourcesRowSecurity(page: Page, datasourceId: string) {
  await gotoAndSettle(page, `/datasources/${datasourceId}/settings`);
  await page.waitForTimeout(500);
  const tab = page.getByRole('tab', { name: /Row security/i }).first();
  if (await tab.count()) await tab.click();
  await page.waitForTimeout(800);
}

// ----------------------- main flow -----------------------

async function main() {
  let seed: { datasourceId: string };
  if (process.env.SKIP_SEED) {
    seed = { datasourceId: process.env.SEEDED_DATASOURCE_ID ?? '' };
    console.log(`[seed] skipped (using SEEDED_DATASOURCE_ID=${seed.datasourceId})`);
  } else {
    seed = await seedData();
  }

  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: VIEWPORT,
    baseURL: BASE,
  });
  const page = await context.newPage();

  await loginUi(page);

  type Target = {
    name: string;
    prep: (p: Page) => Promise<void>;
    darkToo: boolean;
    role?: 'admin' | 'reviewer';
  };
  const targets: Target[] = [
    // Admin pages (light + dark)
    { name: 'users-invite', prep: prepUsersInvite, darkToo: true },
    { name: 'datasources-create', prep: prepDatasourcesCreate, darkToo: true },
    { name: 'review-plans-create', prep: prepReviewPlansCreate, darkToo: true },
    { name: 'review-plans-templates', prep: prepReviewPlansTemplates, darkToo: true },
    { name: 'ai-configs-create', prep: prepAiConfigsCreate, darkToo: true },
    { name: 'notification-channels-create', prep: prepNotificationsCreate, darkToo: true },
    { name: 'system-smtp-edit', prep: prepSystemSmtpEdit, darkToo: true },
    { name: 'oauth2-google', prep: prepOAuth2Google, darkToo: true },
    { name: 'saml-config', prep: prepSamlConfig, darkToo: true },

    // End-user pages (light only)
    { name: 'editor', prep: prepEditor, darkToo: false },
    { name: 'queries-list', prep: prepQueriesList, darkToo: false },
    { name: 'editor-schedule', prep: prepEditorSchedule, darkToo: false },
    { name: 'editor-query-templates', prep: prepEditorTemplates, darkToo: false },

    // v1.1 admin
    { name: 'audit-log', prep: prepAuditLog, darkToo: true },
    { name: 'ai-analyses-dashboard', prep: prepAiAnalyses, darkToo: true },
    { name: 'drivers-list', prep: prepDriversList, darkToo: true },
    {
      name: 'datasources-er-diagram',
      prep: (p) => prepDatasourcesErDiagram(p, seed.datasourceId),
      darkToo: true,
    },

    // v1.2 admin (AF-365 health, AF-362 Slack, AF-353 groups)
    { name: 'datasource-health', prep: prepDatasourceHealth, darkToo: true },
    { name: 'slack-config', prep: prepSlackConfig, darkToo: true },
    { name: 'groups-list', prep: prepGroupsList, darkToo: true },

    // v1.3 admin (AF-379 routing, AF-378 access requests, AF-381 masking, AF-380 row security)
    { name: 'routing-policies', prep: prepRoutingPolicies, darkToo: true },
    { name: 'access-requests-queue', prep: prepAccessRequestsQueue, darkToo: true },
    {
      name: 'datasources-masking',
      prep: (p) => prepDatasourcesMasking(p, seed.datasourceId),
      darkToo: true,
    },
    {
      name: 'datasources-row-security',
      prep: (p) => prepDatasourcesRowSecurity(p, seed.datasourceId),
      darkToo: true,
    },

    // Reviewer-role captures run LAST so we only flip session once.
    { name: 'reviews-queue', prep: prepReviewsQueue, darkToo: false, role: 'reviewer' },
    { name: 'reviews-queue-bulk', prep: prepReviewsBulk, darkToo: false, role: 'reviewer' },
  ];

  const only = process.env.ONLY ? process.env.ONLY.split(',') : null;
  for (const t of targets) {
    if (only && !only.includes(t.name)) continue;
    console.log(`[capture] ${t.name}`);
    try {
      if (t.role === 'reviewer') {
        await ensureReviewerSession(page);
      } else {
        await ensureAdminSession(page);
      }
      await capture(page, t.name, t.prep, { darkToo: t.darkToo });
    } catch (err) {
      console.error(`  FAIL: ${t.name}:`, (err as Error).message);
    }
    await dismissEverything(page);
  }

  await browser.close();
  console.log(`[done] PNGs in ${OUT_DIR}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
