import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import type { AiAnalysis, Datasource, PaginatedResponse } from '@/types/api';
import '@/i18n';

const { listDatasourcesMock, analyzeOnlyMock, submitQueryMock } = vi.hoisted(() => ({
  listDatasourcesMock: vi.fn(),
  analyzeOnlyMock: vi.fn(),
  submitQueryMock: vi.fn(),
}));

vi.mock('@/api/queries', () => ({
  analyzeOnly: analyzeOnlyMock,
  dryRunQuery: vi.fn(),
  submitQuery: submitQueryMock,
  queryKeys: {
    all: ['queries'] as const,
    lists: () => ['queries', 'list'] as const,
    list: (filters: unknown) => ['queries', 'list', filters] as const,
    details: () => ['queries', 'detail'] as const,
    detail: (id: string) => ['queries', 'detail', id] as const,
  },
}));

vi.mock('@/api/datasources', () => ({
  listDatasources: listDatasourcesMock,
  datasourceKeys: {
    all: ['datasources'] as const,
    list: (filters: unknown) => ['datasources', 'list', filters] as const,
  },
}));

vi.mock('@/hooks/useSchemaIntrospect', () => ({
  useSchemaIntrospect: () => ({ data: { schemas: [] }, isLoading: false }),
}));

vi.mock('@/components/editor/SchemaTree', () => ({
  SchemaTree: () => <div data-testid="schema-tree-stub" />,
}));

vi.mock('@/components/editor/ReviewPlanPreview', () => ({
  ReviewPlanPreview: () => <div data-testid="review-plan-stub" />,
}));

vi.mock('@/components/editor/SqlEditor', () => ({
  SqlEditor: ({
    value,
    onChange,
  }: {
    value: string;
    onChange: (next: string) => void;
  }) => (
    <textarea
      aria-label="sql-editor"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  ),
}));

const navigateMock = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

const { QueryEditorPage } = await import('./QueryEditorPage');

const baseDatasource: Datasource = {
  id: 'ds-ai-on',
  organization_id: 'org-1',
  name: 'Production',
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
  review_plan_id: 'rp-1',
  ai_analysis_enabled: true,
  ai_config_id: 'aic-1',
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

const sampleAnalysis: AiAnalysis = {
  risk_level: 'LOW',
  risk_score: 12,
  summary: 'Looks fine.',
  issues: [],
  affects_rows: 1,
  prompt_tokens: 200,
  completion_tokens: 80,
};

const INDEX_DDL = 'CREATE INDEX idx_users_email ON users(email)';
const analysisWithOptimization: AiAnalysis = {
  ...sampleAnalysis,
  optimizations: [
    {
      type: 'INDEX',
      title: 'Add index on users(email)',
      rationale: 'The WHERE clause filters on email.',
      sql: INDEX_DDL,
    },
  ],
};

function page(content: Datasource[]): PaginatedResponse<Datasource> {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: content.length ? 1 : 0,
    last: true,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <App>{node}</App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

// Accessible name on AntD buttons includes the icon's aria-label (e.g. "thunderbolt"),
// so we match on the visible text fragment rather than the full accessible name.
async function findAnalyzeButton() {
  return await screen.findByRole('button', { name: /Analyze/i });
}

function findSubmitButton() {
  return screen.getByRole('button', { name: /Submit for review/i });
}

describe('QueryEditorPage — AI analyze as explicit step (AF-164)', () => {
  beforeEach(() => {
    listDatasourcesMock.mockReset();
    analyzeOnlyMock.mockReset();
    submitQueryMock.mockReset();
    navigateMock.mockReset();
  });

  it('renders Analyze button and gates Submit until analysis runs for AI-enabled datasources', async () => {
    listDatasourcesMock.mockResolvedValue(page([baseDatasource]));

    render(wrap(<QueryEditorPage />));

    const analyzeBtn = await findAnalyzeButton();
    expect(analyzeBtn).toBeDisabled(); // empty SQL → can't analyze

    fireEvent.change(screen.getByLabelText('sql-editor'), {
      target: { value: 'select 1' },
    });

    expect(analyzeBtn).not.toBeDisabled();
    expect(findSubmitButton()).toBeDisabled(); // hard gate until analyze
  });

  it('marks analysis stale on edit, keeps it visible, and re-disables Submit', async () => {
    listDatasourcesMock.mockResolvedValue(page([baseDatasource]));
    analyzeOnlyMock.mockResolvedValue(sampleAnalysis);

    render(wrap(<QueryEditorPage />));

    await findAnalyzeButton();
    const editor = screen.getByLabelText('sql-editor');
    fireEvent.change(editor, { target: { value: 'select 1' } });

    await act(async () => {
      fireEvent.click(await findAnalyzeButton());
    });

    await waitFor(() => {
      expect(analyzeOnlyMock).toHaveBeenCalledWith({
        datasource_id: baseDatasource.id,
        sql: 'select 1',
      });
    });
    await waitFor(() => {
      expect(findSubmitButton()).not.toBeDisabled();
    });
    expect(screen.getByText('Looks fine.')).toBeInTheDocument();

    fireEvent.change(editor, { target: { value: 'select 1 from t' } });

    await waitFor(() => {
      expect(findSubmitButton()).toBeDisabled();
    });
    // The analysis stays on screen (still readable) but is flagged stale.
    expect(screen.getByText('Looks fine.')).toBeInTheDocument();
    expect(screen.getByText('stale')).toBeInTheDocument();
  });

  it('submits the query and navigates to the detail page on success', async () => {
    listDatasourcesMock.mockResolvedValue(page([baseDatasource]));
    analyzeOnlyMock.mockResolvedValue(sampleAnalysis);
    submitQueryMock.mockResolvedValue({
      id: 'qr-99',
      status: 'PENDING_AI',
      ai_analysis: null,
      review_plan: null,
      estimated_review_completion: null,
    });

    render(wrap(<QueryEditorPage />));

    await findAnalyzeButton();
    fireEvent.change(screen.getByLabelText('sql-editor'), {
      target: { value: 'select 1' },
    });

    await act(async () => {
      fireEvent.click(await findAnalyzeButton());
    });
    await waitFor(() => expect(findSubmitButton()).not.toBeDisabled());

    await act(async () => {
      fireEvent.click(findSubmitButton());
    });

    await waitFor(() => {
      expect(submitQueryMock).toHaveBeenCalled();
    });
    expect(navigateMock).toHaveBeenCalledWith('/queries/qr-99');
  });

  it('hides Analyze and allows Submit directly for datasources without AI', async () => {
    const aiOffDs: Datasource = {
      ...baseDatasource,
      id: 'ds-ai-off',
      ai_analysis_enabled: false,
      ai_config_id: null,
    };
    listDatasourcesMock.mockResolvedValue(page([aiOffDs]));

    render(wrap(<QueryEditorPage />));

    // Wait for datasources to load (Submit button is the canonical signal)
    await screen.findByRole('button', { name: /Submit for review/i });
    expect(screen.queryByRole('button', { name: /Analyze/i })).toBeNull();

    fireEvent.change(screen.getByLabelText('sql-editor'), {
      target: { value: 'select 1' },
    });

    expect(findSubmitButton()).not.toBeDisabled();
  });

  it('applies an optimization suggestion into the editor and submits as AI_SUGGESTION', async () => {
    listDatasourcesMock.mockResolvedValue(page([baseDatasource]));
    analyzeOnlyMock.mockResolvedValue(analysisWithOptimization);
    submitQueryMock.mockResolvedValue({
      id: 'qr-100',
      status: 'PENDING_AI',
      ai_analysis: null,
      review_plan: null,
      estimated_review_completion: null,
    });

    render(wrap(<QueryEditorPage />));

    await findAnalyzeButton();
    const editor = screen.getByLabelText('sql-editor');
    fireEvent.change(editor, { target: { value: 'select * from users where email = $1' } });

    await act(async () => {
      fireEvent.click(await findAnalyzeButton());
    });
    await waitFor(() => expect(findSubmitButton()).not.toBeDisabled());

    // Expand the optimization card, then apply it.
    fireEvent.click(screen.getByText('Add index on users(email)'));
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: /Apply as draft/i }));
    });

    // Editor is pre-filled with the suggestion; submit re-gates until re-analysis.
    expect((editor as HTMLTextAreaElement).value).toBe(INDEX_DDL);
    await waitFor(() => expect(findSubmitButton()).toBeDisabled());
    // The analysis is kept on screen (marked stale) so the user can still see the risks and the
    // remaining suggestions to work on them — it is NOT wiped.
    expect(screen.getByText('Add index on users(email)')).toBeInTheDocument();
    expect(screen.getByText('stale')).toBeInTheDocument();

    // Re-analyze the applied draft, then submit.
    await act(async () => {
      fireEvent.click(await findAnalyzeButton());
    });
    await waitFor(() => expect(findSubmitButton()).not.toBeDisabled());
    await act(async () => {
      fireEvent.click(findSubmitButton());
    });

    await waitFor(() => expect(submitQueryMock).toHaveBeenCalled());
    expect(submitQueryMock).toHaveBeenCalledWith(
      expect.objectContaining({ sql: INDEX_DDL, submission_reason: 'AI_SUGGESTION' }),
    );
  });

  it('resets the AI_SUGGESTION flag to USER_SUBMITTED after a manual edit', async () => {
    listDatasourcesMock.mockResolvedValue(page([baseDatasource]));
    analyzeOnlyMock.mockResolvedValue(analysisWithOptimization);
    submitQueryMock.mockResolvedValue({
      id: 'qr-101',
      status: 'PENDING_AI',
      ai_analysis: null,
      review_plan: null,
      estimated_review_completion: null,
    });

    render(wrap(<QueryEditorPage />));

    await findAnalyzeButton();
    const editor = screen.getByLabelText('sql-editor');
    fireEvent.change(editor, { target: { value: 'select 1' } });
    await act(async () => {
      fireEvent.click(await findAnalyzeButton());
    });
    await waitFor(() => expect(findSubmitButton()).not.toBeDisabled());

    fireEvent.click(screen.getByText('Add index on users(email)'));
    await act(async () => {
      fireEvent.click(await screen.findByRole('button', { name: /Apply as draft/i }));
    });

    // A manual edit after applying clears the AI origin.
    fireEvent.change(editor, { target: { value: INDEX_DDL + ' -- tweaked' } });
    await act(async () => {
      fireEvent.click(await findAnalyzeButton());
    });
    await waitFor(() => expect(findSubmitButton()).not.toBeDisabled());
    await act(async () => {
      fireEvent.click(findSubmitButton());
    });

    await waitFor(() => expect(submitQueryMock).toHaveBeenCalled());
    expect(submitQueryMock).toHaveBeenCalledWith(
      expect.objectContaining({ submission_reason: 'USER_SUBMITTED' }),
    );
  });

  it('treats ai_config_id=null as "AI not supported" even when ai_analysis_enabled=true', async () => {
    const unconfiguredDs: Datasource = {
      ...baseDatasource,
      id: 'ds-ai-no-config',
      ai_analysis_enabled: true,
      ai_config_id: null,
    };
    listDatasourcesMock.mockResolvedValue(page([unconfiguredDs]));

    render(wrap(<QueryEditorPage />));

    await screen.findByRole('button', { name: /Submit for review/i });
    expect(screen.queryByRole('button', { name: /Analyze/i })).toBeNull();

    fireEvent.change(screen.getByLabelText('sql-editor'), {
      target: { value: 'select 1' },
    });
    expect(findSubmitButton()).not.toBeDisabled();
  });
});
