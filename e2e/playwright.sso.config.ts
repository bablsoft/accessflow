import { defineConfig, devices } from '@playwright/test';

// Dedicated Playwright config for the SAML SSO spec.
//
// Why a separate config: this is the only spec that exercises a backend with
// a mock SAML IdP wired in via bootstrap env vars. It needs a different
// docker stack (docker-compose.e2e.sso.yml) on different host ports
// (5175 / 8082 / 8085) so it can coexist with the main (5173 / 8080) and
// setup-variant (5174 / 8081) stacks. Playwright has no project-scoped
// `globalSetup`, so isolating the bring-up/tear-down into its own config is
// the cleanest fit — mirrors the setup-wizard variant.
//
// Run via `npm run test:sso`. The main suite continues to run via `npm test`
// against the seeded stack — playwright.config.ts excludes this spec via
// `testIgnore` so it doesn't get picked up there.

const baseURL = process.env.E2E_SSO_BASE_URL ?? 'http://localhost:5175';

export default defineConfig({
  testDir: './tests',
  testMatch: '**/auth-saml-login.spec.ts',
  // The SAML roundtrip (browser → IdP login → SAMLResponse POST → backend
  // ACS → success handler → callback exchange) involves multiple cross-origin
  // navigations through a cold SimpleSAMLphp warm-up, so bump the default
  // 30s ceiling.
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: 1,
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }], ['list']]
    : 'list',
  globalSetup: './global-setup-sso.ts',
  globalTeardown: './global-teardown-sso.ts',
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    extraHTTPHeaders: {
      Accept: 'application/json',
    },
    // The IdP login page is served on a different origin (port 8085). Allow
    // navigation to it without triggering Playwright's strict navigation
    // checks.
    ignoreHTTPSErrors: true,
  },
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // SimpleSAMLphp emits entityID + SSOService.php URLs based on the
        // request's HTTP Host header. Because the backend fetches metadata
        // via the docker-network alias `saml-idp:8080`, those URLs all use
        // that hostname — which the host-side browser cannot resolve. We
        // remap them to the published host port (8085) so the browser can
        // follow the Spring SAML redirect to the IdP login form.
        //
        // See docker-compose.e2e.sso.yml → saml-idp service for the matching
        // env block.
        launchOptions: {
          args: ['--host-resolver-rules=MAP saml-idp:8080 127.0.0.1:8085'],
        },
      },
    },
  ],
});
