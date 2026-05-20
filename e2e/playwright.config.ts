import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';

export default defineConfig({
  testDir: './tests',
  // Specs that need a different stack are excluded from the main seeded-admin
  // suite — each has its own config + globalSetup/Teardown:
  //   * auth-setup-wizard.spec.ts → docker-compose.e2e.setup.yml on 5174/8081,
  //     no pre-seeded admin (playwright.setup.config.ts).
  //   * auth-saml-login.spec.ts → docker-compose.e2e.sso.yml on 5175/8082/8085,
  //     mock SimpleSAMLphp IdP (playwright.sso.config.ts).
  //   * auth-oauth2-login.spec.ts → docker-compose.e2e.sso.yml on the same
  //     SSO-variant stack (plus mock-oauth2-server on 8086), driven by the
  //     same playwright.sso.config.ts.
  testIgnore: [
    '**/auth-setup-wizard.spec.ts',
    '**/auth-saml-login.spec.ts',
    '**/auth-oauth2-login.spec.ts',
  ],
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }], ['list']]
    : 'list',
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    extraHTTPHeaders: {
      Accept: 'application/json',
    },
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
