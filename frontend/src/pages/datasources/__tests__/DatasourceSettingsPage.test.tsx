import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { Datasource, DatasourcePermission, User } from '@/types/api';

const getDatasource = vi.fn();
const updateDatasource = vi.fn();
const testConnection = vi.fn();
const testReplicaConnection = vi.fn();
const listPermissions = vi.fn();
const listGroupPermissions = vi.fn();
const deleteDatasource = vi.fn();
const grantPermission = vi.fn();
const grantGroupPermission = vi.fn();
const getDatasourceSchema = vi.fn();
const listUsers = vi.fn();
const listAllGroups = vi.fn();

vi.mock('@/api/datasources', () => ({
  getDatasource: (...args: unknown[]) => getDatasource(...args),
  updateDatasource: (...args: unknown[]) => updateDatasource(...args),
  testConnection: (...args: unknown[]) => testConnection(...args),
  testReplicaConnection: (...args: unknown[]) => testReplicaConnection(...args),
  listPermissions: (...args: unknown[]) => listPermissions(...args),
  listGroupPermissions: (...args: unknown[]) => listGroupPermissions(...args),
  deleteDatasource: (...args: unknown[]) => deleteDatasource(...args),
  grantPermission: (...args: unknown[]) => grantPermission(...args),
  grantGroupPermission: (...args: unknown[]) => grantGroupPermission(...args),
  revokePermission: vi.fn(),
  revokeGroupPermission: vi.fn(),
  getDatasourceSchema: (...args: unknown[]) => getDatasourceSchema(...args),
  datasourceKeys: {
    all: ['datasources'] as const,
    lists: () => ['datasources', 'list'] as const,
    details: () => ['datasources', 'detail'] as const,
    detail: (id: string) => ['datasources', 'detail', id] as const,
    schema: (id: string) => ['datasources', 'detail', id, 'schema'] as const,
    permissions: (id: string) => ['datasources', 'detail', id, 'permissions'] as const,
    groupPermissions: (id: string) =>
      ['datasources', 'detail', id, 'permissions', 'groups'] as const,
    types: () => ['datasources', 'types'] as const,
  },
}));

vi.mock('@/api/groups', () => ({
  listAllGroups: (...args: unknown[]) => listAllGroups(...args),
  groupKeys: {
    all: ['groups'] as const,
    lists: () => ['groups', 'list'] as const,
  },
}));

vi.mock('@/api/admin', () => ({
  listAiConfigs: () => Promise.resolve([]),
  listUsers: (...args: unknown[]) => listUsers(...args),
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
  text_to_sql_enabled: false,
  custom_driver_id: null,
  connector_id: null,
  jdbc_url_override: null,
  read_replica_jdbc_url: null,
  read_replica_username: null,
  local_datacenter: null,
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
    listGroupPermissions.mockReset();
    listGroupPermissions.mockResolvedValue([]);
    listAllGroups.mockReset();
    listAllGroups.mockResolvedValue([]);
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

const analystUser: User = {
  id: 'u-analyst',
  email: 'analyst@example.com',
  display_name: 'Analyst',
  role: 'ANALYST',
  role_id: null,
  role_name: 'ANALYST',
  auth_provider: 'LOCAL',
  active: true,
  totp_enabled: false,
  last_login_at: null,
  preferred_language: null,
  created_at: '2026-05-01T00:00:00Z',
};

function basePermission(over: Partial<DatasourcePermission>): DatasourcePermission {
  return {
    id: 'perm-1',
    datasource_id: 'ds-1',
    user_id: 'u-analyst',
    user_email: 'analyst@example.com',
    user_display_name: 'Analyst',
    can_read: false,
    can_write: false,
    can_ddl: false,
    can_break_glass: false,
    row_limit_override: null,
    allowed_schemas: null,
    allowed_tables: null,
    restricted_columns: null,
    expires_at: null,
    created_by: 'admin',
    created_at: '2026-05-01T00:00:00Z',
    ...over,
  };
}

async function openGrantModal() {
  await waitFor(() => expect(screen.getByRole('tab', { name: /Permissions/ })).toBeInTheDocument());
  fireEvent.click(screen.getByRole('tab', { name: /Permissions/ }));
  // Page-level "Grant access" button carries a + icon, so its accessible name
  // is "plus Grant access" — match by regex.
  await waitFor(() =>
    expect(screen.getByRole('button', { name: /Grant access/ })).toBeInTheDocument(),
  );
  fireEvent.click(screen.getByRole('button', { name: /Grant access/ }));
  const dialog = await screen.findByRole('dialog');
  return dialog;
}

async function selectAnalyst(dialog: HTMLElement) {
  const combobox = within(dialog).getByRole('combobox', { name: 'User' });
  fireEvent.mouseDown(combobox);
  fireEvent.click(await screen.findByText('Analyst (analyst@example.com)'));
}

describe('DatasourceSettingsPage — break-glass permission grant', () => {
  beforeEach(() => {
    getDatasource.mockReset();
    getDatasource.mockResolvedValue(baseDs);
    listPermissions.mockReset();
    listPermissions.mockResolvedValue([]);
    listGroupPermissions.mockReset();
    listGroupPermissions.mockResolvedValue([]);
    listAllGroups.mockReset();
    listAllGroups.mockResolvedValue([]);
    grantPermission.mockReset();
    grantPermission.mockResolvedValue(basePermission({ can_read: true, can_break_glass: true }));
    getDatasourceSchema.mockReset();
    getDatasourceSchema.mockResolvedValue({ schemas: [] });
    listUsers.mockReset();
    listUsers.mockResolvedValue({
      content: [analystUser],
      page: 0,
      size: 100,
      total_elements: 1,
      total_pages: 1,
    });
  });

  it('defaults the break-glass switch off', async () => {
    render(wrap(<DatasourceSettingsPage />));
    const dialog = await openGrantModal();

    const switches = within(dialog).getAllByRole('switch');
    // Order: read, write, ddl, break-glass.
    expect(switches).toHaveLength(4);
    expect(switches[3]).toHaveAttribute('aria-checked', 'false');
  });

  it('sends can_break_glass: true when the toggle is on', async () => {
    render(wrap(<DatasourceSettingsPage />));
    const dialog = await openGrantModal();

    await selectAnalyst(dialog);
    // read defaults on; turn break-glass on.
    fireEvent.click(within(dialog).getAllByRole('switch')[3]!);
    fireEvent.click(within(dialog).getByRole('button', { name: /Grant access/ }));

    await waitFor(() => expect(grantPermission).toHaveBeenCalled());
    const input = grantPermission.mock.calls[0]![1] as Record<string, unknown>;
    expect(input.can_break_glass).toBe(true);
    expect(input.can_read).toBe(true);
    expect(input.user_id).toBe('u-analyst');
  });

  it('blocks a break-glass-only grant (read/write/DDL all off)', async () => {
    render(wrap(<DatasourceSettingsPage />));
    const dialog = await openGrantModal();

    await selectAnalyst(dialog);
    const switches = within(dialog).getAllByRole('switch');
    fireEvent.click(switches[0]!); // read off (default was on)
    fireEvent.click(switches[3]!); // break-glass on
    fireEvent.click(within(dialog).getByRole('button', { name: /Grant access/ }));

    await screen.findByText('Grant at least one of read, write, or DDL.');
    expect(grantPermission).not.toHaveBeenCalled();
  });

  it('renders the break-glass column as enabled for a break-glass permission', async () => {
    listPermissions.mockResolvedValue([basePermission({ can_break_glass: true })]);
    render(wrap(<DatasourceSettingsPage />));

    await waitFor(() => expect(screen.getByRole('tab', { name: /Permissions/ })).toBeInTheDocument());
    fireEvent.click(screen.getByRole('tab', { name: /Permissions/ }));

    // Column header present (AntD renders a duplicate measure header with
    // scroll={{ x }}, so there are 2 matches).
    expect((await screen.findAllByText('Break-glass')).length).toBeGreaterThanOrEqual(1);
    // The analyst row has exactly one "on" cell — the break-glass column —
    // since read/write/DDL are all off. PermCell renders a check icon when on.
    const emailCell = await screen.findByText('analyst@example.com');
    const row = emailCell.closest('tr')!;
    expect(within(row).getAllByRole('img', { name: 'check' })).toHaveLength(1);
  });
});
