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

const { getQueryMock, cancelQueryMock, executeQueryMock, reanalyzeQueryMock, getQueryDiffMock } = vi.hoisted(() => ({
  getQueryMock: vi.fn(),
  cancelQueryMock: vi.fn(),
  executeQueryMock: vi.fn(),
  reanalyzeQueryMock: vi.fn(),
  getQueryDiffMock: vi.fn(),
}));

vi.mock('@/api/queries', () => ({
  getQuery: getQueryMock,
  cancelQuery: cancelQueryMock,
  executeQuery: executeQueryMock,
  reanalyzeQuery: reanalyzeQueryMock,
  getQueryDiff: getQueryDiffMock,
  queryKeys: {
    all: ['queries'] as const,
    lists: () => ['queries', 'list'] as const,
    list: (filters: unknown) => ['queries', 'list', filters] as const,
    details: () => ['queries', 'detail'] as const,
    detail: (id: string) => ['queries', 'detail', id] as const,
    results: (id: string, page: number, size: number) =>
      ['queries', 'detail', id, 'results', page, size] as const,
    diff: (id: string) => ['queries', 'detail', id, 'diff'] as const,
  },
}));

const { approveQueryMock, rejectQueryMock, requestChangesMock } = vi.hoisted(
  () => ({
    approveQueryMock: vi.fn(),
    rejectQueryMock: vi.fn(),
    requestChangesMock: vi.fn(),
  }),
);

vi.mock('@/api/reviews', () => ({
  approveQuery: approveQueryMock,
  rejectQuery: rejectQueryMock,
  requestChanges: requestChangesMock,
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
    previous_run_id: null,
    review_plan_name: 'Prod plan',
    approval_timeout_hours: 24,
    matched_policy: null,
    review_decisions: [],
    scheduled_for: null,
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

function skippedQuery(): QueryDetail {
  const q = failedQuery();
  q.status = 'PENDING_REVIEW';
  q.ai_analysis = null;
  return q;
}

function pendingAiQuery(): QueryDetail {
  const q = failedQuery();
  q.status = 'PENDING_AI';
  q.ai_analysis = null;
  return q;
}

function rejectedQuery(): QueryDetail {
  const q = pendingReviewQuery();
  q.status = 'REJECTED';
  q.review_decisions = [
    {
      id: 'd-1',
      reviewer: {
        id: 'u-reviewer',
        email: 'reviewer@example.com',
        display_name: 'Rev McReviewer',
      },
      decision: 'REJECTED',
      comment: 'narrow the WHERE clause',
      stage: 1,
      decided_at: '2026-05-01T10:05:00Z',
    },
  ];
  return q;
}

function changesRequestedQuery(): QueryDetail {
  const q = pendingReviewQuery();
  q.review_decisions = [
    {
      id: 'd-2',
      reviewer: {
        id: 'u-reviewer',
        email: 'reviewer@example.com',
        display_name: 'Rev McReviewer',
      },
      decision: 'REQUESTED_CHANGES',
      comment: 'add LIMIT 100',
      stage: 1,
      decided_at: '2026-05-01T10:06:00Z',
    },
  ];
  return q;
}

describe('QueryDetailPage — reviewer decision panel (AF-269)', () => {
  beforeEach(() => {
    getQueryMock.mockReset();
    cancelQueryMock.mockReset();
    executeQueryMock.mockReset();
    reanalyzeQueryMock.mockReset();
    approveQueryMock.mockReset();
    rejectQueryMock.mockReset();
    requestChangesMock.mockReset();
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('disables the Reject button when the comment textarea is empty', async () => {
    setUser('REVIEWER');
    getQueryMock.mockResolvedValue(pendingReviewQuery());

    render(wrap(<QueryDetailPage />));

    const rejectButton = await screen.findByRole('button', { name: /Reject/ });
    expect(rejectButton).toBeDisabled();
  });

  it('enables Reject once a non-empty comment is typed and fires rejectQuery', async () => {
    setUser('REVIEWER');
    getQueryMock.mockResolvedValue(pendingReviewQuery());
    rejectQueryMock.mockResolvedValue({
      query_request_id: 'q-1',
      decision_id: 'd-1',
      decision: 'REJECTED',
      resulting_status: 'REJECTED',
      idempotent_replay: false,
    });

    render(wrap(<QueryDetailPage />));

    const textarea = await screen.findByPlaceholderText(/Optional comment/);
    await act(async () => {
      fireEvent.change(textarea, { target: { value: 'too risky' } });
    });

    const rejectButton = screen.getAllByRole('button').find((b) => /Reject/.test(b.textContent ?? ''))!;
    expect(rejectButton).not.toBeDisabled();
    await act(async () => {
      fireEvent.click(rejectButton);
    });
    await waitFor(() => {
      expect(rejectQueryMock).toHaveBeenCalledWith('q-1', 'too risky');
    });
  });

  it('renders the "Changes requested" banner when the latest decision is REQUESTED_CHANGES and status is PENDING_REVIEW', async () => {
    // Submitter view — must NOT be the reviewer that decided.
    setUser('ANALYST', 'u-submitter');
    getQueryMock.mockResolvedValue(changesRequestedQuery());

    render(wrap(<QueryDetailPage />));

    expect(await screen.findByText('Changes requested')).toBeInTheDocument();
    expect(screen.getByText(/add LIMIT 100/)).toBeInTheDocument();
  });

  it('does NOT render the banner when the latest decision is APPROVED', async () => {
    setUser('ANALYST', 'u-submitter');
    const q = changesRequestedQuery();
    q.review_decisions[0]!.decision = 'APPROVED';
    getQueryMock.mockResolvedValue(q);

    render(wrap(<QueryDetailPage />));

    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByText('Changes requested')).toBeNull();
  });

  it('surfaces the rejection comment in the timeline rejected stage', async () => {
    setUser('ANALYST', 'u-submitter');
    getQueryMock.mockResolvedValue(rejectedQuery());

    render(wrap(<QueryDetailPage />));

    // ApprovalTimeline wraps the comment in double quotes to opt into italic.
    expect(
      await screen.findByText('"narrow the WHERE clause"'),
    ).toBeInTheDocument();
  });
});

describe('QueryDetailPage — AI analysis skipped surface (AF-307)', () => {
  beforeEach(() => {
    getQueryMock.mockReset();
    cancelQueryMock.mockReset();
    executeQueryMock.mockReset();
    reanalyzeQueryMock.mockReset();
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('renders skipped card title, body, and timeline label when ai_analysis is null and status is past PENDING_AI', async () => {
    setUser('REVIEWER');
    getQueryMock.mockResolvedValue(skippedQuery());

    render(wrap(<QueryDetailPage />));

    expect(await screen.findByText('AI analysis (skipped)')).toBeInTheDocument();
    expect(
      screen.getByText(
        'AI analysis was skipped — this datasource has AI analysis disabled.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('AI analysis skipped')).toBeInTheDocument();
    // The pending fallback must not appear when skipped.
    expect(screen.queryByText('Awaiting analysis…')).toBeNull();
  });

  it('keeps the "Awaiting analysis…" fallback when status is still PENDING_AI', async () => {
    setUser('REVIEWER');
    getQueryMock.mockResolvedValue(pendingAiQuery());

    render(wrap(<QueryDetailPage />));

    expect(await screen.findByText('Awaiting analysis…')).toBeInTheDocument();
    expect(screen.queryByText('AI analysis (skipped)')).toBeNull();
    expect(screen.queryByText('AI analysis skipped')).toBeNull();
  });
});

function executedQuery(): QueryDetail {
  const q = failedQuery();
  q.status = 'EXECUTED';
  q.ai_analysis = {
    ...q.ai_analysis!,
    risk_level: 'LOW',
    risk_score: 10,
    summary: 'fine',
    failed: false,
    error_message: null,
  };
  q.rows_affected = 12;
  q.duration_ms = 30;
  q.previous_run_id = 'q-prev';
  return q;
}

describe('QueryDetailPage — query diff card (AF-361)', () => {
  beforeEach(() => {
    getQueryMock.mockReset();
    cancelQueryMock.mockReset();
    executeQueryMock.mockReset();
    reanalyzeQueryMock.mockReset();
    getQueryDiffMock.mockReset();
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('renders deltas when the diff endpoint returns a populated response', async () => {
    setUser('ANALYST', 'u-submitter');
    getQueryMock.mockResolvedValue(executedQuery());
    getQueryDiffMock.mockResolvedValue({
      current_run_id: 'q-1',
      previous_run_id: 'q-prev',
      rows_affected_delta: 2,
      execution_ms_delta: -20,
      row_count_delta: 2,
    });

    render(wrap(<QueryDetailPage />));

    expect(
      await screen.findByText('Compare to previous run'),
    ).toBeInTheDocument();
    // Two badges with +2 (rows_affected_delta and row_count_delta share the same magnitude).
    expect((await screen.findAllByText(/\+2$/)).length).toBeGreaterThanOrEqual(2);
    expect(await screen.findByText('-20 ms')).toBeInTheDocument();
    expect(screen.getByText(/View previous run/)).toBeInTheDocument();
  });

  it('renders the empty state when the diff endpoint returns 404', async () => {
    setUser('ANALYST', 'u-submitter');
    const q = executedQuery();
    q.previous_run_id = null;
    getQueryMock.mockResolvedValue(q);
    getQueryDiffMock.mockRejectedValue({
      isAxiosError: true,
      response: { status: 404, data: { error: 'QUERY_DIFF_NOT_AVAILABLE' } },
    });

    render(wrap(<QueryDetailPage />));

    expect(
      await screen.findByText('No previous run found to compare against'),
    ).toBeInTheDocument();
  });

  it('does not mount the diff card when the query has not yet executed', async () => {
    setUser('REVIEWER');
    getQueryMock.mockResolvedValue(pendingReviewQuery());

    render(wrap(<QueryDetailPage />));

    // Wait for the page to render — the sql card should be visible.
    await screen.findByText('SQL');
    expect(screen.queryByText('Compare to previous run')).toBeNull();
    expect(getQueryDiffMock).not.toHaveBeenCalled();
  });
});

function failedExecutionQuery(): QueryDetail {
  const q = executedQuery();
  q.status = 'FAILED';
  q.rows_affected = null;
  q.duration_ms = 42;
  q.previous_run_id = null;
  q.error_message =
    'ERROR: invalid input value for enum query_status: "PENDING"';
  return q;
}

describe('QueryDetailPage — execution failure surface (AF-408)', () => {
  beforeEach(() => {
    getQueryMock.mockReset();
    cancelQueryMock.mockReset();
    executeQueryMock.mockReset();
    reanalyzeQueryMock.mockReset();
    getQueryDiffMock.mockReset();
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('renders the execution-failure card with the database error cause when FAILED', async () => {
    setUser('ANALYST', 'u-submitter');
    getQueryMock.mockResolvedValue(failedExecutionQuery());

    render(wrap(<QueryDetailPage />));

    expect(await screen.findByText('Error detail')).toBeInTheDocument();
    // The cause renders in the failure card and again in the timeline stage detail.
    expect(
      screen.getAllByText(
        'ERROR: invalid input value for enum query_status: "PENDING"',
      ).length,
    ).toBeGreaterThanOrEqual(1);
    expect(
      screen.getByText(
        'The query could not be executed. The database returned:',
      ),
    ).toBeInTheDocument();
    // The success-only diff/results cards never mount for a failed run.
    expect(screen.queryByText('Compare to previous run')).toBeNull();
  });

  it('renders the fallback line when FAILED but no error_message was captured', async () => {
    setUser('ANALYST', 'u-submitter');
    const q = failedExecutionQuery();
    q.error_message = null;
    getQueryMock.mockResolvedValue(q);

    render(wrap(<QueryDetailPage />));

    expect(
      await screen.findByText('No error detail was captured.'),
    ).toBeInTheDocument();
  });
});
