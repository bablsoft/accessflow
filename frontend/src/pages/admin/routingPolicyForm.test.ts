import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  actionRequiresApprovals,
  conditionSummary,
  conditionToForm,
  defaultRow,
  isCidr,
  minutesToTime,
  rowsToCondition,
  timeToMinutes,
  type RoutingConditionRow,
} from './routingPolicyForm';
import type { RoutingCondition } from '@/types/api';

const t = ((key: string) => key) as unknown as TFunction;

describe('routingPolicyForm', () => {
  it('actionRequiresApprovals only for REQUIRE_APPROVALS and ESCALATE', () => {
    expect(actionRequiresApprovals('REQUIRE_APPROVALS')).toBe(true);
    expect(actionRequiresApprovals('ESCALATE')).toBe(true);
    expect(actionRequiresApprovals('AUTO_APPROVE')).toBe(false);
    expect(actionRequiresApprovals('AUTO_REJECT')).toBe(false);
  });

  it('defaultRow seeds operand-specific values', () => {
    expect(defaultRow('risk_score')).toMatchObject({ score_operator: 'GTE', score_value: 80 });
    expect(defaultRow('time_of_day')).toMatchObject({ time_start_min: 1320, time_end_min: 360 });
    expect(defaultRow('has_where')).toMatchObject({ bool_value: false });
    expect(defaultRow('query_type')).toEqual({ operand: 'query_type', negate: false });
    expect(defaultRow('source_ip')).toMatchObject({ cidrs: [] });
    expect(defaultRow('user_agent')).toMatchObject({ ua_patterns: [] });
    expect(defaultRow('time_since_last_approval')).toMatchObject({
      tsla_operator: 'GT',
      tsla_minutes: 1440,
    });
    expect(defaultRow('cicd_origin')).toMatchObject({ bool_value: true });
  });

  it('rowsToCondition wraps leaves in AND/OR and applies negation', () => {
    const rows: RoutingConditionRow[] = [
      { operand: 'query_type', negate: false, query_types: ['DELETE'] },
      { operand: 'has_where', negate: true, bool_value: true },
    ];
    const all = rowsToCondition('ALL', rows);
    expect(all.type).toBe('and');
    const any = rowsToCondition('ANY', rows);
    expect(any.type).toBe('or');
    if (all.type === 'and') {
      expect(all.children[0]).toEqual({ type: 'query_type', any_of: ['DELETE'] });
      expect(all.children[1]).toEqual({ type: 'not', child: { type: 'has_where', expected: true } });
    }
  });

  it('round-trips every operand through rowsToCondition and conditionToForm', () => {
    const rows: RoutingConditionRow[] = [
      { operand: 'query_type', negate: false, query_types: ['DELETE', 'UPDATE'] },
      { operand: 'referenced_table', negate: false, table_globs: ['payroll.*'] },
      { operand: 'risk_level', negate: false, risk_levels: ['HIGH'] },
      { operand: 'risk_score', negate: false, score_operator: 'GTE', score_value: 80 },
      { operand: 'requester_role', negate: false, roles: ['ANALYST'] },
      { operand: 'requester_group', negate: false, group_ids: ['g1'] },
      { operand: 'time_of_day', negate: false, time_start_min: 1320, time_end_min: 360 },
      { operand: 'day_of_week', negate: false, weekdays: ['MONDAY'] },
      { operand: 'has_where', negate: false, bool_value: false },
      { operand: 'has_limit', negate: true, bool_value: false },
      { operand: 'transactional', negate: false, bool_value: true },
      { operand: 'source_ip', negate: false, cidrs: ['10.0.0.0/8', '2001:db8::/32'] },
      { operand: 'user_agent', negate: true, ua_patterns: ['*curl*'] },
      {
        operand: 'time_since_last_approval',
        negate: false,
        tsla_operator: 'GT',
        tsla_minutes: 1440,
      },
      { operand: 'cicd_origin', negate: false, bool_value: true },
    ];
    const condition = rowsToCondition('ALL', rows);
    const parsed = conditionToForm(condition);
    expect(parsed.supported).toBe(true);
    expect(parsed.matchType).toBe('ALL');
    expect(parsed.rows).toEqual(rows);
  });

  it('isCidr accepts valid IPv4/IPv6 blocks and rejects malformed ones', () => {
    expect(isCidr('10.0.0.0/8')).toBe(true);
    expect(isCidr('203.0.113.0/24')).toBe(true);
    expect(isCidr('2001:db8::/32')).toBe(true);
    expect(isCidr('203.0.113.0')).toBe(false);
    expect(isCidr('203.0.113.0/33')).toBe(false);
    expect(isCidr('999.0.0.0/8')).toBe(false);
    expect(isCidr('not-a-cidr')).toBe(false);
    expect(isCidr('2001:db8::/129')).toBe(false);
  });

  it('conditionSummary covers source_ip, time_since_last_approval and cicd_origin rows', () => {
    const condition = rowsToCondition('ALL', [
      { operand: 'source_ip', negate: true, cidrs: ['203.0.113.0/24'] },
      {
        operand: 'time_since_last_approval',
        negate: false,
        tsla_operator: 'GT',
        tsla_minutes: 1440,
      },
      { operand: 'cicd_origin', negate: false, bool_value: true },
      { operand: 'user_agent', negate: false, ua_patterns: ['*curl*'] },
    ]);
    const summary = conditionSummary(t, condition);
    expect(summary).toContain('203.0.113.0/24');
    expect(summary).toContain('1440');
    expect(summary).toContain('*curl*');
  });

  it('conditionToForm maps OR to ANY', () => {
    const condition: RoutingCondition = {
      type: 'or',
      children: [{ type: 'query_type', any_of: ['SELECT'] }],
    };
    expect(conditionToForm(condition).matchType).toBe('ANY');
  });

  it('conditionToForm treats a bare leaf as a single ALL row', () => {
    const parsed = conditionToForm({ type: 'transactional', expected: true });
    expect(parsed.supported).toBe(true);
    expect(parsed.matchType).toBe('ALL');
    expect(parsed.rows).toEqual([{ operand: 'transactional', negate: false, bool_value: true }]);
  });

  it('conditionToForm returns empty supported result for null', () => {
    expect(conditionToForm(null)).toEqual({ matchType: 'ALL', rows: [], supported: true });
  });

  it('conditionToForm flags deeply nested trees as unsupported', () => {
    const nested: RoutingCondition = {
      type: 'and',
      children: [{ type: 'or', children: [{ type: 'has_where', expected: true }] }],
    };
    expect(conditionToForm(nested).supported).toBe(false);
  });

  it('conditionToForm flags an unsupported bare combinator', () => {
    const nested: RoutingCondition = {
      type: 'not',
      child: { type: 'and', children: [] },
    };
    expect(conditionToForm(nested).supported).toBe(false);
  });

  it('minutesToTime and timeToMinutes convert correctly', () => {
    expect(minutesToTime(0)).toBe('00:00');
    expect(minutesToTime(1320)).toBe('22:00');
    expect(minutesToTime(undefined)).toBe('00:00');
    expect(timeToMinutes(22, 30)).toBe(1350);
  });

  it('conditionSummary produces a readable joined string', () => {
    const condition = rowsToCondition('ANY', [
      { operand: 'query_type', negate: false, query_types: ['DELETE'] },
      { operand: 'risk_score', negate: false, score_operator: 'GTE', score_value: 80 },
    ]);
    const summary = conditionSummary(t, condition);
    expect(summary).toContain('admin.routing_policies.match_any_joiner');
  });

  it('conditionSummary reports advanced for unsupported trees', () => {
    const nested: RoutingCondition = {
      type: 'and',
      children: [{ type: 'and', children: [] }],
    };
    expect(conditionSummary(t, nested)).toBe('admin.routing_policies.condition_advanced');
  });

  it('conditionSummary reports any-query for an empty AND', () => {
    expect(conditionSummary(t, { type: 'and', children: [] })).toBe(
      'admin.routing_policies.condition_any_query',
    );
  });

  it('conditionSummary covers each operand row summary', () => {
    const condition = rowsToCondition('ALL', [
      { operand: 'referenced_table', negate: true, table_globs: ['payroll.*'] },
      { operand: 'requester_group', negate: false, group_ids: ['g1', 'g2'] },
      { operand: 'time_of_day', negate: false, time_start_min: 1320, time_end_min: 360 },
      { operand: 'day_of_week', negate: false, weekdays: ['MONDAY'] },
      { operand: 'risk_level', negate: false, risk_levels: ['HIGH'] },
      { operand: 'requester_role', negate: false, roles: ['ADMIN'] },
      { operand: 'has_where', negate: false, bool_value: true },
    ]);
    expect(conditionSummary(t, condition)).toContain('22:00');
  });
});
