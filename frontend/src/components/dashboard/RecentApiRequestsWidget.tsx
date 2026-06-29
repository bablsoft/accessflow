import { List, Skeleton } from 'antd';
import { ApiOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardRecentApiRequest } from '@/types/api';

interface Props {
  items: DashboardRecentApiRequest[];
  loading: boolean;
}

/** The current user's most recent governed API requests, with status + risk (AF-500). */
export function RecentApiRequestsWidget({ items, loading }: Props) {
  const { t } = useTranslation();
  if (loading) {
    return <Skeleton active paragraph={{ rows: 3 }} />;
  }
  if (items.length === 0) {
    return (
      <EmptyState
        icon={<ApiOutlined style={{ fontSize: 20 }} />}
        title={t('dashboard.recent_api_requests.empty')}
      />
    );
  }
  return (
    <List
      size="small"
      dataSource={items}
      rowKey={(it) => it.id}
      renderItem={(it) => (
        <List.Item
          actions={[
            <Link key="open" to={`/api-requests/${it.id}`}>
              {t('dashboard.recent_api_requests.view')}
            </Link>,
          ]}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <span className="mono" style={{ fontSize: 12, fontWeight: 600 }}>
              {it.verb}
            </span>
            <StatusPill status={it.status} size="sm" />
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
