import { KeyOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { AccessStatusPill } from '@/components/common/AccessStatusPill';
import { accessRequestKeys, listMyAccessRequests } from '@/api/accessRequests';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import type { AccessRequest } from '@/types/api';

const MINE_FILTERS = { size: 5 };

/** The current user's JIT access requests with status and expiry (AF-498 redesign). */
export function MyAccessRequestsWidget() {
  const { t } = useTranslation();
  const requestsQuery = useQuery({
    queryKey: accessRequestKeys.mine(MINE_FILTERS),
    queryFn: () => listMyAccessRequests(MINE_FILTERS),
  });

  return (
    <ActivityList
      items={requestsQuery.data?.content ?? []}
      loading={requestsQuery.isLoading}
      error={requestsQuery.error}
      onRetry={() => void requestsQuery.refetch()}
      emptyIcon={<KeyOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.access_requests.empty')}
      rowKey={(it: AccessRequest) => it.id}
      viewAllTo="/access-requests"
      renderRow={(it) => ({
        pills: <AccessStatusPill status={it.status} size="sm" />,
        primary: (
          <span style={{ fontWeight: 500 }}>
            {it.datasource_name ?? it.connector_name ?? '—'}
          </span>
        ),
        meta:
          it.status === 'APPROVED' && it.expires_at !== null
            ? t('dashboard.access_requests.expires', { date: fmtDate(it.expires_at) })
            : timeAgo(it.created_at),
      })}
    />
  );
}
