import { Button, Skeleton } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { EmptyState } from '@/components/common/EmptyState';
import { PipelineVersionsTab } from '@/components/deployments/PipelineVersionsTab';
import {
  deploymentVersionKeys,
  listPipelineEnvironmentVersions,
} from '@/api/deploymentVersions';
import { apiErrorMessage } from '@/utils/apiErrors';
import { isAxiosError } from 'axios';

const isNotFound = (err: unknown): boolean => isAxiosError(err) && err.response?.status === 404;

/**
 * The per-pipeline version matrix as a standalone route.
 *
 * Deliberately carries no permission guard: the server answers `404` rather than `403` for a
 * pipeline the caller may not see, which is what lets a `can_trigger`-only user — who has no
 * access to the admin settings page — reach the matrix for their own pipelines.
 */
export default function PipelineVersionsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { pipelineId = '' } = useParams();

  // Probes visibility so a 404 renders an honest empty state instead of an empty table.
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
            ? t('deploygov.versions.pipelineTitle', { name: pipelineName })
            : t('deploygov.versions.title')
        }
        subtitle={t('deploygov.versions.pipelineSubtitle')}
        actions={<Button onClick={() => navigate('/deployments')}>{t('common.back')}</Button>}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '12px 28px' }}>
        {matrixQuery.isLoading && <Skeleton active paragraph={{ rows: 6 }} />}
        {matrixQuery.isError &&
          // 404 is the expected answer for a pipeline this caller may not see; anything else is a
          // real failure and must not be dressed up as "your pipeline is gone".
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
          <PipelineVersionsTab pipelineId={pipelineId} />
        )}
      </div>
    </div>
  );
}
