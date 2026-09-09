import { describe, expect, it } from 'vitest';
import type { TFunction } from 'i18next';
import {
  maskingSimulationSummary,
  rowSecuritySimulationSummary,
} from './policySimulationSummaries';
import type { MaskingSimulationResponse, RowSecuritySimulationResponse } from '@/types/api';

const t = ((key: string) => key) as unknown as TFunction;

const rls: RowSecuritySimulationResponse = {
  period_from: '2026-06-01T00:00:00Z',
  period_to: '2026-07-01T00:00:00Z',
  datasource_id: 'ds-1',
  evaluated_count: 812,
  changed_count: 41,
  unclassifiable_count: 3,
  truncated: false,
  transition_counts: [
    { transition: 'NEWLY_DENY_ALL', count: 12 },
    { transition: 'NEWLY_FAILS_CLOSED', count: 9 },
  ],
  user_impacts: [],
  samples: [],
  caveats: ['MEMBERSHIP_STATE_CURRENT'],
};

const masking: MaskingSimulationResponse = {
  period_from: '2026-06-01T00:00:00Z',
  period_to: '2026-07-01T00:00:00Z',
  datasource_id: 'ds-1',
  evaluated_count: 812,
  changed_count: 130,
  newly_masked_count: 130,
  newly_revealed_count: 0,
  truncated: false,
  user_impacts: [],
  column_impacts: [],
  samples: [],
  caveats: ['COLUMN_MATCH_BARE_NAME'],
};

describe('rowSecuritySimulationSummary', () => {
  it('surfaces the two loss-of-access counts and the unclassifiable count as headline stats', () => {
    const summary = rowSecuritySimulationSummary(rls, t);
    expect(summary.stats.map((s) => [s.key, s.value])).toEqual([
      ['denied', 12],
      ['failsClosed', 9],
      ['unclassifiable', 3],
    ]);
  });

  it('reports zero for a transition the run never produced', () => {
    const summary = rowSecuritySimulationSummary({ ...rls, transition_counts: [] }, t);
    expect(summary.stats.find((s) => s.key === 'denied')?.value).toBe(0);
  });

  it('builds a user row per impact', () => {
    const summary = rowSecuritySimulationSummary(
      {
        ...rls,
        user_impacts: [
          {
            user_id: 'u1',
            email: 'a@x.io',
            display_name: 'Ada',
            newly_filtered_count: 20,
            newly_denied_count: 12,
            newly_fails_closed_count: 9,
          },
        ],
      },
      t,
    );
    expect(summary.userRows[0]).toEqual({
      key: 'u1',
      email: 'a@x.io',
      filtered: 20,
      denied: 12,
      failsClosed: 9,
    });
  });

  it('carries the fail-closed reason onto the sample row and exposes no SQL', () => {
    const summary = rowSecuritySimulationSummary(
      {
        ...rls,
        samples: [
          {
            query_request_id: 'q1',
            submitted_by_email: 'a@x.io',
            query_type: 'SELECT',
            created_at: '2026-07-14T09:12:00Z',
            transition: 'NEWLY_FAILS_CLOSED',
            baseline_outcome: 'NOT_APPLICABLE',
            simulated_outcome: 'FAIL_CLOSED',
            reason: 'UNION over a protected table',
          },
        ],
      },
      t,
    );
    const row = summary.detailRows[0] as Record<string, unknown>;
    expect(row.reason).toBe('UNION over a protected table');
    expect(Object.keys(row)).toEqual(['key', 'submitter', 'when', 'transition', 'reason']);
  });

  it('renders a null reason as an empty cell rather than "null"', () => {
    const summary = rowSecuritySimulationSummary(
      {
        ...rls,
        samples: [
          {
            query_request_id: 'q1',
            submitted_by_email: 'a@x.io',
            query_type: 'SELECT',
            created_at: '2026-07-14T09:12:00Z',
            transition: 'NEWLY_FILTERED',
            baseline_outcome: 'NOT_APPLICABLE',
            simulated_outcome: 'APPLIED',
            reason: null,
          },
        ],
      },
      t,
    );
    expect((summary.detailRows[0] as Record<string, unknown>).reason).toBe('');
  });

  it('passes the caveats through untouched', () => {
    expect(rowSecuritySimulationSummary(rls, t).caveats).toEqual(['MEMBERSHIP_STATE_CURRENT']);
  });
});

describe('maskingSimulationSummary', () => {
  it('surfaces the masked and revealed counts', () => {
    const summary = maskingSimulationSummary(masking, t);
    expect(summary.stats.map((s) => [s.key, s.value])).toEqual([
      ['masked', 130],
      ['revealed', 0],
    ]);
  });

  it('joins each user affected column list for display', () => {
    const summary = maskingSimulationSummary(
      {
        ...masking,
        user_impacts: [
          {
            user_id: 'u1',
            email: 'a@x.io',
            display_name: 'Ada',
            newly_masked_columns: ['email', 'phone'],
            newly_revealed_columns: [],
            affected_query_count: 43,
          },
        ],
      },
      t,
    );
    expect(summary.userRows[0]).toEqual({
      key: 'u1',
      email: 'a@x.io',
      masked: 'email, phone',
      revealed: '',
      affected: 43,
    });
  });

  it('uses the column impacts as the drill-down', () => {
    const summary = maskingSimulationSummary(
      {
        ...masking,
        column_impacts: [
          { column_name: 'email', newly_masked_query_count: 130, newly_revealed_query_count: 0 },
        ],
      },
      t,
    );
    expect(summary.detailLabel).toBe('policySimulation.tab_columns');
    expect(summary.detailRows[0]).toEqual({
      key: 'email',
      column: 'email',
      masked: 130,
      revealed: 0,
    });
  });

  it('always reports the bare-name caveat it was given', () => {
    expect(maskingSimulationSummary(masking, t).caveats).toEqual(['COLUMN_MATCH_BARE_NAME']);
  });
});
