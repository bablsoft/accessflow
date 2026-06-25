import { useMemo } from 'react';
import { App, Button, Card, Checkbox, Dropdown, Skeleton, Space, Statistic, Switch } from 'antd';
import {
  DownloadOutlined,
  ReloadOutlined,
  SettingOutlined,
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
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { DashboardWidgetCard } from '@/components/dashboard/DashboardWidgetCard';
import { PendingApprovalsWidget } from '@/components/dashboard/PendingApprovalsWidget';
import { RecentQueriesWidget } from '@/components/dashboard/RecentQueriesWidget';
import { QueryTrendsWidget } from '@/components/dashboard/QueryTrendsWidget';
import { SuggestionBacklogWidget } from '@/components/dashboard/SuggestionBacklogWidget';
import { AnomalyAlertsWidget } from '@/components/dashboard/AnomalyAlertsWidget';
import {
  dashboardKeys,
  exportDashboardSummary,
  fetchDashboardSummary,
  fetchDigestSubscription,
  setDigestSubscription,
  type DashboardExportFormat,
} from '@/api/dashboard';
import {
  DASHBOARD_WIDGET_IDS,
  usePreferencesStore,
  type DashboardWidgetId,
} from '@/store/preferencesStore';
import { dashboardErrorMessage } from '@/utils/apiErrors';
import type { DashboardSummary } from '@/types/api';

export default function DashboardPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const widgets = usePreferencesStore((s) => s.dashboardWidgets);
  const toggleVisibility = usePreferencesStore((s) => s.toggleWidgetVisibility);
  const toggleCollapsed = usePreferencesStore((s) => s.toggleWidgetCollapsed);
  const reorderWidgets = usePreferencesStore((s) => s.reorderWidgets);

  const summaryQuery = useQuery({
    queryKey: dashboardKeys.summary(),
    queryFn: fetchDashboardSummary,
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
    onError: () => message.error(t('dashboard.export_failed')),
  });

  // Reconcile persisted order/visibility with the known widget set (forward-compatible: a widget
  // unknown to the persisted prefs is appended and shown by default).
  const orderedIds = useMemo<DashboardWidgetId[]>(() => {
    const known = new Set(DASHBOARD_WIDGET_IDS);
    const fromPrefs = widgets.order.filter((id) => known.has(id));
    const missing = DASHBOARD_WIDGET_IDS.filter((id) => !fromPrefs.includes(id));
    return [...fromPrefs, ...missing];
  }, [widgets.order]);

  const isVisible = (id: DashboardWidgetId) =>
    widgets.visible.includes(id) || !widgets.order.includes(id);

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

  const summary = summaryQuery.data;
  const visibleIds = orderedIds.filter(isVisible);

  const customizeMenu = {
    items: DASHBOARD_WIDGET_IDS.map((id) => ({
      key: id,
      label: (
        <Checkbox
          checked={isVisible(id)}
          onClick={(e) => {
            e.stopPropagation();
            toggleVisibility(id);
          }}
        >
          {t(`dashboard.widgets.${id}`)}
        </Checkbox>
      ),
    })),
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('dashboard.title')}
        subtitle={t('dashboard.subtitle')}
        actions={
          <Space wrap>
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
            <Dropdown menu={customizeMenu} trigger={['click']}>
              <Button icon={<SettingOutlined />}>{t('dashboard.customize')}</Button>
            </Dropdown>
            <Button icon={<ReloadOutlined />} onClick={() => summaryQuery.refetch()}>
              {t('common.refresh')}
            </Button>
          </Space>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '20px 28px' }}>
        <SummaryCounts summary={summary} loading={summaryQuery.isLoading} />
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
          <SortableContext items={visibleIds} strategy={verticalListSortingStrategy}>
            <Space orientation="vertical" size={16} style={{ width: '100%', marginTop: 16 }}>
              {visibleIds.map((id) => (
                <DashboardWidgetCard
                  key={id}
                  id={id}
                  title={t(`dashboard.widgets.${id}`)}
                  badge={badgeFor(id, summary)}
                  collapsed={!!widgets.collapsed[id]}
                  onToggleCollapsed={() => toggleCollapsed(id)}
                >
                  {renderWidget(id, summary, summaryQuery.isLoading)}
                </DashboardWidgetCard>
              ))}
            </Space>
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
  return undefined;
}

function renderWidget(
  id: DashboardWidgetId,
  summary: DashboardSummary | undefined,
  loading: boolean,
) {
  switch (id) {
    case 'pendingApprovals':
      return (
        <PendingApprovalsWidget items={summary?.recent_pending_approvals ?? []} loading={loading} />
      );
    case 'recentQueries':
      return <RecentQueriesWidget items={summary?.recent_queries ?? []} loading={loading} />;
    case 'trends':
      return <QueryTrendsWidget />;
    case 'suggestions':
      return <SuggestionBacklogWidget />;
    case 'anomalies':
      return <AnomalyAlertsWidget />;
    default:
      return null;
  }
}

function SummaryCounts({
  summary,
  loading,
}: {
  summary: DashboardSummary | undefined;
  loading: boolean;
}) {
  const { t } = useTranslation();
  if (loading || !summary) {
    return <Skeleton active paragraph={{ rows: 1 }} />;
  }
  const cards: Array<{ key: string; label: string; value: number }> = [
    { key: 'pending', label: t('dashboard.summary.pending_approvals'), value: summary.pending_approvals_count },
    { key: 'open', label: t('dashboard.summary.open_queries'), value: summary.open_queries_count },
    { key: 'anomalies', label: t('dashboard.summary.open_anomalies'), value: summary.open_anomalies_count },
    { key: 'suggestions', label: t('dashboard.summary.open_suggestions'), value: summary.open_suggestions_count },
  ];
  return (
    <div
      style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12 }}
    >
      {cards.map((c) => (
        <Card key={c.key} size="small" data-testid={`dashboard-stat-${c.key}`}>
          <Statistic title={c.label} value={c.value} />
        </Card>
      ))}
    </div>
  );
}
