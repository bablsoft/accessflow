import { useState } from 'react';
import { Alert, Button, Skeleton, Table, Tag, Tooltip } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { JobStatusPill } from '@/components/jobs/JobStatusPill';
import { JobExecutionsDrawer } from '@/components/jobs/JobExecutionsDrawer';
import { formatDurationMs, formatIsoDuration } from '@/components/jobs/formatDuration';
import { getJobRegistry, jobKeys } from '@/api/jobs';
import { apiErrorMessage } from '@/utils/apiErrors';
import { jobCadenceTypeLabel } from '@/utils/enumLabels';
import { timeAgo, fmtDate } from '@/utils/dateFormat';
import type { ScheduledJob } from '@/types/api';

export function JobsPage() {
  const { t } = useTranslation();
  const [selectedJob, setSelectedJob] = useState<string | null>(null);

  const registryQuery = useQuery({
    queryKey: jobKeys.registry(),
    queryFn: getJobRegistry,
  });

  const registry = registryQuery.data;
  const jobs = registry?.jobs ?? [];

  let content;
  if (registryQuery.isLoading) {
    content = <Skeleton active paragraph={{ rows: 8 }} style={{ padding: 24 }} />;
  } else if (registryQuery.isError) {
    content = (
      <EmptyState
        title={t('admin.jobs.load_error')}
        description={apiErrorMessage(registryQuery.error, () => t('admin.jobs.load_error'))}
      />
    );
  } else if (registry != null && !registry.scheduling_enabled && jobs.length === 0) {
    content = (
      <EmptyState
        title={t('admin.jobs.scheduler_disabled_title')}
        description={t('admin.jobs.scheduler_disabled_body')}
      />
    );
  } else if (jobs.length === 0) {
    content = <EmptyState title={t('admin.jobs.empty')} />;
  } else {
    content = (
      <Table<ScheduledJob>
        rowKey="job_name"
        size="middle"
        dataSource={jobs}
        scroll={{ x: 'max-content' }}
        pagination={false}
        onRow={(job) => ({
          onClick: () => setSelectedJob(job.job_name),
          style: { cursor: 'pointer' },
        })}
        columns={[
          {
            title: t('admin.jobs.col_job'),
            dataIndex: 'job_name',
            render: (name: string, job) => (
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                <Button
                  type="link"
                  style={{ padding: 0, height: 'auto', fontWeight: 500, alignSelf: 'flex-start' }}
                  onClick={(e) => {
                    e.stopPropagation();
                    setSelectedJob(name);
                  }}
                >
                  {name}
                </Button>
                {job.lock_name != null && (
                  <span className="mono muted" style={{ fontSize: 11 }}>{job.lock_name}</span>
                )}
              </div>
            ),
          },
          {
            title: t('admin.jobs.col_module'),
            dataIndex: 'module',
            render: (module: string | null | undefined, job) =>
              job.registered ? (
                <span className="mono">{module ?? '—'}</span>
              ) : (
                <Tooltip title={t('admin.jobs.not_registered_help')}>
                  <Tag>{t('admin.jobs.not_registered')}</Tag>
                </Tooltip>
              ),
          },
          {
            title: t('admin.jobs.col_cadence'),
            dataIndex: 'cadence',
            render: (cadence: string | null | undefined, job) =>
              cadence == null ? (
                '—'
              ) : (
                <Tooltip
                  title={job.cadence_type != null ? `${jobCadenceTypeLabel(t, job.cadence_type)} · ${cadence}` : cadence}
                >
                  <span>{job.cadence_type === 'CRON' ? cadence : formatIsoDuration(t, cadence)}</span>
                </Tooltip>
              ),
          },
          {
            title: t('admin.jobs.col_last_status'),
            key: 'last_status',
            render: (_v, job) =>
              job.health.last_status == null ? (
                <span className="muted">{t('admin.jobs.never_run')}</span>
              ) : (
                <JobStatusPill status={job.health.last_status} abandoned={job.health.last_abandoned} size="sm" />
              ),
          },
          {
            title: t('admin.jobs.col_last_run'),
            key: 'last_run',
            render: (_v, job) =>
              job.health.last_started_at == null ? (
                '—'
              ) : (
                <Tooltip title={fmtDate(job.health.last_started_at)}>
                  <span>{timeAgo(job.health.last_started_at)}</span>
                </Tooltip>
              ),
          },
          {
            title: t('admin.jobs.col_duration'),
            key: 'duration',
            render: (_v, job) => (
              <span className="mono">{formatDurationMs(t, job.health.last_duration_ms)}</span>
            ),
          },
          {
            title: t('admin.jobs.col_consecutive_failures'),
            key: 'consecutive_failures',
            render: (_v, job) =>
              job.health.consecutive_failures > 0 ? (
                <Tooltip title={job.health.last_error_message ?? undefined}>
                  <Tag color="error">
                    {t('admin.jobs.consecutive_failures', { count: job.health.consecutive_failures })}
                  </Tag>
                </Tooltip>
              ) : (
                <span className="muted">0</span>
              ),
          },
          {
            title: t('admin.jobs.col_window', { window: formatIsoDuration(t, registry?.summary_window) }),
            key: 'window',
            render: (_v, job) => (
              <span className="mono" style={{ fontSize: 12 }}>
                {t('admin.jobs.window_counts', {
                  success: job.health.window_success_count,
                  failed: job.health.window_failure_count,
                })}
                {job.health.window_mean_duration_ms != null && (
                  <span className="muted">
                    {' · '}
                    {t('admin.jobs.window_mean', {
                      duration: formatDurationMs(t, job.health.window_mean_duration_ms),
                    })}
                  </span>
                )}
              </span>
            ),
          },
        ]}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-job-monitoring"
        title={t('admin.jobs.title')}
        subtitle={t('admin.jobs.subtitle')}
        actions={
          <Button
            icon={<ReloadOutlined />}
            loading={registryQuery.isFetching && !registryQuery.isLoading}
            onClick={() => registryQuery.refetch()}
          >
            {t('common.refresh')}
          </Button>
        }
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        {registry != null && !registry.recording_enabled && (
          <Alert type="warning" showIcon title={t('admin.jobs.recording_disabled')} />
        )}
        {registry != null && !registry.scheduling_enabled && jobs.length > 0 && (
          <Alert type="info" showIcon title={t('admin.jobs.scheduler_switch_off')} />
        )}
        {content}
      </div>
      <JobExecutionsDrawer jobName={selectedJob} onClose={() => setSelectedJob(null)} />
    </div>
  );
}
