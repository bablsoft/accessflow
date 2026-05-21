import type { APIRequestContext } from '@playwright/test';

const DEFAULT_API_BASE = 'http://localhost:8080';

// Resolve the backend URL the helpers should hit. Mirrors the convention used
// by tests/auth-totp-login.spec.ts so the same E2E_API_BASE override drives
// both call sites.
function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
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
