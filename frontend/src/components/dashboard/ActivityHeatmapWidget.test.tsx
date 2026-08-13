import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import { bklitChartMocks } from './chartsTestMocks';
import type { MyQueryTrends } from '@/types/api';

const { fetchTrends } = vi.hoisted(() => ({ fetchTrends: vi.fn() }));

vi.mock('@/api/dashboard', () => ({
  dashboardKeys: { trends: (f: unknown) => ['dashboard', 'trends', f] },
  fetchMyQueryTrends: fetchTrends,
}));

vi.mock('@/components/charts', () => bklitChartMocks());

const { ActivityHeatmapWidget } = await import('./ActivityHeatmapWidget');

function activity(): MyQueryTrends {
  const today = new Date().toISOString().slice(0, 10);
  return {
    status_by_day: [{ date: today, status: 'EXECUTED', count: 5 }],
    risk_by_day: [],
  };
}

function renderWidget() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<ActivityHeatmapWidget />, { wrapper });
}

describe('ActivityHeatmapWidget', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchTrends.mockResolvedValue(activity());
  });

  it('renders weekly columns over the 90-day window', async () => {
    renderWidget();
    const chart = await screen.findByTestId('bklit-heatmap-chart');
    const columns = Number(chart.getAttribute('data-columns'));
    // 90 days spans 13–14 Monday-anchored columns depending on the weekday of `from`.
    expect(columns).toBeGreaterThanOrEqual(13);
    expect(columns).toBeLessThanOrEqual(14);
    expect(fetchTrends).toHaveBeenCalledWith(
      expect.objectContaining({ from: expect.any(String), to: expect.any(String) }),
    );
  });

  it('shows the compact empty state when the window has no activity', async () => {
    fetchTrends.mockResolvedValue({ status_by_day: [], risk_by_day: [] });
    renderWidget();
    expect(await screen.findByText(/no query activity in the last 90 days/i)).toBeInTheDocument();
  });

  it('shows the error block on failure', async () => {
    fetchTrends.mockRejectedValue(new Error('boom'));
    renderWidget();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
