import type { TFunction } from 'i18next';
import type {
  CreateDeploymentRoutingPolicyInput,
  DeploymentRoutingAction,
  DeploymentRoutingConditions,
  DeploymentRoutingConditionsInput,
  PipelineProvider,
  RiskLevel,
  UpdateDeploymentRoutingPolicyInput,
} from '@/types/api';
import { isoWeekdayLabel, pipelineProviderLabel, riskLevelLabel } from '@/utils/enumLabels';
import { normalizeTime } from './freezeWindowForm';

/**
 * Flat form model for the deployment routing-policy modal. Unlike query routing's operand tree,
 * deployment conditions are a fixed AND of typed leaves, so the form maps 1:1 onto the wire shape.
 */
export interface DeploymentRoutingPolicyFormValues {
  name: string;
  pipeline_id: string | null;
  action: DeploymentRoutingAction;
  required_approvals: number | null;
  priority: number;
  enabled: boolean;
  environments: string[];
  providers: PipelineProvider[];
  min_risk_level: RiskLevel | null;
  version_globs: string[];
  days_of_week: number[];
  start_time: string | null;
  end_time: string | null;
  timezone: string | null;
}

/** REQUIRE_APPROVALS and ESCALATE need a count; the backend forces it null for AUTO_*. */
export const actionRequiresApprovals = (action: DeploymentRoutingAction): boolean =>
  action === 'REQUIRE_APPROVALS' || action === 'ESCALATE';

function toWireConditions(
  values: DeploymentRoutingPolicyFormValues,
): DeploymentRoutingConditionsInput {
  const hasTimeRange = values.start_time != null && values.end_time != null;
  return {
    environments: values.environments,
    providers: values.providers,
    min_risk_level: values.min_risk_level,
    version_globs: values.version_globs,
    days_of_week: values.days_of_week,
    start_time: hasTimeRange ? values.start_time : null,
    end_time: hasTimeRange ? values.end_time : null,
    timezone: hasTimeRange ? values.timezone : null,
  };
}

export function toCreateInput(
  values: DeploymentRoutingPolicyFormValues,
): CreateDeploymentRoutingPolicyInput {
  return {
    pipeline_id: values.pipeline_id,
    name: values.name,
    conditions: toWireConditions(values),
    action: values.action,
    required_approvals: actionRequiresApprovals(values.action) ? values.required_approvals : null,
    priority: values.priority,
    enabled: values.enabled,
  };
}

export function toUpdateInput(
  values: DeploymentRoutingPolicyFormValues,
): UpdateDeploymentRoutingPolicyInput {
  return {
    ...toCreateInput(values),
    clear_pipeline: values.pipeline_id == null,
  };
}

/** Compact one-line summary of a policy's conditions for the table. */
export function deploymentConditionsSummary(
  t: TFunction,
  conditions: DeploymentRoutingConditions,
): string {
  const parts: string[] = [];
  if (conditions.environments.length > 0) {
    parts.push(
      t('deploygov.routingPolicies.summary_environments', {
        environments: conditions.environments.join(', '),
      }),
    );
  }
  if (conditions.providers.length > 0) {
    parts.push(conditions.providers.map((p) => pipelineProviderLabel(t, p)).join(', '));
  }
  if (conditions.min_risk_level != null) {
    parts.push(
      t('deploygov.routingPolicies.summary_min_risk', {
        risk: riskLevelLabel(t, conditions.min_risk_level),
      }),
    );
  }
  if (conditions.version_globs.length > 0) {
    parts.push(conditions.version_globs.join(', '));
  }
  if (conditions.days_of_week.length > 0) {
    parts.push(
      [...conditions.days_of_week]
        .sort((a, b) => a - b)
        .map((d) => isoWeekdayLabel(t, d))
        .join(', '),
    );
  }
  if (conditions.start_time != null && conditions.end_time != null) {
    const start = normalizeTime(conditions.start_time);
    const end = normalizeTime(conditions.end_time);
    parts.push(
      conditions.timezone ? `${start}–${end} ${conditions.timezone}` : `${start}–${end}`,
    );
  }
  return parts.length > 0
    ? parts.join(' · ')
    : t('deploygov.routingPolicies.summary_match_all');
}
