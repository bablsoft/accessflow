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

import * as rolesApi from './roles';

const roleFixture = {
  id: 'r-1',
  organization_id: 'org-1',
  name: 'Release Manager',
  description: 'Can review queries and manage review plans',
  system: false,
  permissions: ['QUERY_REVIEW', 'REVIEW_PLAN_MANAGE'],
  assigned_user_count: 2,
  created_at: '2026-07-01T00:00:00Z',
  updated_at: '2026-07-02T00:00:00Z',
};

describe('api/roles', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('listRoles GETs /admin/roles and unwraps the roles array', async () => {
    get.mockResolvedValueOnce({ data: { roles: [roleFixture] } });
    const result = await rolesApi.listRoles();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/roles');
    expect(result).toEqual([roleFixture]);
  });

  it('getRole GETs /admin/roles/{id}', async () => {
    get.mockResolvedValueOnce({ data: roleFixture });
    const result = await rolesApi.getRole('r-1');
    expect(get).toHaveBeenCalledWith('/api/v1/admin/roles/r-1');
    expect(result.name).toBe('Release Manager');
  });

  it('createRole POSTs the body to /admin/roles', async () => {
    post.mockResolvedValueOnce({ data: roleFixture });
    const input = {
      name: 'Release Manager',
      description: 'desc',
      permissions: ['QUERY_REVIEW'],
    };
    await rolesApi.createRole(input);
    expect(post).toHaveBeenCalledWith('/api/v1/admin/roles', input);
  });

  it('updateRole PUTs the body to /admin/roles/{id}', async () => {
    put.mockResolvedValueOnce({ data: roleFixture });
    const input = { permissions: ['QUERY_REVIEW', 'QUERY_VIEW_ALL'] };
    await rolesApi.updateRole('r-1', input);
    expect(put).toHaveBeenCalledWith('/api/v1/admin/roles/r-1', input);
  });

  it('deleteRole DELETEs /admin/roles/{id}', async () => {
    del.mockResolvedValueOnce({});
    await rolesApi.deleteRole('r-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/roles/r-1');
  });

  it('getPermissionCatalog GETs /admin/permissions', async () => {
    const catalog = { groups: [{ group: 'QUERIES', permissions: ['QUERY_SUBMIT_SELECT'] }] };
    get.mockResolvedValueOnce({ data: catalog });
    const result = await rolesApi.getPermissionCatalog();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/permissions');
    expect(result).toEqual(catalog);
  });

  it('exposes hierarchical query key factories', () => {
    expect(rolesApi.roleKeys.all).toEqual(['roles']);
    expect(rolesApi.roleKeys.lists()).toEqual(['roles', 'list']);
    expect(rolesApi.roleKeys.detail('r-1')).toEqual(['roles', 'detail', 'r-1']);
    expect(rolesApi.permissionCatalogKeys.catalog()).toEqual(['permissionCatalog', 'catalog']);
  });
});
