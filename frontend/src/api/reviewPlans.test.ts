import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, put, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put, delete: del },
}));

import * as reviewPlansApi from './reviewPlans';

const planFixture = {
  id: 'rp-1',
  organization_id: 'org-1',
  name: 'PII writes',
  description: 'desc',
  requires_ai_review: true,
  requires_human_approval: true,
  min_approvals_required: 1,
  approval_timeout_hours: 24,
  auto_approve_reads: false,
  notify_channels: [],
  approvers: [{ user_id: null, role: 'REVIEWER', stage: 1 }],
  created_at: '2026-05-04T10:15:00Z',
};

describe('api/reviewPlans', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('listReviewPlans unwraps the { content } envelope', async () => {
    get.mockResolvedValueOnce({ data: { content: [planFixture] } });
    const result = await reviewPlansApi.listReviewPlans();
    expect(get).toHaveBeenCalledWith('/api/v1/review-plans');
    expect(result).toEqual([planFixture]);
  });

  it('getReviewPlan GETs /api/v1/review-plans/{id}', async () => {
    get.mockResolvedValueOnce({ data: planFixture });
    const result = await reviewPlansApi.getReviewPlan('rp-1');
    expect(get).toHaveBeenCalledWith('/api/v1/review-plans/rp-1');
    expect(result.id).toBe('rp-1');
  });

  it('createReviewPlan POSTs /api/v1/review-plans with the body', async () => {
    post.mockResolvedValueOnce({ data: planFixture });
    const payload = {
      name: 'PII writes',
      requires_human_approval: true,
      approvers: [{ user_id: null, role: 'REVIEWER' as const, stage: 1 }],
    };
    await reviewPlansApi.createReviewPlan(payload);
    expect(post).toHaveBeenCalledWith('/api/v1/review-plans', payload);
  });

  it('updateReviewPlan PUTs /api/v1/review-plans/{id} with the body', async () => {
    put.mockResolvedValueOnce({ data: planFixture });
    await reviewPlansApi.updateReviewPlan('rp-1', { description: 'updated' });
    expect(put).toHaveBeenCalledWith('/api/v1/review-plans/rp-1', { description: 'updated' });
  });

  it('deleteReviewPlan DELETEs /api/v1/review-plans/{id}', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await reviewPlansApi.deleteReviewPlan('rp-1');
    expect(del).toHaveBeenCalledWith('/api/v1/review-plans/rp-1');
  });

  it('reviewPlanKeys produce stable factory output', () => {
    expect(reviewPlansApi.reviewPlanKeys.all).toEqual(['reviewPlans']);
    expect(reviewPlansApi.reviewPlanKeys.lists()).toEqual(['reviewPlans', 'list']);
    expect(reviewPlansApi.reviewPlanKeys.detail('rp-1'))
      .toEqual(['reviewPlans', 'detail', 'rp-1']);
  });
});
