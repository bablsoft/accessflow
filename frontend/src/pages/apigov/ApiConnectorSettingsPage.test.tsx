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
const listApiSchemas = vi.fn();
const uploadApiSchema = vi.fn();
const previewApiSchemaFilter = vi.fn();
const updateApiSchemaFilter = vi.fn();

vi.mock('@/api/apiConnectors', () => ({
  getApiConnector: (...args: unknown[]) => getApiConnector(...args),
  updateApiConnector: (...args: unknown[]) => updateApiConnector(...args),
  listApiSchemas: (...args: unknown[]) => listApiSchemas(...args),
  listApiOperations: () => Promise.resolve([]),
  uploadApiSchema: (...args: unknown[]) => uploadApiSchema(...args),
  previewApiSchemaFilter: (...args: unknown[]) => previewApiSchemaFilter(...args),
  updateApiSchemaFilter: (...args: unknown[]) => updateApiSchemaFilter(...args),
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
    listApiSchemas.mockReset();
    listApiSchemas.mockResolvedValue([]);
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

describe('ApiConnectorSettingsPage — schema import filter (AF-614)', () => {
  beforeEach(() => {
    getApiConnector.mockReset();
    getApiConnector.mockResolvedValue(baseConnector);
    listReviewPlans.mockReset();
    listReviewPlans.mockResolvedValue([]);
    listApiSchemas.mockReset();
    listApiSchemas.mockResolvedValue([]);
    uploadApiSchema.mockReset();
    previewApiSchemaFilter.mockReset();
    updateApiSchemaFilter.mockReset();
  });

  /** Activates the Schema tab and pastes a document into the textarea. */
  async function openSchemaTabWithContent() {
    render(wrap(<ApiConnectorSettingsPage />));
    fireEvent.click(await screen.findByRole('tab', { name: 'Schema' }));
    const textarea = await screen.findByPlaceholderText('Schema document');
    fireEvent.change(textarea, { target: { value: 'openapi: 3.0.0' } });
    return textarea;
  }

  it('uploads with a null filter when none is configured', async () => {
    uploadApiSchema.mockResolvedValue({
      id: 's1',
      schema_type: 'OPENAPI',
      source_url: null,
      operation_count: 3,
      total_operation_count: 3,
      operation_filter: null,
      created_at: '2026-05-01T00:00:00Z',
    });

    await openSchemaTabWithContent();
    fireEvent.click(screen.getByRole('button', { name: 'Upload schema' }));

    await waitFor(() =>
      expect(uploadApiSchema).toHaveBeenCalledWith(
        'conn-1',
        expect.objectContaining({
          schema_type: 'OPENAPI',
          raw_content: 'openapi: 3.0.0',
          filter: null,
        }),
      ),
    );
  });

  it('reports the post-filter imported count after upload', async () => {
    uploadApiSchema.mockResolvedValue({
      id: 's1',
      schema_type: 'OPENAPI',
      source_url: null,
      operation_count: 2,
      total_operation_count: 5,
      operation_filter: { excludePaths: ['/internal/**'] },
      created_at: '2026-05-01T00:00:00Z',
    });

    await openSchemaTabWithContent();
    fireEvent.click(screen.getByRole('button', { name: 'Upload schema' }));

    expect(await screen.findByText('2 of 5 operations imported')).toBeInTheDocument();
  });

  it('previews which operations a filter drops without uploading', async () => {
    previewApiSchemaFilter.mockResolvedValue({
      total_count: 2,
      kept_count: 1,
      excluded: [
        {
          operation_id: 'internalSync',
          verb: 'POST',
          path: '/internal/sync',
          summary: null,
          write: true,
        },
      ],
    });

    await openSchemaTabWithContent();
    fireEvent.click(screen.getByRole('button', { name: 'Preview' }));

    await waitFor(() => expect(previewApiSchemaFilter).toHaveBeenCalled());
    expect(await screen.findByText('Keeps 1 of 2 operations')).toBeInTheDocument();
    expect(await screen.findByText('internalSync')).toBeInTheDocument();
    expect(uploadApiSchema).not.toHaveBeenCalled();
  });

  it('threads a configured exclude-path glob into the upload payload', async () => {
    uploadApiSchema.mockResolvedValue({
      id: 's1',
      schema_type: 'OPENAPI',
      source_url: null,
      operation_count: 1,
      total_operation_count: 2,
      operation_filter: { excludePaths: ['/internal/**'] },
      created_at: '2026-05-01T00:00:00Z',
    });

    await openSchemaTabWithContent();
    fireEvent.click(screen.getByText('Import filter (optional)'));

    const excludePaths = await screen.findByLabelText('Exclude paths');
    fireEvent.change(excludePaths, { target: { value: '/internal/**' } });
    fireEvent.keyDown(excludePaths, { key: 'Enter', keyCode: 13 });

    fireEvent.click(screen.getByRole('button', { name: 'Upload schema' }));

    await waitFor(() =>
      expect(uploadApiSchema).toHaveBeenCalledWith(
        'conn-1',
        expect.objectContaining({
          filter: expect.objectContaining({ excludePaths: ['/internal/**'] }),
        }),
      ),
    );
  });

  it('edits an existing schema filter without re-uploading', async () => {
    listApiSchemas.mockResolvedValue([
      {
        id: 's1',
        schema_type: 'OPENAPI',
        source_url: null,
        operation_count: 1,
        total_operation_count: 2,
        operation_filter: { excludePaths: ['/internal/**'], excludeDeprecated: false },
        created_at: '2026-05-01T00:00:00Z',
      },
    ]);
    updateApiSchemaFilter.mockResolvedValue({});

    render(wrap(<ApiConnectorSettingsPage />));
    fireEvent.click(await screen.findByRole('tab', { name: 'Schema' }));

    fireEvent.click(await screen.findByRole('button', { name: 'Edit filter' }));
    const deprecated = await screen.findByRole('checkbox', {
      name: 'Exclude deprecated operations',
    });
    fireEvent.click(deprecated);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(updateApiSchemaFilter).toHaveBeenCalledWith(
        'conn-1',
        's1',
        expect.objectContaining({
          excludePaths: ['/internal/**'],
          excludeDeprecated: true,
        }),
      ),
    );
  });
});
