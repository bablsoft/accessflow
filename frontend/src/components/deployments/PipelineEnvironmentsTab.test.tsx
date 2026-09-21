import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { Datasource, DeploymentEnvironment, ReviewPlan } from '@/types/api';

const {
  listDeploymentEnvironments,
  createDeploymentEnvironment,
  updateDeploymentEnvironment,
  deleteDeploymentEnvironment,
  listReviewPlans,
  listDatasources,
} = vi.hoisted(() => ({
  listDeploymentEnvironments: vi.fn(),
  createDeploymentEnvironment: vi.fn(),
  updateDeploymentEnvironment: vi.fn(),
  deleteDeploymentEnvironment: vi.fn(),
  listReviewPlans: vi.fn(),
  listDatasources: vi.fn(),
}));

vi.mock('@/api/deploymentPipelines', async () => {
  const actual = await vi.importActual<typeof import('@/api/deploymentPipelines')>(
    '@/api/deploymentPipelines',
  );
  return {
    ...actual,
    listDeploymentEnvironments,
    createDeploymentEnvironment,
    updateDeploymentEnvironment,
    deleteDeploymentEnvironment,
  };
});

vi.mock('@/api/reviewPlans', async () => {
  const actual = await vi.importActual<typeof import('@/api/reviewPlans')>('@/api/reviewPlans');
  return { ...actual, listReviewPlans };
});

vi.mock('@/api/datasources', async () => {
  const actual = await vi.importActual<typeof import('@/api/datasources')>('@/api/datasources');
  return { ...actual, listDatasources };
});

const { PipelineEnvironmentsTab } = await import('./PipelineEnvironmentsTab');

const staging: DeploymentEnvironment = {
  id: 'env-1',
  pipeline_id: 'pipe-1',
  name: 'staging',
  tags: ['eu-west', 'acme'],
  sort_order: 0,
  require_review: true,
  required_approvals: 2,
  review_plan_id: 'plan-1',
  allow_break_glass: false,
  datasource_id: null,
  created_at: '2026-08-01T10:00:00Z',
};

const production: DeploymentEnvironment = {
  id: 'env-2',
  pipeline_id: 'pipe-1',
  name: 'production',
  tags: [],
  sort_order: 10,
  require_review: true,
  required_approvals: null,
  review_plan_id: null,
  allow_break_glass: true,
  datasource_id: 'ds-1',
  created_at: '2026-08-01T10:00:00Z',
};

const warehouse = {
  id: 'ds-1',
  organization_id: 'org-1',
  name: 'Warehouse',
  db_type: 'POSTGRESQL',
} as Datasource;

const plan: ReviewPlan = {
  id: 'plan-1',
  organization_id: 'org-1',
  name: 'Standard plan',
  description: null,
  requires_ai_review: true,
  requires_human_approval: true,
  min_approvals_required: 1,
  approval_timeout_hours: 24,
  escalation_after_hours: null,
  nudge_interval_hours: null,
  auto_approve_reads: false,
  notify_channels: [],
  approvers: [],
  created_at: '2026-08-01T10:00:00Z',
};

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

describe('PipelineEnvironmentsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listDeploymentEnvironments.mockResolvedValue([staging, production]);
    listReviewPlans.mockResolvedValue([plan]);
    listDatasources.mockResolvedValue({
      content: [warehouse],
      page: 0,
      size: 200,
      total_elements: 1,
      total_pages: 1,
      last: true,
    });
    createDeploymentEnvironment.mockResolvedValue(staging);
    updateDeploymentEnvironment.mockResolvedValue(staging);
    deleteDeploymentEnvironment.mockResolvedValue(undefined);
  });

  it('renders environment rows with approvals, plan name, and the inherited placeholder', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));

    await screen.findByText('staging');
    expect(screen.getByText('production')).toBeInTheDocument();
    // staging: explicit approvals and a resolved review-plan name.
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('Standard plan')).toBeInTheDocument();
    // production: null approvals AND null plan both render the inherited placeholder.
    expect(screen.getAllByText('Plan default')).toHaveLength(2);
  });

  it('creates an environment from the add modal with require_review defaulting to true', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getByRole('button', { name: /add environment/i }));
    await screen.findByRole('dialog');

    const nameInput = screen.getByLabelText('Name');
    fireEvent.change(nameInput, { target: { value: 'qa' } });

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(createDeploymentEnvironment).toHaveBeenCalledTimes(1));
    const [pipelineId, input] = createDeploymentEnvironment.mock.calls[0]!;
    expect(pipelineId).toBe('pipe-1');
    expect(input).toEqual({
      name: 'qa',
      tags: [],
      // Default sort order = one past the current last rung (0, 10 → 11), the server's own default.
      sort_order: 11,
      require_review: true,
      required_approvals: null,
      review_plan_id: null,
      allow_break_glass: false,
      datasource_id: null,
    });
  });

  it('renders the bound datasource name and the deploy-only placeholder', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));

    await screen.findByText('staging');
    await screen.findByText('Warehouse');
    // staging is deploy-only.
    expect(screen.getAllByText('Deploy-only (no database)').length).toBeGreaterThan(0);
  });

  it('falls back to the raw id when the bound datasource no longer exists', async () => {
    listDeploymentEnvironments.mockResolvedValue([{ ...production, datasource_id: 'ds-gone' }]);
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));

    await screen.findByText('production');
    expect(await screen.findByText('ds-gone')).toBeInTheDocument();
  });

  it('binds a datasource on create', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getByRole('button', { name: /add environment/i }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'qa' } });

    // Open the Database select and pick the only option.
    const select = dialog.querySelector('#deployment_environment_datasource_id')!;
    fireEvent.mouseDown(select);
    fireEvent.click(await screen.findByTitle('Warehouse'));

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(createDeploymentEnvironment).toHaveBeenCalledTimes(1));
    expect(createDeploymentEnvironment.mock.calls[0]![1]).toEqual(
      expect.objectContaining({ name: 'qa', datasource_id: 'ds-1' }),
    );
  });

  it('sends clear_datasource when the binding is removed in the edit modal', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    // production (second row) is bound to ds-1.
    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]!);
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('production'));

    const clear = dialog
      .querySelector('#deployment_environment_datasource_id')!
      .closest('.ant-select')!
      .querySelector('.ant-select-clear')!;
    fireEvent.mouseDown(clear);
    fireEvent.click(clear);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateDeploymentEnvironment).toHaveBeenCalledTimes(1));
    expect(updateDeploymentEnvironment.mock.calls[0]![2]).toEqual(
      expect.objectContaining({ datasource_id: null, clear_datasource: true, sort_order: 10 }),
    );
  });

  it('keeps the binding on an edit that does not touch it', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]!);
    await screen.findByRole('dialog');
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('production'));

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateDeploymentEnvironment).toHaveBeenCalledTimes(1));
    expect(updateDeploymentEnvironment.mock.calls[0]![2]).toEqual(
      expect.objectContaining({ datasource_id: 'ds-1', clear_datasource: false }),
    );
  });

  it('requires an Order in the edit modal — a cleared value would otherwise silently keep the old one', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]!);
    await screen.findByRole('dialog');
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('production'));
    // Edit mode does not offer the "leave empty to append" hint.
    expect(screen.queryByText(/Leave empty to append/)).not.toBeInTheDocument();

    const order = screen.getByLabelText('Order');
    fireEvent.change(order, { target: { value: '' } });
    fireEvent.blur(order);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(order.closest('.ant-form-item')).toHaveClass('ant-form-item-has-error'),
    );
    expect(updateDeploymentEnvironment).not.toHaveBeenCalled();
  });

  it('offers the append hint only when creating', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getByRole('button', { name: /add environment/i }));
    await screen.findByRole('dialog');

    expect(screen.getByText(/Leave empty to append/)).toBeInTheDocument();
  });

  it('clamps a negative order to 0 — the @Min(0) parity rule is never reachable from the field', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]!);
    await screen.findByRole('dialog');
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('production'));

    const order = screen.getByLabelText('Order');
    fireEvent.change(order, { target: { value: '-1' } });
    fireEvent.blur(order);
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(updateDeploymentEnvironment).toHaveBeenCalledTimes(1));
    expect(updateDeploymentEnvironment.mock.calls[0]![2]).toEqual(
      expect.objectContaining({ sort_order: 0 }),
    );
  });

  it('prefills the edit modal and sends clear_required_approvals when the count is cleared', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    const editButtons = screen.getAllByRole('button', { name: 'Edit' });
    fireEvent.click(editButtons[0]!);
    await screen.findByRole('dialog');

    // Prefilled from the staging row.
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('staging'));
    const approvals = screen.getByLabelText('Required approvals');
    expect(approvals).toHaveValue('2');

    fireEvent.change(approvals, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(updateDeploymentEnvironment).toHaveBeenCalledTimes(1));
    const [pipelineId, environmentId, input] = updateDeploymentEnvironment.mock.calls[0]!;
    expect(pipelineId).toBe('pipe-1');
    expect(environmentId).toBe('env-1');
    expect(input).toEqual(
      expect.objectContaining({
        name: 'staging',
        tags: ['eu-west', 'acme'],
        required_approvals: null,
        clear_required_approvals: true,
        review_plan_id: 'plan-1',
        clear_review_plan: false,
      }),
    );
    expect(createDeploymentEnvironment).not.toHaveBeenCalled();
  });

  it('deletes an environment after confirming the Popconfirm', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    // First row's Delete button opens the Popconfirm.
    const rowDeletes = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(rowDeletes[0]!);

    await screen.findByText('Delete this environment?');
    // The Popconfirm's ok button is appended last in the document.
    const allDeletes = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(allDeletes[allDeletes.length - 1]!);

    await waitFor(() => expect(deleteDeploymentEnvironment).toHaveBeenCalledTimes(1));
    expect(deleteDeploymentEnvironment).toHaveBeenCalledWith('pipe-1', 'env-1');
  });

  it('renders tag chips, and an em dash for an untagged environment', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));

    await screen.findByText('staging');
    // staging carries two tags; production carries none.
    expect(screen.getByText('acme')).toBeInTheDocument();
    const tagCells = screen.getAllByText('—');
    expect(tagCells.length).toBeGreaterThan(0);
  });

  it('submits an emptied tag list as [] so the server does not read it as "leave unchanged"', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[0]!);
    const dialog = await screen.findByRole('dialog');
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('staging'));

    // Remove both prefilled tags via their close buttons.
    for (const close of [...dialog.querySelectorAll('.ant-select-selection-item-remove')]) {
      fireEvent.click(close);
    }

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateDeploymentEnvironment).toHaveBeenCalledTimes(1));
    expect(updateDeploymentEnvironment.mock.calls[0]![2]).toEqual(
      expect.objectContaining({ tags: [] }),
    );
  });

  it('blocks a save when more than ten tags are entered', async () => {
    listDeploymentEnvironments.mockResolvedValue([
      { ...staging, tags: Array.from({ length: 11 }, (_, i) => `t${i}`) },
    ]);
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[0]!);
    await screen.findByRole('dialog');
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('staging'));

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByText('An environment may have at most 10 tags')).toBeInTheDocument();
    expect(updateDeploymentEnvironment).not.toHaveBeenCalled();
  });

  it('blocks a save when a tag is longer than 32 characters', async () => {
    listDeploymentEnvironments.mockResolvedValue([{ ...staging, tags: ['x'.repeat(33)] }]);
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[0]!);
    await screen.findByRole('dialog');
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('staging'));

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(
      await screen.findByText('Each tag must be at most 32 characters'),
    ).toBeInTheDocument();
    expect(updateDeploymentEnvironment).not.toHaveBeenCalled();
  });
});
