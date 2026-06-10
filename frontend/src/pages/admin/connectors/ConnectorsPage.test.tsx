import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { Connector } from '@/types/api';

const listConnectors = vi.fn();
const installConnector = vi.fn();

vi.mock('@/api/connectors', () => ({
  listConnectors: (...args: unknown[]) => listConnectors(...args),
  installConnector: (...args: unknown[]) => installConnector(...args),
  connectorKeys: {
    all: ['connectors'] as const,
    lists: () => ['connectors', 'list'] as const,
  },
}));

import ConnectorsPage from './ConnectorsPage';

const postgres: Connector = {
  id: 'postgresql',
  db_type: 'POSTGRESQL',
  category: 'RELATIONAL',
  name: 'PostgreSQL',
  icon_url: '/db-icons/postgresql.svg',
  vendor: 'PGDG',
  description: 'Relational database.',
  documentation_url: 'https://jdbc.postgresql.org/',
  default_port: 5432,
  default_ssl_mode: 'VERIFY_FULL',
  jdbc_url_template: 'jdbc:postgresql://{host}:{port}/{database_name}',
  driver_class: 'org.postgresql.Driver',
  driver_status: 'READY',
  bundled: true,
};

const clickhouse: Connector = {
  id: 'clickhouse',
  db_type: 'CUSTOM',
  category: 'RELATIONAL',
  name: 'ClickHouse',
  icon_url: '/db-icons/clickhouse.svg',
  vendor: 'ClickHouse, Inc.',
  description: 'Column-oriented OLAP database.',
  documentation_url: 'https://clickhouse.com/docs',
  default_port: 8123,
  default_ssl_mode: 'DISABLE',
  jdbc_url_template: 'jdbc:ch://{host}:{port}/{database_name}',
  driver_class: 'com.clickhouse.jdbc.ClickHouseDriver',
  driver_status: 'AVAILABLE',
  bundled: false,
};

function renderPage(): void {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const ui: ReactNode = (
    <QueryClientProvider client={client}>
      <AntdApp>
        <MemoryRouter>
          <ConnectorsPage />
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>
  );
  render(ui);
}

describe('ConnectorsPage', () => {
  beforeEach(() => {
    listConnectors.mockReset();
    installConnector.mockReset();
  });

  it('renders a card per connector with an install action for available ones', async () => {
    listConnectors.mockResolvedValue([postgres, clickhouse]);
    renderPage();

    expect(await screen.findByText('ClickHouse')).toBeInTheDocument();
    // 'PostgreSQL' appears twice (card title + db-type badge).
    expect(screen.getAllByText('PostgreSQL').length).toBeGreaterThan(0);
    // Bundled/READY connector shows "Installed", not an install button.
    expect(screen.getByText('Installed')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /install/i })).toBeInTheDocument();
  });

  it('installs an available connector and shows a success message', async () => {
    listConnectors.mockResolvedValue([clickhouse]);
    installConnector.mockResolvedValue({ ...clickhouse, driver_status: 'READY' });
    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /install/i }));

    await waitFor(() => expect(installConnector).toHaveBeenCalled());
    expect(installConnector.mock.calls[0]?.[0]).toBe('clickhouse');
    expect(await screen.findByText('Installed ClickHouse')).toBeInTheDocument();
  });

  it('filters connectors by the search box', async () => {
    listConnectors.mockResolvedValue([postgres, clickhouse]);
    renderPage();

    await screen.findByText('ClickHouse');
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search connectors' }), {
      target: { value: 'click' },
    });

    expect(screen.getByText('ClickHouse')).toBeInTheDocument();
    expect(screen.queryByText('PostgreSQL')).not.toBeInTheDocument();
  });
});
