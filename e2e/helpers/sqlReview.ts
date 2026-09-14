import type { APIRequestContext } from '@playwright/test';
import { apiBase } from './datasources';

/** One ruleset as `/api/v1/admin/sql-review-rulesets` returns it (#863). */
export interface SqlReviewRulesetSummary {
  id: string;
  name: string;
  /** Absent on the organization-wide default ruleset. */
  environment?: string;
  enabled: boolean;
}

export interface SqlReviewRuleConfigInput {
  rule_id: string;
  severity: 'OFF' | 'WARN' | 'BLOCK';
  params?: Record<string, string[]>;
}

/** Creates a SQL review ruleset (#865; admin-only — PERM_SQL_REVIEW_MANAGE). */
export async function createSqlReviewRulesetViaApi(
  request: APIRequestContext,
  token: string,
  options: {
    name: string;
    environment?: string;
    enabled?: boolean;
    rules?: SqlReviewRuleConfigInput[];
  },
): Promise<SqlReviewRulesetSummary> {
  const res = await request.post(`${apiBase()}/api/v1/admin/sql-review-rulesets`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: options.name,
      ...(options.environment === undefined ? {} : { environment: options.environment }),
      // Send every boolean explicitly — an absent primitive boolean 500s under Jackson 3.
      enabled: options.enabled ?? true,
      rules: options.rules ?? [],
    },
  });
  if (!res.ok()) {
    throw new Error(`Create SQL review ruleset failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as SqlReviewRulesetSummary;
}

export async function listSqlReviewRulesetsViaApi(
  request: APIRequestContext,
  token: string,
): Promise<SqlReviewRulesetSummary[]> {
  const res = await request.get(`${apiBase()}/api/v1/admin/sql-review-rulesets`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok()) {
    throw new Error(`List SQL review rulesets failed: ${res.status()} ${await res.text()}`);
  }
  return (await res.json()) as SqlReviewRulesetSummary[];
}

/** Idempotent: a 404 (already gone) is not an error, so afterAll cleanup can be unconditional. */
export async function deleteSqlReviewRulesetViaApi(
  request: APIRequestContext,
  token: string,
  id: string,
): Promise<void> {
  const res = await request.delete(`${apiBase()}/api/v1/admin/sql-review-rulesets/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok() && res.status() !== 404) {
    throw new Error(`Delete SQL review ruleset failed: ${res.status()} ${await res.text()}`);
  }
}

/**
 * Removes any ruleset bound to `environment` — a previous failed run may have left one behind,
 * and the backend allows exactly one per environment per organization.
 */
export async function deleteSqlReviewRulesetsForEnvironmentViaApi(
  request: APIRequestContext,
  token: string,
  environment: string,
): Promise<void> {
  const rulesets = await listSqlReviewRulesetsViaApi(request, token);
  for (const rs of rulesets.filter((r) => r.environment === environment)) {
    await deleteSqlReviewRulesetViaApi(request, token, rs.id);
  }
}
