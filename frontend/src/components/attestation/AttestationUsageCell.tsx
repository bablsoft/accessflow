import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import { Pill } from '@/components/common/Pill';
import { fmtDate } from '@/utils/dateFormat';
import { grantUsageRecommendationLabel } from '@/utils/enumLabels';
import { grantUsageRecommendationColor } from '@/utils/statusColors';
import type { AttestationItem } from '@/types/api';

/**
 * The least-privilege evidence attached to an attestation item (#625) — the whole point of which is
 * that a reviewer decides on facts rather than rubber-stamping.
 *
 * Three states, deliberately distinct:
 * - **no data** — the grant had no usage summary at campaign open. Shown as an explicit "no data"
 *   rather than a dash or a zero, because a missing measurement must not read as an argument for
 *   revoking the grant.
 * - **never used** — measured, and never exercised. The strongest signal on the page.
 * - **idle for N days** — measured, with a last-use date.
 */
export function AttestationUsageCell({ item }: { item: AttestationItem }) {
  const { t } = useTranslation();

  if (item.usage_recommendation === null) {
    return (
      <Tooltip title={t('attestation.usage.no_data_hint')}>
        <span className="muted" style={{ fontSize: 12 }}>
          {t('attestation.usage.no_data')}
        </span>
      </Tooltip>
    );
  }

  const color = grantUsageRecommendationColor(item.usage_recommendation);
  const scope =
    item.usage_granted_target_count === null || item.usage_used_target_count === null
      ? null
      : t('attestation.usage.scope_ratio', {
          used: item.usage_used_target_count,
          granted: item.usage_granted_target_count,
        });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <Pill fg={color.fg} bg={color.bg} border={color.border} withDot size="sm">
        {grantUsageRecommendationLabel(t, item.usage_recommendation)}
      </Pill>
      <span className="muted" style={{ fontSize: 11 }}>
        {item.usage_last_used_at !== null
          ? t('attestation.usage.last_used', { date: fmtDate(item.usage_last_used_at) })
          : item.usage_recommendation === 'INSUFFICIENT_DATA'
            ? // Measured, but not for long enough to conclude anything. Saying "never used" here
              // would read as an argument for revoking a grant that is simply too new to judge.
              t('attestation.usage.not_yet_observed')
            : t('attestation.usage.never_used')}
        {scope !== null ? ` · ${scope}` : ''}
      </span>
    </div>
  );
}
