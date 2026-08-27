import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { DeploymentGateStatus, DeploymentRequest } from '@/types/api';

const { getDeploymentRequestMock, getDeploymentGateMock, cancelDeploymentRequestMock } =
  vi.hoisted(() => ({
    getDeploymentRequestMock: vi.fn(),
    getDeploymentGateMock: vi.fn(),
    cancelDeploymentRequestMock: vi.fn(),
  }));

vi.mock('@/api/deploymentRequests', () => ({
  getDeploymentRequest: getDeploymentRequestMock,
  getDeploymentGate: getDeploymentGateMock,
  cancelDeploymentRequest: cancelDeploymentRequestMock,
  deploymentKeys: {
    all: ['deployments'] as const,
    lists: () => ['deployments', 'list'] as const,
    list: (filters: unknown) => ['deployments', 'list', filters] as const,
    details: () => ['deployments', 'detail'] as const,
    detail: (id: string) => ['deployments', 'detail', id] as const,
    gate: (id: string) => ['deployments', 'detail', id, 'gate'] as const,
  },
}));

const DeploymentDetailPage = (await import('./DeploymentDetailPage')).default;

const baseRequest: DeploymentRequest = {
  id: 'req-1',
  pipeline_id: 'pipe-1',
  pipeline_name: 'Checkout Service',
  provider: 'GITHUB_ACTIONS',
  environment_id: 'env-1',
  environment_name: 'production',
  submitted_by: 'u-me',
  submitted_by_email: 'dev@example.com',
  version: '2.4.1',
  commit_sha: 'abc123def456',
  artifact_ref: 'ghcr.io/acme/checkout:2.4.1',
  run_url: 'https://ci.example.com/runs/42',
  external_run_id: 'run-42',
  metadata: { branch: 'main' },
  status: 'APPROVED',
  submission_reason: 'USER_SUBMITTED',
  justification: 'Ship the checkout fix',
  ai_analysis_id: 'ai-1',
  ai_risk_level: 'MEDIUM',
  ai_risk_score: 45,
  ai_summary: 'Routine service deployment.',
  required_approvals: 2,
  scheduled_for: null,
  outcome: null,
  outcome_reported_at: null,
  outcome_detail: null,
  created_at: '2026-05-01T10:00:00Z',
  decisions: [
    {
      id: 'dec-1',
      reviewer_id: 'u-reviewer',
      decision: 'APPROVED',
      comment: 'Looks safe',
      stage: 1,
      decided_at: '2026-05-01T11:00:00Z',
    },
  ],
};

const releasableGate: DeploymentGateStatus = {
  request_id: 'req-1',
  status: 'APPROVED',
  releasable: true,
  approvals: { required: 2, granted: 2 },
  decisions: [],
  frozen: false,
  freeze_reason: null,
  scheduled_for: null,
  ai_risk_level: 'MEDIUM',
};

function seedUser(id: string) {
  useAuthStore.setState({
    user: {
      id,
      email: 'me@example.com',
      display_name: 'Me',
      role: 'DEVELOPER',
      role_id: null,
      permissions: [],
      auth_provider: 'LOCAL',
      totp_enabled: false,
      platform_admin: false,
      preferred_language: 'en',
    },
    accessToken: 'token',
  });
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/deployments/req-1']}>
        <AntdApp>
          <Routes>
            <Route path="/deployments/:id" element={node} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('DeploymentDetailPage', () => {
  beforeEach(() => {
    getDeploymentRequestMock.mockReset();
    getDeploymentGateMock.mockReset();
    cancelDeploymentRequestMock.mockReset();
    seedUser('u-me');
  });

  it('renders the request metadata and the decisions table', async () => {
    getDeploymentRequestMock.mockResolvedValue(baseRequest);
    getDeploymentGateMock.mockResolvedValue(releasableGate);

    render(wrap(<DeploymentDetailPage />));

    // Pipeline name appears both as the page title and in the descriptions card.
    expect((await screen.findAllByText('Checkout Service')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('2.4.1').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/production/).length).toBeGreaterThan(0);
    expect(screen.getByText('dev@example.com')).toBeInTheDocument();
    // Decisions table row.
    expect(screen.getByText('u-reviewer')).toBeInTheDocument();
    expect(screen.getByText('Looks safe')).toBeInTheDocument();
    // Rendered in both the approvals card and the timeline stage.
    expect(screen.getAllByText('1 of 2 approvals').length).toBeGreaterThan(0);
  });

  it('shows the releasable banner for an APPROVED request with an open gate', async () => {
    getDeploymentRequestMock.mockResolvedValue(baseRequest);
    getDeploymentGateMock.mockResolvedValue(releasableGate);

    render(wrap(<DeploymentDetailPage />));

    expect(
      await screen.findByText(/Approved and releasable/),
    ).toBeInTheDocument();
    expect(getDeploymentGateMock).toHaveBeenCalledWith('req-1');
  });

  it('does not ask the gate while the request is still PENDING_REVIEW', async () => {
    getDeploymentRequestMock.mockResolvedValue({
      ...baseRequest,
      status: 'PENDING_REVIEW',
    });

    render(wrap(<DeploymentDetailPage />));

    await screen.findByText('dev@example.com');
    expect(getDeploymentGateMock).not.toHaveBeenCalled();
    expect(screen.queryByText(/Approved and releasable/)).toBeNull();
    expect(screen.queryByText(/Held by a freeze window/)).toBeNull();
  });

  it('shows the frozen banner with the freeze reason', async () => {
    getDeploymentRequestMock.mockResolvedValue(baseRequest);
    getDeploymentGateMock.mockResolvedValue({
      ...releasableGate,
      releasable: false,
      frozen: true,
      freeze_reason: 'Prod change freeze',
    });

    render(wrap(<DeploymentDetailPage />));

    expect(
      await screen.findByText('Held by a freeze window: Prod change freeze'),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Approved and releasable/)).toBeNull();
  });

  it("hides the cancel button on someone else's request", async () => {
    // Cancellable by state (deferred run still ahead) — only ownership withholds the button.
    getDeploymentRequestMock.mockResolvedValue({
      ...baseRequest,
      submitted_by: 'u-someone-else',
      scheduled_for: new Date(Date.now() + 3_600_000).toISOString(),
    });
    getDeploymentGateMock.mockResolvedValue(releasableGate);

    render(wrap(<DeploymentDetailPage />));

    await screen.findByText('dev@example.com');
    expect(screen.queryByRole('button', { name: 'Cancel deployment' })).toBeNull();
  });

  it('hides cancel on an APPROVED request with no pending deferred run', async () => {
    // The backend only allows cancelling APPROVED while scheduled_for is still in the future.
    getDeploymentRequestMock.mockResolvedValue({ ...baseRequest, scheduled_for: null });
    getDeploymentGateMock.mockResolvedValue(releasableGate);

    render(wrap(<DeploymentDetailPage />));

    await screen.findByText('dev@example.com');
    expect(screen.queryByRole('button', { name: 'Cancel deployment' })).toBeNull();
  });

  it('cancels the request through the Popconfirm when the submitter clicks cancel', async () => {
    // APPROVED is cancellable only while its deferred run is still ahead.
    getDeploymentRequestMock.mockResolvedValue({
      ...baseRequest,
      scheduled_for: new Date(Date.now() + 3_600_000).toISOString(),
    });
    getDeploymentGateMock.mockResolvedValue(releasableGate);
    cancelDeploymentRequestMock.mockResolvedValue(undefined);

    render(wrap(<DeploymentDetailPage />));

    const trigger = await screen.findByRole('button', { name: 'Cancel deployment' });
    await act(async () => {
      fireEvent.click(trigger);
    });

    expect(await screen.findByText('Cancel this deployment?')).toBeInTheDocument();

    // The Popconfirm ok button carries the same text as the trigger — the
    // portal-rendered confirm is the last one in the tree.
    const buttons = screen.getAllByRole('button', { name: 'Cancel deployment' });
    const confirm = buttons[buttons.length - 1]!;
    expect(confirm).not.toBe(trigger);
    await act(async () => {
      fireEvent.click(confirm);
    });

    await waitFor(() => {
      expect(cancelDeploymentRequestMock).toHaveBeenCalledWith('req-1');
    });
  });
});
