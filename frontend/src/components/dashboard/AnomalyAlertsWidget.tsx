import { App, Button, List, Skeleton } from 'antd';
import { CheckOutlined, CloseOutlined, SafetyOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import {
  acknowledgeMyAnomaly,
  anomalyKeys,
  dismissMyAnomaly,
  listMyAnomalies,
} from '@/api/anomalies';
import { dashboardKeys } from '@/api/dashboard';
import { fmtNum, timeAgo } from '@/utils/dateFormat';
import { dashboardErrorMessage } from '@/utils/apiErrors';

const MINE_FILTERS = { status: 'OPEN' as const, page: 0, size: 10 };

/** The current user's own behavioural anomalies, with acknowledge / dismiss (AF-498). */
export function AnomalyAlertsWidget() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const anomaliesQuery = useQuery({
    queryKey: anomalyKeys.mine(MINE_FILTERS),
    queryFn: () => listMyAnomalies(MINE_FILTERS),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: anomalyKeys.mine(MINE_FILTERS) });
    void queryClient.invalidateQueries({ queryKey: dashboardKeys.summary() });
  };

  const acknowledge = useMutation({
    mutationFn: (id: string) => acknowledgeMyAnomaly(id),
    onSuccess: invalidate,
    onError: (err) => message.error(dashboardErrorMessage(err)),
  });
  const dismiss = useMutation({
    mutationFn: (id: string) => dismissMyAnomaly(id),
    onSuccess: invalidate,
    onError: (err) => message.error(dashboardErrorMessage(err)),
  });

  if (anomaliesQuery.isLoading || !anomaliesQuery.data) {
    return <Skeleton active paragraph={{ rows: 3 }} />;
  }
  const items = anomaliesQuery.data.content;
  if (items.length === 0) {
    return (
      <EmptyState
        icon={<SafetyOutlined style={{ fontSize: 20 }} />}
        title={t('dashboard.anomalies.empty')}
      />
    );
  }
  return (
    <List
      size="small"
      dataSource={items}
      rowKey={(a) => a.id}
      renderItem={(a) => (
        <List.Item
          actions={[
            <Button
              key="ack"
              size="small"
              icon={<CheckOutlined />}
              loading={acknowledge.isPending && acknowledge.variables === a.id}
              onClick={() => acknowledge.mutate(a.id)}
            >
              {t('dashboard.anomalies.acknowledge')}
            </Button>,
            <Button
              key="dismiss"
              size="small"
              type="text"
              icon={<CloseOutlined />}
              aria-label={t('dashboard.anomalies.dismiss')}
              loading={dismiss.isPending && dismiss.variables === a.id}
              onClick={() => dismiss.mutate(a.id)}
            />,
          ]}
        >
          <List.Item.Meta
            title={<span>{a.ai_summary ?? a.feature}</span>}
            description={
              <span className="muted" style={{ fontSize: 12 }}>
                {a.datasource_name ?? '—'} · {t('dashboard.anomalies.score')}: {fmtNum(a.score)} ·{' '}
                {timeAgo(a.detected_at)}
              </span>
            }
          />
        </List.Item>
      )}
    />
  );
}
