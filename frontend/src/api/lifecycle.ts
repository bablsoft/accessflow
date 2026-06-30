import { apiClient } from './client';
import type {
  CreateRetentionPolicyRequest,
  ErasureRequest,
  ErasureRequestPage,
  ErasureStatus,
  LifecyclePreviewResponse,
  RetentionPolicy,
  RetentionPolicyPage,
  SubmitErasureRequest,
  UpdateRetentionPolicyRequest,
} from '@/types/api';

const POLICY_BASE = '/api/v1/lifecycle/policies';
const ERASURE_BASE = '/api/v1/lifecycle/erasure-requests';
const ADMIN_ERASURE_BASE = '/api/v1/admin/lifecycle/erasure-requests';

export interface PolicyListFilters {
  page?: number;
  size?: number;
}

export interface ErasureListFilters {
  page?: number;
  size?: number;
}

export const lifecycleKeys = {
  all: ['lifecycle'] as const,
  policies: () => ['lifecycle', 'policies'] as const,
  policyList: (filters: PolicyListFilters) => ['lifecycle', 'policies', 'list', filters] as const,
  policyDetail: (id: string) => ['lifecycle', 'policies', 'detail', id] as const,
  erasures: () => ['lifecycle', 'erasures'] as const,
  erasureMine: (filters: ErasureListFilters) => ['lifecycle', 'erasures', 'mine', filters] as const,
  erasureQueue: (filters: ErasureListFilters) =>
    ['lifecycle', 'erasures', 'queue', filters] as const,
};

// ── Retention policies (admin) ───────────────────────────────────────────────

export async function listPolicies(filters: PolicyListFilters = {}): Promise<RetentionPolicyPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<RetentionPolicyPage>(POLICY_BASE, { params });
  return data;
}

export async function getPolicy(id: string): Promise<RetentionPolicy> {
  const { data } = await apiClient.get<RetentionPolicy>(`${POLICY_BASE}/${id}`);
  return data;
}

export async function createPolicy(body: CreateRetentionPolicyRequest): Promise<RetentionPolicy> {
  const { data } = await apiClient.post<RetentionPolicy>(POLICY_BASE, body);
  return data;
}

export async function updatePolicy(
  id: string,
  body: UpdateRetentionPolicyRequest,
): Promise<RetentionPolicy> {
  const { data } = await apiClient.put<RetentionPolicy>(`${POLICY_BASE}/${id}`, body);
  return data;
}

export async function deletePolicy(id: string): Promise<void> {
  await apiClient.delete(`${POLICY_BASE}/${id}`);
}

export async function previewPolicy(id: string): Promise<LifecyclePreviewResponse> {
  const { data } = await apiClient.post<LifecyclePreviewResponse>(`${POLICY_BASE}/${id}/preview`);
  return data;
}

// ── Erasure requests ─────────────────────────────────────────────────────────

export async function submitErasure(body: SubmitErasureRequest): Promise<ErasureRequest> {
  const { data } = await apiClient.post<ErasureRequest>(ERASURE_BASE, body);
  return data;
}

export async function listMyErasures(filters: ErasureListFilters = {}): Promise<ErasureRequestPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<ErasureRequestPage>(ERASURE_BASE, { params });
  return data;
}

export async function cancelErasure(id: string): Promise<void> {
  await apiClient.post(`${ERASURE_BASE}/${id}/cancel`);
}

export async function listErasureQueue(
  filters: ErasureListFilters = {},
): Promise<ErasureRequestPage> {
  const params: Record<string, number> = {};
  if (typeof filters.page === 'number') params.page = filters.page;
  if (typeof filters.size === 'number') params.size = filters.size;
  const { data } = await apiClient.get<ErasureRequestPage>(ADMIN_ERASURE_BASE, { params });
  return data;
}

export async function approveErasure(id: string, comment?: string): Promise<ErasureRequest> {
  const { data } = await apiClient.post<ErasureRequest>(`${ADMIN_ERASURE_BASE}/${id}/approve`, {
    comment,
  });
  return data;
}

export async function rejectErasure(id: string, comment?: string): Promise<ErasureRequest> {
  const { data } = await apiClient.post<ErasureRequest>(`${ADMIN_ERASURE_BASE}/${id}/reject`, {
    comment,
  });
  return data;
}

export type { ErasureStatus };
