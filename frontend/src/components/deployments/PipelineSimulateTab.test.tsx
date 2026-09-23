import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { Permission } from '@/utils/permissions';
import type { DeploymentSimulationResult } from '@/types/api';

const { simulateMock, listUsersMock, listEnvironmentsMock } = vi.hoisted(() => ({
  simulateMock: vi.fn(),
  listUsersMock: vi.fn(),
  listEnvironmentsMock: vi.fn(),
}));

vi.mock('@/api/deploymentSimulations', () => ({ simulateDeployment: simulateMock }));
vi.mock('@/api/admin', async () => {
  const actual = await vi.importActual<typeof import('@/api/admin')>('@/api/admin');
  return { ...actual, listUsers: listUsersMock };
});
vi.mock('@/api/deploymentPipelines', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/deploymentPipelines')>('@/api/deploymentPipelines');
  return { ...actual, listDeploymentEnvironments: listEnvironmentsMock };
});

const { PipelineSimulateTab } = await import('./PipelineSimulateTab');

function signIn(permissions: Permission[]) {
  useAuthStore.setState({
    user: {
      id: 'admin-1',
      email: 'admin@x.io',
      display_name: 'Admin',
      role: 'ADMIN',
      role_id: null,
      permissions,
      auth_provider: 'LOCAL',
      totp_enabled: false,
      platform_admin: false,
      preferred_language: null,
      governs_apis: true,
      governs_deployments: true,
    },
    accessToken: 't',
  });
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <App>{node}</App>
    </QueryClientProvider>
  );
}

async function pick(label: string | RegExp, option: string) {
  fireEvent.mouseDown(screen.getByLabelText(label));
  await waitFor(() =>
    expect(
      [...document.querySelectorAll('.ant-select-item-option-content')].some(
        (o) => o.textContent === option,
      ),
    ).toBe(true),
  );
  const el = [...document.querySelectorAll('.ant-select-item-option-content')].find(
    (o) => o.textContent === option,
  );
  fireEvent.click(el!);
}

async function fill() {
  await pick(/^Environment/, 'production');
  await pick(/^Simulated user/, 'Dev (dev@x.io)');
  fireEvent.change(screen.getByLabelText(/^Version/), { target: { value: ' 1.4.0 ' } });
}

function result(releasable: boolean): DeploymentSimulationResult {
  return {
    resulting_status: releasable ? 'APPROVED' : 'PENDING_REVIEW',
    releasable,
    evaluated_at: '2026-09-25T18:00:00Z',
    caveats: [],
    steps: [
      { step: 'FREEZE_WINDOW', outcome: releasable ? 'NO_MATCH' : 'MATCH', reason: 'Friday freeze', details: { behavior: 'HOLD', scope: 'PIPELINE' } },
      { step: 'GATE_RELEASABILITY', outcome: releasable ? 'ALLOW' : 'DENY', reason: 'gate', details: { releasable, frozen: !releasable } },
    ],
  };
}

describe('PipelineSimulateTab', () => {
  beforeEach(() => {
    signIn(['DEPLOYMENT_PIPELINE_MANAGE', 'USER_MANAGE']);
    simulateMock.mockReset();
    listUsersMock.mockReset();
    listEnvironmentsMock.mockReset();
    listUsersMock.mockResolvedValue({
      content: [{ id: 'u-1', email: 'dev@x.io', display_name: 'Dev', active: true }],
      page: 0,
      size: 100,
      total_elements: 1,
      total_pages: 1,
    });
    listEnvironmentsMock.mockResolvedValue([
      { id: 'e-2', pipeline_id: 'p-1', name: 'production', tags: [], sort_order: 2, require_review: true },
      { id: 'e-1', pipeline_id: 'p-1', name: 'staging', tags: [], sort_order: 1, require_review: false },
    ]);
  });

  it('requires environment, user and version before simulating', async () => {
    render(wrap(<PipelineSimulateTab pipelineId="p-1" />));
    fireEvent.click(await screen.findByRole('button', { name: 'Simulate' }));
    expect(await screen.findByText('Enter a version')).toBeInTheDocument();
    expect(simulateMock).not.toHaveBeenCalled();
  });

  it('surfaces a held release prominently above the trace', async () => {
    simulateMock.mockResolvedValue(result(false));
    render(wrap(<PipelineSimulateTab pipelineId="p-1" />));
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Simulate' }));

    const banner = await screen.findByTestId('releasable-banner');
    expect(banner).toHaveTextContent('Not releasable — the gate would hold this deployment');
    expect(banner).toHaveTextContent('same function the CI deployment gate blocks on');
    expect(simulateMock.mock.calls[0]![0]).toEqual({
      user_id: 'u-1',
      pipeline_id: 'p-1',
      environment_id: 'e-2',
      version: '1.4.0',
      ai_outcome: 'SKIPPED',
    });
    expect(screen.getByText('Gate releasability')).toBeInTheDocument();
    expect(screen.getByText('Friday freeze')).toBeInTheDocument();
  });

  it('reports a releasable deployment', async () => {
    simulateMock.mockResolvedValue(result(true));
    render(wrap(<PipelineSimulateTab pipelineId="p-1" />));
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Simulate' }));
    expect(await screen.findByTestId('releasable-banner')).toHaveTextContent(
      'Releasable — the gate would let this deployment through',
    );
  });

  it('shows the server detail when the simulation fails', async () => {
    simulateMock.mockRejectedValue({
      isAxiosError: true,
      response: { status: 404, data: { detail: 'Deployment environment not found' } },
    });
    render(wrap(<PipelineSimulateTab pipelineId="p-1" />));
    await fill();
    fireEvent.click(screen.getByRole('button', { name: 'Simulate' }));
    expect(await screen.findByText('Deployment environment not found')).toBeInTheDocument();
  });
});
