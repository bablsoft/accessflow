import { apiClient } from './client';
import type {
  AuditSink,
  CreateAuditSinkInput,
  TestAuditSinkResult,
  UpdateAuditSinkInput,
} from '@/types/api';

const BASE = '/api/v1/admin/audit-sinks';

export const auditSinkKeys = {
  all: ['audit-sinks'] as const,
  lists: () => ['audit-sinks', 'list'] as const,
};

export async function listAuditSinks(): Promise<AuditSink[]> {
  const { data } = await apiClient.get<AuditSink[]>(BASE);
  return data;
}

export async function createAuditSink(input: CreateAuditSinkInput): Promise<AuditSink> {
  const { data } = await apiClient.post<AuditSink>(BASE, input);
  return data;
}

export async function updateAuditSink(
  id: string,
  input: UpdateAuditSinkInput,
): Promise<AuditSink> {
  const { data } = await apiClient.put<AuditSink>(`${BASE}/${id}`, input);
  return data;
}

export async function deleteAuditSink(id: string): Promise<void> {
  await apiClient.delete(`${BASE}/${id}`);
}

export async function testAuditSink(id: string): Promise<TestAuditSinkResult> {
  const { data } = await apiClient.post<TestAuditSinkResult>(`${BASE}/${id}/test`);
  return data;
}
