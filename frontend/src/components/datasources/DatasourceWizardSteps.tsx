import { Steps } from 'antd';
import { useTranslation } from 'react-i18next';

export type WizardStepKey = 'type' | 'connection' | 'test';

interface DatasourceWizardStepsProps {
  current: WizardStepKey;
}

export function DatasourceWizardSteps({ current }: DatasourceWizardStepsProps) {
  const { t } = useTranslation();
  const order: WizardStepKey[] = ['type', 'connection', 'test'];
  return (
    <Steps
      size="small"
      current={order.indexOf(current)}
      items={[
        { title: t('datasources.create.step_type') },
        { title: t('datasources.create.step_connection') },
        { title: t('datasources.create.step_test') },
      ]}
    />
  );
}
