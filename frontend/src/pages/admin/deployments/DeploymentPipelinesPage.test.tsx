import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import type { DeploymentPipeline } from '@/types/api';

const listDeploymentPipelines = vi.fn();
const createDeploymentPipeline = vi.fn();
const deleteDeploymentPipeline = vi.fn();

vi.mock('@/api/deploymentPipelines', () => ({
  listDeploymentPipelines: (...args: unknown[]) => listDeploymentPipelines(...args),
  createDeploymentPipeline: (...args: unknown[]) => createDeploymentPipeline(...args),
  deleteDeploymentPipeline: (...args: unknown[]) => deleteDeploymentPipeline(...args),
  deploymentPipelineKeys: {
    all: ['deployment-pipelines'] as const,
    lists: () => ['deployment-pipelines', 'list'] as const,
    list: (filters: unknown) => ['deployment-pipelines', 'list', filters] as const,
    details: () => ['deployment-pipelines', 'detail'] as const,
    detail: (id: string) => ['deployment-pipelines', 'detail', id] as const,
  },
}));

vi.mock('@/api/reviewPlans', () => ({
  listReviewPlans: () => Promise.resolve([]),
  reviewPlanKeys: { all: ['reviewPlans'] as const, lists: () => ['reviewPlans', 'list'] as const },
}));

vi.mock('@/api/admin', () => ({
  listAiConfigs: () => Promise.resolve([]),
  aiConfigKeys: { all: ['aiConfig'] as const, lists: () => ['aiConfig', 'list'] as const },
}));

const { DeploymentPipelinesPage } = await import('./DeploymentPipelinesPage');

const basePipeline: DeploymentPipeline = {
  id: 'p-1',
  name: 'Prod Deploy',
  provider: 'GITHUB_ACTIONS',
  repository_url: 'https://github.com/acme/shop',
  project_ref: null,
  review_plan_id: null,
  ai_analysis_enabled: true,
  ai_config_id: null,
  active: true,
  created_at: '2026-05-01T00:00:00Z',
  updated_at: null,
};

function page(content: DeploymentPipeline[]) {
  return {
    content,
    page: 0,
    size: 20,
    total_elements: content.length,
    total_pages: 1,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/deployment-pipelines']}>
        <AntdApp>
          <Routes>
            <Route path="/admin/deployment-pipelines" element={node} />
            <Route path="/admin/deployment-pipelines/:id" element={<div>settings-route</div>} />
          </Routes>
        </AntdApp>
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('DeploymentPipelinesPage', () => {
  beforeEach(() => {
    listDeploymentPipelines.mockReset();
    createDeploymentPipeline.mockReset();
    deleteDeploymentPipeline.mockReset();
    listDeploymentPipelines.mockResolvedValue(page([basePipeline]));
  });

  it('renders pipeline rows with name, provider label and active tag', async () => {
    render(wrap(<DeploymentPipelinesPage />));

    expect(await screen.findByText('Prod Deploy')).toBeInTheDocument();
    expect(screen.getByText('GitHub Actions')).toBeInTheDocument();
    // "Active" appears both as the column header and inside the row's tag.
    const rowEl = screen.getByText('Prod Deploy').closest('tr');
    expect(rowEl).not.toBeNull();
    expect(within(rowEl!).getByText('Active')).toBeInTheDocument();
  });

  it('creates a pipeline from the modal and navigates to its settings', async () => {
    createDeploymentPipeline.mockResolvedValue({ ...basePipeline, id: 'p-new', name: 'New Deploy' });

    render(wrap(<DeploymentPipelinesPage />));
    await screen.findByText('Prod Deploy');

    fireEvent.click(screen.getByRole('button', { name: /Add pipeline/i }));
    const dialog = await screen.findByRole('dialog');

    fireEvent.change(within(dialog).getByLabelText('Name'), {
      target: { value: 'New Deploy' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create pipeline' }));

    await waitFor(() =>
      expect(createDeploymentPipeline).toHaveBeenCalledWith({
        name: 'New Deploy',
        provider: 'GITHUB_ACTIONS',
        repository_url: null,
        project_ref: null,
        review_plan_id: null,
        ai_analysis_enabled: true,
        ai_config_id: null,
      }),
    );
    expect(await screen.findByText('settings-route')).toBeInTheDocument();
  });

  it('rejects a name shorter than 3 characters client-side', async () => {
    render(wrap(<DeploymentPipelinesPage />));
    await screen.findByText('Prod Deploy');

    fireEvent.click(screen.getByRole('button', { name: /Add pipeline/i }));
    const dialog = await screen.findByRole('dialog');

    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'ab' } });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create pipeline' }));

    await waitFor(() =>
      expect(dialog.querySelector('.ant-form-item-explain-error')).not.toBeNull(),
    );
    expect(createDeploymentPipeline).not.toHaveBeenCalled();
  });

  it('deletes a pipeline through the confirmation popover', async () => {
    deleteDeploymentPipeline.mockResolvedValue(undefined);

    render(wrap(<DeploymentPipelinesPage />));
    const rowEl = (await screen.findByText('Prod Deploy')).closest('tr');
    expect(rowEl).not.toBeNull();

    fireEvent.click(within(rowEl!).getByRole('button', { name: 'Delete' }));
    await screen.findByText('Delete this pipeline?');

    const pop = await screen.findByRole('tooltip');
    fireEvent.click(within(pop).getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(deleteDeploymentPipeline).toHaveBeenCalledWith('p-1'));
    expect(await screen.findByText('Pipeline deleted')).toBeInTheDocument();
  });

  it('shows the empty state when no pipelines exist', async () => {
    listDeploymentPipelines.mockResolvedValue(page([]));

    render(wrap(<DeploymentPipelinesPage />));

    expect(await screen.findByText('No deployment pipelines yet')).toBeInTheDocument();
  });
});
