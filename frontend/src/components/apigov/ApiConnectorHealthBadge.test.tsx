import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import '@/i18n';
import type { ApiConnectorHealth } from '@/types/api';

const { getApiConnectorHealth } = vi.hoisted(() => ({ getApiConnectorHealth: vi.fn() }));

vi.mock('@/api/apiConnectors', async () => {
  const actual = await vi.importActual<typeof import('@/api/apiConnectors')>('@/api/apiConnectors');
  return { ...actual, getApiConnectorHealth };
});

import { ApiConnectorHealthBadge } from './ApiConnectorHealthBadge';

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <ApiConnectorHealthBadge connectorId="c-1" />
      </AntdApp>
    </QueryClientProvider>,
  );
}

const monitored: ApiConnectorHealth = {
  status: 'UNHEALTHY',
  last_probe_at: '2026-05-04T10:00:00Z',
  consecutive_failures: 3,
  health_check_interval_minutes: 5,
  recent_checks: [
    {
      checked_at: '2026-05-04T10:00:00Z',
      success: false,
      message: 'Connection refused',
      duration_ms: 12,
    },
    { checked_at: '2026-05-04T09:55:00Z', success: true, message: 'HTTP 200', duration_ms: 40 },
  ],
};

describe('ApiConnectorHealthBadge (#896)', () => {
  beforeEach(() => {
    getApiConnectorHealth.mockReset();
  });

  it('reads "Not monitored" for a connector without an interval, never a red state', async () => {
    getApiConnectorHealth.mockResolvedValue({
      status: 'UNKNOWN',
      consecutive_failures: 0,
      recent_checks: [],
    });
    setup();

    expect(await screen.findByText('Not monitored')).toBeInTheDocument();
    expect(screen.queryByText('Unhealthy')).not.toBeInTheDocument();
    expect(getApiConnectorHealth.mock.calls[0]![0]).toBe('c-1');
  });

  it('shows the status and the last probe time when monitored', async () => {
    getApiConnectorHealth.mockResolvedValue(monitored);
    setup();

    expect(await screen.findByText('Unhealthy')).toBeInTheDocument();
    expect(screen.getByText(/^checked /)).toBeInTheDocument();
  });

  it('falls back to the status label in the tooltip when the newest check has no message', async () => {
    getApiConnectorHealth.mockResolvedValue({
      ...monitored,
      status: 'HEALTHY',
      recent_checks: [{ checked_at: '2026-05-04T10:00:00Z', success: true, duration_ms: 5 }],
    });
    setup();

    fireEvent.mouseEnter(await screen.findByRole('button', { name: /^Healthy/ }));

    expect(await screen.findByRole('tooltip')).toHaveTextContent('Healthy');
    expect(screen.queryByText('No health checks yet')).not.toBeInTheDocument();
  });

  it('says not checked yet before the first probe', async () => {
    getApiConnectorHealth.mockResolvedValue({
      ...monitored,
      status: 'UNKNOWN',
      last_probe_at: undefined,
      recent_checks: [],
    });
    setup();

    expect(await screen.findByText('not checked yet')).toBeInTheDocument();
  });

  it('opens a drawer with the recent checks', async () => {
    getApiConnectorHealth.mockResolvedValue(monitored);
    setup();

    // The button is named by its visible status, so screen readers hear the health.
    fireEvent.click(await screen.findByRole('button', { name: /^Unhealthy/ }));

    const drawer = await screen.findByRole('dialog');
    expect(within(drawer).getByText('Connection refused')).toBeInTheDocument();
    expect(within(drawer).getByText('HTTP 200')).toBeInTheDocument();
    expect(within(drawer).getByText('Failed')).toBeInTheDocument();
    expect(within(drawer).getByText('OK')).toBeInTheDocument();
    expect(within(drawer).getByText(/Checked every 5 min · consecutive failures: 3/)).toBeInTheDocument();
  });
});
