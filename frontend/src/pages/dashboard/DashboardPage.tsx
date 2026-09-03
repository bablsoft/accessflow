import { useEffect, useMemo, useState } from 'react';
import { App, Button, Checkbox, Dropdown, Skeleton, Space, Switch, Tooltip } from 'antd';
import {
  DownloadOutlined,
  ReloadOutlined,
  SettingOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  arrayMove,
  rectSortingStrategy,
  sortableKeyboardCoordinates,
} from '@dnd-kit/sortable';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusPill } from '@/components/common/StatusPill';
import { DashboardWidgetCard } from '@/components/dashboard/DashboardWidgetCard';
import { StatTile } from '@/components/dashboard/StatTile';
import { WidgetError } from '@/components/dashboard/WidgetError';
import { PendingApprovalsWidget } from '@/components/dashboard/PendingApprovalsWidget';
import { RecentQueriesWidget } from '@/components/dashboard/RecentQueriesWidget';
import { TrendsWidget } from '@/components/dashboard/TrendsWidget';
import { RiskRingWidget } from '@/components/dashboard/RiskRingWidget';
import { ActivityHeatmapWidget } from '@/components/dashboard/ActivityHeatmapWidget';
import { SuggestionBacklogWidget } from '@/components/dashboard/SuggestionBacklogWidget';
import { AnomalyAlertsWidget } from '@/components/dashboard/AnomalyAlertsWidget';
import { RecentApiRequestsWidget } from '@/components/dashboard/RecentApiRequestsWidget';
import { PendingApiApprovalsWidget } from '@/components/dashboard/PendingApiApprovalsWidget';
import { AttestationsDueWidget } from '@/components/dashboard/AttestationsDueWidget';
import { MyAccessRequestsWidget } from '@/components/dashboard/MyAccessRequestsWidget';
import { MyRequestGroupsWidget } from '@/components/dashboard/MyRequestGroupsWidget';
import {
  dashboardKeys,
  exportDashboardSummary,
  fetchDashboardSummary,
  fetchDigestSubscription,
  fetchMyApiRequestTrends,
  fetchMyQueryTrends,
  setDigestSubscription,
  type DashboardExportFormat,
} from '@/api/dashboard';
import { dailyTotals, halfWindowDelta, trendsFiltersForRange } from '@/utils/trendSeries';
import type { StatTileTrend } from '@/components/dashboard/StatTile';
import { anomalyKeys } from '@/api/anomalies';
import { attestationKeys } from '@/api/attestation';
import { accessRequestKeys } from '@/api/accessRequests';
import { requestGroupKeys } from '@/api/requestGroups';
import {
  DASHBOARD_WIDGET_IDS,
  usePreferencesStore,
  widgetSize,
  type DashboardWidgetId,
} from '@/store/preferencesStore';
import { useAuthStore } from '@/store/authStore';
import { useWebSocket } from '@/hooks/useWebSocket';
import type { AuthUser } from '@/api/auth';
import { hasAnyPermission, type Permission } from '@/utils/permissions';
import { apiErrorMessage, dashboardErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { fmtDate } from '@/utils/dateFormat';
import type { DashboardSummary, MyQueryTrends, MyQueryTrendsFilters } from '@/types/api';
import './dashboard.css';
import { reviewHubPath } from '@/utils/reviewHubTabs';

// Each widget is shown only to users holding a permission for which it is meaningful (mirrors
// the sidebar nav model, AF-522): a non-reviewer never sees the reviewer queue, etc.
const WIDGET_PERMISSIONS: Record<DashboardWidgetId, Permission[]> = {
  pendingApprovals: ['QUERY_REVIEW'],
  attestationsDue: ['ATTESTATION_REVIEW'],
  recentQueries: ['QUERY_SUBMIT_SELECT'],
  myAccessRequests: ['QUERY_SUBMIT_SELECT'],
  myRequestGroups: ['QUERY_SUBMIT_SELECT'],
  trends: ['QUERY_SUBMIT_SELECT'],
  riskMix: ['QUERY_SUBMIT_SELECT'],
  activityHeatmap: ['QUERY_SUBMIT_SELECT'],
  suggestions: ['QUERY_SUBMIT_DML'],
  anomalies: ['ANOMALY_MANAGE'],
  recentApiRequests: ['QUERY_SUBMIT_SELECT'],
  apiRequestTrends: ['QUERY_SUBMIT_SELECT'],
  pendingApiApprovals: ['API_REQUEST_REVIEW'],
};

function widgetAllowed(id: DashboardWidgetId, user: AuthUser | null): boolean {
  return hasAnyPermission(user, WIDGET_PERMISSIONS[id]);
}

/** Sparkline + delta payload for a stat tile, from a day-bucketed trends window. */
function tileTrend(
  data: MyQueryTrends | undefined,
  filters: MyQueryTrendsFilters,
): StatTileTrend | undefined {
  if (!data) return undefined;
  const totals = dailyTotals(data.status_by_day, filters.from ?? '', filters.to ?? '');
  if (!totals.some((d) => d.value > 0)) return undefined;
  const { delta, previous } = halfWindowDelta(totals);
  return { spark: totals.map((d) => ({ date: d.date, value: d.value })), delta, previous };
}

export default function DashboardPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const { subscribe } = useWebSocket();

  const user = useAuthStore((s) => s.user);
  const widgets = usePreferencesStore((s) => s.dashboardWidgets);
  const toggleVisibility = usePreferencesStore((s) => s.toggleWidgetVisibility);
  const toggleCollapsed = usePreferencesStore((s) => s.toggleWidgetCollapsed);
  const reorderWidgets = usePreferencesStore((s) => s.reorderWidgets);
  const setWidgetSizePref = usePreferencesStore((s) => s.setWidgetSize);
  const resetDashboardWidgets = usePreferencesStore((s) => s.resetDashboardWidgets);

  const [customizeOpen, setCustomizeOpen] = useState(false);

  // Only the widgets the current user's permissions can actually use are eligible for the layout.
  const availableIds = useMemo<DashboardWidgetId[]>(
    () => DASHBOARD_WIDGET_IDS.filter((id) => widgetAllowed(id, user)),
    [user],
  );

  const summaryQuery = useQuery({
    queryKey: dashboardKeys.summary(),
    queryFn: fetchDashboardSummary,
  });

  // Tile sparklines share the trends cache with TrendsWidget (same key), gated to roles whose
  // tiles can actually show them.
  const trendsRange = usePreferencesStore((s) => s.dashboardTrendsRange);
  const tileFilters = useMemo(() => trendsFiltersForRange(trendsRange, new Date()), [trendsRange]);
  const queryTrendsQuery = useQuery({
    queryKey: dashboardKeys.trends(tileFilters),
    queryFn: () => fetchMyQueryTrends(tileFilters),
    enabled: availableIds.includes('recentQueries'),
  });
  const apiTrendsQuery = useQuery({
    queryKey: dashboardKeys.apiRequestTrends(tileFilters),
    queryFn: () => fetchMyApiRequestTrends(tileFilters),
    enabled: availableIds.includes('recentApiRequests'),
  });
  const digestQuery = useQuery({
    queryKey: dashboardKeys.digestSubscription(),
    queryFn: fetchDigestSubscription,
  });

  const digestMutation = useMutation({
    mutationFn: (enabled: boolean) => setDigestSubscription(enabled),
    onSuccess: (data) => {
      queryClient.setQueryData(dashboardKeys.digestSubscription(), data);
    },
    onError: (err) => message.error(dashboardErrorMessage(err)),
  });

  const exportMutation = useMutation({
    mutationFn: (format: DashboardExportFormat) => exportDashboardSummary(format),
    onSuccess: ({ blob, filename }) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.rel = 'noopener noreferrer';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('dashboard.export_failed'))),
  });

  // WS events are invalidation hints only (frontend-page.md): the widgets re-fetch via REST.
  useEffect(
    () =>
      subscribe('anomaly.detected', () => {
        void queryClient.invalidateQueries({ queryKey: anomalyKeys.all });
        void queryClient.invalidateQueries({ queryKey: dashboardKeys.summary() });
      }),
    [subscribe, queryClient],
  );
  useEffect(
    () =>
      subscribe('query.status_changed', () => {
        void queryClient.invalidateQueries({ queryKey: dashboardKeys.summary() });
      }),
    [subscribe, queryClient],
  );
  useEffect(
    () =>
      subscribe('review.new_request', () => {
        void queryClient.invalidateQueries({ queryKey: dashboardKeys.summary() });
      }),
    [subscribe, queryClient],
  );

  // Reconcile persisted order/visibility with the role-available widget set (forward-compatible: a
  // widget unknown to the persisted prefs is appended and shown by default; widgets the role can't
  // use are dropped entirely).
  const orderedIds = useMemo<DashboardWidgetId[]>(() => {
    const available = new Set(availableIds);
    const fromPrefs = widgets.order.filter((id) => available.has(id));
    const missing = availableIds.filter((id) => !fromPrefs.includes(id));
    return [...fromPrefs, ...missing];
  }, [widgets.order, availableIds]);

  // Deny-list visibility (prefs v1): everything is visible unless explicitly hidden, so widgets
  // shipped after the prefs were first persisted show up and can be hidden right away.
  const isVisible = (id: DashboardWidgetId) => !widgets.hidden.includes(id);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const onDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    const oldIndex = orderedIds.indexOf(active.id as DashboardWidgetId);
    const newIndex = orderedIds.indexOf(over.id as DashboardWidgetId);
    if (oldIndex < 0 || newIndex < 0) return;
    reorderWidgets(arrayMove(orderedIds, oldIndex, newIndex));
  };

  const refreshAll = () => {
    void queryClient.invalidateQueries({ queryKey: dashboardKeys.all });
    void queryClient.invalidateQueries({ queryKey: anomalyKeys.all });
    void queryClient.invalidateQueries({ queryKey: attestationKeys.worklist() });
    void queryClient.invalidateQueries({ queryKey: accessRequestKeys.all });
    void queryClient.invalidateQueries({ queryKey: requestGroupKeys.lists() });
  };

  // The suggestions stat tile has no dedicated page: it reveals the widget instead.
  const revealSuggestions = () => {
    if (widgets.hidden.includes('suggestions')) toggleVisibility('suggestions');
    if (widgets.collapsed.suggestions) toggleCollapsed('suggestions');
    window.setTimeout(() => {
      document
        .querySelector('[data-testid="dashboard-widget-suggestions"]')
        ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 100);
  };

  const summary = summaryQuery.data;
  const visibleIds = orderedIds.filter(isVisible);

  const customizeMenu = {
    items: [
      ...availableIds.map((id) => ({
        key: id,
        label: (
          // pointer-events: none keeps the checkbox purely presentational — the whole row is the
          // hit target and the menu's onClick is the single toggle path (no double-toggling).
          <span style={{ pointerEvents: 'none' }}>
            <Checkbox checked={isVisible(id)}>{t(`dashboard.widgets.${id}`)}</Checkbox>
          </span>
        ),
      })),
      { type: 'divider' as const },
      {
        key: 'reset',
        icon: <UndoOutlined />,
        label: t('dashboard.reset_layout'),
      },
    ],
    onClick: ({ key }: { key: string }) => {
      if (key === 'reset') {
        resetDashboardWidgets();
        setCustomizeOpen(false);
        return;
      }
      toggleVisibility(key as DashboardWidgetId);
    },
  };

  const digestTooltip =
    digestQuery.data?.last_sent_at != null
      ? t('dashboard.digest_last_sent', { time: fmtDate(digestQuery.data.last_sent_at) })
      : t('dashboard.digest_never_sent');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('dashboard.title')}
        subtitle={t('dashboard.subtitle')}
        actions={
          <Space wrap>
            <Tooltip title={digestTooltip}>
              <Space size={6}>
                <Switch
                  checked={digestQuery.data?.enabled ?? false}
                  loading={digestQuery.isLoading || digestMutation.isPending}
                  onChange={(checked) => digestMutation.mutate(checked)}
                  aria-label={t('dashboard.weekly_digest')}
                />
                <span className="muted" style={{ fontSize: 12 }}>
                  {t('dashboard.weekly_digest')}
                </span>
              </Space>
            </Tooltip>
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  {
                    key: 'pdf',
                    label: t('dashboard.export_pdf'),
                    onClick: () => exportMutation.mutate('PDF'),
                  },
                  {
                    key: 'csv',
                    label: t('dashboard.export_csv'),
                    onClick: () => exportMutation.mutate('CSV'),
                  },
                ],
              }}
            >
              <Button icon={<DownloadOutlined />} loading={exportMutation.isPending}>
                {t('dashboard.export')}
              </Button>
            </Dropdown>
            <Dropdown
              menu={customizeMenu}
              trigger={['click']}
              open={customizeOpen}
              onOpenChange={(open, info) => {
                // Keep the menu open while the user toggles widgets; only trigger/outside closes it.
                if (info.source === 'menu') return;
                setCustomizeOpen(open);
              }}
            >
              <Button icon={<SettingOutlined />}>{t('dashboard.customize')}</Button>
            </Dropdown>
            <Button icon={<ReloadOutlined />} onClick={refreshAll}>
              {t('common.refresh')}
            </Button>
          </Space>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '20px 28px' }}>
        <SummaryCounts
          summary={summary}
          loading={summaryQuery.isLoading}
          error={summaryQuery.isError ? summaryQuery.error : undefined}
          onRetry={() => void summaryQuery.refetch()}
          available={availableIds}
          onOpenSuggestions={revealSuggestions}
          openQueriesTrend={tileTrend(queryTrendsQuery.data, tileFilters)}
          apiRequestsTrend={tileTrend(apiTrendsQuery.data, tileFilters)}
        />
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={visibleIds} strategy={rectSortingStrategy}>
            <div className="af-dashboard-grid">
              {visibleIds.map((id) => (
                <DashboardWidgetCard
                  key={id}
                  id={id}
                  title={t(`dashboard.widgets.${id}`)}
                  badge={badgeFor(id, summary)}
                  collapsed={!!widgets.collapsed[id]}
                  size={widgetSize(widgets, id)}
                  onToggleCollapsed={() => toggleCollapsed(id)}
                  onToggleSize={() =>
                    setWidgetSizePref(id, widgetSize(widgets, id) === 'half' ? 'full' : 'half')
                  }
                >
                  {renderWidget(id, summaryQuery)}
                </DashboardWidgetCard>
              ))}
            </div>
          </SortableContext>
        </DndContext>
      </div>
    </div>
  );
}

function badgeFor(id: DashboardWidgetId, summary: DashboardSummary | undefined): number | undefined {
  if (!summary) return undefined;
  if (id === 'pendingApprovals') return summary.pending_approvals_count;
  if (id === 'suggestions') return summary.open_suggestions_count;
  if (id === 'anomalies') return summary.open_anomalies_count;
  if (id === 'pendingApiApprovals') return summary.pending_api_approvals_count;
  return undefined;
}

interface SummaryQueryLike {
  data: DashboardSummary | undefined;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  refetch: () => unknown;
}

function renderWidget(id: DashboardWidgetId, summaryQuery: SummaryQueryLike) {
  const summary = summaryQuery.data;
  const loading = summaryQuery.isLoading;
  const error = summaryQuery.isError ? summaryQuery.error : undefined;
  const onRetry = () => void summaryQuery.refetch();
  switch (id) {
    case 'pendingApprovals':
      return (
        <PendingApprovalsWidget
          items={summary?.recent_pending_approvals ?? []}
          loading={loading}
          error={error}
          onRetry={onRetry}
        />
      );
    case 'attestationsDue':
      return <AttestationsDueWidget />;
    case 'recentQueries':
      return (
        <RecentQueriesWidget
          items={summary?.recent_queries ?? []}
          loading={loading}
          error={error}
          onRetry={onRetry}
        />
      );
    case 'myAccessRequests':
      return <MyAccessRequestsWidget />;
    case 'myRequestGroups':
      return <MyRequestGroupsWidget />;
    case 'trends':
      return <TrendsWidget kind="queries" />;
    case 'riskMix':
      return <RiskRingWidget />;
    case 'activityHeatmap':
      return <ActivityHeatmapWidget />;
    case 'suggestions':
      return <SuggestionBacklogWidget />;
    case 'anomalies':
      return <AnomalyAlertsWidget />;
    case 'recentApiRequests':
      return (
        <RecentApiRequestsWidget
          items={summary?.recent_api_requests ?? []}
          loading={loading}
          error={error}
          onRetry={onRetry}
        />
      );
    case 'apiRequestTrends':
      return <TrendsWidget kind="apiRequests" />;
    case 'pendingApiApprovals':
      return (
        <PendingApiApprovalsWidget
          items={summary?.recent_pending_api_approvals ?? []}
          loading={loading}
          error={error}
          onRetry={onRetry}
        />
      );
    default:
      return null;
  }
}

function SummaryCounts({
  summary,
  loading,
  error,
  onRetry,
  available,
  onOpenSuggestions,
  openQueriesTrend,
  apiRequestsTrend,
}: {
  summary: DashboardSummary | undefined;
  loading: boolean;
  error?: unknown;
  onRetry: () => void;
  available: DashboardWidgetId[];
  onOpenSuggestions: () => void;
  openQueriesTrend?: StatTileTrend;
  apiRequestsTrend?: StatTileTrend;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  if (loading) {
    return <Skeleton active paragraph={{ rows: 1 }} />;
  }
  if (error !== undefined || !summary) {
    return <WidgetError error={error} onRetry={onRetry} />;
  }
  // Each stat card is tied to a widget so it only shows when that widget is available to the role,
  // and the whole tile links where the number leads (AF-498 redesign).
  const allCards: Array<{
    key: string;
    widget: DashboardWidgetId;
    label: string;
    value: number;
    onOpen: () => void;
    trend?: StatTileTrend;
  }> = [
    {
      key: 'pending',
      widget: 'pendingApprovals',
      label: t('dashboard.summary.pending_approvals'),
      value: summary.pending_approvals_count,
      onOpen: () => navigate('/reviews'),
    },
    {
      key: 'open',
      widget: 'recentQueries',
      label: t('dashboard.summary.open_queries'),
      value: summary.open_queries_count,
      onOpen: () => navigate('/queries'),
      ...(openQueriesTrend ? { trend: openQueriesTrend } : {}),
    },
    {
      key: 'anomalies',
      widget: 'anomalies',
      label: t('dashboard.summary.open_anomalies'),
      value: summary.open_anomalies_count,
      onOpen: () => navigate('/admin/anomalies'),
    },
    {
      key: 'suggestions',
      widget: 'suggestions',
      label: t('dashboard.summary.open_suggestions'),
      value: summary.open_suggestions_count,
      onOpen: onOpenSuggestions,
    },
    {
      key: 'openApiRequests',
      widget: 'recentApiRequests',
      label: t('dashboard.summary.open_api_requests'),
      value: summary.open_api_requests_count,
      onOpen: () => navigate('/api-requests'),
      ...(apiRequestsTrend ? { trend: apiRequestsTrend } : {}),
    },
    {
      key: 'pendingApiApprovals',
      widget: 'pendingApiApprovals',
      label: t('dashboard.summary.pending_api_approvals'),
      value: summary.pending_api_approvals_count,
      onOpen: () => navigate(reviewHubPath('api')),
    },
  ];
  const cards = allCards.filter((c) => available.includes(c.widget));
  if (cards.length === 0) {
    return null;
  }
  const statusBreakdown = summary.status_counts.slice(0, 4);
  return (
    <div className="af-dashboard-stats">
      {cards.map((c) => (
        <StatTile
          key={c.key}
          label={c.label}
          value={c.value}
          testId={`dashboard-stat-${c.key}`}
          onOpen={c.onOpen}
          trend={c.trend}
        >
          {c.key === 'open' && statusBreakdown.length > 0 && (
            <div className="af-stat-breakdown">
              {statusBreakdown.map((s) => (
                <span
                  key={s.status}
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
                >
                  <StatusPill status={s.status} size="sm" />
                  <span className="mono muted" style={{ fontSize: 11 }}>
                    {s.count}
                  </span>
                </span>
              ))}
            </div>
          )}
        </StatTile>
      ))}
    </div>
  );
}
