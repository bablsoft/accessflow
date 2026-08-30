import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';

// Specs that need a different stack are excluded from the main seeded-admin
// suite — each has its own config + globalSetup/Teardown:
//   * auth-setup-wizard.spec.ts → docker-compose.e2e.setup.yml on 5174/8081,
//     no pre-seeded admin (playwright.setup.config.ts).
//   * auth-saml-login.spec.ts → docker-compose.e2e.sso.yml on 5175/8082/8085,
//     mock SimpleSAMLphp IdP (playwright.sso.config.ts).
//   * auth-oauth2-login.spec.ts → docker-compose.e2e.sso.yml on the same
//     SSO-variant stack (plus mock-oauth2-server on 8086), driven by the
//     same playwright.sso.config.ts.
const VARIANT_STACK_SPECS = [
  '**/auth-setup-wizard.spec.ts',
  '**/auth-saml-login.spec.ts',
  '**/auth-oauth2-login.spec.ts',
];

// Specs that CANNOT run concurrently with anything else because they mutate
// state shared by the whole stack — the one seeded org and its one admin —
// or read org-wide aggregates that concurrent writers would corrupt. They run
// in the `serial` project, one file at a time, after the `parallel` project
// (npm test chains the two invocations). Everything else isolates itself with
// Date.now()/randomUUID()-suffixed resources and belongs in `parallel`.
// The full membership rules live in .claude/patterns/e2e-spec.md.
const SERIAL_SPECS = [
  // Org-singleton config mutators — while their edits are live, every other
  // spec sees the changed behavior (broken SMTP, ES-only locale, …).
  '**/admin-languages.spec.ts',
  '**/admin-system-smtp.spec.ts',
  '**/admin-saml-config.spec.ts',
  '**/admin-oauth2-config.spec.ts',
  '**/admin-scim-config.spec.ts',
  '**/admin-slack-config.spec.ts',
  '**/admin-langfuse-config.spec.ts',
  // Seeded-admin identity mutators — password resets and TOTP enrollment on
  // e2e@accessflow.test would break every concurrent login.
  '**/auth-forgot-password.spec.ts',
  '**/auth-totp-login.spec.ts',
  '**/profile-display-and-password.spec.ts',
  '**/profile-totp.spec.ts',
  // Leans on purge-the-whole-mailbox semantics for its resend lifecycle.
  '**/admin-users-invitations.spec.ts',
  // Creates a second organization (orgs are never hard-deleted, only
  // disabled), which flips singleOrganization() into "multi-org" and blanks
  // unauthenticated SSO-provider discovery (AF-456) for the rest of the
  // stack's life. Must run AFTER admin-oauth2-config / admin-saml-config —
  // Playwright runs a project's files in path order, and "platform-…" sorts
  // after "admin-…", which this list relies on.
  '**/platform-organizations.spec.ts',
  // Org-wide aggregate readers — assert on counts/tables that every
  // concurrent spec writes into (audit log, dashboards, anomaly lists).
  '**/admin-audit-log.spec.ts',
  '**/dashboard.spec.ts',
  '**/admin-anomalies.spec.ts',
  '**/admin-datasource-health.spec.ts',
  '**/admin-ai-analyses-dashboard.spec.ts',
  '**/over-provisioned-access.spec.ts',
];

export default defineConfig({
  testDir: './tests',
  testIgnore: VARIANT_STACK_SPECS,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  // File-level parallelism only: describe.serial and test-order dependencies
  // within a file stay intact; distinct files may run on distinct workers.
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // 1 is the safe default so a bare `playwright test` (both projects, one
  // invocation) can never interleave serial-project files. The parallel leg
  // raises it per-invocation: npm test runs
  //   playwright test --project=parallel --workers=$E2E_WORKERS
  // then `--project=serial` at this default.
  workers: 1,
  reporter: process.env.CI
    ? [
        ['github'],
        [
          'html',
          {
            open: 'never',
            // Two invocations (parallel, then serial) must not overwrite each
            // other's report — npm scripts point each leg at its own folder.
            outputFolder: process.env.E2E_REPORT_DIR ?? 'playwright-report',
          },
        ],
        ['list'],
      ]
    : 'list',
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'on-first-retry',
    extraHTTPHeaders: {
      Accept: 'application/json',
    },
  },
  projects: [
    {
      name: 'parallel',
      testIgnore: [...VARIANT_STACK_SPECS, ...SERIAL_SPECS],
      outputDir: 'test-results/parallel',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'serial',
      testMatch: SERIAL_SPECS,
      outputDir: 'test-results/serial',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
