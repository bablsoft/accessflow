import { useMemo, useState } from 'react';
import { Button, Input, Select, Skeleton, Table, Tag } from 'antd';
import type { TableColumnsType } from 'antd';
import { DeploymentUnitOutlined, SearchOutlined } from '@ant-design/icons';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
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
import { deploymentOutcomeLabel, enumOptions, queryStatusLabel } from '@/utils/enumLabels';
import { deploymentOutcomeColor } from '@/utils/statusColors';
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
        <span className="mono" style={{ fontSize: 12 }}>
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
          <span className="muted" style={{ fontSize: 11 }}>
            —
          </span>
        ),
    },
    {
      title: t('deploygov.deployments.outcome'),
      dataIndex: 'outcome',
      width: 130,
      render: (outcome: DeploymentRequest['outcome']) => {
        if (outcome == null) return <span className="muted">—</span>;
        const color = deploymentOutcomeColor(outcome);
        return (
          <Tag style={{ color: color.fg, background: color.bg, borderColor: color.border }}>
            {deploymentOutcomeLabel(t, outcome)}
          </Tag>
        );
      },
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
        <span className="muted" style={{ fontSize: 12 }}>
          {timeAgo(v)}
        </span>
      ),
    },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        title={t('deploygov.deployments.title')}
        subtitle={t('deploygov.deployments.subtitle')}
      />
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
          style={{ width: 160 }}
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
          style={{ width: 200 }}
        />
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
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
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('deploygov.deployments.countLabel', {
            total: listQuery.data?.total_elements ?? 0,
          })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>
        {listQuery.isLoading ? (
          <div style={{ padding: 16 }}>
            <Skeleton active paragraph={{ rows: 8 }} />
          </div>
        ) : (
          <Table<DeploymentRequest>
            rowKey="id"
            dataSource={rows}
            columns={columns}
            size="middle"
            scroll={{ x: 'max-content' }}
            locale={{ emptyText: t('deploygov.deployments.empty') }}
            onRow={(row) => ({
              onClick: () => navigate(`/deployments/${row.id}`),
              style: { cursor: 'pointer' },
            })}
            pagination={{
              current: page + 1,
              pageSize: PAGE_SIZE,
              total: listQuery.data?.total_elements ?? 0,
              showSizeChanger: false,
              onChange: (p) => setPage(p - 1),
            }}
          />
        )}
      </div>
    </div>
  );
}
