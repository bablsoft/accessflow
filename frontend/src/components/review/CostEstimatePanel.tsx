import { InfoCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { CostEstimateDetail, QueryStatus } from '@/types/api';
import { fmtNum } from '@/utils/dateFormat';
import { formatCost } from '@/utils/queryPlan';
import { PlanTree } from '@/components/editor/PlanTree';

interface CostEstimatePanelProps {
  estimate: CostEstimateDetail | null;
  status: QueryStatus;
}

/**
 * Persisted pre-flight cost / blast-radius panel on the query detail page (AF-624): the estimate
 * computed automatically at submission — estimated rows, exact affected-row count for writes, scan
 * type, cost, and the execution-plan tree — with pending / unavailable / failed fallbacks.
 */
export function CostEstimatePanel({ estimate, status }: CostEstimatePanelProps) {
  const { t } = useTranslation();

  if (!estimate) {
    return (
      <div className="muted" style={{ padding: 14, fontSize: 13 }}>
        {status === 'PENDING_AI'
          ? t('cost_estimate_panel.pending')
          : t('cost_estimate_panel.unavailable')}
      </div>
    );
  }

  if (estimate.failed) {
    return (
      <div
        role="status"
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 10,
          margin: 14,
          padding: 12,
          background: 'var(--bg-elev)',
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-md)',
          fontSize: 12,
          lineHeight: 1.5,
        }}
      >
        <WarningOutlined style={{ color: 'var(--risk-med)', marginTop: 2, flexShrink: 0 }} />
        <span>
          {t('cost_estimate_panel.failed')}
          {estimate.error_message ? ` — ${estimate.error_message}` : ''}
        </span>
      </div>
    );
  }

  const hasAffectedCount =
    estimate.affected_row_count !== null && estimate.affected_row_count !== undefined;

  if (!estimate.supported && !hasAffectedCount) {
    return (
      <div
        role="status"
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 10,
          margin: 14,
          padding: 12,
          background: 'var(--bg-elev)',
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-md)',
          fontSize: 12,
          lineHeight: 1.5,
        }}
      >
        <InfoCircleOutlined style={{ color: 'var(--fg-faint)', marginTop: 2, flexShrink: 0 }} />
        <span>{estimate.unsupported_reason ?? t('cost_estimate_panel.unavailable')}</span>
      </div>
    );
  }

  const cost = formatCost(estimate.estimated_cost);
  return (
    <div style={{ padding: 14 }}>
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
          padding: 12,
          marginBottom: estimate.plan || estimate.raw_plan ? 14 : 0,
          background: 'var(--bg-elev)',
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-md)',
          fontSize: 11.5,
          fontFamily: 'var(--font-mono)',
        }}
      >
        {hasAffectedCount && (
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span className="muted">{t('cost_estimate_panel.affected_rows_label')}</span>
            <span>{fmtNum(estimate.affected_row_count as number)}</span>
          </div>
        )}
        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <span className="muted">{t('cost_estimate_panel.est_rows_label')}</span>
          <span>
            {estimate.estimated_rows === null || estimate.estimated_rows === undefined
              ? '—'
              : fmtNum(estimate.estimated_rows)}
          </span>
        </div>
        {estimate.scan_type && (
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span className="muted">{t('cost_estimate_panel.scan_type_label')}</span>
            <span>{estimate.scan_type}</span>
          </div>
        )}
        {cost && (
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span className="muted">{t('cost_estimate_panel.cost_label')}</span>
            <span>{cost}</span>
          </div>
        )}
      </div>
      {estimate.plan ? (
        <PlanTree plan={estimate.plan} />
      ) : estimate.raw_plan ? (
        <pre
          className="mono"
          style={{
            margin: 0,
            padding: 8,
            background: 'var(--bg-elev)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius-sm)',
            fontSize: 11,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
          }}
        >
          <code>{estimate.raw_plan}</code>
        </pre>
      ) : null}
    </div>
  );
}
