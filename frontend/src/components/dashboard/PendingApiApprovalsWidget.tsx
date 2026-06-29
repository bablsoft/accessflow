import { List, Skeleton } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { RiskPill } from '@/components/common/RiskPill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardPendingApiApproval } from '@/types/api';

interface Props {
  items: DashboardPendingApiApproval[];
  loading: boolean;
}

/** Governed API requests awaiting the current user's review decision (AF-500). */
export function PendingApiApprovalsWidget({ items, loading }: Props) {
  const { t } = useTranslation();
  if (loading) {
    return <Skeleton active paragraph={{ rows: 3 }} />;
  }
  if (items.length === 0) {
    return (
      <EmptyState
        icon={<InboxOutlined style={{ fontSize: 20 }} />}
        title={t('dashboard.pending_api_approvals.empty')}
      />
    );
  }
  return (
    <List
      size="small"
      dataSource={items}
      rowKey={(it) => it.api_request_id}
      renderItem={(it) => (
        <List.Item
          actions={[
            <Link key="open" to="/api-reviews">
              {t('dashboard.pending_api_approvals.review')}
            </Link>,
          ]}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>
              {it.verb}
            </span>
            <RiskPill level={it.ai_risk_level ?? 'LOW'} score={it.ai_risk_score} size="sm" />
            <span className="mono" style={{ fontSize: 12 }}>
              {it.request_path}
            </span>
            <span style={{ fontWeight: 500 }}>{it.connector_name ?? '—'}</span>
            <span className="muted" style={{ fontSize: 12 }}>
              {timeAgo(it.created_at)}
            </span>
          </div>
        </List.Item>
      )}
    />
  );
}
