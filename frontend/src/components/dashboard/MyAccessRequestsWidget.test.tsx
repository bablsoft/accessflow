import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AccessRequest, AccessRequestPage } from '@/types/api';

const { listAccess } = vi.hoisted(() => ({ listAccess: vi.fn() }));

vi.mock('@/api/accessRequests', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/accessRequests')>('@/api/accessRequests');
  return { ...actual, listMyAccessRequests: listAccess };
});

const { MyAccessRequestsWidget } = await import('./MyAccessRequestsWidget');

function request(overrides: Partial<AccessRequest> = {}): AccessRequest {
  return {
    id: 'ar-1',
    resource_kind: 'DATASOURCE',
    datasource_id: 'ds-1',
    datasource_name: 'Prod',
    connector_id: null,
    connector_name: null,
    requester_id: 'u-1',
    requester_email: 'me@x.io',
    can_read: true,
    can_write: false,
    can_ddl: false,
    allowed_schemas: null,
    allowed_tables: null,
    allowed_operations: null,
    requested_duration: 'PT4H',
    justification: 'debugging',
    pre_approve_queries: false,
    status: 'PENDING',
    expires_at: null,
    granted_permission_id: null,
    created_at: '2026-08-01T00:00:00Z',
    updated_at: '2026-08-01T00:00:00Z',
    ...overrides,
  };
}

function page(content: AccessRequest[]): AccessRequestPage {
  return { content, page: 0, size: 5, total_elements: content.length, total_pages: 1 };
}

function renderWidget() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  return render(<MyAccessRequestsWidget />, { wrapper });
}

describe('MyAccessRequestsWidget', () => {
  beforeEach(() => {
    listAccess.mockResolvedValue(page([request()]));
  });

  it('renders the resource name with a status pill and a view-all link', async () => {
    renderWidget();
    expect(await screen.findByText('Prod')).toBeInTheDocument();
    const viewAll = screen.getByRole('link', { name: /view all/i });
    expect(viewAll).toHaveAttribute('href', '/access-requests');
  });

  it('shows the expiry for an approved grant', async () => {
    listAccess.mockResolvedValue(
      page([request({ status: 'APPROVED', expires_at: '2026-09-01T00:00:00Z' })]),
    );
    renderWidget();
    expect(await screen.findByText(/expires/i)).toBeInTheDocument();
  });

  it('falls back to the connector name for connector-scoped requests', async () => {
    listAccess.mockResolvedValue(
      page([
        request({
          resource_kind: 'API_CONNECTOR',
          datasource_id: null,
          datasource_name: null,
          connector_id: 'c-1',
          connector_name: 'Payments API',
        }),
      ]),
    );
    renderWidget();
    expect(await screen.findByText('Payments API')).toBeInTheDocument();
  });

  it('shows the compact empty state when there are no requests', async () => {
    listAccess.mockResolvedValue(page([]));
    renderWidget();
    expect(await screen.findByText(/haven't requested any access/i)).toBeInTheDocument();
  });
});
