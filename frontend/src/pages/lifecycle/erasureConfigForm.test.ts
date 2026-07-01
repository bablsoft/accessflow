import { describe, expect, it } from 'vitest';

import {
  configToPayload,
  conditionSetToRows,
  rowsToConditionSet,
} from '@/pages/lifecycle/erasureConfigForm';

describe('rowsToConditionSet', () => {
  it('returns null for empty / undefined rows', () => {
    expect(rowsToConditionSet(undefined)).toBeNull();
    expect(rowsToConditionSet([])).toBeNull();
  });

  it('drops rows without a column', () => {
    expect(rowsToConditionSet([{ operator: 'EQUALS', values: ['x'] }])).toBeNull();
    expect(rowsToConditionSet([{ column: '   ', values: ['x'] }])).toBeNull();
  });

  it('maps a scalar condition, trimming the column and defaulting operator/negate', () => {
    const set = rowsToConditionSet([{ column: ' status ', values: ['inactive'] }]);
    expect(set).toEqual({
      conditions: [{ column: 'status', operator: 'EQUALS', values: ['inactive'], negate: false }],
    });
  });

  it('clears values for IS_NULL and preserves negate + multi-value IN', () => {
    const set = rowsToConditionSet([
      { column: 'deleted_at', operator: 'IS_NULL', values: ['ignored'], negate: true },
      { column: 'region', operator: 'IN', values: ['EU', 'US'] },
    ]);
    expect(set?.conditions[0]).toEqual({
      column: 'deleted_at',
      operator: 'IS_NULL',
      values: [],
      negate: true,
    });
    expect(set?.conditions[1]).toEqual({
      column: 'region',
      operator: 'IN',
      values: ['EU', 'US'],
      negate: false,
    });
  });
});

describe('configToPayload', () => {
  it('normalises blanks to null and defaults columns to an empty array', () => {
    expect(configToPayload({})).toEqual({
      target_table: null,
      target_columns: [],
      conditions: null,
      raw_where: null,
    });
    expect(configToPayload({ target_table: '  ', raw_where: '   ' })).toEqual({
      target_table: null,
      target_columns: [],
      conditions: null,
      raw_where: null,
    });
  });

  it('trims target table and raw WHERE and carries columns + conditions', () => {
    const payload = configToPayload({
      target_table: ' public.users ',
      target_columns: ['email'],
      raw_where: " last_login < '2020-01-01' ",
      conditions: [{ column: 'status', operator: 'NOT_EQUALS', values: ['active'] }],
    });
    expect(payload.target_table).toBe('public.users');
    expect(payload.target_columns).toEqual(['email']);
    expect(payload.raw_where).toBe("last_login < '2020-01-01'");
    expect(payload.conditions).toEqual({
      conditions: [{ column: 'status', operator: 'NOT_EQUALS', values: ['active'], negate: false }],
    });
  });
});

describe('conditionSetToRows', () => {
  it('returns an empty array for null / undefined', () => {
    expect(conditionSetToRows(null)).toEqual([]);
    expect(conditionSetToRows(undefined)).toEqual([]);
  });

  it('round-trips a condition set back into editable rows', () => {
    const set = {
      conditions: [{ column: 'region', operator: 'IN' as const, values: ['EU'], negate: true }],
    };
    expect(conditionSetToRows(set)).toEqual([
      { column: 'region', operator: 'IN', values: ['EU'], negate: true },
    ]);
  });
});
