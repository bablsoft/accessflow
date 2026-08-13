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

const { RiskRingWidget } = await import('./RiskRingWidget');

const trends: MyQueryTrends = {
  status_by_day: [],
  risk_by_day: [
    { date: '2026-08-01', risk_level: 'LOW', count: 6 },
    { date: '2026-08-02', risk_level: 'LOW', count: 2 },
    { date: '2026-08-02', risk_level: 'HIGH', count: 2 },
  ],
};

function renderWidget() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<RiskRingWidget />, { wrapper });
}

describe('RiskRingWidget', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchTrends.mockResolvedValue(trends);
  });

  it('renders one ring per non-zero risk level with the total in the center', async () => {
    renderWidget();
    // Await the legend (rendered from loaded data) before asserting on the chart stub —
    // the ring chart also renders during the loading phase with zero rings.
    expect(await screen.findByText('High')).toBeInTheDocument();
    expect(screen.getByTestId('bklit-ring-chart')).toHaveAttribute('data-rings', '2');
    expect(screen.getByTestId('bklit-ring-center')).toHaveTextContent('10');
    // Legend rows: summed count per level.
    expect(screen.getByText('8')).toBeInTheDocument();
  });

  it('shows the compact empty state when nothing was analyzed', async () => {
    fetchTrends.mockResolvedValue({ status_by_day: [], risk_by_day: [] });
    renderWidget();
    expect(await screen.findByText(/no analyzed queries/i)).toBeInTheDocument();
  });

  it('shows the error block on failure', async () => {
    fetchTrends.mockRejectedValue(new Error('boom'));
    renderWidget();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
