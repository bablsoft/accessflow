import { InboxOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardPendingApproval } from '@/types/api';

interface Props {
  items: DashboardPendingApproval[];
  loading: boolean;
  error?: unknown;
  onRetry?: () => void;
}

/** Queries awaiting the current user's review decision (AF-498). */
export function PendingApprovalsWidget({ items, loading, error, onRetry }: Props) {
  const { t } = useTranslation();
  return (
    <ActivityList
      items={items}
      loading={loading}
      error={error}
      onRetry={onRetry}
      emptyIcon={<InboxOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.pending.empty')}
      rowKey={(it) => it.query_request_id}
      viewAllTo="/reviews"
      renderRow={(it) => ({
        pills: (
          <>
            <QueryTypePill type={it.query_type} size="sm" />
            <RiskPill level={it.ai_risk_level ?? 'LOW'} score={it.ai_risk_score} size="sm" />
          </>
        ),
        primary: <span style={{ fontWeight: 500 }}>{it.datasource_name ?? '—'}</span>,
        meta: `${it.submitted_by_email ?? '—'} · ${timeAgo(it.created_at)}`,
        action: <Link to="/reviews">{t('dashboard.pending.review')}</Link>,
      })}
    />
  );
}
