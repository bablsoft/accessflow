import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { PrivilegedAccessPage as PrivilegedAccessPageEnvelope, PrivilegedAccessRow } from '@/types/api';

const { listMock } = vi.hoisted(() => ({ listMock: vi.fn() }));

vi.mock('@/api/privilegedAccess', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/privilegedAccess')>('@/api/privilegedAccess');
  return { ...actual, listPrivilegedAccess: listMock };
});

const { default: PrivilegedAccessPage } = await import('./PrivilegedAccessPage');

function row(overrides: Partial<PrivilegedAccessRow> = {}): PrivilegedAccessRow {
  return {
    user_id: 'u-1',
    email: 'root@example.com',
    display_name: 'Ops Root',
    role_id: 'r-admin',
    role_name: 'ADMIN',
    system_role: true,
    bypass_kinds: ['QUERY_ADMIN'],
    query_admin: { role_id: 'r-admin', role_name: 'ADMIN', system_role: true },
    break_glass_grants: [],
    evidence: {
      submitted_query_count: 143,
      last_submitted_at: '2026-09-10T14:02:11Z',
      break_glass_execution_count: 0,
      last_break_glass_at: null,
    },
    ...overrides,
  };
}

function pageOf(content: PrivilegedAccessRow[]): PrivilegedAccessPageEnvelope {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: content.length === 0 ? 0 : 1,
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

describe('PrivilegedAccessPage', () => {
  beforeEach(() => {
    listMock.mockReset();
  });

  it('renders a QUERY_ADMIN holder as a bypass with its role and evidence', async () => {
    listMock.mockResolvedValue(pageOf([row()]));

    render(wrap(<PrivilegedAccessPage />));

    expect(await screen.findByText('root@example.com')).toBeInTheDocument();
    expect(screen.getByText('Ops Root')).toBeInTheDocument();
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    expect(screen.getByText('System role')).toBeInTheDocument();
    expect(screen.getByText('Query admin')).toBeInTheDocument();
    expect(screen.getByText('143')).toBeInTheDocument();
    // No break-glass grants and no break-glass runs: a dash and "Never", never a zero date.
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('Never')).toBeInTheDocument();
    expect(screen.getByText('1 identity')).toBeInTheDocument();
  });

  it('renders a break-glass holder with each datasource, its provenance and expiry', async () => {
    listMock.mockResolvedValue(
      pageOf([
        row({
          user_id: 'u-2',
          email: 'oncall@example.com',
          display_name: null,
          role_name: 'Data steward',
          system_role: false,
          bypass_kinds: ['BREAK_GLASS'],
          query_admin: null,
          break_glass_grants: [
            {
              datasource_id: 'ds-1',
              datasource_name: 'analytics',
              source_kind: 'GROUP',
              source_id: 's-1',
              group_id: 'g-1',
              group_name: 'payments-oncall',
              expires_at: '2026-10-01T00:00:00Z',
            },
            {
              datasource_id: 'ds-2',
              datasource_name: 'payments-prod',
              source_kind: 'DIRECT',
              source_id: 's-2',
              group_id: null,
              group_name: null,
              expires_at: null,
            },
          ],
          evidence: {
            submitted_query_count: 3,
            last_submitted_at: '2026-09-01T00:00:00Z',
            break_glass_execution_count: 2,
            last_break_glass_at: '2026-08-30T03:14:00Z',
          },
        }),
      ]),
    );

    render(wrap(<PrivilegedAccessPage />));

    expect(await screen.findByText('Break-glass')).toBeInTheDocument();
    expect(screen.getByText('Custom role')).toBeInTheDocument();
    expect(screen.getByText('analytics')).toBeInTheDocument();
    expect(screen.getByText(/via payments-oncall/)).toBeInTheDocument();
    expect(screen.getByText(/expires /)).toBeInTheDocument();
    expect(screen.getByText('payments-prod')).toBeInTheDocument();
    expect(screen.getByText(/Direct grant · never expires/)).toBeInTheDocument();
    expect(screen.queryByText('Query admin')).not.toBeInTheDocument();
    expect(screen.getAllByText(/last /)).toHaveLength(2);
    // The email is used for the identity when there is no display name — rendered once, not twice.
    expect(screen.getAllByText('oncall@example.com')).toHaveLength(2);
  });

  it('renders both pills on an identity that holds both paths', async () => {
    listMock.mockResolvedValue(pageOf([row({ bypass_kinds: ['QUERY_ADMIN', 'BREAK_GLASS'] })]));

    render(wrap(<PrivilegedAccessPage />));

    expect(await screen.findByText('Query admin')).toBeInTheDocument();
    expect(screen.getByText('Break-glass')).toBeInTheDocument();
  });

  it('shows an empty state when nothing matches', async () => {
    listMock.mockResolvedValue(pageOf([]));

    render(wrap(<PrivilegedAccessPage />));

    expect(await screen.findByText('No privileged access')).toBeInTheDocument();
  });

  it('surfaces a load failure instead of an empty table', async () => {
    listMock.mockRejectedValue(new Error('boom'));

    render(wrap(<PrivilegedAccessPage />));

    expect(
      await screen.findByText('Could not load the privileged-access report'),
    ).toBeInTheDocument();
    expect(screen.getByText('boom')).toBeInTheDocument();
  });

  it('forwards the kind and user-id filters to the request and resets the page', async () => {
    listMock.mockResolvedValue(pageOf([row()]));

    render(wrap(<PrivilegedAccessPage />));
    await screen.findByText('root@example.com');
    expect(listMock.mock.calls[0]?.[0]).toEqual({ page: 0, size: 20 });

    const uuid = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';
    fireEvent.change(screen.getByLabelText('Filter by user id'), {
      target: { value: ` ${uuid} ` },
    });

    await waitFor(() =>
      expect(listMock.mock.calls.at(-1)?.[0]).toEqual({ page: 0, size: 20, user_id: uuid }),
    );

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Bypass kind' }));
    fireEvent.click(await screen.findByTitle('Break-glass'));

    await waitFor(() =>
      expect(listMock.mock.calls.at(-1)?.[0]).toEqual({
        page: 0,
        size: 20,
        kind: 'BREAK_GLASS',
        user_id: uuid,
      }),
    );
  });

  /** A half-typed id would be a guaranteed 400 server-side, so it must not reach the request. */
  it('does not send a user id that is not yet a whole UUID', async () => {
    listMock.mockResolvedValue(pageOf([row()]));

    render(wrap(<PrivilegedAccessPage />));
    await screen.findByText('root@example.com');

    fireEvent.change(screen.getByLabelText('Filter by user id'), {
      target: { value: '3f2504e0-4f89' },
    });

    await waitFor(() =>
      expect(screen.getByLabelText('Filter by user id')).toHaveValue('3f2504e0-4f89'),
    );
    expect(listMock.mock.calls.every((c) => c[0]?.user_id === undefined)).toBe(true);
  });

  it('renders a dash rather than a role caption when the identity holds no role', async () => {
    listMock.mockResolvedValue(
      pageOf([
        row({
          role_id: null,
          role_name: null,
          system_role: false,
          bypass_kinds: ['BREAK_GLASS'],
          query_admin: null,
        }),
      ]),
    );

    render(wrap(<PrivilegedAccessPage />));

    await screen.findByText('root@example.com');
    expect(screen.queryByText('Custom role')).not.toBeInTheDocument();
    expect(screen.queryByText('System role')).not.toBeInTheDocument();
  });

  it('refetches on refresh', async () => {
    listMock.mockResolvedValue(pageOf([row()]));

    render(wrap(<PrivilegedAccessPage />));
    await screen.findByText('root@example.com');

    fireEvent.click(screen.getByRole('button', { name: /refresh/i }));

    await waitFor(() => expect(listMock).toHaveBeenCalledTimes(2));
  });
});
