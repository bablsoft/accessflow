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
      sort: 'createdAt,DESC',
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
        sort: 'createdAt,DESC',
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

  // ── AI configs ────────────────────────────────────────────────────────────
  it('listAiConfigs GETs /admin/ai-configs', async () => {
    get.mockResolvedValueOnce({ data: [] });
    await adminApi.listAiConfigs();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/ai-configs');
  });

  it('getAiConfig GETs /admin/ai-configs/{id}', async () => {
    get.mockResolvedValueOnce({ data: { id: 'a-1', provider: 'ANTHROPIC' } });
    await adminApi.getAiConfig('a-1');
    expect(get).toHaveBeenCalledWith('/api/v1/admin/ai-configs/a-1');
  });

  it('createAiConfig POSTs to /admin/ai-configs', async () => {
    post.mockResolvedValueOnce({ data: { id: 'a-1' } });
    await adminApi.createAiConfig({
      name: 'Prod',
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      api_key: 'sk-test',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/ai-configs', {
      name: 'Prod',
      provider: 'ANTHROPIC',
      model: 'claude-sonnet-4-20250514',
      api_key: 'sk-test',
    });
  });

  it('updateAiConfig PUTs the body', async () => {
    put.mockResolvedValueOnce({ data: { id: 'a-1', provider: 'OPENAI' } });
    await adminApi.updateAiConfig('a-1', { provider: 'OPENAI', model: 'gpt-4o' });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/ai-configs/a-1', {
      provider: 'OPENAI',
      model: 'gpt-4o',
    });
  });

  it('deleteAiConfig DELETEs /admin/ai-configs/{id}', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await adminApi.deleteAiConfig('a-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/ai-configs/a-1');
  });

  it('testAiConfig POSTs /admin/ai-configs/{id}/test', async () => {
    post.mockResolvedValueOnce({ data: { status: 'OK', detail: 'ok' } });
    await adminApi.testAiConfig('a-1');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/ai-configs/a-1/test');
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

  // ── OAuth2 config ─────────────────────────────────────────────────────────
  it('listOAuth2Configs GETs /admin/oauth2-config', async () => {
    get.mockResolvedValueOnce({ data: [] });
    await adminApi.listOAuth2Configs();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/oauth2-config');
  });

  it('getOAuth2Config GETs /admin/oauth2-config/{provider}', async () => {
    get.mockResolvedValueOnce({ data: { provider: 'GOOGLE' } });
    await adminApi.getOAuth2Config('GOOGLE');
    expect(get).toHaveBeenCalledWith('/api/v1/admin/oauth2-config/GOOGLE');
  });

  it('updateOAuth2Config PUTs the body to the provider path', async () => {
    put.mockResolvedValueOnce({ data: { provider: 'GITHUB' } });
    await adminApi.updateOAuth2Config('GITHUB', {
      client_id: 'c',
      client_secret: 's',
      default_role: 'ANALYST',
      active: true,
    });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/oauth2-config/GITHUB', {
      client_id: 'c',
      client_secret: 's',
      default_role: 'ANALYST',
      active: true,
    });
  });

  it('deleteOAuth2Config DELETEs the provider path', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await adminApi.deleteOAuth2Config('GITLAB');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/oauth2-config/GITLAB');
  });

  it('oauth2ConfigKeys produce stable factory output', () => {
    expect(adminApi.oauth2ConfigKeys.all).toEqual(['oauth2Config']);
    expect(adminApi.oauth2ConfigKeys.list()).toEqual(['oauth2Config', 'list']);
    expect(adminApi.oauth2ConfigKeys.detail('MICROSOFT')).toEqual([
      'oauth2Config', 'detail', 'MICROSOFT',
    ]);
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
    expect(adminApi.aiConfigKeys.all).toEqual(['aiConfig']);
    expect(adminApi.aiConfigKeys.lists()).toEqual(['aiConfig', 'list']);
    expect(adminApi.aiConfigKeys.detail('a-1')).toEqual(['aiConfig', 'detail', 'a-1']);
    expect(adminApi.samlConfigKeys.current()).toEqual(['samlConfig', 'current']);
  });

  // ── Setup progress ──────────────────────────────────────────────────────
  it('getSetupProgress GETs /admin/setup-progress', async () => {
    const fixture = {
      datasources_configured: false,
      review_plans_configured: true,
      ai_provider_configured: false,
      completed_steps: 1,
      total_steps: 3,
      complete: false,
    };
    get.mockResolvedValueOnce({ data: fixture });
    const result = await adminApi.getSetupProgress();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/setup-progress');
    expect(result).toEqual(fixture);
  });

  it('setupProgressKeys produce stable factory output', () => {
    expect(adminApi.setupProgressKeys.all).toEqual(['setupProgress']);
    expect(adminApi.setupProgressKeys.current()).toEqual(['setupProgress', 'current']);
  });

  // ── System SMTP ───────────────────────────────────────────────────────────
  const smtpFixture = {
    organization_id: 'org-1',
    host: 'smtp.example.com',
    port: 587,
    username: 'user',
    smtp_password: '********',
    tls: true,
    from_address: 'no-reply@example.com',
    from_name: 'AccessFlow',
    updated_at: '2026-05-13T00:00:00Z',
  };

  it('getSystemSmtp GETs /admin/system-smtp and returns body', async () => {
    get.mockResolvedValueOnce({ data: smtpFixture });
    const result = await adminApi.getSystemSmtp();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/system-smtp');
    expect(result?.host).toBe('smtp.example.com');
  });

  it('getSystemSmtp returns null on 404', async () => {
    get.mockRejectedValueOnce({ response: { status: 404 } });
    const result = await adminApi.getSystemSmtp();
    expect(result).toBeNull();
  });

  it('getSystemSmtp rethrows non-404 errors', async () => {
    get.mockRejectedValueOnce({ response: { status: 500 } });
    await expect(adminApi.getSystemSmtp()).rejects.toMatchObject({
      response: { status: 500 },
    });
  });

  it('updateSystemSmtp PUTs the payload', async () => {
    put.mockResolvedValueOnce({ data: smtpFixture });
    await adminApi.updateSystemSmtp({
      host: 'smtp.example.com',
      port: 587,
      tls: true,
      from_address: 'no-reply@example.com',
    });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/system-smtp', {
      host: 'smtp.example.com',
      port: 587,
      tls: true,
      from_address: 'no-reply@example.com',
    });
  });

  it('deleteSystemSmtp DELETEs the endpoint', async () => {
    del.mockResolvedValueOnce({});
    await adminApi.deleteSystemSmtp();
    expect(del).toHaveBeenCalledWith('/api/v1/admin/system-smtp');
  });

  it('testSystemSmtp POSTs to /test with the input', async () => {
    post.mockResolvedValueOnce({});
    await adminApi.testSystemSmtp({ to: 'to@example.com' });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/system-smtp/test', {
      to: 'to@example.com',
    });
  });

  it('testSystemSmtp defaults to empty body when omitted', async () => {
    post.mockResolvedValueOnce({});
    await adminApi.testSystemSmtp();
    expect(post).toHaveBeenCalledWith('/api/v1/admin/system-smtp/test', {});
  });

  it('systemSmtpKeys produce stable factory output', () => {
    expect(adminApi.systemSmtpKeys.all).toEqual(['systemSmtp']);
    expect(adminApi.systemSmtpKeys.current()).toEqual(['systemSmtp', 'current']);
  });

  // ── User invitations ───────────────────────────────────────────────────────
  const invitationFixture = {
    id: 'inv-1',
    organization_id: 'org-1',
    email: 'alice@example.com',
    display_name: 'Alice',
    role: 'ANALYST' as const,
    status: 'PENDING' as const,
    expires_at: '2026-05-20T00:00:00Z',
    accepted_at: null,
    revoked_at: null,
    invited_by_user_id: 'u-1',
    created_at: '2026-05-13T00:00:00Z',
  };

  it('listInvitations GETs /admin/users/invitations with paging', async () => {
    get.mockResolvedValueOnce({
      data: { content: [invitationFixture], page: 0, size: 20, total_elements: 1, total_pages: 1 },
    });
    const result = await adminApi.listInvitations({ page: 0, size: 20, sort: 'createdAt,desc' });
    expect(get).toHaveBeenCalledWith('/api/v1/admin/users/invitations', {
      params: { page: 0, size: 20, sort: 'createdAt,desc' },
    });
    expect(result.content[0]?.email).toBe('alice@example.com');
  });

  it('listInvitations omits unset params', async () => {
    get.mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 },
    });
    await adminApi.listInvitations();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/users/invitations', { params: {} });
  });

  it('createInvitation POSTs the payload', async () => {
    post.mockResolvedValueOnce({ data: invitationFixture });
    await adminApi.createInvitation({
      email: 'alice@example.com',
      role: 'ANALYST',
    });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/users/invitations', {
      email: 'alice@example.com',
      role: 'ANALYST',
    });
  });

  it('resendInvitation POSTs to /resend', async () => {
    post.mockResolvedValueOnce({ data: invitationFixture });
    await adminApi.resendInvitation('inv-1');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/users/invitations/inv-1/resend');
  });

  it('revokeInvitation DELETEs the invitation', async () => {
    del.mockResolvedValueOnce({});
    await adminApi.revokeInvitation('inv-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/users/invitations/inv-1');
  });

  it('invitationKeys produce stable factory output', () => {
    expect(adminApi.invitationKeys.all).toEqual(['invitations']);
    expect(adminApi.invitationKeys.lists()).toEqual(['invitations', 'list']);
    expect(adminApi.invitationKeys.list({ page: 0, size: 20 })).toEqual([
      'invitations',
      'list',
      { page: 0, size: 20 },
    ]);
  });
});
