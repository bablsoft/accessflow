import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, put } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put },
}));

import * as dashboardApi from './dashboard';
import { dashboardKeys } from './dashboard';

describe('api/dashboard', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(dashboardKeys.all).toEqual(['dashboard']);
    expect(dashboardKeys.summary()).toEqual(['dashboard', 'summary']);
    expect(dashboardKeys.trends({ from: 'a' })).toEqual(['dashboard', 'trends', { from: 'a' }]);
    expect(dashboardKeys.suggestions()).toEqual(['dashboard', 'suggestions']);
    expect(dashboardKeys.digestSubscription()).toEqual(['dashboard', 'digest-subscription']);
  });

  it('fetchDashboardSummary GETs the summary endpoint', async () => {
    get.mockResolvedValueOnce({ data: { pending_approvals_count: 2 } });
    const result = await dashboardApi.fetchDashboardSummary();
    expect(get).toHaveBeenCalledWith('/api/v1/dashboard/summary');
    expect(result.pending_approvals_count).toBe(2);
  });

  it('fetchMyQueryTrends passes from/to when present', async () => {
    get.mockResolvedValueOnce({ data: { status_by_day: [], risk_by_day: [] } });
    await dashboardApi.fetchMyQueryTrends({ from: '2026-06-01', to: '2026-06-30' });
    expect(get).toHaveBeenCalledWith('/api/v1/dashboard/my-query-trends', {
      params: { from: '2026-06-01', to: '2026-06-30' },
    });
  });

  it('fetchMyQueryTrends omits empty params', async () => {
    get.mockResolvedValueOnce({ data: { status_by_day: [], risk_by_day: [] } });
    await dashboardApi.fetchMyQueryTrends();
    expect(get).toHaveBeenCalledWith('/api/v1/dashboard/my-query-trends', { params: {} });
  });

  it('fetchDashboardSuggestions GETs the backlog', async () => {
    get.mockResolvedValueOnce({ data: { suggestions: [] } });
    const result = await dashboardApi.fetchDashboardSuggestions();
    expect(get).toHaveBeenCalledWith('/api/v1/dashboard/suggestions');
    expect(result.suggestions).toEqual([]);
  });

  it('dismissDashboardSuggestion POSTs the URL-encoded id', async () => {
    post.mockResolvedValueOnce({ data: null });
    await dashboardApi.dismissDashboardSuggestion('an-1:0');
    expect(post).toHaveBeenCalledWith('/api/v1/dashboard/suggestions/an-1%3A0/dismiss');
  });

  it('fetchDigestSubscription GETs the subscription', async () => {
    get.mockResolvedValueOnce({ data: { enabled: false, last_sent_at: null } });
    const result = await dashboardApi.fetchDigestSubscription();
    expect(get).toHaveBeenCalledWith('/api/v1/dashboard/digest-subscription');
    expect(result.enabled).toBe(false);
  });

  it('setDigestSubscription PUTs the enabled flag', async () => {
    put.mockResolvedValueOnce({ data: { enabled: true, last_sent_at: null } });
    const result = await dashboardApi.setDigestSubscription(true);
    expect(put).toHaveBeenCalledWith('/api/v1/dashboard/digest-subscription', { enabled: true });
    expect(result.enabled).toBe(true);
  });

  describe('exportDashboardSummary', () => {
    it('returns the blob and parses the filename from Content-Disposition', async () => {
      const blob = new Blob(['x'], { type: 'application/pdf' });
      get.mockResolvedValueOnce({
        data: blob,
        headers: { 'content-disposition': 'attachment; filename="dashboard-summary-2026-06-22.pdf"' },
      });
      const result = await dashboardApi.exportDashboardSummary('PDF');
      expect(get).toHaveBeenCalledWith('/api/v1/dashboard/summary/export', {
        params: { format: 'PDF' },
        responseType: 'blob',
      });
      expect(result.filename).toBe('dashboard-summary-2026-06-22.pdf');
      expect(result.blob).toBe(blob);
    });

    it('falls back to a default filename when the header is missing', async () => {
      get.mockResolvedValueOnce({ data: new Blob(['x']), headers: {} });
      const result = await dashboardApi.exportDashboardSummary('CSV');
      expect(result.filename).toBe('dashboard-summary.csv');
    });
  });
});
