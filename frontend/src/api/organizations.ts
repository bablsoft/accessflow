import { apiClient } from './client';
import type {
  CreateOrganizationInput,
  Organization,
  OrganizationPage,
  OrganizationUsage,
  UpdateOrganizationInput,
} from '@/types/api';

const BASE = '/api/v1/platform/organizations';

export interface OrganizationListParams {
  page?: number;
  size?: number;
}

export const organizationKeys = {
  all: ['organizations'] as const,
  lists: () => ['organizations', 'list'] as const,
  list: (params: OrganizationListParams) => ['organizations', 'list', params] as const,
  details: () => ['organizations', 'detail'] as const,
  detail: (id: string) => ['organizations', 'detail', id] as const,
  usage: (id: string) => ['organizations', 'usage', id] as const,
};

export async function listOrganizations(
  params: OrganizationListParams = {},
): Promise<OrganizationPage> {
  const query: Record<string, number> = {};
  if (typeof params.page === 'number') query.page = params.page;
  if (typeof params.size === 'number') query.size = params.size;
  const { data } = await apiClient.get<OrganizationPage>(BASE, { params: query });
  return data;
}

export async function getOrganization(id: string): Promise<Organization> {
  const { data } = await apiClient.get<Organization>(`${BASE}/${id}`);
  return data;
}

export async function getOrganizationUsage(id: string): Promise<OrganizationUsage> {
  const { data } = await apiClient.get<OrganizationUsage>(`${BASE}/${id}/usage`);
  return data;
}

export async function createOrganization(
  input: CreateOrganizationInput,
): Promise<Organization> {
  const { data } = await apiClient.post<Organization>(BASE, input);
  return data;
}

export async function updateOrganization(
  id: string,
  input: UpdateOrganizationInput,
): Promise<Organization> {
  const { data } = await apiClient.put<Organization>(`${BASE}/${id}`, input);
  return data;
}

export async function disableOrganization(id: string): Promise<void> {
  await apiClient.post(`${BASE}/${id}/disable`);
}

export async function enableOrganization(id: string): Promise<void> {
  await apiClient.post(`${BASE}/${id}/enable`);
}
