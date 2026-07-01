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
});
