import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import dayjs from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';
import { CONDITION_OPERANDS } from '@/utils/enumLabels';
import { fromRoutingFormRow, routingOperandSpecs, toRoutingFormRow } from './routingOperands';

// antd's date pickers register this plugin at runtime; the pure test does it itself.
dayjs.extend(customParseFormat);

const t = ((key: string, opts?: Record<string, unknown>) =>
  opts ? `${key}:${JSON.stringify(opts)}` : key) as unknown as TFunction;

describe('routingOperands', () => {
  const specs = routingOperandSpecs(t, [{ id: 'g-1', name: 'Ops' }], [{ value: 'ADMIN', label: 'Admin' }]);
  const byOperand = Object.fromEntries(specs.map((s) => [s.value, s]));

  it('covers every routing operand with a default form row', () => {
    expect(specs.map((s) => s.value)).toEqual([...CONDITION_OPERANDS]);
    for (const spec of specs) {
      expect(spec.defaultRow()).toMatchObject({ operand: spec.value, negate: false });
    }
  });

  it('keeps the routing editors and their labels', () => {
    expect(byOperand.requester_group?.editor).toMatchObject({
      kind: 'multi',
      field: 'group_ids',
      options: [{ value: 'g-1', label: 'Ops' }],
    });
    expect(byOperand.requester_role?.editor).toMatchObject({
      options: [{ value: 'ADMIN', label: 'Admin' }],
    });
    expect(byOperand.estimated_bytes_scanned?.editor).toMatchObject({
      kind: 'comparison',
      input: 'bytes',
      ariaLabel: 'admin.routing_policies.bytes_value_label',
    });
    expect(byOperand.risk_score?.editor).toMatchObject({ kind: 'comparison', min: 0, max: 100 });
    expect(byOperand.data_budget_used_percent?.editor).toMatchObject({ precision: 0, suffix: '%' });
    expect(byOperand.time_of_day?.editor).toEqual({ kind: 'time-range', field: 'time_range' });
    expect(byOperand.cicd_origin?.editor).toMatchObject({
      kind: 'bool',
      label: 'admin.routing_policies.cicd_present_label',
    });
  });

  it('rejects malformed CIDRs', () => {
    const editor = byOperand.source_ip?.editor;
    if (editor?.kind !== 'tags') throw new Error('expected tags');
    expect(editor.validate?.(['10.0.0.0/8'])).toBeNull();
    expect(editor.validate?.(['nope', '10.0.0.0/8'])).toContain('nope');
  });

  it('converts the time-of-day window between minutes and a Dayjs pair', () => {
    const form = toRoutingFormRow({
      operand: 'time_of_day',
      negate: false,
      time_start_min: 1320,
      time_end_min: 360,
    });
    expect(form.time_range?.[0].format('HH:mm')).toBe('22:00');
    expect(fromRoutingFormRow(form)).toEqual({
      operand: 'time_of_day',
      negate: false,
      time_start_min: 1320,
      time_end_min: 360,
    });
    expect(toRoutingFormRow({ operand: 'has_where', negate: false, bool_value: true })).toEqual({
      operand: 'has_where',
      negate: false,
      bool_value: true,
    });
    expect(fromRoutingFormRow({ operand: 'time_of_day', negate: false, time_range: null })).toEqual({
      operand: 'time_of_day',
      negate: false,
    });
  });
});
