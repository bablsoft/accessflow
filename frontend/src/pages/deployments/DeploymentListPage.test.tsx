import { describe, expect, it, vi, beforeEach } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type {
  DeploymentEnvironmentVersion,
  DeploymentRequest,
  DeploymentRequestPage,
} from '@/types/api';

const {
  listDeploymentRequestsMock,
  listDeploymentPipelinesMock,
  listPipelineEnvironmentVersionsMock,
  navigateMock,
} = vi.hoisted(() => ({
  listDeploymentRequestsMock: vi.fn(),
  listDeploymentPipelinesMock: vi.fn(),
  listPipelineEnvironmentVersionsMock: vi.fn(),
  navigateMock: vi.fn(),
}));

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => navigateMock };
});

vi.mock('@/api/deploymentVersions', () => ({
  listPipelineEnvironmentVersions: listPipelineEnvironmentVersionsMock,
  deploymentVersionKeys: {
    matrix: (pipelineId: string) => ['deployment-versions', 'matrix', pipelineId] as const,
  },
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

const liveVersion: DeploymentEnvironmentVersion = {
  pipeline_id: 'pipe-1',
  pipeline_name: 'Checkout Service',
  environment: { id: 'env-1', name: 'production', tags: ['prod'], sort_order: 10 },
  current_version: '2.4.1',
  current_request_id: 'req-1',
  deployed_at: '2026-05-01T10:00:00Z',
  previous_version: '2.3.9',
  last_outcome: 'SUCCEEDED',
  drift: {
    latest_version: '2.5.0',
    latest_deployed_at: '2026-05-05T10:00:00Z',
    drifted: true,
    days_behind: 4,
    deployments_behind: 2,
  },
};

describe('DeploymentListPage', () => {
  beforeEach(() => {
    listDeploymentRequestsMock.mockReset();
    listDeploymentPipelinesMock.mockReset();
    listPipelineEnvironmentVersionsMock.mockReset();
    listPipelineEnvironmentVersionsMock.mockResolvedValue([]);
    navigateMock.mockReset();
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

  it('badges the request that is live on its environment with the environment drift', async () => {
    listDeploymentRequestsMock.mockResolvedValue(pageOf([baseRequest]));
    listPipelineEnvironmentVersionsMock.mockResolvedValue([liveVersion]);

    render(wrap(<DeploymentListPage />));

    expect(await screen.findByText('2 versions / 4 days behind')).toBeInTheDocument();
    expect(listPipelineEnvironmentVersionsMock).toHaveBeenCalledWith('pipe-1');
  });

  it('leaves a superseded request unbadged — drift describes the environment, not the request', async () => {
    listDeploymentRequestsMock.mockResolvedValue(pageOf([baseRequest]));
    listPipelineEnvironmentVersionsMock.mockResolvedValue([
      { ...liveVersion, current_request_id: 'req-newer' },
    ]);

    render(wrap(<DeploymentListPage />));

    await screen.findByText('2.4.1');
    expect(screen.queryByText('2 versions / 4 days behind')).not.toBeInTheDocument();
  });

  it('leaves rows unbadged when the caller cannot read that pipeline matrix', async () => {
    listDeploymentRequestsMock.mockResolvedValue(pageOf([baseRequest]));
    listPipelineEnvironmentVersionsMock.mockRejectedValue(new Error('404'));

    render(wrap(<DeploymentListPage />));

    await screen.findByText('2.4.1');
    await waitFor(() => expect(listPipelineEnvironmentVersionsMock).toHaveBeenCalled());
    expect(screen.queryByText(/behind/)).not.toBeInTheDocument();
  });

  it('offers the per-pipeline version matrix once a pipeline is selected', async () => {
    listDeploymentRequestsMock.mockResolvedValue(pageOf([baseRequest]));

    render(wrap(<DeploymentListPage />));
    await screen.findByText('2.4.1');

    const button = screen.getByRole('button', { name: /version matrix/i });
    expect(button).toBeDisabled();

    fireEvent.mouseDown(screen.getByLabelText('Pipeline'));
    fireEvent.click(await screen.findByTitle('Checkout Service'));

    await waitFor(() => expect(button).toBeEnabled());
    fireEvent.click(button);
    expect(navigateMock).toHaveBeenCalledWith('/deployment-versions/pipe-1');
  });
});
