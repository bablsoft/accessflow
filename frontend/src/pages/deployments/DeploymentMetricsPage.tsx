import { useTranslation } from 'react-i18next';
import { Page, PageBody } from '@/components/layout';
import { PageHeader } from '@/components/common/PageHeader';
import { DeploymentMetricsPanel } from '@/components/deployments/DeploymentMetricsPanel';

/** Org-wide DORA + approval-SLA metrics (#987), with the per-environment breakdown. */
export default function DeploymentMetricsPage() {
  const { t } = useTranslation();
  return (
    <Page>
      <PageHeader
        docsAnchor="cfg-deployment-pipelines"
        title={t('deploygov.metrics.title')}
        subtitle={t('deploygov.metrics.subtitle')}
      />
      <PageBody>
        <DeploymentMetricsPanel />
      </PageBody>
    </Page>
  );
}
