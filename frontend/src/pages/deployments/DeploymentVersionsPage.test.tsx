import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { Permission } from '@/utils/permissions';
import type { DeploymentEnvironmentVersion, DeploymentVersionDrift } from '@/types/api';

const { listDeploymentEnvironmentVersionsMock, listDeploymentPipelinesMock } = vi.hoisted(() => ({
  listDeploymentEnvironmentVersionsMock: vi.fn(),
  listDeploymentPipelinesMock: vi.fn(),
}));

vi.mock('@/api/deploymentVersions', () => ({
  listDeploymentEnvironmentVersions: listDeploymentEnvironmentVersionsMock,
  deploymentVersionKeys: {
    list: (filters: unknown) => ['deployment-versions', 'list', filters] as const,
  },
}));

vi.mock('@/api/deploymentPipelines', () => ({
  listDeploymentPipelines: listDeploymentPipelinesMock,
  deploymentPipelineKeys: {
    list: (filters: unknown) => ['deployment-pipelines', 'list', filters] as const,
  },
}));

const DeploymentVersionsPage = (await import('./DeploymentVersionsPage')).default;

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

const page = (content: DeploymentEnvironmentVersion[]) => ({
  content,
  page: 0,
  size: 20,
  total_elements: content.length,
  total_pages: 1,
});

function seedUser(permissions: Permission[]) {
  useAuthStore.setState({
    user: {
      id: 'u-me',
      email: 'me@example.com',
      display_name: 'Me',
      role: 'ADMIN',
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

/** AntD selects are not real <select> elements — open by aria-label, then click the option. */
async function chooseOption(ariaLabel: string, optionText: string) {
  fireEvent.mouseDown(screen.getByLabelText(ariaLabel));
  const option = await screen.findByTitle(optionText);
  fireEvent.click(option);
}

describe('DeploymentVersionsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    seedUser(['DEPLOYMENT_PIPELINE_MANAGE']);
    listDeploymentEnvironmentVersionsMock.mockResolvedValue(
      page([
        row(),
        row(
          {
            environment: { id: 'env-2', name: 'staging', tags: ['eu-west'], sort_order: 0 },
            current_version: '2.4.1',
            current_request_id: 'req-2',
          },
          { drifted: false, days_behind: 0, deployments_behind: 0 },
        ),
      ]),
    );
    listDeploymentPipelinesMock.mockResolvedValue({
      content: [{ id: 'pipe-1', name: 'Checkout Service' }],
      page: 0,
      size: 100,
      total_elements: 1,
      total_pages: 1,
    });
  });

  it('renders a row per environment with its pipeline, version and tags', async () => {
    render(wrap(<DeploymentVersionsPage />));

    await screen.findByText('production');
    expect(screen.getByText('staging')).toBeInTheDocument();
    expect(screen.getByText('2.4.0')).toBeInTheDocument();
    expect(screen.getByText('acme')).toBeInTheDocument();
    expect(screen.getByText('2 environments')).toBeInTheDocument();
  });

  it('links each pipeline to its own version matrix', async () => {
    render(wrap(<DeploymentVersionsPage />));

    const links = await screen.findAllByRole('link', { name: 'Checkout Service' });
    expect(links[0]).toHaveAttribute('href', '/deployment-versions/pipe-1');
  });

  it('shows the drift badge on the behind row and "up to date" on the current one', async () => {
    render(wrap(<DeploymentVersionsPage />));

    expect(await screen.findByText('2 versions / 4 days behind')).toBeInTheDocument();
    expect(screen.getByText('Up to date')).toBeInTheDocument();
  });

  it('shows the rollback badge and its unknown variant', async () => {
    listDeploymentEnvironmentVersionsMock.mockResolvedValue(
      page([
        row({ last_outcome: 'ROLLED_BACK', current_version: '2.3.9' }),
        row({
          environment: { id: 'env-2', name: 'staging', tags: [], sort_order: 0 },
          last_outcome: 'ROLLED_BACK',
          current_version: null,
        }),
      ]),
    );
    render(wrap(<DeploymentVersionsPage />));

    await screen.findByText('production');
    expect(screen.getByText('reverted to 2.3.9')).toBeInTheDocument();
    expect(screen.getByText('unknown — see history')).toBeInTheDocument();
  });

  it('filters by pipeline and resets to the first page', async () => {
    render(wrap(<DeploymentVersionsPage />));
    await screen.findByText('production');

    await chooseOption('Pipeline', 'Checkout Service');

    await waitFor(() =>
      expect(listDeploymentEnvironmentVersionsMock).toHaveBeenCalledWith(
        expect.objectContaining({ pipeline_id: 'pipe-1', page: 0 }),
      ),
    );
  });

  it('offers the union of the loaded rows\' tags and filters by one', async () => {
    render(wrap(<DeploymentVersionsPage />));
    await screen.findByText('production');

    fireEvent.mouseDown(screen.getByLabelText('Tag'));
    for (const tag of ['acme', 'eu-west', 'prod']) {
      expect(await screen.findByTitle(tag)).toBeInTheDocument();
    }
    fireEvent.click(screen.getByTitle('prod'));

    await waitFor(() =>
      expect(listDeploymentEnvironmentVersionsMock).toHaveBeenCalledWith(
        expect.objectContaining({ tag: 'prod' }),
      ),
    );
  });

  it('sends drifted=true and drifted=false from the drift filter', async () => {
    render(wrap(<DeploymentVersionsPage />));
    await screen.findByText('production');

    await chooseOption('Drift', 'Behind latest only');
    await waitFor(() =>
      expect(listDeploymentEnvironmentVersionsMock).toHaveBeenCalledWith(
        expect.objectContaining({ drifted: true }),
      ),
    );

    await chooseOption('Drift', 'Up to date only');
    await waitFor(() =>
      expect(listDeploymentEnvironmentVersionsMock).toHaveBeenCalledWith(
        expect.objectContaining({ drifted: false }),
      ),
    );
  });

  it('trims the environment name filter', async () => {
    render(wrap(<DeploymentVersionsPage />));
    await screen.findByText('production');

    fireEvent.change(screen.getByLabelText('Environment'), { target: { value: '  prod  ' } });

    await waitFor(() =>
      expect(listDeploymentEnvironmentVersionsMock).toHaveBeenCalledWith(
        expect.objectContaining({ environment: 'prod' }),
      ),
    );
  });

  it('builds the pipeline filter from the rows when the caller cannot list pipelines', async () => {
    seedUser(['DEPLOYMENT_REVIEW']);
    render(wrap(<DeploymentVersionsPage />));
    await screen.findByText('production');

    expect(listDeploymentPipelinesMock).not.toHaveBeenCalled();
    fireEvent.mouseDown(screen.getByLabelText('Pipeline'));
    expect(await screen.findByTitle('Checkout Service')).toBeInTheDocument();
  });

  it('explains an empty org before any filter is applied', async () => {
    listDeploymentEnvironmentVersionsMock.mockResolvedValue(page([]));
    render(wrap(<DeploymentVersionsPage />));

    expect(await screen.findByText('Nothing deployed yet')).toBeInTheDocument();
  });

  it('keeps the table and says so when filters exclude everything', async () => {
    render(wrap(<DeploymentVersionsPage />));
    await screen.findByText('production');

    listDeploymentEnvironmentVersionsMock.mockResolvedValue(page([]));
    await chooseOption('Drift', 'Behind latest only');

    expect(await screen.findByText('No environments match these filters')).toBeInTheDocument();
    expect(screen.queryByText('Nothing deployed yet')).not.toBeInTheDocument();
  });

  it('surfaces a load failure instead of an empty table', async () => {
    listDeploymentEnvironmentVersionsMock.mockRejectedValue(new Error('boom'));
    render(wrap(<DeploymentVersionsPage />));

    expect(
      await screen.findByText('Deployment governance request failed'),
    ).toBeInTheDocument();
  });
});
