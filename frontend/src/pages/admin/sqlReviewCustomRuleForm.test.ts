import { describe, expect, it } from 'vitest';
import type { SqlReviewCustomRule, SqlRuleConditionOperand } from '@/types/api';
import { SQL_RULE_CONDITION_OPERANDS } from '@/utils/enumLabels';
import {
  FUNCTION_NAME_PATTERN,
  GLOB_PATTERN,
  NEW_RULE_DEFAULT_VALUES,
  SLUG_PATTERN,
  defaultSqlRuleRow,
  invalidEntries,
  rowsToSqlRuleCondition,
  slugOf,
  sqlRuleConditionToRows,
  toFormValues,
  toWriteRequest,
  toggleEnabledRequest,
  type SqlRuleConditionRow,
} from './sqlReviewCustomRuleForm';

const rule: SqlReviewCustomRule = {
  id: 'r-1',
  organization_id: 'org-1',
  rule_id: 'custom_billing_delete',
  name: 'Billing delete',
  description: 'No unbounded deletes on billing',
  message: 'Unbounded {statement_type} on {tables}',
  category: 'DATA_PROTECTION',
  default_severity: 'BLOCK',
  enabled: true,
  condition: {
    type: 'and',
    children: [
      { type: 'referenced_table', globs: ['billing.*'] },
      { type: 'not', child: { type: 'has_where', expected: true } },
    ],
  },
  created_at: '2026-10-01T10:00:00Z',
  updated_at: '2026-10-02T10:00:00Z',
};

describe('sqlReviewCustomRuleForm', () => {
  it('gives every operand a default row', () => {
    for (const operand of SQL_RULE_CONDITION_OPERANDS) {
      const row = defaultSqlRuleRow(operand);
      expect(row).toMatchObject({ operand, negate: false });
    }
    expect(defaultSqlRuleRow('query_type').query_types).toEqual(['DELETE']);
    expect(defaultSqlRuleRow('has_where').bool_value).toBe(false);
    expect(defaultSqlRuleRow('where_always_true').bool_value).toBe(true);
    expect(defaultSqlRuleRow('sql_matches')).toMatchObject({ pattern: '', ignore_case: true });
  });

  it('round-trips every operand through the tree', () => {
    const rows: SqlRuleConditionRow[] = [
      { operand: 'query_type', negate: false, query_types: ['UPDATE', 'OTHER'] },
      { operand: 'referenced_table', negate: true, table_globs: ['billing.*'] },
      { operand: 'referenced_column', negate: false, column_globs: ['*.ssn'] },
      { operand: 'function_called', negate: false, function_names: ['dblink'] },
      ...(
        [
          'has_where',
          'has_limit',
          'has_order_by',
          'where_always_true',
          'join_without_condition',
          'like_leading_wildcard',
          'transactional',
        ] as SqlRuleConditionOperand[]
      ).map((operand, i) => ({ operand, negate: false, bool_value: i % 2 === 0 })),
      { operand: 'sql_matches', negate: true, pattern: 'truncate\\s+', ignore_case: true },
    ];
    const tree = rowsToSqlRuleCondition('ANY', rows);
    expect(tree.type).toBe('or');
    expect(sqlRuleConditionToRows(tree)).toEqual({ matchType: 'ANY', rows, supported: true });
  });

  it('fills defaults for missing row fields and always sends booleans', () => {
    const tree = rowsToSqlRuleCondition('ALL', [
      { operand: 'query_type', negate: false },
      { operand: 'referenced_table', negate: false },
      { operand: 'referenced_column', negate: false },
      { operand: 'function_called', negate: false },
      { operand: 'has_limit', negate: false },
      { operand: 'sql_matches', negate: false },
    ]);
    expect(tree).toEqual({
      type: 'and',
      children: [
        { type: 'query_type', any_of: [] },
        { type: 'referenced_table', globs: [] },
        { type: 'referenced_column', globs: [] },
        { type: 'function_called', names: [] },
        { type: 'has_limit', expected: false },
        { type: 'sql_matches', pattern: '', ignore_case: false },
      ],
    });
  });

  it('seeds a new rule and an existing one', () => {
    expect(toFormValues(null)).toEqual({ values: NEW_RULE_DEFAULT_VALUES, supported: true });
    const { values, supported } = toFormValues(rule);
    expect(supported).toBe(true);
    expect(values).toMatchObject({
      slug: 'billing_delete',
      name: 'Billing delete',
      description: 'No unbounded deletes on billing',
      category: 'DATA_PROTECTION',
      default_severity: 'BLOCK',
      enabled: true,
      match_type: 'ALL',
    });
    expect(values.conditions).toEqual([
      { operand: 'referenced_table', negate: false, table_globs: ['billing.*'] },
      { operand: 'has_where', negate: true, bool_value: true },
    ]);
    expect(toFormValues({ ...rule, description: undefined }).values.description).toBe('');
  });

  it('flags a tree nested deeper than the builder', () => {
    const nested: SqlReviewCustomRule = {
      ...rule,
      condition: { type: 'or', children: [{ type: 'and', children: [] }] },
    };
    expect(toFormValues(nested).supported).toBe(false);
  });

  it('flags a leaf type this build does not know instead of rewriting it', () => {
    const future = {
      ...rule,
      condition: { type: 'and', children: [{ type: 'has_group_by', expected: true }] },
    } as unknown as SqlReviewCustomRule;
    expect(toFormValues(future).supported).toBe(false);
  });

  it('builds a trimmed write body with the fixed prefix', () => {
    const body = toWriteRequest({
      slug: ' billing_delete ',
      name: ' Billing delete ',
      description: '   ',
      message: ' Bad {tables} ',
      category: 'DATA_PROTECTION',
      default_severity: 'WARN',
      enabled: false,
      match_type: 'ALL',
      conditions: [
        { operand: 'referenced_table', negate: false, table_globs: [' billing.* ', ''] },
        { operand: 'referenced_column', negate: false, column_globs: ['*.ssn'] },
        { operand: 'function_called', negate: false, function_names: [' dblink'] },
      ],
    });
    expect(body).toEqual({
      rule_id: 'custom_billing_delete',
      name: 'Billing delete',
      message: 'Bad {tables}',
      category: 'DATA_PROTECTION',
      default_severity: 'WARN',
      enabled: false,
      condition: {
        type: 'and',
        children: [
          { type: 'referenced_table', globs: ['billing.*'] },
          { type: 'referenced_column', globs: ['*.ssn'] },
          { type: 'function_called', names: ['dblink'] },
        ],
      },
    });
    expect(
      toWriteRequest({ ...NEW_RULE_DEFAULT_VALUES, slug: 'abc', description: 'kept' }).description,
    ).toBe('kept');
  });

  it('resends the stored rule for the enabled toggle', () => {
    expect(toggleEnabledRequest(rule, false)).toEqual({
      rule_id: rule.rule_id,
      name: rule.name,
      description: rule.description,
      message: rule.message,
      category: rule.category,
      default_severity: rule.default_severity,
      enabled: false,
      condition: rule.condition,
    });
    expect(toggleEnabledRequest({ ...rule, description: undefined }, true)).not.toHaveProperty(
      'description',
    );
  });

  it('strips the custom_ prefix only when present', () => {
    expect(slugOf('custom_abc')).toBe('abc');
    expect(slugOf('abc')).toBe('abc');
  });

  it('mirrors the backend syntax checks', () => {
    expect(SLUG_PATTERN.test('abc')).toBe(true);
    expect(SLUG_PATTERN.test('ab')).toBe(false);
    expect(SLUG_PATTERN.test('1abc')).toBe(false);
    expect(SLUG_PATTERN.test('Abc')).toBe(false);
    expect(SLUG_PATTERN.test(`a${'b'.repeat(60)}`)).toBe(true);
    expect(SLUG_PATTERN.test(`a${'b'.repeat(61)}`)).toBe(false);
    expect(invalidEntries(['billing.*', 'bad glob', ' '], GLOB_PATTERN)).toEqual(['bad glob']);
    expect(invalidEntries(['pg_sleep', 'x*'], FUNCTION_NAME_PATTERN)).toEqual(['x*']);
  });
});
