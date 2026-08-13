import { UnorderedListOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardRecentQuery } from '@/types/api';

interface Props {
  items: DashboardRecentQuery[];
  loading: boolean;
  error?: unknown;
  onRetry?: () => void;
}

/** The current user's most recent query submissions, with status + risk (AF-498). */
export function RecentQueriesWidget({ items, loading, error, onRetry }: Props) {
  const { t } = useTranslation();
  return (
    <ActivityList
      items={items}
      loading={loading}
      error={error}
      onRetry={onRetry}
      emptyIcon={<UnorderedListOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.recent.empty')}
      rowKey={(it) => it.id}
      viewAllTo="/queries"
      renderRow={(it) => ({
        pills: (
          <>
            <QueryTypePill type={it.query_type} size="sm" />
            <StatusPill status={it.status} size="sm" />
            <RiskPill
              level={it.ai_risk_level ?? 'LOW'}
              score={it.ai_risk_score}
              failed={it.ai_failed}
              size="sm"
            />
          </>
        ),
        primary: <span style={{ fontWeight: 500 }}>{it.datasource_name ?? '—'}</span>,
        meta: timeAgo(it.created_at),
        action: <Link to={`/queries/${it.id}`}>{t('dashboard.recent.view')}</Link>,
      })}
    />
  );
}
