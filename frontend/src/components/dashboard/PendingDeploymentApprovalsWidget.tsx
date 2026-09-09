import { InboxOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { RiskPill } from '@/components/common/RiskPill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardPendingDeploymentApproval } from '@/types/api';
import { reviewHubPath } from '@/utils/reviewHubTabs';

interface Props {
  items: DashboardPendingDeploymentApproval[];
  loading: boolean;
  error?: unknown;
  onRetry?: () => void;
}

/** Governed deployments awaiting the current user's review decision (#926). */
export function PendingDeploymentApprovalsWidget({ items, loading, error, onRetry }: Props) {
  const { t } = useTranslation();
  return (
    <ActivityList
      items={items}
      loading={loading}
      error={error}
      onRetry={onRetry}
      emptyIcon={<InboxOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.pending_deployment_approvals.empty')}
      rowKey={(it) => it.deployment_request_id}
      viewAllTo={reviewHubPath('deployments')}
      renderRow={(it) => ({
        pills: (
          <>
            <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>
              {it.version}
            </span>
            <RiskPill level={it.ai_risk_level ?? 'LOW'} score={it.ai_risk_score} size="sm" />
          </>
        ),
        primary: (
          <>
            <span style={{ fontWeight: 500 }}>{it.pipeline_name ?? '—'}</span>{' '}
            <span className="muted">{it.environment_name ?? '—'}</span>
          </>
        ),
        meta: timeAgo(it.created_at),
        action: (
          <Link to={`/deployments/${it.deployment_request_id}`}>
            {t('dashboard.pending_deployment_approvals.review')}
          </Link>
        ),
      })}
    />
  );
}
