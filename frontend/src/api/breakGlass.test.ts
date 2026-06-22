import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as breakGlassApi from './breakGlass';

describe('api/breakGlass', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('listBreakGlassEvents maps filters to camelCase params', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0, last: true },
    });
    await breakGlassApi.listBreakGlassEvents({
      page: 1,
      size: 50,
      status: 'PENDING_REVIEW',
      datasource_id: 'ds-1',
      user_id: 'u-1',
      from: '2026-01-01T00:00:00Z',
      to: '2026-02-01T00:00:00Z',
    });
    expect(get).toHaveBeenCalledWith('/api/v1/admin/break-glass', {
      params: {
        page: 1,
        size: 50,
        status: 'PENDING_REVIEW',
        datasourceId: 'ds-1',
        userId: 'u-1',
        from: '2026-01-01T00:00:00Z',
        to: '2026-02-01T00:00:00Z',
      },
    });
  });

  it('listBreakGlassEvents omits absent filters', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0, last: true },
    });
    await breakGlassApi.listBreakGlassEvents();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/break-glass', { params: {} });
  });

  it('acknowledgeBreakGlassEvent posts with a comment', async () => {
    post.mockResolvedValueOnce({ data: { id: 'e-1', status: 'REVIEWED' } });
    const result = await breakGlassApi.acknowledgeBreakGlassEvent('e-1', 'done');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/break-glass/e-1/acknowledge', {
      comment: 'done',
    });
    expect(result.status).toBe('REVIEWED');
  });

  it('acknowledgeBreakGlassEvent posts an empty body when no comment', async () => {
    post.mockResolvedValueOnce({ data: { id: 'e-1', status: 'REVIEWED' } });
    await breakGlassApi.acknowledgeBreakGlassEvent('e-1');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/break-glass/e-1/acknowledge', {});
  });

  it('exposes hierarchical query keys', () => {
    expect(breakGlassKeysList()).toEqual(['break-glass', 'list', { page: 0 }]);
  });
});

function breakGlassKeysList() {
  return breakGlassApi.breakGlassKeys.list({ page: 0 });
}
