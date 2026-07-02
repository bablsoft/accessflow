import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import type { ApiConnector, PaginatedResponse } from '@/types/api';
import '@/i18n';

const { listApiConnectorsMock } = vi.hoisted(() => ({
  listApiConnectorsMock: vi.fn(),
}));

vi.mock('@/api/apiConnectors', () => ({
  listApiConnectors: listApiConnectorsMock,
  listApiOperations: vi.fn(),
  apiConnectorKeys: {
    list: (filters: unknown) => ['api-connectors', 'list', filters] as const,
    operations: (id: string) => ['api-connectors', 'detail', id, 'operations'] as const,
  },
}));

vi.mock('@/api/apiRequests', () => ({
  analyzeApiCall: vi.fn(),
  generateApiCall: vi.fn(),
  submitApiRequest: vi.fn(),
}));

vi.mock('@/components/apigov/ApiRequestComposer', () => ({
  ApiRequestComposer: () => <div data-testid="composer-stub" />,
}));

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const ApiEditorPage = (await import('./ApiEditorPage')).default;

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

function pageOf(content: ApiConnector[]): PaginatedResponse<ApiConnector> {
  return {
    content,
    page: 0,
    size: 100,
    total_elements: content.length,
    total_pages: content.length ? 1 : 0,
    last: true,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ApiEditorPage — empty-state guard (FE-557)', () => {
  beforeEach(() => {
    listApiConnectorsMock.mockReset();
    navigateMock.mockReset();
  });

  it('renders a loading message while the connectors query is pending', () => {
    // Never-resolving promise keeps the query in the loading state.
    listApiConnectorsMock.mockReturnValue(new Promise<never>(() => {}));

    render(wrap(<ApiEditorPage />));

    expect(screen.getByText('Loading connectors…')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Submit for review/i })).toBeNull();
  });

  it('shows the empty-state (not the form) when no connectors are accessible', async () => {
    listApiConnectorsMock.mockResolvedValue(pageOf([]));

    render(wrap(<ApiEditorPage />));

    expect(
      await screen.findByText(
        'No API connectors assigned — ask an administrator to grant you access.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Submit for review/i })).toBeNull();
  });

  it('renders the editor unchanged when at least one connector exists', async () => {
    listApiConnectorsMock.mockResolvedValue(pageOf([baseConnector]));

    render(wrap(<ApiEditorPage />));

    // Editor form renders — Submit button is the canonical signal.
    expect(await screen.findByRole('button', { name: /Submit for review/i })).toBeInTheDocument();
    expect(
      screen.queryByText('No API connectors assigned — ask an administrator to grant you access.'),
    ).toBeNull();
  });
});
