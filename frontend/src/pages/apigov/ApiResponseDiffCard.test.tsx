import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import '@/i18n';
import type { ApiRequest, ApiResponseDiff } from '@/types/api';

const { getApiResponseDiffMock, getApiRequestMock } = vi.hoisted(() => ({
  getApiResponseDiffMock: vi.fn(),
  getApiRequestMock: vi.fn(),
}));

vi.mock('@/api/apiRequests', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiRequests')>('@/api/apiRequests');
  return { ...actual, getApiResponseDiff: getApiResponseDiffMock, getApiRequest: getApiRequestMock };
});

vi.mock('@/components/editor/MergeDiffView', () => ({
  MergeDiffView: ({ oldValue, newValue }: { oldValue: string; newValue: string }) => (
    <div data-testid="merge-diff">
      <pre data-testid="merge-old">{oldValue}</pre>
      <pre data-testid="merge-new">{newValue}</pre>
    </div>
  ),
}));

const { ApiResponseDiffCard } = await import('./ApiResponseDiffCard');

function request(overrides: Partial<ApiRequest> = {}): ApiRequest {
  return {
    id: 'r-2',
    connector_id: 'c-1',
    connector_name: 'Health',
    submitted_by: 'u-1',
    submitted_by_email: 'a@example.com',
    operation_id: null,
    verb: 'GET',
    request_path: '/actuator/health',
    write: false,
    status: 'EXECUTED',
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
    response_status_code: 200,
    response_duration_ms: 40,
    response_bytes: 20,
    response_truncated: false,
    response_snapshot: '{"status":"UP"}',
    response_snapshot_preview_truncated: false,
    response_content_type: 'application/json',
    error_message: null,
    created_at: '2026-11-01T10:00:00Z',
    decisions: [],
    ...overrides,
  };
}

function diff(overrides: Partial<ApiResponseDiff> = {}): ApiResponseDiff {
  return {
    current_id: 'r-2',
    previous_id: 'r-1',
    format: 'JSON',
    status_code_current: 200,
    status_code_previous: 503,
    duration_ms_delta: -10,
    bytes_delta: 4,
    added_count: 1,
    removed_count: 0,
    changed_count: 1,
    truncated: false,
    either_response_truncated: false,
    entries: [
      { path: 'status', kind: 'CHANGED', before: '"DOWN"', after: '"UP"' },
      { path: 'components', kind: 'ADDED', before: null, after: '{}' },
    ],
    ...overrides,
  };
}

function axiosError(status: number, data: unknown): AxiosError {
  const response = { data, status, statusText: '', headers: {}, config: {} as never } as AxiosResponse;
  return new AxiosError('Request failed', undefined, undefined, undefined, response);
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter>{node}</MemoryRouter>
    </QueryClientProvider>
  );
}

describe('ApiResponseDiffCard', () => {
  beforeEach(() => {
    getApiResponseDiffMock.mockReset();
    getApiRequestMock.mockReset();
    getApiRequestMock.mockResolvedValue(request({ id: 'r-1', response_snapshot: '{"status":"DOWN"}' }));
  });

  it('shows a skeleton while loading', () => {
    getApiResponseDiffMock.mockReturnValue(new Promise(() => {}));
    const { container } = render(wrap(<ApiResponseDiffCard request={request()} />));
    expect(container.querySelector('.ant-skeleton')).not.toBeNull();
  });

  it('renders the empty state on 404', async () => {
    getApiResponseDiffMock.mockRejectedValue(axiosError(404, { error: 'API_RESPONSE_DIFF_NOT_AVAILABLE' }));
    render(wrap(<ApiResponseDiffCard request={request()} />));
    expect(await screen.findByText('No previous run found to compare against')).toBeInTheDocument();
  });

  it('renders the unsupported note with the server detail on 422', async () => {
    getApiResponseDiffMock.mockRejectedValue(
      axiosError(422, { error: 'API_RESPONSE_DIFF_UNSUPPORTED', detail: 'text/html is not diffable' }),
    );
    render(wrap(<ApiResponseDiffCard request={request()} />));
    expect(await screen.findByText(/only JSON and XML bodies/)).toBeInTheDocument();
    expect(screen.getByText('text/html is not diffable')).toBeInTheDocument();
  });

  it('renders the incompatible note and a generic error', async () => {
    getApiResponseDiffMock.mockRejectedValueOnce(axiosError(422, { error: 'API_RESPONSE_DIFF_INCOMPATIBLE' }));
    const { unmount } = render(wrap(<ApiResponseDiffCard request={request()} />));
    expect(await screen.findByText(/earlier executed run/)).toBeInTheDocument();
    unmount();
    getApiResponseDiffMock.mockRejectedValueOnce(axiosError(500, {}));
    render(wrap(<ApiResponseDiffCard request={request()} />));
    expect(await screen.findByText('Could not load the comparison')).toBeInTheDocument();
  });

  it('renders the entries, deltas, previous link and the side-by-side previews', async () => {
    getApiResponseDiffMock.mockResolvedValue(diff());
    render(wrap(<ApiResponseDiffCard request={request()} />));
    expect(await screen.findByText('"DOWN"')).toBeInTheDocument();
    expect(screen.getByText('"UP"')).toBeInTheDocument();
    expect(screen.getByText('Changed')).toBeInTheDocument();
    expect(screen.getByText('Added')).toBeInTheDocument();
    expect(screen.getByText('503 → 200')).toBeInTheDocument();
    expect(screen.getByText('1 added · 0 removed · 1 changed')).toBeInTheDocument();
    expect(screen.getByText(/masking-policy change/)).toBeInTheDocument();
    expect(screen.getByText('Open the previous run').closest('a')).toHaveAttribute('href', '/api-requests/r-1');
    expect(getApiResponseDiffMock).toHaveBeenCalledWith('r-2', 'previous');
    // JSON previews are pretty-printed so the panes line up.
    expect(await screen.findByTestId('merge-old')).toHaveTextContent('"status": "DOWN"');
    expect(getApiRequestMock).toHaveBeenCalledWith('r-1');
  });

  it('shows the truncation alerts and leaves an unparseable or XML preview as-is', async () => {
    getApiResponseDiffMock.mockResolvedValue(
      diff({ format: 'XML', truncated: true, either_response_truncated: true, duration_ms_delta: null, entries: [] }),
    );
    getApiRequestMock.mockResolvedValue(request({ id: 'r-1', response_snapshot: '<a>1</a>' }));
    render(wrap(<ApiResponseDiffCard request={request({ response_snapshot: '<a>2</a>' })} />));
    expect(await screen.findByText(/Only the first differences/)).toBeInTheDocument();
    expect(screen.getByText(/cut off at the storage cap/)).toBeInTheDocument();
    expect(screen.getByText('Not available')).toBeInTheDocument();
    expect(screen.getByText('The responses are identical')).toBeInTheDocument();
    expect(await screen.findByTestId('merge-old')).toHaveTextContent('<a>1</a>');
  });

  it('keeps a cut-off JSON preview verbatim', async () => {
    getApiResponseDiffMock.mockResolvedValue(diff());
    getApiRequestMock.mockResolvedValue(request({ id: 'r-1', response_snapshot: '{"status":' }));
    render(wrap(<ApiResponseDiffCard request={request()} />));
    expect(await screen.findByTestId('merge-old')).toHaveTextContent('{"status":');
  });

  it('re-keys the query when another request id is entered, and back to previous when cleared', async () => {
    getApiResponseDiffMock.mockResolvedValue(diff());
    render(wrap(<ApiResponseDiffCard request={request()} />));
    await screen.findByText('"DOWN"');
    const input = screen.getByPlaceholderText('Previous run, or paste a request ID');
    fireEvent.change(input, { target: { value: '  r-0  ' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
    await waitFor(() => expect(getApiResponseDiffMock).toHaveBeenCalledWith('r-2', 'r-0'));
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13 });
    // Back on the cached `previous` key: no refetch, the first comparison renders again.
    expect(await screen.findByText('"DOWN"')).toBeInTheDocument();
    expect(getApiResponseDiffMock).toHaveBeenCalledTimes(2);
  });

  it('does not fetch for a request that has not executed', () => {
    render(wrap(<ApiResponseDiffCard request={request({ status: 'FAILED' })} />));
    expect(getApiResponseDiffMock).not.toHaveBeenCalled();
  });
});
