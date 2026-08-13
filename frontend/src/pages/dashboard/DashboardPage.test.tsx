import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from 'antd';
import type { ReactNode } from 'react';
import '@/i18n';
import type {
  AccessRequestPage,
  AnomalyPage,
  AttestationItemPage,
  DashboardSummary,
  DashboardSuggestions,
  DigestSubscription,
  MyQueryTrends,
  RequestGroupPage,
} from '@/types/api';

const {
  fetchSummary,
  fetchTrends,
  fetchApiTrends,
  fetchSuggestions,
  dismissSuggestion,
  fetchDigest,
  setDigest,
  exportSummary,
  listMine,
  listWorklist,
  listAccess,
  listGroups,
} = vi.hoisted(() => ({
  fetchSummary: vi.fn(),
  fetchTrends: vi.fn(),
  fetchApiTrends: vi.fn(),
  fetchSuggestions: vi.fn(),
  dismissSuggestion: vi.fn(),
  fetchDigest: vi.fn(),
  setDigest: vi.fn(),
  exportSummary: vi.fn(),
  listMine: vi.fn(),
  listWorklist: vi.fn(),
  listAccess: vi.fn(),
  listGroups: vi.fn(),
}));

vi.mock('@/api/dashboard', () => ({
  dashboardKeys: {
    all: ['dashboard'],
    summary: () => ['dashboard', 'summary'],
    trends: (f: unknown) => ['dashboard', 'trends', f],
    apiRequestTrends: (f: unknown) => ['dashboard', 'api-request-trends', f],
    suggestions: () => ['dashboard', 'suggestions'],
    digestSubscription: () => ['dashboard', 'digest-subscription'],
  },
  fetchDashboardSummary: fetchSummary,
  fetchMyQueryTrends: fetchTrends,
  fetchMyApiRequestTrends: fetchApiTrends,
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

vi.mock('@/api/attestation', async () => {
  const actual = await vi.importActual<typeof import('@/api/attestation')>('@/api/attestation');
  return { ...actual, listAttestationWorklist: listWorklist };
});

vi.mock('@/api/accessRequests', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/accessRequests')>('@/api/accessRequests');
  return { ...actual, listMyAccessRequests: listAccess };
});

vi.mock('@/api/requestGroups', async () => {
  const actual =
    await vi.importActual<typeof import('@/api/requestGroups')>('@/api/requestGroups');
  return { ...actual, listRequestGroups: listGroups };
});

vi.mock('@/components/charts', async () => {
  const { bklitChartMocks } = await import('@/components/dashboard/chartsTestMocks');
  return bklitChartMocks();
});

import { useAuthStore } from '@/store/authStore';
import { usePreferencesStore } from '@/store/preferencesStore';
import { SYSTEM_ROLE_PERMISSIONS } from '@/mocks/systemRolePermissions';

const { default: DashboardPage } = await import('./DashboardPage');

function setRole(role: keyof typeof SYSTEM_ROLE_PERMISSIONS) {
  useAuthStore.setState({
    user: {
      id: 'u-1',
      email: 'me@x.io',
      display_name: 'Me',
      role,
      role_id: null,
      permissions: SYSTEM_ROLE_PERMISSIONS[role],
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
    open_api_requests_count: 4,
    pending_api_approvals_count: 5,
    status_counts: [
      { status: 'PENDING_REVIEW', count: 4 },
      { status: 'APPROVED', count: 3 },
    ],
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
    recent_api_requests: [
      {
        id: 'a1',
        connector_id: 'c1',
        connector_name: 'Payments API',
        verb: 'GET',
        request_path: '/v1/charges',
        write: false,
        status: 'PENDING_REVIEW',
        ai_risk_level: 'LOW',
        ai_risk_score: 12,
        created_at: '2026-06-20T12:00:00Z',
      },
    ],
    recent_pending_api_approvals: [
      {
        api_request_id: 'a2',
        connector_id: 'c1',
        connector_name: 'Payments API',
        submitted_by_user_id: 'u-9',
        verb: 'POST',
        request_path: '/v1/refunds',
        write: true,
        ai_risk_level: 'HIGH',
        ai_risk_score: 77,
        current_stage: 1,
        created_at: '2026-06-20T13:00:00Z',
      },
    ],
  };
}

const emptyTrends: MyQueryTrends = { status_by_day: [], risk_by_day: [] };
const emptyApiTrends: MyQueryTrends = { status_by_day: [], risk_by_day: [] };
const emptySuggestions: DashboardSuggestions = { suggestions: [] };
const disabledDigest: DigestSubscription = { enabled: false, last_sent_at: null };
const emptyMine: AnomalyPage = { content: [], page: 0, size: 10, total_elements: 0, total_pages: 0 };
const emptyWorklist: AttestationItemPage = {
  content: [],
  page: 0,
  size: 5,
  total_elements: 0,
  total_pages: 0,
};
const emptyAccess: AccessRequestPage = {
  content: [],
  page: 0,
  size: 5,
  total_elements: 0,
  total_pages: 0,
};
const emptyGroups: RequestGroupPage = {
  content: [],
  page: 0,
  size: 5,
  total_elements: 0,
  total_pages: 0,
  last: true,
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={['/dashboard']}>{children}</MemoryRouter>
      </App>
    </QueryClientProvider>
  );
  return render(
    <Routes>
      <Route path="/dashboard" element={<DashboardPage />} />
      <Route path="/reviews" element={<div data-testid="reviews-page" />} />
      <Route path="/queries" element={<div data-testid="queries-page" />} />
    </Routes>,
    { wrapper },
  );
}

describe('DashboardPage', () => {
  beforeEach(() => {
    fetchSummary.mockResolvedValue(summary());
    fetchTrends.mockResolvedValue(emptyTrends);
    fetchApiTrends.mockResolvedValue(emptyApiTrends);
    fetchSuggestions.mockResolvedValue(emptySuggestions);
    fetchDigest.mockResolvedValue(disabledDigest);
    setDigest.mockResolvedValue({ enabled: true, last_sent_at: null });
    listMine.mockResolvedValue(emptyMine);
    listWorklist.mockResolvedValue(emptyWorklist);
    listAccess.mockResolvedValue(emptyAccess);
    listGroups.mockResolvedValue(emptyGroups);
    exportSummary.mockResolvedValue({ blob: new Blob(['x']), filename: 'dashboard-summary.pdf' });
    // jsdom lacks object-URL helpers used by the export download.
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    // Reset persisted widget prefs so all widgets show.
    localStorage.clear();
    usePreferencesStore.getState().resetDashboardWidgets();
    // ADMIN sees every widget (role gating, AF-498).
    setRole('ADMIN');
  });

  it('renders the summary counts and every widget for ADMIN', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('dashboard-stat-pending')).toBeInTheDocument());
    expect(within(screen.getByTestId('dashboard-stat-pending')).getByText('3')).toBeInTheDocument();
    expect(within(screen.getByTestId('dashboard-stat-open')).getByText('7')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-pendingApprovals')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-recentQueries')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-suggestions')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-anomalies')).toBeInTheDocument();
    // API Access Governance widgets + stat cards (AF-500).
    expect(screen.getByTestId('dashboard-widget-recentApiRequests')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-apiRequestTrends')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-pendingApiApprovals')).toBeInTheDocument();
    expect(within(screen.getByTestId('dashboard-stat-openApiRequests')).getByText('4')).toBeInTheDocument();
    expect(within(screen.getByTestId('dashboard-stat-pendingApiApprovals')).getByText('5')).toBeInTheDocument();
    // Redesign additions (AF-498 redesign).
    expect(screen.getByTestId('dashboard-widget-attestationsDue')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-myAccessRequests')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-myRequestGroups')).toBeInTheDocument();
    // Bklit chart widgets.
    expect(screen.getByTestId('dashboard-widget-riskMix')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-activityHeatmap')).toBeInTheDocument();
  });

  it('shows a sparkline on the open-queries tile when the trends window has activity', async () => {
    fetchTrends.mockResolvedValue({
      status_by_day: [{ date: '2026-08-10', status: 'EXECUTED', count: 3 }],
      risk_by_day: [],
    });
    renderPage();
    const openTile = await screen.findByTestId('dashboard-stat-open');
    await waitFor(() =>
      expect(within(openTile).getByTestId('bklit-line-chart')).toBeInTheDocument(),
    );
  });

  it('renders the status breakdown inside the open-queries stat tile', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByTestId('dashboard-stat-open')).toBeInTheDocument());
    const openTile = screen.getByTestId('dashboard-stat-open');
    expect(within(openTile).getByText('4')).toBeInTheDocument();
    expect(within(openTile).getByText('3')).toBeInTheDocument();
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
    // API request read widgets are available to ANALYST; the reviewer-only one is not.
    expect(screen.getByTestId('dashboard-widget-recentApiRequests')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-apiRequestTrends')).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-widget-pendingApiApprovals')).not.toBeInTheDocument();
    expect(screen.getByTestId('dashboard-stat-openApiRequests')).toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-stat-pendingApiApprovals')).not.toBeInTheDocument();
    // Attestation worklist needs ATTESTATION_REVIEW; the self-scoped lists follow submit rights.
    expect(screen.queryByTestId('dashboard-widget-attestationsDue')).not.toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-myAccessRequests')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widget-myRequestGroups')).toBeInTheDocument();
  });

  it('navigates to the reviews queue from the pending-approvals stat tile', async () => {
    renderPage();
    const tile = await screen.findByTestId('dashboard-stat-pending');
    fireEvent.click(tile);
    await waitFor(() => expect(screen.getByTestId('reviews-page')).toBeInTheDocument());
  });

  it('navigates via keyboard from a stat tile', async () => {
    renderPage();
    const tile = await screen.findByTestId('dashboard-stat-open');
    fireEvent.keyDown(tile, { key: 'Enter' });
    await waitFor(() => expect(screen.getByTestId('queries-page')).toBeInTheDocument());
  });

  it('hides a widget from Customize and restores it with Reset layout', async () => {
    renderPage();
    await screen.findByTestId('dashboard-widget-trends');
    fireEvent.click(screen.getByRole('button', { name: /customize/i }));
    const menu = await screen.findByRole('menu');
    fireEvent.click(within(menu).getByText('Query trends'));
    await waitFor(() =>
      expect(screen.queryByTestId('dashboard-widget-trends')).not.toBeInTheDocument(),
    );
    // The menu stays open for further toggles; Reset layout restores the defaults.
    fireEvent.click(within(menu).getByText(/reset layout/i));
    await waitFor(() => expect(screen.getByTestId('dashboard-widget-trends')).toBeInTheDocument());
  });

  it('toggles the widget size preference from the card header', async () => {
    renderPage();
    await screen.findByTestId('dashboard-widget-trends');
    const widen = within(screen.getByTestId('dashboard-widget-trends')).getByRole('button', {
      name: /widen/i,
    });
    fireEvent.click(widen);
    expect(usePreferencesStore.getState().dashboardWidgets.size.trends).toBe('full');
    expect(screen.getByTestId('dashboard-widget-trends')).toHaveClass('af-widget--full');
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

  it('shows an error block with retry when the summary fails', async () => {
    fetchSummary.mockRejectedValue(new Error('boom'));
    renderPage();
    const alerts = await screen.findAllByRole('alert');
    expect(alerts.length).toBeGreaterThan(0);
    expect(screen.queryByTestId('dashboard-stat-pending')).not.toBeInTheDocument();
    fetchSummary.mockResolvedValue(summary());
    const retry = screen.getAllByRole('button', { name: /retry/i });
    expect(retry.length).toBeGreaterThan(0);
    fireEvent.click(retry[0]!);
    await waitFor(() => expect(screen.getByTestId('dashboard-stat-pending')).toBeInTheDocument());
  });
});
