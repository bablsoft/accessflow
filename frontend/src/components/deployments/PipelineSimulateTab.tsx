import { Alert, Button, DatePicker, Form, Input, Select, Skeleton, Space } from 'antd';
import type { Dayjs } from 'dayjs';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { deploymentPipelineKeys, listDeploymentEnvironments } from '@/api/deploymentPipelines';
import { simulateDeployment } from '@/api/deploymentSimulations';
import { EmptyState } from '@/components/common/EmptyState';
import { DecisionTraceView } from '@/components/policies/DecisionTraceView';
import { SimulationAssumptionFields } from '@/components/policies/SimulationAssumptionFields';
import { SimulationUserSelect } from '@/components/policies/SimulationUserSelect';
import { apiErrorMessage } from '@/utils/apiErrors';
import { fmtDate } from '@/utils/dateFormat';
import { deploymentDecisionStepLabel } from '@/utils/enumLabels';
import type {
  DeploymentSimulationRequest,
  DeploymentSimulationResult,
  RiskLevel,
  SimulatedAiOutcome,
} from '@/types/api';

// Mirrors SimulateDeploymentRequest: @NotBlank @Size(max = 255) on version.
export const VERSION_MAX_LENGTH = 255;

interface FormValues {
  environment_id?: string;
  user_id?: string;
  version?: string;
  ai_outcome: SimulatedAiOutcome;
  risk_level?: RiskLevel;
  scheduled_for?: Dayjs | null;
  at?: Dayjs | null;
}

/**
 * The gate's verdict, computed by the same `releasable` function the CI job blocks on — the
 * reason this banner sits above the step-by-step trace.
 */
function ReleasableBanner({ result }: { result: DeploymentSimulationResult }) {
  const { t } = useTranslation();
  const when = result.evaluated_at != null ? fmtDate(result.evaluated_at) : null;
  return (
    <Alert
      type={result.releasable ? 'success' : 'error'}
      showIcon
      data-testid="releasable-banner"
      title={result.releasable ? t('deploygov.simulate.releasable') : t('deploygov.simulate.not_releasable')}
      description={
        <>
          {when && <div>{t('deploygov.simulate.evaluated_at', { date: when })}</div>}
          <div className="muted">{t('deploygov.simulate.gate_note')}</div>
        </>
      }
    />
  );
}

/**
 * Deployment decision trace for one pipeline (#967 endpoint, #1066 UI). The optional `at` instant
 * answers "would a release this Friday evening be held?" — freeze windows and the schedule are
 * evaluated as of that moment. Nothing is written.
 */
export function PipelineSimulateTab({ pipelineId }: { pipelineId: string }) {
  const { t } = useTranslation();
  const environments = useQuery({
    queryKey: deploymentPipelineKeys.environments(pipelineId),
    queryFn: () => listDeploymentEnvironments(pipelineId),
  });
  const trace = useMutation({
    mutationFn: (body: DeploymentSimulationRequest) => simulateDeployment(body),
  });

  const onFinish = (values: FormValues) => {
    if (!values.user_id || !values.environment_id || !values.version) return;
    trace.mutate({
      user_id: values.user_id,
      pipeline_id: pipelineId,
      environment_id: values.environment_id,
      version: values.version.trim(),
      ai_outcome: values.ai_outcome,
      ...(values.risk_level ? { risk_level: values.risk_level } : {}),
      ...(values.scheduled_for ? { scheduled_for: values.scheduled_for.toISOString() } : {}),
      ...(values.at ? { at: values.at.toISOString() } : {}),
    });
  };

  const envs = [...(environments.data ?? [])].sort((a, b) => a.sort_order - b.sort_order);

  return (
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Alert type="info" showIcon title={t('deploygov.simulate.read_only_notice')} />
      <Form<FormValues>
        name="deployment-simulation"
        layout="vertical"
        initialValues={{ ai_outcome: 'SKIPPED' }}
        onFinish={onFinish}
        requiredMark="optional"
      >
        <Space wrap size="middle" align="start">
          <Form.Item
            name="environment_id"
            label={t('deploygov.simulate.environment')}
            rules={[{ required: true, message: t('deploygov.simulate.environment_required') }]}
            style={{ width: 240 }}
          >
            <Select
              loading={environments.isLoading}
              placeholder={t('deploygov.simulate.environment_placeholder')}
              options={envs.map((env) => ({ value: env.id, label: env.name }))}
            />
          </Form.Item>
          <Form.Item
            name="user_id"
            label={t('decisionTrace.user')}
            rules={[{ required: true, message: t('deploygov.simulate.user_required') }]}
            style={{ width: 320 }}
          >
            <SimulationUserSelect />
          </Form.Item>
          <Form.Item
            name="version"
            label={t('deploygov.simulate.version')}
            rules={[
              { required: true, whitespace: true, message: t('deploygov.simulate.version_required') },
              {
                transform: (value?: string) => value?.trim(),
                max: VERSION_MAX_LENGTH,
                message: t('deploygov.simulate.version_too_long', { max: VERSION_MAX_LENGTH }),
              },
            ]}
            style={{ width: 200 }}
          >
            <Input className="mono" placeholder={t('deploygov.simulate.version_placeholder')} />
          </Form.Item>
        </Space>
        <Space wrap size="middle" align="start">
          <SimulationAssumptionFields />
          <Form.Item
            name="at"
            label={t('deploygov.simulate.at')}
            tooltip={t('deploygov.simulate.at_hint')}
            style={{ width: 240 }}
          >
            <DatePicker showTime style={{ width: '100%' }} placeholder={t('deploygov.simulate.at_placeholder')} />
          </Form.Item>
          <Form.Item
            name="scheduled_for"
            label={t('deploygov.simulate.scheduled_for')}
            tooltip={t('deploygov.simulate.scheduled_for_hint')}
            style={{ width: 240 }}
          >
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
        </Space>
        <Button type="primary" htmlType="submit" loading={trace.isPending}>
          {t('deploygov.simulate.simulate')}
        </Button>
      </Form>

      {trace.isPending && <Skeleton active paragraph={{ rows: 8 }} />}
      {trace.isError && (
        <EmptyState
          size="sm"
          title={t('deploygov.simulate.error')}
          description={apiErrorMessage(trace.error, () => t('deploygov.simulate.error'))}
        />
      )}
      {trace.data && !trace.isPending && (
        <DecisionTraceView
          steps={trace.data.steps}
          stepLabel={(k) => deploymentDecisionStepLabel(t, k)}
          resultingStatus={trace.data.resulting_status}
          caveats={trace.data.caveats}
          headline={<ReleasableBanner result={trace.data} />}
        />
      )}
    </Space>
  );
}
