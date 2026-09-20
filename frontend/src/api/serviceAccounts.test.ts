import { beforeEach, describe, expect, it, vi } from 'vitest';

const { get, post, put, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put, delete: del },
}));

import * as api from './serviceAccounts';
import { serviceAccountKeys } from './serviceAccounts';

const BASE = '/api/v1/admin/service-accounts';

const accountFixture = {
  id: 'sa-1',
  email: 'ci-bot@example.com',
  display_name: 'CI bot',
  role: 'READONLY',
  role_id: 'r-1',
  role_name: 'READONLY',
  active: true,
  managed_by: 'UI',
  description: null,
  owner_user_id: null,
  owner_email: null,
  owner_display_name: null,
  mcp_tool_allow_list: null,
  rate_limit_per_minute: null,
  rate_limit_per_day: null,
  active_api_key_count: 0,
  last_used_at: null,
  last_login_at: null,
  created_at: '2026-09-10T12:00:00Z',
  updated_at: '2026-09-10T12:00:00Z',
  api_keys: [],
};

const keyFixture = {
  id: 'k-1',
  name: 'github-actions',
  key_prefix: 'af_kQ7abcde',
  bootstrap_declared: false,
  created_at: '2026-09-10T12:00:00Z',
  last_used_at: null,
  expires_at: null,
  revoked_at: null,
};

describe('api/serviceAccounts', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('exposes stable query keys', () => {
    expect(serviceAccountKeys.all).toEqual(['service-accounts']);
    expect(serviceAccountKeys.lists()).toEqual(['service-accounts', 'list']);
    expect(serviceAccountKeys.list({ page: 1 })).toEqual(['service-accounts', 'list', { page: 1 }]);
    expect(serviceAccountKeys.details()).toEqual(['service-accounts', 'detail']);
    expect(serviceAccountKeys.detail('sa-1')).toEqual(['service-accounts', 'detail', 'sa-1']);
    expect(serviceAccountKeys.delegations('sa-1')).toEqual([
      'service-accounts',
      'detail',
      'sa-1',
      'delegations',
    ]);
    expect(serviceAccountKeys.mcpTools()).toEqual(['service-accounts', 'mcp-tools']);
  });

  it('listServiceAccounts sends only the filters that are set', async () => {
    get.mockResolvedValue({
      data: { content: [accountFixture], page: 0, size: 20, total_elements: 1, total_pages: 1 },
    });

    const page = await api.listServiceAccounts({ page: 0, size: 20, managed_by: 'BOOTSTRAP' });

    expect(get).toHaveBeenCalledWith(BASE, {
      params: { page: 0, size: 20, managed_by: 'BOOTSTRAP' },
    });
    expect(page.content[0]?.id).toBe('sa-1');
  });

  it('listServiceAccounts sends no params by default', async () => {
    get.mockResolvedValue({ data: { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 } });

    await api.listServiceAccounts();

    expect(get).toHaveBeenCalledWith(BASE, { params: {} });
  });

  it('getServiceAccount GETs by id', async () => {
    get.mockResolvedValue({ data: accountFixture });

    const account = await api.getServiceAccount('sa-1');

    expect(get).toHaveBeenCalledWith(`${BASE}/sa-1`);
    expect(account.email).toBe('ci-bot@example.com');
  });

  it('createServiceAccount POSTs the input', async () => {
    post.mockResolvedValue({ data: accountFixture });

    await api.createServiceAccount({ email: 'ci-bot@example.com', display_name: 'CI bot' });

    expect(post).toHaveBeenCalledWith(BASE, { email: 'ci-bot@example.com', display_name: 'CI bot' });
  });

  it('updateServiceAccount PUTs the sparse body including clear', async () => {
    put.mockResolvedValue({ data: accountFixture });

    await api.updateServiceAccount('sa-1', { clear: ['MCP_TOOL_ALLOW_LIST'] });

    expect(put).toHaveBeenCalledWith(`${BASE}/sa-1`, { clear: ['MCP_TOOL_ALLOW_LIST'] });
  });

  it('deactivateServiceAccount DELETEs by id', async () => {
    del.mockResolvedValue({});

    await api.deactivateServiceAccount('sa-1');

    expect(del).toHaveBeenCalledWith(`${BASE}/sa-1`);
  });

  it('issueServiceAccountKey POSTs under the account', async () => {
    post.mockResolvedValue({ data: { api_key: keyFixture, raw_key: 'af_raw' } });

    const issued = await api.issueServiceAccountKey('sa-1', { name: 'github-actions' });

    expect(post).toHaveBeenCalledWith(`${BASE}/sa-1/api-keys`, { name: 'github-actions' });
    expect(issued.raw_key).toBe('af_raw');
  });

  it('rotateServiceAccountKey POSTs the rotation with its grace window', async () => {
    post.mockResolvedValue({
      data: { api_key: keyFixture, raw_key: 'af_new', superseded_key: keyFixture },
    });

    const rotated = await api.rotateServiceAccountKey('sa-1', 'k-1', {
      name: 'github-actions',
      grace_period: 'PT12H',
    });

    expect(post).toHaveBeenCalledWith(`${BASE}/sa-1/api-keys/k-1/rotate`, {
      name: 'github-actions',
      grace_period: 'PT12H',
    });
    expect(rotated.superseded_key.id).toBe('k-1');
  });

  it('revokeServiceAccountKey DELETEs the key', async () => {
    del.mockResolvedValue({});

    await api.revokeServiceAccountKey('sa-1', 'k-1');

    expect(del).toHaveBeenCalledWith(`${BASE}/sa-1/api-keys/k-1`);
  });

  it('listDelegatedPrincipals GETs the array', async () => {
    get.mockResolvedValue({ data: [] });

    const delegations = await api.listDelegatedPrincipals('sa-1');

    expect(get).toHaveBeenCalledWith(`${BASE}/sa-1/delegated-principals`);
    expect(delegations).toEqual([]);
  });

  it('grantDelegatedPrincipal POSTs the principal', async () => {
    post.mockResolvedValue({ data: { id: 'd-1' } });

    await api.grantDelegatedPrincipal('sa-1', { principal_user_id: 'u-1', expires_at: null });

    expect(post).toHaveBeenCalledWith(`${BASE}/sa-1/delegated-principals`, {
      principal_user_id: 'u-1',
      expires_at: null,
    });
  });

  it('revokeDelegatedPrincipal DELETEs the delegation', async () => {
    del.mockResolvedValue({});

    await api.revokeDelegatedPrincipal('sa-1', 'd-1');

    expect(del).toHaveBeenCalledWith(`${BASE}/sa-1/delegated-principals/d-1`);
  });

  it('listMcpTools unwraps the catalog', async () => {
    get.mockResolvedValue({ data: { tools: ['list_datasources', 'validate_sql'] } });

    const tools = await api.listMcpTools();

    expect(get).toHaveBeenCalledWith(`${BASE}/mcp-tools`);
    expect(tools).toEqual(['list_datasources', 'validate_sql']);
  });
});
