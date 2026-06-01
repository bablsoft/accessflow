import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, delete: del },
}));

import * as api from './accessRequests';

describe('api/accessRequests', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
  });

  it('submitAccessRequest posts to /api/v1/access-requests', async () => {
    post.mockResolvedValueOnce({ data: { id: 'a-1', status: 'PENDING' } });
    const result = await api.submitAccessRequest({
      datasource_id: 'ds-1',
      can_read: true,
      can_write: false,
      can_ddl: false,
      requested_duration: 'PT4H',
      justification: 'need',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/access-requests', expect.objectContaining({
      datasource_id: 'ds-1',
      requested_duration: 'PT4H',
    }));
    expect(result.id).toBe('a-1');
  });

  it('listMyAccessRequests passes page/size/status params', async () => {
    get.mockResolvedValueOnce({ data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 } });
    await api.listMyAccessRequests({ page: 1, size: 10, status: 'PENDING' });
    expect(get).toHaveBeenCalledWith('/api/v1/access-requests', {
      params: { page: 1, size: 10, status: 'PENDING' },
    });
  });

  it('listRequestableDatasources gets the datasources sub-resource', async () => {
    get.mockResolvedValueOnce({ data: [{ id: 'ds-1', name: 'db' }] });
    const result = await api.listRequestableDatasources();
    expect(get).toHaveBeenCalledWith('/api/v1/access-requests/datasources');
    expect(result).toHaveLength(1);
  });

  it('cancelAccessRequest deletes by id', async () => {
    del.mockResolvedValueOnce({});
    await api.cancelAccessRequest('a-1');
    expect(del).toHaveBeenCalledWith('/api/v1/access-requests/a-1');
  });

  it('listPendingAccessRequests hits the admin queue', async () => {
    get.mockResolvedValueOnce({ data: { content: [], page: 0, size: 50, total_elements: 0, total_pages: 0 } });
    await api.listPendingAccessRequests({ size: 50 });
    expect(get).toHaveBeenCalledWith('/api/v1/admin/access-requests', { params: { size: 50 } });
  });

  it('approveAccessRequest posts to the admin approve endpoint', async () => {
    post.mockResolvedValueOnce({ data: { access_request_id: 'a-1', resulting_status: 'APPROVED' } });
    await api.approveAccessRequest('a-1', 'ok');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/access-requests/a-1/approve', { comment: 'ok' });
  });

  it('rejectAccessRequest posts the required comment', async () => {
    post.mockResolvedValueOnce({ data: { access_request_id: 'a-1', resulting_status: 'REJECTED' } });
    await api.rejectAccessRequest('a-1', 'no');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/access-requests/a-1/reject', { comment: 'no' });
  });

  it('revokeAccessGrant posts to the admin revoke endpoint', async () => {
    post.mockResolvedValueOnce({ data: { access_request_id: 'a-1', resulting_status: 'REVOKED', no_op: false } });
    await api.revokeAccessGrant('a-1');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/access-requests/a-1/revoke', { comment: null });
  });
});
