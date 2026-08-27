import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { DeploymentRoutingPolicy } from '@/types/api';

const {
  listDeploymentRoutingPolicies,
  createDeploymentRoutingPolicy,
  updateDeploymentRoutingPolicy,
  deleteDeploymentRoutingPolicy,
  listDeploymentEnvironments,
} = vi.hoisted(() => ({
  listDeploymentRoutingPolicies: vi.fn(),
  createDeploymentRoutingPolicy: vi.fn(),
  updateDeploymentRoutingPolicy: vi.fn(),
  deleteDeploymentRoutingPolicy: vi.fn(),
  listDeploymentEnvironments: vi.fn(),
}));

vi.mock('@/api/deploymentRoutingPolicies', async () => {
  const actual = await vi.importActual<typeof import('@/api/deploymentRoutingPolicies')>(
    '@/api/deploymentRoutingPolicies',
  );
  return {
    ...actual,
    listDeploymentRoutingPolicies,
    createDeploymentRoutingPolicy,
    updateDeploymentRoutingPolicy,
    deleteDeploymentRoutingPolicy,
  };
});

vi.mock('@/api/deploymentPipelines', async () => {
  const actual = await vi.importActual<typeof import('@/api/deploymentPipelines')>(
    '@/api/deploymentPipelines',
  );
  return { ...actual, listDeploymentEnvironments };
});

const { PipelineRoutingPoliciesTab } = await import('./PipelineRoutingPoliciesTab');

const scopedPolicy: DeploymentRoutingPolicy = {
  id: 'pol-1',
  pipeline_id: 'pipe-1',
  name: 'Prod gate',
  conditions: {
    environments: ['production'],
    providers: [],
    min_risk_level: 'HIGH',
    version_globs: [],
    days_of_week: [],
    start_time: null,
    end_time: null,
    timezone: null,
  },
  action: 'REQUIRE_APPROVALS',
  required_approvals: 2,
  priority: 10,
  enabled: true,
  created_at: '2026-08-01T10:00:00Z',
};

const globalPolicy: DeploymentRoutingPolicy = {
  id: 'pol-2',
  pipeline_id: null,
  name: 'Global reject',
  conditions: {
    environments: [],
    providers: [],
    min_risk_level: null,
    version_globs: [],
    days_of_week: [],
    start_time: null,
    end_time: null,
    timezone: null,
  },
  action: 'AUTO_REJECT',
  required_approvals: null,
  priority: 50,
  enabled: true,
  created_at: '2026-08-01T10:00:00Z',
};

const otherPipelinePolicy: DeploymentRoutingPolicy = {
  ...scopedPolicy,
  id: 'pol-3',
  pipeline_id: 'other-pipe',
  name: 'Other pipeline policy',
};

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

describe('PipelineRoutingPoliciesTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listDeploymentRoutingPolicies.mockResolvedValue([
      scopedPolicy,
      globalPolicy,
      otherPipelinePolicy,
    ]);
    listDeploymentEnvironments.mockResolvedValue([]);
    createDeploymentRoutingPolicy.mockResolvedValue(scopedPolicy);
    updateDeploymentRoutingPolicy.mockResolvedValue(scopedPolicy);
    deleteDeploymentRoutingPolicy.mockResolvedValue(undefined);
  });

  it('lists priority, name, Global tag, action with approval count, and conditions summary', async () => {
    render(wrap(<PipelineRoutingPoliciesTab pipelineId="pipe-1" />));

    await screen.findByText('Prod gate');
    expect(screen.getByText('10')).toBeInTheDocument();
    expect(screen.getByText('Global reject')).toBeInTheDocument();
    // Only the null-pipeline policy carries the Global tag.
    expect(screen.getAllByText('Global')).toHaveLength(1);
    // Action label plus the ×N approvals suffix.
    expect(screen.getByText('Require approvals')).toBeInTheDocument();
    expect(screen.getByText('×2')).toBeInTheDocument();
    expect(screen.getByText('Auto-reject')).toBeInTheDocument();
    // Conditions summaries.
    expect(screen.getByText('env production · risk ≥ High')).toBeInTheDocument();
    expect(screen.getByText('Matches every deployment')).toBeInTheDocument();
    // A policy scoped to another pipeline is not listed.
    expect(screen.queryByText('Other pipeline policy')).not.toBeInTheDocument();
  });

  it('creates an AUTO_APPROVE policy with null approvals and the pipeline scope', async () => {
    render(wrap(<PipelineRoutingPoliciesTab pipelineId="pipe-1" />));
    await screen.findByText('Prod gate');

    fireEvent.click(screen.getByRole('button', { name: /add policy/i }));
    await screen.findByRole('dialog');

    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Fast lane' } });

    // The default action requires an approvals count…
    expect(screen.getByLabelText('Required approvals')).toBeInTheDocument();

    // …but switching to AUTO_APPROVE hides the field.
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Action' }));
    await waitFor(() =>
      expect(
        [...document.querySelectorAll('.ant-select-item-option-content')].length,
      ).toBeGreaterThan(0),
    );
    const autoApprove = [...document.querySelectorAll('.ant-select-item-option-content')].find(
      (o) => o.textContent === 'Auto-approve',
    );
    fireEvent.click(autoApprove!);
    await waitFor(() =>
      expect(screen.queryByLabelText('Required approvals')).not.toBeInTheDocument(),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Create policy' }));

    await waitFor(() => expect(createDeploymentRoutingPolicy).toHaveBeenCalledTimes(1));
    const [input] = createDeploymentRoutingPolicy.mock.calls[0]!;
    expect(input).toEqual({
      pipeline_id: 'pipe-1',
      name: 'Fast lane',
      action: 'AUTO_APPROVE',
      required_approvals: null,
      priority: 100,
      enabled: true,
      conditions: {
        environments: [],
        providers: [],
        min_risk_level: null,
        version_globs: [],
        days_of_week: [],
        start_time: null,
        end_time: null,
        timezone: null,
      },
    });
  });

  it('prefills the edit modal and sends clear_pipeline when scope is switched off', async () => {
    render(wrap(<PipelineRoutingPoliciesTab pipelineId="pipe-1" />));
    await screen.findByText('Prod gate');

    const editButtons = screen.getAllByRole('button', { name: 'Edit' });
    fireEvent.click(editButtons[0]!);
    await screen.findByRole('dialog');

    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('Prod gate'));

    const dialog = screen.getByRole('dialog');
    const switches = within(dialog).getAllByRole('switch');
    // Scope switch is first and starts on for a pipeline-scoped policy.
    expect(switches[0]!.getAttribute('aria-checked')).toBe('true');
    fireEvent.click(switches[0]!);

    fireEvent.click(screen.getByRole('button', { name: 'Save policy' }));

    await waitFor(() => expect(updateDeploymentRoutingPolicy).toHaveBeenCalledTimes(1));
    const [policyId, input] = updateDeploymentRoutingPolicy.mock.calls[0]!;
    expect(policyId).toBe('pol-1');
    expect(input).toEqual(
      expect.objectContaining({
        name: 'Prod gate',
        pipeline_id: null,
        clear_pipeline: true,
      }),
    );
    expect(createDeploymentRoutingPolicy).not.toHaveBeenCalled();
  });

  it('deletes a policy after confirming the Popconfirm', async () => {
    render(wrap(<PipelineRoutingPoliciesTab pipelineId="pipe-1" />));
    await screen.findByText('Prod gate');

    const rowDeletes = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(rowDeletes[0]!);

    await screen.findByText('Delete this policy?');
    const allDeletes = screen.getAllByRole('button', { name: 'Delete' });
    fireEvent.click(allDeletes[allDeletes.length - 1]!);

    await waitFor(() => expect(deleteDeploymentRoutingPolicy).toHaveBeenCalledTimes(1));
    expect(deleteDeploymentRoutingPolicy).toHaveBeenCalledWith('pol-1');
  });
});
