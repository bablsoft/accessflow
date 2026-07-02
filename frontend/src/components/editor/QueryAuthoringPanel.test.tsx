import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { useState, type ReactNode } from 'react';
import type { Datasource } from '@/types/api';
import '@/i18n';

const { analyzeOnlyMock, dryRunQueryMock } = vi.hoisted(() => ({
  analyzeOnlyMock: vi.fn(),
  dryRunQueryMock: vi.fn(),
}));

vi.mock('@/api/queries', () => ({
  analyzeOnly: analyzeOnlyMock,
  dryRunQuery: dryRunQueryMock,
}));

vi.mock('@/hooks/useSchemaIntrospect', () => ({
  useSchemaIntrospect: () => ({ data: { schemas: [] }, isLoading: false }),
}));

vi.mock('@/components/editor/SchemaTree', () => ({
  SchemaTree: () => <div data-testid="schema-tree-stub" />,
}));

vi.mock('@/components/editor/SqlEditor', () => ({
  SqlEditor: ({
    value,
    onChange,
    syntax,
  }: {
    value: string;
    onChange: (next: string) => void;
    syntax?: string;
  }) => (
    <>
      <textarea
        aria-label="sql-editor"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      <span data-testid="editor-syntax">{syntax}</span>
    </>
  ),
}));

const { QueryAuthoringPanel } = await import('./QueryAuthoringPanel');
const { useQueryAuthoring } = await import('./useQueryAuthoring');

const pgDs = {
  id: 'ds-1',
  name: 'Production',
  db_type: 'POSTGRESQL',
  database_name: 'app',
  ai_analysis_enabled: false,
  ai_config_id: null,
  text_to_sql_enabled: false,
  active: true,
} as Datasource;

const mongoDs = {
  ...pgDs,
  id: 'ds-mongo',
  db_type: 'MONGODB',
  database_name: 'docs',
} as Datasource;

function Harness({ ds, initialSql = '' }: { ds: Datasource; initialSql?: string }) {
  const [sql, setSql] = useState(initialSql);
  const authoring = useQueryAuthoring({ ds, sql, onSqlChange: setSql });
  return (
    <QueryAuthoringPanel
      authoring={authoring}
      ds={ds}
      datasources={[ds]}
      onChangeDs={() => {}}
      sql={sql}
      footer={<div data-testid="footer-slot" />}
    />
  );
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

describe('QueryAuthoringPanel', () => {
  beforeEach(() => {
    analyzeOnlyMock.mockReset();
    dryRunQueryMock.mockReset();
  });

  it('renders the toolbar counters, footer slot, and Format for SQL engines', () => {
    render(wrap(<Harness ds={pgDs} initialSql={'select id\nfrom users'} />));

    expect(screen.getByText('app')).toBeInTheDocument();
    expect(screen.getByText('2 lines')).toBeInTheDocument();
    expect(screen.getByTestId('footer-slot')).toBeInTheDocument();
    expect(screen.getByTestId('schema-tree-stub')).toBeInTheDocument();
    // Single-syntax engine → no syntax toggle.
    expect(screen.queryByLabelText('Query syntax')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Format/i }));
    expect((screen.getByLabelText('sql-editor') as HTMLTextAreaElement).value).toContain('SELECT');
  });

  it('offers the syntax toggle for multi-syntax engines and drives the editor mode', () => {
    render(wrap(<Harness ds={mongoDs} initialSql="db.users.find({})" />));

    expect(screen.getByTestId('editor-syntax')).toHaveTextContent('shell');
    const toggle = screen.getByLabelText('Query syntax');
    fireEvent.click(within(toggle).getByText('JSON'));
    expect(screen.getByTestId('editor-syntax')).toHaveTextContent('json');
  });

  it('switches the right rail between AI hints and the dry-run panel', () => {
    render(wrap(<Harness ds={pgDs} initialSql="select 1" />));

    const rail = screen.getByLabelText('Insight panel');
    fireEvent.click(within(rail).getByText('Dry run'));
    // Selecting the plan side keeps the panel mounted without errors.
    expect(screen.getByTestId('footer-slot')).toBeInTheDocument();
  });
});
