import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import type { ApiConnector, Datasource } from '@/types/api';
import '@/i18n';

const { listApiOperationsMock } = vi.hoisted(() => ({
  listApiOperationsMock: vi.fn(),
}));

vi.mock('@/api/queries', () => ({
  analyzeOnly: vi.fn(),
  dryRunQuery: vi.fn(),
}));

vi.mock('@/api/apiConnectors', () => ({
  listApiOperations: listApiOperationsMock,
  apiConnectorKeys: {
    operations: (id: string) => ['api-connectors', 'detail', id, 'operations'] as const,
  },
}));

vi.mock('@/api/apiRequests', () => ({
  analyzeApiCall: vi.fn(),
  generateApiCall: vi.fn(),
}));

vi.mock('@/hooks/useSchemaIntrospect', () => ({
  useSchemaIntrospect: () => ({ data: { schemas: [] }, isLoading: false }),
}));

vi.mock('@/components/editor/SchemaTree', () => ({
  SchemaTree: () => <div data-testid="schema-tree-stub" />,
}));

vi.mock('@/components/editor/SqlEditor', () => ({
  SqlEditor: ({ value, onChange }: { value: string; onChange: (next: string) => void }) => (
    <textarea aria-label="sql-editor" value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

vi.mock('@/components/apigov/ApiRequestComposer', () => ({
  ApiRequestComposer: () => <div data-testid="composer-stub" />,
}));

const { GroupMemberEditDrawer } = await import('./GroupMemberEditDrawer');
const { newMember } = await import('./groupBuilder');
type DraftMember = import('./groupBuilder').DraftMember;

const datasources = [
  {
    id: 'ds-1',
    name: 'prod-db',
    db_type: 'POSTGRESQL',
    database_name: 'app',
    ai_analysis_enabled: true,
    ai_config_id: 'aic-1',
    text_to_sql_enabled: false,
    active: true,
  },
] as Datasource[];
const connectors = [
  {
    id: 'c-1',
    name: 'CRM',
    schema_present: false,
    text_to_api_enabled: false,
    default_headers: {},
  },
] as unknown as ApiConnector[];

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

function renderDrawer(member: DraftMember | null) {
  const onChange = vi.fn();
  const onClose = vi.fn();
  render(
    wrap(
      <GroupMemberEditDrawer
        member={member}
        index={0}
        datasources={datasources}
        connectors={connectors}
        onChange={onChange}
        onClose={onClose}
      />,
    ),
  );
  return { onChange, onClose };
}

describe('GroupMemberEditDrawer (#559)', () => {
  beforeEach(() => {
    listApiOperationsMock.mockReset();
    listApiOperationsMock.mockResolvedValue([]);
  });

  it('stays closed when no member is being edited', () => {
    renderDrawer(null);
    expect(screen.queryByTestId('group-member-edit-drawer')).toBeNull();
  });

  it('prompts for a datasource before mounting the query panel', async () => {
    const member = newMember('QUERY');
    renderDrawer(member);

    await screen.findByTestId('group-member-edit-drawer');
    expect(screen.getByText(/Select a datasource to start authoring/i)).toBeInTheDocument();
    expect(screen.queryByLabelText('sql-editor')).toBeNull();
  });

  it('mounts the full query authoring surface once a datasource is set', async () => {
    const member = newMember('QUERY');
    member.datasourceId = 'ds-1';
    member.sqlText = 'select 1';
    const { onChange } = renderDrawer(member);

    await screen.findByTestId('group-member-edit-drawer');
    expect(screen.getByTestId('schema-tree-stub')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Dry run/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Analyze/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Templates/i })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('sql-editor'), { target: { value: 'select 2' } });
    await waitFor(() => {
      expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ sqlText: 'select 2' }));
    });

    fireEvent.click(screen.getByRole('checkbox', { name: /single transaction/i }));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ transactional: true }));
  });

  it('mounts the API authoring surface with verb/path propagation', async () => {
    const member = newMember('API_CALL');
    member.connectorId = 'c-1';
    const { onChange, onClose } = renderDrawer(member);

    await screen.findByTestId('group-member-edit-drawer');
    expect(screen.getByTestId('composer-stub')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Method'), { target: { value: 'POST' } });
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ verb: 'POST' }));

    fireEvent.change(screen.getByLabelText('Path'), { target: { value: '/v1/tickets' } });
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ requestPath: '/v1/tickets' }),
    );

    fireEvent.click(screen.getByTestId('group-member-edit-done'));
    expect(onClose).toHaveBeenCalled();
  });
});
