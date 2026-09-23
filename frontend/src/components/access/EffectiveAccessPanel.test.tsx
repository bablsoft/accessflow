import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { EffectiveAccessRow } from '@/types/api';

const { effectiveMock, listDatasourcesMock, schemaMock } = vi.hoisted(() => ({
  effectiveMock: vi.fn(),
  listDatasourcesMock: vi.fn(),
  schemaMock: vi.fn(),
}));

vi.mock('@/api/accessSimulations', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/accessSimulations')>('@/api/accessSimulations');
  return { ...actual, getEffectiveAccess: effectiveMock };
});
vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return { ...actual, listDatasources: listDatasourcesMock, getDatasourceSchema: schemaMock };
});

const { EffectiveAccessPanel } = await import('./EffectiveAccessPanel');

function pageOf<T>(content: T[]) {
  return { content, page: 0, size: 20, total_elements: content.length, total_pages: 1 };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

async function lookUp(table = 'public.orders') {
  fireEvent.mouseDown(screen.getByLabelText('Datasource'));
  await waitFor(() =>
    expect(document.querySelector('.ant-select-item-option-content')).not.toBeNull(),
  );
  const option = [...document.querySelectorAll('.ant-select-item-option-content')].find(
    (o) => o.textContent === 'Orders DB',
  );
  fireEvent.click(option!);
  fireEvent.change(screen.getByLabelText('Table'), { target: { value: table } });
  fireEvent.click(screen.getByRole('button', { name: 'Show access' }));
}

const QUERY_ADMIN: EffectiveAccessRow = {
  user_id: 'u-admin',
  email: 'root@x.io',
  display_name: 'Root',
  role_name: 'ADMIN',
  granted: true,
  table_scope: 'ALL_TABLES',
  can_break_glass: false,
  sources: [{ kind: 'QUERY_ADMIN_BYPASS', grants_capability: true, pre_approve_queries: false }],
};

const BREAK_GLASS_ONLY: EffectiveAccessRow = {
  user_id: 'u-bg',
  email: 'oncall@x.io',
  granted: false,
  can_break_glass: true,
  sources: [
    {
      kind: 'GROUP_PERMISSION',
      source_id: 'p-1',
      group_id: 'g-1',
      group_name: 'On-call',
      grants_capability: false,
      table_scope: 'ALLOW_LISTED',
      covering_allow_list_entry: 'public.orders',
      expires_at: '2026-10-01T00:00:00Z',
      pre_approve_queries: false,
    },
  ],
};

describe('EffectiveAccessPanel', () => {
  beforeEach(() => {
    effectiveMock.mockReset();
    listDatasourcesMock.mockReset();
    schemaMock.mockReset();
    listDatasourcesMock.mockResolvedValue(pageOf([{ id: 'd-1', name: 'Orders DB', db_type: 'POSTGRESQL' }]));
    schemaMock.mockResolvedValue({
      schemas: [{ name: 'public', tables: [{ name: 'orders', columns: [], foreign_keys: [] }] }],
    });
  });

  it('asks for a lookup before loading anything', async () => {
    render(wrap(<EffectiveAccessPanel />));
    expect(await screen.findByText('Pick a datasource, a table and a capability')).toBeInTheDocument();
    expect(effectiveMock).not.toHaveBeenCalled();
  });

  it('renders a QUERY_ADMIN holder with zero permission rows with the bypass flag', async () => {
    effectiveMock.mockResolvedValue(pageOf([QUERY_ADMIN, BREAK_GLASS_ONLY]));
    render(wrap(<EffectiveAccessPanel />));
    await lookUp();

    expect(await screen.findByTestId('effective-access-table')).toBeInTheDocument();
    expect(effectiveMock).toHaveBeenCalledWith({
      datasource_id: 'd-1',
      table: 'public.orders',
      capability: 'WRITE',
      page: 0,
      size: 20,
    });
    expect(within(screen.getByTestId('query-admin-u-admin')).getByText('Yes')).toBeInTheDocument();
    expect(within(screen.getByTestId('break-glass-u-admin')).getByText('No')).toBeInTheDocument();
    expect(screen.getByText('QUERY_ADMIN bypass', { selector: 'span' })).toBeInTheDocument();
  });

  it('keeps break-glass in its own column, apart from "granted"', async () => {
    effectiveMock.mockResolvedValue(pageOf([BREAK_GLASS_ONLY]));
    render(wrap(<EffectiveAccessPanel />));
    await lookUp();

    expect(await screen.findByTestId('effective-access-table')).toBeInTheDocument();
    expect(within(screen.getByTestId('break-glass-u-bg')).getByText('Yes')).toBeInTheDocument();
    expect(within(screen.getByTestId('query-admin-u-bg')).getByText('No')).toBeInTheDocument();
    expect(screen.getByText('Group permission')).toBeInTheDocument();
    expect(screen.getByText(/via group On-call · allow-list: public.orders · expires/)).toBeInTheDocument();
    expect(screen.getByText(/does not grant this capability/)).toBeInTheDocument();
  });

  it('renders the empty state and the error detail', async () => {
    effectiveMock.mockResolvedValueOnce(pageOf([]));
    render(wrap(<EffectiveAccessPanel />));
    await lookUp();
    expect(await screen.findByText('No identity can perform this on the table')).toBeInTheDocument();

    effectiveMock.mockRejectedValueOnce({
      isAxiosError: true,
      response: { status: 400, data: { title: 'Bad Request', detail: 'Table name is not valid' } },
    });
    await lookUp('   x   ');
    expect(await screen.findByText('Could not load effective access')).toBeInTheDocument();
    // The server detail, never the generic ProblemDetail title.
    expect(screen.getByText('Table name is not valid')).toBeInTheDocument();
    expect(screen.queryByText('Bad Request')).not.toBeInTheDocument();
  });
});
