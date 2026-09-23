import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import i18n from '@/i18n';
import en from '@/locales/en.json';
import {
  KNOWN_DETAIL_KEYS,
  detailLabel,
  extractRoutingPolicies,
  formatStepDetails,
} from './decisionTraceDetails';

const t = i18n.t.bind(i18n) as unknown as TFunction;

describe('detailLabel', () => {
  it.each(KNOWN_DETAIL_KEYS)('has an English label for %s', (key) => {
    expect((en.decisionTrace.detail as Record<string, string>)[key]).toBeTruthy();
    expect(detailLabel(key, t)).not.toBe(`decisionTrace.detail.${key}`);
  });

  it('humanises a key the backend adds later instead of dropping it', () => {
    expect(detailLabel('brand_new_signal', t)).toBe('Brand new signal');
  });
});

describe('formatStepDetails', () => {
  it('returns no rows for an empty details object', () => {
    expect(formatStepDetails({}, t)).toEqual([]);
  });

  it('keeps the backend insertion order and formats scalars', () => {
    const rows = formatStepDetails(
      { query_type: 'UPDATE', has_where_clause: false, limit: 5, extra_thing: 'x' },
      t,
    );
    expect(rows.map((r) => r.key)).toEqual(['query_type', 'has_where_clause', 'limit', 'extra_thing']);
    expect(rows[0]?.value).toBe('UPDATE');
    expect(rows[1]?.value).toBe('No');
    expect(rows[2]?.value).toBe('5');
    expect(rows[3]?.label).toBe('Extra thing');
  });

  it('renders the EFFECTIVE_PERMISSION contributing grants, one line each', () => {
    const rows = formatStepDetails(
      {
        query_admin_short_circuit: false,
        contributing_grants: [
          { source_kind: 'DIRECT', source_id: 'p-1' },
          { source_kind: 'GROUP', source_id: 'p-2', group_id: 'g-1', group_name: 'Data team', expires_at: '2026-10-01T00:00:00Z' },
          { source_kind: 'GROUP', source_id: 'p-3' },
        ],
        rejected_tables: [],
      },
      t,
    );
    expect(rows[0]).toMatchObject({ key: 'query_admin_short_circuit', value: 'No' });
    const grants = rows[1]?.value as string[];
    expect(grants).toHaveLength(3);
    expect(grants[0]).toBe('Direct grant · never expires');
    expect(grants[1]).toMatch(/^via group Data team · expires /);
    expect(grants[2]).toBe('Group grant · never expires');
    expect(rows[2]?.value).toEqual(['None']);
  });

  it('reports the query_admin_short_circuit case as a yes', () => {
    const rows = formatStepDetails({ query_admin_short_circuit: true }, t);
    expect(rows).toEqual([
      { key: 'query_admin_short_circuit', label: 'QUERY_ADMIN short-circuit', value: 'Yes' },
    ]);
  });

  it('formats reviewers, plan approvers and nested objects', () => {
    const rows = formatStepDetails(
      {
        reviewers: [
          { user_id: 'u-1', email: 'a@x.io', display_name: 'Ann' },
          { user_id: 'u-2', email: 'b@x.io' },
        ],
        plan_approvers: [{ role: 'REVIEWER', stage: 1 }, { user_id: 'u-9' }],
        predicates: [{ table_ref: 'orders', operator: 'EQ' }],
        tables: ['a', 'b'],
      },
      t,
    );
    expect(rows[0]?.value).toEqual(['Ann (a@x.io)', 'b@x.io']);
    expect(rows[1]?.value).toEqual(['Role REVIEWER · stage 1', 'User u-9']);
    expect(rows[2]?.value).toEqual(['Table ref: orders · Operator: EQ']);
    expect(rows[3]?.value).toEqual(['a', 'b']);
  });

  it('formats ISO instants as dates and leaves plain strings alone', () => {
    const rows = formatStepDetails(
      { scheduled_for: '2026-09-25T18:00:00Z', reason_text: 'Quarter close', nested: { a: null } },
      t,
    );
    expect(rows[0]?.value).not.toBe('2026-09-25T18:00:00Z');
    expect(rows[1]?.value).toBe('Quarter close');
    expect(rows[2]?.value).toBe('A: null');
  });

  it('never renders the routing policies list as a generic row', () => {
    const rows = formatStepDetails({ policies: [], matched_policy_name: 'p' }, t);
    expect(rows.map((r) => r.key)).toEqual(['matched_policy_name']);
  });
});

describe('extractRoutingPolicies', () => {
  it('returns null when the backend attached no list', () => {
    expect(extractRoutingPolicies({})).toBeNull();
    expect(extractRoutingPolicies({ policies: 'nope' })).toBeNull();
  });

  it('maps each entry, defaulting missing fields', () => {
    expect(
      extractRoutingPolicies({
        policies: [
          { policy_id: 'p-1', name: 'Block deletes', priority: 0, action: 'AUTO_REJECT', matched: true, decisive: true },
          { policy_id: 'p-2', name: 'Two approvals', priority: 1, action: 'REQUIRE_APPROVALS', required_approvals: 2, matched: false, decisive: false },
          { name: 'bare' },
          'not-an-object',
        ],
      }),
    ).toEqual([
      { policy_id: 'p-1', name: 'Block deletes', priority: 0, action: 'AUTO_REJECT', required_approvals: undefined, matched: true, decisive: true },
      { policy_id: 'p-2', name: 'Two approvals', priority: 1, action: 'REQUIRE_APPROVALS', required_approvals: 2, matched: false, decisive: false },
      { policy_id: '', name: 'bare', priority: 0, action: undefined, required_approvals: undefined, matched: false, decisive: false },
    ]);
  });
});
