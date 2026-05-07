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

import * as adminApi from './admin';

const userFixture = {
  id: 'u-1',
  email: 'alice@example.com',
  display_name: 'Alice',
  role: 'ANALYST' as const,
  auth_provider: 'LOCAL' as const,
  active: true,
  last_login_at: '2026-05-04T09:00:00Z',
  created_at: '2026-01-15T10:00:00Z',
};

const channelFixture = {
  id: 'ch-1',
  organization_id: 'org-1',
  channel_type: 'WEBHOOK' as const,
  name: 'Eng',
  active: true,
  config: { url: 'https://hooks.example.com', secret: '********' },
  created_at: '2026-05-01T00:00:00Z',
};

describe('api/admin', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  // ── Users ─────────────────────────────────────────────────────────────────
  it('listUsers GETs /admin/users with pagination params', async () => {
    get.mockResolvedValueOnce({
      data: { content: [userFixture], page: 0, size: 20, total_elements: 1, total_pages: 1 },
    });
    const result = await adminApi.listUsers({ page: 0, size: 20, sort: 'email,asc' });
    expect(get).toHaveBeenCalledWith('/api/v1/admin/users', {
      params: { page: 0, size: 20, sort: 'email,asc' },
    });
    expect(result.content[0]?.email).toBe('alice@example.com');
  });

  it('listUsers omits unset params', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 },
    });
    await adminApi.listUsers();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/users', { params: {} });
  });

  it('createUser POSTs the body to /admin/users', async () => {
    post.mockResolvedValueOnce({ data: userFixture });
    await adminApi.createUser({
      email: 'new@example.com',
      password: 'a-strong-password',
      display_name: 'New',
      role: 'ANALYST',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/users', {
      email: 'new@example.com',
      password: 'a-strong-password',
      display_name: 'New',
      role: 'ANALYST',
    });
  });

  it('updateUser PUTs /admin/users/{id} with the body', async () => {
    put.mockResolvedValueOnce({ data: userFixture });
    await adminApi.updateUser('u-1', { role: 'REVIEWER' });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/users/u-1', { role: 'REVIEWER' });
  });

  it('deactivateUser DELETEs /admin/users/{id}', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await adminApi.deactivateUser('u-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/users/u-1');
  });

  // ── Audit log ─────────────────────────────────────────────────────────────
  it('listAuditEvents passes filter params with the correct query keys', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 },
    });
    await adminApi.listAuditEvents({
      actor_id: 'u-1',
      action: 'USER_LOGIN',
      resource_type: 'user',
      resource_id: 'r-1',
      from: '2026-05-01T00:00:00Z',
      to: '2026-05-08T00:00:00Z',
      page: 1,
      size: 50,
      sort: 'created_at,DESC',
    });
    expect(get).toHaveBeenCalledWith('/api/v1/admin/audit-log', {
      params: {
        actorId: 'u-1',
        action: 'USER_LOGIN',
        resourceType: 'user',
        resourceId: 'r-1',
        from: '2026-05-01T00:00:00Z',
        to: '2026-05-08T00:00:00Z',
        page: 1,
        size: 50,
        sort: 'created_at,DESC',
      },
    });
  });

  // ── Notification channels ────────────────────────────────────────────────
  it('listChannels GETs /admin/notification-channels', async () => {
    get.mockResolvedValueOnce({ data: [channelFixture] });
    const result = await adminApi.listChannels();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/notification-channels');
    expect(result).toHaveLength(1);
  });

  it('createChannel POSTs the body', async () => {
    post.mockResolvedValueOnce({ data: channelFixture });
    await adminApi.createChannel({
      name: 'Eng',
      channel_type: 'WEBHOOK',
      config: { url: 'https://hooks.example.com', secret: 'topsecret' },
    });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/notification-channels', {
      name: 'Eng',
      channel_type: 'WEBHOOK',
      config: { url: 'https://hooks.example.com', secret: 'topsecret' },
    });
  });

  it('updateChannel PUTs /admin/notification-channels/{id}', async () => {
    put.mockResolvedValueOnce({ data: channelFixture });
    await adminApi.updateChannel('ch-1', { name: 'Eng v2' });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/notification-channels/ch-1', {
      name: 'Eng v2',
    });
  });

  it('testChannel POSTs to /test with the optional email body', async () => {
    post.mockResolvedValueOnce({ data: { status: 'OK', detail: 'sent' } });
    await adminApi.testChannel('ch-1', { email: 'ops@example.com' });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/notification-channels/ch-1/test', {
      email: 'ops@example.com',
    });
  });

  // ── AI config ─────────────────────────────────────────────────────────────
  it('getAiConfig GETs /admin/ai-config', async () => {
    get.mockResolvedValueOnce({ data: { provider: 'ANTHROPIC' } });
    await adminApi.getAiConfig();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/ai-config');
  });

  it('updateAiConfig PUTs the body', async () => {
    put.mockResolvedValueOnce({ data: { provider: 'OPENAI' } });
    await adminApi.updateAiConfig({ provider: 'OPENAI', model: 'gpt-4o' });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/ai-config', {
      provider: 'OPENAI',
      model: 'gpt-4o',
    });
  });

  it('testAiConfig POSTs /admin/ai-config/test', async () => {
    post.mockResolvedValueOnce({ data: { status: 'OK', detail: 'ok' } });
    await adminApi.testAiConfig();
    expect(post).toHaveBeenCalledWith('/api/v1/admin/ai-config/test');
  });

  // ── SAML config ───────────────────────────────────────────────────────────
  it('getSamlConfig GETs /admin/saml-config', async () => {
    get.mockResolvedValueOnce({ data: { active: false } });
    await adminApi.getSamlConfig();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/saml-config');
  });

  it('updateSamlConfig PUTs the body', async () => {
    put.mockResolvedValueOnce({ data: { active: true } });
    await adminApi.updateSamlConfig({ active: true });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/saml-config', { active: true });
  });

  // ── Key factories ────────────────────────────────────────────────────────
  it('userKeys produce stable factory output', () => {
    expect(adminApi.userKeys.all).toEqual(['users']);
    expect(adminApi.userKeys.lists()).toEqual(['users', 'list']);
    expect(adminApi.userKeys.list({ page: 0 })).toEqual(['users', 'list', { page: 0 }]);
    expect(adminApi.userKeys.detail('u-1')).toEqual(['users', 'detail', 'u-1']);
  });

  it('auditKeys produce stable factory output', () => {
    expect(adminApi.auditKeys.all).toEqual(['audit']);
    expect(adminApi.auditKeys.list({ action: 'X' })).toEqual([
      'audit', 'list', { action: 'X' },
    ]);
  });

  it('notificationChannelKeys produce stable factory output', () => {
    expect(adminApi.notificationChannelKeys.all).toEqual(['notificationChannels']);
    expect(adminApi.notificationChannelKeys.detail('ch-1')).toEqual([
      'notificationChannels', 'detail', 'ch-1',
    ]);
  });

  it('aiConfigKeys / samlConfigKeys produce stable factory output', () => {
    expect(adminApi.aiConfigKeys.current()).toEqual(['aiConfig', 'current']);
    expect(adminApi.samlConfigKeys.current()).toEqual(['samlConfig', 'current']);
  });
});
