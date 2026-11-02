import { Button, Skeleton } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { isAxiosError } from 'axios';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { DeploymentMetricsPanel } from '@/components/deployments/DeploymentMetricsPanel';
import {
  deploymentVersionKeys,
  listPipelineEnvironmentVersions,
} from '@/api/deploymentVersions';
import { apiErrorMessage } from '@/utils/apiErrors';

const isNotFound = (err: unknown): boolean => isAxiosError(err) && err.response?.status === 404;

/**
 * One pipeline's metrics as a standalone route (#987). Deliberately unguarded, like
 * `PipelineVersionsPage`: the server answers 404-never-403, which is what lets a
 * `can_trigger`-only user reach the metrics of their own pipeline from `/deployments`.
 */
export default function PipelineMetricsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { pipelineId = '' } = useParams();

  // Shares the panel's environment-options cache entry, and probes visibility + the name.
  const matrixQuery = useQuery({
    queryKey: deploymentVersionKeys.matrix(pipelineId),
    queryFn: () => listPipelineEnvironmentVersions(pipelineId),
    enabled: !!pipelineId,
  });

  const pipelineName = matrixQuery.data?.[0]?.pipeline_name;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="guide-deployment-approval"
        title={
          pipelineName
            ? t('deploygov.metrics.pipelineTitle', { name: pipelineName })
            : t('deploygov.metrics.title')
        }
        subtitle={t('deploygov.metrics.pipelineSubtitle')}
        actions={<Button onClick={() => navigate('/deployments')}>{t('common.back')}</Button>}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 28px' }}>
        {matrixQuery.isLoading && <Skeleton active paragraph={{ rows: 6 }} />}
        {matrixQuery.isError &&
          (isNotFound(matrixQuery.error) ? (
            <EmptyState
              title={t('deploygov.versions.notFound')}
              description={t('deploygov.versions.notFoundDescription')}
            />
          ) : (
            <EmptyState
              title={t('deploygov.error')}
              description={apiErrorMessage(matrixQuery.error, () => t('deploygov.error'))}
            />
          ))}
        {!matrixQuery.isLoading && !matrixQuery.isError && (
          <DeploymentMetricsPanel pipelineId={pipelineId} />
        )}
      </div>
    </div>
  );
}
