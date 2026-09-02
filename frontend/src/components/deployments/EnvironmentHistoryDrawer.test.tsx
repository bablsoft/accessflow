import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { Permission } from '@/utils/permissions';
import type { DeploymentVersionHistoryEntry } from '@/types/api';

const { listDeploymentEnvironmentHistoryMock } = vi.hoisted(() => ({
  listDeploymentEnvironmentHistoryMock: vi.fn(),
}));

vi.mock('@/api/deploymentVersions', () => ({
  listDeploymentEnvironmentHistory: listDeploymentEnvironmentHistoryMock,
  deploymentVersionKeys: {
    history: (pipelineId: string, environmentId: string, filters: unknown) =>
      ['deployment-versions', 'history', pipelineId, environmentId, filters] as const,
  },
}));

const { EnvironmentHistoryDrawer } = await import('./EnvironmentHistoryDrawer');

const environment = { id: 'env-1', name: 'production', tags: ['prod'], sort_order: 10 };

function entry(
  overrides: Partial<DeploymentVersionHistoryEntry> = {},
): DeploymentVersionHistoryEntry {
  return {
    request_id: 'req-1',
    version: '2.4.1',
    status: 'EXECUTED',
    outcome: 'SUCCEEDED',
    outcome_reported_at: '2026-05-01T10:20:00Z',
    submitted_by: 'u-other',
    submission_reason: 'USER_SUBMITTED',
    commit_sha: 'abc1234def567',
    run_url: 'https://ci.example.com/runs/42',
    created_at: '2026-05-01T09:50:00Z',
    executed_at: '2026-05-01T10:00:00Z',
    ...overrides,
  };
}

function seedUser(id: string, permissions: Permission[]) {
  useAuthStore.setState({
    user: {
      id,
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

describe('EnvironmentHistoryDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    seedUser('u-me', ['DEPLOYMENT_REVIEW']);
    listDeploymentEnvironmentHistoryMock.mockResolvedValue({
      content: [entry(), entry({ request_id: 'req-0', version: '2.3.9', outcome: null })],
      page: 0,
      size: 20,
      total_elements: 2,
      total_pages: 1,
    });
  });

  it('never queries while closed', () => {
    render(
      wrap(<EnvironmentHistoryDrawer pipelineId="pipe-1" environment={null} onClose={() => {}} />),
    );
    expect(listDeploymentEnvironmentHistoryMock).not.toHaveBeenCalled();
  });

  it('lists the timeline in server order with version, status and outcome', async () => {
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    expect(screen.getByText('2.3.9')).toBeInTheDocument();
    expect(screen.getAllByText('Executed').length).toBeGreaterThan(0);
    expect(screen.getByText('succeeded')).toBeInTheDocument();
    expect(listDeploymentEnvironmentHistoryMock).toHaveBeenCalledWith('pipe-1', 'env-1', {
      page: 0,
      size: 20,
    });
  });

  it('titles the drawer with the environment name', async () => {
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    expect(await screen.findByText('Deployment history — production')).toBeInTheDocument();
  });

  it('shortens the commit sha and opens the CI run in a new tab', async () => {
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    expect(screen.getAllByText('Commit: abc1234').length).toBe(2);
    const runLink = screen.getAllByRole('link', { name: 'CI run' })[0]!;
    expect(runLink).toHaveAttribute('target', '_blank');
    expect(runLink).toHaveAttribute('rel', 'noreferrer');
  });

  it('labels the timestamp as submitted when the request never executed', async () => {
    listDeploymentEnvironmentHistoryMock.mockResolvedValue({
      content: [entry({ status: 'REJECTED', outcome: null, executed_at: null })],
      page: 0,
      size: 20,
      total_elements: 1,
      total_pages: 1,
    });
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    expect(screen.getByText(/^Submitted:/)).toBeInTheDocument();
  });

  it('links the drill-down for a deployment reviewer', async () => {
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    expect(screen.getAllByRole('link', { name: 'Open deployment' })[0]).toHaveAttribute(
      'href',
      '/deployments/req-1',
    );
  });

  it('does not link the drill-down for a pipeline admin who did not submit it', async () => {
    seedUser('u-me', ['DEPLOYMENT_PIPELINE_MANAGE']);
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    expect(screen.queryByRole('link', { name: 'Open deployment' })).not.toBeInTheDocument();
    expect(screen.getAllByText('Open deployment').length).toBe(2);
  });

  it('links the drill-down for the submitter even without review permissions', async () => {
    seedUser('u-other', ['DEPLOYMENT_PIPELINE_MANAGE']);
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    expect(screen.getAllByRole('link', { name: 'Open deployment' }).length).toBe(2);
  });

  it('shows the empty state when the environment has no deployments', async () => {
    listDeploymentEnvironmentHistoryMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      total_elements: 0,
      total_pages: 0,
    });
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    expect(
      await screen.findByText('No deployments to this environment yet'),
    ).toBeInTheDocument();
  });

  it('hides pagination when the history fits on one page', async () => {
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    expect(document.querySelector('.ant-pagination')).toBeNull();
  });

  it('pages through a longer history', async () => {
    listDeploymentEnvironmentHistoryMock.mockResolvedValue({
      content: [entry()],
      page: 0,
      size: 20,
      total_elements: 25,
      total_pages: 2,
    });
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    const next = await waitFor(() => {
      const button = document.querySelector('.ant-pagination-next button');
      expect(button).not.toBeNull();
      return button!;
    });
    fireEvent.click(next);
    await waitFor(() =>
      expect(listDeploymentEnvironmentHistoryMock).toHaveBeenCalledWith('pipe-1', 'env-1', {
        page: 1,
        size: 20,
      }),
    );
  });

  it('resets the page cursor when the drawer switches environment', async () => {
    listDeploymentEnvironmentHistoryMock.mockResolvedValue({
      content: [entry()],
      page: 0,
      size: 20,
      total_elements: 25,
      total_pages: 2,
    });
    const { rerender } = render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    await screen.findByText('2.4.1');
    const next = await waitFor(() => {
      const button = document.querySelector('.ant-pagination-next button');
      expect(button).not.toBeNull();
      return button!;
    });
    fireEvent.click(next);
    await waitFor(() =>
      expect(listDeploymentEnvironmentHistoryMock).toHaveBeenCalledWith('pipe-1', 'env-1', {
        page: 1,
        size: 20,
      }),
    );

    rerender(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={{ ...environment, id: 'env-2', name: 'staging' }}
          onClose={() => {}}
        />,
      ),
    );
    await waitFor(() =>
      expect(listDeploymentEnvironmentHistoryMock).toHaveBeenCalledWith('pipe-1', 'env-2', {
        page: 0,
        size: 20,
      }),
    );
  });

  it('surfaces a failed history read instead of claiming there are no deployments', async () => {
    listDeploymentEnvironmentHistoryMock.mockRejectedValue(new Error('boom'));
    render(
      wrap(
        <EnvironmentHistoryDrawer
          pipelineId="pipe-1"
          environment={environment}
          onClose={() => {}}
        />,
      ),
    );
    expect(await screen.findByText('Deployment governance request failed')).toBeInTheDocument();
    expect(
      screen.queryByText('No deployments to this environment yet'),
    ).not.toBeInTheDocument();
  });
});
