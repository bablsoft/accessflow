import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';

interface ApprovalPredictionBadgeProps {
  /** Advisory approval probability in [0,1]; null/undefined renders nothing. */
  probability?: number | null;
  size?: 'sm' | 'md';
}

/**
 * Compact approval-likelihood badge for review-queue rows (AF-645). Deliberately neutral: it uses
 * the muted palette rather than the risk tokens, because the number is a triage signal — not a risk
 * level and not a recommendation to approve or reject.
 */
export function ApprovalPredictionBadge({ probability, size = 'md' }: ApprovalPredictionBadgeProps) {
  const { t } = useTranslation();

  if (probability === null || probability === undefined) return null;

  const percent = Math.round(probability * 100);
  return (
    <Tooltip title={t('approval_prediction.tooltip')}>
      <span
        className={`af-pill af-pill-${size}`}
        style={{
          color: 'var(--fg-muted)',
          background: 'var(--bg-sunken)',
          borderColor: 'var(--border)',
        }}
        data-testid="approval-prediction-badge"
      >
        {t('approval_prediction.badge_value', { percent })}
      </span>
    </Tooltip>
  );
}
