import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { DeploymentEnvironmentVersion } from '@/types/api';

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

const PipelineVersionsPage = (await import('./PipelineVersionsPage')).default;

const matrixRow: DeploymentEnvironmentVersion = {
  pipeline_id: 'pipe-1',
  pipeline_name: 'Checkout Service',
  environment: { id: 'env-1', name: 'production', tags: ['prod'], sort_order: 10 },
  current_version: '2.4.0',
  current_request_id: 'req-1',
  deployed_at: '2026-05-01T10:00:00Z',
  previous_version: '2.3.9',
  last_outcome: 'SUCCEEDED',
  drift: {
    latest_version: '2.4.1',
    latest_deployed_at: '2026-05-05T10:00:00Z',
    drifted: true,
    days_behind: 4,
    deployments_behind: 2,
  },
};

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/deployment-versions/pipe-1']}>
        <AntdApp>
          <Routes>
            <Route path="/deployment-versions/:pipelineId" element={node} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('PipelineVersionsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useAuthStore.setState({
      user: {
        id: 'u-me',
        email: 'me@example.com',
        display_name: 'Me',
        // A can_trigger-only user: no functional deploygov permission at all.
        role: 'DEVELOPER',
        role_id: null,
        permissions: ['QUERY_SUBMIT_SELECT'],
        auth_provider: 'LOCAL',
        totp_enabled: false,
        platform_admin: false,
        preferred_language: 'en',
      },
      accessToken: 'token',
    });
    listPipelineEnvironmentVersionsMock.mockResolvedValue([matrixRow]);
    listDeploymentEnvironmentHistoryMock.mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      total_elements: 0,
      total_pages: 0,
    });
  });

  it('titles the page with the pipeline name from the matrix and renders it', async () => {
    render(wrap(<PipelineVersionsPage />));

    expect(await screen.findByText('Checkout Service — version matrix')).toBeInTheDocument();
    expect(screen.getByText('production')).toBeInTheDocument();
    expect(screen.getByText('2 versions / 4 days behind')).toBeInTheDocument();
    expect(listPipelineEnvironmentVersionsMock).toHaveBeenCalledWith('pipe-1');
  });

  it('falls back to the generic title when the pipeline has no environments yet', async () => {
    listPipelineEnvironmentVersionsMock.mockResolvedValue([]);
    render(wrap(<PipelineVersionsPage />));

    expect(await screen.findByText('Version matrix')).toBeInTheDocument();
  });

  it('renders a not-found state when the server hides the pipeline behind a 404', async () => {
    listPipelineEnvironmentVersionsMock.mockRejectedValue(
      Object.assign(new Error('404'), { isAxiosError: true, response: { status: 404 } }),
    );
    render(wrap(<PipelineVersionsPage />));

    expect(await screen.findByText('Pipeline not found')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('does not dress a server failure up as a missing pipeline', async () => {
    listPipelineEnvironmentVersionsMock.mockRejectedValue(
      Object.assign(new Error('boom'), { isAxiosError: true, response: { status: 500 } }),
    );
    render(wrap(<PipelineVersionsPage />));

    expect(await screen.findByText('Deployment governance request failed')).toBeInTheDocument();
    expect(screen.queryByText('Pipeline not found')).not.toBeInTheDocument();
  });
});
