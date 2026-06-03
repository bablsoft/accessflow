import { test, expect, type APIRequestContext, type Page, type Route } from '@playwright/test';
import { deleteDatasource, loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Shared suffix keeps names unique across reruns against a long-lived database
// (the e2e stack reuses postgres data between `stack:up` cycles when teardown
// doesn't run with `-v`). Same pattern as AF-275 / AF-276.
const UNIQUE_SUFFIX = `af277-${Date.now()}`;
const PRIMARY_NAME = `e2e-ollama-${UNIQUE_SUFFIX}`;
const ANTHROPIC_NAME = `e2e-anthropic-${UNIQUE_SUFFIX}`;
const OPENAI_NAME = `e2e-openai-${UNIQUE_SUFFIX}`;
const COMPAT_NAME = `e2e-compat-${UNIQUE_SUFFIX}`;
const IN_USE_NAME = `e2e-inuse-${UNIQUE_SUFFIX}`;
const DUPLICATE_NAME = `e2e-dupe-${UNIQUE_SUFFIX}`;
const BOUND_DS_NAME = `e2e-ds-bound-${UNIQUE_SUFFIX}`;

const DEFAULT_API_BASE = 'http://localhost:8080';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/admin/ai-configs that AiConfigListPage issues on
// mount, so the Table (or EmptyState) renders real content before we drive
// the UI. Same gate pattern as AF-275's waitForUsersListReady.
async function waitForAiConfigsListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/ai-configs(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

// Install a one-shot stub for POST /api/v1/admin/ai-configs/{id}/test that
// returns the configured payload as HTTP 200. The backend test endpoint
// always returns 200 (status='OK' | 'ERROR' lives in the body), so we mirror
// that contract here. Returns the Playwright unroute key for cleanup.
async function stubTestEndpoint(
  page: Page,
  payload: { status: 'OK' | 'ERROR'; detail: string },
): Promise<void> {
  await page.route('**/api/v1/admin/ai-configs/*/test', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(payload),
    });
  });
}

interface CreatedAiConfig {
  id: string;
  name: string;
}

async function createAiConfigViaApi(
  request: APIRequestContext,
  accessToken: string,
  body: {
    name: string;
    provider: 'OLLAMA' | 'OPENAI' | 'ANTHROPIC' | 'OPENAI_COMPATIBLE';
    model: string;
    endpoint?: string | null;
    api_key?: string | null;
    timeout_ms?: number;
    max_prompt_tokens?: number;
    max_completion_tokens?: number;
  },
): Promise<CreatedAiConfig> {
  const res = await request.post(`${apiBase()}/api/v1/admin/ai-configs`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: {
      timeout_ms: 30_000,
      max_prompt_tokens: 8_000,
      max_completion_tokens: 2_000,
      ...body,
    },
  });
  if (!res.ok()) {
    throw new Error(`Create AI config failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as CreatedAiConfig;
}

async function deleteAiConfigViaApi(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/admin/ai-configs/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(
      `AI config cleanup (${id}) returned ${res.status()}: ${await res.text()}`,
    );
  }
}

// Inline POST /api/v1/datasources binding a Postgres datasource to an AI
// config. createPostgresDatasource() in helpers/datasources.ts hardcodes
// ai_config_id=null so it can't drive this scenario; the test 9 in-use
// guard is the only call site, so a one-off inline body is preferable to
// extending the shared helper.
async function createDatasourceBoundToAiConfig(
  request: APIRequestContext,
  accessToken: string,
  aiConfigId: string,
  name: string,
): Promise<{ id: string; name: string }> {
  const res = await request.post(`${apiBase()}/api/v1/datasources`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: {
      name,
      db_type: 'POSTGRESQL',
      host: 'postgres',
      port: 5432,
      database_name: 'accessflow',
      username: 'accessflow',
      password: 'accessflow',
      ssl_mode: 'DISABLE',
      ai_analysis_enabled: true,
      ai_config_id: aiConfigId,
      custom_driver_id: null,
      review_plan_id: null,
    },
  });
  if (!res.ok()) {
    throw new Error(
      `Create datasource bound to ai_config failed: ${res.status()} ${await res.text()}`,
    );
  }
  return (await res.json()) as { id: string; name: string };
}

// Open the wizard fresh — the wizard tracks step state in component memory,
// so a reload guarantees Step 1 (provider tile picker) is showing.
async function openWizardFresh(page: Page): Promise<void> {
  await page.goto('/admin/ai-configs/new');
  await expect(page.getByRole('heading', { name: 'New AI configuration' })).toBeVisible({
    timeout: 10_000,
  });
}

// AF-277 covers the /admin/ai-configs CRUD + test flows:
//   1. Empty-state assertion (skipped when previous runs left rows).
//   2. Create Ollama via wizard → list shows it.
//   3. Create Anthropic + OpenAI via wizard (parametrized) — exercises the
//      needs_api_key=true path and the hidden-endpoint path.
//   4. Edit primary → update name → list reflects new name.
//   5. Edit-page Test button → success then error (mocked /test).
//   6. Row "Test" action → success toast (mocked OK).
//   7. Row "Test" action → error toast with detail (mocked ERROR).
//   8. Row "Delete" → row removed.
//   9. Delete in-use config → modal lists bound datasources (high-value guard).
//  10. Create with duplicate name → 409 → error toast.
//  11. Create Custom (OpenAI-compatible) via wizard — endpoint required + keyless.
//
// describe.serial because the primary Ollama config walks through tests
// 2 → 4 → 5 → 6 → 7 → 8 (create → edit → test ok → test ok via list →
// test error → delete). The Playwright project is already workers=1, so
// this is mostly belt-and-braces.
test.describe.serial('/admin/ai-configs — wizard, list, edit, test, delete', () => {
  let adminAccessToken = '';
  let primaryAiConfigId: string | null = null;
  let primaryDisplayName = PRIMARY_NAME; // becomes `${PRIMARY_NAME}-renamed` in test 4
  const secondaryAiConfigIds: string[] = []; // anthropic + openai
  let inUseAiConfigId: string | null = null;
  let boundDatasourceId: string | null = null;
  let duplicateAiConfigId: string | null = null;
  let compatAiConfigId: string | null = null;

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    // Delete the bound datasource first — DELETE /datasources is a SOFT delete
    // (sets is_active=false) so the binding row remains. That means the in-use
    // AI config cleanup below will log a 409 warning; the orphan is wiped by
    // the next `stack:down -v`, so accept the noise rather than plumb an
    // unbind-then-delete dance that future-AccessFlow may break.
    if (boundDatasourceId) {
      await deleteDatasource(request, adminAccessToken, boundDatasourceId);
      boundDatasourceId = null;
    }
    const allIds = [
      primaryAiConfigId, // already deleted by test 8 in the happy path
      ...secondaryAiConfigIds,
      inUseAiConfigId,
      duplicateAiConfigId,
      compatAiConfigId,
    ].filter((id): id is string => Boolean(id));
    for (const id of allIds) {
      await deleteAiConfigViaApi(request, adminAccessToken, id);
    }
  });

  test('1) empty state renders on a fresh stack with zero configs', async ({
    page,
    request,
  }) => {
    // Skip when prior runs left rows behind — the e2e stack persists Postgres
    // between `stack:up` cycles unless torn down with `-v`, so the empty
    // assertion only holds the first time.
    const listRes = await request.get(`${apiBase()}/api/v1/admin/ai-configs`, {
      headers: { Authorization: `Bearer ${adminAccessToken}` },
    });
    expect(listRes.status()).toBe(200);
    const existing = (await listRes.json()) as Array<unknown>;
    test.skip(
      existing.length > 0,
      `Stack already has ${existing.length} AI configurations; empty-state assertion is only valid on a fresh database`,
    );

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    await expect(
      page.getByRole('heading', { name: 'AI configurations' }),
    ).toBeVisible();
    // EmptyState renders title + description as plain <div>s, not headings.
    await expect(
      page.getByText('No AI configurations yet', { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(
        'Add an AI configuration to enable risk analysis on a datasource.',
      ),
    ).toBeVisible();
    // The empty state and the page header both render an "Add AI
    // configuration" button — at least one must be present.
    await expect(
      page.getByRole('button', { name: 'Add AI configuration' }).first(),
    ).toBeVisible();
  });

  test('2) create Ollama config via wizard → list shows it', async ({ page }) => {
    await stubTestEndpoint(page, {
      status: 'OK',
      detail: 'AI provider responded with risk_level=LOW',
    });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await openWizardFresh(page);

    // Step 1 — pick Ollama. Provider tiles are <button type="button"> rendering
    // a bold label ("Ollama") and a muted description ("Self-hosted local models").
    await page.getByRole('button', { name: /Ollama/ }).click();

    // Step 2 — connection form. Ollama is needs_api_key=false, but the field
    // is still rendered (optional). Defaults are pre-filled: model=llama3.1:70b,
    // endpoint=http://localhost:11434/api.
    await expect(page.getByLabel('Configuration name')).toBeVisible();
    await expect(page.getByLabel('API key')).toBeVisible();
    await expect(page.getByLabel('API endpoint')).toBeVisible();
    await page.getByLabel('Configuration name').fill(PRIMARY_NAME);
    await expect(page.getByLabel('Model')).toHaveValue('llama3.1:70b');
    await expect(page.getByLabel('API endpoint')).toHaveValue(
      'http://localhost:11434/api',
    );

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/ai-configs$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save and continue' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as {
      id: string;
      name: string;
      provider: string;
      model: string;
    };
    primaryAiConfigId = body.id;
    expect(body.name).toBe(PRIMARY_NAME);
    expect(body.provider).toBe('OLLAMA');
    expect(body.model).toBe('llama3.1:70b');

    // Step 3 — Send test prompt. Mocked /test returns status=OK.
    await page.getByRole('button', { name: 'Send test prompt' }).click();
    await expect(page.getByText('Test passed', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(
      page.getByText('AI provider responded with risk_level=LOW'),
    ).toBeVisible();

    // Done → navigate back to the list.
    await page.getByRole('button', { name: 'Done' }).click();
    await page.waitForURL('**/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    const primaryRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(PRIMARY_NAME)),
    });
    await expect(primaryRow).toBeVisible();
    await expect(primaryRow.getByText('Ollama', { exact: true })).toBeVisible();
    await expect(primaryRow.getByText('llama3.1:70b', { exact: true })).toBeVisible();
    await expect(primaryRow.getByText('0 datasources', { exact: true })).toBeVisible();

    await page.unroute('**/api/v1/admin/ai-configs/*/test');
  });

  test('3) create Anthropic + OpenAI configs via wizard (needs_api_key path)', async ({
    page,
  }) => {
    const providers: Array<{
      tile: 'Anthropic' | 'OpenAI';
      providerEnum: 'ANTHROPIC' | 'OPENAI';
      defaultModel: string;
      configName: string;
    }> = [
      {
        tile: 'Anthropic',
        providerEnum: 'ANTHROPIC',
        defaultModel: 'claude-sonnet-4-20250514',
        configName: ANTHROPIC_NAME,
      },
      {
        tile: 'OpenAI',
        providerEnum: 'OPENAI',
        defaultModel: 'gpt-4o',
        configName: OPENAI_NAME,
      },
    ];

    for (const provider of providers) {
      await stubTestEndpoint(page, {
        status: 'OK',
        detail: `AI provider responded with risk_level=LOW (${provider.providerEnum})`,
      });

      await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
      await openWizardFresh(page);

      // Step 1 — pick the provider. Anthropic / OpenAI tiles are unique by
      // their label text; the description disambiguates from anything else
      // matching "Anthropic" elsewhere on the page (there is none).
      await page.getByRole('button', { name: new RegExp(provider.tile) }).click();

      // Step 2 — the API endpoint field MUST be hidden for non-Ollama
      // providers (no defaultEndpoint → field not rendered).
      await expect(page.getByLabel('API endpoint')).toHaveCount(0);
      // API key field must be present and required.
      await expect(page.getByLabel('API key')).toBeVisible();
      await expect(page.getByLabel('Model')).toHaveValue(provider.defaultModel);

      // Submit with blank API key → AntD validation rejects without firing the
      // POST. The validation message comes from admin.ai_configs.api_key_required.
      await page.getByLabel('Configuration name').fill(provider.configName);
      await page.getByRole('button', { name: 'Save and continue' }).click();
      await expect(
        page.getByText('API key is required for this provider'),
      ).toBeVisible({ timeout: 10_000 });

      // Fill the API key and submit for real.
      await page.getByLabel('API key').fill(`test-key-${UNIQUE_SUFFIX}`);
      const createResponsePromise = page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          /\/api\/v1\/admin\/ai-configs$/.test(r.url()),
        { timeout: 15_000 },
      );
      await page.getByRole('button', { name: 'Save and continue' }).click();
      const createResponse = await createResponsePromise;
      expect(createResponse.status()).toBe(201);
      const body = (await createResponse.json()) as {
        id: string;
        provider: string;
        model: string;
        api_key: string | null;
      };
      secondaryAiConfigIds.push(body.id);
      expect(body.provider).toBe(provider.providerEnum);
      expect(body.model).toBe(provider.defaultModel);
      // API key is masked in the response — never the literal value the spec sent.
      expect(body.api_key).toBe('********');

      // Step 3 — happy-path Send test prompt + Done.
      await page.getByRole('button', { name: 'Send test prompt' }).click();
      await expect(
        page.getByText(
          `AI provider responded with risk_level=LOW (${provider.providerEnum})`,
        ),
      ).toBeVisible({ timeout: 10_000 });
      await page.getByRole('button', { name: 'Done' }).click();
      await page.waitForURL('**/admin/ai-configs');
      await waitForAiConfigsListReady(page);

      const row = page.getByRole('row', {
        name: new RegExp(escapeRegex(provider.configName)),
      });
      await expect(row).toBeVisible();
      await expect(row.getByText(provider.tile, { exact: true })).toBeVisible();
      await expect(row.getByText(provider.defaultModel, { exact: true })).toBeVisible();

      await page.unroute('**/api/v1/admin/ai-configs/*/test');
    }
  });

  test('4) edit primary Ollama → update name → list reflects new name', async ({
    page,
  }) => {
    test.skip(!primaryAiConfigId, 'Test 2 must succeed to seed the primary config');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    const primaryRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(PRIMARY_NAME)),
    });
    await expect(primaryRow).toBeVisible();
    await primaryRow.getByRole('button', { name: 'Edit' }).click();

    await page.waitForURL(`**/admin/ai-configs/${primaryAiConfigId}`);
    await expect(
      page.getByRole('heading', { name: new RegExp(`Edit · ${escapeRegex(PRIMARY_NAME)}`) }),
    ).toBeVisible({ timeout: 10_000 });

    const renamed = `${PRIMARY_NAME}-renamed`;
    await page.getByLabel('Configuration name').fill(renamed);

    const updateResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new RegExp(`/api/v1/admin/ai-configs/${primaryAiConfigId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const updateResponse = await updateResponsePromise;
    expect(updateResponse.status()).toBe(200);
    const body = (await updateResponse.json()) as { id: string; name: string };
    expect(body.name).toBe(renamed);

    await expect(
      page.getByText('AI configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    primaryDisplayName = renamed;

    await page.getByRole('button', { name: 'Back' }).click();
    await page.waitForURL('**/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    await expect(
      page.getByRole('row', { name: new RegExp(escapeRegex(renamed)) }),
    ).toBeVisible();
    // The old name (without the -renamed suffix) — verify exactly to avoid
    // matching the new name's substring.
    await expect(
      page.getByRole('row', { name: new RegExp(`${escapeRegex(PRIMARY_NAME)}(?!-renamed)`) }),
    ).toHaveCount(0);
  });

  test('5) edit-page Test button → success then error', async ({ page }) => {
    test.skip(!primaryAiConfigId, 'Test 2 must succeed to seed the primary config');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/admin/ai-configs/${primaryAiConfigId}`);
    await expect(
      page.getByRole('heading', {
        name: new RegExp(`Edit · ${escapeRegex(primaryDisplayName)}`),
      }),
    ).toBeVisible({ timeout: 10_000 });

    // Round 1 — mocked OK.
    await stubTestEndpoint(page, {
      status: 'OK',
      detail: 'AI provider responded with risk_level=LOW',
    });
    const okResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new RegExp(`/api/v1/admin/ai-configs/${primaryAiConfigId}/test$`).test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Send test prompt' }).click();
    expect((await okResponsePromise).status()).toBe(200);
    // Button label toggles to "Test passed" (testing='ok' state).
    await expect(
      page.getByRole('button', { name: 'Test passed' }),
    ).toBeVisible({ timeout: 10_000 });

    // Round 2 — mocked ERROR. Replace the stub.
    await page.unroute('**/api/v1/admin/ai-configs/*/test');
    await stubTestEndpoint(page, {
      status: 'ERROR',
      detail: 'Endpoint unreachable',
    });
    // The button's accessible name is now "Test passed"; clicking it fires
    // the same testMutation. Wait for that specific POST.
    const errorResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new RegExp(`/api/v1/admin/ai-configs/${primaryAiConfigId}/test$`).test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Test passed' }).click();
    expect((await errorResponsePromise).status()).toBe(200);

    await expect(
      page.getByText('Test failed: Endpoint unreachable', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
    // The button reverts to the original "Send test prompt" label
    // (testing='idle' after an ERROR result).
    await expect(
      page.getByRole('button', { name: 'Send test prompt' }),
    ).toBeVisible();

    await page.unroute('**/api/v1/admin/ai-configs/*/test');
  });

  test('6) row Test action → success toast (mocked OK)', async ({ page }) => {
    test.skip(!primaryAiConfigId, 'Test 2 must succeed to seed the primary config');

    await stubTestEndpoint(page, {
      status: 'OK',
      detail: 'AI provider responded with risk_level=LOW',
    });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    const primaryRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(primaryDisplayName)),
    });
    await expect(primaryRow).toBeVisible();

    const testResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new RegExp(`/api/v1/admin/ai-configs/${primaryAiConfigId}/test$`).test(r.url()),
      { timeout: 15_000 },
    );
    await primaryRow.getByRole('button', { name: 'Test' }).click();
    expect((await testResponsePromise).status()).toBe(200);

    await expect(page.getByText('Test passed', { exact: true })).toBeVisible({
      timeout: 10_000,
    });

    await page.unroute('**/api/v1/admin/ai-configs/*/test');
  });

  test('7) row Test action → error toast (mocked ERROR with detail)', async ({
    page,
  }) => {
    test.skip(!primaryAiConfigId, 'Test 2 must succeed to seed the primary config');

    await stubTestEndpoint(page, {
      status: 'ERROR',
      detail: 'Connection refused: ollama:11434',
    });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    const primaryRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(primaryDisplayName)),
    });
    await primaryRow.getByRole('button', { name: 'Test' }).click();

    await expect(
      page.getByText('Test failed: Connection refused: ollama:11434', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    await page.unroute('**/api/v1/admin/ai-configs/*/test');
  });

  test('8) delete primary (unbound) → row removed', async ({ page }) => {
    test.skip(!primaryAiConfigId, 'Test 2 must succeed to seed the primary config');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    const primaryRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(primaryDisplayName)),
    });
    await expect(primaryRow).toBeVisible();
    await primaryRow.getByRole('button', { name: 'Delete' }).click();

    // AntD Popconfirm renders detached in a portal. Scope to the popover that
    // contains the confirm title to avoid colliding with the row's icon button
    // (both have accessible name "Delete").
    const popover = page
      .locator('.ant-popover')
      .filter({ hasText: 'Delete AI configuration?' });
    await expect(popover).toBeVisible({ timeout: 10_000 });
    await expect(
      popover.getByText(
        `This will remove the configuration named ${primaryDisplayName}.`,
      ),
    ).toBeVisible();

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/admin/ai-configs/${primaryAiConfigId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await popover.getByRole('button', { name: 'Delete' }).click();
    expect((await deleteResponsePromise).status()).toBe(204);

    await expect(
      page.getByText('AI configuration deleted', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
    await expect(primaryRow).toHaveCount(0, { timeout: 10_000 });

    // The primary config is gone; null the id so afterAll doesn't double-delete.
    primaryAiConfigId = null;
  });

  test('9) delete in-use config → modal lists bound datasources', async ({
    page,
    request,
  }) => {
    // Arrange via API: fresh Ollama config + Postgres datasource bound to it.
    const inUse = await createAiConfigViaApi(request, adminAccessToken, {
      name: IN_USE_NAME,
      provider: 'OLLAMA',
      model: 'llama3.1:70b',
      endpoint: 'http://localhost:11434/api',
    });
    inUseAiConfigId = inUse.id;
    const boundDs = await createDatasourceBoundToAiConfig(
      request,
      adminAccessToken,
      inUseAiConfigId,
      BOUND_DS_NAME,
    );
    boundDatasourceId = boundDs.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    const inUseRow = page.getByRole('row', {
      name: new RegExp(escapeRegex(IN_USE_NAME)),
    });
    await expect(inUseRow).toBeVisible();
    // Should show in_use_count == 1.
    await expect(inUseRow.getByText('1 datasource', { exact: true })).toBeVisible();
    await inUseRow.getByRole('button', { name: 'Delete' }).click();

    const popover = page
      .locator('.ant-popover')
      .filter({ hasText: 'Delete AI configuration?' });
    await expect(popover).toBeVisible({ timeout: 10_000 });

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/admin/ai-configs/${inUseAiConfigId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await popover.getByRole('button', { name: 'Delete' }).click();
    const deleteResponse = await deleteResponsePromise;
    expect(deleteResponse.status()).toBe(409);
    const body = (await deleteResponse.json()) as {
      error?: string;
      boundDatasources?: Array<{ id: string; name: string }>;
    };
    expect(body.error).toBe('AI_CONFIG_IN_USE');
    expect(body.boundDatasources).toEqual([
      expect.objectContaining({ id: boundDatasourceId, name: BOUND_DS_NAME }),
    ]);

    // The list page opens its in-use Modal — title and the bound datasource
    // name in a <li>. AntD renders Modal with role="dialog".
    const inUseDialog = page.getByRole('dialog', {
      name: 'Cannot delete — still in use',
    });
    await expect(inUseDialog).toBeVisible({ timeout: 10_000 });
    await expect(
      inUseDialog.getByText('Unbind these datasources first:'),
    ).toBeVisible();
    await expect(inUseDialog.locator('li', { hasText: BOUND_DS_NAME })).toBeVisible();

    // Close the modal — its only button is "OK".
    await inUseDialog.getByRole('button', { name: 'OK' }).click();
    await expect(inUseDialog).toBeHidden({ timeout: 10_000 });
    // The row stays — delete was rejected.
    await expect(inUseRow).toBeVisible();
  });

  test('10) create with duplicate name → 409 → error toast, wizard stays on step 2', async ({
    page,
    request,
  }) => {
    const existing = await createAiConfigViaApi(request, adminAccessToken, {
      name: DUPLICATE_NAME,
      provider: 'OLLAMA',
      model: 'llama3.1:70b',
      endpoint: 'http://localhost:11434/api',
    });
    duplicateAiConfigId = existing.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await openWizardFresh(page);

    await page.getByRole('button', { name: /Ollama/ }).click();
    await page.getByLabel('Configuration name').fill(DUPLICATE_NAME);

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/ai-configs$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save and continue' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(409);
    const body = (await createResponse.json()) as { error?: string };
    expect(body.error).toBe('AI_CONFIG_NAME_ALREADY_EXISTS');

    await expect(
      page.getByText(
        'An AI configuration with that name already exists. Pick a different name.',
      ),
    ).toBeVisible({ timeout: 10_000 });

    // Wizard stays on step 2 — the Configuration name input is still mounted.
    await expect(page.getByLabel('Configuration name')).toHaveValue(DUPLICATE_NAME);
  });

  test('11) create Custom (OpenAI-compatible) via wizard — endpoint required, keyless', async ({
    page,
  }) => {
    await stubTestEndpoint(page, {
      status: 'OK',
      detail: 'AI provider responded with risk_level=LOW (OPENAI_COMPATIBLE)',
    });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await openWizardFresh(page);

    // Step 1 — pick the Custom (OpenAI-compatible) tile.
    await page.getByRole('button', { name: /Custom \(OpenAI-compatible\)/ }).click();

    // Step 2 — the API endpoint field is shown and the API key is optional
    // (needs_api_key=false). The endpoint is pre-filled with the placeholder.
    await expect(page.getByLabel('API endpoint')).toBeVisible();
    await expect(page.getByLabel('API key')).toBeVisible();

    await page.getByLabel('Configuration name').fill(COMPAT_NAME);
    await page.getByLabel('Model').fill('qwen2.5');

    // Clearing the endpoint must trigger the required-rule on submit — no POST fires.
    await page.getByLabel('API endpoint').fill('');
    await page.getByRole('button', { name: 'Save and continue' }).click();
    await expect(
      page.getByText('API endpoint is required for a custom provider'),
    ).toBeVisible({ timeout: 10_000 });

    // Supply an endpoint, leave the API key blank, and submit for real.
    await page.getByLabel('API endpoint').fill('http://vllm:8000/v1');
    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        /\/api\/v1\/admin\/ai-configs$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save and continue' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as {
      id: string;
      provider: string;
      model: string;
      endpoint: string | null;
      api_key: string | null;
    };
    compatAiConfigId = body.id;
    expect(body.provider).toBe('OPENAI_COMPATIBLE');
    expect(body.model).toBe('qwen2.5');
    expect(body.endpoint).toBe('http://vllm:8000/v1');
    // Keyless config — the API key is omitted from the response (never the masked value).
    expect(body.api_key ?? null).toBeNull();

    // Step 3 — happy-path Send test prompt + Done.
    await page.getByRole('button', { name: 'Send test prompt' }).click();
    await expect(
      page.getByText('AI provider responded with risk_level=LOW (OPENAI_COMPATIBLE)'),
    ).toBeVisible({ timeout: 10_000 });
    await page.getByRole('button', { name: 'Done' }).click();
    await page.waitForURL('**/admin/ai-configs');
    await waitForAiConfigsListReady(page);

    const row = page.getByRole('row', {
      name: new RegExp(escapeRegex(COMPAT_NAME)),
    });
    await expect(row).toBeVisible();
    await expect(
      row.getByText('Custom (OpenAI-compatible)', { exact: true }),
    ).toBeVisible();
    await expect(row.getByText('qwen2.5', { exact: true })).toBeVisible();

    await page.unroute('**/api/v1/admin/ai-configs/*/test');
  });
});
