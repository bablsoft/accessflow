import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import type { QueryDetail, Role } from '@/types/api';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';

function buildAxiosError(status: number, data: unknown): AxiosError {
  const response = {
    data,
    status,
    statusText: '',
    headers: {},
    config: {} as never,
  } as AxiosResponse;
  return new AxiosError('Request failed', undefined, undefined, undefined, response);
}

const { getQueryMock, cancelQueryMock, executeQueryMock, reanalyzeQueryMock } = vi.hoisted(() => ({
  getQueryMock: vi.fn(),
  cancelQueryMock: vi.fn(),
  executeQueryMock: vi.fn(),
  reanalyzeQueryMock: vi.fn(),
}));

vi.mock('@/api/queries', () => ({
  getQuery: getQueryMock,
  cancelQuery: cancelQueryMock,
  executeQuery: executeQueryMock,
  reanalyzeQuery: reanalyzeQueryMock,
  queryKeys: {
    all: ['queries'] as const,
    lists: () => ['queries', 'list'] as const,
    list: (filters: unknown) => ['queries', 'list', filters] as const,
    details: () => ['queries', 'detail'] as const,
    detail: (id: string) => ['queries', 'detail', id] as const,
    results: (id: string, page: number, size: number) =>
      ['queries', 'detail', id, 'results', page, size] as const,
  },
}));

vi.mock('@/api/reviews', () => ({
  approveQuery: vi.fn(),
  rejectQuery: vi.fn(),
  requestChanges: vi.fn(),
  reviewKeys: { all: ['reviews'] as const },
}));

vi.mock('@/components/queries/QueryResultsTable', () => ({
  QueryResultsTable: () => <div data-testid="results-table-stub" />,
}));

const { QueryDetailPage } = await import('./QueryDetailPage');

function failedQuery(): QueryDetail {
  return {
    id: 'q-1',
    datasource: { id: 'ds-1', name: 'Prod PG' },
    submitted_by: { id: 'u-submitter', email: 's@example.com', display_name: 'Submitter' },
    sql_text: 'SELECT 1',
    query_type: 'SELECT',
    status: 'PENDING_REVIEW',
    justification: 'ticket-42',
    ai_analysis: {
      id: 'ai-1',
      risk_level: 'CRITICAL',
      risk_score: 100,
      summary: 'AI analysis failed: provider unavailable',
      issues: [],
      missing_indexes_detected: false,
      affects_row_estimate: null,
      ai_provider: 'ANTHROPIC',
      ai_model: 'unknown',
      prompt_tokens: 0,
      completion_tokens: 0,
      failed: true,
      error_message: 'provider unavailable',
    },
    rows_affected: null,
    duration_ms: null,
    error_message: null,
    review_plan_name: 'Prod plan',
    approval_timeout_hours: 24,
    review_decisions: [],
    created_at: '2026-05-01T10:00:00Z',
    updated_at: '2026-05-01T10:00:30Z',
  };
}

function setUser(role: Role, userId = 'u-reviewer') {
  useAuthStore.setState({
    user: {
      id: userId,
      email: `${role.toLowerCase()}@example.com`,
      display_name: role,
      role,
      auth_provider: 'LOCAL',
      totp_enabled: false,
      preferred_language: 'en',
    },
    accessToken: 'token',
  });
}

function wrap(node: ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/queries/q-1']}>
        <App>
          <Routes>
            <Route path="/queries/:id" element={node} />
          </Routes>
        </App>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('QueryDetailPage — AI failure surface (AF-249)', () => {
  beforeEach(() => {
    getQueryMock.mockReset();
    cancelQueryMock.mockReset();
    executeQueryMock.mockReset();
    reanalyzeQueryMock.mockReset();
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('renders the warning banner and reason when AI failed', async () => {
    setUser('REVIEWER');
    getQueryMock.mockResolvedValue(failedQuery());

    render(wrap(<QueryDetailPage />));

    // Both the warning banner and the accordion title carry this label, so use findAllByText.
    const titles = await screen.findAllByText('AI analysis failed');
    expect(titles.length).toBeGreaterThanOrEqual(2);
    // Reason rendered inside the banner detail (interpolated via {{reason}}).
    expect(screen.getAllByText(/provider unavailable/).length).toBeGreaterThan(0);
  });

  it('renders the failure variant of the AI accordion with reason', async () => {
    setUser('REVIEWER');
    getQueryMock.mockResolvedValue(failedQuery());

    render(wrap(<QueryDetailPage />));

    // Accordion body renders the explainer string.
    expect(
      await screen.findByText(/no risk score is available/i),
    ).toBeInTheDocument();
    // Reason label appears in the accordion's reason chip (and may also surface elsewhere).
    expect(screen.getAllByText(/Reason:/).length).toBeGreaterThan(0);
  });

  it('shows "Re-analyze" for REVIEWER role and triggers the mutation', async () => {
    setUser('REVIEWER');
    getQueryMock.mockResolvedValue(failedQuery());
    reanalyzeQueryMock.mockResolvedValue(undefined);

    render(wrap(<QueryDetailPage />));

    // Wait for the page to render, then find one of the Re-analyze buttons.
    const buttons = await screen.findAllByRole('button', { name: /Re-analyze/i });
    expect(buttons.length).toBeGreaterThan(0);
    const firstButton = buttons[0];
    if (!firstButton) throw new Error('expected at least one Re-analyze button');

    await act(async () => {
      fireEvent.click(firstButton);
    });

    await waitFor(() => {
      expect(reanalyzeQueryMock).toHaveBeenCalledWith('q-1');
    });
  });

  it('hides "Re-analyze" for ANALYST role', async () => {
    setUser('ANALYST', 'u-submitter');
    getQueryMock.mockResolvedValue(failedQuery());

    render(wrap(<QueryDetailPage />));

    const titles = await screen.findAllByText('AI analysis failed');
    expect(titles.length).toBeGreaterThanOrEqual(1);
    expect(screen.queryByRole('button', { name: /Re-analyze/i })).toBeNull();
  });

  it('shows "Re-analyze" for ADMIN role', async () => {
    setUser('ADMIN');
    getQueryMock.mockResolvedValue(failedQuery());

    render(wrap(<QueryDetailPage />));

    expect(
      (await screen.findAllByRole('button', { name: /Re-analyze/i })).length,
    ).toBeGreaterThan(0);
  });

  it('does not render the failure banner when analysis succeeded', async () => {
    setUser('REVIEWER');
    const ok = failedQuery();
    ok.ai_analysis = {
      ...ok.ai_analysis!,
      risk_level: 'LOW',
      risk_score: 10,
      summary: 'looks fine',
      failed: false,
      error_message: null,
    };
    getQueryMock.mockResolvedValue(ok);

    render(wrap(<QueryDetailPage />));

    expect(await screen.findByText('looks fine')).toBeInTheDocument();
    expect(screen.queryByText(/Review is proceeding without an AI recommendation/i)).toBeNull();
  });
});

function pendingReviewQuery(): QueryDetail {
  const q = failedQuery();
  q.status = 'PENDING_REVIEW';
  q.ai_analysis = {
    ...q.ai_analysis!,
    risk_level: 'LOW',
    risk_score: 10,
    summary: 'fine',
    failed: false,
    error_message: null,
  };
  return q;
}

describe('QueryDetailPage — submitter cancel (AF-266)', () => {
  beforeEach(() => {
    getQueryMock.mockReset();
    cancelQueryMock.mockReset();
    executeQueryMock.mockReset();
    reanalyzeQueryMock.mockReset();
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('submitter on PENDING_REVIEW confirms Popconfirm → cancelQuery is called', async () => {
    // failedQuery().submitted_by.id === 'u-submitter', so the auth user must match.
    setUser('ANALYST', 'u-submitter');
    getQueryMock.mockResolvedValue(pendingReviewQuery());
    cancelQueryMock.mockResolvedValue(undefined);

    render(wrap(<QueryDetailPage />));

    const cancelBtn = await screen.findByRole('button', { name: /Cancel query/i });
    await act(async () => {
      fireEvent.click(cancelBtn);
    });

    // AntD renders OK / Cancel into a portal under document.body — RTL finds it.
    const okBtn = await screen.findByRole('button', { name: /^OK$/i });
    await act(async () => {
      fireEvent.click(okBtn);
    });

    await waitFor(() => {
      expect(cancelQueryMock).toHaveBeenCalledWith('q-1');
    });
  });

  it('non-submitter on PENDING_REVIEW does not see the Cancel button', async () => {
    setUser('REVIEWER', 'u-reviewer');
    getQueryMock.mockResolvedValue(pendingReviewQuery());

    render(wrap(<QueryDetailPage />));

    // Wait for the page to render before asserting absence.
    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByRole('button', { name: /Cancel query/i })).toBeNull();
  });

  it('renders an error toast when cancel rejects with 409 QUERY_NOT_CANCELLABLE', async () => {
    setUser('ANALYST', 'u-submitter');
    getQueryMock.mockResolvedValue(pendingReviewQuery());
    cancelQueryMock.mockRejectedValue(
      buildAxiosError(409, {
        type: 'about:blank',
        title: 'Conflict',
        status: 409,
        detail: 'Query is not cancellable',
        error: 'QUERY_NOT_CANCELLABLE',
        currentStatus: 'APPROVED',
      }),
    );

    render(wrap(<QueryDetailPage />));

    const cancelBtn = await screen.findByRole('button', { name: /Cancel query/i });
    await act(async () => {
      fireEvent.click(cancelBtn);
    });
    const okBtn = await screen.findByRole('button', { name: /^OK$/i });
    await act(async () => {
      fireEvent.click(okBtn);
    });

    await waitFor(() => {
      expect(
        screen.getByText('This query can no longer be cancelled (it has already advanced).'),
      ).toBeInTheDocument();
    });
  });
});
