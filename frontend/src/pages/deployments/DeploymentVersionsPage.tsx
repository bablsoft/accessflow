import { useMemo, useState } from 'react';
import { Input, Select, Skeleton, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import {
  deploymentVersionKeys,
  listDeploymentEnvironmentVersions,
} from '@/api/deploymentVersions';
import { deploymentPipelineKeys, listDeploymentPipelines } from '@/api/deploymentPipelines';
import { tagOptions } from '@/components/deployments/versionMatrix';
import {
  DeployedAtCell,
  DriftChip,
  EnvironmentCell,
  OutcomeCell,
  VersionCell,
} from '@/components/deployments/versionMatrixCells';
import { apiErrorMessage } from '@/utils/apiErrors';
import { usePermission } from '@/utils/permissions';
import type { DeploymentEnvironmentVersion } from '@/types/api';

const PAGE_SIZE = 20;

type DriftFilter = 'all' | 'drifted' | 'current';

export default function DeploymentVersionsPage() {
  const { t } = useTranslation();
  const canManagePipelines = usePermission('DEPLOYMENT_PIPELINE_MANAGE');

  const [pipeline, setPipeline] = useState<string | 'all'>('all');
  const [tag, setTag] = useState<string | 'all'>('all');
  const [environment, setEnvironment] = useState('');
  const [drift, setDrift] = useState<DriftFilter>('all');
  const [page, setPage] = useState(0);

  const filters = useMemo(
    () => ({
      pipeline_id: pipeline === 'all' ? undefined : pipeline,
      tag: tag === 'all' ? undefined : tag,
      environment: environment.trim() || undefined,
      drifted: drift === 'all' ? undefined : drift === 'drifted',
      page,
      size: PAGE_SIZE,
    }),
    [pipeline, tag, environment, drift, page],
  );

  const listQuery = useQuery({
    queryKey: deploymentVersionKeys.list(filters),
    queryFn: () => listDeploymentEnvironmentVersions(filters),
  });

  // /deployment-pipelines needs DEPLOYMENT_PIPELINE_MANAGE; a DEPLOYMENT_REVIEW-only caller
  // builds the pipeline filter from the names already present in the loaded rows.
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
      if (!seen.has(row.pipeline_id)) seen.set(row.pipeline_id, row.pipeline_name);
    }
    return [...seen.entries()].map(([value, label]) => ({ value, label }));
  }, [canManagePipelines, pipelinesQuery.data, rows]);

  // Built from the loaded rows, which has two consequences worth knowing. `tag` is a server
  // filter, so once one is selected the response carries only rows with it and the other options
  // drop out until it is cleared (the selected value is pinned so it never vanishes from its own
  // Select). And the rows are one page, so a tag used only beyond page 1 is not offered until you
  // page to it — the same truncation the pipeline fallback above has.
  const tagFilterOptions = useMemo(
    () => tagOptions(rows, tag === 'all' ? null : tag),
    [rows, tag],
  );

  const filtersActive =
    pipeline !== 'all' || tag !== 'all' || environment.trim() !== '' || drift !== 'all';

  const columns: TableColumnsType<DeploymentEnvironmentVersion> = [
    {
      title: t('deploygov.versions.pipeline'),
      dataIndex: 'pipeline_name',
      width: 180,
      render: (v: string, row) => <Link to={`/deployment-versions/${row.pipeline_id}`}>{v}</Link>,
    },
    {
      title: t('deploygov.versions.environment'),
      key: 'environment',
      width: 220,
      render: (_v, row) => <EnvironmentCell environment={row.environment} />,
    },
    {
      title: t('deploygov.versions.currentVersion'),
      dataIndex: 'current_version',
      width: 140,
      render: (v: string | null) => <VersionCell value={v} />,
    },
    {
      title: t('deploygov.versions.deployedAt'),
      dataIndex: 'deployed_at',
      width: 120,
      render: (v: string | null) => <DeployedAtCell value={v} />,
    },
    {
      title: t('deploygov.versions.previousVersion'),
      dataIndex: 'previous_version',
      width: 130,
      render: (v: string | null) => <VersionCell value={v} />,
    },
    {
      title: t('deploygov.versions.outcome'),
      key: 'outcome',
      width: 150,
      render: (_v, row) => <OutcomeCell row={row} />,
    },
    {
      title: t('deploygov.versions.drift'),
      key: 'drift',
      width: 190,
      render: (_v, row) => <DriftChip row={row} />,
    },
  ];

  const body = () => {
    if (listQuery.isLoading) {
      return (
        <div style={{ padding: 16 }}>
          <Skeleton active paragraph={{ rows: 8 }} />
        </div>
      );
    }
    if (listQuery.isError) {
      return (
        <EmptyState
          title={t('deploygov.error')}
          description={apiErrorMessage(listQuery.error, () => t('deploygov.error'))}
        />
      );
    }
    // The endpoint only returns environments deployed at least once, so "no pipelines" and
    // "nothing deployed yet" are indistinguishable here — one honest message serves both.
    if (rows.length === 0 && !filtersActive) {
      return (
        <EmptyState
          title={t('deploygov.versions.empty')}
          description={t('deploygov.versions.emptyDescription')}
        />
      );
    }
    return (
      <Table<DeploymentEnvironmentVersion>
        rowKey={(row) => row.environment.id}
        dataSource={rows}
        columns={columns}
        size="middle"
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: t('deploygov.versions.emptyFiltered') }}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: listQuery.data?.total_elements ?? 0,
          showSizeChanger: false,
          onChange: (p) => setPage(p - 1),
        }}
      />
    );
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-deployment-pipelines"
        title={t('deploygov.versions.title')}
        subtitle={t('deploygov.versions.subtitle')}
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
          value={pipeline}
          aria-label={t('deploygov.versions.pipeline')}
          onChange={(v) => {
            setPipeline(v);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('deploygov.versions.filterAllPipelines') },
            ...pipelineOptions,
          ]}
          style={{ width: 200 }}
        />
        <Select
          value={tag}
          aria-label={t('deploygov.versions.filterTag')}
          onChange={(v) => {
            setTag(v);
            setPage(0);
          }}
          notFoundContent={t('deploygov.versions.noTags')}
          options={[
            { value: 'all', label: t('deploygov.versions.filterAllTags') },
            ...tagFilterOptions,
          ]}
          style={{ width: 170 }}
        />
        <Input
          prefix={<SearchOutlined style={{ color: 'var(--fg-faint)' }} />}
          placeholder={t('deploygov.versions.environmentFilterPlaceholder')}
          aria-label={t('deploygov.versions.environment')}
          value={environment}
          onChange={(e) => {
            setEnvironment(e.target.value);
            setPage(0);
          }}
          style={{ width: 170 }}
          allowClear
        />
        <Select
          value={drift}
          aria-label={t('deploygov.versions.filterDrift')}
          onChange={(v: DriftFilter) => {
            setDrift(v);
            setPage(0);
          }}
          options={[
            { value: 'all', label: t('deploygov.versions.filterDriftAll') },
            { value: 'drifted', label: t('deploygov.versions.filterDriftOnly') },
            { value: 'current', label: t('deploygov.versions.filterDriftNone') },
          ]}
          style={{ width: 180 }}
        />
        <div style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 11 }}>
          {t('deploygov.versions.countLabel', { total: listQuery.data?.total_elements ?? 0 })}
        </span>
      </div>
      <div style={{ flex: 1, overflow: 'auto', padding: '0 12px' }}>{body()}</div>
    </div>
  );
}
