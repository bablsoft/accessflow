import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ApiConnector } from '@/types/api';

const getApiConnector = vi.fn();
const updateApiConnector = vi.fn();
const listReviewPlans = vi.fn();

vi.mock('@/api/apiConnectors', () => ({
  getApiConnector: (...args: unknown[]) => getApiConnector(...args),
  updateApiConnector: (...args: unknown[]) => updateApiConnector(...args),
  listApiSchemas: () => Promise.resolve([]),
  listApiOperations: () => Promise.resolve([]),
  uploadApiSchema: vi.fn(),
  deleteApiSchema: vi.fn(),
  apiConnectorKeys: {
    all: ['api-connectors'] as const,
    lists: () => ['api-connectors', 'list'] as const,
    detail: (id: string) => ['api-connectors', 'detail', id] as const,
    schemas: (id: string) => ['api-connectors', 'detail', id, 'schemas'] as const,
    operations: (id: string) => ['api-connectors', 'detail', id, 'operations'] as const,
  },
}));

vi.mock('@/api/admin', () => ({
  listAiConfigs: () => Promise.resolve([]),
  aiConfigKeys: { all: ['aiConfig'] as const, lists: () => ['aiConfig', 'list'] as const },
}));

vi.mock('@/api/reviewPlans', () => ({
  listReviewPlans: (...args: unknown[]) => listReviewPlans(...args),
  reviewPlanKeys: { all: ['reviewPlans'] as const, lists: () => ['reviewPlans', 'list'] as const },
}));

vi.mock('@/components/apigov/ApiConnectorMaskingTab', () => ({
  ApiConnectorMaskingTab: () => null,
}));
vi.mock('@/components/apigov/ApiConnectorClassificationTab', () => ({
  ApiConnectorClassificationTab: () => null,
}));
vi.mock('@/components/apigov/ApiConnectorPermissionsTab', () => ({
  ApiConnectorPermissionsTab: () => null,
}));

const ApiConnectorSettingsPage = (await import('./ApiConnectorSettingsPage')).default;

const baseConnector: ApiConnector = {
  id: 'conn-1',
  name: 'Petstore',
  protocol: 'REST',
  base_url: 'https://petstore.example.com',
  default_headers: {},
  trace_header_mapping: {},
  timeout_ms: 30000,
  tls_verify: true,
  auth_method: 'NONE',
  has_credentials: false,
  oauth2_token_uri: null,
  oauth2_client_id: null,
  oauth2_scopes: null,
  oauth2_audience: null,
  oauth2_username: null,
  oauth2_grant_type: 'CLIENT_CREDENTIALS',
  oauth2_client_auth: 'CLIENT_SECRET_BASIC',
  oauth2_client_secret_configured: false,
  oauth2_refresh_token_configured: false,
  oauth2_password_configured: false,
  review_plan_id: null,
  ai_analysis_enabled: false,
  ai_config_id: null,
  text_to_api_enabled: false,
  require_review_reads: true,
  require_review_writes: true,
  max_response_bytes: 10485760,
  active: true,
  schema_present: false,
  created_at: '2026-05-01T00:00:00Z',
};

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/api-connectors/conn-1/settings']}>
        <AntdApp>
          <Routes>
            <Route path="/api-connectors/:id/settings" element={node} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ApiConnectorSettingsPage — review plan (AF-579)', () => {
  beforeEach(() => {
    getApiConnector.mockReset();
    updateApiConnector.mockReset();
    listReviewPlans.mockReset();
    listReviewPlans.mockResolvedValue([{ id: 'plan-1', name: 'Two approvals' }]);
  });

  it('shows the assigned review plan in the config form', async () => {
    getApiConnector.mockResolvedValue({ ...baseConnector, review_plan_id: 'plan-1' });

    render(wrap(<ApiConnectorSettingsPage />));

    expect(await screen.findByLabelText('Review plan')).toBeInTheDocument();
    expect(await screen.findByText('Two approvals')).toBeInTheDocument();
  });

  it('submits the assigned plan without the clear flag', async () => {
    getApiConnector.mockResolvedValue({ ...baseConnector, review_plan_id: 'plan-1' });
    updateApiConnector.mockResolvedValue({ ...baseConnector, review_plan_id: 'plan-1' });

    render(wrap(<ApiConnectorSettingsPage />));
    await screen.findByLabelText('Review plan');

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(updateApiConnector).toHaveBeenCalledWith(
        'conn-1',
        expect.objectContaining({ review_plan_id: 'plan-1', clear_review_plan: false }),
      ),
    );
  });

  it('sends clear_review_plan when the plan is cleared', async () => {
    getApiConnector.mockResolvedValue({ ...baseConnector, review_plan_id: 'plan-1' });
    updateApiConnector.mockResolvedValue(baseConnector);

    const { container } = render(wrap(<ApiConnectorSettingsPage />));
    await screen.findByLabelText('Review plan');
    await screen.findByText('Two approvals');

    // The review plan select is the only one holding a value, so its clear
    // affordance is the only .ant-select-clear in the tree.
    const clearIcon = container.querySelector('.ant-select-clear');
    expect(clearIcon).not.toBeNull();
    fireEvent.mouseDown(clearIcon!);
    fireEvent.click(clearIcon!);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(updateApiConnector).toHaveBeenCalledWith(
        'conn-1',
        expect.objectContaining({ clear_review_plan: true }),
      ),
    );
  });
});
