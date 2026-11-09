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

import * as api from './apiConnectors';
import { apiConnectorKeys } from './apiConnectors';

const connectorFixture = {
  id: 'c-1',
  organization_id: 'org-1',
  name: 'Stripe',
  protocol: 'REST',
  base_url: 'https://api.stripe.com',
  auth_method: 'NONE',
  active: true,
  created_at: '2026-05-04T10:15:00Z',
};

const permissionFixture = {
  id: 'perm-1',
  user_id: 'u-1',
  user_email: 'a@example.com',
  user_display_name: 'Alice',
  can_read: true,
  can_write: false,
  can_break_glass: false,
  can_override_variables: false,
  expires_at: null,
  allowed_operations: [],
  restricted_response_fields: [],
  created_at: '2026-05-04T10:15:00Z',
};

describe('api/apiConnectors', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(apiConnectorKeys.list({ page: 1 })).toEqual(['api-connectors', 'list', { page: 1 }]);
    expect(apiConnectorKeys.detail('c-1')).toEqual(['api-connectors', 'detail', 'c-1']);
    expect(apiConnectorKeys.permissions('c-1')).toEqual([
      'api-connectors',
      'detail',
      'c-1',
      'permissions',
    ]);
    expect(apiConnectorKeys.operations('c-1')).toEqual([
      'api-connectors',
      'detail',
      'c-1',
      'operations',
    ]);
    expect(apiConnectorKeys.schemas('c-1')).toEqual([
      'api-connectors',
      'detail',
      'c-1',
      'schemas',
    ]);
  });

  it('lists connectors with pagination params', async () => {
    get.mockResolvedValue({ data: { content: [connectorFixture], total_elements: 1 } });
    const page = await api.listApiConnectors({ page: 2, size: 10 });
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors', { params: { page: 2, size: 10 } });
    expect(page.content).toHaveLength(1);
  });

  it('omits pagination params when not provided', async () => {
    get.mockResolvedValue({ data: { content: [] } });
    await api.listApiConnectors();
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors', { params: {} });
  });

  it('gets a connector', async () => {
    get.mockResolvedValue({ data: connectorFixture });
    const c = await api.getApiConnector('c-1');
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1');
    expect(c.id).toBe('c-1');
  });

  it('creates a connector', async () => {
    post.mockResolvedValue({ data: connectorFixture });
    await api.createApiConnector({ name: 'Stripe' } as never);
    expect(post).toHaveBeenCalledWith('/api/v1/api-connectors', { name: 'Stripe' });
  });

  it('updates a connector', async () => {
    put.mockResolvedValue({ data: connectorFixture });
    await api.updateApiConnector('c-1', { name: 'Stripe2' } as never);
    expect(put).toHaveBeenCalledWith('/api/v1/api-connectors/c-1', { name: 'Stripe2' });
  });

  it('deletes a connector', async () => {
    del.mockResolvedValue({ data: undefined });
    await api.deleteApiConnector('c-1');
    expect(del).toHaveBeenCalledWith('/api/v1/api-connectors/c-1');
  });

  it('tests a connector', async () => {
    post.mockResolvedValue({ data: { success: true, message: 'HTTP 200' } });
    const r = await api.testApiConnector('c-1');
    expect(post).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/test');
    expect(r.success).toBe(true);
  });

  it('lists and uploads and deletes schemas', async () => {
    get.mockResolvedValue({ data: [] });
    await api.listApiSchemas('c-1');
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/schemas');

    post.mockResolvedValue({ data: { id: 's-1' } });
    await api.uploadApiSchema('c-1', { schema_type: 'OPENAPI', raw_content: '{}' });
    expect(post).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/schemas', {
      schema_type: 'OPENAPI',
      raw_content: '{}',
    });

    del.mockResolvedValue({ data: undefined });
    await api.deleteApiSchema('c-1', 's-1');
    expect(del).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/schemas/s-1');
  });

  it('lists operations', async () => {
    get.mockResolvedValue({ data: [] });
    await api.listApiOperations('c-1');
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/operations');
  });

  it('lists permissions', async () => {
    get.mockResolvedValue({ data: [permissionFixture] });
    const perms = await api.listApiConnectorPermissions('c-1');
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/permissions');
    expect(perms).toHaveLength(1);
  });

  it('grants a permission', async () => {
    post.mockResolvedValue({ data: permissionFixture });
    const input = {
      user_id: 'u-1',
      can_read: true,
      can_write: false,
      can_break_glass: false,
      can_override_variables: false,
      expires_at: null,
      allowed_operations: [],
      restricted_response_fields: [],
    };
    await api.grantApiConnectorPermission('c-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/permissions', input);
  });

  it('updates a permission via PUT with the permission id in the path', async () => {
    put.mockResolvedValue({ data: { ...permissionFixture, can_write: true } });
    const input = {
      can_read: false,
      can_write: true,
      can_break_glass: true,
      can_override_variables: false,
      expires_at: '2030-01-01T00:00:00.000Z',
      allowed_operations: ['createPet'],
      restricted_response_fields: ['data.ssn'],
    };
    const result = await api.updateApiConnectorPermission('c-1', 'perm-1', input);
    expect(put).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/permissions/perm-1', input);
    expect(result.can_write).toBe(true);
  });

  it('revokes a permission', async () => {
    del.mockResolvedValue({ data: undefined });
    await api.revokeApiConnectorPermission('c-1', 'perm-1');
    expect(del).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/permissions/perm-1');
  });

  it('lists group permissions', async () => {
    get.mockResolvedValue({ data: [{ id: 'gp-1', group_id: 'g-1', group_name: 'Analysts' }] });
    const perms = await api.listApiConnectorGroupPermissions('c-1');
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/permissions/groups');
    expect(perms).toHaveLength(1);
  });

  it('grants a group permission', async () => {
    post.mockResolvedValue({ data: { id: 'gp-1' } });
    const input = {
      group_id: 'g-1',
      can_read: true,
      can_write: false,
      can_break_glass: false,
      can_override_variables: false,
    };
    await api.grantApiConnectorGroupPermission('c-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/permissions/groups', input);
  });

  it('updates a group permission via PUT with the permission id in the path', async () => {
    put.mockResolvedValue({ data: { id: 'gp-1', group_id: 'g-1', can_write: true } });
    const input = {
      can_read: false,
      can_write: true,
      can_break_glass: true,
      can_override_variables: false,
      expires_at: '2030-01-01T00:00:00.000Z',
      allowed_operations: ['listPets'],
      restricted_response_fields: ['data.ssn'],
    };
    const result = await api.updateApiConnectorGroupPermission('c-1', 'gp-1', input);
    expect(put).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/permissions/groups/gp-1', input);
    expect(result.can_write).toBe(true);
  });

  it('revokes a group permission', async () => {
    del.mockResolvedValue({ data: undefined });
    await api.revokeApiConnectorGroupPermission('c-1', 'gp-1');
    expect(del).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/permissions/groups/gp-1');
  });

  it('groupPermissions key factory', () => {
    expect(api.apiConnectorKeys.groupPermissions('c-1')).toEqual([
      'api-connectors',
      'detail',
      'c-1',
      'permissions',
      'groups',
    ]);
  });

  describe('schema refresh, drift and health (#896)', () => {
    it('builds the health, revisions and findings keys', () => {
      expect(apiConnectorKeys.health('c-1')).toEqual(['api-connectors', 'detail', 'c-1', 'health']);
      expect(apiConnectorKeys.revisions('c-1', 's-1')).toEqual([
        'api-connectors',
        'detail',
        'c-1',
        'schemas',
        's-1',
        'revisions',
      ]);
      expect(apiConnectorKeys.findings('c-1')).toEqual([
        'api-connectors',
        'detail',
        'c-1',
        'drift-findings',
      ]);
      expect(apiConnectorKeys.findings('c-1', 'OPEN')).toEqual([
        'api-connectors',
        'detail',
        'c-1',
        'drift-findings',
        'OPEN',
      ]);
    });

    it('reads connector health', async () => {
      get.mockResolvedValue({ data: { status: 'HEALTHY', recent_checks: [] } });
      const health = await api.getApiConnectorHealth('c-1');
      expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/health');
      expect(health.status).toBe('HEALTHY');
    });

    it('lists schema revisions', async () => {
      get.mockResolvedValue({ data: [{ id: 'r-1' }] });
      const revisions = await api.listApiSchemaRevisions('c-1', 's-1');
      expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/schemas/s-1/revisions');
      expect(revisions).toHaveLength(1);
    });

    it('lists drift findings with and without a status filter', async () => {
      get.mockResolvedValue({ data: [] });
      await api.listApiDriftFindings('c-1', 'OPEN');
      expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/drift-findings', {
        params: { status: 'OPEN' },
      });
      await api.listApiDriftFindings('c-1');
      expect(get).toHaveBeenLastCalledWith('/api/v1/api-connectors/c-1/drift-findings', {
        params: {},
      });
    });

    it('refreshes a schema now', async () => {
      post.mockResolvedValue({ data: { finding_count: 0, unchanged: true } });
      const result = await api.refreshApiSchema('c-1', 's-1');
      expect(post).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/schemas/s-1/refresh');
      expect(result.unchanged).toBe(true);
    });

    it('updates the refresh policy, null turning it off', async () => {
      put.mockResolvedValue({ data: { id: 's-1' } });
      await api.updateApiSchemaRefreshPolicy('c-1', 's-1', 30);
      expect(put).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/schemas/s-1/refresh-policy', {
        refresh_interval_minutes: 30,
      });
      await api.updateApiSchemaRefreshPolicy('c-1', 's-1', null);
      expect(put).toHaveBeenLastCalledWith(
        '/api/v1/api-connectors/c-1/schemas/s-1/refresh-policy',
        { refresh_interval_minutes: null },
      );
    });

    it('accepts a revision', async () => {
      post.mockResolvedValue({ data: { id: 's-1' } });
      const schema = await api.acceptApiSchemaRevision('c-1', 's-1', 'r-1');
      expect(post).toHaveBeenCalledWith(
        '/api/v1/api-connectors/c-1/schemas/s-1/revisions/r-1/accept',
      );
      expect(schema.id).toBe('s-1');
    });

    it('dismisses a finding', async () => {
      post.mockResolvedValue({ data: undefined });
      await api.dismissApiDriftFinding('c-1', 'f-1');
      expect(post).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/drift-findings/f-1/dismiss');
    });
  });
});
