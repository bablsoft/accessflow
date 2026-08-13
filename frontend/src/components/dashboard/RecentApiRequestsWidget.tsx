import { ApiOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardRecentApiRequest } from '@/types/api';

interface Props {
  items: DashboardRecentApiRequest[];
  loading: boolean;
  error?: unknown;
  onRetry?: () => void;
}

/** The current user's most recent governed API requests, with status + risk (AF-500). */
export function RecentApiRequestsWidget({ items, loading, error, onRetry }: Props) {
  const { t } = useTranslation();
  return (
    <ActivityList
      items={items}
      loading={loading}
      error={error}
      onRetry={onRetry}
      emptyIcon={<ApiOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.recent_api_requests.empty')}
      rowKey={(it) => it.id}
      viewAllTo="/api-requests"
      renderRow={(it) => ({
        pills: (
          <>
            <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>
              {it.verb}
            </span>
            <StatusPill status={it.status} size="sm" />
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
        action: <Link to={`/api-requests/${it.id}`}>{t('dashboard.recent_api_requests.view')}</Link>,
      })}
    />
  );
}
