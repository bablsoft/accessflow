import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';

export default defineConfig({
  testDir: './tests',
  // auth-setup-wizard.spec.ts runs against a different stack
  // (docker-compose.e2e.setup.yml on ports 5174/8081, no pre-seeded admin) and
  // has its own config (playwright.setup.config.ts) with the matching
  // globalSetup/Teardown. Excluded here so `npm test` against the main stack
  // doesn't try to drive a /setup wizard that has no UI to land on.
  testIgnore: ['**/auth-setup-wizard.spec.ts'],
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
