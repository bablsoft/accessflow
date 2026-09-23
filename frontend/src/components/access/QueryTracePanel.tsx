import { Alert, Button, Collapse, Form, InputNumber, Select, Skeleton, Space } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { simulateAccess } from '@/api/accessSimulations';
import { EmptyState } from '@/components/common/EmptyState';
import { SqlEditor } from '@/components/editor/SqlEditor';
import { DecisionTraceView } from '@/components/policies/DecisionTraceView';
import { SimulationUserSelect } from '@/components/policies/SimulationUserSelect';
import { SimulationDatasourceSelect } from '@/components/policies/SimulationDatasourceSelect';
import {
  useCanListAllDatasources,
  useVisibleDatasources,
} from '@/components/policies/useSimulationDatasources';
import { formatStepDetails } from '@/components/policies/decisionTraceDetails';
import { apiErrorMessage } from '@/utils/apiErrors';
import {
  RISK_LEVELS,
  SIMULATED_AI_OUTCOMES,
  enumOptions,
  queryDecisionStepLabel,
  riskLevelLabel,
  simulatedAiOutcomeLabel,
} from '@/utils/enumLabels';
import type { AccessSimulationRequest, RiskLevel, SimulatedAiOutcome } from '@/types/api';

// Mirrors SimulateAccessRequest: @Size(max = 100000) on sql, @Min(-1) @Max(100) on risk_score.
// The UI offers 0–100 only; -1 is the backend's "no score" sentinel, reached by leaving it empty.
export const SQL_MAX_LENGTH = 100_000;
export const RISK_SCORE_MIN = 0;
export const RISK_SCORE_MAX = 100;

interface FormValues {
  user_id?: string;
  datasource_id?: string;
  sql?: string;
  ai_outcome: SimulatedAiOutcome;
  risk_level?: RiskLevel;
  risk_score?: number | null;
}

/**
 * The query decision trace (#859 endpoint, #1066 UI). Read-only: the trace replays one
 * hypothetical query through the live pipeline's stages and writes nothing, so the button says
 * "Trace" and a success raises no toast.
 */
export function QueryTracePanel() {
  const { t } = useTranslation();
  const [form] = Form.useForm<FormValues>();
  const datasourceId = Form.useWatch('datasource_id', form);

  const { rows: datasourceRows } = useVisibleDatasources();
  const canListAll = useCanListAllDatasources();
  const selected = datasourceRows.find((d) => d.id === datasourceId);

  const trace = useMutation({ mutationFn: (body: AccessSimulationRequest) => simulateAccess(body) });

  const onFinish = (values: FormValues) => {
    if (!values.user_id || !values.datasource_id || !values.sql) return;
    const hasRisk = values.risk_level != null && values.risk_score != null;
    trace.mutate({
      user_id: values.user_id,
      datasource_id: values.datasource_id,
      sql: values.sql,
      ai_outcome: values.ai_outcome,
      // The pair travels together or not at all: a level without a score is scored -1 server-side,
      // which silently suppresses every risk policy. The form rules below refuse it first.
      ...(hasRisk ? { risk_level: values.risk_level, risk_score: values.risk_score ?? undefined } : {}),
    });
  };

  // Each risk field is required once the other one is set.
  const pairRule =
    (other: keyof FormValues) =>
    ({ getFieldValue }: { getFieldValue: (name: keyof FormValues) => unknown }) => ({
      validator(_: unknown, value: unknown) {
        const otherSet = getFieldValue(other) != null;
        const selfSet = value != null;
        return otherSet === selfSet
          ? Promise.resolve()
          : Promise.reject(new Error(t('access.simulation.risk_pair_required')));
      },
    });

  return (
    <Space orientation="vertical" size="large" style={{ width: '100%' }}>
      <Alert type="info" showIcon title={t('access.simulation.read_only_notice')} />

      <Form<FormValues>
        form={form}
        name="access-trace"
        layout="vertical"
        initialValues={{ ai_outcome: 'SKIPPED', sql: '' }}
        onFinish={onFinish}
        requiredMark="optional"
      >
        <Space wrap size="middle" style={{ width: '100%' }} align="start">
          <Form.Item
            name="user_id"
            label={t('decisionTrace.user')}
            rules={[{ required: true, message: t('access.simulation.user_required') }]}
            style={{ width: 320 }}
          >
            <SimulationUserSelect />
          </Form.Item>
          <Form.Item
            name="datasource_id"
            label={t('access.simulation.datasource')}
            rules={[{ required: true, message: t('access.simulation.datasource_required') }]}
            extra={canListAll ? undefined : t('access.simulation.datasource_scoped_hint')}
            style={{ width: 320 }}
          >
            <SimulationDatasourceSelect />
          </Form.Item>
        </Space>

        <Form.Item
          name="sql"
          label={t('access.simulation.sql')}
          rules={[
            { required: true, whitespace: true, message: t('access.simulation.sql_required') },
            {
              max: SQL_MAX_LENGTH,
              message: t('access.simulation.sql_too_long', { max: SQL_MAX_LENGTH }),
            },
          ]}
          valuePropName="value"
          trigger="onChange"
        >
          <SqlEditorField dbType={selected?.db_type} />
        </Form.Item>

        <Space wrap size="middle" align="start">
          <Form.Item name="ai_outcome" label={t('decisionTrace.ai_outcome')} style={{ width: 220 }}>
            <Select options={enumOptions(SIMULATED_AI_OUTCOMES, simulatedAiOutcomeLabel, t)} />
          </Form.Item>
          <Form.Item
            name="risk_level"
            label={t('decisionTrace.risk_level')}
            dependencies={['risk_score']}
            rules={[pairRule('risk_score')]}
            style={{ width: 200 }}
          >
            <Select allowClear options={enumOptions(RISK_LEVELS, riskLevelLabel, t)} />
          </Form.Item>
          <Form.Item
            name="risk_score"
            label={t('access.simulation.risk_score')}
            dependencies={['risk_level']}
            rules={[
              pairRule('risk_level'),
              {
                type: 'number',
                min: RISK_SCORE_MIN,
                max: RISK_SCORE_MAX,
                message: t('access.simulation.risk_score_range', {
                  min: RISK_SCORE_MIN,
                  max: RISK_SCORE_MAX,
                }),
              },
            ]}
            style={{ width: 200 }}
          >
            <InputNumber
              min={RISK_SCORE_MIN}
              max={RISK_SCORE_MAX}
              precision={0}
              style={{ width: '100%' }}
            />
          </Form.Item>
        </Space>
        <div className="muted" style={{ fontSize: 12, marginTop: -8, marginBottom: 16 }}>
          {t('access.simulation.risk_pair_hint')}
        </div>

        <Button type="primary" htmlType="submit" loading={trace.isPending}>
          {t('access.simulation.trace')}
        </Button>
      </Form>

      {trace.isPending && <Skeleton active paragraph={{ rows: 8 }} />}
      {trace.isError && (
        <EmptyState
          size="sm"
          title={t('access.simulation.error')}
          description={apiErrorMessage(trace.error, () => t('access.simulation.error'))}
        />
      )}
      {trace.data && !trace.isPending && (
        <>
          <DecisionTraceView
            steps={trace.data.steps}
            stepLabel={(k) => queryDecisionStepLabel(t, k)}
            resultingStatus={trace.data.resulting_status}
            caveats={trace.data.caveats}
          />
          {trace.data.evaluated_context != null && (
            <Collapse
              size="small"
              items={[
                {
                  key: 'context',
                  label: t('access.simulation.evaluated_context'),
                  children: (
                    <dl className="af-trace-details" data-testid="evaluated-context">
                      {formatStepDetails(trace.data.evaluated_context, t).map((row) => (
                        <div key={row.key} className="af-trace-detail">
                          <dt className="muted">{row.label}</dt>
                          <dd>{Array.isArray(row.value) ? row.value.join(', ') : row.value}</dd>
                        </div>
                      ))}
                    </dl>
                  ),
                },
              ]}
            />
          )}
        </>
      )}
    </Space>
  );
}

/** Adapts the CodeMirror editor to a Form.Item child (value / onChange injected by the form). */
function SqlEditorField({
  value,
  onChange,
  dbType,
}: {
  value?: string;
  onChange?: (next: string) => void;
  dbType?: Parameters<typeof SqlEditor>[0]['dbType'];
}) {
  return <SqlEditor value={value ?? ''} onChange={(next) => onChange?.(next)} dbType={dbType} height={160} />;
}
