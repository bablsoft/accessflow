import { lazy, Suspense, type ReactNode } from 'react';
import { Button, Skeleton, Space, Tabs } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { Navigate, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { PushApprovalsToggle } from '@/components/review/PushApprovalsToggle';
import { QueryReviewsTab } from '@/pages/reviews/QueryReviewsTab';
import { usePendingReviewCounts } from '@/hooks/usePendingReviewCounts';
import { useAuthStore } from '@/store/authStore';
import { reviewKeys } from '@/api/reviews';
import {
  resolveReviewHubTab,
  reviewHubPath,
  visibleReviewHubTabs,
  type ReviewHubTabKey,
} from '@/utils/reviewHubTabs';

// The query tab is the historical `/reviews` page and stays in the main chunk; the API and
// deployment tabs were separate lazy routes before #772 and stay lazy.
const ApiReviewsTab = lazy(() =>
  import('@/pages/apigov/ApiReviewsTab').then((m) => ({ default: m.ApiReviewsTab })),
);
const PendingDeploymentsTab = lazy(() =>
  import('@/pages/deployments/DeploymentReviewTabs').then((m) => ({
    default: m.PendingDeploymentsTab,
  })),
);
const RollbackReviewsTab = lazy(() =>
  import('@/pages/deployments/DeploymentReviewTabs').then((m) => ({
    default: m.RollbackReviewsTab,
  })),
);

interface ReviewHubTab {
  key: ReviewHubTabKey;
  labelKey: string;
  element: ReactNode;
}

/**
 * Tab registry — one entry per review queue, in display order. The permission that gates each
 * tab lives in `REVIEW_HUB_TAB_PERMISSION`; adding a queue is one entry here and one there.
 */
const TABS: ReviewHubTab[] = [
  { key: 'queries', labelKey: 'reviews.hub.tab_queries', element: <QueryReviewsTab /> },
  { key: 'api', labelKey: 'reviews.hub.tab_api', element: <ApiReviewsTab /> },
  { key: 'deployments', labelKey: 'reviews.hub.tab_deployments', element: <PendingDeploymentsTab /> },
  { key: 'rollbacks', labelKey: 'reviews.hub.tab_rollbacks', element: <RollbackReviewsTab /> },
];

/**
 * The unified review queue (#772): every request kind the viewer may review, as tabs on one
 * page, with the pending count on each tab and the sum on the sidebar badge. Tabs the viewer
 * lacks the permission for are not rendered — a reviewer holding only `API_REQUEST_REVIEW`
 * sees no Deployments tab, not an empty one. Only the active tab is mounted, so the inactive
 * queues are never fetched (and never leak hidden rows into the DOM).
 */
export function ReviewHubPage() {
  const { t } = useTranslation();
  const user = useAuthStore((s) => s.user);
  const queryClient = useQueryClient();
  const counts = usePendingReviewCounts();
  const [searchParams, setSearchParams] = useSearchParams();

  const requested = searchParams.get('tab');
  const active = resolveReviewHubTab(requested, user);
  if (!active) return null;
  // A `?tab=` the viewer may not see (or that does not exist) is replaced, so the URL never
  // lies about what is on screen. A bare `/reviews` is left alone.
  if (requested !== null && requested !== active) {
    return <Navigate to={reviewHubPath(active)} replace />;
  }

  const visible = visibleReviewHubTabs(user);
  const tabs = TABS.filter((tab) => visible.includes(tab.key));
  const activeTab = tabs.find((tab) => tab.key === active);
  if (!activeTab) return null;

  const onRefresh = () => {
    void queryClient.invalidateQueries({ queryKey: reviewKeys.pending() });
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('reviews.title')}
        subtitle={t('reviews.hub.subtitle')}
        actions={
          // The push-approvals opt-in (AF-444) covers query decisions only, so it travels with
          // the Queries tab rather than sitting above every queue.
          active === 'queries' ? (
            <Space>
              <PushApprovalsToggle />
              <Button icon={<ReloadOutlined />} onClick={onRefresh}>
                {t('common.refresh')}
              </Button>
            </Space>
          ) : undefined
        }
      />
      <Tabs
        activeKey={active}
        onChange={(key) => setSearchParams({ tab: key }, { replace: true })}
        style={{ padding: '0 28px' }}
        items={tabs.map((tab) => ({
          key: tab.key,
          label: t(tab.labelKey, { count: counts[tab.key] }),
          // Rendered below, not as `children` — an inactive AntD tab panel stays mounted (hidden),
          // which would fetch every queue and duplicate row text in the DOM.
        }))}
      />
      <Suspense
        fallback={
          <div style={{ padding: 24 }}>
            <Skeleton active paragraph={{ rows: 6 }} />
          </div>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
          {activeTab.element}
        </div>
      </Suspense>
    </div>
  );
}
