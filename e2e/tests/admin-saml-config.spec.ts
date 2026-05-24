import {
  test,
  expect,
  type APIRequestContext,
  type Browser,
  type Page,
} from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';

const DEFAULT_API_BASE = 'http://localhost:8080';

// Issue references the AF-280 spec — the standard e2e stack
// (docker-compose.e2e.yml) does NOT seed any SAML config row, so we start from
// "SAML disabled / no IdP fields". Each save round-trips through the real
// backend (no stubs).

// A small but well-formed PEM block. The backend's @Pattern only checks the
// BEGIN/END envelope — the bytes inside are never parsed (the service just
// encrypts and persists the raw string). Using a tiny placeholder keeps the
// spec body readable.
const VALID_PEM = `-----BEGIN CERTIFICATE-----
MIIBhTCCASegAwIBAgIQE2E280AF280CertPlaceholderXX==
-----END CERTIFICATE-----`;

const IDP_METADATA_URL = 'https://idp.e2e.test/metadata';
const IDP_ENTITY_ID = 'urn:e2e:idp';
const IDP_ENTITY_ID_UPDATED = 'urn:e2e:idp-2';

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/editor', { timeout: 15_000 });
}

// Gate before driving the SAML form: wait for the GET that SamlConfigPage
// issues on mount so the Form.useForm setFieldsValue effect has populated the
// inputs. Mirrors the waitForSmtpLoaded pattern in admin-system-smtp.spec.ts.
async function waitForSamlConfigLoaded(page: Page): Promise<void> {
  await page.waitForResponse(
    (r) =>
      r.request().method() === 'GET' &&
      /\/api\/v1\/admin\/saml-config$/.test(r.url()) &&
      r.status() < 500,
    { timeout: 15_000 },
  );
}

// A NEW browser context bypasses both the Zustand auth store (in-memory) and
// TanStack Query's 30s staleTime on ['samlEnabled'] — so the LoginPage SAML
// button reflects the backend's current state without waiting for the cache
// window. Each probe also tears the context down so we don't leak sessions.
async function probeLoginSamlButton(browser: Browser): Promise<{
  visible: boolean;
}> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.goto('/login');
    // The button is rendered conditionally on GET /api/v1/auth/saml/enabled;
    // wait for that probe to land before asserting either way.
    await page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/auth\/saml\/enabled$/.test(r.url()) &&
        r.status() < 500,
      { timeout: 10_000 },
    );
    const button = page.getByRole('button', { name: /SAML SSO/i });
    const visible = await button.isVisible().catch(() => false);
    return { visible };
  } finally {
    await context.close();
  }
}

async function resetSamlConfig(
  request: APIRequestContext,
  accessToken: string,
): Promise<void> {
  const res = await request.put(`${apiBase()}/api/v1/admin/saml-config`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: {
      idp_metadata_url: '',
      idp_entity_id: '',
      sp_entity_id: '',
      acs_url: '',
      slo_url: '',
      signing_cert_pem: '',
      active: false,
    },
  });
  if (!res.ok()) {
    // eslint-disable-next-line no-console
    console.warn(
      `SAML config reset returned ${res.status()}: ${await res.text()}`,
    );
  }
}

// AF-280 covers the /admin/saml CRUD surface end-to-end against the real
// backend:
//   1. Initial state — SAML disabled, login page has no SAML button.
//   2. Enable + save IdP fields + cert → success toast; logged-out probe of
//      /login shows the "Continue with SAML SSO" button.
//   3. Reload — fields persisted, cert masked as ********; editing a single
//      non-cert field preserves the stored cert.
//   4. Disable + save → toast; logged-out probe of /login no longer shows
//      the button.
//   5. Failure — malformed metadata URL → inline AntD error, no PUT fires.
//   6. Failure — invalid PEM → backend 400 VALIDATION_ERROR → error toast,
//      form stays open.
//
// describe.serial because each scenario depends on the singleton SAML row's
// state established by the previous one, and afterAll resets to a disabled
// row so adjacent specs aren't affected. The Playwright project is already
// workers=1, so this is belt-and-braces.
test.describe.serial('/admin/saml — config CRUD (no IdP roundtrip)', () => {
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  });

  test.afterAll(async ({ request }) => {
    if (adminAccessToken) {
      await resetSamlConfig(request, adminAccessToken);
    }
  });

  test('1) initial load → form renders, login page shows no SAML button', async ({
    page,
    browser,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/saml');
    await waitForSamlConfigLoaded(page);

    await expect(page.getByRole('heading', { name: 'SAML / SSO' })).toBeVisible();
    await expect(page.getByText('Identity provider', { exact: true })).toBeVisible();
    await expect(page.getByText('Service provider', { exact: true })).toBeVisible();
    await expect(page.getByText('Attribute mapping', { exact: true })).toBeVisible();

    // The Active switch should reflect the unseeded "disabled" default.
    await expect(page.getByRole('switch')).not.toBeChecked();

    const probe = await probeLoginSamlButton(browser);
    expect(probe.visible).toBe(false);
  });

  test('2) enable + save metadata + cert → toast; /login shows SAML button', async ({
    page,
    browser,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/saml');
    await waitForSamlConfigLoaded(page);

    await page.getByLabel('IdP metadata URL').fill(IDP_METADATA_URL);
    await page.getByLabel('IdP entity ID').fill(IDP_ENTITY_ID);
    await page.getByLabel('Signing certificate (PEM)').fill(VALID_PEM);
    // The active switch is the only one in the SAML form.
    await page.getByRole('switch').click();
    await expect(page.getByRole('switch')).toBeChecked();

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/saml-config$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as {
      active?: boolean;
      idp_metadata_url?: string;
      signing_cert_pem?: string;
    };
    expect(body.active).toBe(true);
    expect(body.idp_metadata_url).toBe(IDP_METADATA_URL);
    // Response masks the cert (POST/PUT echoes the GET shape).
    expect(body.signing_cert_pem).toBe('********');

    await expect(
      page.getByText('SAML configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    const probe = await probeLoginSamlButton(browser);
    expect(probe.visible).toBe(true);
  });

  test('3) reload → fields persisted, cert masked, mask-preserve roundtrip', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/saml');
    await waitForSamlConfigLoaded(page);

    await expect(page.getByLabel('IdP metadata URL')).toHaveValue(IDP_METADATA_URL);
    await expect(page.getByLabel('IdP entity ID')).toHaveValue(IDP_ENTITY_ID);
    await expect(page.getByLabel('Signing certificate (PEM)')).toHaveValue('********');

    // Edit only the entity ID. The frontend strips the masked cert from the
    // payload (`signing_cert_pem: undefined`), so the backend preserves the
    // existing ciphertext. We assert via the response shape that the cert is
    // still configured (masked) after the save.
    await page.getByLabel('IdP entity ID').fill(IDP_ENTITY_ID_UPDATED);

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/saml-config$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as {
      idp_entity_id?: string;
      signing_cert_pem?: string;
    };
    expect(body.idp_entity_id).toBe(IDP_ENTITY_ID_UPDATED);
    expect(body.signing_cert_pem).toBe('********');

    await expect(
      page.getByText('SAML configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('4) disable + save → toast; /login no longer shows SAML button', async ({
    page,
    browser,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/saml');
    await waitForSamlConfigLoaded(page);

    // The previous test left active=true.
    await expect(page.getByRole('switch')).toBeChecked();
    await page.getByRole('switch').click();
    await expect(page.getByRole('switch')).not.toBeChecked();

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/saml-config$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as { active?: boolean };
    expect(body.active).toBe(false);

    await expect(
      page.getByText('SAML configuration saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });

    const probe = await probeLoginSamlButton(browser);
    expect(probe.visible).toBe(false);
  });

  test('5) malformed metadata URL → inline AntD error, no PUT fires', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/saml');
    await waitForSamlConfigLoaded(page);

    // Watch for any PUT — there shouldn't be one because the AntD `type: 'url'`
    // rule blocks the submission before the mutation runs.
    let putFired = false;
    page.on('request', (req) => {
      if (
        req.method() === 'PUT' &&
        /\/api\/v1\/admin\/saml-config$/.test(req.url())
      ) {
        putFired = true;
      }
    });

    await page.getByLabel('IdP metadata URL').fill('not-a-url');
    await page.getByRole('button', { name: 'Save' }).click();

    // Scope the error lookup to the metadata URL Form.Item so an unrelated
    // explain-error elsewhere doesn't accidentally match. AntD renders the
    // explain inside the same .ant-form-item as the field's label.
    const metadataField = page
      .locator('.ant-form-item')
      .filter({ has: page.getByLabel('IdP metadata URL') });
    await expect(
      metadataField.locator('.ant-form-item-explain-error').first(),
    ).toContainText('Enter a valid URL', { timeout: 5_000 });

    expect(putFired).toBe(false);
  });

  test('6) invalid PEM → backend 400, error toast, form stays open', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/saml');
    await waitForSamlConfigLoaded(page);

    // Fix the URL field so we don't trip the new client-side rule, then plant
    // a non-PEM cert so the backend @Pattern rejects with 400.
    await page.getByLabel('IdP metadata URL').fill(IDP_METADATA_URL);
    await page.getByLabel('Signing certificate (PEM)').fill('not-a-cert');

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/saml-config$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(400);
    const body = (await saveResponse.json()) as { error?: string };
    expect(body.error).toBe('VALIDATION_ERROR');

    await expect(page.locator('.ant-message-error').first()).toBeVisible({
      timeout: 10_000,
    });

    // Form stays open with the bad input still present so the user can correct
    // it — saveMutation.onError surfaces the toast but does not navigate or
    // reset the form.
    await expect(page.getByLabel('Signing certificate (PEM)')).toHaveValue(
      'not-a-cert',
    );
  });
});
