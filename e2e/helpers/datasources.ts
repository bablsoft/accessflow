import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import type { APIRequestContext } from '@playwright/test';

const DEFAULT_API_BASE = 'http://localhost:8080';
const DEFAULT_MAILCRAB_BASE = 'http://localhost:1080';
const INVITE_URL_REGEX = /\/invite\/([A-Za-z0-9_-]+)/;

// Resolve the backend URL the helpers should hit. Mirrors the convention used
// by tests/auth-totp-login.spec.ts so the same E2E_API_BASE override drives
// both call sites.
export function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

function mailcrabBase(): string {
  return process.env.E2E_MAILCRAB_BASE ?? DEFAULT_MAILCRAB_BASE;
}

// Minimal shape returned by `POST /api/v1/datasources` — kept local so the
// e2e package doesn't import from frontend/. Only the fields specs actually
// use are listed; ignore the rest.
export interface CreatedDatasource {
  id: string;
  name: string;
}

export interface CreatePostgresDatasourceOptions {
  /**
   * Override the generated name. Default: `Postgres E2E ${Date.now()}`. A
   * unique suffix is required because the backend enforces a
   * (organization_id, name) uniqueness constraint and the suite runs against
   * a long-lived database between `npm run stack:up` cycles.
   */
  name?: string;
  /**
   * Hostname the backend uses to reach the customer Postgres. Defaults to
   * `postgres` — the service name of the same Postgres container the control
   * plane uses, reachable over the e2e-network bridge.
   */
  host?: string;
  /**
   * Database name. Defaults to `accessflow` (owned by the `accessflow` user
   * after Flyway provisioning).
   */
  databaseName?: string;
  /** JDBC username. Defaults to `accessflow`. */
  username?: string;
  /** JDBC password. Defaults to `accessflow`. */
  password?: string;
  /**
   * Optional review plan id to attach to the datasource. When omitted the
   * datasource has no plan — submitted queries reach PENDING_REVIEW but no
   * reviewer can approve them (DefaultReviewService:189-192 requires a plan
   * for actionability). Specs that drive approval MUST set this.
   */
  reviewPlanId?: string;
  /**
   * Enable AI analysis on the datasource. Defaults to false because the
   * stack does not normally have an AI provider wired in. AF-347 enables
   * this in concert with `aiConfigId` to run the AI listener against the
   * mock-ai WireMock container.
   */
  aiAnalysisEnabled?: boolean;
  /**
   * Optional ai_config UUID to bind. Required when `aiAnalysisEnabled` is true.
   */
  aiConfigId?: string;
  /**
   * Enable text-to-SQL generation (AF-335) on the datasource. Defaults to
   * false. Requires `aiConfigId` to be set — the editor only renders the
   * "Describe your query" bar when both the flag and a bound AI config are
   * present. Used by the screenshot seed to showcase the feature.
   */
  textToSqlEnabled?: boolean;
}

// POST /api/v1/auth/login → returns the access token. Mirrors the inline
// login helper at tests/auth-totp-login.spec.ts:12-22 so future specs can
// reach for one shared implementation.
export async function loginViaApi(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<string> {
  const res = await request.post(`${apiBase()}/api/v1/auth/login`, {
    data: { email, password },
  });
  if (!res.ok()) {
    throw new Error(`Login failed: ${res.status()} ${await res.text()}`);
  }
  const json = (await res.json()) as { access_token?: string };
  if (!json.access_token) {
    throw new Error(`Login response missing access_token: ${JSON.stringify(json)}`);
  }
  return json.access_token;
}

// SHA-256 of a file on disk, hex-encoded. Used by the AF-274 custom-driver
// upload spec to compute the `expected_sha256` form field at runtime so the
// committed fixture JAR can be regenerated without touching test constants.
export async function sha256OfFile(path: string): Promise<string> {
  const bytes = await readFile(path);
  return createHash('sha256').update(bytes).digest('hex');
}

export interface CreatedCustomDriver {
  id: string;
  jar_sha256: string;
  vendor_name: string;
}

export interface UploadCustomDriverOptions {
  jarPath: string;
  vendorName: string;
  driverClass: string;
  targetDbType?: 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE' | 'MSSQL' | 'CUSTOM';
  /**
   * Override the SHA-256 that gets sent as `expected_sha256`. Default: the
   * real SHA of the JAR file, so the backend accepts the upload. The AF-274
   * spec only needs the override for the invalid-JAR path (which it drives
   * through the UI), so this knob is here for symmetry rather than current
   * use.
   */
  expectedSha256?: string;
}

// POST /api/v1/datasources/drivers (multipart). Mirrors the
// CustomJdbcDriverController contract: jar + vendor_name + target_db_type +
// driver_class + expected_sha256. The spec calls this for arrangement (the
// in-use guard test) so it can skip the upload modal and go straight to the
// delete-while-bound assertion.
export async function uploadCustomDriverViaApi(
  request: APIRequestContext,
  accessToken: string,
  opts: UploadCustomDriverOptions,
): Promise<CreatedCustomDriver> {
  const jarBytes = await readFile(opts.jarPath);
  const sha = opts.expectedSha256 ?? createHash('sha256').update(jarBytes).digest('hex');
  const filename = opts.jarPath.split('/').pop() ?? 'driver.jar';
  const res = await request.post(`${apiBase()}/api/v1/datasources/drivers`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    multipart: {
      jar: {
        name: filename,
        mimeType: 'application/java-archive',
        buffer: jarBytes,
      },
      vendor_name: opts.vendorName,
      target_db_type: opts.targetDbType ?? 'CUSTOM',
      driver_class: opts.driverClass,
      expected_sha256: sha,
    },
  });
  if (!res.ok()) {
    throw new Error(
      `Upload custom driver failed: ${res.status()} ${await res.text()}`,
    );
  }
  return (await res.json()) as CreatedCustomDriver;
}

// DELETE /api/v1/datasources/drivers/{id} — best-effort `afterAll` cleanup,
// same shape as deleteDatasource(). Logs a warning on non-204 so a stack
// that was already torn down (or a driver a previous run never created)
// doesn't fail the suite. 409 is treated as a real failure (something else
// kept the driver bound) and is logged loudly.
export async function deleteCustomDriverViaApi(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.delete(
    `${apiBase()}/api/v1/datasources/drivers/${id}`,
    { headers: { Authorization: `Bearer ${accessToken}` } },
  );
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(
      `Custom driver cleanup (${id}) returned ${res.status()}: ${await res.text()}`,
    );
  }
}

export interface CreateCustomDatasourceOptions {
  /** Override the generated name. Required by uniqueness constraint on reruns. */
  name?: string;
  /** UUID of an uploaded custom driver, returned by uploadCustomDriverViaApi. */
  customDriverId: string;
  /**
   * JDBC URL. `validateDriverChoice` requires non-blank and the shape
   * `^jdbc:[a-zA-Z][a-zA-Z0-9+\-.]*:.+$`. POST /api/v1/datasources does NOT
   * test connectivity at create time for CUSTOM drivers, so the URL can be
   * a sentinel; the default `jdbc:stub:e2e-af274` is intentionally
   * unroutable.
   */
  jdbcUrlOverride?: string;
}

// POST /api/v1/datasources with db_type=CUSTOM. Bound to the driver passed
// in opts; intentionally points at an unreachable JDBC URL because the
// AF-274 spec only needs the *binding* to exist (so deleting the driver
// returns 409 CUSTOM_DRIVER_IN_USE). validateDriverChoice (server side)
// also requires host/port/database_name to be null for CUSTOM.
export async function createCustomDatasource(
  request: APIRequestContext,
  accessToken: string,
  opts: CreateCustomDatasourceOptions,
): Promise<CreatedDatasource> {
  const body = {
    name: opts.name ?? `Custom E2E ${Date.now()}`,
    db_type: 'CUSTOM',
    host: null,
    port: null,
    database_name: null,
    username: 'e2e',
    password: 'e2e',
    ssl_mode: 'DISABLE',
    ai_analysis_enabled: false,
    ai_config_id: null,
    custom_driver_id: opts.customDriverId,
    jdbc_url_override: opts.jdbcUrlOverride ?? 'jdbc:stub:e2e-af274',
    review_plan_id: null,
  };
  const res = await request.post(`${apiBase()}/api/v1/datasources`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: body,
  });
  if (!res.ok()) {
    throw new Error(
      `Create custom datasource failed: ${res.status()} ${await res.text()}`,
    );
  }
  return (await res.json()) as CreatedDatasource;
}

// POST /api/v1/datasources targeting the compose Postgres. The wizard spec
// (tests/datasource-create-wizard.spec.ts) covers the UI flow; this helper
// exists so specs that just need *a* datasource can skip the wizard.
//
// Defaults match the wizard spec's connection details, including the
// ssl_mode = DISABLE override required by the bare postgres:18 container.
// ai_analysis_enabled defaults to false; AF-347 toggles it on (together with
// aiConfigId) so the AI listener writes real ai_analyses rows against the
// in-stack mock-ai WireMock container.
export async function createPostgresDatasource(
  request: APIRequestContext,
  accessToken: string,
  opts: CreatePostgresDatasourceOptions = {},
): Promise<CreatedDatasource> {
  const body = {
    name: opts.name ?? `Postgres E2E ${Date.now()}`,
    db_type: 'POSTGRESQL',
    host: opts.host ?? 'postgres',
    port: 5432,
    database_name: opts.databaseName ?? 'accessflow',
    username: opts.username ?? 'accessflow',
    password: opts.password ?? 'accessflow',
    ssl_mode: 'DISABLE',
    ai_analysis_enabled: opts.aiAnalysisEnabled ?? false,
    ai_config_id: opts.aiConfigId ?? null,
    text_to_sql_enabled: opts.textToSqlEnabled ?? false,
    custom_driver_id: null,
    review_plan_id: opts.reviewPlanId ?? null,
  };
  const res = await request.post(`${apiBase()}/api/v1/datasources`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: body,
  });
  if (!res.ok()) {
    throw new Error(
      `Create datasource failed: ${res.status()} ${await res.text()}`,
    );
  }
  return (await res.json()) as CreatedDatasource;
}

// GET /api/v1/admin/ai-configs — returns the bootstrap-seeded list (plus
// anything earlier specs created). Used by AF-347 to look up the
// `e2e-mock-openai` config id at runtime without hardcoding UUIDs.
export interface AiConfigSummary {
  id: string;
  name: string;
  provider: string;
}

export async function listAiConfigsViaApi(
  request: APIRequestContext,
  accessToken: string,
): Promise<AiConfigSummary[]> {
  const res = await request.get(`${apiBase()}/api/v1/admin/ai-configs`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok()) {
    throw new Error(`List AI configs failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as AiConfigSummary[];
}

// DELETE /api/v1/datasources/{id} — best-effort `afterAll` cleanup. Logs but
// does not throw on a non-204 so a stack that has already been torn down (or
// a datasource a previous run never created) doesn't fail the entire suite.
export async function deleteDatasource(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/datasources/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(
      `Datasource cleanup (${id}) returned ${res.status()}: ${await res.text()}`,
    );
  }
}

export interface SubmittedQuery {
  id: string;
  status: string;
}

// POST /api/v1/queries — keep the body shape in sync with QueryEditorPage's
// SubmitQueryRequest (datasource_id + sql + optional justification). Used by
// specs that need a query in PENDING_REVIEW without driving the editor UI.
export async function submitQueryViaApi(
  request: APIRequestContext,
  accessToken: string,
  datasourceId: string,
  sql: string,
  justification = 'e2e: helper-submitted query',
  scheduledFor?: string,
): Promise<SubmittedQuery> {
  const body: Record<string, unknown> = {
    datasource_id: datasourceId,
    sql,
    justification,
  };
  if (scheduledFor) body.scheduled_for = scheduledFor;
  const res = await request.post(`${apiBase()}/api/v1/queries`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: body,
  });
  if (!res.ok()) {
    throw new Error(`Submit query failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as SubmittedQuery;
}

// Submit returns PENDING_AI immediately; the AF-307 skip listener flips status
// to PENDING_REVIEW asynchronously when ai_analysis_enabled=false on the
// datasource. Poll GET /api/v1/queries/{id} until the status matches `expected`.
export async function waitForQueryStatus(
  request: APIRequestContext,
  accessToken: string,
  id: string,
  expected: string,
  timeoutMs = 15_000,
): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    const res = await request.get(`${apiBase()}/api/v1/queries/${id}`, {
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

// POST /api/v1/queries/{id}/execute — manually triggers execution of an
// APPROVED query. Returns the post-execution body (status: 'EXECUTED' on
// success, 'FAILED' on a customer-DB error).
export async function executeQueryViaApi(
  request: APIRequestContext,
  accessToken: string,
  queryId: string,
): Promise<{ status: string; rows_affected: number | null; duration_ms: number | null }> {
  const res = await request.post(
    `${apiBase()}/api/v1/queries/${queryId}/execute`,
    {
      headers: { Authorization: `Bearer ${accessToken}` },
    },
  );
  if (!res.ok()) {
    throw new Error(`Execute query failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as {
    status: string;
    rows_affected: number | null;
    duration_ms: number | null;
  };
}

// POST /api/v1/reviews/{queryId}/approve — the comment field is optional on
// the backend (ReviewDecisionRequest), so callers can pass it through or omit.
// The backend enforces that the approver is NOT the submitter (403) so callers
// must hand in a token that belongs to a different user.
export async function approveQueryViaApi(
  request: APIRequestContext,
  accessToken: string,
  queryId: string,
  comment?: string,
): Promise<void> {
  const res = await request.post(
    `${apiBase()}/api/v1/reviews/${queryId}/approve`,
    {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: comment ? { comment } : {},
    },
  );
  if (!res.ok()) {
    throw new Error(`Approve query failed: ${res.status()} ${await res.text()}`);
  }
}

// POST /api/v1/datasources/{id}/permissions — grants a user access to a
// datasource. Requires an ADMIN token. Specs that need a non-admin submitter to
// query a datasource must grant at least can_read first.
export async function grantPermissionViaApi(
  request: APIRequestContext,
  adminAccessToken: string,
  datasourceId: string,
  userId: string,
  opts: { canRead?: boolean; canWrite?: boolean; canDdl?: boolean } = {},
): Promise<void> {
  const res = await request.post(
    `${apiBase()}/api/v1/datasources/${datasourceId}/permissions`,
    {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
      data: {
        user_id: userId,
        can_read: opts.canRead ?? true,
        can_write: opts.canWrite ?? false,
        can_ddl: opts.canDdl ?? false,
      },
    },
  );
  if (!res.ok()) {
    throw new Error(`Grant permission failed: ${res.status()} ${await res.text()}`);
  }
}

export interface CreatedMaskingPolicy {
  id: string;
  column_ref: string;
  strategy: string;
}

// POST /api/v1/datasources/{id}/masking-policies (AF-381) — creates a per-column
// dynamic masking policy. Requires an ADMIN token. `revealToUserIds` lists the
// users who see the unmasked value; everyone else gets the strategy output.
export async function createMaskingPolicyViaApi(
  request: APIRequestContext,
  adminAccessToken: string,
  datasourceId: string,
  opts: {
    columnRef: string;
    strategy: string;
    strategyParams?: Record<string, string>;
    revealToRoles?: string[];
    revealToGroupIds?: string[];
    revealToUserIds?: string[];
    enabled?: boolean;
  },
): Promise<CreatedMaskingPolicy> {
  const res = await request.post(
    `${apiBase()}/api/v1/datasources/${datasourceId}/masking-policies`,
    {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
      data: {
        column_ref: opts.columnRef,
        strategy: opts.strategy,
        strategy_params: opts.strategyParams ?? {},
        reveal_to_roles: opts.revealToRoles ?? [],
        reveal_to_group_ids: opts.revealToGroupIds ?? [],
        reveal_to_user_ids: opts.revealToUserIds ?? [],
        enabled: opts.enabled ?? true,
      },
    },
  );
  if (!res.ok()) {
    throw new Error(`Create masking policy failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as CreatedMaskingPolicy;
}

export interface CreatedRowSecurityPolicy {
  id: string;
  table_name: string;
  column_name: string;
}

// POST /api/v1/datasources/{id}/row-security-policies (AF-380) — creates a
// per-table row-level security predicate. Requires an ADMIN token. The proxy
// injects `<column> <operator> <value>` into matching queries; a VARIABLE value
// (e.g. `:user.region`) resolves per submitter from built-ins or user attributes.
export async function createRowSecurityPolicyViaApi(
  request: APIRequestContext,
  adminAccessToken: string,
  datasourceId: string,
  opts: {
    tableName: string;
    columnName: string;
    operator: string;
    valueType: 'VARIABLE' | 'LITERAL';
    valueExpression: string;
    appliesToRoles?: string[];
    appliesToGroupIds?: string[];
    appliesToUserIds?: string[];
    enabled?: boolean;
  },
): Promise<CreatedRowSecurityPolicy> {
  const res = await request.post(
    `${apiBase()}/api/v1/datasources/${datasourceId}/row-security-policies`,
    {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
      data: {
        table_name: opts.tableName,
        column_name: opts.columnName,
        operator: opts.operator,
        value_type: opts.valueType,
        value_expression: opts.valueExpression,
        applies_to_roles: opts.appliesToRoles ?? [],
        applies_to_group_ids: opts.appliesToGroupIds ?? [],
        applies_to_user_ids: opts.appliesToUserIds ?? [],
        enabled: opts.enabled ?? true,
      },
    },
  );
  if (!res.ok()) {
    throw new Error(`Create row security policy failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as CreatedRowSecurityPolicy;
}

// PUT /api/v1/admin/users/{id} (AF-380) — sets the admin-editable attribute map
// resolvable in row-security predicates as `:user.<key>`. Requires an ADMIN token.
export async function setUserAttributesViaApi(
  request: APIRequestContext,
  adminAccessToken: string,
  userId: string,
  attributes: Record<string, string>,
): Promise<void> {
  const res = await request.put(`${apiBase()}/api/v1/admin/users/${userId}`, {
    headers: { Authorization: `Bearer ${adminAccessToken}` },
    data: { attributes },
  });
  if (!res.ok()) {
    throw new Error(`Set user attributes failed: ${res.status()} ${await res.text()}`);
  }
}

export interface InvitedUser {
  id: string;
  email: string;
  role: string;
}

// POST /api/v1/admin/users/invitations — requires an ADMIN token. The
// invitation token itself is NOT returned in the response (the backend emails
// it via Mailcrab), so callers chain this with waitForInviteToken to extract
// the token, then acceptInvitationViaApi to provision the user.
export async function inviteUserViaApi(
  request: APIRequestContext,
  adminAccessToken: string,
  email: string,
  displayName: string,
  role: 'ADMIN' | 'REVIEWER' | 'ANALYST',
): Promise<InvitedUser> {
  const res = await request.post(
    `${apiBase()}/api/v1/admin/users/invitations`,
    {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
      data: { email, displayName, role },
    },
  );
  if (!res.ok()) {
    throw new Error(`Invite user failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as InvitedUser;
}

// POST /api/v1/auth/invitations/{token}/accept — public endpoint, no auth. The
// backend creates the user with the password as their LOCAL credential.
export async function acceptInvitationViaApi(
  request: APIRequestContext,
  token: string,
  password: string,
  displayName?: string,
): Promise<void> {
  const res = await request.post(
    `${apiBase()}/api/v1/auth/invitations/${token}/accept`,
    {
      data: displayName ? { password, displayName } : { password },
    },
  );
  if (!res.ok()) {
    throw new Error(`Accept invitation failed: ${res.status()} ${await res.text()}`);
  }
}

export interface ReviewPlanApprover {
  role?: 'ADMIN' | 'REVIEWER';
  userId?: string;
  stage: number;
}

export interface CreatedReviewPlan {
  id: string;
  name: string;
}

// POST /api/v1/review-plans — required by approval flows. Without a plan
// attached to the datasource, `listPendingForReviewer` filters the query out
// and `prepareDecision` throws ReviewerNotEligibleException (403). Default
// approver is a single ADMIN at stage 1 with min_approvals_required=1; the
// timeout default mirrors the server-side default for clarity.
export async function createReviewPlanViaApi(
  request: APIRequestContext,
  adminAccessToken: string,
  opts: {
    name?: string;
    approvers?: ReviewPlanApprover[];
    requiresHumanApproval?: boolean;
    minApprovalsRequired?: number;
    approvalTimeoutHours?: number;
  } = {},
): Promise<CreatedReviewPlan> {
  const body = {
    name: opts.name ?? `E2E Review Plan ${Date.now()}`,
    description: 'created by helpers/datasources.ts for e2e',
    requires_ai_review: false,
    requires_human_approval: opts.requiresHumanApproval ?? true,
    min_approvals_required: opts.minApprovalsRequired ?? 1,
    approval_timeout_hours: opts.approvalTimeoutHours ?? 24,
    auto_approve_reads: false,
    notify_channels: [],
    approvers: (opts.approvers ?? [{ role: 'ADMIN', stage: 1 }]).map((a) => ({
      user_id: a.userId ?? null,
      role: a.role ?? null,
      stage: a.stage,
    })),
  };
  const res = await request.post(`${apiBase()}/api/v1/review-plans`, {
    headers: { Authorization: `Bearer ${adminAccessToken}` },
    data: body,
  });
  if (!res.ok()) {
    throw new Error(`Create review plan failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as CreatedReviewPlan;
}

// DELETE /api/v1/review-plans/{id} — best-effort `afterAll` cleanup, same
// shape as deleteDatasource(). Tolerates 404 (already gone) and 409
// (REVIEW_PLAN_IN_USE — happens when a soft-deleted datasource still holds
// the binding row; the next `stack:down -v` clears it). Both are logged as
// warnings rather than thrown so a single bad row doesn't fail the suite.
export async function deleteReviewPlanViaApi(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/review-plans/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok() && res.status() !== 404 && res.status() !== 409) {
    // eslint-disable-next-line no-console
    console.warn(
      `Review plan cleanup (${id}) returned ${res.status()}: ${await res.text()}`,
    );
  }
}

// POST /api/v1/datasources/{id} updates not exposed today, so the spec
// triggers DATASOURCE_UNAVAILABLE by DELETEing the row between approval and
// execute. Kept here so the lifecycle helpers all live in one place.

interface MailcrabSummary {
  id: string;
  to?: Array<{ email?: string } | string>;
}

interface MailcrabMessage extends MailcrabSummary {
  text?: string;
  html?: string;
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

// Drop every captured email so subsequent waitForInviteToken calls don't
// match a previous run's invitation. Safe to call between tests.
export async function purgeMailcrab(request: APIRequestContext): Promise<void> {
  const res = await request.post(`${mailcrabBase()}/api/delete-all`);
  if (!res.ok() && res.status() !== 404) {
    throw new Error(`Mailcrab purge failed: ${res.status()} ${await res.text()}`);
  }
}

// Polls Mailcrab for the most recent email addressed to `recipient` and
// extracts the one-time invitation token from the `/invite/{token}` URL in
// the body. Mirrors the inline implementation in tests/query-detail-cancel.spec.ts
// so future specs can reuse it.
export async function waitForInviteToken(
  request: APIRequestContext,
  recipient: string,
  timeoutMs = 15_000,
): Promise<string> {
  const deadline = Date.now() + timeoutMs;
  let lastError = '';
  while (Date.now() < deadline) {
    const listRes = await request.get(`${mailcrabBase()}/api/messages`);
    if (listRes.ok()) {
      const summaries = (await listRes.json()) as MailcrabSummary[];
      const match = [...summaries]
        .reverse()
        .find((m) => recipientMatches(m, recipient));
      if (match) {
        const detailRes = await request.get(
          `${mailcrabBase()}/api/message/${match.id}`,
        );
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
