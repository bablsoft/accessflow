import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { DeploymentRequest, DeploymentRequestPage } from '@/types/api';

const { listDeploymentRequestsMock, listDeploymentPipelinesMock } = vi.hoisted(() => ({
  listDeploymentRequestsMock: vi.fn(),
  listDeploymentPipelinesMock: vi.fn(),
}));

vi.mock('@/api/deploymentRequests', () => ({
  listDeploymentRequests: listDeploymentRequestsMock,
  deploymentKeys: {
    all: ['deployments'] as const,
    lists: () => ['deployments', 'list'] as const,
    list: (filters: unknown) => ['deployments', 'list', filters] as const,
    details: () => ['deployments', 'detail'] as const,
    detail: (id: string) => ['deployments', 'detail', id] as const,
    gate: (id: string) => ['deployments', 'detail', id, 'gate'] as const,
  },
}));

vi.mock('@/api/deploymentPipelines', () => ({
  listDeploymentPipelines: listDeploymentPipelinesMock,
  deploymentPipelineKeys: {
    all: ['deployment-pipelines'] as const,
    lists: () => ['deployment-pipelines', 'list'] as const,
    list: (filters: unknown) => ['deployment-pipelines', 'list', filters] as const,
    details: () => ['deployment-pipelines', 'detail'] as const,
    detail: (id: string) => ['deployment-pipelines', 'detail', id] as const,
  },
}));

const DeploymentListPage = (await import('./DeploymentListPage')).default;

const baseRequest: DeploymentRequest = {
  id: 'req-1',
  pipeline_id: 'pipe-1',
  pipeline_name: 'Checkout Service',
  provider: 'GITHUB_ACTIONS',
  environment_id: 'env-1',
  environment_name: 'production',
  submitted_by: 'u-other',
  submitted_by_email: 'dev@example.com',
  version: '2.4.1',
  commit_sha: 'abc123def456',
  artifact_ref: null,
  run_url: null,
  external_run_id: 'run-42',
  metadata: {},
  status: 'PENDING_REVIEW',
  submission_reason: 'USER_SUBMITTED',
  justification: null,
  ai_analysis_id: null,
  ai_risk_level: 'MEDIUM',
  ai_risk_score: 45,
  ai_summary: null,
  required_approvals: 2,
  scheduled_for: null,
  outcome: null,
  outcome_reported_at: null,
  outcome_detail: null,
  created_at: '2026-05-01T10:00:00Z',
  decisions: [],
};

function pageOf(content: DeploymentRequest[]): DeploymentRequestPage {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: 1,
  };
}

function seedUser(permissions: string[]) {
  useAuthStore.setState({
    user: {
      id: 'u-me',
      email: 'me@example.com',
      display_name: 'Me',
      role: 'DEVELOPER',
      role_id: null,
      permissions,
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
      <MemoryRouter>
        <AntdApp>{node}</AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('DeploymentListPage', () => {
  beforeEach(() => {
    listDeploymentRequestsMock.mockReset();
    listDeploymentPipelinesMock.mockReset();
    seedUser([]);
  });

  it('renders deployment request rows from the list endpoint', async () => {
    listDeploymentRequestsMock.mockResolvedValue(pageOf([baseRequest]));

    render(wrap(<DeploymentListPage />));

    expect(await screen.findByText('Checkout Service')).toBeInTheDocument();
    expect(screen.getByText('2.4.1')).toBeInTheDocument();
    expect(screen.getByText('production')).toBeInTheDocument();
    expect(screen.getByText('1 deployments')).toBeInTheDocument();
  });

  it('does not ask for pipelines without DEPLOYMENT_PIPELINE_MANAGE and still fills the filter from rows', async () => {
    listDeploymentRequestsMock.mockResolvedValue(pageOf([baseRequest]));

    render(wrap(<DeploymentListPage />));

    await screen.findByText('2.4.1');
    expect(listDeploymentPipelinesMock).not.toHaveBeenCalled();

    // Open the pipeline filter — the option list is built from the loaded rows.
    const pipelineSelect = screen.getByRole('combobox', { name: 'Pipeline' });
    await act(async () => {
      fireEvent.mouseDown(pipelineSelect);
    });

    expect(
      await screen.findByRole('option', { name: 'Checkout Service' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'All pipelines' })).toBeInTheDocument();
    expect(listDeploymentPipelinesMock).not.toHaveBeenCalled();
  });

  it('lists pipelines when the user holds DEPLOYMENT_PIPELINE_MANAGE', async () => {
    seedUser(['DEPLOYMENT_PIPELINE_MANAGE']);
    listDeploymentRequestsMock.mockResolvedValue(pageOf([baseRequest]));
    listDeploymentPipelinesMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 100,
      total_elements: 0,
      total_pages: 0,
    });

    render(wrap(<DeploymentListPage />));

    await waitFor(() => {
      expect(listDeploymentPipelinesMock).toHaveBeenCalledWith({ size: 100 });
    });
  });

  it('shows the empty state when there are no deployment requests', async () => {
    listDeploymentRequestsMock.mockResolvedValue(pageOf([]));

    render(wrap(<DeploymentListPage />));

    expect(await screen.findByText('No deployment requests yet')).toBeInTheDocument();
    expect(screen.getByText('0 deployments')).toBeInTheDocument();
  });
});
