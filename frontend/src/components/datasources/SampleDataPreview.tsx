import { Alert, Skeleton, Table, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';
import { useTableSample } from '@/hooks/useTableSample';

interface SampleDataPreviewProps {
  datasourceId: string;
  schema?: string;
  table: string;
}

/**
 * Read-only preview of a bounded, RLS- and masking-aware sample of a table's rows (AF-443). Masked
 * columns are badged and only ever render the masked value the backend returned — never the raw one.
 */
export function SampleDataPreview({ datasourceId, schema, table }: SampleDataPreviewProps) {
  const { t } = useTranslation();
  const { data, isLoading, isError, error } = useTableSample(datasourceId, table, schema);

  if (isLoading) {
    return <Skeleton active paragraph={{ rows: 6 }} />;
  }
  if (isError) {
    return (
      <Alert
        type="info"
        showIcon
        message={t('datasources.settings.sample_error')}
        description={(error as Error).message}
      />
    );
  }
  if (!data) return null;

  const columns: TableColumnsType<Record<string, unknown>> = data.columns.map((col, idx) => ({
    title: (
      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>
        {col.name} <span className="muted">{col.type}</span>
        {col.restricted ? (
          <Tooltip title={t('datasources.settings.sample_masked_tooltip')}>
            <LockOutlined
              aria-label={t('datasources.settings.sample_masked_tooltip')}
              style={{ marginLeft: 6, color: 'var(--af-color-warning, #d97706)' }}
            />
          </Tooltip>
        ) : null}
      </span>
    ),
    dataIndex: String(idx),
    key: String(idx),
    ellipsis: true,
    render: (value: unknown) =>
      col.restricted ? <span className="muted mono">{formatCell(value)}</span> : formatCell(value),
  }));

  const dataSource = data.rows.map((row, rowIdx) => {
    const record: Record<string, unknown> = { __key: rowIdx };
    row.forEach((cell, cellIdx) => {
      record[String(cellIdx)] = cell;
    });
    return record;
  });

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={t('datasources.settings.sample_governed_note')}
      />
      <Table
        rowKey="__key"
        columns={columns}
        dataSource={dataSource}
        size="small"
        scroll={{ x: 'max-content' }}
        pagination={false}
        locale={{ emptyText: t('datasources.settings.sample_empty') }}
        footer={() =>
          data.truncated ? (
            <span className="muted" style={{ fontSize: 11 }}>
              {t('datasources.settings.sample_truncated', { count: data.row_count })}
            </span>
          ) : (
            <span className="muted" style={{ fontSize: 11 }}>
              {t('datasources.settings.sample_row_count', { count: data.row_count })}
            </span>
          )
        }
      />
    </div>
  );
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return 'NULL';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
