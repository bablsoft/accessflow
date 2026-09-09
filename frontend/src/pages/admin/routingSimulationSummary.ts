import type { TFunction } from 'i18next';
import type { RoutingSimulationResponse, RoutingSimulationOutcome } from '@/types/api';
import type { SimulationSummary } from '@/components/policies/PolicySimulationDrawer';
import { fmtDate } from '@/utils/dateFormat';

/** `null` on an arm means no policy matched there — the query falls through to the review plan. */
export function outcomeLabel(
  outcome: RoutingSimulationOutcome | undefined,
  t: TFunction,
): string {
  if (!outcome || outcome === 'NO_MATCH') {
    return t('policySimulation.no_match');
  }
  return t(`enums.routing_action.${outcome}`);
}

/** Reduces a routing dry run into the rows and stats the shared drawer renders. */
export function routingSimulationSummary(
  result: RoutingSimulationResponse,
  t: TFunction,
): SimulationSummary {
  return {
    evaluatedCount: result.evaluated_count,
    changedCount: result.changed_count,
    truncated: result.truncated,
    caveats: result.caveats,
    stats: [
      {
        key: 'changed',
        label: t('policySimulation.routing_changed'),
        value: result.changed_count,
      },
    ],
    userColumns: [
      { title: t('policySimulation.col_user'), dataIndex: 'email' },
      { title: t('policySimulation.col_changed'), dataIndex: 'changed' },
      { title: t('policySimulation.col_after'), dataIndex: 'actions' },
    ],
    userRows: result.user_impacts.map((impact) => ({
      key: impact.user_id,
      email: impact.email,
      changed: impact.changed_count,
      actions: impact.simulated_actions.map((a) => outcomeLabel(a, t)).join(', '),
    })),
    detailLabel: t('policySimulation.tab_queries'),
    detailColumns: [
      { title: t('policySimulation.col_submitter'), dataIndex: 'submitter' },
      { title: t('policySimulation.col_when'), dataIndex: 'when' },
      { title: t('policySimulation.col_before'), dataIndex: 'before' },
      { title: t('policySimulation.col_after'), dataIndex: 'after' },
    ],
    detailRows: result.samples.map((sample) => ({
      key: sample.query_request_id,
      submitter: sample.submitted_by_email,
      when: fmtDate(sample.created_at),
      before: outcomeLabel(sample.baseline?.action, t),
      after: outcomeLabel(sample.simulated?.action, t),
    })),
  };
}
