import { defineConfig, devices } from '@playwright/test';

// Dedicated Playwright config for the first-run setup-wizard spec.
//
// Why a separate config: this is the only spec that exercises a backend without
// a pre-seeded admin, so it needs a different docker stack
// (docker-compose.e2e.setup.yml) on different host ports (5174 / 8081). Playwright
// has no project-scoped `globalSetup`, so isolating the bring-up/tear-down into
// its own config is the cleanest fit.
//
// Run via `npm run test:setup`. The main suite continues to run via `npm test`
// against the seeded stack — playwright.config.ts now excludes this spec via
// `testIgnore` so it doesn't get picked up there.

const baseURL = process.env.E2E_SETUP_BASE_URL ?? 'http://localhost:5174';

export default defineConfig({
  testDir: './tests',
  testMatch: '**/auth-setup-wizard.spec.ts',
  // Spinning up the variant stack on first run takes ~30–60s for Docker image
  // builds, plus per-test fetches against a cold backend — bump generously over
  // the main 30s default.
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }], ['list']]
    : 'list',
  globalSetup: './global-setup-setup.ts',
  globalTeardown: './global-teardown-setup.ts',
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
