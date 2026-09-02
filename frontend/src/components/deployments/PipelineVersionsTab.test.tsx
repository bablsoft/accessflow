import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { DeploymentEnvironmentVersion, DeploymentVersionDrift } from '@/types/api';

const { listPipelineEnvironmentVersionsMock, listDeploymentEnvironmentHistoryMock } = vi.hoisted(
  () => ({
    listPipelineEnvironmentVersionsMock: vi.fn(),
    listDeploymentEnvironmentHistoryMock: vi.fn(),
  }),
);

vi.mock('@/api/deploymentVersions', () => ({
  listPipelineEnvironmentVersions: listPipelineEnvironmentVersionsMock,
  listDeploymentEnvironmentHistory: listDeploymentEnvironmentHistoryMock,
  deploymentVersionKeys: {
    matrix: (pipelineId: string) => ['deployment-versions', 'matrix', pipelineId] as const,
    history: (pipelineId: string, environmentId: string, filters: unknown) =>
      ['deployment-versions', 'history', pipelineId, environmentId, filters] as const,
  },
}));

const { PipelineVersionsTab } = await import('./PipelineVersionsTab');

function row(
  overrides: Partial<DeploymentEnvironmentVersion> = {},
  drift: Partial<DeploymentVersionDrift> = {},
): DeploymentEnvironmentVersion {
  return {
    pipeline_id: 'pipe-1',
    pipeline_name: 'Checkout Service',
    environment: { id: 'env-1', name: 'production', tags: ['prod', 'acme'], sort_order: 10 },
    current_version: '2.4.0',
    current_request_id: 'req-1',
    deployed_at: '2026-05-01T10:00:00Z',
    previous_version: '2.3.9',
    last_outcome: 'SUCCEEDED',
    ...overrides,
    drift: {
      latest_version: '2.4.1',
      latest_deployed_at: '2026-05-05T10:00:00Z',
      drifted: true,
      days_behind: 4,
      deployments_behind: 2,
      ...drift,
    },
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

describe('PipelineVersionsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({
      user: {
        id: 'u-me',
        email: 'me@example.com',
        display_name: 'Me',
        role: 'ADMIN',
        role_id: null,
        permissions: ['DEPLOYMENT_PIPELINE_MANAGE'],
        auth_provider: 'LOCAL',
        totp_enabled: false,
        platform_admin: false,
        preferred_language: 'en',
      },
      accessToken: 'token',
    });
    listPipelineEnvironmentVersionsMock.mockResolvedValue([
      row(),
      row(
        {
          environment: { id: 'env-2', name: 'staging', tags: ['eu-west'], sort_order: 0 },
          current_version: '2.4.1',
          current_request_id: 'req-2',
        },
        { drifted: false, days_behind: 0, deployments_behind: 0 },
      ),
    ]);
    listDeploymentEnvironmentHistoryMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      total_elements: 0,
      total_pages: 0,
    });
  });

  it('renders every environment in server order with its versions and tags', async () => {
    render(wrap(<PipelineVersionsTab pipelineId="pipe-1" />));

    await screen.findByText('production');
    expect(screen.getByText('staging')).toBeInTheDocument();
    expect(screen.getByText('2.4.0')).toBeInTheDocument();
    expect(screen.getByText('acme')).toBeInTheDocument();
    // Server order is authoritative — production (sort_order 10) came back first.
    const names = screen.getAllByRole('row').map((r) => r.textContent ?? '');
    expect(names[1]).toContain('production');
  });

  it('badges a drifted environment and marks an up-to-date one', async () => {
    render(wrap(<PipelineVersionsTab pipelineId="pipe-1" />));

    expect(await screen.findByText('2 versions / 4 days behind')).toBeInTheDocument();
    expect(screen.getByText('Up to date')).toBeInTheDocument();
  });

  it('shows never-deployed environments rather than hiding them', async () => {
    listPipelineEnvironmentVersionsMock.mockResolvedValue([
      row(
        {
          environment: { id: 'env-3', name: 'qa', tags: [], sort_order: 0 },
          current_version: null,
          current_request_id: null,
          deployed_at: null,
          previous_version: null,
          last_outcome: null,
        },
        { days_behind: null, deployments_behind: null },
      ),
    ]);
    render(wrap(<PipelineVersionsTab pipelineId="pipe-1" />));

    await screen.findByText('qa');
    expect(screen.getByText('Never deployed')).toBeInTheDocument();
  });

  it('surfaces the version a rollback reverted to', async () => {
    listPipelineEnvironmentVersionsMock.mockResolvedValue([
      row({ last_outcome: 'ROLLED_BACK', current_version: '2.3.9' }),
    ]);
    render(wrap(<PipelineVersionsTab pipelineId="pipe-1" />));

    await screen.findByText('production');
    expect(screen.getByText('reverted to 2.3.9')).toBeInTheDocument();
  });

  it('admits the current version is unknown after consecutive rollbacks', async () => {
    listPipelineEnvironmentVersionsMock.mockResolvedValue([
      row({ last_outcome: 'ROLLED_BACK', current_version: null }),
    ]);
    render(wrap(<PipelineVersionsTab pipelineId="pipe-1" />));

    await screen.findByText('production');
    expect(screen.getByText('unknown — see history')).toBeInTheDocument();
  });

  it('opens the history drawer for the clicked environment', async () => {
    render(wrap(<PipelineVersionsTab pipelineId="pipe-1" />));

    await screen.findByText('production');
    fireEvent.click(screen.getAllByRole('button', { name: 'History' })[0]!);

    expect(await screen.findByText('Deployment history — production')).toBeInTheDocument();
    expect(listDeploymentEnvironmentHistoryMock).toHaveBeenCalledWith('pipe-1', 'env-1', {
      page: 0,
      size: 20,
    });
  });

  it('points at the Environments tab when the pipeline has none', async () => {
    listPipelineEnvironmentVersionsMock.mockResolvedValue([]);
    render(wrap(<PipelineVersionsTab pipelineId="pipe-1" />));

    expect(
      await screen.findByText('No environments yet — add one on the Environments tab'),
    ).toBeInTheDocument();
  });

  it('surfaces a failed matrix read instead of claiming the pipeline has no environments', async () => {
    listPipelineEnvironmentVersionsMock.mockRejectedValue(new Error('boom'));
    render(wrap(<PipelineVersionsTab pipelineId="pipe-1" />));

    expect(await screen.findByText('Deployment governance request failed')).toBeInTheDocument();
    expect(
      screen.queryByText('No environments yet — add one on the Environments tab'),
    ).not.toBeInTheDocument();
  });
});
