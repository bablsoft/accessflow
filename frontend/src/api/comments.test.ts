import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as commentsApi from './comments';

describe('api/comments', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('exposes a query-scoped key factory', () => {
    expect(commentsApi.commentKeys.all('q-1')).toEqual([
      'queries',
      'detail',
      'q-1',
      'comments',
    ]);
  });

  it('listComments GETs the query comments endpoint', async () => {
    get.mockResolvedValueOnce({ data: [] });
    const result = await commentsApi.listComments('q-1');
    expect(get).toHaveBeenCalledWith('/api/v1/queries/q-1/comments');
    expect(result).toEqual([]);
  });

  it('createComment POSTs the anchor + body', async () => {
    post.mockResolvedValueOnce({ data: { id: 'c-1' } });
    await commentsApi.createComment('q-1', {
      anchor_start_line: 2,
      anchor_end_line: 4,
      anchor_snapshot: 'SELECT 1',
      body: 'fix this',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/queries/q-1/comments', {
      anchor_start_line: 2,
      anchor_end_line: 4,
      anchor_snapshot: 'SELECT 1',
      body: 'fix this',
    });
  });

  it('replyToComment POSTs the reply body', async () => {
    post.mockResolvedValueOnce({ data: { id: 'c-2' } });
    await commentsApi.replyToComment('q-1', 'c-1', 'agreed');
    expect(post).toHaveBeenCalledWith('/api/v1/queries/q-1/comments/c-1/replies', {
      body: 'agreed',
    });
  });

  it('resolveComment POSTs to the resolve endpoint', async () => {
    post.mockResolvedValueOnce({ data: { id: 'c-1' } });
    await commentsApi.resolveComment('q-1', 'c-1');
    expect(post).toHaveBeenCalledWith('/api/v1/queries/q-1/comments/c-1/resolve');
  });

  it('reopenComment POSTs to the reopen endpoint', async () => {
    post.mockResolvedValueOnce({ data: { id: 'c-1' } });
    await commentsApi.reopenComment('q-1', 'c-1');
    expect(post).toHaveBeenCalledWith('/api/v1/queries/q-1/comments/c-1/reopen');
  });
});
