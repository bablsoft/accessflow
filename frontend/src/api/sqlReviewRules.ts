import { apiClient } from './client';
import type {
  SqlReviewCustomRule,
  SqlReviewCustomRuleWriteRequest,
  SqlReviewRuleTestRequest,
  SqlReviewRuleTestResponse,
} from '@/types/api';

const ADMIN_BASE = '/api/v1/admin/sql-review-rules';

export const sqlReviewRuleKeys = {
  all: ['sqlReviewCustomRules'] as const,
  list: () => ['sqlReviewCustomRules', 'list'] as const,
  detail: (id: string) => ['sqlReviewCustomRules', id] as const,
};

/** The organization's custom SQL review rules, enabled and disabled (#1010). */
export async function listSqlReviewCustomRules(): Promise<SqlReviewCustomRule[]> {
  const { data } = await apiClient.get<SqlReviewCustomRule[]>(ADMIN_BASE);
  return data;
}

export async function getSqlReviewCustomRule(id: string): Promise<SqlReviewCustomRule> {
  const { data } = await apiClient.get<SqlReviewCustomRule>(`${ADMIN_BASE}/${id}`);
  return data;
}

export async function createSqlReviewCustomRule(
  payload: SqlReviewCustomRuleWriteRequest,
): Promise<SqlReviewCustomRule> {
  const { data } = await apiClient.post<SqlReviewCustomRule>(ADMIN_BASE, payload);
  return data;
}

export async function updateSqlReviewCustomRule(
  id: string,
  payload: SqlReviewCustomRuleWriteRequest,
): Promise<SqlReviewCustomRule> {
  const { data } = await apiClient.put<SqlReviewCustomRule>(`${ADMIN_BASE}/${id}`, payload);
  return data;
}

/** Also removes the rule's ruleset configs; recorded findings keep their snapshotted message. */
export async function deleteSqlReviewCustomRule(id: string): Promise<void> {
  await apiClient.delete(`${ADMIN_BASE}/${id}`);
}

/** Evaluates an unsaved draft against SQL — nothing is persisted or audited. */
export async function testSqlReviewCustomRule(
  input: SqlReviewRuleTestRequest,
): Promise<SqlReviewRuleTestResponse> {
  const { data } = await apiClient.post<SqlReviewRuleTestResponse>(`${ADMIN_BASE}/test`, input);
  return data;
}
