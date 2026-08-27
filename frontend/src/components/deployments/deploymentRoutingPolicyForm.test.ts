import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import type { DeploymentRoutingPolicy } from '@/types/api';
import type { DeploymentRoutingPolicyFormValues } from './deploymentRoutingPolicyForm';
import {
  actionRequiresApprovals,
  deploymentConditionsSummary,
  toCreateInput,
  toUpdateInput,
} from './deploymentRoutingPolicyForm';

const BASE_VALUES: DeploymentRoutingPolicyFormValues = {
  name: '',
  pipeline_id: null,
  action: 'REQUIRE_APPROVALS',
  required_approvals: 1,
  priority: 100,
  enabled: true,
  environments: [],
  providers: [],
  min_risk_level: null,
  version_globs: [],
  days_of_week: [],
  start_time: null,
  end_time: null,
  timezone: null,
};

// Echoes the key plus its interpolation, so tests assert the shape without pinning English copy.
const t = ((key: string, options?: Record<string, unknown>) =>
  options
    ? `${key}:${Object.entries(options)
        .map(([k, v]) => `${k}=${String(v)}`)
        .join(',')}`
    : key) as unknown as TFunction;

function makePolicy(overrides: Partial<DeploymentRoutingPolicy>): DeploymentRoutingPolicy {
  return {
    id: 'pol-1',
    pipeline_id: null,
    name: 'policy',
    conditions: {
      environments: [],
      providers: [],
      min_risk_level: null,
      version_globs: [],
      days_of_week: [],
      start_time: null,
      end_time: null,
      timezone: null,
    },
    action: 'REQUIRE_APPROVALS',
    required_approvals: 2,
    priority: 100,
    enabled: true,
    created_at: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

describe('deploymentRoutingPolicyForm', () => {
  it('knows which actions require an approvals count', () => {
    expect(actionRequiresApprovals('REQUIRE_APPROVALS')).toBe(true);
    expect(actionRequiresApprovals('ESCALATE')).toBe(true);
    expect(actionRequiresApprovals('AUTO_APPROVE')).toBe(false);
    expect(actionRequiresApprovals('AUTO_REJECT')).toBe(false);
  });

  it('forces required_approvals null for AUTO_* actions on the wire', () => {
    const input = toCreateInput({
      ...BASE_VALUES,
      name: 'auto',
      action: 'AUTO_APPROVE',
      required_approvals: 3,
    });
    expect(input.required_approvals).toBeNull();

    const escalate = toCreateInput({
      ...BASE_VALUES,
      name: 'esc',
      action: 'ESCALATE',
      required_approvals: 3,
    });
    expect(escalate.required_approvals).toBe(3);
  });

  it('drops a half-filled time range and its timezone', () => {
    const input = toCreateInput({
      ...BASE_VALUES,
      name: 'time',
      start_time: '09:00',
      end_time: null,
      timezone: 'UTC',
    });
    expect(input.conditions?.start_time).toBeNull();
    expect(input.conditions?.end_time).toBeNull();
    expect(input.conditions?.timezone).toBeNull();
  });

  it('sets clear_pipeline on update when the pipeline scope is removed', () => {
    const cleared = toUpdateInput({ ...BASE_VALUES, name: 'x' });
    expect(cleared.clear_pipeline).toBe(true);
    const scoped = toUpdateInput({
      ...BASE_VALUES,
      name: 'x',
      pipeline_id: 'p-1',
    });
    expect(scoped.clear_pipeline).toBe(false);
  });

  it('summarizes conditions as a compact line', () => {
    const summary = deploymentConditionsSummary(t, {
      environments: ['production', 'staging'],
      providers: ['GITHUB_ACTIONS'],
      min_risk_level: 'HIGH',
      version_globs: ['2.*'],
      days_of_week: [2, 1],
      start_time: '09:00:00',
      end_time: '17:00:00',
      timezone: 'UTC',
    });
    expect(summary).toContain('environments=production, staging');
    expect(summary).toContain('enums.pipeline_provider.GITHUB_ACTIONS');
    expect(summary).toContain('risk=enums.risk_level.HIGH');
    expect(summary).toContain('2.*');
    // Weekdays sorted ascending.
    expect(summary.indexOf('MONDAY')).toBeLessThan(summary.indexOf('TUESDAY'));
    expect(summary).toContain('09:00–17:00 UTC');
  });

  it('summarizes an empty condition set as match-all', () => {
    const summary = deploymentConditionsSummary(t, makePolicy({}).conditions);
    expect(summary).toBe('deploygov.routingPolicies.summary_match_all');
  });
});
