import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { AxiosError, AxiosHeaders } from 'axios';
import '@/i18n';
import type { SchemaChangeLadder, SchemaChangePromotion, SchemaChangeSet } from '@/types/api';

const api = vi.hoisted(() => ({
  getSchemaChangeSet: vi.fn(),
  getSchemaChangeLadder: vi.fn(),
  listSchemaChangePromotions: vi.fn(),
  listSchemaChangePipelines: vi.fn(),
  replaceSchemaChangeSetStatements: vi.fn(),
  promoteSchemaChangeSet: vi.fn(),
  cancelSchemaChangePromotion: vi.fn(),
  updateSchemaChangeSet: vi.fn(),
  deleteSchemaChangeSet: vi.fn(),
  navigate: vi.fn(),
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => api.navigate };
});

vi.mock('@/components/editor/SqlEditor', () => ({
  SqlEditor: ({ value, onChange, readOnly }: { value: string; onChange: (v: string) => void; readOnly?: boolean }) => (
    <textarea
      data-testid="sql-editor"
      value={value}
      readOnly={readOnly}
      onChange={(e) => onChange(e.target.value)}
    />
  ),
}));

vi.mock('@/api/schemaChange', async () => {
  const actual = await vi.importActual<typeof import('@/api/schemaChange')>('@/api/schemaChange');
  return {
    schemaChangeKeys: actual.schemaChangeKeys,
    getSchemaChangeSet: api.getSchemaChangeSet,
    getSchemaChangeLadder: api.getSchemaChangeLadder,
    listSchemaChangePromotions: api.listSchemaChangePromotions,
    listSchemaChangePipelines: api.listSchemaChangePipelines,
    replaceSchemaChangeSetStatements: api.replaceSchemaChangeSetStatements,
    promoteSchemaChangeSet: api.promoteSchemaChangeSet,
    cancelSchemaChangePromotion: api.cancelSchemaChangePromotion,
    updateSchemaChangeSet: api.updateSchemaChangeSet,
    deleteSchemaChangeSet: api.deleteSchemaChangeSet,
  };
});

const Page = (await import('./SchemaChangeSetDetailPage')).default;

const baseSet: SchemaChangeSet = {
  id: 's-1',
  pipeline_id: 'p-1',
  name: 'orders-archive',
  description: null,
  status: 'DRAFT',
  statements_checksum: 'c',
  created_at: '2026-09-22T10:00:00Z',
  updated_at: '2026-09-22T10:00:00Z',
  statements: [
    { id: 'st-1', sequence_order: 0, sql_text: 'ALTER TABLE orders ADD COLUMN a INT', query_type: 'DDL', created_at: 'x' },
    { id: 'st-2', sequence_order: 1, sql_text: 'COMMENT ON COLUMN orders.a IS $$x$$', query_type: 'OTHER', created_at: 'x' },
  ],
  review_warnings: [],
};

const ladder: SchemaChangeLadder = {
  change_set_id: 's-1',
  pipeline_id: 'p-1',
  rungs: [
    { environment_id: 'e-dev', environment_name: 'dev', sort_order: 0, datasource_id: 'd-1', state: 'PROMOTABLE' },
    {
      environment_id: 'e-prod',
      environment_name: 'prod',
      sort_order: 1,
      datasource_id: 'd-2',
      state: 'BLOCKED',
      blocker: 'LOWER_ENVIRONMENT_NOT_APPLIED',
      blocking_environment_name: 'dev',
    },
  ],
};

const promotion = (status: SchemaChangePromotion['status']): SchemaChangePromotion => ({
  id: 'pr-1',
  change_set_id: 's-1',
  environment_id: 'e-dev',
  environment_name: 'dev',
  datasource_id: 'd-1',
  request_group_id: 'g-1',
  status,
  statements_checksum: 'c',
  promoted_by: 'u-1',
  submitted_at: '2026-09-22T11:00:00Z',
});

function problem(status: number, data: unknown) {
  return new AxiosError('Request failed', 'ERR_BAD_RESPONSE', undefined, undefined, {
    status,
    statusText: 'x',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data,
  });
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <MemoryRouter initialEntries={['/schema-change-sets/s-1']}>
          <Routes>
            <Route path="/schema-change-sets/:id" element={<Page />} />
          </Routes>
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>,
  );
}

describe('SchemaChangeSetDetailPage', () => {
  beforeEach(() => {
    Object.values(api).forEach((fn) => fn.mockReset());
    api.getSchemaChangeSet.mockResolvedValue(baseSet);
    api.getSchemaChangeLadder.mockResolvedValue(ladder);
    api.listSchemaChangePromotions.mockResolvedValue([]);
    api.listSchemaChangePipelines.mockResolvedValue([{ id: 'p-1', name: 'orders-pipeline', active: true, environments: [] }]);
  });

  it('shows a skeleton while loading', () => {
    api.getSchemaChangeSet.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByTestId('schema-change-detail-loading')).toBeTruthy();
  });

  it('shows the server detail when the set cannot be loaded', async () => {
    api.getSchemaChangeSet.mockRejectedValue(problem(404, { detail: 'Schema change set not found' }));
    renderPage();
    expect(await screen.findByText('Schema change set not found')).toBeTruthy();
    fireEvent.click(screen.getByText('Back'));
    expect(api.navigate).toHaveBeenCalledWith('/schema-change-sets');
  });

  it('renders an editable statement list and the ladder with its reasons', async () => {
    renderPage();
    expect((await screen.findAllByText('orders-archive')).length).toBeGreaterThan(0);
    expect(await screen.findByText('Pipeline orders-pipeline')).toBeTruthy();
    await waitFor(() => expect(screen.getAllByTestId('sql-editor')).toHaveLength(2));
    expect(screen.getByText('Other')).toBeTruthy();
    expect(screen.getByText('Add statement')).toBeTruthy();
    expect(await screen.findByText('Blocked — dev not yet applied')).toBeTruthy();
    expect(screen.getByText('Not promoted yet')).toBeTruthy();
  });

  it('renders the editor read-only with the reason once a promotion freezes the set', async () => {
    api.getSchemaChangeSet.mockResolvedValue({ ...baseSet, status: 'ACTIVE' });
    api.listSchemaChangePromotions.mockResolvedValue([promotion('IN_REVIEW')]);
    renderPage();

    const banner = await screen.findByTestId('statements-frozen');
    expect(banner.textContent).toContain('A promotion to dev is In review');
    await waitFor(() =>
      screen.getAllByTestId('sql-editor').forEach((el) => expect((el as HTMLTextAreaElement).readOnly).toBe(true)),
    );
    expect(screen.queryByText('Add statement')).toBeNull();
    expect(screen.queryByText('Save statements')).toBeNull();
    expect(screen.queryByText('Delete')).toBeNull();
  });

  it('keeps the editor read-only when the promotions cannot be loaded', async () => {
    api.listSchemaChangePromotions.mockRejectedValue(problem(500, { detail: 'Promotions unavailable' }));
    renderPage();
    const banner = await screen.findByTestId('statements-promotions-error');
    expect(banner.textContent).toContain('Promotions unavailable');
    expect(screen.queryByText('Save statements')).toBeNull();
    expect(screen.queryByText('Delete')).toBeNull();
  });

  it('explains an archived set instead of offering edits', async () => {
    api.getSchemaChangeSet.mockResolvedValue({ ...baseSet, status: 'ARCHIVED' });
    renderPage();
    expect(await screen.findByText('This change set is archived')).toBeTruthy();
    expect(screen.queryByText('Archive')).toBeNull();
  });

  it('surfaces the server detail and pins the problem to its statement on a refused save', async () => {
    api.replaceSchemaChangeSetStatements.mockRejectedValue(
      problem(422, {
        error: 'SCHEMA_CHANGE_SET_STATEMENT_DML',
        detail: 'Statement 2 of the change set is DELETE',
        statementIndex: 1,
      }),
    );
    renderPage();
    await waitFor(() => expect(screen.getAllByTestId('sql-editor')).toHaveLength(2));
    fireEvent.click(screen.getByText('Save statements'));

    const problems = await screen.findByTestId('statement-problems-1');
    expect(problems.textContent).toContain('Statement 2 of the change set is DELETE');
    expect(problems.textContent).toContain('Refused');
    await waitFor(() =>
      expect(screen.getAllByText('Statement 2 of the change set is DELETE').length).toBeGreaterThan(1),
    );
    expect(api.replaceSchemaChangeSetStatements).toHaveBeenCalledWith('s-1', [
      'ALTER TABLE orders ADD COLUMN a INT',
      'COMMENT ON COLUMN orders.a IS $$x$$',
    ]);
  });

  it('shows blocking findings per statement', async () => {
    api.replaceSchemaChangeSetStatements.mockRejectedValue(
      problem(422, {
        error: 'SCHEMA_CHANGE_SET_STATEMENT_BLOCKED',
        detail: 'A statement is blocked',
        findings: [
          { statement_index: 0, datasource_id: 'd-1', rule_id: 'drop_statement', severity: 'BLOCK', message: 'DROP is blocked' },
        ],
      }),
    );
    renderPage();
    await waitFor(() => expect(screen.getAllByTestId('sql-editor')).toHaveLength(2));
    fireEvent.click(screen.getByText('Save statements'));
    const problems = await screen.findByTestId('statement-problems-0');
    expect(problems.textContent).toContain('drop_statement');
    expect(problems.textContent).toContain('Blocked');
  });

  it('keeps WARN findings from a successful save on their statement', async () => {
    api.replaceSchemaChangeSetStatements.mockResolvedValue({
      ...baseSet,
      review_warnings: [
        { statement_index: 0, datasource_id: 'd-1', rule_id: 'ddl_statement', severity: 'WARN', message: 'ALTER is a schema change' },
      ],
    });
    renderPage();
    await waitFor(() => expect(screen.getAllByTestId('sql-editor')).toHaveLength(2));
    fireEvent.click(screen.getByText('Save statements'));
    expect(await screen.findByText('Statements saved — review warnings: 1')).toBeTruthy();
    expect((await screen.findByTestId('statement-problems-0')).textContent).toContain('Warning');
  });

  it('validates a blank statement before calling the server', async () => {
    renderPage();
    await waitFor(() => expect(screen.getAllByTestId('sql-editor')).toHaveLength(2));
    fireEvent.click(screen.getByText('Add statement'));
    await waitFor(() => expect(screen.getAllByTestId('sql-editor')).toHaveLength(3));
    fireEvent.click(screen.getByText('Save statements'));
    expect(await screen.findByText('Enter a statement or remove it')).toBeTruthy();
    expect(api.replaceSchemaChangeSetStatements).not.toHaveBeenCalled();
    fireEvent.click(screen.getByLabelText('Remove statement 3'));
    await waitFor(() => expect(screen.getAllByTestId('sql-editor')).toHaveLength(2));
  });

  it('promotes the promotable rung and reports a refusal detail', async () => {
    api.promoteSchemaChangeSet.mockRejectedValueOnce(
      problem(403, { detail: 'You hold no DDL grant on the target datasource' }),
    );
    renderPage();
    fireEvent.click(await screen.findByText('Promote to dev'));
    fireEvent.click(await screen.findByText('Promote'));
    expect(await screen.findByText('You hold no DDL grant on the target datasource')).toBeTruthy();
    expect(api.promoteSchemaChangeSet).toHaveBeenCalledWith('s-1', 'e-dev');

    api.promoteSchemaChangeSet.mockResolvedValueOnce(promotion('PENDING'));
    fireEvent.click(screen.getByText('Promote to dev'));
    const confirm = await screen.findAllByText('Promote');
    fireEvent.click(confirm[confirm.length - 1] as HTMLElement);
    expect(await screen.findByText('Promotion to dev submitted')).toBeTruthy();
  });

  it('archives, renames and deletes', async () => {
    api.updateSchemaChangeSet.mockResolvedValue({ ...baseSet, name: 'renamed' });
    api.deleteSchemaChangeSet.mockResolvedValue(undefined);
    renderPage();

    fireEvent.click(await screen.findByText('Archive'));
    const archive = await screen.findAllByText('Archive');
    fireEvent.click(archive[archive.length - 1] as HTMLElement);
    await waitFor(() => expect(api.updateSchemaChangeSet).toHaveBeenCalledWith('s-1', { status: 'ARCHIVED' }));

    fireEvent.click(screen.getByText('Edit details'));
    const name = await screen.findByLabelText('Name');
    fireEvent.change(name, { target: { value: 'renamed' } });
    fireEvent.click(screen.getByText('Save'));
    await waitFor(() =>
      expect(api.updateSchemaChangeSet).toHaveBeenCalledWith('s-1', { name: 'renamed', description: '' }),
    );

    fireEvent.click(screen.getByText('Delete'));
    const del = await screen.findAllByText('Delete');
    fireEvent.click(del[del.length - 1] as HTMLElement);
    await waitFor(() => expect(api.navigate).toHaveBeenCalledWith('/schema-change-sets'));
  });

  it('cancels an open promotion from the ladder', async () => {
    api.getSchemaChangeLadder.mockResolvedValue({
      ...ladder,
      rungs: [{ ...ladder.rungs[0], state: 'IN_PROGRESS', latest_promotion: promotion('PENDING') }],
    });
    api.cancelSchemaChangePromotion.mockResolvedValue(undefined);
    renderPage();
    fireEvent.click(await screen.findByText('Cancel promotion'));
    const confirm = await screen.findAllByText('Cancel promotion');
    fireEvent.click(confirm[confirm.length - 1] as HTMLElement);
    expect(await screen.findByText('Promotion cancelled')).toBeTruthy();
  });
});
