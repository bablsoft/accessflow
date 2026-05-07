import { useState } from 'react';
import { Alert, Skeleton, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { getQueryResults, queryKeys } from '@/api/queries';

interface Props {
  queryId: string;
}

const DEFAULT_SIZE = 50;

export function QueryResultsTable({ queryId }: Props) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(DEFAULT_SIZE);
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
      </span>
    ),
    dataIndex: String(idx),
    key: String(idx),
    ellipsis: true,
    render: (value: unknown) => formatCell(value),
  }));

  const dataSource = data.rows.map((row, rowIdx) => {
    const record: Record<string, unknown> = { __key: rowIdx };
    row.forEach((cell, cellIdx) => {
      record[String(cellIdx)] = cell;
    });
    return record;
  });

  return (
    <Table
      rowKey="__key"
      columns={columns}
      dataSource={dataSource}
      size="small"
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
  );
}

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return 'NULL';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
