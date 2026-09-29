import { Tooltip } from 'antd';
import { useTranslation } from 'react-i18next';
import type { AttestationItem } from '@/types/api';
import { rowLimitSourceLabel } from './rowLimitSource';

/**
 * The row limit the subject actually gets next to the one configured on the grant under review
 * (#1084). Shown only when they differ — a datasource cap or a lower group grant — so a reviewer
 * never certifies a limit that does not apply. Items snapshotted before this evidence existed carry
 * none and render nothing.
 */
export function AttestationRowLimitNote({ item }: { item: AttestationItem }) {
  const { t } = useTranslation();
  const effective = item.effective_row_limit;
  const configured = item.row_limit_override;
  const source = rowLimitSourceLabel(t, item.row_limit_source);
  if (effective == null || source === null) return null;

  const groupLimited = item.row_limit_source?.startsWith('group:') ?? false;
  const differs = configured != null ? configured !== effective : groupLimited;
  if (!differs) return null;

  return (
    <Tooltip title={t('attestation.row_limit.hint')}>
      <span className="muted" style={{ fontSize: 11 }} tabIndex={0}>
        {configured != null
          ? t('attestation.row_limit.configured_applies', { configured, effective, source })
          : t('attestation.row_limit.applies', { effective, source })}
      </span>
    </Tooltip>
  );
}
