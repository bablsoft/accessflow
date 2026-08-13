import { GroupOutlined } from '@ant-design/icons';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { RequestGroupStatusPill } from '@/components/common/RequestGroupStatusPill';
import { listRequestGroups, requestGroupKeys } from '@/api/requestGroups';
import { useAuthStore } from '@/store/authStore';
import { timeAgo } from '@/utils/dateFormat';
import type { RequestGroup } from '@/types/api';

/** The current user's grouped requests (AF-501) surfaced on the dashboard (AF-498 redesign). */
export function MyRequestGroupsWidget() {
  const { t } = useTranslation();
  // GET /request-groups returns ALL org groups for admins — scope to the caller explicitly so
  // the widget's "My" title stays truthful for every role.
  const userId = useAuthStore((s) => s.user?.id);
  const filters = { size: 5, ...(userId ? { submitted_by: userId } : {}) };
  const groupsQuery = useQuery({
    queryKey: requestGroupKeys.list(filters),
    queryFn: () => listRequestGroups(filters),
  });

  return (
    <ActivityList
      items={groupsQuery.data?.content ?? []}
      loading={groupsQuery.isLoading}
      error={groupsQuery.error}
      onRetry={() => void groupsQuery.refetch()}
      emptyIcon={<GroupOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.request_groups.empty')}
      rowKey={(it: RequestGroup) => it.id}
      viewAllTo="/request-groups"
      renderRow={(it) => ({
        pills: <RequestGroupStatusPill status={it.status} size="sm" />,
        primary: (
          <>
            <span style={{ fontWeight: 500 }}>{it.name}</span>{' '}
            <span className="muted">
              {t('dashboard.request_groups.item_count', { count: it.items.length })}
            </span>
          </>
        ),
        meta: timeAgo(it.created_at),
        action: <Link to={`/request-groups/${it.id}`}>{t('dashboard.request_groups.view')}</Link>,
      })}
    />
  );
}
