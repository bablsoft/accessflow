import { fileURLToPath } from 'node:url';
import { readFile, writeFile, unlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test, expect, type Page } from '@playwright/test';
import {
  createCustomDatasource,
  deleteCustomDriverViaApi,
  deleteDatasource,
  loginViaApi,
  sha256OfFile,
  uploadCustomDriverViaApi,
  type CreatedCustomDriver,
  type CreatedDatasource,
} from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

// Shared suffix keeps vendor / datasource names unique across reruns against
// a long-lived database (the e2e stack reuses postgres data between
// `stack:up` cycles).
const UNIQUE_SUFFIX = `af274-${Date.now()}`;
const HAPPY_PATH_VENDOR = `Stub Driver ${UNIQUE_SUFFIX} HAPPY`;
const INVALID_VENDOR = `Stub Driver ${UNIQUE_SUFFIX} INVALID`;
const IN_USE_VENDOR = `Stub Driver ${UNIQUE_SUFFIX} IN-USE`;
const IN_USE_DATASOURCE = `Custom DS ${UNIQUE_SUFFIX}`;

const STUB_DRIVER_CLASS = 'com.accessflow.e2e.StubDriver';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const STUB_JAR_FIXTURE = path.join(__dirname, '..', 'fixtures', 'drivers', 'test-driver.jar');
const INVALID_JAR_PATH = path.join(__dirname, '..', 'fixtures', 'drivers', 'not-a-driver.jar');

// The valid stub JAR is content-hashed by the backend (SHA-256 must be
// unique per org) and `DELETE /api/v1/datasources/{id}` is a soft delete
// that doesn't unbind the driver. So if Test 3 leaves an orphaned binding
// behind, a re-run against the same stack would hit CUSTOM_DRIVER_DUPLICATE
// on Test 1. Sidestep that by writing a per-run uniquified copy: read the
// committed fixture, append a marker (after the ZIP EOCD, which JarFile
// tolerates), and stage the result in $TMPDIR. Each run gets a fresh SHA.
const RUNTIME_STUB_JAR = path.join(tmpdir(), `af274-stub-${UNIQUE_SUFFIX}.jar`);

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Wait for the GET /api/v1/datasources/drivers that the page issues on mount
// before driving the UI. Mirrors the "page is interactive" gate the AF-273
// settings spec uses, so the table / empty-state is rendered with real data
// rather than the loading skeleton.
async function waitForDriversListReady(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/datasources\/drivers$/.test(r.url()) &&
      r.ok(),
    { timeout: 15_000 },
  );
}

async function openUploadModal(page: Page): Promise<void> {
  // The empty-state and PageHeader both expose a primary "Upload driver"
  // button; either one opens the same modal. `.first()` accepts whichever is
  // currently rendered (depends on whether the list is empty or not).
  await page.getByRole('button', { name: 'Upload driver' }).first().click();
  await expect(page.getByRole('dialog', { name: 'Upload JDBC driver' })).toBeVisible({
    timeout: 10_000,
  });
}

async function fillUploadForm(
  page: Page,
  opts: { jarPath: string; vendorName: string; driverClass: string; sha256: string },
): Promise<void> {
  // Ant's Upload.Dragger exposes a real <input type=file> below the dropzone.
  // Scoping to the dialog avoids matching any other hidden inputs on the page.
  const dialog = page.getByRole('dialog', { name: 'Upload JDBC driver' });
  await dialog.locator('input[type="file"]').setInputFiles(opts.jarPath);
  // The fileList renders the filename once the dragger accepts the file —
  // wait for it so the submit click can't race the React state update.
  await expect(
    dialog.locator('.ant-upload-list-item-name', {
      hasText: path.basename(opts.jarPath),
    }),
  ).toBeVisible({ timeout: 10_000 });
  await dialog.locator('#vendor_name').fill(opts.vendorName);
  // target_db_type defaults to CUSTOM; no need to touch it.
  await dialog.locator('#driver_class').fill(opts.driverClass);
  await dialog.locator('#expected_sha256').fill(opts.sha256);
}

// AF-274 covers three scenarios on /admin/drivers:
//   1. Happy path — upload a valid stub JAR via UI → row appears → delete via
//      popconfirm → empty state returns.
//   2. Invalid JAR rejection — upload a non-JAR file (with a .jar extension
//      so the client-side dragger accepts it) → backend 422
//      CUSTOM_DRIVER_INVALID_JAR → error toast.
//   3. Delete-in-use guard — arrange a driver bound to a CUSTOM datasource
//      via API → click delete in the UI → backend 409 CUSTOM_DRIVER_IN_USE →
//      error toast → row stays.
//
// One deviation from the issue's literal script — the spec matches the
// implementation, not the issue body:
//
//   * Issue says "modal lists the bound datasource" for the in-use guard.
//     The current UI fires a `message.error` toast via showApiError instead
//     (CustomDriversPage.tsx:39-47). The spec asserts the toast text exactly
//     ("Cannot delete: 1 datasource still uses this driver. Delete that
//     datasource first." — errors.custom_driver_in_use_one). Wiring a real
//     in-use modal that lists the bound rows is a frontend feature, not an
//     e2e concern.
//
// Wired as `describe.serial` because all three tests share the
// /admin/drivers page and we want deterministic cleanup ordering — the
// in-use scenario deletes its datasource before its driver. Playwright
// already runs workers=1 so this is mostly belt-and-braces.
test.describe.serial('/admin/drivers — upload + delete', () => {
  let stubSha = '';
  let invalidSha = '';
  let adminAccessToken = '';

  // Trailing cleanup ids — set when a test arranges API state, cleared when
  // the test deletes that state itself.
  let cleanupDatasourceId: string | null = null;
  let cleanupDriverId: string | null = null;

  test.beforeAll(async ({ request }) => {
    const fixtureBytes = await readFile(STUB_JAR_FIXTURE);
    const marker = Buffer.from(`\n# af274 ${UNIQUE_SUFFIX}\n`, 'utf8');
    await writeFile(RUNTIME_STUB_JAR, Buffer.concat([fixtureBytes, marker]));
    stubSha = await sha256OfFile(RUNTIME_STUB_JAR);
    invalidSha = await sha256OfFile(INVALID_JAR_PATH);
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    // `DELETE /api/v1/datasources/{id}` is a SOFT delete (deactivate) that
    // does NOT clear `custom_driver_id` on the row, and
    // `findAllByCustomDriver_Id` doesn't filter on `active`. Net result: the
    // test 3 driver stays referenced by the (now-inactive) datasource, and
    // the driver cleanup below logs a 409 — same orphan-on-rerun pattern as
    // every other e2e spec that creates datasources. CI runs against a
    // fresh stack so this is just cosmetic noise; local re-runs within the
    // same `stack:up` lifecycle may need a `stack:down` first.
    if (cleanupDatasourceId) {
      await deleteDatasource(request, adminAccessToken, cleanupDatasourceId);
      cleanupDatasourceId = null;
    }
    if (cleanupDriverId) {
      await deleteCustomDriverViaApi(request, adminAccessToken, cleanupDriverId);
      cleanupDriverId = null;
    }
    await unlink(RUNTIME_STUB_JAR).catch(() => {
      /* best-effort temp cleanup */
    });
  });

  test('1) upload a valid stub JAR via UI, then delete it', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);

    await page.goto('/admin/drivers');
    await waitForDriversListReady(page);
    await expect(
      page.getByRole('heading', { name: 'Custom JDBC drivers' }),
    ).toBeVisible();

    await openUploadModal(page);
    await fillUploadForm(page, {
      jarPath: RUNTIME_STUB_JAR,
      vendorName: HAPPY_PATH_VENDOR,
      driverClass: STUB_DRIVER_CLASS,
      sha256: stubSha,
    });

    const uploadResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().endsWith('/api/v1/datasources/drivers'),
      { timeout: 15_000 },
    );
    await page
      .getByRole('dialog', { name: 'Upload JDBC driver' })
      .getByRole('button', { name: 'Upload driver' })
      .click();
    const uploadResponse = await uploadResponsePromise;
    expect(uploadResponse.status()).toBe(201);
    const uploadBody = (await uploadResponse.json()) as { id: string };
    cleanupDriverId = uploadBody.id;

    // Success toast + modal closes + row renders.
    await expect(page.getByText('Driver uploaded.')).toBeVisible({ timeout: 10_000 });
    await expect(
      page.getByRole('dialog', { name: 'Upload JDBC driver' }),
    ).toBeHidden({ timeout: 10_000 });
    const happyRow = page.getByRole('row', { name: new RegExp(HAPPY_PATH_VENDOR) });
    await expect(happyRow).toBeVisible();
    await expect(happyRow.getByText(STUB_DRIVER_CLASS)).toBeVisible();

    // Delete via the row's icon button + popconfirm.
    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/datasources/drivers/${cleanupDriverId}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await happyRow.getByRole('button', { name: 'Delete driver' }).click();
    // Popconfirm renders detached above the row — match by its OK button text.
    await page.getByRole('button', { name: 'Delete', exact: true }).click();
    const deleteResponse = await deleteResponsePromise;
    expect(deleteResponse.status()).toBe(204);

    await expect(page.getByText('Driver deleted.')).toBeVisible({ timeout: 10_000 });
    // Assert the just-uploaded row is gone. We don't assert the empty-state
    // here because orphans from previous runs in this stack lifecycle (see
    // afterAll comment) can leave other rows in the table — those are
    // unrelated to this test's success.
    await expect(happyRow).toHaveCount(0, { timeout: 10_000 });
    // The driver is gone — `afterAll` doesn't need to chase it.
    cleanupDriverId = null;
  });

  test('2) non-JAR upload returns 422 CUSTOM_DRIVER_INVALID_JAR and shows a toast', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/drivers');
    await waitForDriversListReady(page);

    await openUploadModal(page);
    await fillUploadForm(page, {
      jarPath: INVALID_JAR_PATH,
      vendorName: INVALID_VENDOR,
      driverClass: STUB_DRIVER_CLASS,
      sha256: invalidSha,
    });

    const uploadResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().endsWith('/api/v1/datasources/drivers'),
      { timeout: 15_000 },
    );
    await page
      .getByRole('dialog', { name: 'Upload JDBC driver' })
      .getByRole('button', { name: 'Upload driver' })
      .click();
    const uploadResponse = await uploadResponsePromise;
    expect(uploadResponse.status()).toBe(422);
    const body = (await uploadResponse.json()) as {
      error?: string;
      driverClass?: string;
    };
    expect(body.error).toBe('CUSTOM_DRIVER_INVALID_JAR');
    expect(body.driverClass).toBe(STUB_DRIVER_CLASS);

    // Toast text comes from errors.custom_driver_invalid_jar with the
    // {{driverClass}} substitution.
    await expect(
      page.getByText(
        `The uploaded JAR does not contain ${STUB_DRIVER_CLASS}, or that class does not implement java.sql.Driver.`,
      ),
    ).toBeVisible({ timeout: 10_000 });

    // Modal stays open so the user can fix and retry.
    await expect(
      page.getByRole('dialog', { name: 'Upload JDBC driver' }),
    ).toBeVisible();
  });

  test('3) deleting a driver bound to a datasource returns 409 and shows the in-use toast', async ({
    request,
    page,
  }) => {
    // Arrange via API so the spec can focus on the delete-while-bound
    // assertion. The bound datasource references the driver; deleting the
    // datasource first restores the cleanup invariant.
    const driver: CreatedCustomDriver = await uploadCustomDriverViaApi(
      request,
      adminAccessToken,
      {
        jarPath: RUNTIME_STUB_JAR,
        vendorName: IN_USE_VENDOR,
        driverClass: STUB_DRIVER_CLASS,
        targetDbType: 'CUSTOM',
      },
    );
    cleanupDriverId = driver.id;

    const datasource: CreatedDatasource = await createCustomDatasource(
      request,
      adminAccessToken,
      {
        name: IN_USE_DATASOURCE,
        customDriverId: driver.id,
      },
    );
    cleanupDatasourceId = datasource.id;

    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/drivers');
    await waitForDriversListReady(page);

    const boundRow = page.getByRole('row', { name: new RegExp(IN_USE_VENDOR) });
    await expect(boundRow).toBeVisible();

    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'DELETE' &&
        new RegExp(`/api/v1/datasources/drivers/${driver.id}$`).test(r.url()),
      { timeout: 15_000 },
    );
    await boundRow.getByRole('button', { name: 'Delete driver' }).click();
    await page.getByRole('button', { name: 'Delete', exact: true }).click();
    const deleteResponse = await deleteResponsePromise;
    expect(deleteResponse.status()).toBe(409);
    const body = (await deleteResponse.json()) as {
      error?: string;
      referencedBy?: string[];
    };
    expect(body.error).toBe('CUSTOM_DRIVER_IN_USE');
    expect(body.referencedBy).toEqual([datasource.id]);

    await expect(
      page.getByText(
        'Cannot delete: 1 datasource still uses this driver. Delete that datasource first.',
      ),
    ).toBeVisible({ timeout: 10_000 });

    // Driver row stays in the table — the delete was blocked.
    await expect(boundRow).toBeVisible();
  });
});
