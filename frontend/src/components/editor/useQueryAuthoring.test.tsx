import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import type { Datasource, QueryTemplate } from '@/types/api';
import '@/i18n';

const { analyzeOnlyMock, dryRunQueryMock } = vi.hoisted(() => ({
  analyzeOnlyMock: vi.fn(),
  dryRunQueryMock: vi.fn(),
}));

vi.mock('@/api/queries', () => ({
  analyzeOnly: analyzeOnlyMock,
  dryRunQuery: dryRunQueryMock,
}));

const { useQueryAuthoring } = await import('./useQueryAuthoring');
type SqlChangeSource = import('./useQueryAuthoring').SqlChangeSource;

const baseDs: Datasource = {
  id: 'ds-1',
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
  read_replicas: [],
  result_cache_enabled: false,
  result_cache_ttl_seconds: null,
  local_datacenter: null,
  active: true,
  created_at: '2026-05-01T00:00:00Z',
};

const mongoDs: Datasource = {
  ...baseDs,
  id: 'ds-mongo',
  db_type: 'MONGODB',
  text_to_sql_enabled: true,
};

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{children}</App>
    </QueryClientProvider>
  );
}

function setup(ds: Datasource | null, initialSql = '') {
  const onSqlChange = vi.fn<(next: string, source: SqlChangeSource) => void>();
  const utils = renderHook(({ sql }) => useQueryAuthoring({ ds, sql, onSqlChange }), {
    initialProps: { sql: initialSql },
    wrapper,
  });
  return { ...utils, onSqlChange };
}

describe('useQueryAuthoring', () => {
  beforeEach(() => {
    analyzeOnlyMock.mockReset();
    dryRunQueryMock.mockReset();
  });

  it('derives capability flags from the datasource', () => {
    const { result } = setup(baseDs, 'select 1');
    expect(result.current.aiSupported).toBe(true);
    expect(result.current.textToSqlSupported).toBe(false); // SQL engines have no text-to-SQL mode gate off
    expect(result.current.canFormat).toBe(true);
    expect(result.current.canAnalyze).toBe(true);

    const off = setup({ ...baseDs, ai_config_id: null }, 'select 1');
    expect(off.result.current.aiSupported).toBe(false);
    expect(off.result.current.canAnalyze).toBe(false);
  });

  it('runs analysis, reports freshness, and marks it stale when the SQL diverges', async () => {
    analyzeOnlyMock.mockResolvedValue({
      risk_level: 'LOW',
      risk_score: 10,
      summary: 'ok',
      issues: [],
    });
    const { result, rerender } = setup(baseDs, 'select 1');

    act(() => result.current.analyze());
    await waitFor(() => expect(result.current.hasFreshAnalysis).toBe(true));
    expect(analyzeOnlyMock).toHaveBeenCalledWith({ datasource_id: 'ds-1', sql: 'select 1' });
    expect(result.current.analysisStale).toBe(false);

    rerender({ sql: 'select 2' });
    expect(result.current.analysis).not.toBeNull(); // stays readable
    expect(result.current.analysisStale).toBe(true);
    expect(result.current.hasFreshAnalysis).toBe(false);
  });

  it('dry-runs the SQL, switches the rail to the plan panel, and tracks staleness', async () => {
    dryRunQueryMock.mockResolvedValue({ supported: true, engine_id: 'pg', duration_ms: 3 });
    const { result, rerender } = setup(baseDs, 'select 1');
    expect(result.current.rightPanel).toBe('ai');

    act(() => result.current.dryRun());
    expect(result.current.rightPanel).toBe('plan');
    await waitFor(() => expect(result.current.dryRunResult).not.toBeNull());
    expect(result.current.dryRunStale).toBe(false);

    rerender({ sql: 'select 2' });
    expect(result.current.dryRunStale).toBe(true);
  });

  it('formats via the change funnel with the user source', () => {
    const { result, onSqlChange } = setup(baseDs, 'select id from users');
    act(() => result.current.format());
    expect(onSqlChange).toHaveBeenCalledTimes(1);
    const [formatted, source] = onSqlChange.mock.calls[0]!;
    expect(formatted).toContain('SELECT');
    expect(source).toBe('user');
  });

  it('tags applied suggestions and switches syntax to match the draft', () => {
    const { result, onSqlChange } = setup(mongoDs, '');
    expect(result.current.effectiveSyntax).toBe('shell');

    act(() => result.current.applySuggestion('{"find": "users"}'));
    expect(onSqlChange).toHaveBeenCalledWith('{"find": "users"}', 'ai_suggestion');
    expect(result.current.effectiveSyntax).toBe('json');
  });

  it('applies generated drafts, honouring only syntax hints valid for the engine', () => {
    const { result, onSqlChange } = setup(mongoDs, '');

    act(() => result.current.applyGenerated('db.users.find({})', 'shell'));
    expect(onSqlChange).toHaveBeenCalledWith('db.users.find({})', 'generated');
    expect(result.current.effectiveSyntax).toBe('shell');

    act(() => result.current.applyGenerated('whatever', 'cypher'));
    expect(result.current.effectiveSyntax).toBe('shell'); // invalid hint ignored
  });

  it('owns the template drawer/modal choreography and applies rendered templates', () => {
    const { result, onSqlChange } = setup(baseDs, '');
    act(() => result.current.openTemplates());
    expect(result.current.templatesOpen).toBe(true);
    act(() => result.current.openSaveTemplate());
    expect(result.current.saveTemplateOpen).toBe(true);
    act(() => result.current.closeSaveTemplate());
    expect(result.current.saveTemplateOpen).toBe(false);

    const template = { id: 'tpl-1' } as QueryTemplate;
    act(() => result.current.setPendingTemplate(template));
    expect(result.current.pendingTemplate).toBe(template);

    act(() => result.current.applyTemplate('select * from t'));
    expect(onSqlChange).toHaveBeenCalledWith('select * from t', 'template');
    expect(result.current.pendingTemplate).toBeNull();
    expect(result.current.templatesOpen).toBe(false);
  });

  it('clears a failed analysis on the next edit so the empty prompt returns', async () => {
    analyzeOnlyMock.mockRejectedValue(new Error('boom'));
    dryRunQueryMock.mockRejectedValue(new Error('boom'));
    const { result } = setup(baseDs, 'select 1');

    act(() => result.current.analyze());
    act(() => result.current.dryRun());
    await waitFor(() => expect(result.current.analyzing).toBe(false));
    await waitFor(() => expect(result.current.dryRunning).toBe(false));

    act(() => result.current.handleSqlChange('select 2'));
    await waitFor(() => expect(result.current.analysis).toBeNull());
    expect(result.current.dryRunResult).toBeNull();
  });
});
