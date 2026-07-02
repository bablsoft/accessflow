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
import {
  complianceKeys,
  exportComplianceReport,
  fetchComplianceReport,
  type ComplianceExportResult,
  type ComplianceReportParams,
} from '@/api/compliance';
import type {
  ClassifiedAccessRow,
  ComplianceReportFormat,
  ComplianceReportType,
  RegulatoryAuditTrailRow,
} from '@/types/api';

const { Text } = Typography;

function triggerDownload({ blob, filename }: ComplianceExportResult): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export default function AuditorDashboardPage() {
  const { t } = useTranslation();
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
      triggerDownload(result);
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
      title: t('auditor.col_approvers'),
      dataIndex: 'approvers',
      render: (approvers: RegulatoryAuditTrailRow['approvers']) =>
        approvers.map((a) => a.display_name ?? a.email ?? '').filter(Boolean).join(', '),
    },
  ];

  const rows =
    type === 'CLASSIFIED_ACCESS'
      ? report.data?.classified_access ?? []
      : report.data?.audit_trail ?? [];
  const isEmpty = !report.isLoading && rows.length === 0;

  return (
    <div>
      <PageHeader
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
          <Alert type="warning" showIcon message={t('auditor.report_truncated')} />
        )}

        {report.isLoading ? (
          <Skeleton active paragraph={{ rows: 6 }} />
        ) : isEmpty ? (
          <EmptyState title={t('auditor.empty_title')} description={t('auditor.empty_description')} />
        ) : type === 'CLASSIFIED_ACCESS' ? (
          <Table<ClassifiedAccessRow>
            rowKey="query_request_id"
            columns={classifiedColumns}
            dataSource={report.data?.classified_access ?? []}
            pagination={{ pageSize: 20 }}
            size="small"
          />
        ) : (
          <Table<RegulatoryAuditTrailRow>
            rowKey="query_request_id"
            columns={trailColumns}
            dataSource={report.data?.audit_trail ?? []}
            pagination={{ pageSize: 20 }}
            size="small"
          />
        )}
      </Space>
    </div>
  );
}
