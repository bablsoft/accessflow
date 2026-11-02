import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { AxiosError, type AxiosResponse } from 'axios';
import type { ReactNode } from 'react';
import '@/i18n';
import { useAuthStore } from '@/store/authStore';
import type { Permission } from '@/utils/permissions';
import type { DeploymentEnvironmentMetrics, DeploymentMetrics } from '@/types/api';
import { bklitChartMocks } from '@/components/dashboard/chartsTestMocks';

const mocks = vi.hoisted(() => ({
  getOrganizationDeploymentMetrics: vi.fn(),
  getPipelineDeploymentMetrics: vi.fn(),
  getDeploymentEnvironmentMetrics: vi.fn(),
  listDeploymentPipelines: vi.fn(),
  listPipelineEnvironmentVersions: vi.fn(),
}));

vi.mock('@/components/charts', () => bklitChartMocks());

vi.mock('@/api/deploymentMetrics', () => ({
  getOrganizationDeploymentMetrics: mocks.getOrganizationDeploymentMetrics,
  getPipelineDeploymentMetrics: mocks.getPipelineDeploymentMetrics,
  getDeploymentEnvironmentMetrics: mocks.getDeploymentEnvironmentMetrics,
  deploymentMetricKeys: {
    organization: (f: unknown) => ['deployment-metrics', 'organization', f] as const,
    pipeline: (id: string, f: unknown) => ['deployment-metrics', 'pipeline', id, f] as const,
    breakdown: (f: unknown) => ['deployment-metrics', 'breakdown', f] as const,
  },
}));

vi.mock('@/api/deploymentPipelines', () => ({
  listDeploymentPipelines: mocks.listDeploymentPipelines,
  deploymentPipelineKeys: {
    list: (f: unknown) => ['deployment-pipelines', 'list', f] as const,
  },
}));

vi.mock('@/api/deploymentVersions', () => ({
  listPipelineEnvironmentVersions: mocks.listPipelineEnvironmentVersions,
  deploymentVersionKeys: {
    matrix: (id: string) => ['deployment-versions', 'matrix', id] as const,
  },
}));

const { DeploymentMetricsPanel } = await import('./DeploymentMetricsPanel');

function metrics(over: Partial<DeploymentMetrics> = {}): DeploymentMetrics {
  return {
    period: { from: '2026-10-01T00:00:00Z', to: '2026-10-03T00:00:00Z' },
    deployment_frequency: { total: 4, per_day: 2 },
    lead_time: { p50_seconds: 4320, p95_seconds: 273_600, sample_size: 4, source: 'SUBMITTED_AT' },
    change_failure_rate: { rate: 0.5, failed: 1, rolled_back: 1, unreported: 2, executed: 4 },
    time_to_restore: { restored: 0, open_incidents: 2 },
    approval_latency: { p50_seconds: 900, p95_seconds: 3600, sample_size: 3 },
    timeout_rate: { rate: 0.25, timed_out: 1, entered_review: 4 },
    break_glass_count: 1,
    daily: [
      { date: '2026-10-01', deployments: 2, failed: 1, rolled_back: 0 },
      { date: '2026-10-02', deployments: 2, failed: 0, rolled_back: 1 },
    ],
    ...over,
  };
}

function breakdownRow(over: Partial<DeploymentEnvironmentMetrics> = {}): DeploymentEnvironmentMetrics {
  const base = metrics();
  return {
    pipeline_id: 'pipe-1',
    pipeline_name: 'Checkout Service',
    environment_id: 'env-1',
    environment_name: 'production',
    deployment_frequency: base.deployment_frequency,
    lead_time: base.lead_time,
    change_failure_rate: base.change_failure_rate,
    time_to_restore: base.time_to_restore,
    approval_latency: base.approval_latency,
    timeout_rate: base.timeout_rate,
    break_glass_count: 0,
    ...over,
  };
}

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

function problem(status: number, data: Record<string, unknown>): AxiosError {
  const response = { data, status, statusText: '', headers: {}, config: {} as never } as AxiosResponse;
  return new AxiosError('failed', undefined, undefined, undefined, response);
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

async function chooseOption(ariaLabel: string, optionText: string) {
  fireEvent.mouseDown(screen.getByLabelText(ariaLabel));
  fireEvent.click(await screen.findByTitle(optionText));
}

const value = (testId: string) => screen.getByTestId(`${testId}-value`).textContent;

describe('DeploymentMetricsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    seedUser(['DEPLOYMENT_REVIEW']);
    mocks.getOrganizationDeploymentMetrics.mockResolvedValue(metrics());
    mocks.getPipelineDeploymentMetrics.mockResolvedValue(metrics());
    mocks.getDeploymentEnvironmentMetrics.mockResolvedValue({
      period: metrics().period,
      environments: [
        breakdownRow(),
        breakdownRow({
          environment_id: 'env-2',
          environment_name: 'staging',
          deployment_frequency: { total: 7, per_day: 3.5 },
        }),
      ],
    });
    mocks.listDeploymentPipelines.mockResolvedValue({
      content: [{ id: 'pipe-1', name: 'Checkout Service' }],
    });
    mocks.listPipelineEnvironmentVersions.mockResolvedValue([
      { pipeline_name: 'Checkout Service', environment: { id: 'env-9', name: 'canary' } },
    ]);
  });

  it('shows a skeleton while the metrics load', () => {
    mocks.getOrganizationDeploymentMetrics.mockReturnValue(new Promise(() => {}));
    const { container } = render(wrap(<DeploymentMetricsPanel />));
    expect(container.querySelector('.ant-skeleton')).toBeInTheDocument();
  });

  it('renders the server detail on failure', async () => {
    mocks.getOrganizationDeploymentMetrics.mockRejectedValue(
      problem(400, { detail: 'The window is longer than 366 days' }),
    );
    render(wrap(<DeploymentMetricsPanel />));
    expect(await screen.findByText('The window is longer than 366 days')).toBeInTheDocument();
  });

  it('shows the empty state when nothing executed in the window', async () => {
    mocks.getOrganizationDeploymentMetrics.mockResolvedValue(
      metrics({ deployment_frequency: { total: 0, per_day: 0 } }),
    );
    render(wrap(<DeploymentMetricsPanel />));
    expect(await screen.findByText('No deployments in this period')).toBeInTheDocument();
    expect(screen.queryByTestId('metric-frequency')).not.toBeInTheDocument();
  });

  it('renders every tile from the server values, dashing missing percentiles', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    await screen.findByTestId('metric-frequency');

    expect(value('metric-frequency')).toBe('4');
    expect(screen.getByText('2 per day')).toBeInTheDocument();
    expect(value('metric-lead-time')).toBe('1h 12m');
    expect(screen.getByText('p95 3d 4h')).toBeInTheDocument();
    expect(value('metric-change-failure')).toBe('50%');
    expect(screen.getByTestId('metric-unreported')).toHaveTextContent('Outcome not reported: 2');
    // time_to_restore has no percentiles on the wire — the backend omits nulls.
    expect(value('metric-restore')).toBe('—');
    expect(screen.getByText('Still open: 2')).toBeInTheDocument();
    expect(value('metric-approval')).toBe('15m');
    expect(value('metric-timeout')).toBe('25%');
    expect(value('metric-break-glass')).toBe('1');
  });

  it('captions the lead time with its source', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    expect(await screen.findByTestId('metric-lead-time-source')).toHaveTextContent(
      'From submission time — set commit_timestamp in the deployment metadata',
    );
  });

  it('omits the source caption and the unreported caveat when there is nothing to say', async () => {
    mocks.getOrganizationDeploymentMetrics.mockResolvedValue(
      metrics({
        lead_time: { sample_size: 0 },
        change_failure_rate: { failed: 0, rolled_back: 0, unreported: 0, executed: 4 },
      }),
    );
    render(wrap(<DeploymentMetricsPanel />));
    await screen.findByTestId('metric-frequency');
    expect(screen.queryByTestId('metric-lead-time-source')).not.toBeInTheDocument();
    expect(screen.queryByTestId('metric-unreported')).not.toBeInTheDocument();
    expect(value('metric-change-failure')).toBe('—');
  });

  it('charts the daily series and the failure ring', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    const chart = await screen.findByTestId('bklit-area-chart');
    expect(chart).toHaveAttribute('data-rows', '2');
    expect(
      within(chart)
        .getAllByTestId('bklit-area')
        .map((area) => area.getAttribute('data-key')),
    ).toEqual(['Deployments', 'Failed', 'Rolled back']);
    expect(screen.getByTestId('bklit-ring-chart')).toHaveAttribute('data-rings', '2');
    expect(screen.getByTestId('metric-ring-rate')).toHaveTextContent('50%');
  });

  it('lists the per-environment breakdown linking to the version matrix', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    const breakdown = await screen.findByTestId('metrics-breakdown');
    await within(breakdown).findByText('staging');
    expect(within(breakdown).getByText('production')).toBeInTheDocument();
    expect(within(breakdown).getAllByText('Checkout Service')[0]?.closest('a')).toHaveAttribute(
      'href',
      '/deployment-versions/pipe-1',
    );
    expect(within(breakdown).getByText('7')).toBeInTheDocument();
  });

  it('shows a breakdown error without hiding the tiles', async () => {
    mocks.getDeploymentEnvironmentMetrics.mockRejectedValue(problem(500, { detail: 'boom' }));
    render(wrap(<DeploymentMetricsPanel />));
    const breakdown = await screen.findByTestId('metrics-breakdown');
    expect(await within(breakdown).findByText('boom')).toBeInTheDocument();
    expect(screen.getByTestId('metric-frequency')).toBeInTheDocument();
  });

  it('hides the pipeline picker without DEPLOYMENT_PIPELINE_MANAGE', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    await screen.findByTestId('metric-frequency');
    expect(screen.queryByLabelText('Pipeline')).not.toBeInTheDocument();
    expect(mocks.listDeploymentPipelines).not.toHaveBeenCalled();
  });

  it('narrows both reads by the picked pipeline for a pipeline manager', async () => {
    seedUser(['DEPLOYMENT_PIPELINE_MANAGE']);
    render(wrap(<DeploymentMetricsPanel />));
    await screen.findByTestId('metric-frequency');

    await chooseOption('Pipeline', 'Checkout Service');

    await waitFor(() => expect(mocks.getOrganizationDeploymentMetrics).toHaveBeenCalledTimes(2));
    expect(mocks.getOrganizationDeploymentMetrics.mock.calls[1]?.[0]).toMatchObject({
      pipeline_id: 'pipe-1',
    });
    await waitFor(() => expect(mocks.getDeploymentEnvironmentMetrics).toHaveBeenCalledTimes(2));
    expect(mocks.getDeploymentEnvironmentMetrics.mock.calls[1]?.[0]).toMatchObject({
      pipeline_id: 'pipe-1',
    });
  });

  it('sends the environment to the metrics read and narrows the breakdown rows', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    await screen.findByTestId('metric-frequency');
    const first = mocks.getOrganizationDeploymentMetrics.mock.calls[0]?.[0];
    expect(first).toMatchObject({ environment: undefined, pipeline_id: undefined });
    expect(typeof first.from).toBe('string');
    expect(typeof first.to).toBe('string');

    await chooseOption('Environment', 'staging');

    await waitFor(() => expect(mocks.getOrganizationDeploymentMetrics).toHaveBeenCalledTimes(2));
    expect(mocks.getOrganizationDeploymentMetrics.mock.calls[1]?.[0]).toMatchObject({
      environment: 'staging',
    });
    const breakdown = screen.getByTestId('metrics-breakdown');
    await waitFor(() => expect(within(breakdown).queryByText('production')).not.toBeInTheDocument());
    // The breakdown read itself never carries the environment — it also feeds the options.
    expect(mocks.getDeploymentEnvironmentMetrics).toHaveBeenCalledTimes(1);
  });

  it('re-reads over the chosen period preset', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    await screen.findByTestId('metric-frequency');
    const thirty = mocks.getOrganizationDeploymentMetrics.mock.calls[0]?.[0];

    await chooseOption('Period', 'Last 7 days');

    await waitFor(() => expect(mocks.getOrganizationDeploymentMetrics).toHaveBeenCalledTimes(2));
    const seven = mocks.getOrganizationDeploymentMetrics.mock.calls[1]?.[0];
    expect(seven.to).toBe(thirty.to);
    expect(Date.parse(seven.to) - Date.parse(seven.from)).toBe(7 * 86_400_000);
  });

  it('opens a range picker for a custom period', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    await screen.findByTestId('metric-frequency');

    await chooseOption('Period', 'Custom range');

    // The picker opens on the trailing 30 days, so its first window is the preset's.
    await waitFor(() => expect(document.querySelector('.ant-picker-range')).toBeInTheDocument());
    const custom = mocks.getOrganizationDeploymentMetrics.mock.calls.at(-1)?.[0];
    expect(Date.parse(custom.to) - Date.parse(custom.from)).toBe(30 * 86_400_000);
  });

  it('sends the picked custom range as UTC days', async () => {
    render(wrap(<DeploymentMetricsPanel />));
    await screen.findByTestId('metric-frequency');
    await chooseOption('Period', 'Custom range');
    await waitFor(() => expect(document.querySelector('.ant-picker-range')).toBeInTheDocument());

    const [startInput, endInput] = Array.from(
      document.querySelectorAll<HTMLInputElement>('.ant-picker-range input'),
    );
    fireEvent.mouseDown(startInput!);
    fireEvent.focus(startInput!);
    fireEvent.change(startInput!, { target: { value: '2026-10-01' } });
    fireEvent.keyDown(startInput!, { key: 'Enter', code: 'Enter' });
    fireEvent.focus(endInput!);
    fireEvent.change(endInput!, { target: { value: '2026-10-03' } });
    fireEvent.keyDown(endInput!, { key: 'Enter', code: 'Enter' });

    await waitFor(() =>
      expect(mocks.getOrganizationDeploymentMetrics.mock.calls.at(-1)?.[0]).toMatchObject({
        from: '2026-10-01T00:00:00.000Z',
        to: '2026-10-04T00:00:00.000Z',
      }),
    );
  });

  it('reads one pipeline with environment options from its matrix, without a breakdown', async () => {
    render(wrap(<DeploymentMetricsPanel pipelineId="pipe-7" />));
    await screen.findByTestId('metric-frequency');

    expect(mocks.getPipelineDeploymentMetrics.mock.calls[0]?.[0]).toBe('pipe-7');
    expect(mocks.getOrganizationDeploymentMetrics).not.toHaveBeenCalled();
    expect(mocks.getDeploymentEnvironmentMetrics).not.toHaveBeenCalled();
    expect(screen.queryByTestId('metrics-breakdown')).not.toBeInTheDocument();

    await chooseOption('Environment', 'canary');
    await waitFor(() => expect(mocks.getPipelineDeploymentMetrics).toHaveBeenCalledTimes(2));
    expect(mocks.getPipelineDeploymentMetrics.mock.calls[1]?.[1]).toMatchObject({
      environment: 'canary',
    });
  });
});
