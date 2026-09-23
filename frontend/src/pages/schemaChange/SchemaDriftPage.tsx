import { useMemo, useState } from 'react';
import { Alert, App, Button, Card, Select, Skeleton, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { CheckOutlined, SyncOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  acknowledgeSchemaDriftFinding,
  listSchemaChangePipelines,
  listSchemaDriftFindings,
  listSchemaDriftScans,
  requestSchemaDriftScan,
  schemaChangeKeys,
} from '@/api/schemaChange';
import type {
  SchemaChangePipeline,
  SchemaDriftFinding,
  SchemaDriftFindingStatus,
  SchemaDriftScan,
  SchemaDriftScanPage,
} from '@/types/api';
import {
  enumOptions,
  SCHEMA_DRIFT_FINDING_STATUSES,
  schemaDriftBaselineLabel,
  schemaDriftFindingKindLabel,
  schemaDriftFindingStatusLabel,
} from '@/utils/enumLabels';
import { schemaDriftFindingKindColor, schemaDriftFindingStatusColor } from '@/utils/statusColors';
import { fmtDate, timeAgo } from '@/utils/dateFormat';
import { apiErrorMessage } from '@/utils/apiErrors';
import { showApiError } from '@/utils/showApiError';
import { groupFindingsByEnvironment, latestScanByEnvironment, scanReasonText } from '@/utils/schemaChange';

const FINDINGS_PAGE = 100;
const SCANS_PAGE = 100;
const SCAN_POLL_MS = 5_000;
/** An unfinished scan older than this is an orphan of a dead replica, not one worth polling for. */
const SCAN_POLL_WINDOW_MS = 30 * 60_000;

function isRunning(scan: SchemaDriftScan): boolean {
  return !scan.finished_at && Date.now() - new Date(scan.started_at).getTime() < SCAN_POLL_WINDOW_MS;
}

interface EnvironmentSection {
  environmentId: string;
  environmentName: string;
  pipelineName?: string;
  bound: boolean;
}

export default function SchemaDriftPage() {
  const { t } = useTranslation();
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const [pipeline, setPipeline] = useState<string>('all');
  const [environment, setEnvironment] = useState<string>('all');
  const [status, setStatus] = useState<SchemaDriftFindingStatus | 'all'>('OPEN');

  const findingFilters = useMemo(
    () => ({
      pipeline_id: pipeline === 'all' ? undefined : pipeline,
      environment_id: environment === 'all' ? undefined : environment,
      status: status === 'all' ? undefined : status,
      size: FINDINGS_PAGE,
    }),
    [pipeline, environment, status],
  );
  const scanFilters = useMemo(
    () => ({
      pipeline_id: pipeline === 'all' ? undefined : pipeline,
      environment_id: environment === 'all' ? undefined : environment,
      size: SCANS_PAGE,
    }),
    [pipeline, environment],
  );

  const pipelinesQuery = useQuery({
    queryKey: schemaChangeKeys.pipelines(),
    queryFn: listSchemaChangePipelines,
  });
  const findingsQuery = useQuery({
    queryKey: schemaChangeKeys.driftFindings(findingFilters),
    queryFn: () => listSchemaDriftFindings(findingFilters),
  });
  const scansQuery = useQuery({
    queryKey: schemaChangeKeys.driftScans(scanFilters),
    queryFn: async () => {
      const previous = queryClient.getQueryData<SchemaDriftScanPage>(
        schemaChangeKeys.driftScans(scanFilters),
      );
      const next = await listSchemaDriftScans(scanFilters);
      // A scan that just finished recorded findings the findings list has not seen yet.
      const finished = previous?.content.some(
        (before) => !before.finished_at && next.content.some((s) => s.id === before.id && s.finished_at),
      );
      if (finished) {
        void queryClient.invalidateQueries({ queryKey: ['schema-change', 'drift', 'findings'] });
      }
      return next;
    },
    // A scan requested with "Scan now" runs off the request thread; follow it until it finishes.
    refetchInterval: (query) => (query.state.data?.content.some(isRunning) ? SCAN_POLL_MS : false),
  });

  const invalidateDrift = () =>
    void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.drift() });

  const scanNow = useMutation({
    mutationFn: requestSchemaDriftScan,
    onSuccess: () => {
      invalidateDrift();
      message.success(t('schemaChange.drift.scanRequested'));
    },
    onError: (err) =>
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('schemaChange.drift.scanError'))),
  });
  const acknowledge = useMutation({
    mutationFn: acknowledgeSchemaDriftFinding,
    onSuccess: () => {
      invalidateDrift();
      message.success(t('schemaChange.drift.acknowledged'));
    },
    onError: (err) =>
      showApiError(message, err, (e) => apiErrorMessage(e, () => t('schemaChange.drift.acknowledgeError'))),
  });

  const pipelines = useMemo(() => pipelinesQuery.data ?? [], [pipelinesQuery.data]);
  const visiblePipelines = useMemo(
    () => (pipeline === 'all' ? pipelines : pipelines.filter((p) => p.id === pipeline)),
    [pipelines, pipeline],
  );
  const environmentOptions = visiblePipelines.flatMap((p) =>
    p.environments
      .filter((e) => e.datasource_id)
      .map((e) => ({
        value: e.id,
        label: pipeline === 'all' ? `${p.name} / ${e.name}` : e.name,
      })),
  );

  const sections = useMemo(
    () => buildSections(visiblePipelines, environment, findingsQuery.data?.content ?? []),
    [visiblePipelines, environment, findingsQuery.data],
  );
  const latestScans = useMemo(
    () => latestScanByEnvironment(scansQuery.data?.content ?? []),
    [scansQuery.data],
  );
  const groups = useMemo(
    () =>
      new Map(
        groupFindingsByEnvironment(
          findingsQuery.data?.content ?? [],
          sections.map((s) => s.environmentId),
        ).map((g) => [g.environmentId, g.findings]),
      ),
    [findingsQuery.data, sections],
  );

  const columns: TableColumnsType<SchemaDriftFinding> = [
    {
      title: t('schemaChange.drift.objectPath'),
      dataIndex: 'object_path',
      render: (v: string) => <span className="mono">{v}</span>,
    },
    {
      title: t('schemaChange.drift.kind'),
      dataIndex: 'finding_kind',
      render: (k: SchemaDriftFinding['finding_kind']) => {
        const c = schemaDriftFindingKindColor(k);
        return (
          <Tag style={{ color: c.fg, background: c.bg, borderColor: c.border }}>
            {schemaDriftFindingKindLabel(t, k)}
          </Tag>
        );
      },
    },
    {
      title: t('schemaChange.drift.expected'),
      dataIndex: 'expected_value',
      render: (v: string | null | undefined) =>
        v ? <span className="mono">{v}</span> : <span className="muted">{t('schemaChange.drift.absent')}</span>,
    },
    {
      title: t('schemaChange.drift.actual'),
      dataIndex: 'actual_value',
      render: (v: string | null | undefined) =>
        v ? <span className="mono">{v}</span> : <span className="muted">{t('schemaChange.drift.absent')}</span>,
    },
    {
      title: t('schemaChange.drift.status'),
      dataIndex: 'status',
      render: (s: SchemaDriftFindingStatus) => {
        const c = schemaDriftFindingStatusColor(s);
        return (
          <Tag style={{ color: c.fg, background: c.bg, borderColor: c.border }}>
            {schemaDriftFindingStatusLabel(t, s)}
          </Tag>
        );
      },
    },
    {
      title: t('schemaChange.drift.firstSeen'),
      dataIndex: 'first_detected_at',
      render: (v: string) => <span className="muted" title={fmtDate(v)}>{timeAgo(v)}</span>,
    },
    {
      title: t('schemaChange.drift.lastSeen'),
      dataIndex: 'last_seen_at',
      render: (v: string) => <span className="muted" title={fmtDate(v)}>{timeAgo(v)}</span>,
    },
    {
      title: t('schemaChange.drift.actions'),
      key: 'actions',
      render: (_v, f) =>
        f.status === 'OPEN' ? (
          <Button
            size="small"
            icon={<CheckOutlined />}
            loading={acknowledge.isPending && acknowledge.variables === f.id}
            onClick={() => acknowledge.mutate(f.id)}
          >
            {t('schemaChange.drift.acknowledge')}
          </Button>
        ) : null,
    },
  ];

  // One page of each is read; say so rather than let an older scan read as "never scanned".
  const truncated =
    (findingsQuery.data?.total_elements ?? 0) > (findingsQuery.data?.content.length ?? 0) ||
    (scansQuery.data?.total_elements ?? 0) > (scansQuery.data?.content.length ?? 0);

  const loading = pipelinesQuery.isLoading || findingsQuery.isLoading || scansQuery.isLoading;
  const error = pipelinesQuery.error ?? findingsQuery.error ?? scansQuery.error;

  let body;
  if (loading) {
    body = (
      <div style={{ padding: 16 }} data-testid="schema-drift-loading">
        <Skeleton active paragraph={{ rows: 8 }} />
      </div>
    );
  } else if (error) {
    body = (
      <Card size="small">
        <EmptyState
          title={t('schemaChange.drift.loadError')}
          description={apiErrorMessage(error, () => t('schemaChange.drift.loadError'))}
          action={
            <Button
              onClick={() => {
                invalidateDrift();
                void queryClient.invalidateQueries({ queryKey: schemaChangeKeys.pipelines() });
              }}
            >
              {t('common.retry')}
            </Button>
          }
        />
      </Card>
    );
  } else if (sections.length === 0) {
    body = (
      <Card size="small">
        <EmptyState
          title={t('schemaChange.drift.noEnvironmentsTitle')}
          description={t('schemaChange.drift.noEnvironmentsDescription')}
        />
      </Card>
    );
  } else {
    body = sections.map((section) => (
      <EnvironmentCard
        key={section.environmentId}
        section={section}
        scan={latestScans.get(section.environmentId)}
        findings={groups.get(section.environmentId) ?? []}
        columns={columns}
        statusFiltered={status === 'ACKNOWLEDGED' || status === 'RESOLVED'}
        scanning={scanNow.isPending && scanNow.variables === section.environmentId}
        onScan={() => scanNow.mutate(section.environmentId)}
      />
    ));
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader title={t('schemaChange.drift.title')} subtitle={t('schemaChange.drift.subtitle')} />
      <div
        style={{
          padding: '12px 28px',
          background: 'var(--bg-elev)',
          borderBottom: '1px solid var(--border)',
          display: 'flex',
          gap: 8,
          flexWrap: 'wrap',
          alignItems: 'center',
        }}
      >
        <Select
          value={pipeline}
          aria-label={t('schemaChange.drift.pipeline')}
          onChange={(v) => {
            setPipeline(v);
            setEnvironment('all');
          }}
          options={[
            { value: 'all', label: t('schemaChange.list.allPipelines') },
            ...pipelines.map((p) => ({ value: p.id, label: p.name })),
          ]}
          style={{ width: 220 }}
        />
        <Select
          value={environment}
          aria-label={t('schemaChange.drift.environment')}
          onChange={setEnvironment}
          options={[{ value: 'all', label: t('schemaChange.drift.allEnvironments') }, ...environmentOptions]}
          style={{ width: 240 }}
        />
        <Select
          value={status}
          aria-label={t('schemaChange.drift.status')}
          onChange={setStatus}
          options={[
            { value: 'all', label: t('schemaChange.drift.allStatuses') },
            ...enumOptions(SCHEMA_DRIFT_FINDING_STATUSES, schemaDriftFindingStatusLabel, t),
          ]}
          style={{ width: 170 }}
        />
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: 20, display: 'flex', flexDirection: 'column', gap: 16 }}>
        <Alert type="info" showIcon title={t('schemaChange.drift.fidelityNote')} />
        {truncated && (
          <Alert
            type="warning"
            showIcon
            data-testid="drift-truncated"
            title={t('schemaChange.drift.truncated', { shown: FINDINGS_PAGE })}
          />
        )}
        {body}
      </div>
    </div>
  );
}

/**
 * The environments to show: every bound rung of the visible pipelines (optionally one), plus any
 * environment that still has findings but is no longer on a ladder.
 */
function buildSections(
  pipelines: SchemaChangePipeline[],
  environmentFilter: string,
  findings: SchemaDriftFinding[],
): EnvironmentSection[] {
  const sections: EnvironmentSection[] = [];
  const known = new Set<string>();
  for (const p of pipelines) {
    for (const e of p.environments) {
      known.add(e.id);
      if (!e.datasource_id) continue;
      if (environmentFilter !== 'all' && e.id !== environmentFilter) continue;
      sections.push({ environmentId: e.id, environmentName: e.name, pipelineName: p.name, bound: true });
    }
  }
  for (const f of findings) {
    if (!known.has(f.environment_id) && !sections.some((s) => s.environmentId === f.environment_id)) {
      sections.push({ environmentId: f.environment_id, environmentName: f.environment_id, bound: false });
    }
  }
  return sections;
}

function EnvironmentCard({
  section,
  scan,
  findings,
  columns,
  statusFiltered,
  scanning,
  onScan,
}: {
  section: EnvironmentSection;
  scan: SchemaDriftScan | undefined;
  findings: SchemaDriftFinding[];
  columns: TableColumnsType<SchemaDriftFinding>;
  statusFiltered: boolean;
  scanning: boolean;
  onScan: () => void;
}) {
  const { t } = useTranslation();
  const reason = scanReasonText(t, scan?.error_message);
  const title = section.bound
    ? `${section.pipelineName ?? ''} / ${section.environmentName}`
    : t('schemaChange.drift.deletedEnvironment', { id: section.environmentId });

  let state;
  if (!scan) {
    state = <div className="muted">{t('schemaChange.drift.neverScanned')}</div>;
  } else if (!scan.finished_at) {
    state = (
      <div className="muted">
        <SyncOutlined spin style={{ marginRight: 6 }} />
        {t('schemaChange.drift.scanRunning')}
      </div>
    );
  } else if (!scan.applicable) {
    state = (
      <Alert
        type="warning"
        showIcon
        data-testid={`drift-unsupported-${section.environmentName}`}
        title={t('schemaChange.drift.unsupportedTitle')}
        description={reason ?? t('schemaChange.drift.reason.ENGINE_NOT_APPLICABLE')}
      />
    );
  } else if (findings.length === 0) {
    state = reason ? (
      <Alert type="info" showIcon title={t('schemaChange.drift.noComparison')} description={reason} />
    ) : (
      <div data-testid={`drift-clean-${section.environmentName}`} className="muted">
        {statusFiltered ? t('schemaChange.drift.noFindingsFiltered') : t('schemaChange.drift.noFindings')}
      </div>
    );
  }

  return (
    <Card
      size="small"
      title={title}
      data-testid={`drift-environment-${section.environmentName}`}
      extra={
        section.bound && (
          <Button size="small" icon={<SyncOutlined />} loading={scanning} onClick={onScan}>
            {t('schemaChange.drift.scanNow')}
          </Button>
        )
      }
    >
      {scan?.finished_at && (
        <div className="muted" style={{ fontSize: 12, marginBottom: 8 }}>
          {t('schemaChange.drift.lastScan', {
            when: timeAgo(scan.finished_at),
            baseline: schemaDriftBaselineLabel(t, scan.baseline),
            count: scan.findings_count,
          })}
          {scan.partial && <Tag style={{ marginLeft: 8 }}>{t('schemaChange.drift.partial')}</Tag>}
        </div>
      )}
      {state}
      {findings.length > 0 && (
        <>
          {reason && scan?.applicable && (
            <Alert type="info" showIcon style={{ marginBottom: 8 }} title={reason} />
          )}
          <Table<SchemaDriftFinding>
            rowKey="id"
            size="small"
            dataSource={findings}
            columns={columns}
            pagination={false}
            scroll={{ x: 'max-content' }}
          />
        </>
      )}
    </Card>
  );
}
