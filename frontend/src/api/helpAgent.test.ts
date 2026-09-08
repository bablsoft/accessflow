import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, put } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put },
}));

import {
  getHelpAgentConfig,
  helpAgentKeys,
  reindexHelpCorpus,
  testHelpAgentConfig,
  updateHelpAgentConfig,
} from './helpAgent';

describe('api/helpAgent', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(helpAgentKeys.all).toEqual(['helpAgent']);
    expect(helpAgentKeys.config()).toEqual(['helpAgent', 'config']);
  });

  it('reads the config', async () => {
    get.mockResolvedValueOnce({ data: { id: null, enabled: false } });
    await expect(getHelpAgentConfig()).resolves.toMatchObject({ enabled: false });
    expect(get).toHaveBeenCalledWith('/api/v1/admin/help-agent');
  });

  it('PUTs a partial update', async () => {
    put.mockResolvedValueOnce({ data: { id: 'cfg-1', enabled: true } });
    await updateHelpAgentConfig({ enabled: true, clear_ai_config: true });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/help-agent', {
      enabled: true,
      clear_ai_config: true,
    });
  });

  it('runs the retrieval test', async () => {
    post.mockResolvedValueOnce({ data: { status: 'OK', detail: 'reachable' } });
    await expect(testHelpAgentConfig()).resolves.toMatchObject({ status: 'OK' });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/help-agent/test');
  });

  it('requests a re-index', async () => {
    post.mockResolvedValueOnce({ data: undefined });
    await reindexHelpCorpus();
    expect(post).toHaveBeenCalledWith('/api/v1/admin/help-agent/reindex');
  });
});
