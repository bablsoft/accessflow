import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { RequestGroup, RequestGroupItem } from '@/types/api';

const { getRequestGroupMock } = vi.hoisted(() => ({
  getRequestGroupMock: vi.fn(),
}));

vi.mock('@/api/requestGroups', async () => {
  const actual = await vi.importActual<typeof import('@/api/requestGroups')>('@/api/requestGroups');
  return {
    ...actual,
    getRequestGroup: getRequestGroupMock,
    executeRequestGroup: vi.fn(),
    cancelRequestGroup: vi.fn(),
    approveRequestGroup: vi.fn(),
    rejectRequestGroup: vi.fn(),
  };
});

const RequestGroupDetailPage = (await import('./RequestGroupDetailPage')).default;

function item(overrides: Partial<RequestGroupItem> = {}): RequestGroupItem {
  return {
    id: 'i-1',
    sequence_order: 0,
    target_kind: 'QUERY',
    datasource_id: 'ds-1',
    datasource_name: 'prod-db',
    sql_text: 'SELECT * FROM users',
    query_type: 'SELECT',
    transactional: false,
    api_connector_id: null,
    api_connector_name: null,
    operation_id: null,
    verb: null,
    request_path: null,
    ai_analysis_id: null,
    ai_risk_level: 'MEDIUM',
    ai_risk_score: 40,
    ai_analysis: null,
    status: 'PENDING',
    response_status_code: null,
    rows_affected: null,
    error_message: null,
    duration_ms: null,
    executed_at: null,
    ...overrides,
  };
}

function group(items: RequestGroupItem[]): RequestGroup {
  return {
    id: 'g-1',
    organization_id: 'org-1',
    submitted_by_user_id: 'u-1',
    submitted_by_display_name: 'Dana',
    name: 'nightly bundle',
    description: null,
    status: 'PENDING_REVIEW',
    continue_on_error: false,
    scheduled_for: null,
    ai_risk_level: 'MEDIUM',
    ai_risk_score: 40,
    required_approvals: 1,
    current_review_stage: 1,
    error_message: null,
    execution_started_at: null,
    execution_completed_at: null,
    created_at: '2026-07-01T10:00:00Z',
    updated_at: '2026-07-01T10:00:00Z',
    items,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={['/request-groups/g-1']}>
          <Routes>
            <Route path="/request-groups/:id" element={<RequestGroupDetailPage />} />
          </Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  );
}

describe('RequestGroupDetailPage', () => {
  beforeEach(() => {
    getRequestGroupMock.mockReset();
    useAuthStore.setState({
      user: {
        id: 'u-1',
        email: 'u@x.io',
        display_name: 'Dana',
        role: 'ANALYST',
        organization_id: 'org-1',
      } as never,
    });
  });

  it('renders member steps in sequence order with testids', async () => {
    getRequestGroupMock.mockResolvedValue(
      group([
        item({ id: 'i-2', sequence_order: 1, sql_text: 'SELECT 2' }),
        item({ id: 'i-1', sequence_order: 0, sql_text: 'SELECT 1' }),
      ]),
    );
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('group-step-0')).toBeInTheDocument();
    });
    expect(screen.getByTestId('group-step-1')).toBeInTheDocument();
    expect(screen.getByText('nightly bundle')).toBeInTheDocument();
  });

  it('expanding a step surfaces its embedded AI analysis', async () => {
    getRequestGroupMock.mockResolvedValue(
      group([
        item({
          ai_analysis_id: 'a-1',
          ai_analysis: {
            id: 'a-1',
            risk_level: 'MEDIUM',
            risk_score: 40,
            summary: 'Reads all user rows.',
            issues: [],
            optimizations: [],
            missing_indexes_detected: false,
            affects_row_estimate: null,
            ai_provider: 'OPENAI',
            ai_model: 'gpt-4o',
            prompt_tokens: 12,
            completion_tokens: 7,
            failed: false,
            error_message: null,
          },
        }),
      ]),
    );
    renderPage();

    await waitFor(() => {
      expect(screen.getByTestId('group-step-0-toggle')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('group-step-0-toggle'));

    expect(screen.getByTestId('group-step-0-ai')).toHaveTextContent('Reads all user rows.');
    expect(screen.getByTestId('group-step-0-body')).toHaveTextContent('SELECT * FROM users');
  });
});
