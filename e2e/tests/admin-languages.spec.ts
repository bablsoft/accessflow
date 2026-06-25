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

// SupportedLanguage backend enum values; afterAll restores these so adjacent
// specs aren't trapped in an EN+ES allow-list.
const ALL_SUPPORTED_LANGUAGES = ['en', 'es', 'de', 'fr', 'zh-CN', 'ru', 'hy'];

function apiBase(): string {
  return process.env.E2E_API_BASE ?? DEFAULT_API_BASE;
}

async function loginViaUi(page: Page, email: string, password: string): Promise<void> {
  await page.goto('/login');
  await page.locator('#login-email').fill(email);
  await page.locator('#login-password').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL('**/dashboard', { timeout: 15_000 });
}

// Reset both the org's localization_config row and the admin user's
// preferred_language back to defaults so the EN+ES restriction (and any
// Spanish UI preference) doesn't bleed into other specs in the run.
async function resetLocalizationState(
  request: APIRequestContext,
  accessToken: string,
): Promise<void> {
  const orgRes = await request.put(`${apiBase()}/api/v1/admin/localization-config`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: {
      available_languages: ALL_SUPPORTED_LANGUAGES,
      default_language: 'en',
      ai_review_language: 'en',
    },
  });
  if (!orgRes.ok()) {
    // eslint-disable-next-line no-console
    console.warn(`Localization-config reset returned ${orgRes.status()}: ${await orgRes.text()}`);
  }
  const meRes = await request.put(`${apiBase()}/api/v1/me/localization`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { language: 'en' },
  });
  if (!meRes.ok()) {
    // eslint-disable-next-line no-console
    console.warn(`Me-localization reset returned ${meRes.status()}: ${await meRes.text()}`);
  }
}

// Probe the LoginPage's language switcher in a fresh context (no cookies, no
// in-memory Zustand auth state). Returns the menu-item labels currently
// rendered after the public GET resolves.
async function probeLoginLanguageOptions(browser: Browser): Promise<string[]> {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    await page.goto('/login');
    // The switcher's menu items come from GET /api/v1/auth/localization-config.
    await page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/auth\/localization-config$/.test(r.url()) &&
        r.status() < 500,
      { timeout: 10_000 },
    );
    await page.getByRole('button', { name: 'Language' }).click();
    // Wait for the dropdown to render its menu items.
    await page.waitForSelector('[role="menuitem"]', { state: 'visible', timeout: 5_000 });
    const labels = await page.getByRole('menuitem').allTextContents();
    return labels.map((s) => s.trim());
  } finally {
    await context.close();
  }
}

// AF-284 covers org-level language config + per-user preference end-to-end:
//   1. Admin restricts allowed languages to EN+ES via /admin/languages.
//   2. Logged-out /login page renders a language selector listing EN+ES only.
//   3. Topbar language switcher flips UI text to Spanish (PUT /me/localization).
//   4. Empty allowed-list is blocked client-side AND backend returns
//      400 ILLEGAL_LOCALIZATION_CONFIG when bypassed.
//   5. Per-user preference to a language outside the allow-list returns
//      400 LANGUAGE_NOT_IN_ALLOWED_LIST.
//
// describe.serial because each scenario depends on the singleton
// localization_config row from the prior one. afterAll restores the row + the
// admin user's preferred_language so neighbouring specs see a clean slate.
test.describe.serial('/admin/languages + per-user preference', () => {
  let adminAccessToken = '';

  test.beforeAll(async ({ request }) => {
    adminAccessToken = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
    // Belt-and-braces: a previous failed run may have left the admin in
    // Spanish and the org's allow-list restricted, which would flip test 1's
    // success-toast assertion language. Restore to defaults before the run.
    await resetLocalizationState(request, adminAccessToken);
  });

  test.afterAll(async ({ request }) => {
    if (adminAccessToken) {
      await resetLocalizationState(request, adminAccessToken);
    }
  });

  test('1) admin restricts allowed languages to EN+ES via UI', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/languages');
    // Wait for GET /api/v1/admin/localization-config so the form values are populated.
    await page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/admin\/localization-config$/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );

    // Uncheck every non-English language to leave only EN. Then check ES.
    for (const code of ['de', 'fr', 'zh-CN', 'ru', 'hy']) {
      const box = page.getByRole('checkbox', { name: new RegExp(`\\(${escapeRegex(code)}\\)`) });
      if (await box.isChecked()) {
        await box.uncheck();
      }
    }
    const enBox = page.getByRole('checkbox', { name: /\(en\)/ });
    if (!(await enBox.isChecked())) {
      await enBox.check();
    }
    const esBox = page.getByRole('checkbox', { name: /\(es\)/ });
    if (!(await esBox.isChecked())) {
      await esBox.check();
    }

    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/localization-config$/.test(r.url()),
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Save' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.status()).toBe(200);
    const body = (await saveResponse.json()) as {
      available_languages?: string[];
      default_language?: string;
    };
    expect(body.available_languages).toEqual(expect.arrayContaining(['en', 'es']));
    expect(body.available_languages).not.toEqual(expect.arrayContaining(['de']));
    expect(body.default_language).toBe('en');

    await expect(
      page.getByText('Language settings saved', { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test('2) /login language selector lists EN+ES only (logged-out)', async ({ browser }) => {
    const labels = await probeLoginLanguageOptions(browser);
    expect(labels).toContain('English');
    expect(labels).toContain('Español');
    // Languages NOT in the allow-list must not appear.
    expect(labels).not.toContain('Deutsch');
    expect(labels).not.toContain('Français');
    expect(labels).not.toContain('Русский');
  });

  test('3) topbar language switcher applies Spanish UI', async ({ page }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    // The topbar switcher is in the post-login app shell; /editor is fine.
    await page.locator('.af-language-switcher').first().click();

    const switchResponsePromise = page.waitForResponse(
      (r) =>
        r.request().method() === 'PUT' &&
        /\/api\/v1\/me\/localization$/.test(r.url()),
      { timeout: 10_000 },
    );
    // Pick Español from the dropdown.
    await page.getByRole('menuitem', { name: 'Español' }).click();
    const switchResponse = await switchResponsePromise;
    expect(switchResponse.status()).toBe(200);
    const switchBody = (await switchResponse.json()) as { current_language?: string };
    expect(switchBody.current_language).toBe('es');

    // A stable Spanish string from the sidebar nav, rendered immediately on
    // i18n.changeLanguage. "Editor SQL" is the es.json translation of
    // nav.editor (en.json: "Query editor"). Use first() because the same
    // string also lives in the page title.
    await expect(page.getByText('Editor SQL').first()).toBeVisible({ timeout: 10_000 });
  });

  test('4) empty allowed-list — UI blocks submit and backend rejects with ILLEGAL_LOCALIZATION_CONFIG', async ({
    page,
  }) => {
    // ----- UI side: client-side AntD rule (min: 1) must block the PUT.
    // After test 3 the admin's preferred_language is 'es', so the UI loads in
    // Spanish. Use the type-attribute selector for the only form submit on the
    // page instead of getByRole({ name: 'Save' }) so the assertion stays
    // language-agnostic.
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto('/admin/languages');
    await page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/api\/v1\/admin\/localization-config$/.test(r.url()) &&
        r.ok(),
      { timeout: 15_000 },
    );

    // Uncheck every language. The display label is the native name + " (code)";
    // the language code is stable across locales.
    for (const code of ['en', 'es', 'de', 'fr', 'zh-CN', 'ru', 'hy']) {
      const box = page.getByRole('checkbox', { name: new RegExp(`\\(${escapeRegex(code)}\\)`) });
      if (await box.isChecked()) {
        await box.uncheck();
      }
    }

    // Watch for a PUT — it must NOT happen because client-side validation blocks it.
    let putFired = false;
    const putListener = (r: import('@playwright/test').Response) => {
      if (
        r.request().method() === 'PUT' &&
        /\/api\/v1\/admin\/localization-config$/.test(r.url())
      ) {
        putFired = true;
      }
    };
    page.on('response', putListener);
    await page.locator('button[type="submit"]').first().click();
    // Give any in-flight request a moment to land. 500ms is enough to
    // distinguish "blocked" from "fired-but-slow"; the form rule fails
    // synchronously.
    await page.waitForTimeout(500);
    page.off('response', putListener);
    expect(putFired).toBe(false);

    // ----- Backend side: an empty allow-list is caught by Bean Validation
    // (`@NotEmpty` on UpdateLocalizationConfigRequest) before it reaches the
    // service, so the GlobalExceptionHandler emits VALIDATION_ERROR — that's
    // the actual contract for the empty-list path.
    const emptyListResult = await page.evaluate(async () => {
      const client = (
        window as unknown as {
          __apiClient?: {
            put: (
              url: string,
              data: unknown,
            ) => Promise<{ status: number; data: unknown }>;
          };
        }
      ).__apiClient;
      if (!client) throw new Error('window.__apiClient is not exposed');
      try {
        const res = await client.put('/api/v1/admin/localization-config', {
          available_languages: [],
          default_language: 'en',
          ai_review_language: 'en',
        });
        return { status: res.status, body: res.data };
      } catch (err) {
        const e = err as { response?: { status: number; data: unknown } };
        return { status: e.response?.status ?? -1, body: e.response?.data ?? null };
      }
    });
    expect(emptyListResult.status).toBe(400);
    expect((emptyListResult.body as { error?: string } | null)?.error).toBe('VALIDATION_ERROR');

    // The service-layer ILLEGAL_LOCALIZATION_CONFIG path fires when the
    // default_language is not in available_languages (passes Bean Validation,
    // fails DefaultLocalizationConfigService.validate).
    const mismatchResult = await page.evaluate(async () => {
      const client = (
        window as unknown as {
          __apiClient?: {
            put: (
              url: string,
              data: unknown,
            ) => Promise<{ status: number; data: unknown }>;
          };
        }
      ).__apiClient;
      if (!client) throw new Error('window.__apiClient is not exposed');
      try {
        const res = await client.put('/api/v1/admin/localization-config', {
          available_languages: ['en'],
          default_language: 'es',
          ai_review_language: 'en',
        });
        return { status: res.status, body: res.data };
      } catch (err) {
        const e = err as { response?: { status: number; data: unknown } };
        return { status: e.response?.status ?? -1, body: e.response?.data ?? null };
      }
    });
    expect(mismatchResult.status).toBe(400);
    expect((mismatchResult.body as { error?: string } | null)?.error).toBe(
      'ILLEGAL_LOCALIZATION_CONFIG',
    );
  });

  test('5) per-user preference outside allow-list returns 400 LANGUAGE_NOT_IN_ALLOWED_LIST', async ({
    page,
  }) => {
    await loginViaUi(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    // Allow-list is EN+ES at this point (set in test 1). Asking for 'de'
    // must be rejected by DefaultUserPreferenceService.setPreferredLanguage.
    const apiResult = await page.evaluate(async () => {
      const client = (
        window as unknown as {
          __apiClient?: {
            put: (
              url: string,
              data: unknown,
            ) => Promise<{ status: number; data: unknown }>;
          };
        }
      ).__apiClient;
      if (!client) throw new Error('window.__apiClient is not exposed');
      try {
        const res = await client.put('/api/v1/me/localization', { language: 'de' });
        return { status: res.status, body: res.data };
      } catch (err) {
        const e = err as { response?: { status: number; data: unknown } };
        return { status: e.response?.status ?? -1, body: e.response?.data ?? null };
      }
    });
    expect(apiResult.status).toBe(400);
    const apiBody = apiResult.body as { error?: string } | null;
    expect(apiBody?.error).toBe('LANGUAGE_NOT_IN_ALLOWED_LIST');
  });
});

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
