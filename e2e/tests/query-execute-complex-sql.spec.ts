import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  acceptInvitationViaApi,
  apiBase,
  approveQueryViaApi,
  createPostgresDatasource,
  createReviewPlanViaApi,
  deleteDatasource,
  executeQueryViaApi,
  inviteUserViaApi,
  loginViaApi,
  purgeMailcrab,
  submitQueryViaApi,
  waitForInviteToken,
  waitForQueryStatus,
  type CreatedDatasource,
  type CreatedReviewPlan,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const APPROVER_PASSWORD = 'Approver-Pwd!123';

// A per-run scratch table so repeated `npm test` cycles against the same
// long-lived Postgres never collide on the (schema, table) namespace. hex-only
// suffix — a bare UUID contains '-' which isn't a legal unquoted identifier.
const TABLE = `af595_${randomUUID().replace(/-/g, '')}`;
const FUNC = `${TABLE}_dbl`;

async function loginViaUi(
  page: Page,
  email: string,
  password: string,
): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Two contexts / logins per UI case plus a handful of API round-trips push the
// slower tests past the default 30s budget; mirror query-execute.spec.ts.
test.describe.configure({ timeout: 90_000 });

test.describe.serial('complex SQL execution against Postgres (AF-595)', () => {
  let adminAccessToken = '';
  let approverEmail = '';
  let approverAccessToken = '';
  let reviewPlan: CreatedReviewPlan | null = null;
  let datasource: CreatedDatasource | null = null;

  // Submit → wait PENDING_REVIEW → approve (as the second user, since
  // self-approval is blocked) → execute. Returns the post-execution body so
  // callers can assert the terminal status and affected-row count. Execute
  // returns HTTP 202 with status EXECUTED or FAILED — never throws for a
  // customer-DB error, so a FAILED case still resolves here.
  async function runApproved(
    request: APIRequestContext,
    sql: string,
  ): Promise<{ id: string; status: string; rowsAffected: number | null }> {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      sql,
      'AF-595 complex SQL execution',
    );
    await waitForQueryStatus(
      request,
      adminAccessToken,
      submitted.id,
      'PENDING_REVIEW',
    );
    await approveQueryViaApi(request, approverAccessToken, submitted.id);
    const exec = await executeQueryViaApi(request, adminAccessToken, submitted.id);
    return { id: submitted.id, status: exec.status, rowsAffected: exec.rows_affected };
  }

  interface StoredResults {
    columns: Array<{ name: string; type: string; restricted: boolean }>;
    rows: unknown[][];
    row_count: number;
  }

  // GET /queries/{id}/results — the paginated SELECT snapshot the Results table
  // renders from. Only populated after a successful EXECUTED SELECT.
  async function readResults(
    request: APIRequestContext,
    id: string,
  ): Promise<StoredResults> {
    const res = await request.get(
      `${apiBase()}/api/v1/queries/${id}/results?page=0&size=100`,
      { headers: { Authorization: `Bearer ${adminAccessToken}` } },
    );
    if (!res.ok()) {
      throw new Error(`Read results failed: ${res.status()} ${await res.text()}`);
    }
    return (await res.json()) as StoredResults;
  }

  // Raw POST /queries so the parse/validation cases can assert the 422 envelope
  // directly (submitQueryViaApi throws on non-2xx, which would hide the body).
  async function submitRaw(request: APIRequestContext, sql: string) {
    if (!datasource) throw new Error('datasource not created in beforeAll');
    return request.post(`${apiBase()}/api/v1/queries`, {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
      data: { datasource_id: datasource.id, sql, justification: 'AF-595 rejection' },
    });
  }

  async function readQuery(request: APIRequestContext, id: string) {
    const res = await request.get(`${apiBase()}/api/v1/queries/${id}`, {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
    });
    if (!res.ok()) {
      throw new Error(`Read query failed: ${res.status()} ${await res.text()}`);
    }
    return (await res.json()) as { status: string; error_message: string | null };
  }

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);

    // A second user with reviewer authority — self-approval is rejected, so the
    // approver MUST differ from the admin submitter. Fresh per run so re-runs
    // don't trip the (email) uniqueness constraint.
    approverEmail = `approver-${randomUUID()}@e2e.local`;
    await purgeMailcrab(request);
    await inviteUserViaApi(
      request,
      adminAccessToken,
      approverEmail,
      'AF-595 Approver',
      'ADMIN',
    );
    const inviteToken = await waitForInviteToken(request, approverEmail);
    await acceptInvitationViaApi(
      request,
      inviteToken,
      APPROVER_PASSWORD,
      'AF-595 Approver',
    );
    approverAccessToken = await loginViaApi(
      request,
      approverEmail,
      APPROVER_PASSWORD,
    );

    reviewPlan = await createReviewPlanViaApi(request, adminAccessToken, {
      name: `E2E Review Plan AF595 ${Date.now()}`,
      approvers: [{ role: 'ADMIN', stage: 1 }],
      minApprovalsRequired: 1,
    });

    datasource = await createPostgresDatasource(request, adminAccessToken, {
      name: `Postgres E2E AF595 ${Date.now()}`,
      reviewPlanId: reviewPlan.id,
    });

    // Scratch table for the whole suite. The admin submitter bypasses the
    // per-datasource capability check (DefaultQuerySubmissionService:65), so
    // DDL / DML flow through submit → approve → execute like any other query.
    const created = await runApproved(
      request,
      `CREATE TABLE ${TABLE} (id integer PRIMARY KEY, name text NOT NULL, qty integer NOT NULL DEFAULT 0)`,
    );
    expect(created.status).toBe('EXECUTED');
  });

  test.afterAll(async ({ request }) => {
    // Best-effort teardown — uniquely-named scratch objects don't break re-runs
    // if a mid-suite failure leaves them behind, but keep the DB tidy anyway.
    try {
      await runApproved(request, `DROP TABLE IF EXISTS ${TABLE}`);
      await runApproved(request, `DROP FUNCTION IF EXISTS ${FUNC}(integer)`);
    } catch {
      // ignore — the datasource delete below and `stack:down -v` are the backstop
    }
    if (datasource) {
      await deleteDatasource(request, adminAccessToken, datasource.id);
    }
  });

  // ── 1. Multi-row INSERT → EXECUTED, affected-row count rendered (UI) ─────────
  test('multi-row INSERT executes and renders the affected-row count', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      `INSERT INTO ${TABLE} (id, name, qty) VALUES (1, 'alpha', 10), (2, 'bravo', 20), (3, 'charlie', 30)`,
      'AF-595 multi-row insert',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Approved'),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Execute now' }).click();

    await expect(page.getByText('Query executed')).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Executed'),
    ).toBeVisible({ timeout: 15_000 });

    // Execution result card (INSERT has no Results table — only the affected
    // count). The "rows affected" label's immediate sibling holds the value.
    await expect(page.getByText('Execution result')).toBeVisible({ timeout: 10_000 });
    await expect(
      page
        .getByText('rows affected', { exact: true })
        .locator('xpath=following-sibling::div'),
    ).toHaveText('3');
  });

  // ── 2. BEGIN; … COMMIT; DML batch executes atomically (API) ──────────────────
  test('transactional BEGIN…COMMIT batch commits all statements atomically', async ({
    request,
  }) => {
    const batch = await runApproved(
      request,
      `BEGIN; INSERT INTO ${TABLE} (id, name, qty) VALUES (100, 'x', 1), (101, 'y', 2); UPDATE ${TABLE} SET qty = 50 WHERE id = 100; DELETE FROM ${TABLE} WHERE id = 101; COMMIT;`,
    );
    expect(batch.status).toBe('EXECUTED');

    // Verify the whole batch committed: the INSERT + UPDATE landed and the
    // DELETE removed 101, so exactly one row (id=100, qty=50) survives.
    const verify = await runApproved(
      request,
      `SELECT id, qty FROM ${TABLE} WHERE id IN (100, 101) ORDER BY id`,
    );
    expect(verify.status).toBe('EXECUTED');
    const results = await readResults(request, verify.id);
    expect(results.rows).toEqual([[100, 50]]);
  });

  // ── 3. UPDATE / DELETE with WHERE report their affected-row counts (API) ─────
  test('UPDATE and DELETE with WHERE report affected-row counts', async ({
    request,
  }) => {
    const seed = await runApproved(
      request,
      `INSERT INTO ${TABLE} (id, name, qty) VALUES (200, 'd', 1), (201, 'e', 2), (202, 'f', 3), (203, 'g', 4)`,
    );
    expect(seed.status).toBe('EXECUTED');
    expect(seed.rowsAffected).toBe(4);

    const updated = await runApproved(
      request,
      `UPDATE ${TABLE} SET qty = qty + 1 WHERE id IN (200, 201)`,
    );
    expect(updated.status).toBe('EXECUTED');
    expect(updated.rowsAffected).toBe(2);

    const deleted = await runApproved(
      request,
      `DELETE FROM ${TABLE} WHERE id = 203`,
    );
    expect(deleted.status).toBe('EXECUTED');
    expect(deleted.rowsAffected).toBe(1);
  });

  // ── 4. Stored function via SELECT executes; CALL is a rejected query type ────
  test('a stored function executes via SELECT while CALL is rejected 422', async ({
    request,
  }) => {
    const fn = await runApproved(
      request,
      `CREATE FUNCTION ${FUNC}(integer) RETURNS integer AS 'SELECT $1 * 2' LANGUAGE sql`,
    );
    expect(fn.status).toBe('EXECUTED');

    const called = await runApproved(request, `SELECT ${FUNC}(21) AS doubled`);
    expect(called.status).toBe('EXECUTED');
    const results = await readResults(request, called.id);
    expect(results.rows).toEqual([[42]]);

    // CALL statements classify as an unsupported query type and are rejected at
    // submission (SqlParserServiceImpl → QueryType.OTHER). Documented gap — not
    // widened here; asserted so a future CALL parser lands with test coverage.
    const rejected = await submitRaw(request, `CALL ${FUNC}(21)`);
    expect(rejected.status()).toBe(422);
    const body = (await rejected.json()) as { error?: string };
    expect(body.error).toBe('INVALID_SQL');
  });

  // ── 5. CTE + JOIN SELECT → EXECUTED, multi-column results table renders (UI) ──
  test('CTE join SELECT renders a multi-column results table', async ({
    page,
    request,
  }) => {
    if (!datasource) throw new Error('datasource not created in beforeAll');

    const seed = await runApproved(
      request,
      `INSERT INTO ${TABLE} (id, name, qty) VALUES (300, 'p', 7), (301, 'q', 8), (302, 'r', 9)`,
    );
    expect(seed.status).toBe('EXECUTED');

    const submitted = await submitQueryViaApi(
      request,
      adminAccessToken,
      datasource.id,
      `WITH picked AS (SELECT id, qty FROM ${TABLE} WHERE id BETWEEN 300 AND 302) SELECT p.id, s.name, p.qty FROM picked p JOIN ${TABLE} s ON s.id = p.id ORDER BY p.id`,
      'AF-595 CTE join select',
    );
    await waitForQueryStatus(request, adminAccessToken, submitted.id, 'PENDING_REVIEW');
    await approveQueryViaApi(request, approverAccessToken, submitted.id);

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/queries/${submitted.id}`);
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Approved'),
    ).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: 'Execute now' }).click();
    await expect(page.getByText('Query executed')).toBeVisible({ timeout: 15_000 });
    await expect(
      page.getByRole('heading', { level: 1 }).getByText('Executed'),
    ).toBeVisible({ timeout: 15_000 });

    // Results card renders for EXECUTED SELECT. Three projected columns
    // (id, name, qty) → 3 header cells; three source rows → 3 body rows.
    await expect(
      page.getByText('Results', { exact: true }).first(),
    ).toBeVisible({ timeout: 10_000 });
    await expect(
      page.locator('.ant-table-thead th.ant-table-cell'),
    ).toHaveCount(3, { timeout: 10_000 });
    await expect(
      page.locator('.ant-table-tbody tr.ant-table-row'),
    ).toHaveCount(3);
    const firstRow = await page
      .locator('.ant-table-tbody tr.ant-table-row')
      .first()
      .locator('td.ant-table-cell')
      .allInnerTexts();
    expect(firstRow).toEqual(['300', 'p', '7']);
  });

  // ── 6. Execution-time failures land in FAILED with the DB error surfaced ─────
  test('constraint violation and divide-by-zero land in FAILED with an error', async ({
    request,
  }) => {
    // A PK already occupied by test 1 (id=1) → duplicate-key violation at
    // execution time, not parse time. The lifecycle service catches the
    // SQLException and marks the query FAILED (HTTP 202, no 500, no stuck row).
    const dup = await runApproved(
      request,
      `INSERT INTO ${TABLE} (id, name, qty) VALUES (1, 'dup', 0)`,
    );
    expect(dup.status).toBe('FAILED');
    const dupQuery = await readQuery(request, dup.id);
    expect(dupQuery.status).toBe('FAILED');
    expect(dupQuery.error_message?.trim()).toBeTruthy();

    // A second, non-constraint failure shape: division by zero.
    const divZero = await runApproved(request, `SELECT 1 / 0 AS boom`);
    expect(divZero.status).toBe('FAILED');
    const divQuery = await readQuery(request, divZero.id);
    expect(divQuery.error_message?.trim()).toBeTruthy();
  });

  // ── 7. Parse / validation rejections return HTTP 422 (API) ───────────────────
  test('unparseable and multi-statement SQL are rejected with 422', async ({
    request,
  }) => {
    const unparseable = await submitRaw(request, `SELCT * FROM ${TABLE}`);
    expect(unparseable.status()).toBe(422);
    const unparseableBody = (await unparseable.json()) as {
      error?: string;
      detail?: string;
    };
    expect(unparseableBody.error).toBe('INVALID_SQL');
    expect(unparseableBody.detail).toContain('Failed to parse SQL');

    // Disallowed multi-statement input — the BEGIN;…COMMIT; envelope is the
    // only multi-statement form the proxy accepts (and only for homogeneous
    // DML). A bare `SELECT …; DROP …;` is rejected.
    const multi = await submitRaw(request, `SELECT 1; DROP TABLE ${TABLE};`);
    expect(multi.status()).toBe(422);
    const multiBody = (await multi.json()) as { error?: string; detail?: string };
    expect(multiBody.error).toBe('INVALID_SQL');
    expect(multiBody.detail).toContain('Multiple SQL statements');
  });
});
