import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type { AnomalyPage, DashboardSummary, DashboardSuggestions, DigestSubscription, MyQueryTrends } from '@/types/api';

const {
  fetchSummary,
  fetchTrends,
  fetchSuggestions,
  dismissSuggestion,
  fetchDigest,
  setDigest,
  exportSummary,
  listMine,
} = vi.hoisted(() => ({
  fetchSummary: vi.fn(),
  fetchTrends: vi.fn(),
  fetchSuggestions: vi.fn(),
  dismissSuggestion: vi.fn(),
  fetchDigest: vi.fn(),
  setDigest: vi.fn(),
  exportSummary: vi.fn(),
  listMine: vi.fn(),
}));

vi.mock('@/api/dashboard', () => ({
  dashboardKeys: {
    summary: () => ['dashboard', 'summary'],
    trends: (f: unknown) => ['dashboard', 'trends', f],
    suggestions: () => ['dashboard', 'suggestions'],
    digestSubscription: () => ['dashboard', 'digest-subscription'],
  },
  fetchDashboardSummary: fetchSummary,
  fetchMyQueryTrends: fetchTrends,
  fetchDashboardSuggestions: fetchSuggestions,
  dismissDashboardSuggestion: dismissSuggestion,
  fetchDigestSubscription: fetchDigest,
  setDigestSubscription: setDigest,
  exportDashboardSummary: exportSummary,
}));

vi.mock('@/api/anomalies', async () => {
  const actual = await vi.importActual<typeof import('@/api/anomalies')>('@/api/anomalies');
  return { ...actual, listMyAnomalies: listMine };
});

vi.mock('@ant-design/charts', () => ({
  Line: ({ data }: { data: unknown[] }) => <div data-testid="ant-line-chart" data-points={data.length} />,
}));

import { useAuthStore } from '@/store/authStore';
import type { Role } from '@/types/api';

const { default: DashboardPage } = await import('./DashboardPage');

function setRole(role: Role) {
  useAuthStore.setState({
    user: {
      id: 'u-1',
      email: 'me@x.io',
      display_name: 'Me',
      role,
      auth_provider: 'LOCAL',
      totp_enabled: false,
      platform_admin: false,
      preferred_language: null,
    },
    accessToken: 'token',
  });
}

function summary(): DashboardSummary {
  return {
    pending_approvals_count: 3,
    open_queries_count: 7,
    open_anomalies_count: 1,
    open_suggestions_count: 2,
    status_counts: [],
    recent_queries: [
      {
        id: 'q1',
        datasource_id: 'ds1',
        datasource_name: 'Prod',
        query_type: 'SELECT',
        status: 'PENDING_REVIEW',
        ai_risk_level: 'LOW',
        ai_risk_score: 10,
        ai_failed: false,
        created_at: '2026-06-20T10:00:00Z',
      },
    ],
    recent_pending_approvals: [
      {
        query_request_id: 'q2',
        datasource_id: 'ds1',
        datasource_name: 'Prod',
        submitted_by_email: 'a@x.io',
        query_type: 'DELETE',
        ai_risk_level: 'HIGH',
        ai_risk_score: 80,
        current_stage: 1,
        created_at: '2026-06-20T11:00:00Z',
      },
    ],
  };
}

const emptyTrends: MyQueryTrends = { status_by_day: [], risk_by_day: [] };
const emptySuggestions: DashboardSuggestions = { suggestions: [] };
const disabledDigest: DigestSubscription = { enabled: false, last_sent_at: null };
const emptyMine: AnomalyPage = { content: [], page: 0, size: 10, total_elements: 0, total_pages: 0 };

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={['/dashboard']}>{children}</MemoryRouter>
      </App>
    </QueryClientProvider>
  );
  return render(<DashboardPage />, { wrapper });
}

describe('DashboardPage', () => {
  beforeEach(() => {
    fetchSummary.mockResolvedValue(summary());
    fetchTrends.mockResolvedValue(emptyTrends);
    fetchSuggestions.mockResolvedValue(emptySuggestions);
    fetchDigest.mockResolvedValue(disabledDigest);
    setDigest.mockResolvedValue({ enabled: true, last_sent_at: null });
    listMine.mockResolvedValue(emptyMine);
    exportSummary.mockResolvedValue({ blob: new Blob(['x']), filename: 'dashboard-summary.pdf' });
    // jsdom lacks object-URL helpers used by the export download.
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    // Reset persisted widget prefs so all widgets show.
    localStorage.clear();
    // ADMIN sees every widget (role gating, AF-498).
    setRole('ADMIN');
  });

  it('renders the summary counts and the four core widgets', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('dashboard-stat-pending')).toBeInTheDocument());
    expect(within(screen.getByTestId('dashboard-stat-pending')).getByText('3')).toBeInTheDocument();
    expect(within(screen.getByTestId('dashboard-stat-open')).getByText('7')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-pendingApprovals')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-recentQueries')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-suggestions')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-anomalies')).toBeInTheDocument();
  });

  it('hides widgets the role cannot use (ANALYST sees no pending-approvals or anomalies)', async () => {
    setRole('ANALYST');
    renderPage();
    // Wait for the summary to load (a stat card available to ANALYST appears).
    await waitFor(() => expect(screen.getByTestId('dashboard-stat-open')).toBeInTheDocument());
    expect(screen.getByTestId('dashboard-widget-recentQueries')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-suggestions')).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-widget-pendingApprovals')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-widget-anomalies')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-stat-pending')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-stat-anomalies')).not.toBeInTheDocument();
  });

  it('toggles the weekly digest opt-in', async () => {
    renderPage();
    const toggle = await screen.findByRole('switch', { name: /weekly email digest/i });
    // Wait for the subscription query to resolve so the switch leaves its loading state.
    await waitFor(() => expect(toggle).not.toHaveClass('ant-switch-loading'));
    fireEvent.click(toggle);
    await waitFor(() => expect(setDigest).toHaveBeenCalledWith(true));
  });

  it('exports the weekly summary as PDF', async () => {
    renderPage();
    const exportBtn = await screen.findByRole('button', { name: /export this week/i });
    fireEvent.click(exportBtn);
    const pdfItem = await screen.findByText(/export as pdf/i);
    fireEvent.click(pdfItem);
    await waitFor(() => expect(exportSummary).toHaveBeenCalledWith('PDF'));
  });
});
