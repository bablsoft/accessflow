import { List, Skeleton } from 'antd';
import { UnorderedListOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { QueryTypePill } from '@/components/common/QueryTypePill';
import { timeAgo } from '@/utils/dateFormat';
import type { DashboardRecentQuery } from '@/types/api';

interface Props {
  items: DashboardRecentQuery[];
  loading: boolean;
}

/** The current user's most recent query submissions, with status + risk (AF-498). */
export function RecentQueriesWidget({ items, loading }: Props) {
  const { t } = useTranslation();
  if (loading) {
    return <Skeleton active paragraph={{ rows: 3 }} />;
  }
  if (items.length === 0) {
    return (
      <EmptyState
        icon={<UnorderedListOutlined style={{ fontSize: 20 }} />}
        title={t('dashboard.recent.empty')}
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
            <Link key="open" to={`/queries/${it.id}`}>
              {t('dashboard.recent.view')}
            </Link>,
          ]}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <QueryTypePill type={it.query_type} size="sm" />
            <StatusPill status={it.status} size="sm" />
            <RiskPill
              level={it.ai_risk_level ?? 'LOW'}
              score={it.ai_risk_score}
              failed={it.ai_failed}
              size="sm"
            />
            <span style={{ fontWeight: 500 }}>{it.datasource_name ?? '—'}</span>
            <span className="muted" style={{ fontSize: 12 }}>
              {timeAgo(it.created_at)}
            </span>
          </div>
        </List.Item>
      )}
    />
  );
}
