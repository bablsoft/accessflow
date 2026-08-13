import { SafetyCertificateOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { Pill } from '@/components/common/Pill';
import { attestationKeys, listAttestationWorklist } from '@/api/attestation';
import { fmtDate } from '@/utils/dateFormat';
import { grantUsageRecommendationLabel } from '@/utils/enumLabels';
import { grantUsageRecommendationColor } from '@/utils/statusColors';
import type { AttestationItem } from '@/types/api';

const WORKLIST_FILTERS = { size: 5 };

/** Attestation items awaiting the current user's certify/revoke decision (AF-384, AF-498 redesign). */
export function AttestationsDueWidget() {
  const { t } = useTranslation();
  const worklistQuery = useQuery({
    queryKey: attestationKeys.worklistFor(WORKLIST_FILTERS),
    queryFn: () => listAttestationWorklist(WORKLIST_FILTERS),
  });

  return (
    <ActivityList
      items={worklistQuery.data?.content ?? []}
      loading={worklistQuery.isLoading}
      error={worklistQuery.error}
      onRetry={() => void worklistQuery.refetch()}
      emptyIcon={<SafetyCertificateOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.attestations.empty')}
      rowKey={(it: AttestationItem) => it.id}
      viewAllTo="/reviews/attestations"
      renderRow={(it) => {
        // null recommendation means "no data", which must not be rendered as a judgement
        // (see AttestationUsageCell) — the pill is simply omitted here.
        const rec = it.usage_recommendation;
        const color = rec === null ? null : grantUsageRecommendationColor(rec);
        return {
          pills:
            rec !== null && color !== null ? (
              <Pill fg={color.fg} bg={color.bg} border={color.border} withDot size="sm">
                {grantUsageRecommendationLabel(t, rec)}
              </Pill>
            ) : undefined,
          primary: (
            <>
              <span style={{ fontWeight: 500 }}>{it.subject_user_email}</span>{' '}
              <span className="muted">{it.datasource_name ?? '—'}</span>
            </>
          ),
          meta:
            it.permission_expires_at !== null
              ? t('dashboard.attestations.expires', { date: fmtDate(it.permission_expires_at) })
              : undefined,
        };
      }}
    />
  );
}
