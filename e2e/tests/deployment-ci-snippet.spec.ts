import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import {
  apiBase,
  createApiKeyViaApi,
  loginViaApi,
  revokeApiKeyViaApi,
} from '../helpers/datasources';
import {
  createDeploymentEnvironmentViaApi,
  createDeploymentPipelineViaApi,
  deleteDeploymentPipelineViaApi,
  waitForDeploymentStatus,
  type CreatedDeploymentPipeline,
} from '../helpers/deployments';
import { login } from '../helpers/login';
import { activeTabPanel } from '../helpers/ui';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

/**
 * The CI setup panel (#696) as a pasteable contract.
 *
 * CiSnippetPanel.test.tsx already asserts every distinctive line of all four snippets, so this
 * spec deliberately does not re-snapshot them. It covers the two claims a jsdom test cannot make:
 *
 *  1. `apiBaseUrl()` resolves to the real backend at runtime. The panel uses the served
 *     runtime-config value rather than `window.location.origin`, because the SPA origin has no
 *     /api proxy — a snippet built from the origin would POST to the SPA and get index.html.
 *     The unit test asserts apiBaseUrl() against itself, which is tautological.
 *  2. The curl snippet is an executable request. It documents `Authorization: ApiKey …`, a scheme
 *     ApiKeyAuthenticationFilter supports but which no other spec exercises — the deployment
 *     helpers all send `X-API-Key`.
 */
test.describe('deployment CI snippets (#696)', () => {
  const stamp = Date.now();
  const envName = `ci-prod-${stamp}`;

  let adminToken = '';
  let apiKey = '';
  let apiKeyId = '';
  let pipeline: CreatedDeploymentPipeline | null = null;

  test.beforeAll(async ({ request }) => {
    adminToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // GENERIC on purpose: the page header renders the provider label, so a GITHUB_ACTIONS
    // pipeline would put "GitHub Actions" on the page outside the panel too.
    pipeline = await createDeploymentPipelineViaApi(request, adminToken, {
      name: `e2e-ci-696-${stamp}`,
      provider: 'GENERIC',
      aiAnalysisEnabled: false,
    });
    await createDeploymentEnvironmentViaApi(request, adminToken, pipeline.id, {
      name: envName,
      requireReview: false,
    });
    const key = await createApiKeyViaApi(request, adminToken, `af696-ci-snippet-${randomUUID()}`);
    apiKey = key.rawKey;
    apiKeyId = key.id;
  });

  test.afterAll(async ({ request }) => {
    if (apiKeyId) await revokeApiKeyViaApi(request, adminToken, apiKeyId);
    if (pipeline) await deleteDeploymentPipelineViaApi(request, adminToken, pipeline.id);
  });

  test('every platform snippet carries this pipeline id and the runtime API base URL', async ({
    page,
  }) => {
    // The panel renders apiBaseUrl() from the served runtime-config.js, which the e2e frontend
    // image bakes as http://localhost:8080 — the same value apiBase() defaults to. Overriding
    // E2E_API_BASE without rebuilding that image would fail this assertion for a reason unrelated
    // to the panel.
    const base = apiBase();
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/admin/deployment-pipelines/${pipeline!.id}?tab=ci`);
    const panel = activeTabPanel(page);
    const snippet = panel.getByTestId('ci-snippet');

    await expect(snippet).toContainText(`accessflow-url: ${base}`, { timeout: 15_000 });
    await expect(snippet).toContainText(`pipeline-id: ${pipeline!.id}`);

    // Segmented renders zero-size radio inputs behind their labels — click the label text.
    // Scoped through the active panel because AntD keeps the six other tab panes mounted.
    const platforms = panel.getByRole('radiogroup');

    await platforms.getByText('GitLab CI', { exact: true }).click();
    await expect(snippet).toContainText(`ACCESSFLOW_ENDPOINT: "${base}"`);
    await expect(snippet).toContainText(`AF_PIPELINE_ID: "${pipeline!.id}"`);

    await platforms.getByText('Azure Pipelines', { exact: true }).click();
    await expect(snippet).toContainText(`accessflowUrl: "${base}"`);
    await expect(snippet).toContainText(`pipelineId: "${pipeline!.id}"`);

    await platforms.getByText('curl', { exact: true }).click();
    await expect(snippet).toContainText(`${base}/api/v1/deployment-requests`);
    await expect(snippet).toContainText(`"pipeline_id": "${pipeline!.id}"`);
    await expect(snippet).toContainText(`${base}/api/v1/deployment-gate?request_id=`);

    // Presence only. The copyable text is the same string the <pre> renders, so clicking it would
    // add clipboard permissions and headless flakiness without adding an assertion.
    await expect(panel.getByRole('button', { name: /copy/i })).toBeVisible();
  });

  test('the curl snippet is a working request against the live backend', async ({ request }) => {
    // Exactly what the snippet documents: the ApiKey authorization scheme, the CI header, and the
    // snake_case body — re-issued so a stale snippet fails here rather than in someone's pipeline.
    const submitted = await request.post(`${apiBase()}/api/v1/deployment-requests`, {
      headers: {
        Authorization: `ApiKey ${apiKey}`,
        'X-AccessFlow-CI': 'true',
      },
      data: {
        pipeline_id: pipeline!.id,
        environment: envName,
        version: `9.9.9-${stamp}`,
        external_run_id: `snippet-${stamp}`,
        commit_sha: 'deadbeef',
      },
    });
    expect(submitted.status(), await submitted.text()).toBe(202);
    const requestId = ((await submitted.json()) as { id: string }).id;

    // The POST returns 202 with the row still PENDING_AI: reaching APPROVED takes two module
    // listener hops (analysis skipped → state machine). Poll like every other deploygov spec
    // rather than racing them.
    await waitForDeploymentStatus(request, adminToken, requestId, 'APPROVED');

    const gate = await request.get(
      `${apiBase()}/api/v1/deployment-gate?request_id=${encodeURIComponent(requestId)}`,
      { headers: { Authorization: `ApiKey ${apiKey}` } },
    );
    expect(gate.status()).toBe(200);
    const body = (await gate.json()) as { status: string; releasable: boolean; frozen: boolean };
    // The environment skips review, so this one is genuinely releasable — which also proves the
    // gate answered on its merits rather than falling closed on an auth or lookup failure.
    expect(body.status).toBe('APPROVED');
    expect(body.releasable).toBe(true);
    expect(body.frozen).toBe(false);
  });
});
