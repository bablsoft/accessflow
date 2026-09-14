import { apiClient } from './client';
import type {
  SqlReviewEvaluateInput,
  SqlReviewEvaluation,
  SqlReviewRule,
  SqlReviewRuleset,
  SqlReviewRulesetWriteRequest,
} from '@/types/api';

const BASE = '/api/v1/sql-review';
const ADMIN_BASE = '/api/v1/admin/sql-review-rulesets';

export const sqlReviewKeys = {
  all: ['sqlReview'] as const,
  rules: () => ['sqlReview', 'rules'] as const,
  rulesets: () => ['sqlReview', 'rulesets'] as const,
  ruleset: (id: string) => ['sqlReview', 'rulesets', id] as const,
  // The raw SQL is part of the key: every distinct draft the author pauses on is its own
  // evaluation, and TanStack dedupes a re-typed draft against the cached one.
  evaluation: (datasourceId: string, sql: string) =>
    ['sqlReview', 'evaluation', datasourceId, sql] as const,
};

/** Read-only live-lint evaluation — nothing is persisted or audited (#863). */
export async function evaluateSqlReview(input: SqlReviewEvaluateInput): Promise<SqlReviewEvaluation> {
  const { data } = await apiClient.post<SqlReviewEvaluation>(`${BASE}/evaluate`, input);
  return data;
}

/** The localized built-in rule catalog (`SQL_REVIEW_MANAGE`). */
export async function getSqlReviewRules(): Promise<SqlReviewRule[]> {
  const { data } = await apiClient.get<SqlReviewRule[]>(`${BASE}/rules`);
  return data;
}

export async function listSqlReviewRulesets(): Promise<SqlReviewRuleset[]> {
  const { data } = await apiClient.get<SqlReviewRuleset[]>(ADMIN_BASE);
  return data;
}

export async function getSqlReviewRuleset(id: string): Promise<SqlReviewRuleset> {
  const { data } = await apiClient.get<SqlReviewRuleset>(`${ADMIN_BASE}/${id}`);
  return data;
}

export async function createSqlReviewRuleset(
  payload: SqlReviewRulesetWriteRequest,
): Promise<SqlReviewRuleset> {
  const { data } = await apiClient.post<SqlReviewRuleset>(ADMIN_BASE, payload);
  return data;
}

export async function updateSqlReviewRuleset(
  id: string,
  payload: SqlReviewRulesetWriteRequest,
): Promise<SqlReviewRuleset> {
  const { data } = await apiClient.put<SqlReviewRuleset>(`${ADMIN_BASE}/${id}`, payload);
  return data;
}

export async function deleteSqlReviewRuleset(id: string): Promise<void> {
  await apiClient.delete(`${ADMIN_BASE}/${id}`);
}
