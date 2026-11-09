import { expect, test } from '@playwright/test';
import { loginViaApi } from '../helpers/datasources';
import {
  createApiConnectorViaApi,
  deleteApiConnectorViaApi,
  waitForApiConnectorProbed,
} from '../helpers/apiConnectors';
import { activeTabPanel, clickTab } from '../helpers/ui';
import { login } from '../helpers/login';

/**
 * #896 — the drift tab and the health badge on the connector settings page.
 *
 * WireMock (`mock-ai`, reachable only from the backend container) serves a fixed OpenAPI document at
 * /specs/petstore.json, so a refresh is always "unchanged": a real finding is covered by the backend
 * integration test (#893). Any HTTP answer is a successful probe, so pointing the connector's base URL
 * at WireMock makes the scheduled health check report HEALTHY.
 */

const ADMIN_EMAIL = 'e2e@accessflow.test';
const ADMIN_PASSWORD = 'E2ePassword!123';
const MOCK_BASE = 'http://mock-ai:8080';
const SPEC_URL = `${MOCK_BASE}/specs/petstore.json`;

test('admin loads a schema by URL, refreshes it on the Drift tab and monitors health', async ({
  page,
  request,
}) => {
  test.setTimeout(300_000);
  const token = await loginViaApi(request, ADMIN_EMAIL, ADMIN_PASSWORD);
  const connector = await createApiConnectorViaApi(request, token, {
    name: `Drift API ${Date.now()}`,
    baseUrl: MOCK_BASE,
    aiAnalysisEnabled: false,
  });

  try {
    await login(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    await page.goto(`/api-connectors/${connector.id}/settings`);

    // Unmonitored connectors read "Not monitored", never a red state.
    await expect(page.getByRole('button', { name: /^Not monitored/ })).toBeVisible();

    // Load the schema from a URL with a refresh interval.
    await clickTab(page, 'Schema');
    const schemaPanel = activeTabPanel(page);
    await schemaPanel.locator('.ant-segmented-item', { hasText: 'From URL' }).click();
    await schemaPanel.getByLabel('Schema URL').fill(SPEC_URL);
    await schemaPanel.getByLabel('Auto-refresh interval (minutes)').fill('30');
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          r.url().endsWith(`/api/v1/api-connectors/${connector.id}/schemas`) &&
          r.ok(),
      ),
      schemaPanel.getByRole('button', { name: 'Upload schema' }).click(),
    ]);
    await expect(schemaPanel.getByText('Every 30 min')).toBeVisible();

    // Refresh now on the Drift tab: the served document is the governed one.
    await clickTab(page, 'Drift');
    const driftPanel = activeTabPanel(page);
    await expect(driftPanel.getByText(SPEC_URL)).toBeVisible();
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'POST' &&
          r.url().includes(`/api-connectors/${connector.id}/schemas/`) &&
          r.url().endsWith('/refresh') &&
          r.ok(),
      ),
      driftPanel.getByRole('button', { name: 'Refresh now' }).click(),
    ]);
    await expect(page.getByText('The schema is unchanged')).toBeVisible();
    await expect(driftPanel.getByText('No drift findings')).toBeVisible();
    // An unchanged refresh records no revision; the catalog is untouched.
    await expect(driftPanel.getByText('No revisions yet')).toBeVisible();
    await expect(driftPanel.getByText(/last refreshed/)).toBeVisible();

    // ?tab=drift deep-links the tab (the drift notification's target).
    await page.goto(`/api-connectors/${connector.id}/settings?tab=drift`);
    await expect(page.getByRole('tab', { name: 'Drift' })).toHaveAttribute('aria-selected', 'true');

    // Opt into health checks every minute.
    await clickTab(page, 'Configuration');
    const configPanel = activeTabPanel(page);
    await configPanel.getByLabel('Health check interval (minutes)').fill('1');
    await Promise.all([
      page.waitForResponse(
        (r) =>
          r.request().method() === 'PUT' &&
          r.url().endsWith(`/api/v1/api-connectors/${connector.id}`) &&
          r.ok(),
      ),
      configPanel.getByRole('button', { name: 'Save' }).click(),
    ]);

    const health = await waitForApiConnectorProbed(request, token, connector.id);
    expect(health.status).toBe('HEALTHY');

    await page.reload();
    // The badge's accessible name is its visible status text.
    const badge = page.getByRole('button', { name: /^Healthy/ });
    await expect(badge).toBeVisible();
    await badge.click();
    const drawer = page.getByRole('dialog').filter({ hasText: 'Health checks' });
    await expect(drawer.getByText('OK', { exact: true }).first()).toBeVisible();
  } finally {
    await deleteApiConnectorViaApi(request, token, connector.id);
  }
});
