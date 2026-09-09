import { DeploymentUnitOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ActivityList } from '@/components/dashboard/ActivityList';
import { DriftChip } from '@/components/deployments/versionMatrixCells';
import {
  deploymentVersionKeys,
  listDeploymentEnvironmentVersions,
  type DeploymentEnvironmentVersionFilters,
} from '@/api/deploymentVersions';
import type { DeploymentEnvironmentVersion } from '@/types/api';

/** Drifted environments first — the rows an operator would actually act on. */
const MATRIX_FILTERS: DeploymentEnvironmentVersionFilters = { drifted: true, size: 5 };

/**
 * Which version runs where, straight from the #742 org-wide version inventory. Shows only the
 * drifted environments: an up-to-date fleet is the empty state, and the full matrix is one click
 * away on `/deployment-versions`.
 */
export function DeploymentVersionsWidget() {
  const { t } = useTranslation();
  const versionsQuery = useQuery({
    queryKey: deploymentVersionKeys.list(MATRIX_FILTERS),
    queryFn: () => listDeploymentEnvironmentVersions(MATRIX_FILTERS),
  });

  return (
    <ActivityList
      items={versionsQuery.data?.content ?? []}
      loading={versionsQuery.isLoading}
      error={versionsQuery.error}
      onRetry={() => void versionsQuery.refetch()}
      emptyIcon={<DeploymentUnitOutlined style={{ fontSize: 16 }} />}
      emptyTitle={t('dashboard.deployment_versions.empty')}
      rowKey={(it: DeploymentEnvironmentVersion) => `${it.pipeline_id}:${it.environment.id}`}
      viewAllTo="/deployment-versions"
      renderRow={(it) => ({
        pills: <DriftChip row={it} />,
        primary: (
          <>
            <span style={{ fontWeight: 500 }}>{it.pipeline_name}</span>{' '}
            <span className="muted">{it.environment.name}</span>
          </>
        ),
        meta: (
          <span className="mono" style={{ fontSize: 12 }}>
            {it.current_version ?? '—'}
          </span>
        ),
      })}
    />
  );
}
