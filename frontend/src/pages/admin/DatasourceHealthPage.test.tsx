import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { DatasourceHealth, DatasourceHealthPage } from '@/types/api';

const { fetchDatasourceHealthMock } = vi.hoisted(() => ({
  fetchDatasourceHealthMock: vi.fn(),
}));

vi.mock('@/api/datasourceHealth', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasourceHealth')>(
    '@/api/datasourceHealth',
  );
  return {
    ...actual,
    fetchDatasourceHealth: fetchDatasourceHealthMock,
  };
});

// Charts library is mocked so tests stay deterministic and don't try to render canvas.
vi.mock('@ant-design/charts', () => ({
  Pie: ({ data }: { data: unknown[] }) => (
    <div data-testid="ant-pie-chart" data-slices={data.length} />
  ),
}));

const { default: DatasourceHealthPage } = await import('./DatasourceHealthPage');

function row(overrides: Partial<DatasourceHealth> = {}): DatasourceHealth {
  return {
    datasource_id: 'd1',
    datasource_name: 'prod-db',
    db_type: 'POSTGRESQL',
    active: true,
    pool_active: 2,
    pool_idle: 8,
    pool_waiting: 0,
    pool_total: 10,
    pool_max: 20,
    queries_last_24h: 42,
    execution_ms_p50: 12.5,
    execution_ms_p95: 88,
    errors_last_24h: 3,
    ...overrides,
  };
}

function page(content: DatasourceHealth[]): DatasourceHealthPage {
  return { content, page: 0, size: 50, total_elements: content.length, total_pages: 1 };
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

describe('DatasourceHealthPage', () => {
  beforeEach(() => {
    fetchDatasourceHealthMock.mockReset();
  });

  it('renders a card with the ring and stats per datasource', async () => {
    fetchDatasourceHealthMock.mockResolvedValue(
      page([row(), row({ datasource_id: 'd2', datasource_name: 'analytics' })]),
    );

    render(wrap(<DatasourceHealthPage />));

    expect(screen.getByText('Datasource health')).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getAllByTestId('datasource-health-card')).toHaveLength(2);
    });
    expect(screen.getByText('prod-db')).toBeInTheDocument();
    expect(screen.getByText('analytics')).toBeInTheDocument();
    expect(screen.getAllByTestId('ant-pie-chart')).toHaveLength(2);
  });

  it('shows the empty state when there are no datasources', async () => {
    fetchDatasourceHealthMock.mockResolvedValue(page([]));

    render(wrap(<DatasourceHealthPage />));

    await waitFor(() => {
      expect(screen.getByText('No datasources yet')).toBeInTheDocument();
    });
  });

  it('shows the error state when the request fails', async () => {
    fetchDatasourceHealthMock.mockRejectedValue(new Error('boom'));

    render(wrap(<DatasourceHealthPage />));

    await waitFor(() => {
      expect(screen.getByText("Couldn't load datasource health")).toBeInTheDocument();
    });
  });

  it('renders "pool not initialised" when pool gauges are null', async () => {
    fetchDatasourceHealthMock.mockResolvedValue(
      page([
        row({
          pool_active: null,
          pool_idle: null,
          pool_waiting: null,
          pool_total: null,
          pool_max: null,
        }),
      ]),
    );

    render(wrap(<DatasourceHealthPage />));

    await waitFor(() => {
      expect(
        screen.getByTestId('datasource-health-pool-uninitialized'),
      ).toBeInTheDocument();
    });
    expect(screen.queryByTestId('ant-pie-chart')).not.toBeInTheDocument();
  });
});
