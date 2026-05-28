import { apiClient } from './client';
import type {
  CreateUserGroupInput,
  PageEnvelope,
  UpdateUserGroupInput,
  UserGroup,
  UserGroupMember,
  UserGroupPage,
} from '@/types/api';

const BASE = '/api/v1/admin/groups';

export const groupKeys = {
  all: ['groups'] as const,
  lists: () => ['groups', 'list'] as const,
  list: (filters: { page?: number; size?: number; sort?: string }) =>
    ['groups', 'list', filters] as const,
  detail: (id: string) => ['groups', 'detail', id] as const,
  members: (id: string) => ['groups', 'members', id] as const,
};

export async function listGroups(
  filters: { page?: number; size?: number; sort?: string } = {},
): Promise<UserGroupPage> {
  const params: Record<string, string | number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  if (filters.sort) params.sort = filters.sort;
  const { data } = await apiClient.get<UserGroupPage>(BASE, { params });
  return data;
}

export async function listAllGroups(): Promise<UserGroup[]> {
  // List with a large size to gather every group for selectors.
  const { data } = await apiClient.get<PageEnvelope<UserGroup>>(BASE, {
    params: { page: 0, size: 500 },
  });
  return data.content;
}

export async function getGroup(id: string): Promise<UserGroup> {
  const { data } = await apiClient.get<UserGroup>(`${BASE}/${id}`);
  return data;
}

export async function createGroup(input: CreateUserGroupInput): Promise<UserGroup> {
  const { data } = await apiClient.post<UserGroup>(BASE, input);
  return data;
}

export async function updateGroup(id: string, input: UpdateUserGroupInput): Promise<UserGroup> {
  const { data } = await apiClient.put<UserGroup>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteGroup(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function listGroupMembers(id: string): Promise<UserGroupMember[]> {
  const { data } = await apiClient.get<{ members: UserGroupMember[] }>(`${BASE}/${id}/members`);
  return data.members;
}

export async function addGroupMember(id: string, userId: string): Promise<UserGroupMember> {
  const { data } = await apiClient.post<UserGroupMember>(`${BASE}/${id}/members`, { userId });
  return data;
}

export async function removeGroupMember(id: string, userId: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}/members/${userId}`);
}
