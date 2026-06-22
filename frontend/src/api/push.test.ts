import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, delete: del },
}));

import * as pushApi from './push';

describe('api/push', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
  });

  it('getVapidPublicKey returns the public_key field', async () => {
    get.mockResolvedValueOnce({ data: { public_key: 'PUB' } });
    await expect(pushApi.getVapidPublicKey()).resolves.toBe('PUB');
    expect(get).toHaveBeenCalledWith('/api/v1/push/vapid-public-key');
  });

  it('subscribePush posts the subscription with snake_case user_agent', async () => {
    post.mockResolvedValueOnce({ data: undefined });
    await pushApi.subscribePush(
      { endpoint: 'https://push/abc', keys: { p256dh: 'P', auth: 'A' } },
      'Firefox',
    );
    expect(post).toHaveBeenCalledWith('/api/v1/push/subscriptions', {
      endpoint: 'https://push/abc',
      keys: { p256dh: 'P', auth: 'A' },
      user_agent: 'Firefox',
    });
  });

  it('subscribePush sends null user_agent when omitted', async () => {
    post.mockResolvedValueOnce({ data: undefined });
    await pushApi.subscribePush({ endpoint: 'e', keys: { p256dh: 'P', auth: 'A' } });
    expect(post.mock.calls[0]?.[1]).toMatchObject({ user_agent: null });
  });

  it('unsubscribePush deletes with the endpoint in the body', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await pushApi.unsubscribePush('https://push/abc');
    expect(del).toHaveBeenCalledWith('/api/v1/push/subscriptions', {
      data: { endpoint: 'https://push/abc' },
    });
  });
});
