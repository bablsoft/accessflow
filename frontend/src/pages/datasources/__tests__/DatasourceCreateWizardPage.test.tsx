import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type {
  ConnectionTestResult,
  Datasource,
  DatasourceTypesResponse,
} from '@/types/api';

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual =
    await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return {
    ...actual,
    useNavigate: () => navigateMock,
  };
});

const createDatasource = vi.fn();
const updateDatasource = vi.fn();
const testConnection = vi.fn();
const getDatasourceTypes = vi.fn();

vi.mock('@/api/datasources', () => ({
  createDatasource: (...args: unknown[]) => createDatasource(...args),
  updateDatasource: (...args: unknown[]) => updateDatasource(...args),
  testConnection: (...args: unknown[]) => testConnection(...args),
  getDatasourceTypes: (...args: unknown[]) => getDatasourceTypes(...args),
  datasourceKeys: {
    all: ['datasources'] as const,
    lists: () => ['datasources', 'list'] as const,
    detail: (id: string) => ['datasources', 'detail', id] as const,
    types: () => ['datasources', 'types'] as const,
  },
}));

vi.mock('@/api/admin', () => ({
  listAiConfigs: () => Promise.resolve([]),
  aiConfigKeys: {
    all: ['aiConfig'] as const,
    lists: () => ['aiConfig', 'list'] as const,
  },
  setupProgressKeys: {
    all: ['setupProgress'] as const,
    current: () => ['setupProgress', 'current'] as const,
  },
}));

vi.mock('@/api/reviewPlans', () => ({
  listReviewPlans: () => Promise.resolve([]),
  reviewPlanKeys: {
    all: ['reviewPlans'] as const,
    lists: () => ['reviewPlans', 'list'] as const,
  },
}));

const { default: DatasourceCreateWizardPage } = await import(
  '../DatasourceCreateWizardPage'
);

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AntdApp>{node}</AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

const typesResponse: DatasourceTypesResponse = {
  types: [
    {
      code: 'POSTGRESQL',
      display_name: 'PostgreSQL',
      icon_url: '/db-icons/postgresql.svg',
      default_port: 5432,
      default_ssl_mode: 'REQUIRE',
      jdbc_url_template: 'jdbc:postgresql://{host}:{port}/{database}',
      driver_status: 'READY',
      bundled: true,
    },
  ],
};

const baseDatasource: Datasource = {
  id: 'ds-1',
  organization_id: 'org-1',
  name: 'Prod PG',
  db_type: 'POSTGRESQL',
  host: 'db.internal',
  port: 5432,
  database_name: 'appdb',
  username: 'svc',
  ssl_mode: 'REQUIRE',
  connection_pool_size: 10,
  max_rows_per_query: 10_000,
  require_review_reads: false,
  require_review_writes: true,
  review_plan_id: null,
  ai_analysis_enabled: false,
  ai_config_id: null,
  active: true,
  created_at: '2026-05-12T00:00:00Z',
};

async function fillConnectionAndSubmit(submitLabel: string) {
  fireEvent.change(screen.getByLabelText('Name'), {
    target: { value: 'Prod PG' },
  });
  fireEvent.change(screen.getByLabelText('Host'), {
    target: { value: 'db.internal' },
  });
  fireEvent.change(screen.getByLabelText('Database name'), {
    target: { value: 'appdb' },
  });
  fireEvent.change(screen.getByLabelText('Username'), {
    target: { value: 'svc' },
  });
  fireEvent.change(screen.getByLabelText('Password'), {
    target: { value: 'hunter2' },
  });

  fireEvent.click(screen.getByRole('button', { name: submitLabel }));
}

describe('DatasourceCreateWizardPage', () => {
  beforeEach(() => {
    createDatasource.mockReset();
    updateDatasource.mockReset();
    testConnection.mockReset();
    getDatasourceTypes.mockReset();
    navigateMock.mockReset();
    getDatasourceTypes.mockResolvedValue(typesResponse);
  });

  it('POSTs once on first submit and advances to test step', async () => {
    createDatasource.mockResolvedValueOnce(baseDatasource);

    render(wrap(<DatasourceCreateWizardPage />));

    await screen.findByText('PostgreSQL');
    fireEvent.click(screen.getByText('PostgreSQL'));

    await screen.findByLabelText('Name');
    await fillConnectionAndSubmit('Save and test');

    await waitFor(() => expect(createDatasource).toHaveBeenCalledTimes(1));
    expect(createDatasource).toHaveBeenCalledWith(
      expect.objectContaining({
        name: 'Prod PG',
        host: 'db.internal',
        db_type: 'POSTGRESQL',
        ai_analysis_enabled: false,
        ai_config_id: null,
      }),
    );
    expect(updateDatasource).not.toHaveBeenCalled();

    await screen.findByRole('button', { name: /Test connection/ });
  });

  it('PUTs (not POSTs) when back-editing after a failed test', async () => {
    createDatasource.mockResolvedValueOnce(baseDatasource);
    updateDatasource.mockResolvedValueOnce({
      ...baseDatasource,
      host: 'db2.internal',
    });

    render(wrap(<DatasourceCreateWizardPage />));

    await screen.findByText('PostgreSQL');
    fireEvent.click(screen.getByText('PostgreSQL'));

    await screen.findByLabelText('Name');
    await fillConnectionAndSubmit('Save and test');

    await screen.findByRole('button', { name: /Test connection/ });
    fireEvent.click(screen.getByRole('button', { name: /Back/ }));

    await screen.findByLabelText('Host');
    fireEvent.change(screen.getByLabelText('Host'), {
      target: { value: 'db2.internal' },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Save and continue' }));

    await waitFor(() => expect(updateDatasource).toHaveBeenCalledTimes(1));
    expect(updateDatasource).toHaveBeenCalledWith(
      'ds-1',
      expect.objectContaining({ host: 'db2.internal' }),
    );
    expect(createDatasource).toHaveBeenCalledTimes(1);
  });

  it('settings step PUTs and navigates to /datasources/:id/settings', async () => {
    createDatasource.mockResolvedValueOnce(baseDatasource);
    const testOk: ConnectionTestResult = {
      ok: true,
      latency_ms: 42,
      message: null,
    };
    testConnection.mockResolvedValueOnce(testOk);
    updateDatasource.mockResolvedValueOnce({
      ...baseDatasource,
      connection_pool_size: 20,
    });

    render(wrap(<DatasourceCreateWizardPage />));

    await screen.findByText('PostgreSQL');
    fireEvent.click(screen.getByText('PostgreSQL'));

    await screen.findByLabelText('Name');
    await fillConnectionAndSubmit('Save and test');

    const testBtn = await screen.findByRole('button', {
      name: /Test connection/,
    });
    fireEvent.click(testBtn);

    await waitFor(() => expect(testConnection).toHaveBeenCalledWith('ds-1'));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    await screen.findByLabelText('Connection pool size');
    fireEvent.click(
      screen.getByRole('button', { name: 'Save and finish' }),
    );

    await waitFor(() => expect(updateDatasource).toHaveBeenCalledTimes(1));
    expect(updateDatasource).toHaveBeenCalledWith(
      'ds-1',
      expect.objectContaining({
        connection_pool_size: 10,
        max_rows_per_query: 10_000,
        require_review_writes: true,
        require_review_reads: false,
        ai_analysis_enabled: false,
        clear_ai_config: true,
      }),
    );
    await waitFor(() =>
      expect(navigateMock).toHaveBeenCalledWith('/datasources/ds-1/settings'),
    );
  });

  it('disables the Next button until the test passes', async () => {
    createDatasource.mockResolvedValueOnce(baseDatasource);
    testConnection.mockResolvedValueOnce({
      ok: false,
      latency_ms: 0,
      message: 'auth failed',
    } satisfies ConnectionTestResult);

    render(wrap(<DatasourceCreateWizardPage />));

    await screen.findByText('PostgreSQL');
    fireEvent.click(screen.getByText('PostgreSQL'));

    await screen.findByLabelText('Name');
    await fillConnectionAndSubmit('Save and test');

    await screen.findByRole('button', { name: /Test connection/ });

    const nextBtn = screen.getByRole('button', { name: 'Next' });
    expect(nextBtn).toBeDisabled();
  });
});
