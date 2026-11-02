import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, type AxiosResponse } from 'axios';
import '@/i18n';

const { listPipelineEnvironmentVersionsMock, navigateMock } = vi.hoisted(() => ({
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
    matrix: (id: string) => ['deployment-versions', 'matrix', id] as const,
  },
}));

vi.mock('@/components/deployments/DeploymentMetricsPanel', () => ({
  DeploymentMetricsPanel: ({ pipelineId }: { pipelineId?: string }) => (
    <div>panel {pipelineId}</div>
  ),
}));

const { default: PipelineMetricsPage } = await import('./PipelineMetricsPage');

function problem(status: number, data: Record<string, unknown>): AxiosError {
  const response = { data, status, statusText: '', headers: {}, config: {} as never } as AxiosResponse;
  return new AxiosError('failed', undefined, undefined, undefined, response);
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/deployment-metrics/:pipelineId" element={<PipelineMetricsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PipelineMetricsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('titles the page with the pipeline name and mounts the fixed-pipeline panel', async () => {
    listPipelineEnvironmentVersionsMock.mockResolvedValue([
      { pipeline_name: 'Checkout Service', environment: { id: 'e', name: 'prod' } },
    ]);
    renderAt('/deployment-metrics/pipe-1');

    expect(await screen.findByText('panel pipe-1')).toBeInTheDocument();
    expect(screen.getByText('Metrics — Checkout Service')).toBeInTheDocument();
    expect(listPipelineEnvironmentVersionsMock).toHaveBeenCalledWith('pipe-1');
  });

  it('falls back to the generic title for a pipeline with no deployed environment', async () => {
    listPipelineEnvironmentVersionsMock.mockResolvedValue([]);
    renderAt('/deployment-metrics/pipe-1');
    expect(await screen.findByText('panel pipe-1')).toBeInTheDocument();
    expect(screen.getByText('Deployment metrics')).toBeInTheDocument();
  });

  it('renders the not-found state for a 404', async () => {
    listPipelineEnvironmentVersionsMock.mockRejectedValue(problem(404, {}));
    renderAt('/deployment-metrics/pipe-1');
    expect(await screen.findByText('Pipeline not found')).toBeInTheDocument();
    expect(screen.queryByText(/^panel/)).not.toBeInTheDocument();
  });

  it('renders the server detail for any other failure', async () => {
    listPipelineEnvironmentVersionsMock.mockRejectedValue(problem(500, { detail: 'db down' }));
    renderAt('/deployment-metrics/pipe-1');
    expect(await screen.findByText('db down')).toBeInTheDocument();
  });

  it('navigates back to the deployments list', async () => {
    listPipelineEnvironmentVersionsMock.mockResolvedValue([]);
    renderAt('/deployment-metrics/pipe-1');
    await screen.findByText('panel pipe-1');
    fireEvent.click(screen.getByText('Back'));
    expect(navigateMock).toHaveBeenCalledWith('/deployments');
  });
});
