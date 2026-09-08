import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post },
}));

import {
  askHelpChat,
  createHelpChatSession,
  fetchHelpChatAvailability,
  fetchHelpChatSession,
  helpChatKeys,
} from './helpChat';

describe('api/helpChat', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(helpChatKeys.all).toEqual(['helpChat']);
    expect(helpChatKeys.availability()).toEqual(['helpChat', 'availability']);
    expect(helpChatKeys.session('s1')).toEqual(['helpChat', 'session', 's1']);
  });

  it('fetches availability', async () => {
    const snapshot = {
      enabled: true,
      retrieval_active: true,
      corpus_version: 'c0ac599ef7fc',
      chunk_count: 512,
    };
    get.mockResolvedValueOnce({ data: snapshot });
    await expect(fetchHelpChatAvailability()).resolves.toEqual(snapshot);
    expect(get).toHaveBeenCalledWith('/api/v1/help-chat/availability');
  });



  it('creates a session', async () => {
    post.mockResolvedValueOnce({ data: { id: 's1', title: '', message_count: 0, created_at: 'x' } });
    await expect(createHelpChatSession()).resolves.toMatchObject({ id: 's1' });
    expect(post).toHaveBeenCalledWith('/api/v1/help-chat/sessions');
  });

  it('reads one conversation', async () => {
    get.mockResolvedValueOnce({ data: { session: { id: 's1' }, messages: [] } });
    await fetchHelpChatSession('s1');
    expect(get).toHaveBeenCalledWith('/api/v1/help-chat/sessions/s1');
  });

  it('posts a question with the route label', async () => {
    post.mockResolvedValueOnce({ data: { session: { id: 's1' } } });
    await askHelpChat('s1', { question: 'How do I submit a query?', route_name: 'Review queue' });
    expect(post).toHaveBeenCalledWith('/api/v1/help-chat/sessions/s1/messages', {
      question: 'How do I submit a query?',
      route_name: 'Review queue',
    });
  });

});
