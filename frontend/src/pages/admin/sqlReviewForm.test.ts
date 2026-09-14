import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import type { SqlReviewRule, SqlReviewRuleset } from '@/types/api';
import {
  ORG_DEFAULT_ENVIRONMENT,
  environmentOptions,
  isRuleRowSent,
  rulesSummary,
  rulesetEnvironmentLabel,
  toFormValues,
  toWriteRequest,
  toggleEnabledRequest,
  validateParam,
  type SqlReviewRulesetFormValues,
} from './sqlReviewForm';

const t = ((key: string) => key) as unknown as TFunction;

const selectStar: SqlReviewRule = {
  rule_id: 'select_star',
  category: 'PERFORMANCE',
  default_severity: 'WARN',
  name: 'SELECT *',
  description: 'bare star',
  params: [],
};
const protectedTable: SqlReviewRule = {
  rule_id: 'protected_table',
  category: 'DATA_PROTECTION',
  default_severity: 'BLOCK',
  name: 'Protected table',
  description: 'globs',
  params: [{ key: 'globs', required: true, defaults: [], value_pattern: '[A-Za-z0-9_$*.-]+' }],
};
const disallowedFunction: SqlReviewRule = {
  rule_id: 'disallowed_function',
  category: 'STATEMENT_SAFETY',
  default_severity: 'BLOCK',
  name: 'Disallowed function',
  description: 'names',
  params: [
    {
      key: 'names',
      required: true,
      defaults: ['pg_sleep', 'sleep'],
      value_pattern: '[A-Za-z0-9_$.-]+',
    },
  ],
};
const catalog = [selectStar, protectedTable, disallowedFunction];

const ruleset: SqlReviewRuleset = {
  id: 'rs-1',
  organization_id: 'org-1',
  name: 'Production',
  description: 'Payroll is off limits',
  environment: 'PRODUCTION',
  enabled: true,
  rules: [
    { rule_id: 'protected_table', severity: 'BLOCK', params: { globs: ['payroll.*'] } },
    { rule_id: 'select_star', severity: 'BLOCK', params: {} },
  ],
  created_at: '2026-09-11T10:00:00Z',
  updated_at: '2026-09-11T10:00:00Z',
};

describe('sqlReviewForm (#865)', () => {
  it('offers the organization default first, then every environment', () => {
    expect(environmentOptions(t).map((o) => o.value)).toEqual([
      ORG_DEFAULT_ENVIRONMENT,
      'DEVELOPMENT',
      'TEST',
      'STAGING',
      'PRODUCTION',
    ]);
    expect(environmentOptions(t)[0]!.label).toBe('admin.sql_review.environment_org_default');
  });

  it('labels a bound and a default ruleset', () => {
    expect(rulesetEnvironmentLabel(t, ruleset)).toBe('enums.datasource_environment.PRODUCTION');
    expect(rulesetEnvironmentLabel(t, { ...ruleset, environment: undefined })).toBe(
      'admin.sql_review.environment_org_default',
    );
  });

  it('seeds every catalog rule with its configured or built-in severity', () => {
    const values = toFormValues(ruleset, catalog);
    expect(values).toEqual({
      name: 'Production',
      description: 'Payroll is off limits',
      environment: 'PRODUCTION',
      enabled: true,
      rules: {
        select_star: { severity: 'BLOCK', params: {} },
        protected_table: { severity: 'BLOCK', params: { globs: ['payroll.*'] } },
        disallowed_function: { severity: 'BLOCK', params: { names: [] } },
      },
    });
  });

  it('seeds a blank form for a new ruleset as the organization default', () => {
    const values = toFormValues(null, catalog);
    expect(values.name).toBe('');
    expect(values.environment).toBe(ORG_DEFAULT_ENVIRONMENT);
    expect(values.enabled).toBe(true);
    expect(values.rules.select_star).toEqual({ severity: 'WARN', params: {} });
  });

  it('stores a row only when it differs from the default or carries params', () => {
    expect(isRuleRowSent(selectStar, { severity: 'WARN', params: {} })).toBe(false);
    expect(isRuleRowSent(selectStar, { severity: 'OFF', params: {} })).toBe(true);
    expect(isRuleRowSent(protectedTable, { severity: 'BLOCK', params: { globs: [] } })).toBe(false);
    expect(isRuleRowSent(protectedTable, { severity: 'BLOCK', params: { globs: [' a.* '] } })).toBe(true);
    expect(isRuleRowSent(selectStar, undefined)).toBe(false);
  });

  it('maps the form onto a sparse write body, omitting empty params and the default environment', () => {
    const values: SqlReviewRulesetFormValues = {
      name: '  Staging  ',
      description: '   ',
      environment: ORG_DEFAULT_ENVIRONMENT,
      enabled: false,
      rules: {
        select_star: { severity: 'WARN', params: {} },
        protected_table: { severity: 'BLOCK', params: { globs: [' payroll.* ', '', 'hr.*'] } },
        disallowed_function: { severity: 'OFF', params: { names: [] } },
      },
    };
    expect(toWriteRequest(values, catalog)).toEqual({
      name: 'Staging',
      enabled: false,
      rules: [
        { rule_id: 'protected_table', severity: 'BLOCK', params: { globs: ['payroll.*', 'hr.*'] } },
        { rule_id: 'disallowed_function', severity: 'OFF' },
      ],
    });
  });

  it('round-trips an existing ruleset through the form', () => {
    const body = toWriteRequest(toFormValues(ruleset, catalog), catalog);
    expect(body).toEqual({
      name: 'Production',
      description: 'Payroll is off limits',
      environment: 'PRODUCTION',
      enabled: true,
      rules: [
        { rule_id: 'select_star', severity: 'BLOCK' },
        { rule_id: 'protected_table', severity: 'BLOCK', params: { globs: ['payroll.*'] } },
      ],
    });
  });

  it('ignores form rows for rules the catalog no longer lists', () => {
    const values = toFormValues(null, catalog);
    values.rules.ghost = { severity: 'BLOCK', params: {} };
    expect(toWriteRequest(values, catalog).rules).toEqual([]);
  });

  it('builds a full replace body for the list-level enabled toggle', () => {
    expect(toggleEnabledRequest(ruleset, false)).toEqual({
      name: 'Production',
      description: 'Payroll is off limits',
      environment: 'PRODUCTION',
      enabled: false,
      rules: [
        { rule_id: 'protected_table', severity: 'BLOCK', params: { globs: ['payroll.*'] } },
        { rule_id: 'select_star', severity: 'BLOCK' },
      ],
    });
    expect(toggleEnabledRequest({ ...ruleset, description: undefined, environment: undefined }, true))
      .not.toHaveProperty('environment');
  });

  describe('validateParam', () => {
    const globs = protectedTable.params[0]!;
    const names = disallowedFunction.params[0]!;

    it('requires an entry only when the row is stored and the param has no defaults', () => {
      expect(validateParam(protectedTable, globs, { severity: 'BLOCK', params: { globs: [] } })).toBeNull();
      expect(validateParam(protectedTable, globs, { severity: 'OFF', params: { globs: [] } })).toEqual({
        kind: 'required',
      });
      expect(validateParam(disallowedFunction, names, { severity: 'WARN', params: { names: [] } })).toBeNull();
    });

    it('rejects an entry outside the whole-string pattern', () => {
      expect(
        validateParam(protectedTable, globs, { severity: 'BLOCK', params: { globs: ['payroll.*', 'bad glob'] } }),
      ).toEqual({ kind: 'invalid', value: 'bad glob' });
      expect(
        validateParam(protectedTable, globs, { severity: 'BLOCK', params: { globs: ['payroll.*'] } }),
      ).toBeNull();
    });

    it('skips the pattern check when the pattern cannot be compiled', () => {
      const broken = { ...globs, value_pattern: '[' };
      expect(validateParam(protectedTable, broken, { severity: 'BLOCK', params: { globs: ['(('] } })).toBeNull();
    });

    it('tolerates a missing row', () => {
      expect(validateParam(protectedTable, globs, undefined)).toBeNull();
    });
  });

  it('summarises effective severities across the catalog', () => {
    expect(rulesSummary(ruleset, catalog)).toEqual({ block: 3, warn: 0, off: 0 });
    expect(rulesSummary({ ...ruleset, rules: [{ rule_id: 'select_star', severity: 'OFF', params: {} }] }, catalog))
      .toEqual({ block: 2, warn: 0, off: 1 });
    expect(rulesSummary({ ...ruleset, rules: [] }, catalog)).toEqual({ block: 2, warn: 1, off: 0 });
  });
});
