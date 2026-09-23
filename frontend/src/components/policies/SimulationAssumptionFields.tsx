import { Form, Select } from 'antd';
import { useTranslation } from 'react-i18next';
import {
  RISK_LEVELS,
  SIMULATED_AI_OUTCOMES,
  enumOptions,
  riskLevelLabel,
  simulatedAiOutcomeLabel,
} from '@/utils/enumLabels';

/**
 * The AI outcome and risk level a trace assumes. Used by the API-call and deployment traces,
 * which take a risk level without a score (the query trace pairs level and score itself).
 */
export function SimulationAssumptionFields() {
  const { t } = useTranslation();
  return (
    <>
      <Form.Item name="ai_outcome" label={t('decisionTrace.ai_outcome')} style={{ width: 220 }}>
        <Select options={enumOptions(SIMULATED_AI_OUTCOMES, simulatedAiOutcomeLabel, t)} />
      </Form.Item>
      <Form.Item name="risk_level" label={t('decisionTrace.risk_level')} style={{ width: 200 }}>
        <Select allowClear options={enumOptions(RISK_LEVELS, riskLevelLabel, t)} />
      </Form.Item>
    </>
  );
}
