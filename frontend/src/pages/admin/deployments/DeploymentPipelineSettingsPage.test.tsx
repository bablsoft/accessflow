import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { DeploymentPipeline } from '@/types/api';

const getDeploymentPipeline = vi.fn();
const updateDeploymentPipeline = vi.fn();
const listReviewPlans = vi.fn();
const listAiConfigs = vi.fn();

vi.mock('@/api/deploymentPipelines', () => ({
  getDeploymentPipeline: (...args: unknown[]) => getDeploymentPipeline(...args),
  updateDeploymentPipeline: (...args: unknown[]) => updateDeploymentPipeline(...args),
  deploymentPipelineKeys: {
    all: ['deployment-pipelines'] as const,
    lists: () => ['deployment-pipelines', 'list'] as const,
    list: (filters: unknown) => ['deployment-pipelines', 'list', filters] as const,
    details: () => ['deployment-pipelines', 'detail'] as const,
    detail: (id: string) => ['deployment-pipelines', 'detail', id] as const,
  },
}));

vi.mock('@/api/reviewPlans', () => ({
  listReviewPlans: (...args: unknown[]) => listReviewPlans(...args),
  reviewPlanKeys: { all: ['reviewPlans'] as const, lists: () => ['reviewPlans', 'list'] as const },
}));

vi.mock('@/api/admin', () => ({
  listAiConfigs: (...args: unknown[]) => listAiConfigs(...args),
  aiConfigKeys: { all: ['aiConfig'] as const, lists: () => ['aiConfig', 'list'] as const },
}));

vi.mock('@/components/deployments/PipelineEnvironmentsTab', () => ({
  PipelineEnvironmentsTab: () => null,
}));
vi.mock('@/components/deployments/PipelineVersionsTab', () => ({
  PipelineVersionsTab: () => <div>versions tab</div>,
}));
vi.mock('@/components/deployments/PipelinePermissionsTab', () => ({
  PipelinePermissionsTab: () => null,
}));
vi.mock('@/components/deployments/PipelineFreezeWindowsTab', () => ({
  PipelineFreezeWindowsTab: () => null,
}));
vi.mock('@/components/deployments/PipelineRoutingPoliciesTab', () => ({
  PipelineRoutingPoliciesTab: () => null,
}));

const { DeploymentPipelineSettingsPage } = await import('./DeploymentPipelineSettingsPage');

const basePipeline: DeploymentPipeline = {
  id: 'pipe-1',
  name: 'Prod Deploy',
  provider: 'GITLAB_CI',
  repository_url: 'https://gitlab.com/acme/shop',
  project_ref: null,
  review_plan_id: null,
  ai_analysis_enabled: true,
  ai_config_id: null,
  active: true,
  created_at: '2026-05-01T00:00:00Z',
  updated_at: null,
};

function wrap(node: ReactNode, entry = '/admin/deployment-pipelines/pipe-1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <AntdApp>
          <Routes>
            <Route path="/admin/deployment-pipelines/:id" element={node} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('DeploymentPipelineSettingsPage', () => {
  beforeEach(() => {
    getDeploymentPipeline.mockReset();
    updateDeploymentPipeline.mockReset();
    listReviewPlans.mockReset();
    listAiConfigs.mockReset();
    getDeploymentPipeline.mockResolvedValue(basePipeline);
    listReviewPlans.mockResolvedValue([]);
    listAiConfigs.mockResolvedValue([]);
  });

  it('shows the pipeline name in the header and all seven tabs', async () => {
    render(wrap(<DeploymentPipelineSettingsPage />));

    expect(await screen.findByText('Prod Deploy')).toBeInTheDocument();
    expect(getDeploymentPipeline).toHaveBeenCalledWith('pipe-1');
    for (const tab of [
      'General',
      'Environments',
      'Versions',
      'Permissions',
      'Freeze windows',
      'Routing policies',
      'CI setup',
    ]) {
      expect(screen.getByRole('tab', { name: tab })).toBeInTheDocument();
    }
  });

  it('renders the pipeline id in the header, copyable without reading the URL', async () => {
    render(wrap(<DeploymentPipelineSettingsPage />));

    expect(await screen.findByTestId('pipeline-id')).toHaveTextContent('pipe-1');
    expect(screen.getByRole('button', { name: 'Copy pipeline ID' })).toBeInTheDocument();
  });

  it('prefills the general form with the pipeline values', async () => {
    render(wrap(<DeploymentPipelineSettingsPage />));

    const name = await screen.findByLabelText('Name');
    await waitFor(() => expect(name).toHaveValue('Prod Deploy'));
    expect(screen.getByLabelText('Repository URL')).toHaveValue('https://gitlab.com/acme/shop');
  });

  it('saves an edited name with the clear flags for unset associations', async () => {
    updateDeploymentPipeline.mockResolvedValue({ ...basePipeline, name: 'Renamed Deploy' });

    render(wrap(<DeploymentPipelineSettingsPage />));
    const name = await screen.findByLabelText('Name');
    await waitFor(() => expect(name).toHaveValue('Prod Deploy'));

    fireEvent.change(name, { target: { value: 'Renamed Deploy' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    await waitFor(() =>
      expect(updateDeploymentPipeline).toHaveBeenCalledWith('pipe-1', {
        name: 'Renamed Deploy',
        provider: 'GITLAB_CI',
        repository_url: 'https://gitlab.com/acme/shop',
        // Empty string, not null: null means "unchanged" to the update command, so a cleared
        // input must send '' to actually clear the stored value.
        project_ref: '',
        review_plan_id: null,
        clear_review_plan: true,
        ai_analysis_enabled: true,
        ai_config_id: null,
        clear_ai_config: true,
        active: true,
      }),
    );
    expect(await screen.findByText('Pipeline updated')).toBeInTheDocument();
  });

  it('shows the not-found empty state when the pipeline cannot be loaded', async () => {
    getDeploymentPipeline.mockRejectedValue(new Error('404'));

    render(wrap(<DeploymentPipelineSettingsPage />));

    expect(await screen.findByText('Pipeline not found')).toBeInTheDocument();
    expect(screen.queryByRole('tab', { name: 'General' })).not.toBeInTheDocument();
  });

  it('opens the tab named in the URL and writes the active tab back to it', async () => {
    render(wrap(<DeploymentPipelineSettingsPage />, '/admin/deployment-pipelines/pipe-1?tab=versions'));

    expect(await screen.findByText('versions tab')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Versions' })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    fireEvent.click(screen.getByRole('tab', { name: 'Permissions' }));
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: 'Permissions' })).toHaveAttribute(
        'aria-selected',
        'true',
      ),
    );
  });

  it('falls back to General for an unknown tab in the URL', async () => {
    render(wrap(<DeploymentPipelineSettingsPage />, '/admin/deployment-pipelines/pipe-1?tab=nope'));

    await screen.findByText('Prod Deploy');
    expect(screen.getByRole('tab', { name: 'General' })).toHaveAttribute('aria-selected', 'true');
  });
});
