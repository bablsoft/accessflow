import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AxiosError, type AxiosResponse } from 'axios';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ApiRequest } from '@/types/api';

const { listApiRequests, listApiConnectors, executeApiRequest } = vi.hoisted(() => ({
  executeApiRequest: vi.fn(),
  listApiRequests: vi.fn(),
  listApiConnectors: vi.fn(),
}));

vi.mock('@/api/apiRequests', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiRequests')>('@/api/apiRequests');
  return { ...actual, listApiRequests, cancelApiRequest: vi.fn(), executeApiRequest };
});
vi.mock('@/api/apiConnectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return { ...actual, listApiConnectors };
});

const { default: ApiRequestsListPage } = await import('./ApiRequestsListPage');

function request(overrides: Partial<ApiRequest>): ApiRequest {
  return {
    id: 'r-1-aaaaaaaa',
    connector_id: 'c-1',
    connector_name: 'Payments API',
    submitted_by: 'u-me',
    submitted_by_email: 'me@example.com',
    operation_id: null,
    verb: 'GET',
    request_path: '/v1/charges',
    write: false,
    status: 'APPROVED',
    submission_reason: 'USER_SUBMITTED',
    justification: null,
    ai_analysis_id: null,
    ai_risk_level: null,
    ai_risk_score: null,
    ai_summary: null,
    variable_overrides: {},
    body_type: null,
    scheduled_for: null,
    trace_id: null,
    span_id: null,
    response_status_code: null,
    response_duration_ms: null,
    response_bytes: null,
    response_truncated: false,
    response_snapshot: null,
    response_snapshot_preview_truncated: false,
    response_content_type: null,
    error_message: null,
    created_at: '2026-11-10T10:00:00Z',
    decisions: [],
    ...overrides,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AntdApp>{node}</AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ApiRequestsListPage — throttled requests (#974)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listApiConnectors.mockResolvedValue({ content: [], page: 0, size: 100, total_elements: 0, total_pages: 0 });
  });

  it('marks a rate-limit-queued approved request with the hourglass, and only that one', async () => {
    listApiRequests.mockResolvedValue({
      content: [
        request({ id: 'queued-1-xxxx', throttled_until: '2026-11-10T10:01:00Z' }),
        request({ id: 'plain-22-xxxx', request_path: '/v1/refunds' }),
      ],
      page: 0,
      size: 20,
      total_elements: 2,
      total_pages: 1,
    });
    render(wrap(<ApiRequestsListPage />));
    expect(await screen.findByText('/v1/refunds')).toBeInTheDocument();
    expect(screen.getAllByLabelText('Queued by a connector rate limit')).toHaveLength(1);
  });

  it('warns and refetches when Execute hits a connector rate limit', async () => {
    const page = { content: [request({ id: 'r-9-xxxxxxx' })], page: 0, size: 20, total_elements: 1, total_pages: 1 };
    listApiRequests.mockResolvedValue(page);
    const response = {
      data: { error: 'API_CONNECTOR_QUOTA_EXCEEDED', detail: 'Rate limit reached', limitKind: 'MINUTE' },
      status: 429, statusText: '', headers: {}, config: {} as never,
    } as AxiosResponse;
    executeApiRequest.mockRejectedValue(new AxiosError('x', undefined, undefined, undefined, response));
    render(wrap(<ApiRequestsListPage />));
    fireEvent.click(await screen.findByText('Execute'));
    expect(await screen.findByText('Rate limit reached (Calls per minute)')).toBeInTheDocument();
    await waitFor(() => expect(listApiRequests).toHaveBeenCalledTimes(2));
  });

  it('keeps the error toast for other execute failures', async () => {
    listApiRequests.mockResolvedValue({ content: [request({ id: 'r-8-xxxxxxx' })], page: 0, size: 20, total_elements: 1, total_pages: 1 });
    executeApiRequest.mockRejectedValue(new Error('Upstream failed'));
    render(wrap(<ApiRequestsListPage />));
    fireEvent.click(await screen.findByText('Execute'));
    expect(await screen.findByText('Upstream failed')).toBeInTheDocument();
  });
});
