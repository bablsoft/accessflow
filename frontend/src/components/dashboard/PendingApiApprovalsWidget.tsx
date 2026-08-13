import { InboxOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { RiskPill } from '@/components/common/RiskPill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardPendingApiApproval } from '@/types/api';

interface Props {
  items: DashboardPendingApiApproval[];
  loading: boolean;
  error?: unknown;
  onRetry?: () => void;
}

/** Governed API requests awaiting the current user's review decision (AF-500). */
export function PendingApiApprovalsWidget({ items, loading, error, onRetry }: Props) {
  const { t } = useTranslation();
  return (
    <ActivityList
      items={items}
      loading={loading}
      error={error}
      onRetry={onRetry}
      emptyIcon={<InboxOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.pending_api_approvals.empty')}
      rowKey={(it) => it.api_request_id}
      viewAllTo="/api-reviews"
      renderRow={(it) => ({
        pills: (
          <>
            <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>
              {it.verb}
            </span>
            <RiskPill level={it.ai_risk_level ?? 'LOW'} score={it.ai_risk_score} size="sm" />
          </>
        ),
        primary: (
          <>
            <span className="mono" style={{ fontSize: 12 }}>
              {it.request_path}
            </span>{' '}
            <span style={{ fontWeight: 500 }}>{it.connector_name ?? '—'}</span>
          </>
        ),
        meta: timeAgo(it.created_at),
        action: <Link to="/api-reviews">{t('dashboard.pending_api_approvals.review')}</Link>,
      })}
    />
  );
}
