import {
  test,
  expect,
  type APIRequestContext,
  type Locator,
  type Page,
  type Route,
} from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Unique suffix keeps names unique across reruns against a long-lived database
// (the e2e stack reuses postgres data between `stack:up` cycles unless torn
// down with `-v`). Same pattern as AF-278.
const UNIQUE_SUFFIX = `af628-${Date.now()}`;
const SPLUNK_NAME = `e2e-splunk-${UNIQUE_SUFFIX}`;
const S3_NAME = `e2e-s3-worm-${UNIQUE_SUFFIX}`;

// A destination the backend could never have delivered to from the browser; we
// only use it to assert the browser itself never calls the sink URL.
const SPLUNK_URL = 'https://splunk.e2e-sink.invalid:8088/services/collector/event';

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
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Wait for the GET /api/v1/admin/audit-sinks that AuditSinksPage issues on
// mount, so the cards (or empty state) render real content before we drive the
// UI. Same gate pattern as AF-278.
async function waitForSinksListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/audit-sinks(\?|$)/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

// Stub POST /api/v1/admin/audit-sinks/{id}/test so the backend never makes a
// real outbound delivery to a SIEM destination. Same shape as the
// notification-channel test stubs.
async function stubTestEndpointOk(page: Page): Promise<void> {
  await page.route('**/api/v1/admin/audit-sinks/*/test', async (route: Route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ status: 'OK', detail: 'Test event delivered' }),
    });
  });
}

async function stubTestEndpointError(page: Page, detail: string): Promise<void> {
  await page.route('**/api/v1/admin/audit-sinks/*/test', async (route: Route) => {
    await route.fulfill({
      status: 502,
      contentType: 'application/problem+json',
      body: JSON.stringify({
        type: 'about:blank',
        title: 'Bad Gateway',
        status: 502,
        detail,
        error: 'AUDIT_SINK_TEST_FAILED',
        timestamp: new Date().toISOString(),
      }),
    });
  });
}

interface CreatedSink {
  id: string;
  name: string;
}

async function selectAntdOption(
  scope: Locator | Page,
  selectLabel: string | RegExp,
  optionLabel: string,
): Promise<void> {
  // Open the AntD Select by clicking its visible label / combobox cell. The
  // dropdown panel renders in a portal at the document root, so look it up on
  // `scope.page()` regardless of where the trigger lives.
  await scope.getByLabel(selectLabel).click();
  const root = 'page' in scope ? scope.page() : scope;
  await root
    .locator('.ant-select-item-option')
    .filter({ hasText: optionLabel })
    .first()
    .click();
}

function sinkCard(page: Page, name: string): Locator {
  return page
    .getByTestId('audit-sink-card')
    .filter({ hasText: name })
    .first();
}

async function deleteSinkViaApi(
  request: APIRequestContext,
  accessToken: string,
  id: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/admin/audit-sinks/${id}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok() && res.status() !== 404) {
    // eslint-disable-next-line no-console
    console.warn(`Audit sink cleanup (${id}) returned ${res.status()}: ${await res.text()}`);
  }
}

// #628 covers the /admin/audit-sinks CRUD + health + Test flows:
//   1. Create SPLUNK_HEC sink via modal → card appears, token masked, no
//      browser request to the sink URL.
//   2. Create S3_OBJECT_LOCK sink via modal (type-conditional fields).
//   3. Edit SPLUNK sink: rename; the token round-trips as the mask and the PUT
//      body sends "********" so the backend preserves the stored secret.
//   4. Submit an empty form → inline validation, no POST fired.
//   5. Test button: stubbed 200 → success toast; stubbed 502 ProblemDetail →
//      error toast surfacing the backend detail.
//   6. Disable the SPLUNK sink via edit → card shows the disabled pill.
//   7. Delete both sinks via Popconfirm → cards removed.
//
// describe.serial because the SPLUNK card moves through 1 → 3 → 5 → 6 → 7 and
// the S3 card persists between 2 and 7. The Playwright project is already
// workers=1, so this is belt-and-braces.
test.describe.serial('/admin/audit-sinks — sink CRUD + health + test (#628)', () => {
  let adminAccessToken = '';
  const created = new Map<string, CreatedSink>();
  let splunkDisplayName = SPLUNK_NAME; // becomes `${SPLUNK_NAME}-renamed` in test 3

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    for (const s of created.values()) {
      await deleteSinkViaApi(request, adminAccessToken, s.id);
    }
  });

  test('1) create SPLUNK_HEC sink via modal → card appears, token stays masked', async ({
    page,
  }) => {
    // The browser must never talk to the sink destination itself — creation
    // only persists config; delivery happens in the backend drain job.
    let sinkCalled = false;
    page.on('request', (req) => {
      if (req.url().startsWith('https://splunk.e2e-sink.invalid')) sinkCalled = true;
    });

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/audit-sinks');
    await waitForSinksListReady(page);

    await page.getByRole('button', { name: 'Add sink' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add audit sink' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    await dialog.getByLabel('Name', { exact: true }).fill(SPLUNK_NAME);
    // SPLUNK_HEC is the default type — its fields are already visible.
    await dialog.getByLabel('HEC endpoint URL').fill(SPLUNK_URL);
    await dialog.getByLabel('HEC token').fill(`hec-secret-${UNIQUE_SUFFIX}`);
    await dialog.getByLabel('Index (optional)').fill('accessflow');

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' && /\/api\/v1\/admin\/audit-sinks$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Create sink' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as {
      id: string;
      name: string;
      config: Record<string, unknown>;
    };
    created.set('splunk', { id: body.id, name: body.name });
    expect(body.name).toBe(SPLUNK_NAME);
    // The response already comes back with the secret masked.
    expect(body.config['token']).toBe('********');

    await expect(page.getByText('Audit sink created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    const card = sinkCard(page, SPLUNK_NAME);
    await expect(card).toBeVisible();
    // The card shows the destination, never the token — masked or otherwise.
    await expect(card.getByText(/hec-secret-/)).toHaveCount(0);
    await expect(card.getByText('********')).toHaveCount(0);
    expect(sinkCalled).toBe(false);
  });

  test('2) create S3_OBJECT_LOCK sink via modal → card appears', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/audit-sinks');
    await waitForSinksListReady(page);

    await page.getByRole('button', { name: 'Add sink' }).click();
    const dialog = page.getByRole('dialog', { name: 'Add audit sink' });
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    await dialog.getByLabel('Name', { exact: true }).fill(S3_NAME);
    await selectAntdOption(dialog, 'Type', 'S3 Object Lock (WORM)');
    // The Splunk fields are gone; the S3 fields are the type-conditional block.
    await expect(dialog.getByLabel('HEC endpoint URL')).toHaveCount(0);
    await dialog.getByLabel('Bucket').fill('accessflow-audit-worm');
    await dialog.getByLabel('Region').fill('eu-central-1');
    await dialog.getByLabel('Access key ID').fill('AKIAE2EEXAMPLE');
    await dialog.getByLabel('Secret access key').fill(`s3-secret-${UNIQUE_SUFFIX}`);
    await dialog.getByLabel('Retention (days)').fill('365');
    await dialog.getByLabel('Key prefix (optional)').fill('audit/');

    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' && /\/api\/v1\/admin\/audit-sinks$/.test(r.url()),
      { timeout: 15_000 },
    );
    await dialog.getByRole('button', { name: 'Create sink' }).click();
    const createResponse = await createResponsePromise;
    expect(createResponse.status()).toBe(201);
    const body = (await createResponse.json()) as { id: string; name: string };
    created.set('s3', { id: body.id, name: body.name });
    expect(body.name).toBe(S3_NAME);

    await expect(page.getByText('Audit sink created', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(sinkCard(page, S3_NAME)).toBeVisible();
  });

  test('3) edit SPLUNK sink: rename + masked token passes through on PUT', async ({ page }) => {
    const splunk = created.get('splunk');
    test.skip(!splunk, 'Test 1 must succeed to seed the SPLUNK sink');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/audit-sinks');
    await waitForSinksListReady(page);

    await sinkCard(page, SPLUNK_NAME).getByRole('button', { name: 'Edit' }).click();
    const editDialog = page.getByRole('dialog', {
      name: new RegExp(`Edit sink · ${escapeRegex(SPLUNK_NAME)}`),
    });
    await expect(editDialog).toBeVisible({ timeout: 10_000 });

    // The stored secret round-trips as the literal mask.
    await expect(editDialog.getByLabel('HEC token')).toHaveValue('********');

    const renamed = `${SPLUNK_NAME}-renamed`;
    await editDialog.getByLabel('Name', { exact: true }).fill(renamed);

    const updateResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new RegExp(`/api/v1/admin/audit-sinks/${splunk!.id}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await editDialog.getByRole('button', { name: 'Save changes' }).click();
    const updateResponse = await updateResponsePromise;
    expect(updateResponse.status()).toBe(200);
    // Passing the mask through preserves the stored secret server-side.
    const putBody = JSON.parse(updateResponse.request().postData() ?? '{}') as {
      name: string;
      config: Record<string, unknown>;
    };
    expect(putBody.name).toBe(renamed);
    expect(putBody.config['token']).toBe('********');
    const responseBody = (await updateResponse.json()) as { name: string };
    expect(responseBody.name).toBe(renamed);

    await expect(page.getByText('Audit sink updated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(sinkCard(page, renamed)).toBeVisible();

    splunkDisplayName = renamed;
    created.set('splunk', { id: splunk!.id, name: renamed });
  });

  test('4) submit empty form → inline validation, no POST fired', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/audit-sinks');
    await waitForSinksListReady(page);

    let postFired = false;
    const guard = (req: import('@playwright/test').Request): void => {
      if (req.method() === 'POST' && /\/api\/v1\/admin\/audit-sinks$/.test(req.url())) {
        postFired = true;
      }
    };
    page.on('request', guard);

    try {
      await page.getByRole('button', { name: 'Add sink' }).click();
      const dialog = page.getByRole('dialog', { name: 'Add audit sink' });
      await expect(dialog).toBeVisible({ timeout: 10_000 });

      await dialog.getByRole('button', { name: 'Create sink' }).click();

      // The required rules reject submission: AntD shows the field-level error
      // explanations and the modal stays open.
      await expect(dialog).toBeVisible();
      await expect(dialog.locator('.ant-form-item-explain-error').first()).toBeVisible({
        timeout: 10_000,
      });

      // Settle for a tick so a stray POST would have time to be observed.
      await page.waitForTimeout(500);
      expect(postFired).toBe(false);

      await dialog.getByRole('button', { name: 'Cancel' }).click();
    } finally {
      page.off('request', guard);
    }
  });

  test('5) Test button: stubbed 200 → success toast, stubbed 502 → error detail toast', async ({
    page,
  }) => {
    const splunk = created.get('splunk');
    test.skip(!splunk, 'Test 1 must succeed to seed the SPLUNK sink');

    await stubTestEndpointOk(page);
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/audit-sinks');
    await waitForSinksListReady(page);

    const card = sinkCard(page, splunkDisplayName);
    const testResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        new RegExp(`/api/v1/admin/audit-sinks/${splunk!.id}/test$`).test(r.url()),
      { timeout: 15_000 },
    );
    await card.getByRole('button', { name: 'Test' }).click();
    expect((await testResponsePromise).status()).toBe(200);

    await expect(page.getByText('Test event delivered', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await page.unroute('**/api/v1/admin/audit-sinks/*/test');

    // Now the failing destination: a 502 ProblemDetail whose backend-localized
    // detail must surface in the error toast (auditSinkErrorMessage prefers it).
    const failureDetail = 'Splunk HEC returned 403: invalid token';
    await stubTestEndpointError(page, failureDetail);
    await card.getByRole('button', { name: 'Test' }).click();
    await expect(page.getByText(failureDetail, { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await page.unroute('**/api/v1/admin/audit-sinks/*/test');
  });

  test('6) disable via edit → card shows the disabled pill', async ({ page }) => {
    const splunk = created.get('splunk');
    test.skip(!splunk, 'Test 1 must succeed to seed the SPLUNK sink');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/audit-sinks');
    await waitForSinksListReady(page);

    await sinkCard(page, splunkDisplayName).getByRole('button', { name: 'Edit' }).click();
    const editDialog = page.getByRole('dialog', {
      name: new RegExp(`Edit sink · ${escapeRegex(splunkDisplayName)}`),
    });
    await expect(editDialog).toBeVisible({ timeout: 10_000 });

    await editDialog.getByLabel('Enabled').click();

    const updateResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        new RegExp(`/api/v1/admin/audit-sinks/${splunk!.id}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await editDialog.getByRole('button', { name: 'Save changes' }).click();
    const updateResponse = await updateResponsePromise;
    expect(updateResponse.status()).toBe(200);
    const putBody = JSON.parse(updateResponse.request().postData() ?? '{}') as {
      enabled: boolean;
    };
    expect(putBody.enabled).toBe(false);

    await expect(page.getByText('Audit sink updated', { exact: true })).toBeVisible({
      timeout: 10_000,
    });
    await expect(
      sinkCard(page, splunkDisplayName).getByText('disabled', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('7) delete both sinks via Popconfirm → cards removed', async ({ page }) => {
    const splunk = created.get('splunk');
    const s3 = created.get('s3');
    test.skip(!splunk || !s3, 'Tests 1 and 2 must succeed to seed the deletable sinks');

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/audit-sinks');
    await waitForSinksListReady(page);

    const targets: Array<{ key: 'splunk' | 's3'; id: string; name: string }> = [
      { key: 'splunk', id: splunk!.id, name: splunkDisplayName },
      { key: 's3', id: s3!.id, name: S3_NAME },
    ];

    for (const target of targets) {
      await sinkCard(page, target.name).getByRole('button', { name: 'Delete sink' }).click();

      const popover = page.locator('.ant-popover').filter({ hasText: 'Delete audit sink?' });
      await expect(popover).toBeVisible({ timeout: 10_000 });
      await expect(
        popover.getByText(
          `This will remove the sink named ${target.name}. Events already delivered stay at the destination.`,
        ),
      ).toBeVisible();

      const deleteResponsePromise = page.waitForResponse(
        (r) =>
          r.request().method() === 'DELETE' &&
          new RegExp(`/api/v1/admin/audit-sinks/${target.id}$`).test(r.url()),
        { timeout: 15_000 },
      );
      await popover.getByRole('button', { name: 'Delete' }).click();
      expect((await deleteResponsePromise).status()).toBe(204);

      await expect(page.getByText(target.name, { exact: true })).toHaveCount(0, {
        timeout: 10_000,
      });
      created.delete(target.key);
    }
  });
});
