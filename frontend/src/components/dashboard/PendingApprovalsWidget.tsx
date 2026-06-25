import { List, Skeleton } from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardPendingApproval } from '@/types/api';

interface Props {
  items: DashboardPendingApproval[];
  loading: boolean;
}

/** Queries awaiting the current user's review decision (AF-498). */
export function PendingApprovalsWidget({ items, loading }: Props) {
  const { t } = useTranslation();
  if (loading) {
    return <Skeleton active paragraph={{ rows: 3 }} />;
  }
  if (items.length === 0) {
    return (
      <EmptyState
        icon={<InboxOutlined style={{ fontSize: 20 }} />}
        title={t('dashboard.pending.empty')}
      />
    );
  }
  return (
    <List
      size="small"
      dataSource={items}
      rowKey={(it) => it.query_request_id}
      renderItem={(it) => (
        <List.Item
          actions={[
            <Link key="open" to="/reviews">
              {t('dashboard.pending.review')}
            </Link>,
          ]}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <QueryTypePill type={it.query_type} size="sm" />
            <RiskPill level={it.ai_risk_level ?? 'LOW'} score={it.ai_risk_score} size="sm" />
            <span style={{ fontWeight: 500 }}>{it.datasource_name ?? '—'}</span>
            <span className="muted" style={{ fontSize: 12 }}>
              {it.submitted_by_email ?? '—'} · {timeAgo(it.created_at)}
            </span>
          </div>
        </List.Item>
      )}
    />
  );
}
