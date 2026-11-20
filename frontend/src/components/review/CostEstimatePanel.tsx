import { InfoCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { CostEstimateDetail, QueryStatus } from '@/types/api';
import { fmtNum } from '@/utils/dateFormat';
import { formatBytes, formatCost } from '@/utils/queryPlan';
import { PlanTree } from '@/components/editor/PlanTree';

interface CostEstimatePanelProps {
  estimate: CostEstimateDetail | null;
  status: QueryStatus;
}

/**
 * Persisted pre-flight cost / blast-radius panel on the query detail page (AF-624): the estimate
 * computed automatically at submission — estimated rows, exact affected-row count for writes, the
 * warehouse bytes-scanned estimate (#941), scan type, cost, and the execution-plan tree — with pending / unavailable / failed fallbacks.
 */
export function CostEstimatePanel({ estimate, status }: CostEstimatePanelProps) {
  const { t } = useTranslation();

  if (!estimate) {
    return (
      <div className="muted u-text-sm" style={{ padding: 14 }}>
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
        className="af-box u-flex u-items-start u-p-3 u-text-xs u-leading-normal" style={{ gap: 10, margin: 14 }}
      >
        <WarningOutlined className="u-fg-risk-med u-shrink-0" style={{ marginTop: 2 }} />
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
        className="af-box u-flex u-items-start u-p-3 u-text-xs u-leading-normal" style={{ gap: 10, margin: 14 }}
      >
        <InfoCircleOutlined className="u-fg-faint u-shrink-0" style={{ marginTop: 2 }} />
        <span>{estimate.unsupported_reason ?? t('cost_estimate_panel.unavailable')}</span>
      </div>
    );
  }

  const cost = formatCost(estimate.estimated_cost);
  return (
    <div style={{ padding: 14 }}>
      <div
        className="af-box u-flex u-col u-p-3 u-text-xs u-mono" style={{ gap: 6, marginBottom: estimate.plan || estimate.raw_plan ? 14 : 0 }}
      >
        {hasAffectedCount && (
          <div className="u-flex u-justify-between">
            <span className="muted">{t('cost_estimate_panel.affected_rows_label')}</span>
            <span>{fmtNum(estimate.affected_row_count as number)}</span>
          </div>
        )}
        <div className="u-flex u-justify-between">
          <span className="muted">{t('cost_estimate_panel.est_rows_label')}</span>
          <span>
            {estimate.estimated_rows === null || estimate.estimated_rows === undefined
              ? '—'
              : fmtNum(estimate.estimated_rows)}
          </span>
        </div>
        {estimate.estimated_bytes_scanned !== null &&
          estimate.estimated_bytes_scanned !== undefined && (
            <div className="u-flex u-justify-between">
              <span className="muted">{t('cost_estimate_panel.est_bytes_label')}</span>
              <span>{formatBytes(estimate.estimated_bytes_scanned)}</span>
            </div>
          )}
        {estimate.scan_type && (
          <div className="u-flex u-justify-between">
            <span className="muted">{t('cost_estimate_panel.scan_type_label')}</span>
            <span>{estimate.scan_type}</span>
          </div>
        )}
        {cost && (
          <div className="u-flex u-justify-between">
            <span className="muted">{t('cost_estimate_panel.cost_label')}</span>
            <span>{cost}</span>
          </div>
        )}
      </div>
      {estimate.plan ? (
        <PlanTree plan={estimate.plan} />
      ) : estimate.raw_plan ? (
        <pre
          className="mono u-m-0 u-p-2 u-text-2xs u-bg-elev u-border u-rounded-sm u-pre-wrap u-break-word"
        >
          <code>{estimate.raw_plan}</code>
        </pre>
      ) : null}
    </div>
  );
}
