import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import * as api from './apiRequests';
import { apiRequestKeys } from './apiRequests';

describe('api/apiRequests', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('builds hierarchical query keys with the diff nested under the detail', () => {
    expect(apiRequestKeys.all).toEqual(['api-requests']);
    expect(apiRequestKeys.list({ page: 1 })).toEqual(['api-requests', 'list', { page: 1 }]);
    expect(apiRequestKeys.details()).toEqual(['api-requests', 'detail']);
    expect(apiRequestKeys.detail('r-1')).toEqual(['api-requests', 'detail', 'r-1']);
    expect(apiRequestKeys.diff('r-1', 'previous')).toEqual([
      'api-requests',
      'detail',
      'r-1',
      'diff',
      'previous',
    ]);
    expect(apiRequestKeys.reviewQueue({ verb: 'GET' })).toEqual(['api-reviews', 'queue', { verb: 'GET' }]);
  });

  it('lists requests with only the filters that are set', async () => {
    get.mockResolvedValue({ data: { content: [] } });
    await api.listApiRequests();
    expect(get).toHaveBeenLastCalledWith('/api/v1/api-requests', { params: {} });
    await api.listApiRequests({
      page: 0,
      size: 20,
      status: 'EXECUTED',
      connector_id: 'c-1',
      verb: 'GET',
      submitted_by: 'u-1',
      trace_id: 't',
      span_id: 's',
      from: 'a',
      to: 'b',
    });
    expect(get).toHaveBeenLastCalledWith('/api/v1/api-requests', {
      params: {
        page: 0,
        size: 20,
        status: 'EXECUTED',
        connector_id: 'c-1',
        verb: 'GET',
        submitted_by: 'u-1',
        trace_id: 't',
        span_id: 's',
        from: 'a',
        to: 'b',
      },
    });
  });

  it('downloads the response with the filename from Content-Disposition', async () => {
    const blob = new Blob(['{}']);
    get.mockResolvedValue({
      data: blob,
      headers: { 'content-disposition': 'attachment; filename="resp.json"', 'content-type': 'application/json' },
    });
    const res = await api.downloadApiResponse('r-1');
    expect(get).toHaveBeenCalledWith('/api/v1/api-requests/r-1/response', { responseType: 'blob' });
    expect(res).toEqual({ blob, filename: 'resp.json', contentType: 'application/json' });
  });

  it('falls back to a default filename and content type', async () => {
    get.mockResolvedValue({ data: new Blob([]), headers: {} });
    const res = await api.downloadApiResponse('r-2');
    expect(res.filename).toBe('api-response-r-2');
    expect(res.contentType).toBe('application/octet-stream');
  });

  it('gets, submits, cancels and executes a request', async () => {
    get.mockResolvedValue({ data: { id: 'r-1' } });
    post.mockResolvedValue({ data: { id: 'r-1' } });
    expect(await api.getApiRequest('r-1')).toEqual({ id: 'r-1' });
    expect(get).toHaveBeenCalledWith('/api/v1/api-requests/r-1');
    const input = { connector_id: 'c-1', verb: 'GET', request_path: '/x' };
    await api.submitApiRequest(input);
    expect(post).toHaveBeenCalledWith('/api/v1/api-requests', input);
    await api.cancelApiRequest('r-1');
    expect(post).toHaveBeenCalledWith('/api/v1/api-requests/r-1/cancel');
    await api.executeApiRequest('r-1');
    expect(post).toHaveBeenCalledWith('/api/v1/api-requests/r-1/execute');
  });

  it('replays a request', async () => {
    post.mockResolvedValue({ data: { id: 'r-9', dropped_variable_override_count: 1 } });
    const res = await api.replayApiRequest('r-1');
    expect(post).toHaveBeenCalledWith('/api/v1/api-requests/r-1/replay');
    expect(res).toEqual({ id: 'r-9', dropped_variable_override_count: 1 });
  });

  it('fetches the response diff against the previous run by default', async () => {
    get.mockResolvedValue({ data: { current_id: 'r-1' } });
    await api.getApiResponseDiff('r-1');
    expect(get).toHaveBeenLastCalledWith('/api/v1/api-requests/r-1/diff', {
      params: { against: 'previous' },
    });
    await api.getApiResponseDiff('r-1', 'r-0');
    expect(get).toHaveBeenLastCalledWith('/api/v1/api-requests/r-1/diff', { params: { against: 'r-0' } });
  });

  it('analyzes and generates calls', async () => {
    post.mockResolvedValue({ data: {} });
    await api.analyzeApiCall({ connector_id: 'c-1' });
    expect(post).toHaveBeenCalledWith('/api/v1/api-requests/analyze', { connector_id: 'c-1' });
    await api.generateApiCall({ connector_id: 'c-1', prompt: 'list' });
    expect(post).toHaveBeenCalledWith('/api/v1/api-requests/generate', { connector_id: 'c-1', prompt: 'list' });
  });

  it('lists, approves and rejects reviews', async () => {
    get.mockResolvedValue({ data: { content: [] } });
    post.mockResolvedValue({ data: {} });
    await api.listPendingApiReviews();
    expect(get).toHaveBeenLastCalledWith('/api/v1/api-reviews', { params: {} });
    await api.listPendingApiReviews({ page: 1, size: 10, connector_id: 'c-1', verb: 'POST' });
    expect(get).toHaveBeenLastCalledWith('/api/v1/api-reviews', {
      params: { page: 1, size: 10, connector_id: 'c-1', verb: 'POST' },
    });
    await api.approveApiReview('r-1', 'ok');
    expect(post).toHaveBeenCalledWith('/api/v1/api-reviews/r-1/approve', { comment: 'ok' });
    await api.rejectApiReview('r-1');
    expect(post).toHaveBeenCalledWith('/api/v1/api-reviews/r-1/reject', { comment: undefined });
  });
});
