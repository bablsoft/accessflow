import { request as pwRequest } from '@playwright/test';
import { ADMIN_EMAIL, ADMIN_PASSWORD } from './helpers/login';
import { apiBase, loginViaApi } from './helpers/datasources';
import { createApiConnectorViaApi } from './helpers/apiConnectors';
import { createDeploymentPipelineViaApi } from './helpers/deployments';
import { setGovernanceDomainsViaApi } from './helpers/governanceDomains';

/**
 * One-time stack preparation for the main seeded-admin suite.
 *
 * The organization governs **all three** domains, because the suite asserts on surfaces that only
 * exist when it does — `review-hub.spec.ts` expects four review tabs, `dashboard.spec.ts` the API
 * and deployment widgets. The provisioning default is database-only (#926), so this turns both
 * optional domains on.
 *
 * It then seeds one API connector and one deployment pipeline, and that half is **not** optional.
 * Enabling a domain adds an admin-onboarding step ("create your first API connector" /
 * "…deployment pipeline"), and `AppLayout` renders the setup-progress banner above *every* page
 * until every step is satisfied. Left unseeded, that banner lingers through the run and reflows
 * the page under whatever a test is clicking — which is exactly how enabling the domains first
 * broke `admin-users-crud` and `notifications-bell`, two specs that have nothing to do with
 * governance domains. Satisfying the steps the flags add keeps the banner's lifetime the same as
 * it was before the domains were switched on.
 */
const BASELINE_CONNECTOR = 'e2e-baseline-connector';
const BASELINE_PIPELINE = 'e2e-baseline-pipeline';

/** Swallows the duplicate-name conflict a repeat run produces, and only that. */
async function ignoreDuplicate(create: () => Promise<unknown>): Promise<void> {
  try {
    await create();
  } catch (e) {
    const message = (e as Error).message;
    if (!/\b409\b/.test(message)) throw e;
  }
}

async function globalSetup(): Promise<void> {
  const api = await pwRequest.newContext();
  try {
    const token = await loginViaApi(api, ADMIN_EMAIL, ADMIN_PASSWORD);
    await setGovernanceDomainsViaApi(api, token, {
      governs_apis: true,
      governs_deployments: true,
    });
    // Idempotent by construction: `npm test` invokes Playwright twice (the parallel leg, then
    // the serial one), so this runs at least twice against the same stack, and a developer may
    // re-run either against a stack that is already up. A duplicate name is a 409 — the step has
    // already been satisfied, which is all this cares about.
    await ignoreDuplicate(() =>
      createApiConnectorViaApi(api, token, { name: BASELINE_CONNECTOR }),
    );
    await ignoreDuplicate(() =>
      createDeploymentPipelineViaApi(api, token, { name: BASELINE_PIPELINE }),
    );
    // eslint-disable-next-line no-console
    console.log(`[global-setup] ${apiBase()}: governance domains on, onboarding steps seeded`);
  } finally {
    await api.dispose();
  }
}

export default globalSetup;
