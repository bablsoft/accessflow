import { useMemo, useState } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { App, Alert, Button, DatePicker, Segmented, Skeleton, Space, Table, Tag, Typography } from 'antd';
import { DownloadOutlined, FilePdfOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useTranslation } from 'react-i18next';
import dayjs, { type Dayjs } from 'dayjs';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { queryTypeLabel, dataClassificationLabel } from '@/utils/enumLabels';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { downloadBlob } from '@/utils/downloadBlob';
import { formatDurationCompact } from '@/utils/accessTtl';
import {
  complianceKeys,
  exportComplianceReport,
  fetchComplianceReport,
  type ComplianceReportParams,
} from '@/api/compliance';
import type {
  ClassifiedAccessRow,
  ComplianceReportFormat,
  ComplianceReport,
  ComplianceReportType,
  DeploymentMetricsReportRow,
  RegulatoryAuditTrailRow,
} from '@/types/api';

const { Text } = Typography;

function reportRows(type: ComplianceReportType, report: ComplianceReport | undefined): unknown[] {
  switch (type) {
    case 'CLASSIFIED_ACCESS':
      return report?.classified_access ?? [];
    case 'REGULATORY_AUDIT_TRAIL':
      return report?.audit_trail ?? [];
    case 'DEPLOYMENT_METRICS':
      return report?.deployment_metrics ?? [];
    default: {
      const unreachable: never = type;
      throw new Error(`Unknown compliance report type: ${String(unreachable)}`);
    }
  }
}

function seconds(value: number | null | undefined): string {
  return value == null ? '—' : formatDurationCompact(value * 1000);
}

function percentiles(p50: number | null | undefined, p95: number | null | undefined): string {
  return p50 == null && p95 == null ? '—' : `${seconds(p50)} / ${seconds(p95)}`;
}

function percent(rate: number | null | undefined, language: string): string {
  return rate == null
    ? '—'
    : new Intl.NumberFormat(language, { style: 'percent', maximumFractionDigits: 1 }).format(rate);
}

export default function AuditorDashboardPage() {
  const { t, i18n } = useTranslation();
  const { message } = App.useApp();
  const [type, setType] = useState<ComplianceReportType>('CLASSIFIED_ACCESS');
  const [range, setRange] = useState<[Dayjs, Dayjs]>(() => [dayjs().subtract(90, 'day'), dayjs()]);

  const params: ComplianceReportParams = useMemo(
    () => ({ from: range[0].toISOString(), to: range[1].toISOString() }),
    [range],
  );

  const report = useQuery({
    queryKey: complianceKeys.report(type, params),
    queryFn: () => fetchComplianceReport(type, params),
  });

  const exportMutation = useMutation({
    mutationFn: (format: ComplianceReportFormat) => exportComplianceReport(type, format, params),
    onSuccess: (result) => {
      downloadBlob(result);
      if (result.truncated) {
        message.warning(t('auditor.export_truncated'));
      }
      message.success(
        t('auditor.export_signed', { algorithm: result.signatureAlgorithm ?? 'RSA' }),
      );
    },
    onError: (err) => showApiError(message, err, (e) => apiErrorMessage(e, () => t('auditor.export_failed'))),
  });

  const classifiedColumns: ColumnsType<ClassifiedAccessRow> = [
    {
      title: t('auditor.col_executed_at'),
      dataIndex: 'executed_at',
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    { title: t('auditor.col_datasource'), dataIndex: 'datasource_name' },
    { title: t('auditor.col_submitter'), dataIndex: 'submitter_email' },
    {
      title: t('auditor.col_query_type'),
      dataIndex: 'query_type',
      render: (v: ClassifiedAccessRow['query_type']) => queryTypeLabel(t, v),
    },
    {
      title: t('auditor.col_referenced_tables'),
      dataIndex: 'referenced_tables',
      render: (v: string[]) => v.join(', '),
    },
    {
      title: t('auditor.col_classifications'),
      dataIndex: 'matched',
      render: (matched: ClassifiedAccessRow['matched']) => (
        <Space size={[4, 4]} wrap>
          {matched.map((m, i) => (
            <Tag key={i} color="volcano">
              {m.column_name ? `${m.table_name}.${m.column_name}` : m.table_name}:{' '}
              {dataClassificationLabel(t, m.classification)}
            </Tag>
          ))}
        </Space>
      ),
    },
    { title: t('auditor.col_rows_affected'), dataIndex: 'rows_affected' },
  ];

  const trailColumns: ColumnsType<RegulatoryAuditTrailRow> = [
    {
      title: t('auditor.col_executed_at'),
      dataIndex: 'executed_at',
      render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
    },
    { title: t('auditor.col_datasource'), dataIndex: 'datasource_name' },
    { title: t('auditor.col_submitter'), dataIndex: 'submitter_email' },
    {
      title: t('auditor.col_query_type'),
      dataIndex: 'query_type',
      render: (v: RegulatoryAuditTrailRow['query_type']) => queryTypeLabel(t, v),
    },
    {
      title: t('auditor.col_sql'),
      dataIndex: 'sql_text',
      render: (v: string) => <Text code>{v}</Text>,
    },
    {
      title: t('auditor.col_effective_sql'),
      dataIndex: 'effective_sql',
      render: (v: string | null | undefined) => (v ? <Text code>{v}</Text> : '—'),
    },
    {
      title: t('auditor.col_approvers'),
      dataIndex: 'approvers',
      render: (approvers: RegulatoryAuditTrailRow['approvers']) =>
        approvers.map((a) => a.display_name ?? a.email ?? '').filter(Boolean).join(', '),
    },
  ];

  const deploymentColumns: ColumnsType<DeploymentMetricsReportRow> = [
    { title: t('auditor.col_pipeline'), dataIndex: 'pipeline_name' },
    { title: t('auditor.col_environment'), dataIndex: 'environment_name' },
    { title: t('auditor.col_deployments'), dataIndex: 'deployments' },
    {
      title: t('auditor.col_lead_time'),
      key: 'lead_time',
      render: (_: unknown, r: DeploymentMetricsReportRow) =>
        percentiles(r.lead_time_p50_seconds, r.lead_time_p95_seconds),
    },
    {
      title: t('auditor.col_change_failure_rate'),
      key: 'change_failure_rate',
      render: (_: unknown, r: DeploymentMetricsReportRow) =>
        t('auditor.change_failure_value', {
          rate: percent(r.change_failure_rate, i18n.language),
          failed: r.failed,
          rolledBack: r.rolled_back,
        }),
    },
    {
      title: t('auditor.col_time_to_restore'),
      key: 'time_to_restore',
      render: (_: unknown, r: DeploymentMetricsReportRow) =>
        percentiles(r.time_to_restore_p50_seconds, r.time_to_restore_p95_seconds),
    },
    { title: t('auditor.col_open_incidents'), dataIndex: 'open_incidents' },
    {
      title: t('auditor.col_approval_latency'),
      key: 'approval_latency',
      render: (_: unknown, r: DeploymentMetricsReportRow) =>
        percentiles(r.approval_latency_p50_seconds, r.approval_latency_p95_seconds),
    },
    {
      title: t('auditor.col_timeout_rate'),
      key: 'timeout_rate',
      render: (_: unknown, r: DeploymentMetricsReportRow) => percent(r.timeout_rate, i18n.language),
    },
    { title: t('auditor.col_break_glass'), dataIndex: 'break_glass_count' },
  ];

  const rows = reportRows(type, report.data);
  const isEmpty = !report.isLoading && rows.length === 0;

  const table = () => {
    switch (type) {
      case 'CLASSIFIED_ACCESS':
        return (
          <Table<ClassifiedAccessRow>
            rowKey="query_request_id"
            columns={classifiedColumns}
            dataSource={report.data?.classified_access ?? []}
            pagination={{ pageSize: 20 }}
            size="small"
          />
        );
      case 'REGULATORY_AUDIT_TRAIL':
        return (
          <Table<RegulatoryAuditTrailRow>
            rowKey="query_request_id"
            columns={trailColumns}
            dataSource={report.data?.audit_trail ?? []}
            pagination={{ pageSize: 20 }}
            size="small"
          />
        );
      case 'DEPLOYMENT_METRICS':
        return (
          <Table<DeploymentMetricsReportRow>
            rowKey={(r) => `${r.pipeline_id}:${r.environment_id}`}
            columns={deploymentColumns}
            dataSource={report.data?.deployment_metrics ?? []}
            pagination={{ pageSize: 20 }}
            scroll={{ x: 'max-content' }}
            size="small"
          />
        );
      default: {
        const unreachable: never = type;
        throw new Error(`Unknown compliance report type: ${String(unreachable)}`);
      }
    }
  };

  return (
    <div>
      <PageHeader
        docsAnchor="compliance-reports"
        title={t('auditor.title')}
        subtitle={t('auditor.subtitle')}
        actions={
          <Space>
            <Button
              icon={<FilePdfOutlined />}
              loading={exportMutation.isPending}
              onClick={() => exportMutation.mutate('PDF')}
            >
              {t('auditor.export_pdf')}
            </Button>
            <Button
              icon={<DownloadOutlined />}
              loading={exportMutation.isPending}
              onClick={() => exportMutation.mutate('CSV')}
            >
              {t('auditor.export_csv')}
            </Button>
          </Space>
        }
      />

      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <Space wrap>
          <Segmented<ComplianceReportType>
            value={type}
            onChange={setType}
            options={[
              { label: t('auditor.tab_classified_access'), value: 'CLASSIFIED_ACCESS' },
              { label: t('auditor.tab_regulatory_trail'), value: 'REGULATORY_AUDIT_TRAIL' },
              { label: t('auditor.tab_deployment_metrics'), value: 'DEPLOYMENT_METRICS' },
            ]}
          />
          <DatePicker.RangePicker
            value={range}
            allowClear={false}
            aria-label={t('auditor.period')}
            onChange={(v) => {
              if (v && v[0] && v[1]) setRange([v[0], v[1]]);
            }}
          />
        </Space>

        {report.data?.truncated && (
          <Alert type="warning" showIcon title={t('auditor.report_truncated')} />
        )}

        {report.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : isEmpty ? (
          <EmptyState
            title={t('auditor.empty_title')}
            description={t(
              type === 'DEPLOYMENT_METRICS'
                ? 'auditor.empty_description_deployments'
                : 'auditor.empty_description',
            )}
          />
        ) : (
          table()
        )}
      </Space>
    </div>
  );
}
