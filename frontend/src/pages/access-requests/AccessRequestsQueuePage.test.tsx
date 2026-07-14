import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { PendingAccessRequestItem } from '@/types/api';

const listPendingAccessRequests = vi.fn();
const approveAccessRequest = vi.fn();
const rejectAccessRequest = vi.fn();

vi.mock('@/api/accessRequests', () => ({
  listPendingAccessRequests: (...args: unknown[]) => listPendingAccessRequests(...args),
  approveAccessRequest: (...args: unknown[]) => approveAccessRequest(...args),
  rejectAccessRequest: (...args: unknown[]) => rejectAccessRequest(...args),
  accessRequestKeys: {
    all: ['access-requests'] as const,
    queue: () => ['access-requests', 'queue'] as const,
    queueFor: (filters: unknown) => ['access-requests', 'queue', filters] as const,
  },
}));

const { AccessRequestsQueuePage } = await import('./AccessRequestsQueuePage');

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

function pending(overrides: Partial<PendingAccessRequestItem>): PendingAccessRequestItem {
  return {
    id: 'req-1',
    resource_kind: 'DATASOURCE',
    datasource: { id: 'ds-1', name: 'analytics' },
    connector: null,
    requested_by: { id: 'u-1', email: 'analyst@x.io' },
    can_read: true,
    can_write: false,
    can_ddl: false,
    allowed_schemas: null,
    allowed_tables: null,
    allowed_operations: null,
    requested_duration: 'PT4H',
    justification: 'need it',
    pre_approve_queries: false,
    current_stage: 0,
    created_at: '2026-07-14T10:00:00Z',
    ...overrides,
  };
}

describe('AccessRequestsQueuePage — mixed resource kinds (AF-567)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listPendingAccessRequests.mockResolvedValue({
      content: [
        pending({}),
        pending({
          id: 'req-2',
          resource_kind: 'API_CONNECTOR',
          datasource: null,
          connector: { id: 'conn-1', name: 'billing-api' },
          allowed_operations: ['getPets'],
        }),
      ],
      page: 0,
      size: 50,
      total_elements: 2,
      total_pages: 1,
    });
  });

  it('renders both kinds with a kind tag and the resource name', async () => {
    render(wrap(<AccessRequestsQueuePage />));

    expect(await screen.findByText('analytics')).toBeInTheDocument();
    expect(screen.getByText('billing-api')).toBeInTheDocument();
    const table = screen.getByRole('table');
    expect(table).toHaveTextContent('API Connection');
    expect(table).toHaveTextContent('Datasource');
    expect(table).toHaveTextContent('1 operation');
  });

  it('approves a connector request from the queue', async () => {
    approveAccessRequest.mockResolvedValue({
      access_request_id: 'req-2',
      decision_id: 'd-1',
      decision: 'APPROVED',
      resulting_status: 'APPROVED',
      idempotent_replay: false,
    });

    render(wrap(<AccessRequestsQueuePage />));
    await screen.findByText('billing-api');

    const approveButtons = screen.getAllByRole('button', { name: /Approve/ });
    fireEvent.click(approveButtons[1]!);

    await waitFor(() => expect(approveAccessRequest).toHaveBeenCalled());
    expect(approveAccessRequest.mock.calls[0]?.[0]).toBe('req-2');
  });
});
