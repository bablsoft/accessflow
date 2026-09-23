import { useState } from 'react';
import { Drawer, Select, Skeleton, Table, Typography } from 'antd';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '@/components/common/EmptyState';
import { jobKeys, listJobExecutions } from '@/api/jobs';
import { apiErrorMessage } from '@/utils/apiErrors';
import { JOB_EXECUTION_STATUSES, jobExecutionStatusLabel } from '@/utils/enumLabels';
import { fmtDate } from '@/utils/dateFormat';
import type { JobExecution, JobExecutionStatus } from '@/types/api';
import { JobStatusPill } from './JobStatusPill';
import { formatDurationMs } from './formatDuration';

const PAGE_SIZE = 20;

function ExecutionsBody({ jobName }: { jobName: string }) {
  const { t } = useTranslation();
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState<JobExecutionStatus | undefined>(undefined);

  const params = { status, page, size: PAGE_SIZE };
  const executionsQuery = useQuery({
    queryKey: jobKeys.executions(jobName, params),
    queryFn: () => listJobExecutions(jobName, params),
    // Keep the table mounted while the next page or filter loads instead of flashing a skeleton.
    placeholderData: keepPreviousData,
  });

  const filter = (
    <Select<JobExecutionStatus>
      allowClear
      aria-label={t('admin.jobs.filter_status')}
      placeholder={t('admin.jobs.filter_status')}
      style={{ width: 200 }}
      value={status}
      onChange={(value) => {
        setStatus(value);
        setPage(0);
      }}
      options={JOB_EXECUTION_STATUSES.map((value) => ({
        value,
        label: jobExecutionStatusLabel(t, value),
      }))}
    />
  );

  let body;
  if (executionsQuery.isLoading) {
    body = <Skeleton active paragraph={{ rows: 6 }} />;
  } else if (executionsQuery.isError) {
    body = (
      <EmptyState
        title={t('admin.jobs.history_load_error')}
        description={apiErrorMessage(executionsQuery.error, () => t('admin.jobs.history_load_error'))}
        size="sm"
      />
    );
  } else if ((executionsQuery.data?.content ?? []).length === 0) {
    body = <EmptyState title={t('admin.jobs.history_empty')} size="sm" />;
  } else {
    body = (
      <Table<JobExecution>
        rowKey="id"
        size="small"
        dataSource={executionsQuery.data?.content ?? []}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: executionsQuery.data?.total_elements ?? 0,
          showSizeChanger: false,
          onChange: (p) => setPage(p - 1),
        }}
        expandable={{
          rowExpandable: (row) => row.error_message != null || row.error_class != null,
          expandedRowRender: (row) => (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              {row.error_class != null && (
                <span className="mono muted" style={{ fontSize: 12 }}>{row.error_class}</span>
              )}
              {row.error_message != null && (
                <Typography.Paragraph className="mono" style={{ fontSize: 12, whiteSpace: 'pre-wrap', margin: 0 }}>
                  {row.error_message}
                </Typography.Paragraph>
              )}
            </div>
          ),
        }}
        columns={[
          {
            title: t('admin.jobs.col_status'),
            dataIndex: 'status',
            render: (_v, row) => (
              <JobStatusPill status={row.status} abandoned={row.abandoned} size="sm" />
            ),
          },
          {
            title: t('admin.jobs.col_started'),
            dataIndex: 'started_at',
            render: (v: string) => <span style={{ fontSize: 12 }}>{fmtDate(v)}</span>,
          },
          {
            title: t('admin.jobs.col_duration'),
            dataIndex: 'duration_ms',
            render: (v: number | null | undefined) => (
              <span className="mono" style={{ fontSize: 12 }}>{formatDurationMs(t, v)}</span>
            ),
          },
          {
            title: t('admin.jobs.col_instance'),
            dataIndex: 'instance_id',
            render: (v: string | null | undefined) => (
              <span className="mono muted" style={{ fontSize: 12 }}>{v ?? '—'}</span>
            ),
          },
        ]}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {filter}
      {body}
    </div>
  );
}

export function JobExecutionsDrawer({
  jobName,
  onClose,
}: {
  jobName: string | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Drawer
      open={jobName !== null}
      onClose={onClose}
      size={640}
      destroyOnHidden
      title={jobName === null ? t('admin.jobs.history') : t('admin.jobs.history_title', { name: jobName })}
    >
      {/* Keyed so switching jobs resets the page and filter without a useEffect. */}
      {jobName !== null && <ExecutionsBody key={jobName} jobName={jobName} />}
    </Drawer>
  );
}
