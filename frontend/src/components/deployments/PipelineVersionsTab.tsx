import { useState } from 'react';
import { Button, Table } from 'antd';
import type { TableColumnsType } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import {
  deploymentVersionKeys,
  listPipelineEnvironmentVersions,
} from '@/api/deploymentVersions';
import type {
  DeploymentEnvironmentVersion,
  DeploymentVersionEnvironmentRef,
} from '@/types/api';
import { EmptyState } from '@/components/common/EmptyState';
import { apiErrorMessage } from '@/utils/apiErrors';
import { EnvironmentHistoryDrawer } from './EnvironmentHistoryDrawer';
import {
  DeployedAtCell,
  DriftChip,
  EnvironmentCell,
  OutcomeCell,
  VersionCell,
} from './versionMatrixCells';

/**
 * The version matrix for one pipeline. Mounted both as the settings-page Versions tab and as the
 * standalone `/deployment-versions/:pipelineId` page that `can_trigger`-only users can reach.
 */
export function PipelineVersionsTab({ pipelineId }: { pipelineId: string }) {
  const { t } = useTranslation();
  const [historyFor, setHistoryFor] = useState<DeploymentVersionEnvironmentRef | null>(null);

  const matrixQuery = useQuery({
    queryKey: deploymentVersionKeys.matrix(pipelineId),
    queryFn: () => listPipelineEnvironmentVersions(pipelineId),
  });

  const columns: TableColumnsType<DeploymentEnvironmentVersion> = [
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
    {
      title: '',
      key: 'actions',
      width: 110,
      render: (_v, row) => (
        <Button size="small" onClick={() => setHistoryFor(row.environment)}>
          {t('deploygov.versions.history')}
        </Button>
      ),
    },
  ];

  // The standalone route catches this before mounting the tab, but the settings-page mount does
  // not — without this, a failed matrix read would tell an admin they have no environments.
  if (matrixQuery.isError) {
    return (
      <EmptyState
        title={t('deploygov.error')}
        description={apiErrorMessage(matrixQuery.error, () => t('deploygov.error'))}
      />
    );
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {/* Every environment comes back, including ones never deployed to — those render "—" and a
          "Never deployed" chip. The empty state below only fires when the pipeline has no
          environments configured at all. Server order (sort_order, then name) is authoritative. */}
      <Table<DeploymentEnvironmentVersion>
        rowKey={(row) => row.environment.id}
        size="small"
        pagination={false}
        loading={matrixQuery.isLoading}
        dataSource={matrixQuery.data ?? []}
        columns={columns}
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: t('deploygov.versions.tabEmpty') }}
      />
      <EnvironmentHistoryDrawer
        pipelineId={pipelineId}
        environment={historyFor}
        onClose={() => setHistoryFor(null)}
      />
    </div>
  );
}
