import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/common/PageHeader';
import { DeploymentMetricsPanel } from '@/components/deployments/DeploymentMetricsPanel';

/** Org-wide DORA + approval-SLA metrics (#987), with the per-environment breakdown. */
export default function DeploymentMetricsPage() {
  const { t } = useTranslation();
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <PageHeader
        docsAnchor="cfg-deployment-pipelines"
        title={t('deploygov.metrics.title')}
        subtitle={t('deploygov.metrics.subtitle')}
      />
      <div style={{ flex: 1, overflow: 'auto', padding: '16px 28px' }}>
        <DeploymentMetricsPanel />
      </div>
    </div>
  );
}
