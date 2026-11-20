import { useMemo, useState } from 'react';
import { Button, Input, Select, Skeleton, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { DeploymentUnitOutlined, FundOutlined, SearchOutlined } from '@ant-design/icons';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { DataTable, FilterBar, Page, PageBody } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { StatusPill } from '@/components/common/StatusPill';
import { RiskPill } from '@/components/common/RiskPill';
import { deploymentKeys, listDeploymentRequests } from '@/api/deploymentRequests';
import { deploymentPipelineKeys, listDeploymentPipelines } from '@/api/deploymentPipelines';
import {
  deploymentVersionKeys,
  listPipelineEnvironmentVersions,
} from '@/api/deploymentVersions';
import { DriftChip } from '@/components/deployments/versionMatrixCells';
import { ChangeTicketLink } from '@/components/deployments/ChangeTicketLink';
import {
  deploymentOutcomeLabel,
  enumOptions,
  queryStatusLabel,
  verificationStatusLabel,
} from '@/utils/enumLabels';
import { deploymentOutcomeColor, verificationStatusColor } from '@/utils/statusColors';
import { timeAgo } from '@/utils/dateFormat';
import { usePermission } from '@/utils/permissions';
import type { DeploymentEnvironmentVersion, DeploymentRequest, QueryStatus } from '@/types/api';

const STATUSES: QueryStatus[] = [
  'PENDING_AI',
  'PENDING_REVIEW',
  'APPROVED',
  'EXECUTED',
  'REJECTED',
  'TIMED_OUT',
  'FAILED',
  'CANCELLED',
];

const PAGE_SIZE = 20;

export default function DeploymentListPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const canManagePipelines = usePermission('DEPLOYMENT_PIPELINE_MANAGE');

  const [status, setStatus] = useState<QueryStatus | 'all'>('all');
  const [pipeline, setPipeline] = useState<string | 'all'>('all');
  const [environment, setEnvironment] = useState('');
  const [version, setVersion] = useState('');
  const [page, setPage] = useState(0);

  const filters = useMemo(
    () => ({
      status: status === 'all' ? undefined : status,
      pipeline_id: pipeline === 'all' ? undefined : pipeline,
      environment: environment.trim() || undefined,
      version: version.trim() || undefined,
      page,
      size: PAGE_SIZE,
    }),
    [status, pipeline, environment, version, page],
  );

  const listQuery = useQuery({
    queryKey: deploymentKeys.list(filters),
    queryFn: () => listDeploymentRequests(filters),
  });

  // /deployment-pipelines needs DEPLOYMENT_PIPELINE_MANAGE; everyone else builds the
  // pipeline filter from the names already present in the loaded rows.
  const pipelinesQuery = useQuery({
    queryKey: deploymentPipelineKeys.list({ size: 100 }),
    queryFn: () => listDeploymentPipelines({ size: 100 }),
    enabled: canManagePipelines,
  });

  const rows = useMemo(() => listQuery.data?.content ?? [], [listQuery.data]);

  const pipelineOptions = useMemo(() => {
    if (canManagePipelines && pipelinesQuery.data) {
      return pipelinesQuery.data.content.map((p) => ({ value: p.id, label: p.name }));
    }
    const seen = new Map<string, string>();
    for (const row of rows) {
      if (!seen.has(row.pipeline_id)) {
        seen.set(row.pipeline_id, row.pipeline_name ?? row.pipeline_id);
      }
    }
    return [...seen.entries()].map(([value, label]) => ({ value, label }));
  }, [canManagePipelines, pipelinesQuery.data, rows]);

  // One matrix read per distinct pipeline on the page — a handful at 20 rows, and the keys are
  // shared with the detail page and across pagination, so revisits are cache hits. A 404 (the
  // caller cannot see that pipeline's matrix) just leaves its rows unbadged.
  const pipelineIds = useMemo(() => [...new Set(rows.map((r) => r.pipeline_id))], [rows]);
  const versionsByEnvironment = useQueries({
    queries: pipelineIds.map((pipelineId) => ({
      queryKey: deploymentVersionKeys.matrix(pipelineId),
      queryFn: () => listPipelineEnvironmentVersions(pipelineId),
      retry: false,
    })),
    combine: (results) => {
      const byEnvironment = new Map<string, DeploymentEnvironmentVersion>();
      for (const result of results) {
        for (const row of result.data ?? []) byEnvironment.set(row.environment.id, row);
      }
      return byEnvironment;
    },
  });

  const columns: TableColumnsType<DeploymentRequest> = [
    {
      title: t('deploygov.deployments.pipeline'),
      dataIndex: 'pipeline_name',
      width: 180,
      render: (v: string | null, r) => v ?? r.pipeline_id,
    },
    { title: t('deploygov.deployments.environment'), dataIndex: 'environment_name', width: 140 },
    {
      title: t('deploygov.deployments.version'),
      dataIndex: 'version',
      width: 140,
      render: (v: string) => (
        <span className="mono u-text-xs">
          {v}
        </span>
      ),
    },
    {
      title: t('deploygov.deployments.status'),
      dataIndex: 'status',
      width: 140,
      render: (s: QueryStatus) => <StatusPill status={s} />,
    },
    {
      title: t('deploygov.deployments.risk'),
      width: 120,
      render: (_v, r) =>
        r.ai_risk_level != null && r.ai_risk_score != null ? (
          <RiskPill level={r.ai_risk_level} score={r.ai_risk_score} />
        ) : (
          <span className="muted u-text-2xs">
            —
          </span>
        ),
    },
    {
      title: t('deploygov.deployments.outcome'),
      dataIndex: 'outcome',
      width: 130,
      render: (outcome: DeploymentRequest['outcome'], row) => {
        // SKIPPED is left out: it would tag every deploy to an environment with no checks.
        const verification = row.verification_status ?? 'PENDING';
        const showVerification = verification !== 'PENDING' && verification !== 'SKIPPED';
        if (outcome == null && !showVerification) return <span className="muted">—</span>;
        const color = outcome == null ? null : deploymentOutcomeColor(outcome);
        const verificationColor = verificationStatusColor(verification);
        return (
          <span className="u-inline-flex u-wrap u-gap-1">
            {outcome != null && color && (
              <Tag style={{ color: color.fg, background: color.bg, borderColor: color.border }}>
                {deploymentOutcomeLabel(t, outcome)}
              </Tag>
            )}
            {/* A breach leaves outcome SUCCEEDED, so the verification tag says it failed. */}
            {showVerification && (
              <Tag
                style={{
                  color: verificationColor.fg,
                  background: verificationColor.bg,
                  borderColor: verificationColor.border,
                }}
              >
                {t('deploygov.deployments.verificationTag', {
                  status: verificationStatusLabel(t, verification),
                })}
              </Tag>
            )}
          </span>
        );
      },
    },
    {
      title: t('deploygov.deployments.ticket'),
      key: 'change_ticket',
      width: 160,
      render: (_v, r) =>
        r.change_ticket ? (
          <ChangeTicketLink ticket={r.change_ticket} />
        ) : (
          <span className="muted">—</span>
        ),
    },
    {
      title: t('deploygov.versions.drift'),
      key: 'drift',
      width: 180,
      // Only the request that is actually live on the environment gets a chip — drift describes
      // the environment now, so badging a superseded historical request would be misleading.
      render: (_v, r) => {
        const version = versionsByEnvironment.get(r.environment_id);
        if (version == null || version.current_request_id !== r.id) {
          return <span className="muted">—</span>;
        }
        return <DriftChip row={version} note={t('deploygov.versions.driftOnCurrent')} />;
      },
    },
    {
      title: t('deploygov.deployments.created'),
      dataIndex: 'created_at',
      width: 110,
      render: (v: string) => (
        <span className="muted u-text-xs">
          {timeAgo(v)}
        </span>
      ),
    },
  ];

  return (
    <Page>
      <PageHeader
        title={t('deploygov.deployments.title')}
        subtitle={t('deploygov.deployments.subtitle')}
      />
      <FilterBar>
        <Select
          value={status}
          aria-label={t('deploygov.deployments.status')}
          onChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('deploygov.deployments.filterAllStatuses') },
            ...enumOptions(STATUSES, queryStatusLabel, t),
          ]}
          className="u-w-sm"
        />
        <Select
          value={pipeline}
          aria-label={t('deploygov.deployments.pipeline')}
          onChange={(v) => {
            setPipeline(v);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('deploygov.deployments.filterAllPipelines') },
            ...pipelineOptions,
          ]}
          className="u-w-md"
        />
        <Input
          prefix={<SearchOutlined className="u-fg-faint" />}
          placeholder={t('deploygov.deployments.environmentFilterPlaceholder')}
          aria-label={t('deploygov.deployments.environment')}
          value={environment}
          onChange={(e) => {
            setEnvironment(e.target.value);
            setPage(0);
          }}
          style={{ width: 170 }}
          allowClear
        />
        <Input
          placeholder={t('deploygov.deployments.versionFilterPlaceholder')}
          aria-label={t('deploygov.deployments.version')}
          value={version}
          onChange={(e) => {
            setVersion(e.target.value);
            setPage(0);
          }}
          style={{ width: 150 }}
          allowClear
        />
        {/* The only route to a pipeline's matrix for a can_trigger-only user: the settings page
            and GET /deployment-pipelines are both admin-only, but their pipeline options are
            derived from their own rows above, so this needs no extra query. */}
        <Button
          icon={<DeploymentUnitOutlined />}
          disabled={pipeline === 'all'}
          onClick={() => navigate(`/deployment-versions/${pipeline}`)}
        >
          {t('deploygov.versions.viewMatrix')}
        </Button>
        <Button
          icon={<FundOutlined />}
          disabled={pipeline === 'all'}
          onClick={() => navigate(`/deployment-metrics/${pipeline}`)}
        >
          {t('deploygov.metrics.viewMetrics')}
        </Button>
        <div className="u-flex-1" />
        <span className="mono muted u-text-2xs">
          {t('deploygov.deployments.countLabel', {
            total: listQuery.data?.total_elements ?? 0,
          })}
        </span>
      </FilterBar>
      <PageBody flush>
        {listQuery.isLoading ? (
          <div className="u-p-4">
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <DataTable<DeploymentRequest>
            rowKey="id"
            dataSource={rows}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            emptyTitle={t('deploygov.deployments.empty')}
            onRowActivate={(row) => navigate(`/deployments/${row.id}`)}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: listQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
          />
        )}
      </PageBody>
    </Page>
  );
}
