import type { ReactNode } from 'react';
import { Alert, Space } from 'antd';
import { useTranslation } from 'react-i18next';
import { Pill } from '@/components/common/Pill';
import { decisionStepOutcomeLabel, queryStatusLabel } from '@/utils/enumLabels';
import { decisionStepOutcomeColor, statusColor } from '@/utils/statusColors';
import type { DecisionTraceStep, QueryStatus, SimulationCaveat } from '@/types/api';
import {
  ROUTING_POLICIES_KEY,
  extractRoutingPolicies,
  formatStepDetails,
} from './decisionTraceDetails';
import { RoutingPoliciesTraceTable } from './RoutingPoliciesTraceTable';
import './decisionTrace.css';

export interface DecisionTraceViewProps<K extends string> {
  steps: DecisionTraceStep<K>[];
  /** Localises the step kind — each decision kind passes its own label function. */
  stepLabel: (kind: K) => string;
  resultingStatus?: QueryStatus;
  caveats: SimulationCaveat[];
  /** Kind-specific verdict rendered above the steps (e.g. the deployment gate's `releasable`). */
  headline?: ReactNode;
}

/**
 * One renderer for all three decision traces (#1066). Every step is rendered, a SKIP included —
 * "this stage never ran" is an answer, and the backend reports it rather than omitting it.
 */
export function DecisionTraceView<K extends string>({
  steps,
  stepLabel,
  resultingStatus,
  caveats,
  headline,
}: DecisionTraceViewProps<K>) {
  const { t } = useTranslation();
  const status = resultingStatus == null ? null : statusColor(resultingStatus);

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }} data-testid="decision-trace">
      <div className="af-trace-result">
        <span className="muted">{t('decisionTrace.resulting_status')}</span>
        {status && resultingStatus != null ? (
          <Pill fg={status.fg} bg={status.bg} border={status.border} withDot>
            <span data-testid="decision-trace-status">{queryStatusLabel(t, resultingStatus)}</span>
          </Pill>
        ) : (
          <span data-testid="decision-trace-status">{t('decisionTrace.refused')}</span>
        )}
      </div>

      {headline}

      {caveats.map((caveat) => (
        <Alert key={caveat} type="info" showIcon title={t(`policySimulation.caveats.${caveat}`)} />
      ))}

      <ol className="af-trace-steps">
        {steps.map((step, index) => {
          const color = decisionStepOutcomeColor(step.outcome);
          const isRouting = step.step === 'ROUTING_POLICIES';
          const rows = formatStepDetails(step.details, t, isRouting ? [ROUTING_POLICIES_KEY] : []);
          return (
            <li
              key={`${step.step}-${index}`}
              className="af-trace-step"
              data-testid={`trace-step-${step.step}`}
              data-outcome={step.outcome}
            >
              <div className="af-trace-step-head">
                <span className="mono muted af-trace-step-index">{index + 1}</span>
                <strong>{stepLabel(step.step)}</strong>
                <Pill fg={color.fg} bg={color.bg} border={color.border} withDot size="sm">
                  {decisionStepOutcomeLabel(t, step.outcome)}
                </Pill>
              </div>
              <div className="af-trace-step-reason">{step.reason}</div>
              {rows.length > 0 && (
                <dl className="af-trace-details">
                  {rows.map((row) => (
                    <div key={row.key} className="af-trace-detail">
                      <dt className="muted">{row.label}</dt>
                      <dd>
                        {Array.isArray(row.value) ? (
                          <ul>
                            {row.value.map((line, i) => (
                              <li key={i}>{line}</li>
                            ))}
                          </ul>
                        ) : (
                          row.value
                        )}
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
              {isRouting && (
                <RoutingPoliciesTraceTable policies={extractRoutingPolicies(step.details)} />
              )}
            </li>
          );
        })}
      </ol>
    </Space>
  );
}
