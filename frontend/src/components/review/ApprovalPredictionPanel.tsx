import { InfoCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type { ApprovalPredictionDetail, QueryStatus } from '@/types/api';
import { ApprovalPredictionBadge } from './ApprovalPredictionBadge';

interface ApprovalPredictionPanelProps {
  prediction?: ApprovalPredictionDetail | null;
  status: QueryStatus;
}

const SKIP_REASON_KEYS: Record<string, string> = {
  DISABLED: 'approval_prediction.skipped_disabled',
  MODEL_NOT_SERVING: 'approval_prediction.skipped_model_not_serving',
};

const NOTICE_STYLE = {
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
} as const;

/**
 * Advisory approval-likelihood panel on the query detail page (AF-645): the probability that a
 * human reviewer approves this query, derived from the organization's own past review decisions.
 * Strictly informational — the copy never tells the reviewer what to decide.
 */
export function ApprovalPredictionPanel({ prediction, status }: ApprovalPredictionPanelProps) {
  const { t } = useTranslation();

  if (!prediction) {
    return (
      <div className="muted" style={{ padding: 14, fontSize: 13 }}>
        {status === 'PENDING_AI' || status === 'PENDING_REVIEW'
          ? t('approval_prediction.pending')
          : t('approval_prediction.unavailable')}
      </div>
    );
  }

  if (prediction.failed) {
    return (
      <div role="status" style={NOTICE_STYLE}>
        <WarningOutlined style={{ color: 'var(--risk-med)', marginTop: 2, flexShrink: 0 }} />
        <span>{t('approval_prediction.failed')}</span>
      </div>
    );
  }

  if (prediction.skipped || prediction.probability === null || prediction.probability === undefined) {
    const reasonKey = prediction.skipped_reason
      ? SKIP_REASON_KEYS[prediction.skipped_reason]
      : undefined;
    return (
      <div role="status" style={NOTICE_STYLE}>
        <InfoCircleOutlined style={{ color: 'var(--fg-faint)', marginTop: 2, flexShrink: 0 }} />
        <span>{t(reasonKey ?? 'approval_prediction.skipped_generic')}</span>
      </div>
    );
  }

  return (
    <div style={{ padding: 14 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          padding: 12,
          background: 'var(--bg-elev)',
          border: '1px solid var(--border)',
          borderRadius: 'var(--radius-md)',
          fontSize: 12,
        }}
      >
        <span className="muted">{t('approval_prediction.value_label')}</span>
        <ApprovalPredictionBadge probability={prediction.probability} />
      </div>
      <div className="muted" style={{ marginTop: 10, fontSize: 11.5, lineHeight: 1.5 }}>
        {t('approval_prediction.tooltip')}
      </div>
    </div>
  );
}
