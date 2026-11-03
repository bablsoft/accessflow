import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import '@/i18n';
import { SYSTEM_ROLE_PERMISSIONS } from '@/mocks/systemRolePermissions';
import { useAuthStore } from '@/store/authStore';
import type { ApiRequest, QueryStatus } from '@/types/api';

const mocks = vi.hoisted(() => ({
  getApiRequest: vi.fn(),
  replayApiRequest: vi.fn(),
  executeApiRequest: vi.fn(),
  cancelApiRequest: vi.fn(),
  approveApiReview: vi.fn(),
  rejectApiReview: vi.fn(),
  downloadApiResponse: vi.fn(),
}));

vi.mock('@/api/apiRequests', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiRequests')>('@/api/apiRequests');
  return { ...actual, ...mocks };
});

vi.mock('./ApiResponseDiffCard', () => ({
  ApiResponseDiffCard: ({ request }: { request: ApiRequest }) => (
    <div data-testid="diff-card">{request.id}</div>
  ),
}));

const { default: ApiRequestDetailPage } = await import('./ApiRequestDetailPage');

function request(overrides: Partial<ApiRequest> = {}): ApiRequest {
  return {
    id: 'r-1',
    connector_id: 'c-1',
    connector_name: 'Payments API',
    submitted_by: 'u-me',
    submitted_by_email: 'me@example.com',
    operation_id: null,
    verb: 'GET',
    request_path: '/v1/charges',
    write: false,
    status: 'EXECUTED',
    submission_reason: 'USER_SUBMITTED',
    justification: 'audit',
    ai_analysis_id: null,
    ai_risk_level: 'LOW',
    ai_risk_score: 5,
    ai_summary: 'Reads charges',
    variable_overrides: { tenant: 'acme' },
    body_type: null,
    scheduled_for: null,
    trace_id: null,
    span_id: null,
    response_status_code: 200,
    response_duration_ms: 30,
    response_bytes: 12,
    response_truncated: true,
    response_snapshot: '{"ok":true}',
    response_snapshot_preview_truncated: true,
    response_content_type: 'application/json',
    error_message: null,
    created_at: '2026-11-01T10:00:00Z',
    decisions: [],
    ...overrides,
  };
}

function setUser(role: keyof typeof SYSTEM_ROLE_PERMISSIONS, id = 'u-me') {
  useAuthStore.setState({
    user: {
      id,
      email: `${role.toLowerCase()}@example.com`,
      display_name: role,
      role,
      role_id: null,
      permissions: SYSTEM_ROLE_PERMISSIONS[role],
      auth_provider: 'LOCAL',
      totp_enabled: false,
      platform_admin: false,
      preferred_language: 'en',
    },
    accessToken: 'token',
  });
}

function axiosError(status: number, data: unknown): AxiosError {
  const response = { data, status, statusText: '', headers: {}, config: {} as never } as AxiosResponse;
  return new AxiosError('Request failed', undefined, undefined, undefined, response);
}

function LocationProbe() {
  return <div data-testid="location">{useLocation().pathname}</div>;
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/api-requests/r-1']}>
        <AntdApp>
          <Routes>
            <Route path="/api-requests/:id" element={<>{node}<LocationProbe /></>} />
            <Route path="/api-requests" element={<LocationProbe />} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

async function openReplay() {
  fireEvent.click(await screen.findByText('Replay'));
  const dialog = await screen.findByRole('dialog');
  fireEvent.click(within(dialog).getByText('Replay'));
}

describe('ApiRequestDetailPage', () => {
  beforeEach(() => {
    for (const m of Object.values(mocks)) m.mockReset();
    setUser('ANALYST');
  });

  afterEach(() => {
    useAuthStore.setState({ user: null, accessToken: null });
  });

  it('renders the request, its cards and the diff card for an executed call', async () => {
    mocks.getApiRequest.mockResolvedValue(request());
    render(wrap(<ApiRequestDetailPage />));
    expect(await screen.findByText('GET /v1/charges')).toBeInTheDocument();
    expect(screen.getByText('Reads charges')).toBeInTheDocument();
    expect(screen.getByText('acme')).toBeInTheDocument();
    expect(screen.getByTestId('diff-card')).toHaveTextContent('r-1');
    expect(screen.getByText('Truncated')).toBeInTheDocument();
  });

  it('shows the not-found state', async () => {
    mocks.getApiRequest.mockRejectedValue(axiosError(404, {}));
    render(wrap(<ApiRequestDetailPage />));
    expect(await screen.findByText('Request not found')).toBeInTheDocument();
  });

  describe('replay', () => {
    it.each<QueryStatus>(['EXECUTED', 'FAILED', 'REJECTED', 'TIMED_OUT', 'CANCELLED'])(
      'offers Replay to the submitter of a %s request',
      async (status) => {
        mocks.getApiRequest.mockResolvedValue(request({ status, response_snapshot: null }));
        render(wrap(<ApiRequestDetailPage />));
        expect(await screen.findByText('Replay')).toBeInTheDocument();
      },
    );

    it.each<QueryStatus>(['PENDING_AI', 'PENDING_REVIEW', 'APPROVED'])(
      'hides Replay while the request is %s',
      async (status) => {
        mocks.getApiRequest.mockResolvedValue(request({ status }));
        render(wrap(<ApiRequestDetailPage />));
        await screen.findByText('GET /v1/charges');
        expect(screen.queryByText('Replay')).toBeNull();
      },
    );

    it('hides Replay from someone who did not submit the request', async () => {
      setUser('ADMIN', 'u-admin');
      mocks.getApiRequest.mockResolvedValue(request());
      render(wrap(<ApiRequestDetailPage />));
      await screen.findByText('GET /v1/charges');
      expect(screen.queryByText('Replay')).toBeNull();
    });

    it('confirms, then navigates to the new request', async () => {
      mocks.getApiRequest.mockResolvedValue(request());
      mocks.replayApiRequest.mockResolvedValue({ ...request({ id: 'r-9', status: 'PENDING_AI' }), dropped_variable_override_count: 0 });
      render(wrap(<ApiRequestDetailPage />));
      await openReplay();
      await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/api-requests/r-9'));
      expect(mocks.replayApiRequest.mock.calls[0]?.[0]).toBe('r-1');
      expect(await screen.findByText('Replay submitted')).toBeInTheDocument();
    });

    it('warns when variable overrides were dropped', async () => {
      mocks.getApiRequest.mockResolvedValue(request());
      mocks.replayApiRequest.mockResolvedValue({ ...request({ id: 'r-9' }), dropped_variable_override_count: 2 });
      render(wrap(<ApiRequestDetailPage />));
      await openReplay();
      expect(await screen.findByText(/2 variable override\(s\) you can no longer set/)).toBeInTheDocument();
    });

    it('maps a 422 operation-unavailable error', async () => {
      mocks.getApiRequest.mockResolvedValue(request());
      mocks.replayApiRequest.mockRejectedValue(axiosError(422, { error: 'API_REPLAY_OPERATION_UNAVAILABLE' }));
      render(wrap(<ApiRequestDetailPage />));
      await openReplay();
      expect(await screen.findByText(/no longer in the connector's catalog/)).toBeInTheDocument();
      expect(screen.getByTestId('location')).toHaveTextContent('/api-requests/r-1');
    });

    it('maps a 409 invalid-state error', async () => {
      mocks.getApiRequest.mockResolvedValue(request());
      mocks.replayApiRequest.mockRejectedValue(axiosError(409, { error: 'API_REQUEST_INVALID_STATE' }));
      render(wrap(<ApiRequestDetailPage />));
      await openReplay();
      expect(await screen.findByText('Only finished requests can be replayed.')).toBeInTheDocument();
    });

    it('does not replay when the modal is cancelled', async () => {
      mocks.getApiRequest.mockResolvedValue(request());
      render(wrap(<ApiRequestDetailPage />));
      fireEvent.click(await screen.findByText('Replay'));
      const dialog = await screen.findByRole('dialog');
      fireEvent.click(within(dialog).getByText('Cancel'));
      expect(mocks.replayApiRequest).not.toHaveBeenCalled();
    });
  });

  it('executes an approved request', async () => {
    mocks.getApiRequest.mockResolvedValue(request({ status: 'APPROVED', response_snapshot: null }));
    mocks.executeApiRequest.mockResolvedValue(request());
    render(wrap(<ApiRequestDetailPage />));
    fireEvent.click(await screen.findByText('Execute'));
    expect(await screen.findByText('Request executed')).toBeInTheDocument();
  });

  it('lets the submitter cancel a pending request and surfaces failures', async () => {
    mocks.getApiRequest.mockResolvedValue(request({ status: 'PENDING_REVIEW', response_snapshot: null }));
    mocks.cancelApiRequest.mockRejectedValueOnce(axiosError(409, { detail: 'already decided' }));
    render(wrap(<ApiRequestDetailPage />));
    fireEvent.click(await screen.findByText('Cancel'));
    expect(await screen.findByText('already decided')).toBeInTheDocument();
    mocks.cancelApiRequest.mockResolvedValueOnce(undefined);
    fireEvent.click(screen.getByText('Cancel'));
    expect(await screen.findByText('Request cancelled')).toBeInTheDocument();
  });

  it('lets a reviewer approve a request they did not submit', async () => {
    setUser('REVIEWER', 'u-reviewer');
    mocks.getApiRequest.mockResolvedValue(request({ status: 'PENDING_REVIEW', response_snapshot: null }));
    mocks.approveApiReview.mockResolvedValue({});
    render(wrap(<ApiRequestDetailPage />));
    fireEvent.click(await screen.findByText('Approve'));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByPlaceholderText('Comment'), { target: { value: ' fine ' } });
    fireEvent.click(within(dialog).getByText('OK'));
    await waitFor(() => expect(mocks.approveApiReview).toHaveBeenCalledWith('r-1', 'fine'));
    expect(await screen.findByText('Approved')).toBeInTheDocument();
  });

  it('lets a reviewer reject without a comment', async () => {
    setUser('REVIEWER', 'u-reviewer');
    mocks.getApiRequest.mockResolvedValue(request({ status: 'PENDING_REVIEW', response_snapshot: null }));
    mocks.rejectApiReview.mockResolvedValue({});
    render(wrap(<ApiRequestDetailPage />));
    fireEvent.click(await screen.findByText('Reject'));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByText('OK'));
    await waitFor(() => expect(mocks.rejectApiReview).toHaveBeenCalledWith('r-1', undefined));
  });

  it('downloads the full response and reports a failure', async () => {
    mocks.getApiRequest.mockResolvedValue(request());
    const createUrl = vi.fn(() => 'blob:x');
    const revokeUrl = vi.fn();
    Object.assign(URL, { createObjectURL: createUrl, revokeObjectURL: revokeUrl });
    mocks.downloadApiResponse.mockResolvedValueOnce({ blob: new Blob(['{}']), filename: 'r.json', contentType: 'application/json' });
    render(wrap(<ApiRequestDetailPage />));
    fireEvent.click(await screen.findByText('Download full response'));
    await waitFor(() => expect(revokeUrl).toHaveBeenCalledWith('blob:x'));
    mocks.downloadApiResponse.mockRejectedValueOnce(new Error('disk full'));
    fireEvent.click(screen.getByText('Download full response'));
    expect(await screen.findByText('disk full')).toBeInTheDocument();
  });

  it('navigates back to the list', async () => {
    mocks.getApiRequest.mockResolvedValue(request({ error_message: 'upstream 500' }));
    render(wrap(<ApiRequestDetailPage />));
    expect(await screen.findByText('upstream 500')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Back'));
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(/^\/api-requests$/));
  });
});
