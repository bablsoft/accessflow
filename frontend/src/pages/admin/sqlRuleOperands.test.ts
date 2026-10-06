import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import { SQL_RULE_CONDITION_OPERANDS } from '@/utils/enumLabels';
import { sqlRuleOperandSpecs } from './sqlRuleOperands';

const t = ((key: string, opts?: Record<string, unknown>) =>
  opts ? `${key}:${JSON.stringify(opts)}` : key) as unknown as TFunction;

describe('sqlRuleOperandSpecs', () => {
  const specs = sqlRuleOperandSpecs(t);
  const byOperand = Object.fromEntries(specs.map((s) => [s.value, s]));

  it('covers the whole SQL operand set in order', () => {
    expect(specs.map((s) => s.value)).toEqual([...SQL_RULE_CONDITION_OPERANDS]);
    expect(byOperand.query_type?.label).toBe('enums.sql_rule_operand.query_type');
    expect(byOperand.has_where?.defaultRow()).toEqual({
      operand: 'has_where',
      negate: false,
      bool_value: false,
    });
  });

  it('maps operands to their value editors', () => {
    expect(byOperand.query_type?.editor).toMatchObject({ kind: 'multi', field: 'query_types' });
    const options = (byOperand.query_type?.editor as { options: { value: string }[] }).options;
    expect(options.map((o) => o.value)).toContain('OTHER');
    expect(byOperand.referenced_column?.editor).toMatchObject({ kind: 'tags', field: 'column_globs' });
    expect(byOperand.sql_matches?.editor).toMatchObject({
      kind: 'regex',
      patternField: 'pattern',
      ignoreCaseField: 'ignore_case',
      maxLength: 500,
    });
    expect(byOperand.like_leading_wildcard?.editor).toMatchObject({ kind: 'bool', field: 'bool_value' });
  });

  it('validates glob and function entries', () => {
    const tables = byOperand.referenced_table?.editor;
    const functions = byOperand.function_called?.editor;
    if (tables?.kind !== 'tags' || functions?.kind !== 'tags') throw new Error('expected tags');
    expect(tables.validate?.(['billing.*'])).toBeNull();
    expect(tables.validate?.(['bad glob'])).toContain('bad glob');
    expect(functions.validate?.(['x*'])).toContain('x*');
    expect(functions.validate?.(['dblink'])).toBeNull();
  });
});
