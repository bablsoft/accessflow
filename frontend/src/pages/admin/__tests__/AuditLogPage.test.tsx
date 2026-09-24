import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AuditEvent } from '@/types/api';

const listAuditEvents = vi.fn();

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, listAuditEvents: (...args: unknown[]) => listAuditEvents(...args) };
});

const { AuditLogPage } = await import('../AuditLogPage');

function event(partial: Partial<AuditEvent>): AuditEvent {
  return {
    id: 'a-1',
    organization_id: 'org-1',
    actor_id: 'sa-1',
    actor_email: 'ci-bot@example.com',
    actor_display_name: 'CI bot',
    action: 'QUERY_SUBMITTED',
    resource_type: 'query_request',
    resource_id: 'q-1',
    metadata: {},
    ip_address: null,
    user_agent: null,
    created_at: '2026-09-16T08:11:02Z',
    ...partial,
  };
}

function wrap(node: ReactNode, entry = '/admin/audit-log') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <AntdApp>
          <Routes>
            <Route path="/admin/audit-log" element={node} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AuditLogPage — on-behalf-of attribution (#874, #875)', () => {
  beforeEach(() => {
    listAuditEvents.mockReset();
    listAuditEvents.mockResolvedValue({
      content: [
        event({
          on_behalf_of_email: 'alice@example.com',
          metadata: {
            on_behalf_of_user_id: 'u-alice',
            service_account: true,
            api_key_id: 'k-1',
            application_name: 'reporting-service',
            application_name_source: 'header',
          },
        }),
        event({ id: 'a-2', actor_id: 'u-bob', actor_email: 'bob@example.com', actor_display_name: 'Bob' }),
      ],
      page: 0,
      size: 50,
      total_elements: 2,
      total_pages: 1,
    });
  });

  it('renders the chip beside the actor only on attributed rows', async () => {
    render(wrap(<AuditLogPage />));

    const botRow = (await screen.findByText('CI bot')).closest('tr');
    expect(within(botRow!).getByTestId('on-behalf-of-tag')).toHaveTextContent('on behalf of alice@example.com');
    expect(within(botRow!).getByText('via service account')).toBeInTheDocument();
    const bobRow = screen.getByText('Bob').closest('tr');
    expect(within(bobRow!).queryByTestId('on-behalf-of-tag')).not.toBeInTheDocument();
  });

  it('seeds the actor and on-behalf-of filters from the URL and sends them to the API', async () => {
    render(wrap(<AuditLogPage />, '/admin/audit-log?actor_id=sa-1&on_behalf_of_user_id=u-alice'));

    await screen.findByText('CI bot');
    expect(listAuditEvents).toHaveBeenCalledWith(
      expect.objectContaining({ actor_id: 'sa-1', on_behalf_of_user_id: 'u-alice' }),
    );
  });

  it('shows the calling application with an untrusted marker for a header source (#938)', async () => {
    render(wrap(<AuditLogPage />));

    const botRow = (await screen.findByText('CI bot')).closest('tr');
    expect(within(botRow!).getByTestId('audit-application-a-1')).toHaveTextContent('reporting-service');
    expect(within(botRow!).getByTestId('audit-application-a-1-untrusted')).toBeInTheDocument();
    const bobRow = screen.getByText('Bob').closest('tr');
    expect(within(bobRow!).queryByText('reporting-service')).not.toBeInTheDocument();
  });

  it('shows the application in the detail drawer, or a dash when the row has none', async () => {
    render(wrap(<AuditLogPage />));

    fireEvent.click((await screen.findByText('CI bot')).closest('tr')!);
    expect(await screen.findByTestId('audit-detail-application')).toHaveTextContent('reporting-service');
    expect(screen.getByTestId('audit-detail-application-untrusted')).toBeInTheDocument();
  });

  it('seeds and applies the application filter', async () => {
    render(wrap(<AuditLogPage />, '/admin/audit-log?application_name=etl'));
    await screen.findByText('CI bot');
    expect(listAuditEvents).toHaveBeenCalledWith(expect.objectContaining({ application_name: 'etl' }));

    fireEvent.change(screen.getByLabelText('Filter by application'), {
      target: { value: ' billing ' },
    });

    await waitFor(() =>
      expect(listAuditEvents).toHaveBeenLastCalledWith(
        expect.objectContaining({ application_name: 'billing', page: 0 }),
      ),
    );
  });

  it('applies a typed on-behalf-of filter', async () => {
    render(wrap(<AuditLogPage />));
    await screen.findByText('CI bot');

    fireEvent.change(screen.getByLabelText('On behalf of (user id)'), { target: { value: 'u-alice' } });

    await waitFor(() =>
      expect(listAuditEvents).toHaveBeenLastCalledWith(
        expect.objectContaining({ on_behalf_of_user_id: 'u-alice', page: 0 }),
      ),
    );
  });
});
