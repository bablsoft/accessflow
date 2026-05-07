import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as reviewsApi from './reviews';

const pendingFixture = {
  content: [
    {
      id: 'q-1',
      datasource: { id: 'ds-1', name: 'Production' },
      submitted_by: { id: 'u-1', email: 'a@example.com' },
      sql_text: 'SELECT 1',
      query_type: 'SELECT',
      justification: 'why',
      ai_analysis: null,
      current_stage: 1,
      created_at: '2026-05-04T10:15:00Z',
    },
  ],
  page: 0,
  size: 20,
  total_elements: 1,
  total_pages: 1,
};

const decisionFixture = {
  query_request_id: 'q-1',
  decision_id: 'd-1',
  decision: 'APPROVED',
  resulting_status: 'APPROVED',
  idempotent_replay: false,
};

describe('api/reviews', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('listPendingReviews GETs /api/v1/reviews/pending with no params by default', async () => {
    get.mockResolvedValueOnce({ data: pendingFixture });
    await reviewsApi.listPendingReviews();
    expect(get).toHaveBeenCalledWith('/api/v1/reviews/pending', { params: {} });
  });

  it('listPendingReviews forwards page and size', async () => {
    get.mockResolvedValueOnce({ data: pendingFixture });
    const result = await reviewsApi.listPendingReviews({ page: 1, size: 50 });
    expect(get).toHaveBeenCalledWith('/api/v1/reviews/pending', {
      params: { page: 1, size: 50 },
    });
    expect(result.content).toHaveLength(1);
    expect(result.total_elements).toBe(1);
  });

  it('approveQuery POSTs /api/v1/reviews/{id}/approve with comment when provided', async () => {
    post.mockResolvedValueOnce({ data: decisionFixture });
    const result = await reviewsApi.approveQuery('q-1', 'looks good');
    expect(post).toHaveBeenCalledWith('/api/v1/reviews/q-1/approve', { comment: 'looks good' });
    expect(result.decision).toBe('APPROVED');
  });

  it('approveQuery POSTs with comment=null when omitted', async () => {
    post.mockResolvedValueOnce({ data: decisionFixture });
    await reviewsApi.approveQuery('q-1');
    expect(post).toHaveBeenCalledWith('/api/v1/reviews/q-1/approve', { comment: null });
  });

  it('rejectQuery POSTs /api/v1/reviews/{id}/reject with comment', async () => {
    post.mockResolvedValueOnce({
      data: { ...decisionFixture, decision: 'REJECTED', resulting_status: 'REJECTED' },
    });
    await reviewsApi.rejectQuery('q-1', 'too risky');
    expect(post).toHaveBeenCalledWith('/api/v1/reviews/q-1/reject', { comment: 'too risky' });
  });

  it('rejectQuery POSTs with comment=null when omitted', async () => {
    post.mockResolvedValueOnce({ data: decisionFixture });
    await reviewsApi.rejectQuery('q-1');
    expect(post).toHaveBeenCalledWith('/api/v1/reviews/q-1/reject', { comment: null });
  });

  it('requestChanges POSTs /api/v1/reviews/{id}/request-changes with required comment', async () => {
    post.mockResolvedValueOnce({
      data: {
        ...decisionFixture,
        decision: 'REQUESTED_CHANGES',
        resulting_status: 'PENDING_REVIEW',
      },
    });
    await reviewsApi.requestChanges('q-1', 'narrow the WHERE clause');
    expect(post).toHaveBeenCalledWith('/api/v1/reviews/q-1/request-changes', {
      comment: 'narrow the WHERE clause',
    });
  });

  it('reviewKeys produce stable factory output', () => {
    expect(reviewsApi.reviewKeys.all).toEqual(['reviews']);
    expect(reviewsApi.reviewKeys.pending()).toEqual(['reviews', 'pending']);
    expect(reviewsApi.reviewKeys.pendingFor({ page: 0, size: 50 }))
      .toEqual(['reviews', 'pending', { page: 0, size: 50 }]);
  });
});
