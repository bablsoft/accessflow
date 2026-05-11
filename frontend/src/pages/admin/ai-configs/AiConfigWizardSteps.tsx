import { Steps } from 'antd';
import { useTranslation } from 'react-i18next';

export type AiConfigWizardStepKey = 'provider' | 'connection' | 'test';

interface AiConfigWizardStepsProps {
  current: AiConfigWizardStepKey;
}

export function AiConfigWizardSteps({ current }: AiConfigWizardStepsProps) {
  const { t } = useTranslation();
  const order: AiConfigWizardStepKey[] = ['provider', 'connection', 'test'];
  return (
    <Steps
      size="small"
      current={order.indexOf(current)}
      items={[
        { title: t('admin.ai_configs.wizard.step_provider') },
        { title: t('admin.ai_configs.wizard.step_connection') },
        { title: t('admin.ai_configs.wizard.step_test') },
      ]}
    />
  );
}
