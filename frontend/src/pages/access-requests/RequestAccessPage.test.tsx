import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AccessRequest } from '@/types/api';

const listRequestableDatasources = vi.fn();
const listRequestableConnectors = vi.fn();
const listRequestableConnectorOperations = vi.fn();
const listMyAccessRequests = vi.fn();
const submitAccessRequest = vi.fn();
const cancelAccessRequest = vi.fn();

vi.mock('@/api/accessRequests', () => ({
  listRequestableDatasources: (...args: unknown[]) => listRequestableDatasources(...args),
  listRequestableConnectors: (...args: unknown[]) => listRequestableConnectors(...args),
  listRequestableConnectorOperations: (...args: unknown[]) =>
    listRequestableConnectorOperations(...args),
  listMyAccessRequests: (...args: unknown[]) => listMyAccessRequests(...args),
  submitAccessRequest: (...args: unknown[]) => submitAccessRequest(...args),
  cancelAccessRequest: (...args: unknown[]) => cancelAccessRequest(...args),
  getRequestableDatasourceSchema: vi.fn(),
  accessRequestKeys: {
    all: ['access-requests'] as const,
    mine: (filters: unknown) => ['access-requests', 'mine', filters] as const,
    datasources: () => ['access-requests', 'datasources'] as const,
    schema: (id: string) => ['access-requests', 'datasources', id, 'schema'] as const,
    connectors: () => ['access-requests', 'connectors'] as const,
    connectorOperations: (id: string) =>
      ['access-requests', 'connectors', id, 'operations'] as const,
  },
}));

const { RequestAccessPage } = await import('./RequestAccessPage');

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AntdApp>{node}</AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function baseRequest(overrides: Partial<AccessRequest>): AccessRequest {
  return {
    id: 'req-1',
    resource_kind: 'DATASOURCE',
    datasource_id: 'ds-1',
    datasource_name: 'analytics',
    connector_id: null,
    connector_name: null,
    requester_id: 'u-1',
    requester_email: 'u@x.io',
    can_read: true,
    can_write: false,
    can_ddl: false,
    allowed_schemas: null,
    allowed_tables: null,
    allowed_operations: null,
    requested_duration: 'PT4H',
    justification: 'need it',
    pre_approve_queries: false,
    status: 'PENDING',
    expires_at: null,
    granted_permission_id: null,
    created_at: '2026-07-14T10:00:00Z',
    updated_at: '2026-07-14T10:00:00Z',
    ...overrides,
  };
}

const emptyPage = { content: [], page: 0, size: 50, total_elements: 0, total_pages: 0 };

describe('RequestAccessPage — resource type selector (AF-567)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listRequestableDatasources.mockResolvedValue([{ id: 'ds-1', name: 'analytics' }]);
    listRequestableConnectors.mockResolvedValue([
      { id: 'conn-1', name: 'billing-api', protocol: 'REST' },
    ]);
    listRequestableConnectorOperations.mockResolvedValue([
      { operation_id: 'getPets', verb: 'GET', path: '/pets', summary: 'List pets', write: false },
    ]);
    listMyAccessRequests.mockResolvedValue(emptyPage);
  });

  it('defaults to the datasource form with a DDL capability', async () => {
    render(wrap(<RequestAccessPage />));

    expect(await screen.findByRole('combobox', { name: 'Datasource' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: 'DDL' })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'API Connection' })).not.toBeInTheDocument();
  });

  it('switching to API Connection swaps the picker, drops DDL, and offers operations', async () => {
    render(wrap(<RequestAccessPage />));
    await screen.findByRole('combobox', { name: 'Datasource' });

    fireEvent.click(screen.getByRole('radio', { name: 'API Connection' }));

    expect(await screen.findByRole('combobox', { name: 'API Connection' })).toBeInTheDocument();
    expect(screen.queryByRole('combobox', { name: 'Datasource' })).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: 'DDL' })).not.toBeInTheDocument();
    expect(screen.queryByText('Pre-approve queries under this grant')).not.toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Allowed operations (optional)' })).toBeInTheDocument();
    await waitFor(() => expect(listRequestableConnectors).toHaveBeenCalled());
  });

  it('submits a connector request with connector_id and no DDL', async () => {
    submitAccessRequest.mockResolvedValue(baseRequest({}));
    render(wrap(<RequestAccessPage />));
    await screen.findByRole('combobox', { name: 'Datasource' });

    fireEvent.click(screen.getByRole('radio', { name: 'API Connection' }));

    const connectorSelect = await screen.findByRole('combobox', { name: 'API Connection' });
    fireEvent.mouseDown(connectorSelect);
    fireEvent.click(await screen.findByText('billing-api (REST)'));

    fireEvent.change(screen.getByLabelText('Justification'), {
      target: { value: 'temporary access' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Submit request' }));

    await waitFor(() => expect(submitAccessRequest).toHaveBeenCalled());
    expect(submitAccessRequest.mock.calls[0]?.[0]).toEqual({
      connector_id: 'conn-1',
      can_read: true,
      can_write: false,
      can_ddl: false,
      allowed_operations: null,
      requested_duration: 'PT4H',
      justification: 'temporary access',
    });
  });

  it('renders connector requests in the list with a kind tag and operations count', async () => {
    listMyAccessRequests.mockResolvedValue({
      ...emptyPage,
      content: [
        baseRequest({
          id: 'req-2',
          resource_kind: 'API_CONNECTOR',
          datasource_id: null,
          datasource_name: null,
          connector_id: 'conn-1',
          connector_name: 'billing-api',
          allowed_operations: ['getPets', 'addPet'],
          status: 'REJECTED',
        }),
      ],
      total_elements: 1,
    });

    render(wrap(<RequestAccessPage />));

    expect(await screen.findByText('billing-api')).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(table).toHaveTextContent('API Connection');
    expect(table).toHaveTextContent('2 operations');
  });
});
