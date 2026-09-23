import { Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { useTranslation } from 'react-i18next';
import { Pill } from '@/components/common/Pill';
import { EmptyState } from '@/components/common/EmptyState';
import { routingActionLabel } from '@/utils/enumLabels';
import { decisionStepOutcomeColor } from '@/utils/statusColors';
import type { RoutingPolicyTraceEntry } from '@/types/api';

interface Props {
  /** `null` when the backend did not evaluate the policy list on this path. */
  policies: RoutingPolicyTraceEntry[] | null;
}

function YesNo({ value, positive }: { value: boolean; positive: 'MATCH' | 'ALLOW' }) {
  const { t } = useTranslation();
  const color = decisionStepOutcomeColor(value ? positive : 'NO_MATCH');
  return (
    <Pill fg={color.fg} bg={color.bg} border={color.border} size="sm">
      {value ? t('common.yes') : t('common.no')}
    </Pill>
  );
}

/**
 * Every enabled routing policy in priority order, not just the winner — the near-miss row is often
 * the answer the admin came for. Shared by all three decision kinds.
 */
export function RoutingPoliciesTraceTable({ policies }: Props) {
  const { t } = useTranslation();

  if (policies === null) {
    return (
      <EmptyState
        size="sm"
        title={t('decisionTrace.policies_not_evaluated')}
        description={t('decisionTrace.policies_not_evaluated_hint')}
      />
    );
  }
  if (policies.length === 0) {
    return <EmptyState size="sm" title={t('decisionTrace.policies_none')} />;
  }

  const columns: TableColumnsType<RoutingPolicyTraceEntry> = [
    { title: t('decisionTrace.col_priority'), dataIndex: 'priority', key: 'priority', width: 90 },
    { title: t('decisionTrace.col_policy'), dataIndex: 'name', key: 'name' },
    {
      title: t('decisionTrace.col_action'),
      key: 'action',
      render: (_: unknown, row) => routingActionLabel(t, row.action),
    },
    {
      title: t('decisionTrace.col_required_approvals'),
      key: 'required_approvals',
      width: 150,
      render: (_: unknown, row) => (row.required_approvals == null ? '—' : row.required_approvals),
    },
    {
      title: t('decisionTrace.col_matched'),
      key: 'matched',
      width: 110,
      render: (_: unknown, row) => <YesNo value={row.matched} positive="MATCH" />,
    },
    {
      title: t('decisionTrace.col_decisive'),
      key: 'decisive',
      width: 110,
      render: (_: unknown, row) => <YesNo value={row.decisive} positive="ALLOW" />,
    },
  ];

  return (
    <Table<RoutingPolicyTraceEntry>
      size="small"
      rowKey="policy_id"
      columns={columns}
      dataSource={policies}
      pagination={false}
      scroll={{ x: 'max-content' }}
      rowClassName={(row) => (row.decisive ? 'af-trace-decisive-row' : '')}
      data-testid="routing-policies-trace"
    />
  );
}
