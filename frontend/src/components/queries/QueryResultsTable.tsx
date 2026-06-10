import { useState } from 'react';
import { Alert, Segmented, Skeleton, Table, Tooltip } from 'antd';
import type { TableColumnsType } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { getQueryResults, queryKeys } from '@/api/queries';
import { documentsToJson } from '@/utils/resultDocuments';

type ResultView = 'table' | 'json';

interface Props {
  queryId: string;
  /** Default result view; MongoDB callers pass 'json'. */
  defaultView?: ResultView;
}

const DEFAULT_SIZE = 50;

export function QueryResultsTable({ queryId, defaultView = 'table' }: Props) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(DEFAULT_SIZE);
  const [view, setView] = useState<ResultView>(defaultView);
  const { data, isLoading, isError, error } = useQuery({
    queryKey: queryKeys.results(queryId, page, size),
    queryFn: () => getQueryResults(queryId, page, size),
  });

  if (isLoading) {
    return <Skeleton active paragraph={{ rows: 6 }} />;
  }
  if (isError) {
    return (
      <Alert
        type="info"
        showIcon
        message={t('queries.detail.results_unavailable')}
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
          <Tooltip title={t('queries.detail.column_masked_tooltip')}>
            <LockOutlined
              aria-label={t('queries.detail.column_masked_tooltip')}
              style={{ marginLeft: 6, color: 'var(--af-color-warning, #d97706)' }}
            />
          </Tooltip>
        ) : null}
      </span>
    ),
    dataIndex: String(idx),
    key: String(idx),
    ellipsis: true,
    render: (value: unknown) => {
      if (col.restricted) {
        return <span className="muted mono">{formatCell(value)}</span>;
      }
      return formatCell(value);
    },
  }));

  const dataSource = data.rows.map((row, rowIdx) => {
    const record: Record<string, unknown> = { __key: rowIdx };
    row.forEach((cell, cellIdx) => {
      record[String(cellIdx)] = cell;
    });
    return record;
  });

  const toggle = (
    <div style={{ marginBottom: 8 }}>
      <Segmented<ResultView>
        size="small"
        value={view}
        onChange={setView}
        aria-label={t('queries.detail.view_label')}
        options={[
          { label: t('queries.detail.view_table'), value: 'table' },
          { label: t('queries.detail.view_json'), value: 'json' },
        ]}
      />
    </div>
  );

  if (view === 'json') {
    return (
      <div>
        {toggle}
        <pre
          className="mono"
          style={{
            margin: 0,
            padding: 12,
            maxHeight: 480,
            overflow: 'auto',
            background: 'var(--bg-code)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--radius)',
            fontSize: 12,
          }}
        >
          {documentsToJson(data.columns, data.rows)}
        </pre>
        {data.truncated ? (
          <div className="muted" style={{ fontSize: 11, marginTop: 6 }}>
            {t('queries.detail.results_truncated', { count: data.row_count })}
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <div>
      {toggle}
      <Table
        rowKey="__key"
        columns={columns}
        dataSource={dataSource}
        size="small"
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total: data.row_count,
          showSizeChanger: true,
          pageSizeOptions: [25, 50, 100, 250],
          onChange: (nextPage, nextSize) => {
            setPage(nextPage - 1);
            setSize(nextSize);
          },
        }}
        footer={() =>
          data.truncated ? (
            <span className="muted" style={{ fontSize: 11 }}>
              {t('queries.detail.results_truncated', { count: data.row_count })}
            </span>
          ) : null
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
