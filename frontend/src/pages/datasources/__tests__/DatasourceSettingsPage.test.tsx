import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { Datasource } from '@/types/api';

const getDatasource = vi.fn();
const updateDatasource = vi.fn();
const testConnection = vi.fn();
const testReplicaConnection = vi.fn();
const listPermissions = vi.fn();
const deleteDatasource = vi.fn();

vi.mock('@/api/datasources', () => ({
  getDatasource: (...args: unknown[]) => getDatasource(...args),
  updateDatasource: (...args: unknown[]) => updateDatasource(...args),
  testConnection: (...args: unknown[]) => testConnection(...args),
  testReplicaConnection: (...args: unknown[]) => testReplicaConnection(...args),
  listPermissions: (...args: unknown[]) => listPermissions(...args),
  deleteDatasource: (...args: unknown[]) => deleteDatasource(...args),
  grantPermission: vi.fn(),
  revokePermission: vi.fn(),
  getDatasourceSchema: vi.fn(),
  datasourceKeys: {
    all: ['datasources'] as const,
    lists: () => ['datasources', 'list'] as const,
    details: () => ['datasources', 'detail'] as const,
    detail: (id: string) => ['datasources', 'detail', id] as const,
    schema: (id: string) => ['datasources', 'detail', id, 'schema'] as const,
    permissions: (id: string) => ['datasources', 'detail', id, 'permissions'] as const,
    types: () => ['datasources', 'types'] as const,
  },
}));

vi.mock('@/api/admin', () => ({
  listAiConfigs: () => Promise.resolve([]),
  listUsers: () => Promise.resolve({ content: [], page: 0, size: 100, total_elements: 0, total_pages: 0 }),
  aiConfigKeys: { all: ['aiConfig'] as const, lists: () => ['aiConfig', 'list'] as const },
  userKeys: { all: ['users'] as const, list: (_f: unknown) => ['users', 'list'] as const },
}));

vi.mock('@/api/queries', () => ({
  listQueries: () => Promise.resolve({ content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 }),
  queryKeys: { all: ['queries'] as const, list: (_f: unknown) => ['queries', 'list'] as const },
}));

vi.mock('@/api/reviewPlans', () => ({
  listReviewPlans: () => Promise.resolve([]),
  reviewPlanKeys: { all: ['reviewPlans'] as const, lists: () => ['reviewPlans', 'list'] as const },
}));

vi.mock('@/hooks/useSchemaIntrospect', () => ({
  useSchemaIntrospect: () => ({ data: undefined, isLoading: false, isError: false }),
}));

const { DatasourceSettingsPage } = await import('../DatasourceSettingsPage');

const baseDs: Datasource = {
  id: 'ds-1',
  organization_id: 'org-1',
  name: 'Prod',
  db_type: 'POSTGRESQL',
  host: 'db.internal',
  port: 5432,
  database_name: 'app',
  username: 'svc',
  ssl_mode: 'REQUIRE',
  connection_pool_size: 10,
  max_rows_per_query: 1000,
  require_review_reads: false,
  require_review_writes: true,
  review_plan_id: null,
  ai_analysis_enabled: false,
  ai_config_id: null,
  custom_driver_id: null,
  jdbc_url_override: null,
  read_replica_jdbc_url: null,
  read_replica_username: null,
  active: true,
  created_at: '2026-05-01T00:00:00Z',
};

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/datasources/ds-1/settings']}>
        <AntdApp>
          <Routes>
            <Route path="/datasources/:id/settings" element={node} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('DatasourceSettingsPage — read replica section', () => {
  beforeEach(() => {
    getDatasource.mockReset();
    updateDatasource.mockReset();
    testReplicaConnection.mockReset();
    listPermissions.mockReset();
    listPermissions.mockResolvedValue([]);
  });

  it('renders the read replica section with empty fields when datasource has no replica', async () => {
    getDatasource.mockResolvedValue(baseDs);

    render(wrap(<DatasourceSettingsPage />));

    await waitFor(() => expect(screen.getByLabelText('Replica JDBC URL')).toBeInTheDocument());
    expect((screen.getByLabelText('Replica JDBC URL') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('Replica username') as HTMLInputElement).value).toBe('');
    expect(screen.getByRole('button', { name: /Test replica/i })).toBeInTheDocument();
  });

  it('pre-fills the form when datasource has a replica configured', async () => {
    getDatasource.mockResolvedValue({
      ...baseDs,
      read_replica_jdbc_url: 'jdbc:postgresql://replica:5432/app',
      read_replica_username: 'replica-user',
    });

    render(wrap(<DatasourceSettingsPage />));

    await waitFor(() =>
      expect((screen.getByLabelText('Replica JDBC URL') as HTMLInputElement).value).toBe(
        'jdbc:postgresql://replica:5432/app',
      ),
    );
    expect((screen.getByLabelText('Replica username') as HTMLInputElement).value).toBe(
      'replica-user',
    );
  });

  it('calls testReplicaConnection with live form values when Test replica is clicked', async () => {
    getDatasource.mockResolvedValue(baseDs);
    testReplicaConnection.mockResolvedValue({ ok: true, latency_ms: 8, message: null });

    render(wrap(<DatasourceSettingsPage />));

    await waitFor(() => expect(screen.getByLabelText('Replica JDBC URL')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Replica JDBC URL'), {
      target: { value: 'jdbc:postgresql://r:5432/db' },
    });
    fireEvent.change(screen.getByLabelText('Replica username'), {
      target: { value: 'ru' },
    });
    fireEvent.change(screen.getByLabelText('Replica password'), {
      target: { value: 'rpw' },
    });

    fireEvent.click(screen.getByRole('button', { name: /Test replica/i }));

    await waitFor(() =>
      expect(testReplicaConnection).toHaveBeenCalledWith('ds-1', {
        jdbc_url: 'jdbc:postgresql://r:5432/db',
        username: 'ru',
        password: 'rpw',
      }),
    );
  });

  it('omits password when blank so the backend uses the persisted value', async () => {
    getDatasource.mockResolvedValue({
      ...baseDs,
      read_replica_jdbc_url: 'jdbc:postgresql://replica:5432/app',
      read_replica_username: 'replica-user',
    });
    testReplicaConnection.mockResolvedValue({ ok: true, latency_ms: 5, message: null });

    render(wrap(<DatasourceSettingsPage />));

    await waitFor(() => expect(screen.getByLabelText('Replica JDBC URL')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Test replica/i }));

    await waitFor(() =>
      expect(testReplicaConnection).toHaveBeenCalledWith('ds-1', {
        jdbc_url: 'jdbc:postgresql://replica:5432/app',
        username: 'replica-user',
        password: undefined,
      }),
    );
  });

  it('submits replica fields on save and strips blank password', async () => {
    getDatasource.mockResolvedValue(baseDs);
    updateDatasource.mockResolvedValue(baseDs);

    render(wrap(<DatasourceSettingsPage />));

    await waitFor(() => expect(screen.getByLabelText('Replica JDBC URL')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Replica JDBC URL'), {
      target: { value: 'jdbc:postgresql://r:5432/db' },
    });
    fireEvent.change(screen.getByLabelText('Replica username'), {
      target: { value: 'ru' },
    });
    // Leave replica password blank.

    fireEvent.click(screen.getByRole('button', { name: /Save changes/i }));

    await waitFor(() => expect(updateDatasource).toHaveBeenCalled());
    const body = updateDatasource.mock.calls[0]![1] as Record<string, unknown>;
    expect(body.read_replica_jdbc_url).toBe('jdbc:postgresql://r:5432/db');
    expect(body.read_replica_username).toBe('ru');
    expect(body).not.toHaveProperty('read_replica_password');
  });

  it('passes blank read_replica_jdbc_url through on save so the backend clear-on-blank rule fires', async () => {
    getDatasource.mockResolvedValue({
      ...baseDs,
      read_replica_jdbc_url: 'jdbc:postgresql://replica:5432/app',
      read_replica_username: 'ru',
    });
    updateDatasource.mockResolvedValue(baseDs);

    render(wrap(<DatasourceSettingsPage />));

    await waitFor(() => expect(screen.getByLabelText('Replica JDBC URL')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText('Replica JDBC URL'), {
      target: { value: '' },
    });
    fireEvent.click(screen.getByRole('button', { name: /Save changes/i }));

    await waitFor(() => expect(updateDatasource).toHaveBeenCalled());
    const body = updateDatasource.mock.calls[0]![1] as Record<string, unknown>;
    expect(body.read_replica_jdbc_url).toBe('');
  });
});
