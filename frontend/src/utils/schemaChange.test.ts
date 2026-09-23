import { describe, expect, it } from 'vitest';
import { AxiosError, AxiosHeaders } from 'axios';
import type { TFunction } from 'i18next';
import type {
  SchemaChangeLadderRung,
  SchemaChangePromotion,
  SchemaDriftFinding,
  SchemaDriftScan,
} from '@/types/api';
import {
  findingsByStatement,
  freezingPromotion,
  groupFindingsByEnvironment,
  ladderRungReason,
  latestScanByEnvironment,
  parseScanReason,
  scanReasonText,
  statementProblemsFromError,
} from './schemaChange';
import {
  SCHEMA_CHANGE_PROMOTION_STATUSES,
  SCHEMA_CHANGE_SET_STATUSES,
  SCHEMA_DRIFT_BASELINES,
  SCHEMA_DRIFT_FINDING_KINDS,
  SCHEMA_DRIFT_FINDING_STATUSES,
  schemaChangePromotionStatusLabel,
  schemaChangeSetStatusLabel,
  schemaDriftBaselineLabel,
  schemaDriftFindingKindLabel,
  schemaDriftFindingStatusLabel,
  statementQueryTypeLabel,
} from './enumLabels';
import {
  schemaChangeLadderRungColor,
  schemaChangePromotionStatusColor,
  schemaChangeSetStatusColor,
  schemaDriftFindingKindColor,
  schemaDriftFindingStatusColor,
} from './statusColors';

const t = ((key: string, opts?: Record<string, unknown>) =>
  opts ? `${key} ${JSON.stringify(opts)}` : key) as unknown as TFunction;

const promotion = (status: SchemaChangePromotion['status']): SchemaChangePromotion => ({
  id: `p-${status}`,
  change_set_id: 's-1',
  environment_id: 'e-1',
  datasource_id: 'd-1',
  status,
  statements_checksum: 'c',
  promoted_by: 'u-1',
  submitted_at: '2026-09-23T10:00:00Z',
});

const scan = (id: string, environment: string, extra: Partial<SchemaDriftScan> = {}): SchemaDriftScan => ({
  id,
  pipeline_id: 'p-1',
  environment_id: environment,
  baseline: 'PREVIOUS_ENVIRONMENT',
  started_at: '2026-09-23T10:00:00Z',
  applicable: true,
  findings_count: 0,
  partial: false,
  ...extra,
});

const finding = (id: string, environment: string): SchemaDriftFinding => ({
  id,
  scan_id: 'sc-1',
  environment_id: environment,
  object_path: 'public.orders',
  finding_kind: 'MISSING_IN_TARGET',
  status: 'OPEN',
  first_detected_at: '2026-09-23T10:00:00Z',
  last_seen_at: '2026-09-23T10:00:00Z',
});

const rung = (extra: Partial<SchemaChangeLadderRung>): SchemaChangeLadderRung => ({
  environment_id: 'e-1',
  environment_name: 'prod',
  sort_order: 1,
  state: 'BLOCKED',
  ...extra,
});

function axiosError(data: unknown): AxiosError {
  return new AxiosError('boom', 'ERR', undefined, undefined, {
    status: 422,
    statusText: 'x',
    headers: {},
    config: { headers: new AxiosHeaders() },
    data,
  });
}

describe('schemaChange enums and colors', () => {
  it('labels every enum value through its enums key', () => {
    for (const v of SCHEMA_CHANGE_SET_STATUSES) {
      expect(schemaChangeSetStatusLabel(t, v)).toBe(`enums.schema_change_set_status.${v}`);
      expect(schemaChangeSetStatusColor(v).fg).toBeTruthy();
    }
    for (const v of SCHEMA_CHANGE_PROMOTION_STATUSES) {
      expect(schemaChangePromotionStatusLabel(t, v)).toBe(`enums.schema_change_promotion_status.${v}`);
      expect(schemaChangePromotionStatusColor(v).bg).toBeTruthy();
    }
    for (const v of SCHEMA_DRIFT_FINDING_KINDS) {
      expect(schemaDriftFindingKindLabel(t, v)).toBe(`enums.schema_drift_finding_kind.${v}`);
      expect(schemaDriftFindingKindColor(v).border).toBeTruthy();
    }
    for (const v of SCHEMA_DRIFT_FINDING_STATUSES) {
      expect(schemaDriftFindingStatusLabel(t, v)).toBe(`enums.schema_drift_finding_status.${v}`);
      expect(schemaDriftFindingStatusColor(v).fg).toBeTruthy();
    }
    for (const v of SCHEMA_DRIFT_BASELINES) {
      expect(schemaDriftBaselineLabel(t, v)).toBe(`enums.schema_drift_baseline.${v}`);
    }
    expect(statementQueryTypeLabel(t, 'OTHER')).toBe('enums.query_type.OTHER');
    for (const v of ['APPLIED', 'IN_PROGRESS', 'PROMOTABLE', 'BLOCKED'] as const) {
      expect(schemaChangeLadderRungColor(v).fg).toMatch(/^var\(--/);
    }
  });
});

describe('freezingPromotion', () => {
  it('ignores failed and cancelled promotions', () => {
    expect(freezingPromotion([promotion('FAILED'), promotion('CANCELLED')])).toBeUndefined();
    expect(freezingPromotion([])).toBeUndefined();
  });

  it('returns the newest freezing promotion', () => {
    expect(freezingPromotion([promotion('CANCELLED'), promotion('IN_REVIEW'), promotion('APPLIED')])?.status).toBe(
      'IN_REVIEW',
    );
  });
});

describe('scan reasons', () => {
  it('parses a code with and without a cause', () => {
    expect(parseScanReason(null)).toBeNull();
    expect(parseScanReason('ENGINE_NOT_APPLICABLE')).toEqual({ code: 'ENGINE_NOT_APPLICABLE' });
    expect(parseScanReason('SCAN_FAILED: boom')).toEqual({ code: 'SCAN_FAILED', cause: 'boom' });
    expect(parseScanReason('SCAN_FAILED:')).toEqual({ code: 'SCAN_FAILED' });
  });

  it('localizes known codes and passes unknown text through', () => {
    expect(scanReasonText(t, undefined)).toBeNull();
    expect(scanReasonText(t, 'ENGINE_NOT_APPLICABLE')).toBe('schemaChange.drift.reason.ENGINE_NOT_APPLICABLE');
    expect(scanReasonText(t, 'TARGET_INTROSPECTION_FAILED: timeout')).toBe(
      'schemaChange.drift.reason.TARGET_INTROSPECTION_FAILED (timeout)',
    );
    expect(scanReasonText(t, 'something new')).toBe('something new');
  });
});

describe('drift grouping', () => {
  it('keeps the newest scan per environment', () => {
    const latest = latestScanByEnvironment([scan('a', 'e-1'), scan('b', 'e-2'), scan('c', 'e-1')]);
    expect(latest.get('e-1')?.id).toBe('a');
    expect(latest.size).toBe(2);
  });

  it('orders groups by ladder, then unknown environments', () => {
    const groups = groupFindingsByEnvironment(
      [finding('1', 'gone'), finding('2', 'prod'), finding('3', 'dev'), finding('4', 'prod')],
      ['dev', 'staging', 'prod'],
    );
    expect(groups.map((g) => g.environmentId)).toEqual(['dev', 'prod', 'gone']);
    expect(groups[1]?.findings).toHaveLength(2);
  });
});

describe('statementProblemsFromError', () => {
  it('ignores non-axios errors and empty bodies', () => {
    expect(statementProblemsFromError(new Error('x'))).toBeNull();
    expect(statementProblemsFromError(axiosError(undefined))).toBeNull();
    expect(statementProblemsFromError(axiosError({ detail: 'no index' }))).toBeNull();
  });

  it('maps a gate refusal to its statement', () => {
    expect(
      statementProblemsFromError(axiosError({ statementIndex: 2, detail: 'Statement 3 is DML' })),
    ).toEqual({ 2: [{ severity: 'ERROR', message: 'Statement 3 is DML' }] });
  });

  it('maps every blocking finding to its statement', () => {
    const problems = statementProblemsFromError(
      axiosError({
        findings: [
          { statement_index: 0, datasource_id: 'd-1', rule_id: 'drop_statement', severity: 'BLOCK', message: 'DROP' },
          { statement_index: 0, datasource_id: 'd-2', rule_id: 'drop_statement', severity: 'BLOCK', message: 'DROP' },
        ],
      }),
    );
    expect(problems?.[0]).toHaveLength(2);
    expect(problems?.[0]?.[0]).toMatchObject({ severity: 'BLOCK', ruleId: 'drop_statement' });
  });

  it('findingsByStatement defaults to WARN', () => {
    const out = findingsByStatement([
      { statement_index: 1, datasource_id: 'd', rule_id: 'ddl_statement', severity: 'WARN', message: 'm' },
    ]);
    expect(out[1]?.[0]?.severity).toBe('WARN');
  });
});

describe('ladderRungReason', () => {
  it('is null for anything not blocked', () => {
    expect(ladderRungReason(t, rung({ state: 'PROMOTABLE' }))).toBeNull();
    expect(ladderRungReason(t, rung({ state: 'BLOCKED' }))).toBeNull();
  });

  it('names the unapplied lower environment', () => {
    expect(
      ladderRungReason(t, rung({ blocker: 'LOWER_ENVIRONMENT_NOT_APPLIED', blocking_environment_name: 'staging' })),
    ).toContain('"environment":"staging"');
    expect(ladderRungReason(t, rung({ blocker: 'LOWER_ENVIRONMENT_NOT_APPLIED' }))).toContain('—');
  });

  it('describes a freeze with and without a reason', () => {
    expect(ladderRungReason(t, rung({ blocker: 'FREEZE_ACTIVE', freeze_behavior: 'HOLD' }))).toContain(
      'schemaChange.ladder.blocker.FREEZE_ACTIVE {',
    );
    expect(
      ladderRungReason(t, rung({ blocker: 'FREEZE_ACTIVE', freeze_behavior: 'REJECT', freeze_reason: 'Q3' })),
    ).toContain('FREEZE_ACTIVE_reason');
    expect(ladderRungReason(t, rung({ blocker: 'FREEZE_ACTIVE' }))).toContain('"behavior":"—"');
  });

  it('uses the plain key for the other blockers', () => {
    expect(ladderRungReason(t, rung({ blocker: 'NO_DATASOURCE' }))).toBe('schemaChange.ladder.blocker.NO_DATASOURCE');
  });
});
