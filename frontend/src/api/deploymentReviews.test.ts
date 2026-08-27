import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as api from './deploymentReviews';
import { deploymentReviewKeys, deploymentRollbackReviewKeys } from './deploymentReviews';

describe('api/deploymentReviews', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(deploymentReviewKeys.list({ pipeline_id: 'p-1' })).toEqual([
      'deployment-reviews',
      'list',
      { pipeline_id: 'p-1' },
    ]);
    expect(deploymentRollbackReviewKeys.list({ status: 'PENDING_REVIEW' })).toEqual([
      'deployment-rollback-reviews',
      'list',
      { status: 'PENDING_REVIEW' },
    ]);
    expect(deploymentRollbackReviewKeys.detail('r-1')).toEqual([
      'deployment-rollback-reviews',
      'detail',
      'r-1',
    ]);
  });

  it('lists pending reviews with filters', async () => {
    get.mockResolvedValue({ data: { content: [], total_elements: 0 } });
    await api.listDeploymentReviews({ pipeline_id: 'p-1', page: 0, size: 20 });
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-reviews', {
      params: { pipeline_id: 'p-1', page: 0, size: 20 },
    });
  });

  it('omits absent review filters', async () => {
    get.mockResolvedValue({ data: { content: [] } });
    await api.listDeploymentReviews();
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-reviews', { params: {} });
  });

  it('approves with a comment', async () => {
    post.mockResolvedValue({
      data: { decision_id: 'dec-1', decision: 'APPROVED', resulting_status: 'APPROVED' },
    });
    const result = await api.approveDeployment('d-1', 'LGTM');
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-reviews/d-1/approve', {
      comment: 'LGTM',
    });
    expect(result.resulting_status).toBe('APPROVED');
  });

  it('approves without a comment (null body field)', async () => {
    post.mockResolvedValue({ data: { decision: 'APPROVED' } });
    await api.approveDeployment('d-1');
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-reviews/d-1/approve', {
      comment: null,
    });
  });

  it('rejects with a comment', async () => {
    post.mockResolvedValue({ data: { decision: 'REJECTED', resulting_status: 'REJECTED' } });
    await api.rejectDeployment('d-1', 'not now');
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-reviews/d-1/reject', {
      comment: 'not now',
    });
  });

  it('lists rollback reviews with a status filter', async () => {
    get.mockResolvedValue({ data: { content: [], total_elements: 0 } });
    await api.listDeploymentRollbackReviews({ status: 'PENDING_REVIEW', page: 0 });
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-rollback-reviews', {
      params: { status: 'PENDING_REVIEW', page: 0 },
    });
  });

  it('gets a rollback review', async () => {
    get.mockResolvedValue({ data: { id: 'r-1', status: 'PENDING_REVIEW' } });
    const r = await api.getDeploymentRollbackReview('r-1');
    expect(get).toHaveBeenCalledWith('/api/v1/deployment-rollback-reviews/r-1');
    expect(r.id).toBe('r-1');
  });

  it('acknowledges a rollback with an optional comment', async () => {
    post.mockResolvedValue({ data: { id: 'r-1', status: 'REVIEWED' } });
    await api.acknowledgeDeploymentRollback('r-1', 'root cause filed');
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-rollback-reviews/r-1/acknowledge', {
      comment: 'root cause filed',
    });

    await api.acknowledgeDeploymentRollback('r-1');
    expect(post).toHaveBeenCalledWith('/api/v1/deployment-rollback-reviews/r-1/acknowledge', {
      comment: null,
    });
  });
});
