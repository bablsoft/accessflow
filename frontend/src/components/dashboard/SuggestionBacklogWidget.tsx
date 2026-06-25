import { App, Button, List, Skeleton, Tag } from 'antd';
import { BulbOutlined, CloseOutlined, EditOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { RiskPill } from '@/components/common/RiskPill';
import {
  dashboardKeys,
  dismissDashboardSuggestion,
  fetchDashboardSuggestions,
} from '@/api/dashboard';
import { optimizationTypeLabel } from '@/utils/enumLabels';
import { dashboardErrorMessage } from '@/utils/apiErrors';
import type { DashboardSuggestion } from '@/types/api';

/** Actionable AI optimization suggestions drawn from the user's analyses; dismissable (AF-498). */
export function SuggestionBacklogWidget() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const suggestionsQuery = useQuery({
    queryKey: dashboardKeys.suggestions(),
    queryFn: fetchDashboardSuggestions,
  });

  const dismissMutation = useMutation({
    mutationFn: (id: string) => dismissDashboardSuggestion(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: dashboardKeys.suggestions() });
      void queryClient.invalidateQueries({ queryKey: dashboardKeys.summary() });
      message.success(t('dashboard.suggestions.dismissed'));
    },
    onError: (err) => message.error(dashboardErrorMessage(err)),
  });

  const openInEditor = (s: DashboardSuggestion) => {
    navigate('/editor', { state: { presetSql: s.sql, datasourceId: s.datasource_id } });
  };

  if (suggestionsQuery.isLoading || !suggestionsQuery.data) {
    return <Skeleton active paragraph={{ rows: 3 }} />;
  }
  const items = suggestionsQuery.data.suggestions;
  if (items.length === 0) {
    return (
      <EmptyState
        icon={<BulbOutlined style={{ fontSize: 20 }} />}
        title={t('dashboard.suggestions.empty')}
      />
    );
  }
  return (
    <List
      size="small"
      dataSource={items}
      rowKey={(s) => s.id}
      renderItem={(s) => (
        <List.Item
          actions={[
            <Button
              key="open"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openInEditor(s)}
            >
              {t('dashboard.suggestions.open_in_editor')}
            </Button>,
            <Button
              key="dismiss"
              size="small"
              type="text"
              icon={<CloseOutlined />}
              aria-label={t('dashboard.suggestions.dismiss')}
              loading={dismissMutation.isPending && dismissMutation.variables === s.id}
              onClick={() => dismissMutation.mutate(s.id)}
            />,
          ]}
        >
          <List.Item.Meta
            title={
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                <Tag>{optimizationTypeLabel(t, s.type)}</Tag>
                <span>{s.title}</span>
                <RiskPill level={s.risk_level} size="sm" />
              </div>
            }
            description={
              <span className="muted" style={{ fontSize: 12 }}>
                {s.datasource_name ?? '—'} · {s.rationale}
              </span>
            }
          />
        </List.Item>
      )}
    />
  );
}
