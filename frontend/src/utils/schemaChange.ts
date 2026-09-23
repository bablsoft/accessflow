import axios, { type AxiosError } from 'axios';
import type { TFunction } from 'i18next';
import type {
  SchemaChangeLadderRung,
  SchemaChangePromotion,
  SchemaChangePromotionStatus,
  SchemaChangeStatementFinding,
  SchemaDriftFinding,
  SchemaDriftScan,
} from '@/types/api';
import { freezeBehaviorLabel } from './enumLabels';

/** Mirrors the backend Bean Validation on the change-set request DTOs. */
export const SCHEMA_CHANGE_NAME_MIN = 3;
export const SCHEMA_CHANGE_NAME_MAX = 255;
export const SCHEMA_CHANGE_DESCRIPTION_MAX = 2000;
export const SCHEMA_CHANGE_SQL_TEXT_MAX = 100_000;
/**
 * The default of `ACCESSFLOW_SCHEMACHANGE_MAX_STATEMENTS`. The server enforces the configured
 * value (400 `SCHEMA_CHANGE_SET_STATEMENT_LIMIT`); this spares the round-trip at the default.
 */
export const SCHEMA_CHANGE_MAX_STATEMENTS = 50;

/** Any promotion in one of these states freezes the statement list and the delete. */
export const FREEZING_PROMOTION_STATUSES: readonly SchemaChangePromotionStatus[] = [
  'PENDING',
  'IN_REVIEW',
  'APPROVED',
  'APPLIED',
  'PARTIALLY_APPLIED',
];

/** The promotion that freezes the set, newest first, or undefined when it is still editable. */
export function freezingPromotion(
  promotions: readonly SchemaChangePromotion[],
): SchemaChangePromotion | undefined {
  return promotions.find((p) => FREEZING_PROMOTION_STATUSES.includes(p.status));
}

/** The drift reason codes the UI localizes; anything else is shown verbatim. */
export const SCHEMA_DRIFT_REASON_CODES = [
  'ENGINE_NOT_APPLICABLE',
  'BASELINE_PREVIOUS_ENVIRONMENT_NOT_FOUND',
  'BASELINE_ENVIRONMENT_NOT_CONFIGURED',
  'BASELINE_ENVIRONMENT_NOT_FOUND',
  'BASELINE_ENVIRONMENT_IS_TARGET',
  'BASELINE_ENVIRONMENT_NO_DATASOURCE',
  'BASELINE_ENGINE_MISMATCH',
  'BASELINE_SNAPSHOT_MISSING',
  'BASELINE_SNAPSHOT_UNREADABLE',
  'BASELINE_DATASOURCE_REBOUND',
  'BASELINE_INTROSPECTION_FAILED',
  'TARGET_INTROSPECTION_FAILED',
  'SCAN_FAILED',
  'FK_COMPARISON_SUPPRESSED',
  'SCAN_SUPERSEDED',
] as const;

export type SchemaDriftReasonCode = (typeof SCHEMA_DRIFT_REASON_CODES)[number];

/** Splits a scan's `error_message` (`CODE` or `CODE: cause`) into its parts. */
export function parseScanReason(
  message: string | null | undefined,
): { code: string; cause?: string } | null {
  if (!message) return null;
  const idx = message.indexOf(':');
  if (idx < 0) return { code: message.trim() };
  const cause = message.slice(idx + 1).trim();
  return { code: message.slice(0, idx).trim(), ...(cause ? { cause } : {}) };
}

/** A localized sentence for a scan reason; unknown codes fall back to the raw text. */
export function scanReasonText(t: TFunction, message: string | null | undefined): string | null {
  const parsed = parseScanReason(message);
  if (!parsed) return null;
  if (!(SCHEMA_DRIFT_REASON_CODES as readonly string[]).includes(parsed.code)) {
    return message ?? null;
  }
  const text = t(`schemaChange.drift.reason.${parsed.code}`);
  return parsed.cause ? `${text} (${parsed.cause})` : text;
}

/** The newest scan per environment; `scans` arrive newest `started_at` first. */
export function latestScanByEnvironment(
  scans: readonly SchemaDriftScan[],
): Map<string, SchemaDriftScan> {
  const latest = new Map<string, SchemaDriftScan>();
  for (const scan of scans) {
    if (!latest.has(scan.environment_id)) latest.set(scan.environment_id, scan);
  }
  return latest;
}

/**
 * Findings grouped by environment, in ladder order first and then any environment the ladder no
 * longer knows (a deleted environment's findings stay visible).
 */
export function groupFindingsByEnvironment(
  findings: readonly SchemaDriftFinding[],
  ladderOrder: readonly string[],
): { environmentId: string; findings: SchemaDriftFinding[] }[] {
  const groups = new Map<string, SchemaDriftFinding[]>();
  for (const finding of findings) {
    const list = groups.get(finding.environment_id) ?? [];
    list.push(finding);
    groups.set(finding.environment_id, list);
  }
  const ordered = [
    ...ladderOrder.filter((id) => groups.has(id)),
    ...[...groups.keys()].filter((id) => !ladderOrder.includes(id)),
  ];
  return ordered.map((environmentId) => ({
    environmentId,
    findings: groups.get(environmentId) ?? [],
  }));
}

export interface StatementProblem {
  severity: 'ERROR' | 'BLOCK' | 'WARN';
  message: string;
  ruleId?: string;
  datasourceId?: string;
}

interface StatementProblemBody {
  error?: string;
  detail?: string;
  statementIndex?: number;
  findings?: SchemaChangeStatementFinding[];
}

/**
 * The per-statement problems a failed save carries: `statementIndex` on a gate refusal, or every
 * blocking `findings` entry on `SCHEMA_CHANGE_SET_STATEMENT_BLOCKED`. Null when the error names
 * no statement — the caller then shows the `detail` as a toast.
 */
export function statementProblemsFromError(err: unknown): Record<number, StatementProblem[]> | null {
  if (!axios.isAxiosError(err)) return null;
  const body = (err as AxiosError<StatementProblemBody>).response?.data;
  if (!body) return null;
  if (Array.isArray(body.findings) && body.findings.length > 0) {
    return findingsByStatement(body.findings, 'BLOCK');
  }
  if (typeof body.statementIndex === 'number' && body.detail) {
    return { [body.statementIndex]: [{ severity: 'ERROR', message: body.detail }] };
  }
  return null;
}

export function findingsByStatement(
  findings: readonly SchemaChangeStatementFinding[],
  severity: StatementProblem['severity'] = 'WARN',
): Record<number, StatementProblem[]> {
  const out: Record<number, StatementProblem[]> = {};
  for (const f of findings) {
    const list = out[f.statement_index] ?? [];
    list.push({ severity, message: f.message, ruleId: f.rule_id, datasourceId: f.datasource_id });
    out[f.statement_index] = list;
  }
  return out;
}

/** Why a ladder rung cannot be promoted, as one sentence; null when it is not blocked. */
export function ladderRungReason(t: TFunction, rung: SchemaChangeLadderRung): string | null {
  if (rung.state !== 'BLOCKED' || !rung.blocker) return null;
  switch (rung.blocker) {
    case 'LOWER_ENVIRONMENT_NOT_APPLIED':
      return t('schemaChange.ladder.blocker.LOWER_ENVIRONMENT_NOT_APPLIED', {
        environment: rung.blocking_environment_name ?? '—',
      });
    case 'FREEZE_ACTIVE': {
      const behavior = rung.freeze_behavior ? freezeBehaviorLabel(t, rung.freeze_behavior) : '—';
      return rung.freeze_reason
        ? t('schemaChange.ladder.blocker.FREEZE_ACTIVE_reason', { behavior, reason: rung.freeze_reason })
        : t('schemaChange.ladder.blocker.FREEZE_ACTIVE', { behavior });
    }
    default:
      return t(`schemaChange.ladder.blocker.${rung.blocker}`);
  }
}
