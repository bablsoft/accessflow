import { apiClient } from './client';
import type {
  CreateRoleInput,
  PermissionCatalog,
  RoleDetail,
  RoleSummary,
  UpdateRoleInput,
} from '@/types/api';

const ROLES_BASE = '/api/v1/admin/roles';
const PERMISSIONS_BASE = '/api/v1/admin/permissions';

export const roleKeys = {
  all: ['roles'] as const,
  lists: () => ['roles', 'list'] as const,
  details: () => ['roles', 'detail'] as const,
  detail: (id: string) => ['roles', 'detail', id] as const,
};

export const permissionCatalogKeys = {
  all: ['permissionCatalog'] as const,
  catalog: () => ['permissionCatalog', 'catalog'] as const,
};

export async function listRoles(): Promise<RoleSummary[]> {
  const { data } = await apiClient.get<{ roles: RoleSummary[] }>(ROLES_BASE);
  return data.roles;
}

export async function getRole(id: string): Promise<RoleDetail> {
  const { data } = await apiClient.get<RoleDetail>(`${ROLES_BASE}/${id}`);
  return data;
}

export async function createRole(input: CreateRoleInput): Promise<RoleDetail> {
  const { data } = await apiClient.post<RoleDetail>(ROLES_BASE, input);
  return data;
}

export async function updateRole(id: string, input: UpdateRoleInput): Promise<RoleDetail> {
  const { data } = await apiClient.put<RoleDetail>(`${ROLES_BASE}/${id}`, input);
  return data;
}

export async function deleteRole(id: string): Promise<void> {
  await apiClient.delete(`${ROLES_BASE}/${id}`);
}

export async function getPermissionCatalog(): Promise<PermissionCatalog> {
  const { data } = await apiClient.get<PermissionCatalog>(PERMISSIONS_BASE);
  return data;
}
