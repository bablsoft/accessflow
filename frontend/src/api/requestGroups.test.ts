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

import * as api from './requestGroups';

const page = { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0, last: true };

describe('api/requestGroups', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('listRequestGroups maps filters to snake_case params', async () => {
    get.mockResolvedValueOnce({ data: page });
    await api.listRequestGroups({
      page: 1,
      size: 50,
      status: 'PENDING_REVIEW',
      submitted_by: 'u-1',
    });
    expect(get).toHaveBeenCalledWith('/api/v1/request-groups', {
      params: { page: 1, size: 50, status: 'PENDING_REVIEW', submitted_by: 'u-1' },
    });
  });

  it('listRequestGroups omits absent filters', async () => {
    get.mockResolvedValueOnce({ data: page });
    await api.listRequestGroups();
    expect(get).toHaveBeenCalledWith('/api/v1/request-groups', { params: {} });
  });

  it('getRequestGroup fetches by id', async () => {
    get.mockResolvedValueOnce({ data: { id: 'g-1' } });
    const result = await api.getRequestGroup('g-1');
    expect(get).toHaveBeenCalledWith('/api/v1/request-groups/g-1');
    expect(result.id).toBe('g-1');
  });

  it('createRequestGroup posts the payload', async () => {
    post.mockResolvedValueOnce({ data: { id: 'g-1' } });
    const input = { name: 'G', continue_on_error: false, items: [] };
    await api.createRequestGroup(input);
    expect(post).toHaveBeenCalledWith('/api/v1/request-groups', input);
  });

  it('updateRequestGroup puts the payload', async () => {
    put.mockResolvedValueOnce({ data: { id: 'g-1' } });
    const input = { name: 'G', continue_on_error: true, items: [] };
    await api.updateRequestGroup('g-1', input);
    expect(put).toHaveBeenCalledWith('/api/v1/request-groups/g-1', input);
  });

  it('deleteRequestGroup deletes by id', async () => {
    del.mockResolvedValueOnce({});
    await api.deleteRequestGroup('g-1');
    expect(del).toHaveBeenCalledWith('/api/v1/request-groups/g-1');
  });

  it('submitRequestGroup posts break-glass + schedule', async () => {
    post.mockResolvedValueOnce({});
    await api.submitRequestGroup('g-1', { break_glass: true, scheduled_for: '2030-01-01T00:00:00Z' });
    expect(post).toHaveBeenCalledWith('/api/v1/request-groups/g-1/submit', {
      break_glass: true,
      scheduled_for: '2030-01-01T00:00:00Z',
    });
  });

  it('executeRequestGroup posts to the execute endpoint', async () => {
    post.mockResolvedValueOnce({ data: { id: 'g-1' } });
    await api.executeRequestGroup('g-1');
    expect(post).toHaveBeenCalledWith('/api/v1/request-groups/g-1/execute');
  });

  it('cancelRequestGroup posts to the cancel endpoint', async () => {
    post.mockResolvedValueOnce({ data: { id: 'g-1' } });
    await api.cancelRequestGroup('g-1');
    expect(post).toHaveBeenCalledWith('/api/v1/request-groups/g-1/cancel');
  });

  it('listPendingGroupReviews maps page/size', async () => {
    get.mockResolvedValueOnce({ data: page });
    await api.listPendingGroupReviews({ page: 2, size: 10 });
    expect(get).toHaveBeenCalledWith('/api/v1/request-groups/reviews', {
      params: { page: 2, size: 10 },
    });
  });

  it('approveRequestGroup posts a comment', async () => {
    post.mockResolvedValueOnce({ data: { decision: 'APPROVED' } });
    await api.approveRequestGroup('g-1', 'ok');
    expect(post).toHaveBeenCalledWith('/api/v1/request-groups/g-1/approve', { comment: 'ok' });
  });

  it('rejectRequestGroup posts a comment', async () => {
    post.mockResolvedValueOnce({ data: { decision: 'REJECTED' } });
    await api.rejectRequestGroup('g-1', 'no');
    expect(post).toHaveBeenCalledWith('/api/v1/request-groups/g-1/reject', { comment: 'no' });
  });

  it('exposes hierarchical query keys', () => {
    expect(api.requestGroupKeys.list({ page: 0 })).toEqual(['request-groups', 'list', { page: 0 }]);
    expect(api.requestGroupKeys.detail('g-1')).toEqual(['request-groups', 'detail', 'g-1']);
    expect(api.requestGroupKeys.reviews({ page: 0 })).toEqual([
      'request-groups',
      'reviews',
      { page: 0 },
    ]);
  });
});
