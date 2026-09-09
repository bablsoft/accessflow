import type { TFunction } from 'i18next';
import type {
  MaskingSimulationResponse,
  RowSecuritySimulationResponse,
  RowSecurityTransition,
} from '@/types/api';
import type { SimulationSummary } from '@/components/policies/PolicySimulationDrawer';
import { fmtDate } from '@/utils/dateFormat';

/**
 * Every RowSecurityTransition, mapped to its label key explicitly. A derived key
 * (`rls_${transition.toLowerCase()}`) silently rendered the raw SCREAMING_SNAKE constant for the
 * two transitions whose key name did not happen to match, and a `defaultValue` fallback hid it.
 */
const TRANSITION_LABEL_KEYS: Record<RowSecurityTransition, string> = {
  UNCHANGED: 'policySimulation.rls_unchanged',
  NEWLY_FILTERED: 'policySimulation.rls_newly_filtered',
  NEWLY_DENY_ALL: 'policySimulation.rls_newly_denied',
  NEWLY_FAILS_CLOSED: 'policySimulation.rls_newly_fails_closed',
  NO_LONGER_FILTERED: 'policySimulation.rls_no_longer_filtered',
  UNCLASSIFIABLE: 'policySimulation.rls_unclassifiable',
};

/** Label for one transition. Exported so the mapping is directly testable across all six values. */
export function transitionLabel(transition: RowSecurityTransition, t: TFunction): string {
  return t(TRANSITION_LABEL_KEYS[transition]);
}

/** Reduces a row-security dry run into the rows and stats the shared drawer renders. */
export function rowSecuritySimulationSummary(
  result: RowSecuritySimulationResponse,
  t: TFunction,
): SimulationSummary {
  return {
    evaluatedCount: result.evaluated_count,
    changedCount: result.changed_count,
    truncated: result.truncated,
    caveats: result.caveats,
    stats: [
      {
        key: 'denied',
        label: t('policySimulation.rls_newly_denied'),
        value: count(result, 'NEWLY_DENY_ALL'),
      },
      {
        key: 'failsClosed',
        label: t('policySimulation.rls_newly_fails_closed'),
        value: count(result, 'NEWLY_FAILS_CLOSED'),
      },
      {
        key: 'unclassifiable',
        label: t('policySimulation.rls_unclassifiable'),
        value: result.unclassifiable_count,
      },
    ],
    userColumns: [
      { title: t('policySimulation.col_user'), dataIndex: 'email' },
      { title: t('policySimulation.rls_newly_filtered'), dataIndex: 'filtered' },
      { title: t('policySimulation.rls_newly_denied'), dataIndex: 'denied' },
      { title: t('policySimulation.rls_newly_fails_closed'), dataIndex: 'failsClosed' },
    ],
    userRows: result.user_impacts.map((impact) => ({
      key: impact.user_id,
      email: impact.email,
      filtered: impact.newly_filtered_count,
      denied: impact.newly_denied_count,
      failsClosed: impact.newly_fails_closed_count,
    })),
    detailLabel: t('policySimulation.tab_queries'),
    detailColumns: [
      { title: t('policySimulation.col_submitter'), dataIndex: 'submitter' },
      { title: t('policySimulation.col_when'), dataIndex: 'when' },
      { title: t('policySimulation.col_after'), dataIndex: 'transition' },
      { title: t('policySimulation.col_reason'), dataIndex: 'reason' },
    ],
    detailRows: result.samples.map((sample) => ({
      key: sample.query_request_id,
      submitter: sample.submitted_by_email,
      when: fmtDate(sample.created_at),
      transition: transitionLabel(sample.transition, t),
      reason: sample.reason ?? '',
    })),
  };
}

function count(result: RowSecuritySimulationResponse, transition: string): number {
  return result.transition_counts.find((entry) => entry.transition === transition)?.count ?? 0;
}

/** Reduces a masking dry run into the rows and stats the shared drawer renders. */
export function maskingSimulationSummary(
  result: MaskingSimulationResponse,
  t: TFunction,
): SimulationSummary {
  return {
    evaluatedCount: result.evaluated_count,
    changedCount: result.changed_count,
    truncated: result.truncated,
    caveats: result.caveats,
    stats: [
      {
        key: 'masked',
        label: t('policySimulation.masking_newly_masked'),
        value: result.newly_masked_count,
      },
      {
        key: 'revealed',
        label: t('policySimulation.masking_newly_revealed'),
        value: result.newly_revealed_count,
      },
    ],
    userColumns: [
      { title: t('policySimulation.col_user'), dataIndex: 'email' },
      { title: t('policySimulation.col_newly_masked'), dataIndex: 'masked' },
      { title: t('policySimulation.col_newly_revealed'), dataIndex: 'revealed' },
      { title: t('policySimulation.col_changed'), dataIndex: 'affected' },
    ],
    userRows: result.user_impacts.map((impact) => ({
      key: impact.user_id,
      email: impact.email,
      masked: impact.newly_masked_columns.join(', '),
      revealed: impact.newly_revealed_columns.join(', '),
      affected: impact.affected_query_count,
    })),
    detailLabel: t('policySimulation.tab_columns'),
    detailColumns: [
      { title: t('policySimulation.col_column'), dataIndex: 'column' },
      { title: t('policySimulation.col_newly_masked'), dataIndex: 'masked' },
      { title: t('policySimulation.col_newly_revealed'), dataIndex: 'revealed' },
    ],
    detailRows: result.column_impacts.map((impact) => ({
      key: impact.column_name,
      column: impact.column_name,
      masked: impact.newly_masked_query_count,
      revealed: impact.newly_revealed_query_count,
    })),
  };
}
