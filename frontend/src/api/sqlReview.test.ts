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

import * as sqlReviewApi from './sqlReview';
import type { SqlReviewRuleset, SqlReviewRulesetWriteRequest } from '@/types/api';

const rulesetFixture: SqlReviewRuleset = {
  id: 'rs-1',
  organization_id: 'org-1',
  name: 'Production',
  environment: 'PRODUCTION',
  enabled: true,
  rules: [{ rule_id: 'select_star', severity: 'BLOCK', params: {} }],
  created_at: '2026-09-11T10:00:00Z',
  updated_at: '2026-09-11T10:00:00Z',
};

const writeFixture: SqlReviewRulesetWriteRequest = {
  name: 'Production',
  environment: 'PRODUCTION',
  enabled: true,
  rules: [{ rule_id: 'select_star', severity: 'BLOCK' }],
};

describe('sqlReview api', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('exposes hierarchical, domain-prefixed query keys', () => {
    expect(sqlReviewApi.sqlReviewKeys.all).toEqual(['sqlReview']);
    expect(sqlReviewApi.sqlReviewKeys.rules()).toEqual(['sqlReview', 'rules']);
    expect(sqlReviewApi.sqlReviewKeys.rulesets()).toEqual(['sqlReview', 'rulesets']);
    expect(sqlReviewApi.sqlReviewKeys.ruleset('rs-1')).toEqual(['sqlReview', 'rulesets', 'rs-1']);
    expect(sqlReviewApi.sqlReviewKeys.evaluation('ds-1', 'SELECT 1')).toEqual([
      'sqlReview',
      'evaluation',
      'ds-1',
      'SELECT 1',
    ]);
  });

  it('evaluates SQL through POST /sql-review/evaluate', async () => {
    const evaluation = { applicable: true, findings: [] };
    post.mockResolvedValue({ data: evaluation });
    const result = await sqlReviewApi.evaluateSqlReview({ datasource_id: 'ds-1', sql: 'SELECT 1' });
    expect(post).toHaveBeenCalledWith('/api/v1/sql-review/evaluate', {
      datasource_id: 'ds-1',
      sql: 'SELECT 1',
    });
    expect(result).toEqual(evaluation);
  });

  it('loads the rule catalog', async () => {
    get.mockResolvedValue({ data: [] });
    expect(await sqlReviewApi.getSqlReviewRules()).toEqual([]);
    expect(get).toHaveBeenCalledWith('/api/v1/sql-review/rules');
  });

  it('lists rulesets', async () => {
    get.mockResolvedValue({ data: [rulesetFixture] });
    expect(await sqlReviewApi.listSqlReviewRulesets()).toEqual([rulesetFixture]);
    expect(get).toHaveBeenCalledWith('/api/v1/admin/sql-review-rulesets');
  });

  it('gets one ruleset', async () => {
    get.mockResolvedValue({ data: rulesetFixture });
    expect(await sqlReviewApi.getSqlReviewRuleset('rs-1')).toEqual(rulesetFixture);
    expect(get).toHaveBeenCalledWith('/api/v1/admin/sql-review-rulesets/rs-1');
  });

  it('creates a ruleset', async () => {
    post.mockResolvedValue({ data: rulesetFixture });
    expect(await sqlReviewApi.createSqlReviewRuleset(writeFixture)).toEqual(rulesetFixture);
    expect(post).toHaveBeenCalledWith('/api/v1/admin/sql-review-rulesets', writeFixture);
  });

  it('updates a ruleset', async () => {
    put.mockResolvedValue({ data: rulesetFixture });
    expect(await sqlReviewApi.updateSqlReviewRuleset('rs-1', writeFixture)).toEqual(rulesetFixture);
    expect(put).toHaveBeenCalledWith('/api/v1/admin/sql-review-rulesets/rs-1', writeFixture);
  });

  it('deletes a ruleset', async () => {
    del.mockResolvedValue({});
    await sqlReviewApi.deleteSqlReviewRuleset('rs-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/sql-review-rulesets/rs-1');
  });
});
