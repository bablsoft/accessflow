import { useState } from 'react';
import { Button, Drawer, Table, Tag, Tooltip, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { apiConnectorKeys, getApiConnectorHealth } from '@/api/apiConnectors';
import { Pill } from '@/components/common/Pill';
import { apiConnectorHealthStatusLabel } from '@/utils/enumLabels';
import { apiConnectorHealthColor } from '@/utils/statusColors';
import { fmtDate } from '@/utils/dateFormat';
import type { ApiConnectorHealthCheck } from '@/types/api';

/**
 * Scheduled-health badge (#896). An unmonitored connector reads "Not monitored" — never a red
 * state — because health checks are opt-in.
 */
export function ApiConnectorHealthBadge({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const healthQuery = useQuery({
    queryKey: apiConnectorKeys.health(connectorId),
    queryFn: () => getApiConnectorHealth(connectorId),
    enabled: !!connectorId,
  });
  const health = healthQuery.data;
  if (!health) return null;

  const monitored = health.health_check_interval_minutes != null;
  const colors = apiConnectorHealthColor(monitored ? health.status : 'UNKNOWN');
  const label = monitored
    ? apiConnectorHealthStatusLabel(t, health.status)
    : t('apiGov.health.notMonitored');
  const lastCheck = health.recent_checks[0];
  const tooltip = !monitored
    ? t('apiGov.health.notMonitoredHint')
    : lastCheck
      ? (lastCheck.message ?? apiConnectorHealthStatusLabel(t, health.status))
      : t('apiGov.health.noChecks');

  return (
    <>
      <Tooltip title={tooltip}>
        <Button
          type="text"
          size="small"
          onClick={() => setOpen(true)}
        >
          <Pill fg={colors.fg} bg={colors.bg} border={colors.border} withDot size="sm">
            {label}
          </Pill>
          {monitored && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {health.last_probe_at
                ? t('apiGov.health.lastProbed', { time: fmtDate(health.last_probe_at) })
                : t('apiGov.health.neverProbed')}
            </Typography.Text>
          )}
        </Button>
      </Tooltip>
      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        title={t('apiGov.health.historyTitle')}
        size="large"
        destroyOnHidden
      >
        <Typography.Paragraph type="secondary">
          {monitored
            ? t('apiGov.health.intervalSummary', {
                minutes: health.health_check_interval_minutes,
                failures: health.consecutive_failures,
              })
            : t('apiGov.health.notMonitoredHint')}
        </Typography.Paragraph>
        <Table<ApiConnectorHealthCheck>
          rowKey="checked_at"
          size="small"
          pagination={false}
          dataSource={health.recent_checks}
          locale={{ emptyText: t('apiGov.health.noChecks') }}
          columns={[
            {
              title: t('apiGov.health.checkedAt'),
              dataIndex: 'checked_at',
              render: (v: string) => fmtDate(v),
            },
            {
              title: t('apiGov.health.result'),
              dataIndex: 'success',
              render: (success: boolean) =>
                success ? (
                  <Tag color="success">{t('apiGov.health.success')}</Tag>
                ) : (
                  <Tag color="error">{t('apiGov.health.failure')}</Tag>
                ),
            },
            {
              title: t('apiGov.health.durationMs'),
              dataIndex: 'duration_ms',
            },
            {
              title: t('apiGov.health.message'),
              dataIndex: 'message',
              render: (v: string | null | undefined) => v ?? '—',
            },
          ]}
        />
      </Drawer>
    </>
  );
}
