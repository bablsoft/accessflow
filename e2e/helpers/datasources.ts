import type { APIRequestContext } from '@playwright/test';

const DEFAULT_API_BASE = 'http://localhost:8080';
const DEFAULT_MAILCRAB_BASE = 'http://localhost:1080';
const INVITE_URL_REGEX = /\/invite\/([A-Za-z0-9_-]+)/;

// Resolve the backend URL the helpers should hit. Mirrors the convention used
// by tests/auth-totp-login.spec.ts so the same E2E_API_BASE override drives
// both call sites.
function apiBase(): string {
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

// POST /api/v1/datasources targeting the compose Postgres. The wizard spec
// (tests/datasource-create-wizard.spec.ts) covers the UI flow; this helper
// exists so specs that just need *a* datasource can skip the wizard.
//
// Defaults match the wizard spec's connection details, including the
// ssl_mode = DISABLE override required by the bare postgres:18 container.
// ai_analysis_enabled is hard-set to false — the e2e stack has no AI
// provider wired in.
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
    ai_analysis_enabled: false,
    ai_config_id: null,
    custom_driver_id: null,
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
): Promise<SubmittedQuery> {
  const res = await request.post(`${apiBase()}/api/v1/queries`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { datasource_id: datasourceId, sql, justification },
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
