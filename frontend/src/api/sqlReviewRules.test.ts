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

import * as api from './sqlReviewRules';
import type { SqlReviewCustomRule, SqlReviewCustomRuleWriteRequest } from '@/types/api';

const write: SqlReviewCustomRuleWriteRequest = {
  rule_id: 'custom_no_billing_delete',
  name: 'No billing delete',
  message: 'Unbounded write on {tables}',
  category: 'STATEMENT_SAFETY',
  default_severity: 'WARN',
  enabled: true,
  condition: { type: 'and', children: [{ type: 'referenced_table', globs: ['billing.*'] }] },
};

const rule: SqlReviewCustomRule = {
  ...write,
  id: 'r-1',
  organization_id: 'org-1',
  created_at: '2026-10-01T10:00:00Z',
  updated_at: '2026-10-01T10:00:00Z',
};

describe('sqlReviewRules api', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('exposes domain-prefixed query keys', () => {
    expect(api.sqlReviewRuleKeys.all).toEqual(['sqlReviewCustomRules']);
    expect(api.sqlReviewRuleKeys.list()).toEqual(['sqlReviewCustomRules', 'list']);
    expect(api.sqlReviewRuleKeys.detail('r-1')).toEqual(['sqlReviewCustomRules', 'r-1']);
  });

  it('lists and reads rules', async () => {
    get.mockResolvedValueOnce({ data: [rule] }).mockResolvedValueOnce({ data: rule });
    await expect(api.listSqlReviewCustomRules()).resolves.toEqual([rule]);
    await expect(api.getSqlReviewCustomRule('r-1')).resolves.toEqual(rule);
    expect(get).toHaveBeenNthCalledWith(1, '/api/v1/admin/sql-review-rules');
    expect(get).toHaveBeenNthCalledWith(2, '/api/v1/admin/sql-review-rules/r-1');
  });

  it('creates, updates and deletes a rule', async () => {
    post.mockResolvedValueOnce({ data: rule });
    put.mockResolvedValueOnce({ data: rule });
    del.mockResolvedValueOnce({});
    await expect(api.createSqlReviewCustomRule(write)).resolves.toEqual(rule);
    await expect(api.updateSqlReviewCustomRule('r-1', write)).resolves.toEqual(rule);
    await api.deleteSqlReviewCustomRule('r-1');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/sql-review-rules', write);
    expect(put).toHaveBeenCalledWith('/api/v1/admin/sql-review-rules/r-1', write);
    expect(del).toHaveBeenCalledWith('/api/v1/admin/sql-review-rules/r-1');
  });

  it('tests a draft against SQL', async () => {
    const findings = [
      { rule_id: write.rule_id, severity: 'WARN', statement_index: 0, message: 'Unbounded write' },
    ];
    post.mockResolvedValueOnce({ data: { findings } });
    const input = { rule: write, sql: 'DELETE FROM billing.invoices', dialect: 'POSTGRESQL' as const };
    await expect(api.testSqlReviewCustomRule(input)).resolves.toEqual({ findings });
    expect(post).toHaveBeenCalledWith('/api/v1/admin/sql-review-rules/test', input);
  });
});
