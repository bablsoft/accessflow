import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
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
  change_ticket_required: false,
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
  change_ticket_required: true,
  datasource_id: 'ds-1',
  created_at: '2026-08-01T10:00:00Z',
};

const verified: DeploymentEnvironment = {
  ...staging,
  id: 'env-3',
  name: 'canary',
  sort_order: 20,
  verification: {
    window: 'PT15M',
    sample_interval: 'PT30S',
    failure_threshold: 3,
    checks: [
      {
        kind: 'HTTP_HEALTH',
        name: 'health',
        url: 'https://svc.example.com/healthz',
        expected_status: 200,
      },
      {
        kind: 'PROMQL_THRESHOLD',
        name: 'errors',
        prometheus_url: 'https://prom.example.com',
        query: 'sum(rate(errors[5m]))',
        comparator: 'LT',
        threshold: 0.05,
        no_data_is_failure: true,
      },
    ],
  },
};

function problem(status: number, data: Record<string, unknown>): AxiosError {
  const response = { data, status, statusText: '', headers: {}, config: {} as never } as AxiosResponse;
  return new AxiosError('failed', undefined, undefined, undefined, response);
}

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
      change_ticket_required: false,
      datasource_id: null,
      verification: null,
    });
  });

  it('creates a change-ticket environment when the switch is on and explains the trigger', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getByRole('button', { name: /add environment/i }));
    await screen.findByRole('dialog');
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'prod-eu' } });
    expect(screen.getByText(/must enable the deployment trigger/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('switch', { name: 'Change ticket' }));

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(createDeploymentEnvironment).toHaveBeenCalledTimes(1));
    expect(createDeploymentEnvironment.mock.calls[0]![1]).toEqual(
      expect.objectContaining({ name: 'prod-eu', change_ticket_required: true }),
    );
  });

  it('prefills the change-ticket switch on edit and sends it back when turned off', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    fireEvent.click(screen.getAllByRole('button', { name: 'Edit' })[1]!);
    await screen.findByRole('dialog');
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('production'));
    const toggle = screen.getByRole('switch', { name: 'Change ticket' });
    expect(toggle).toBeChecked();
    fireEvent.click(toggle);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(updateDeploymentEnvironment).toHaveBeenCalledTimes(1));
    expect(updateDeploymentEnvironment.mock.calls[0]![2]).toEqual(
      expect.objectContaining({ change_ticket_required: false }),
    );
  });

  it('renders the change-ticket column', async () => {
    render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
    await screen.findByText('staging');

    expect(screen.getByText('Change ticket').closest('th')).not.toBeNull();
    // Both rows require review; production adds break-glass and the change ticket.
    expect(screen.getAllByText('✓')).toHaveLength(4);
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

  describe('post-deployment verification (#1027)', () => {
    async function openCreate() {
      render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
      await screen.findByText('staging');
      fireEvent.click(screen.getByRole('button', { name: /add environment/i }));
      await screen.findByRole('dialog');
      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'canary' } });
    }

    function enable() {
      fireEvent.click(screen.getByRole('switch', { name: 'Verify after release' }));
    }

    it('summarises the check count in the table and an em dash when off', async () => {
      listDeploymentEnvironments.mockResolvedValue([staging, verified]);
      render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
      await screen.findByText('canary');
      expect(screen.getByText('Checks: 2')).toBeInTheDocument();
      expect(screen.getByText('Verification').closest('th')).not.toBeNull();
    });

    it('hides the fields until the switch is on', async () => {
      await openCreate();
      expect(screen.queryByLabelText('Health URL')).toBeNull();
      enable();
      expect(await screen.findByLabelText('Health URL')).toBeInTheDocument();
    });

    it('creates an environment with an HTTP check', async () => {
      await openCreate();
      enable();
      fireEvent.change(await screen.findByLabelText('Check name'), { target: { value: 'health' } });
      fireEvent.change(screen.getByLabelText('Health URL'), {
        target: { value: 'https://svc.example.com/healthz' },
      });
      fireEvent.change(screen.getByLabelText('Body contains (optional)'), {
        target: { value: 'ok' },
      });

      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(createDeploymentEnvironment).toHaveBeenCalledTimes(1));
      expect(createDeploymentEnvironment.mock.calls[0]![1].verification).toEqual({
        window: 'PT15M',
        sample_interval: 'PT1M',
        failure_threshold: 3,
        checks: [
          {
            kind: 'HTTP_HEALTH',
            name: 'health',
            url: 'https://svc.example.com/healthz',
            expected_status: 200,
            body_contains: 'ok',
          },
        ],
      });
    });

    it('swaps to the PromQL fields when the kind changes', async () => {
      await openCreate();
      enable();
      fireEvent.mouseDown(await screen.findByLabelText('Check type'));
      fireEvent.click(await screen.findByText('PromQL threshold'));
      expect(await screen.findByLabelText('Prometheus URL')).toBeInTheDocument();
      expect(screen.queryByLabelText('Health URL')).toBeNull();
      fireEvent.change(screen.getByLabelText('Check name'), { target: { value: 'errors' } });
      fireEvent.change(screen.getByLabelText('Prometheus URL'), {
        target: { value: 'https://prom.example.com' },
      });
      fireEvent.change(screen.getByLabelText('PromQL query'), {
        target: { value: 'sum(rate(errors[5m]))' },
      });
      fireEvent.change(screen.getByLabelText('Threshold'), { target: { value: '0.05' } });

      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(createDeploymentEnvironment).toHaveBeenCalledTimes(1));
      expect(createDeploymentEnvironment.mock.calls[0]![1].verification.checks).toEqual([
        {
          kind: 'PROMQL_THRESHOLD',
          name: 'errors',
          prometheus_url: 'https://prom.example.com',
          query: 'sum(rate(errors[5m]))',
          comparator: 'LT',
          threshold: 0.05,
          no_data_is_failure: true,
        },
      ]);
    });

    it('refuses a non-http URL', async () => {
      await openCreate();
      enable();
      fireEvent.change(await screen.findByLabelText('Health URL'), {
        target: { value: 'ftp://svc.example.com' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(await screen.findByText('Use an http:// or https:// URL.')).toBeInTheDocument();
      expect(createDeploymentEnvironment).not.toHaveBeenCalled();
    });

    it('refuses a threshold the window cannot reach and an interval longer than the window', async () => {
      await openCreate();
      enable();
      fireEvent.change(await screen.findByLabelText('Window'), { target: { value: '2' } });
      fireEvent.change(screen.getByLabelText('Sample every'), { target: { value: '1' } });
      fireEvent.change(screen.getByLabelText('Failures to breach'), { target: { value: '3' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(await screen.findByText(/collects at most 2 samples/)).toBeInTheDocument();

      fireEvent.change(screen.getByLabelText('Sample every'), { target: { value: '5' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(
        await screen.findByText(/no longer than the window/),
      ).toBeInTheDocument();
      expect(createDeploymentEnvironment).not.toHaveBeenCalled();
    });

    it('refuses duplicate check names and an empty check list', async () => {
      await openCreate();
      enable();
      await screen.findByLabelText('Check name');
      // Window bounds are clamped by the InputNumber's own min / max, so no out-of-range value
      // reaches the Form rule.
      fireEvent.click(screen.getByRole('button', { name: /add check/i }));
      const names = await screen.findAllByLabelText('Check name');
      fireEvent.change(names[0]!, { target: { value: 'health' } });
      fireEvent.change(names[1]!, { target: { value: 'health' } });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(
        (await screen.findAllByText('Another check already uses this name.')).length,
      ).toBeGreaterThan(0);

      for (const remove of screen.getAllByRole('button', { name: /remove check/i })) {
        fireEvent.click(remove);
      }
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(await screen.findByText('Add between 1 and 10 checks.')).toBeInTheDocument();
      expect(createDeploymentEnvironment).not.toHaveBeenCalled();
    });

    it('prefills a stored config on edit and round-trips it unchanged', async () => {
      listDeploymentEnvironments.mockResolvedValue([verified]);
      render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
      await screen.findByText('canary');
      fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
      await screen.findByRole('dialog');
      expect(await screen.findByLabelText('Health URL')).toHaveValue(
        'https://svc.example.com/healthz',
      );
      expect(screen.getByLabelText('Prometheus URL')).toHaveValue('https://prom.example.com');

      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(updateDeploymentEnvironment).toHaveBeenCalledTimes(1));
      const input = updateDeploymentEnvironment.mock.calls[0]![2];
      expect(input.clear_verification).toBe(false);
      expect(input.verification).toEqual({
        window: 'PT15M',
        sample_interval: 'PT30S',
        failure_threshold: 3,
        checks: [
          {
            kind: 'HTTP_HEALTH',
            name: 'health',
            url: 'https://svc.example.com/healthz',
            expected_status: 200,
            body_contains: null,
          },
          {
            kind: 'PROMQL_THRESHOLD',
            name: 'errors',
            prometheus_url: 'https://prom.example.com',
            query: 'sum(rate(errors[5m]))',
            comparator: 'LT',
            threshold: 0.05,
            no_data_is_failure: true,
          },
        ],
      });
    });

    it('sends clear_verification when the switch is turned off on edit', async () => {
      listDeploymentEnvironments.mockResolvedValue([verified]);
      render(wrap(<PipelineEnvironmentsTab pipelineId="pipe-1" />));
      await screen.findByText('canary');
      fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
      await screen.findByLabelText('Health URL');
      fireEvent.click(screen.getByRole('switch', { name: 'Verify after release' }));

      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(updateDeploymentEnvironment).toHaveBeenCalledTimes(1));
      expect(updateDeploymentEnvironment.mock.calls[0]![2]).toEqual(
        expect.objectContaining({ verification: null, clear_verification: true }),
      );
    });

    it('refuses credentials in a URL and a query string on a Prometheus URL', async () => {
      await openCreate();
      enable();
      fireEvent.change(await screen.findByLabelText('Health URL'), {
        target: { value: 'https://user:secret@svc.example.com/healthz' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(await screen.findByText(/Remove the user:password@ part/)).toBeInTheDocument();

      fireEvent.mouseDown(screen.getByLabelText('Check type'));
      fireEvent.click(await screen.findByText('PromQL threshold'));
      fireEvent.change(await screen.findByLabelText('Prometheus URL'), {
        target: { value: 'https://prom.example.com/?tenant=a' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(
        await screen.findByText('Use the base URL only — no query string or fragment.'),
      ).toBeInTheDocument();
      expect(createDeploymentEnvironment).not.toHaveBeenCalled();
    });

    it('names the rule a 422 DEPLOYMENT_VERIFICATION_INVALID reports, not its generic detail', async () => {
      // The server's real shape: one generic `detail`, the rule in `reason` / `checkName`.
      createDeploymentEnvironment.mockRejectedValue(
        problem(422, {
          error: 'DEPLOYMENT_VERIFICATION_INVALID',
          reason: 'host_unresolvable',
          checkName: 'health',
          detail: 'The verification config is invalid',
        }),
      );
      await openCreate();
      enable();
      fireEvent.change(await screen.findByLabelText('Check name'), { target: { value: 'health' } });
      fireEvent.change(screen.getByLabelText('Health URL'), {
        target: { value: 'https://nope.example.com' },
      });
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(
        await screen.findByText(
          'Check "health": the host does not resolve from the AccessFlow server.',
        ),
      ).toBeInTheDocument();
      expect(screen.queryByText('The verification config is invalid')).toBeNull();
    });

    it('falls back to the server detail for any other error', async () => {
      createDeploymentEnvironment.mockRejectedValue(
        problem(409, { error: 'DEPLOYMENT_ENVIRONMENT_DUPLICATE_NAME', detail: 'Name taken' }),
      );
      await openCreate();
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      expect(await screen.findByText('Name taken')).toBeInTheDocument();
    });
  });
});
