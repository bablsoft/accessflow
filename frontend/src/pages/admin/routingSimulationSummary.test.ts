import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import { outcomeLabel, routingSimulationSummary } from './routingSimulationSummary';
import type { RoutingSimulationResponse } from '@/types/api';

const t = ((key: string) => key) as unknown as TFunction;

const base: RoutingSimulationResponse = {
  period_from: '2026-06-01T00:00:00Z',
  period_to: '2026-07-01T00:00:00Z',
  datasource_id: null,
  evaluated_count: 1284,
  changed_count: 312,
  skipped_ai_failed_count: 0,
  truncated: false,
  outcome_deltas: [],
  user_impacts: [],
  samples: [],
  caveats: ['MEMBERSHIP_STATE_CURRENT'],
};

describe('outcomeLabel', () => {
  it('renders NO_MATCH as the fall-through label, not as an action', () => {
    expect(outcomeLabel('NO_MATCH', t)).toBe('policySimulation.no_match');
  });

  it('renders a missing arm as the fall-through label too', () => {
    expect(outcomeLabel(undefined, t)).toBe('policySimulation.no_match');
  });

  it('routes real actions through the shared enum labels', () => {
    expect(outcomeLabel('AUTO_REJECT', t)).toBe('enums.routing_action.AUTO_REJECT');
  });
});

describe('routingSimulationSummary', () => {
  it('carries the counts, truncation flag and caveats through', () => {
    const summary = routingSimulationSummary({ ...base, truncated: true }, t);
    expect(summary.evaluatedCount).toBe(1284);
    expect(summary.changedCount).toBe(312);
    expect(summary.truncated).toBe(true);
    expect(summary.caveats).toEqual(['MEMBERSHIP_STATE_CURRENT']);
  });

  it('builds one user row per impact, keyed by user id', () => {
    const summary = routingSimulationSummary(
      {
        ...base,
        user_impacts: [
          {
            user_id: 'u1',
            email: 'a@x.io',
            display_name: 'Ada',
            changed_count: 47,
            simulated_actions: ['AUTO_REJECT'],
          },
        ],
      },
      t,
    );
    expect(summary.userRows).toEqual([
      {
        key: 'u1',
        email: 'a@x.io',
        changed: 47,
        actions: 'enums.routing_action.AUTO_REJECT',
      },
    ]);
  });

  it('renders a sample whose baseline arm did not match as the fall-through label', () => {
    const summary = routingSimulationSummary(
      {
        ...base,
        samples: [
          {
            query_request_id: 'q1',
            submitted_by_email: 'a@x.io',
            datasource_name: 'prod',
            query_type: 'DELETE',
            historical_status: 'EXECUTED',
            created_at: '2026-07-14T09:12:00Z',
            baseline: null,
            simulated: {
              action: 'AUTO_REJECT',
              policy_id: null,
              policy_name: 'Draft',
              is_draft: true,
              required_approvals: null,
            },
          },
        ],
      },
      t,
    );
    const row = summary.detailRows[0] as Record<string, unknown>;
    expect(row.key).toBe('q1');
    expect(row.before).toBe('policySimulation.no_match');
    expect(row.after).toBe('enums.routing_action.AUTO_REJECT');
  });

  it('exposes no SQL text on a sample row', () => {
    const summary = routingSimulationSummary(
      {
        ...base,
        samples: [
          {
            query_request_id: 'q1',
            submitted_by_email: 'a@x.io',
            datasource_name: 'prod',
            query_type: 'SELECT',
            historical_status: 'EXECUTED',
            created_at: '2026-07-14T09:12:00Z',
            baseline: null,
            simulated: null,
          },
        ],
      },
      t,
    );
    expect(Object.keys(summary.detailRows[0] as object)).toEqual([
      'key',
      'submitter',
      'when',
      'before',
      'after',
    ]);
  });
});
