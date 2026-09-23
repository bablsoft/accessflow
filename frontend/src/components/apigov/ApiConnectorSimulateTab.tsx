import { Alert, Button, Form, Input, Select, Skeleton, Space } from 'antd';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { apiConnectorKeys, listApiOperations } from '@/api/apiConnectors';
import { simulateApiCall } from '@/api/apiCallSimulations';
import { EmptyState } from '@/components/common/EmptyState';
import { DecisionTraceView } from '@/components/policies/DecisionTraceView';
import { SimulationAssumptionFields } from '@/components/policies/SimulationAssumptionFields';
import { SimulationUserSelect } from '@/components/policies/SimulationUserSelect';
import { apiErrorMessage } from '@/utils/apiErrors';
import { apiDecisionStepLabel } from '@/utils/enumLabels';
import type { ApiCallSimulationRequest, RiskLevel, SimulatedAiOutcome } from '@/types/api';

// Mirrors SimulateApiCallRequest: @Size(max = 20) on verb (operation_id comes from the list).
export const VERB_MAX_LENGTH = 20;

interface FormValues {
  user_id?: string;
  operation_id?: string;
  verb?: string;
  ai_outcome: SimulatedAiOutcome;
  risk_level?: RiskLevel;
}

/**
 * API-call decision trace for one connector (#967 endpoint, #1066 UI). Replays a hypothetical call
 * through the governance pipeline without contacting the governed API, and writes nothing.
 */
export function ApiConnectorSimulateTab({ connectorId }: { connectorId: string }) {
  const { t } = useTranslation();
  const operations = useQuery({
    queryKey: apiConnectorKeys.operations(connectorId),
    queryFn: () => listApiOperations(connectorId),
  });
  const trace = useMutation({
    mutationFn: (body: ApiCallSimulationRequest) => simulateApiCall(body),
  });

  const onFinish = (values: FormValues) => {
    if (!values.user_id) return;
    const verb = values.verb?.trim();
    trace.mutate({
      user_id: values.user_id,
      connector_id: connectorId,
      ai_outcome: values.ai_outcome,
      ...(values.operation_id ? { operation_id: values.operation_id } : {}),
      ...(verb ? { verb } : {}),
      ...(values.risk_level ? { risk_level: values.risk_level } : {}),
    });
  };

  return (
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Alert type="info" showIcon title={t('apiGov.simulate.read_only_notice')} />
      <Form<FormValues>
        name="api-call-simulation"
        layout="vertical"
        initialValues={{ ai_outcome: 'SKIPPED' }}
        onFinish={onFinish}
        requiredMark="optional"
      >
        <Space wrap size="middle" align="start">
          <Form.Item
            name="user_id"
            label={t('decisionTrace.user')}
            rules={[{ required: true, message: t('apiGov.simulate.user_required') }]}
            style={{ width: 320 }}
          >
            <SimulationUserSelect />
          </Form.Item>
          <Form.Item name="operation_id" label={t('apiGov.simulate.operation')} style={{ width: 380 }}>
            <Select
              allowClear
              showSearch={{ optionFilterProp: 'label' }}
              loading={operations.isLoading}
              placeholder={t('apiGov.simulate.operation_placeholder')}
              options={(operations.data ?? []).map((op) => ({
                value: op.operation_id,
                label: `${op.verb} ${op.path}${op.summary ? ` — ${op.summary}` : ''}`,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="verb"
            label={t('apiGov.simulate.verb')}
            tooltip={t('apiGov.simulate.verb_hint')}
            rules={[
              {
                // Checked on the value that is sent: the form trims before posting.
                transform: (value?: string) => value?.trim(),
                max: VERB_MAX_LENGTH,
                message: t('apiGov.simulate.verb_too_long', { max: VERB_MAX_LENGTH }),
              },
            ]}
            style={{ width: 160 }}
          >
            <Input className="mono" placeholder={t('apiGov.simulate.verb_placeholder')} />
          </Form.Item>
          <SimulationAssumptionFields />
        </Space>
        <Button type="primary" htmlType="submit" loading={trace.isPending}>
          {t('apiGov.simulate.simulate')}
        </Button>
      </Form>

      {trace.isPending && <Skeleton active paragraph={{ rows: 8 }} />}
      {trace.isError && (
        <EmptyState
          size="sm"
          title={t('apiGov.simulate.error')}
          description={apiErrorMessage(trace.error, () => t('apiGov.simulate.error'))}
        />
      )}
      {trace.data && !trace.isPending && (
        <DecisionTraceView
          steps={trace.data.steps}
          stepLabel={(k) => apiDecisionStepLabel(t, k)}
          resultingStatus={trace.data.resulting_status}
          caveats={trace.data.caveats}
        />
      )}
    </Space>
  );
}
