import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AiAnalysisStats, DatasourcePage } from '@/types/api';

const { fetchAiAnalysisStatsMock, listDatasourcesMock } = vi.hoisted(() => ({
  fetchAiAnalysisStatsMock: vi.fn(),
  listDatasourcesMock: vi.fn(),
}));

vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return {
    ...actual,
    fetchAiAnalysisStats: fetchAiAnalysisStatsMock,
  };
});

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>(
    '@/api/datasources',
  );
  return {
    ...actual,
    listDatasources: listDatasourcesMock,
  };
});

// Charts library is mocked so tests stay deterministic and don't try to render canvas.
vi.mock('@ant-design/charts', () => ({
  Line: ({ data }: { data: unknown[] }) => (
    <div data-testid="ant-line-chart" data-points={data.length} />
  ),
  Bar: ({ data }: { data: unknown[] }) => (
    <div data-testid="ant-bar-chart" data-points={data.length} />
  ),
}));

const { default: AiAnalysesPage } = await import('./AiAnalysesPage');

function emptyStats(): AiAnalysisStats {
  return {
    risk_score_over_time: [],
    top_issue_categories: [],
    top_submitters: [],
  };
}

function fullStats(): AiAnalysisStats {
  return {
    risk_score_over_time: [
      { date: '2026-05-10', success_avg_risk_score: 50, total_count: 4, success_count: 4 },
      { date: '2026-05-11', success_avg_risk_score: 75, total_count: 2, success_count: 2 },
    ],
    top_issue_categories: [
      { category: 'performance', count: 8 },
      { category: 'security', count: 3 },
    ],
    top_submitters: [
      { user_id: 'u1', email: 'a@x.test', display_name: 'Alice', count: 5 },
      { user_id: 'u2', email: 'b@x.test', display_name: null, count: 2 },
    ],
  };
}

function emptyDatasources(): DatasourcePage {
  return { content: [], page: 0, size: 100, total_elements: 0, total_pages: 0, last: true };
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

describe('AiAnalysesPage', () => {
  beforeEach(() => {
    fetchAiAnalysisStatsMock.mockReset();
    listDatasourcesMock.mockReset();
    listDatasourcesMock.mockResolvedValue(emptyDatasources());
  });

  it('renders the page title and the three chart cards when data is present', async () => {
    fetchAiAnalysisStatsMock.mockResolvedValue(fullStats());

    render(wrap(<AiAnalysesPage />));

    expect(screen.getByText('AI analyses')).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByTestId('ai-analyses-risk-chart')).toBeInTheDocument();
    });
    expect(screen.getByTestId('ai-analyses-categories-chart')).toBeInTheDocument();
    expect(screen.getByTestId('ai-analyses-submitters-chart')).toBeInTheDocument();
    // Line series with two metrics × two days = 4 points.
    expect(screen.getByTestId('ant-line-chart')).toHaveAttribute('data-points', '4');
    // Two bar charts: 2 categories + 2 submitters.
    const bars = screen.getAllByTestId('ant-bar-chart');
    expect(bars[0]).toHaveAttribute('data-points', '2');
    expect(bars[1]).toHaveAttribute('data-points', '2');
  });

  it('shows an empty state when every series is empty', async () => {
    fetchAiAnalysisStatsMock.mockResolvedValue(emptyStats());

    render(wrap(<AiAnalysesPage />));

    await waitFor(() => {
      expect(screen.getByText('No AI analyses in this window')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('ai-analyses-risk-chart')).not.toBeInTheDocument();
  });

  it('refetches when the refresh button is clicked', async () => {
    fetchAiAnalysisStatsMock.mockResolvedValue(emptyStats());

    render(wrap(<AiAnalysesPage />));

    await waitFor(() => {
      expect(fetchAiAnalysisStatsMock).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByRole('button', { name: /refresh/i }));

    await waitFor(() => {
      expect(fetchAiAnalysisStatsMock).toHaveBeenCalledTimes(2);
    });
  });

  it('sends datasource_id only when a specific datasource is selected', async () => {
    fetchAiAnalysisStatsMock.mockResolvedValue(emptyStats());
    listDatasourcesMock.mockResolvedValue({
      content: [
        {
          id: 'ds-1',
          organization_id: 'org-1',
          name: 'Prod',
          db_type: 'POSTGRESQL',
          host: 'h',
          port: 5432,
          database_name: 'db',
          username: 'u',
          ssl_mode: 'PREFER',
          ai_analysis_enabled: true,
          active: true,
          created_at: '2026-05-01T00:00:00Z',
          require_review_reads: true,
          require_review_writes: true,
          connection_pool_size: 5,
          max_rows_per_query: 10000,
        },
      ],
      page: 0,
      size: 100,
      total_elements: 1,
      total_pages: 1,
      last: true,
    });

    render(wrap(<AiAnalysesPage />));

    await waitFor(() => {
      expect(fetchAiAnalysisStatsMock).toHaveBeenCalledWith(
        expect.objectContaining({ datasource_id: undefined }),
      );
    });
  });
});
