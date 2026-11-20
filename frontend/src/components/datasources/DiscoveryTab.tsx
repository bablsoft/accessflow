import { useMemo, useState } from 'react';
import { Alert, App, Button, Card, Form, InputNumber, Segmented, Switch, Tag, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { CheckOutlined, CloseOutlined, RadarChartOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { DataTable, StatusTag, type StatusTone } from '@/components/layout';
import { EmptyState } from '@/components/common/EmptyState';
import {
  bulkDecideFindings,
  discoveryKeys,
  fetchDiscoveryConfig,
  fetchDiscoveryFindings,
  triggerDiscoveryScan,
  updateDiscoveryConfig,
} from '@/api/discovery';
import { dataClassificationKeys } from '@/api/dataClassifications';
import { maskingPolicyKeys } from '@/api/maskingPolicies';
import {
  DISCOVERY_FINDING_STATUSES,
  dataClassificationLabel,
  discoveryDetectorLabel,
  discoveryFindingStatusLabel,
  enumOptions,
} from '@/utils/enumLabels';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import type {
  DataClassification,
  DiscoveryDecision,
  DiscoveryDetector,
  DiscoveryFinding,
  DiscoveryFindingStatus,
  DiscoveryRowStatus,
  UpdateDiscoveryConfigInput,
} from '@/types/api';

interface ConfigFormValues {
  enabled: boolean;
  sample_size: number;
  scan_interval_hours: number;
  ai_classification_enabled: boolean;
}

const PAGE_SIZE = 20;

// Exhaustive by type, so adding a DiscoveryFindingStatus is a compile error here rather than a
// silently grey tag.
const STATUS_COLORS: Record<DiscoveryFindingStatus, StatusTone> = {
  PENDING: 'info',
  CONFIRMED: 'success',
  DISMISSED: 'neutral',
  STALE: 'warning',
};

export function DiscoveryTab({ dsId }: { dsId: string }) {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ConfigFormValues>();

  const [statusFilter, setStatusFilter] = useState<DiscoveryFindingStatus | 'ALL'>('PENDING');
  const [page, setPage] = useState(0);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [rowStatuses, setRowStatuses] = useState<Record<string, DiscoveryRowStatus>>({});

  const configQuery = useQuery({
    queryKey: discoveryKeys.config(dsId),
    queryFn: () => fetchDiscoveryConfig(dsId),
  });

  const findingFilters = {
    status: statusFilter === 'ALL' ? undefined : statusFilter,
    page,
    size: PAGE_SIZE,
  };
  const findingsQuery = useQuery({
    queryKey: discoveryKeys.findings(dsId, findingFilters),
    queryFn: () => fetchDiscoveryFindings(dsId, findingFilters),
  });
  const findings = findingsQuery.data?.content ?? [];

  const invalidateFindings = () => {
    void queryClient.invalidateQueries({ queryKey: discoveryKeys.all });
  };

  const saveConfig = useMutation({
    mutationFn: (input: UpdateDiscoveryConfigInput) => updateDiscoveryConfig(dsId, input),
    onSuccess: (config) => {
      queryClient.setQueryData(discoveryKeys.config(dsId), config);
      message.success(t('datasources.settings.discovery.config_save_success'));
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('datasources.settings.discovery.config_save_error')),
      );
    },
  });

  const scanNow = useMutation({
    mutationFn: () => triggerDiscoveryScan(dsId),
    onSuccess: () => {
      message.success(t('datasources.settings.discovery.scan_started'));
      // The scan runs asynchronously; refresh the config (last_scan_at) and findings shortly.
      window.setTimeout(() => {
        void queryClient.invalidateQueries({ queryKey: discoveryKeys.all });
      }, 5000);
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('datasources.settings.discovery.scan_error')),
      );
    },
  });

  const bulk = useMutation({
    mutationFn: (decision: DiscoveryDecision) =>
      bulkDecideFindings(dsId, { finding_ids: selectedRowKeys, decision }),
    onSuccess: (response, decision) => {
      const failures: Record<string, DiscoveryRowStatus> = {};
      let success = 0;
      for (const row of response.results) {
        if (row.status === 'SUCCESS' || row.status === 'TAG_CONFLICT') {
          success += 1;
        } else {
          failures[row.finding_id] = row.status;
        }
      }
      const failure = Object.keys(failures).length;
      // Keep failed rows selected so the user can retry; drop the successes.
      setSelectedRowKeys((prev) => prev.filter((id) => failures[id] !== undefined));
      setRowStatuses((prev) => ({ ...prev, ...failures }));
      invalidateFindings();
      if (decision === 'CONFIRM') {
        // Confirming applies tags and derives masking — refresh the sibling tabs.
        void queryClient.invalidateQueries({ queryKey: dataClassificationKeys.list(dsId) });
        void queryClient.invalidateQueries({ queryKey: dataClassificationKeys.derivation(dsId) });
        void queryClient.invalidateQueries({ queryKey: maskingPolicyKeys.list(dsId) });
      }
      if (failure === 0) {
        message.success(
          t('datasources.settings.discovery.bulk_toast_summary', { count: success }),
        );
      } else {
        message.warning(
          t('datasources.settings.discovery.bulk_toast_partial', { success, failure }),
        );
      }
    },
    onError: (err) => {
      showApiError(message, err, (e) =>
        apiErrorMessage(e, () => t('datasources.settings.discovery.bulk_error')),
      );
    },
  });

  const rowStatusTag = (status: DiscoveryRowStatus) => {
    const labelKey =
      status === 'INVALID_STATE'
        ? 'datasources.settings.discovery.row_status_invalid_state'
        : status === 'NOT_FOUND'
          ? 'datasources.settings.discovery.row_status_not_found'
          : 'datasources.settings.discovery.row_status_error';
    return <StatusTag tone={status === 'INVALID_STATE' ? 'warning' : 'danger'}>{t(labelKey)}</StatusTag>;
  };

  const columns: TableColumnsType<DiscoveryFinding> = useMemo(
    () => [
      {
        title: t('datasources.settings.discovery.col_column'),
        key: 'column',
        render: (_: unknown, f: DiscoveryFinding) => (
          <span className="mono u-text-xs">
            {(f.schema_name ? `${f.schema_name}.` : '') + `${f.table_name}.${f.column_name}`}
          </span>
        ),
      },
      {
        title: t('datasources.settings.discovery.col_classification'),
        dataIndex: 'classification',
        width: 130,
        render: (v: DataClassification) => (
          <StatusTag tone="danger">{dataClassificationLabel(t, v)}</StatusTag>
        ),
      },
      {
        title: t('datasources.settings.discovery.col_detector'),
        dataIndex: 'detector',
        width: 130,
        render: (v: DiscoveryDetector, f: DiscoveryFinding) =>
          f.rationale ? (
            <Tooltip title={f.rationale}>
              <Tag>{discoveryDetectorLabel(t, v)}</Tag>
            </Tooltip>
          ) : (
            <Tag>{discoveryDetectorLabel(t, v)}</Tag>
          ),
      },
      {
        title: t('datasources.settings.discovery.col_confidence'),
        dataIndex: 'confidence',
        width: 120,
        render: (v: number, f: DiscoveryFinding) => (
          <span className="u-text-xs">
            {t('datasources.settings.discovery.confidence_value', { confidence: v })}
            {f.match_count > 0 && (
              <span className="muted">
                {' '}
                ({f.match_count}/{f.sample_count})
              </span>
            )}
          </span>
        ),
      },
      {
        title: t('datasources.settings.discovery.col_sample'),
        dataIndex: 'sample_redacted',
        render: (v: string | null) =>
          v ? (
            <span className="mono muted u-text-xs">
              {v}
            </span>
          ) : (
            <span className="muted">—</span>
          ),
      },
      {
        title: t('datasources.settings.discovery.col_detected'),
        dataIndex: 'last_detected_at',
        width: 140,
        render: (v: string) => (
          <Tooltip title={fmtDate(v)}>
            <span className="muted u-text-xs">
              {timeAgo(v)}
            </span>
          </Tooltip>
        ),
      },
      {
        title: t('datasources.settings.discovery.col_status'),
        dataIndex: 'status',
        width: 130,
        render: (v: DiscoveryFindingStatus, f: DiscoveryFinding) => {
          const s = rowStatuses[f.id];
          if (s) return rowStatusTag(s);
          const tag = <StatusTag tone={STATUS_COLORS[v]}>{discoveryFindingStatusLabel(t, v)}</StatusTag>;
          if (v !== 'STALE') return tag;
          return (
            <Tooltip title={t('datasources.settings.discovery.stale_hint')}>{tag}</Tooltip>
          );
        },
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [t, rowStatuses],
  );

  const config = configQuery.data;
  // STALE is an aged PENDING (AF-659), so it stays selectable — bulk-dismissing aged rows is the
  // whole point of retiring them.
  const isDecidable = (status: DiscoveryFindingStatus) =>
    status === 'PENDING' || status === 'STALE';
  const decidableFilter =
    statusFilter === 'PENDING' || statusFilter === 'STALE' || statusFilter === 'ALL';

  return (
    <div className="u-p-gutter u-flex u-col u-gap-5">
      <Card size="small" title={t('datasources.settings.discovery.settings_title')}>
        <div className="muted u-text-xs u-mb-4 u-max-w-form">
          {t('datasources.settings.discovery.settings_description')}
        </div>
        {config && (
          <Form<ConfigFormValues>
            form={form}
            layout="inline"
            initialValues={{
              enabled: config.enabled,
              sample_size: config.sample_size,
              scan_interval_hours: config.scan_interval_hours,
              ai_classification_enabled: config.ai_classification_enabled,
            }}
            onFinish={(values) => saveConfig.mutate(values)}
            style={{ rowGap: 12 }}
          >
            <Form.Item
              name="enabled"
              label={t('datasources.settings.discovery.label_enabled')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="sample_size"
              label={t('datasources.settings.discovery.label_sample_size')}
              rules={[
                {
                  type: 'number',
                  min: 10,
                  max: 1000,
                  message: t('datasources.settings.discovery.sample_size_range'),
                },
              ]}
            >
              <InputNumber min={10} max={1000} step={10} />
            </Form.Item>
            <Form.Item
              name="scan_interval_hours"
              label={t('datasources.settings.discovery.label_scan_interval')}
              rules={[
                {
                  type: 'number',
                  min: 1,
                  max: 720,
                  message: t('datasources.settings.discovery.scan_interval_range'),
                },
              ]}
            >
              <InputNumber min={1} max={720} />
            </Form.Item>
            <Form.Item
              name="ai_classification_enabled"
              label={t('datasources.settings.discovery.label_ai')}
              valuePropName="checked"
              tooltip={t('datasources.settings.discovery.ai_hint')}
            >
              <Switch />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" loading={saveConfig.isPending}>
                {t('common.save')}
              </Button>
            </Form.Item>
            <Form.Item>
              <Button
                icon={<RadarChartOutlined />}
                loading={scanNow.isPending}
                onClick={() => scanNow.mutate()}
              >
                {t('datasources.settings.discovery.scan_now')}
              </Button>
            </Form.Item>
          </Form>
        )}
        {config?.last_scan_at && (
          <div className="muted u-text-xs u-mt-3">
            {t('datasources.settings.discovery.last_scan', {
              time: fmtDate(config.last_scan_at),
            })}
          </div>
        )}
        {config?.last_scan_error && (
          <Alert
            className="u-mt-2"
            type="warning"
            showIcon
            title={t('datasources.settings.discovery.last_scan_error', {
              error: config.last_scan_error,
            })}
          />
        )}
      </Card>

      <div>
        <div
          className="u-flex u-justify-between u-items-center u-mb-3 u-gap-4"
        >
          <div>
            <div className="u-fw-600">
              {t('datasources.settings.discovery.findings_title')}
            </div>
            <div className="muted u-text-xs u-max-w-text">
              {t('datasources.settings.discovery.findings_description')}
            </div>
          </div>
          <Segmented<DiscoveryFindingStatus | 'ALL'>
            value={statusFilter}
            onChange={(value) => {
              setStatusFilter(value);
              setPage(0);
              setSelectedRowKeys([]);
            }}
            options={[
              ...enumOptions(DISCOVERY_FINDING_STATUSES, discoveryFindingStatusLabel, t),
              { value: 'ALL', label: t('datasources.settings.discovery.filter_all') },
            ]}
          />
        </div>

        {selectedRowKeys.length > 0 && (
          <div
            role="toolbar"
            aria-label={t('datasources.settings.discovery.bulk_action_bar_count', {
              count: selectedRowKeys.length,
            })}
            className="af-box u-mb-3 u-flex u-items-center u-gap-3 u-py-2 u-px-4" style={{ position: 'sticky', top: 0, zIndex: 10 }}
          >
            <strong>
              {t('datasources.settings.discovery.bulk_action_bar_count', {
                count: selectedRowKeys.length,
              })}
            </strong>
            <div className="u-flex-1" />
            <Button
              size="small"
              danger
              icon={<CloseOutlined />}
              loading={bulk.isPending}
              onClick={() => bulk.mutate('DISMISS')}
            >
              {t('datasources.settings.discovery.dismiss_selected')}
            </Button>
            <Button
              size="small"
              type="primary"
              icon={<CheckOutlined />}
              loading={bulk.isPending}
              onClick={() => bulk.mutate('CONFIRM')}
            >
              {t('datasources.settings.discovery.confirm_selected')}
            </Button>
            <Button size="small" type="link" onClick={() => setSelectedRowKeys([])}>
              {t('datasources.settings.discovery.clear_selection')}
            </Button>
          </div>
        )}

        {!findingsQuery.isLoading && findings.length === 0 ? (
          <EmptyState
            title={t('datasources.settings.discovery.empty_title')}
            description={t('datasources.settings.discovery.empty_description')}
          />
        ) : (
          <DataTable<DiscoveryFinding>
            rowKey="id"
            size="middle"
            loading={findingsQuery.isLoading}
            dataSource={findings}
            columns={columns}
            scroll={{ x: 'max-content' }}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: findingsQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (nextPage) => setPage(nextPage - 1),
            }}
            rowSelection={
              decidableFilter
                ? {
                    selectedRowKeys,
                    onChange: (keys) => setSelectedRowKeys(keys as string[]),
                    getCheckboxProps: (f) => ({ disabled: !isDecidable(f.status) }),
                  }
                : undefined
            }
          />
        )}
      </div>
    </div>
  );
}
