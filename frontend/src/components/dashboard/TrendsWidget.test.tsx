import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import '@/i18n';
import { bklitChartMocks } from './chartsTestMocks';
import type { MyQueryTrends } from '@/types/api';

const { fetchTrends, fetchApiTrends } = vi.hoisted(() => ({
  fetchTrends: vi.fn(),
  fetchApiTrends: vi.fn(),
}));

vi.mock('@/api/dashboard', () => ({
  dashboardKeys: {
    trends: (f: unknown) => ['dashboard', 'trends', f],
    apiRequestTrends: (f: unknown) => ['dashboard', 'api-request-trends', f],
  },
  fetchMyQueryTrends: fetchTrends,
  fetchMyApiRequestTrends: fetchApiTrends,
}));

vi.mock('@/components/charts', () => bklitChartMocks());

import { usePreferencesStore } from '@/store/preferencesStore';
import { trendsFiltersForRange } from '@/utils/trendSeries';

const { TrendsWidget } = await import('./TrendsWidget');

const trends: MyQueryTrends = {
  status_by_day: [
    { date: '2026-08-01', status: 'EXECUTED', count: 3 },
    { date: '2026-08-02', status: 'REJECTED', count: 1 },
  ],
  risk_by_day: [{ date: '2026-08-01', risk_level: 'LOW', count: 4 }],
};

function renderWidget(kind: 'queries' | 'apiRequests' = 'queries') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<TrendsWidget kind={kind} />, { wrapper });
}

describe('trendsFiltersForRange', () => {
  it('anchors the window at UTC day granularity with an exclusive tomorrow end', () => {
    const now = new Date('2026-08-12T15:30:00Z');
    const { from, to } = trendsFiltersForRange('7d', now);
    expect(to).toBe('2026-08-13T00:00:00.000Z');
    expect(from).toBe('2026-08-06T00:00:00.000Z');
  });
});

describe('TrendsWidget', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchTrends.mockResolvedValue(trends);
    fetchApiTrends.mockResolvedValue(trends);
    usePreferencesStore.setState({ dashboardTrendsRange: '30d' });
  });

  it('renders a gradient area per status series with dense rows', async () => {
    renderWidget();
    const areas = await screen.findAllByTestId('bklit-area');
    expect(areas.map((a) => a.getAttribute('data-key'))).toEqual(['Executed', 'Rejected']);
    expect(screen.getByTestId('bklit-area-chart')).toHaveAttribute('data-rows', '30');
    // Legend dots carry the same labels.
    expect(screen.getByText('Executed')).toBeInTheDocument();
    expect(fetchTrends).toHaveBeenCalledWith(
      expect.objectContaining({ from: expect.any(String), to: expect.any(String) }),
    );
  });

  it('switches to the risk metric without refetching', async () => {
    renderWidget();
    await screen.findByTestId('bklit-area-chart');
    fireEvent.click(screen.getByText('By risk'));
    await waitFor(() =>
      expect(screen.getAllByTestId('bklit-area').map((a) => a.getAttribute('data-key'))).toEqual([
        'Low',
      ]),
    );
    expect(fetchTrends).toHaveBeenCalledTimes(1);
  });

  it('changing the range persists the preference and refetches a narrower window', async () => {
    renderWidget();
    await screen.findByTestId('bklit-area-chart');
    fireEvent.click(screen.getByText('7d'));
    await waitFor(() => expect(fetchTrends).toHaveBeenCalledTimes(2));
    expect(usePreferencesStore.getState().dashboardTrendsRange).toBe('7d');
    const first = fetchTrends.mock.calls[0]?.[0] as { from: string };
    const second = fetchTrends.mock.calls[1]?.[0] as { from: string };
    expect(new Date(second.from).getTime()).toBeGreaterThan(new Date(first.from).getTime());
  });

  it('uses the api-request endpoint and empty message for kind=apiRequests', async () => {
    fetchApiTrends.mockResolvedValue({ status_by_day: [], risk_by_day: [] });
    renderWidget('apiRequests');
    expect(await screen.findByText(/no api request activity/i)).toBeInTheDocument();
    expect(fetchApiTrends).toHaveBeenCalled();
    expect(fetchTrends).not.toHaveBeenCalled();
  });

  it('shows the compact empty state when there is no activity', async () => {
    fetchTrends.mockResolvedValue({ status_by_day: [], risk_by_day: [] });
    renderWidget();
    expect(await screen.findByText(/no query activity/i)).toBeInTheDocument();
  });

  it('shows the error block with retry when the fetch fails', async () => {
    fetchTrends.mockRejectedValue(new Error('boom'));
    renderWidget();
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    fetchTrends.mockResolvedValue(trends);
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    expect(await screen.findByTestId('bklit-area-chart')).toBeInTheDocument();
  });
});
