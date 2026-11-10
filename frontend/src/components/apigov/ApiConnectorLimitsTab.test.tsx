import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import { bklitChartMocks } from '@/components/dashboard/chartsTestMocks';
import type { ApiConnector, ApiConnectorUsage } from '@/types/api';

const { getApiConnector, updateApiConnector, getApiConnectorUsage } = vi.hoisted(() => ({
  getApiConnector: vi.fn(),
  updateApiConnector: vi.fn(),
  getApiConnectorUsage: vi.fn(),
}));

vi.mock('@/api/apiConnectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return { ...actual, getApiConnector, updateApiConnector };
});
vi.mock('@/api/apiConnectorUsage', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/apiConnectorUsage')>('@/api/apiConnectorUsage');
  return { ...actual, getApiConnectorUsage };
});
vi.mock('@/components/charts', () => bklitChartMocks());

const { ApiConnectorLimitsTab } = await import('./ApiConnectorLimitsTab');

function connector(overrides: Partial<ApiConnector> = {}): ApiConnector {
  return {
    id: 'c-1',
    name: 'Stripe',
    protocol: 'REST',
    base_url: 'https://api.stripe.com',
    default_headers: {},
    trace_header_mapping: {},
    timeout_ms: 30000,
    tls_verify: true,
    auth_method: 'NONE',
    has_credentials: false,
    oauth2_token_uri: null,
    oauth2_client_id: null,
    oauth2_scopes: null,
    oauth2_audience: null,
    oauth2_username: null,
    oauth2_grant_type: 'CLIENT_CREDENTIALS',
    oauth2_client_auth: 'CLIENT_SECRET_POST',
    oauth2_client_secret_configured: false,
    oauth2_refresh_token_configured: false,
    oauth2_password_configured: false,
    review_plan_id: null,
    ai_analysis_enabled: false,
    ai_config_id: null,
    text_to_api_enabled: false,
    require_review_reads: false,
    require_review_writes: true,
    max_response_bytes: 1048576,
    active: true,
    schema_present: false,
    created_at: '2026-11-01T00:00:00Z',
    ...overrides,
  };
}

function usage(overrides: Partial<ApiConnectorUsage> = {}): ApiConnectorUsage {
  return {
    connector_id: 'c-1',
    month_start: '2026-11-01',
    month_to_date_spend_micros: 2_500_000,
    monthly_budget_micros: 10_000_000,
    budget_used_percent: 25,
    cost_per_call_micros: 500_000,
    days: [{ date: '2026-11-02', calls: 5, failed_calls: 1, throttled_calls: 2, spend_micros: 2_500_000 }],
    ...overrides,
  };
}

function wrap(node: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={client}>
      <AntdApp>{node}</AntdApp>
    </QueryClientProvider>
  );
}

describe('ApiConnectorLimitsTab', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getApiConnector.mockResolvedValue(connector());
    updateApiConnector.mockResolvedValue(connector());
    getApiConnectorUsage.mockResolvedValue(usage());
  });

  it('submits 0 for every empty field, so the form clears them all', async () => {
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    fireEvent.click(await screen.findByText('Save limits'));
    await waitFor(() => expect(updateApiConnector).toHaveBeenCalled());
    expect(updateApiConnector.mock.calls[0]?.[0]).toBe('c-1');
    expect(updateApiConnector.mock.calls[0]?.[1]).toEqual({
      calls_per_minute: 0,
      calls_per_day: 0,
      per_user_share_percent: 0,
      cost_per_call_micros: 0,
      monthly_budget_micros: 0,
    });
  });

  it('prefills from the connector and submits money as micros', async () => {
    getApiConnector.mockResolvedValue(
      connector({
        calls_per_minute: 60,
        calls_per_day: 1000,
        per_user_share_percent: 25,
        cost_per_call_micros: 500_000,
        monthly_budget_micros: 100_000_000,
      }),
    );
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    const cost = await screen.findByLabelText('Cost per call (units)');
    expect(cost).toHaveValue('0.500000');
    fireEvent.change(cost, { target: { value: '0.0125' } });
    fireEvent.click(screen.getByText('Save limits'));
    await waitFor(() => expect(updateApiConnector).toHaveBeenCalled());
    expect(updateApiConnector.mock.calls[0]?.[1]).toEqual({
      calls_per_minute: 60,
      calls_per_day: 1000,
      per_user_share_percent: 25,
      cost_per_call_micros: 12_500,
      monthly_budget_micros: 100_000_000,
    });
    expect(await screen.findByText('Limits saved')).toBeInTheDocument();
  });

  it('rejects a per-user share above 100', async () => {
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    const share = await screen.findByLabelText('Per-user share');
    fireEvent.change(share, { target: { value: '101' } });
    fireEvent.click(screen.getByText('Save limits'));
    expect(await screen.findByText('Must be between 0 and 100')).toBeInTheDocument();
    expect(updateApiConnector).not.toHaveBeenCalled();
  });

  it('surfaces the server detail when saving fails', async () => {
    updateApiConnector.mockRejectedValue(new Error('Limit rejected'));
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    fireEvent.click(await screen.findByText('Save limits'));
    expect(await screen.findByText('Limit rejected')).toBeInTheDocument();
  });

  it('mounts the usage chart with three series and the budget ring', async () => {
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    expect(await screen.findByText('25% of the monthly budget used')).toBeInTheDocument();
    expect(screen.getByTestId('bklit-area-chart')).toBeInTheDocument();
    const keys = screen.getAllByTestId('bklit-area').map((el) => el.getAttribute('data-key'));
    expect(keys).toEqual(['Calls', 'Failed', 'Throttled']);
    expect(screen.getByTestId('bklit-ring-chart')).toHaveAttribute('data-total', '25');
    expect(screen.getByText('2.5 of 10 units')).toBeInTheDocument();
    expect(screen.queryByText('Budget exceeded')).not.toBeInTheDocument();
  });

  it('tags an exceeded budget at 100% and caps the ring', async () => {
    getApiConnectorUsage.mockResolvedValue(usage({ budget_used_percent: 130 }));
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    expect(await screen.findByText('Budget exceeded')).toBeInTheDocument();
    expect(screen.getByTestId('bklit-ring-chart')).toHaveAttribute('data-total', '100');
  });

  it('shows plain month-to-date spend when there is no budget or price', async () => {
    getApiConnectorUsage.mockResolvedValue(
      usage({ budget_used_percent: null, monthly_budget_micros: null }),
    );
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    expect(await screen.findByTestId('connector-usage-spend')).toHaveTextContent('2.5 units');
    expect(screen.getByText('No monthly budget or per-call price set.')).toBeInTheDocument();
    expect(screen.queryByTestId('bklit-ring-chart')).not.toBeInTheDocument();
  });

  it('asks for a price when a budget is set but the connector has none', async () => {
    getApiConnectorUsage.mockResolvedValue(usage({ budget_used_percent: null, cost_per_call_micros: null }));
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    expect(
      await screen.findByText('Set a cost per call to track spend against this budget.'),
    ).toBeInTheDocument();
  });

  it('renders the empty state when every zero-filled day has no activity', async () => {
    getApiConnectorUsage.mockResolvedValue(
      usage({
        days: [
          { date: '2026-11-01', calls: 0, failed_calls: 0, throttled_calls: 0, spend_micros: 0 },
          { date: '2026-11-02', calls: 0, failed_calls: 0, throttled_calls: 0, spend_micros: 0 },
        ],
      }),
    );
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    expect(await screen.findByText('No calls in this period')).toBeInTheDocument();
    expect(screen.queryByTestId('bklit-area-chart')).not.toBeInTheDocument();
  });

  it('refetches usage for the chosen range', async () => {
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    await screen.findByTestId('bklit-area-chart');
    fireEvent.click(screen.getByText('7 days'));
    await waitFor(() => expect(getApiConnectorUsage).toHaveBeenCalledTimes(2));
    const range = getApiConnectorUsage.mock.calls[1]?.[1] as { from: string; to: string };
    const days = (Date.parse(range.to) - Date.parse(range.from)) / 86_400_000;
    expect(days).toBe(6);
  });

  it('shows a retryable error when usage fails to load', async () => {
    getApiConnectorUsage.mockRejectedValue(new Error('usage down'));
    render(wrap(<ApiConnectorLimitsTab connectorId="c-1" />));
    expect(await screen.findByText('usage down')).toBeInTheDocument();
  });
});
