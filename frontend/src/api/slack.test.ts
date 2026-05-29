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

import * as slackApi from './slack';

describe('api/slack', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('getSlackAppConfig GETs the admin config endpoint', async () => {
    get.mockResolvedValueOnce({ data: { app_id: 'A1', has_bot_token: true } });
    const result = await slackApi.getSlackAppConfig();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/slack-app-config');
    expect(result?.app_id).toBe('A1');
  });

  it('getSlackAppConfig returns null on 404', async () => {
    get.mockRejectedValueOnce({ isAxiosError: true, response: { status: 404 } });
    expect(await slackApi.getSlackAppConfig()).toBeNull();
  });

  it('getSlackAppConfig rethrows non-404 errors', async () => {
    get.mockRejectedValueOnce({ isAxiosError: true, response: { status: 500 } });
    await expect(slackApi.getSlackAppConfig()).rejects.toMatchObject({
      response: { status: 500 },
    });
  });

  it('upsertSlackAppConfig PUTs the input', async () => {
    put.mockResolvedValueOnce({ data: { app_id: 'A2' } });
    const result = await slackApi.upsertSlackAppConfig({
      app_id: 'A2',
      default_channel_id: 'C1',
      bot_token: 'xoxb',
      signing_secret: 's',
      active: true,
    });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/slack-app-config', {
      app_id: 'A2',
      default_channel_id: 'C1',
      bot_token: 'xoxb',
      signing_secret: 's',
      active: true,
    });
    expect(result.app_id).toBe('A2');
  });

  it('deleteSlackAppConfig DELETEs the admin config endpoint', async () => {
    del.mockResolvedValueOnce({});
    await slackApi.deleteSlackAppConfig();
    expect(del).toHaveBeenCalledWith('/api/v1/admin/slack-app-config');
  });

  it('testSlackAppConfig POSTs to the test endpoint', async () => {
    post.mockResolvedValueOnce({ data: { status: 'OK', detail: 'ok' } });
    const result = await slackApi.testSlackAppConfig();
    expect(post).toHaveBeenCalledWith('/api/v1/admin/slack-app-config/test');
    expect(result.status).toBe('OK');
  });

  it('getSlackLinkStatus GETs the integration link endpoint', async () => {
    get.mockResolvedValueOnce({ data: { linked: true, slack_user_id: 'U1' } });
    const result = await slackApi.getSlackLinkStatus();
    expect(get).toHaveBeenCalledWith('/api/v1/integrations/slack/link');
    expect(result.linked).toBe(true);
  });

  it('createSlackLinkCode POSTs to the link-codes endpoint', async () => {
    post.mockResolvedValueOnce({ data: { code: 'ABC', expires_at: '2026-01-01T00:00:00Z' } });
    const result = await slackApi.createSlackLinkCode();
    expect(post).toHaveBeenCalledWith('/api/v1/integrations/slack/link-codes');
    expect(result.code).toBe('ABC');
  });

  it('unlinkSlack DELETEs the integration link endpoint', async () => {
    del.mockResolvedValueOnce({});
    await slackApi.unlinkSlack();
    expect(del).toHaveBeenCalledWith('/api/v1/integrations/slack/link');
  });

  it('exposes stable query-key factories', () => {
    expect(slackApi.slackAppConfigKeys.all).toEqual(['slackAppConfig']);
    expect(slackApi.slackAppConfigKeys.current()).toEqual(['slackAppConfig', 'current']);
    expect(slackApi.slackLinkKeys.status()).toEqual(['slackLink', 'status']);
  });
});
