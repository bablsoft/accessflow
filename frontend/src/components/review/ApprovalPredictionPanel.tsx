import { InfoCircleOutlined, WarningOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import type {
  ApprovalPredictionDetail,
  ApprovalPredictionSkipReason,
  QueryStatus,
} from '@/types/api';
import { msSince } from '@/utils/dateFormat';
import { ApprovalPredictionBadge } from './ApprovalPredictionBadge';

interface ApprovalPredictionPanelProps {
  prediction?: ApprovalPredictionDetail | null;
  status: QueryStatus;
  /** The query's `updated_at`; bounds how long a missing prediction reads as "computing". */
  updatedAt?: string;
}

/** Exhaustive over the backend's closed skip-token set — a new token breaks this map on purpose. */
const SKIP_REASON_KEYS: Record<ApprovalPredictionSkipReason, string> = {
  DISABLED: 'approval_prediction.skipped_disabled',
  MODEL_NOT_SERVING: 'approval_prediction.skipped_model_not_serving',
};

/**
 * How long after the last status change a missing prediction still reads as "computing". Scoring
 * fires off the transition into review and takes seconds; past this the row is never coming (a
 * query that predates the feature, or a listener that never ran), so say so instead of spinning.
 */
const PENDING_GRACE_MS = 5 * 60_000;

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
export function ApprovalPredictionPanel({
  prediction,
  status,
  updatedAt,
}: ApprovalPredictionPanelProps) {
  const { t } = useTranslation();

  if (!prediction) {
    const age = msSince(updatedAt);
    const withinGrace = Number.isNaN(age) || age < PENDING_GRACE_MS;
    const awaitingScore =
      status === 'PENDING_AI' || (status === 'PENDING_REVIEW' && withinGrace);
    return (
      <div className="muted" style={{ padding: 14, fontSize: 13 }}>
        {awaitingScore
          ? t('approval_prediction.pending')
          : t('approval_prediction.unavailable')}
      </div>
    );
  }

  if (prediction.failed) {
    return (
      <div role="status" style={NOTICE_STYLE}>
        {/* --risk-med is this theme's amber; it colours the warning glyph, not the prediction. */}
        <WarningOutlined style={{ color: 'var(--risk-med)', marginTop: 2, flexShrink: 0 }} />
        <span>{t('approval_prediction.failed')}</span>
      </div>
    );
  }

  if (prediction.skipped || prediction.probability === null || prediction.probability === undefined) {
    // The server owns the token set; anything unrecognised degrades to the generic copy rather
    // than leaking a raw machine token into the UI.
    const reasonKey =
      (prediction.skipped_reason && SKIP_REASON_KEYS[prediction.skipped_reason]) ||
      'approval_prediction.unavailable';
    return (
      <div role="status" style={NOTICE_STYLE}>
        <InfoCircleOutlined style={{ color: 'var(--fg-faint)', marginTop: 2, flexShrink: 0 }} />
        <span>{t(reasonKey)}</span>
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
