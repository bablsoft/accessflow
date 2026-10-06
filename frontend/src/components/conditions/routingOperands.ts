import type { TFunction } from 'i18next';
import dayjs, { type Dayjs } from 'dayjs';
import type { RoutingConditionOperand } from '@/types/api';
import {
  CONDITION_OPERANDS,
  QUERY_SHAPES,
  QUERY_TYPES,
  RISK_LEVELS,
  WEEKDAYS,
  conditionOperandLabel,
  enumOptions,
  queryShapeLabel,
  queryTypeLabel,
  riskLevelLabel,
  weekdayLabel,
} from '@/utils/enumLabels';
import type { RoleOption } from '@/utils/roleOptions';
import {
  defaultRow,
  isCidr,
  minutesToTime,
  type RoutingConditionRow,
} from '@/pages/admin/routingPolicyForm';
import type { ConditionOperandSpec, ConditionValueEditorSpec } from './ConditionTreeEditor';

/** A routing row as the form holds it: the time-of-day window as a Dayjs pair, not minutes. */
export type RoutingFormConditionRow = Omit<RoutingConditionRow, 'time_start_min' | 'time_end_min'> & {
  time_range?: [Dayjs, Dayjs] | null;
};

function minutesToDayjs(minutes: number | undefined): Dayjs {
  return dayjs(minutesToTime(minutes), 'HH:mm');
}

function dayjsToMinutes(value: Dayjs): number {
  return value.hour() * 60 + value.minute();
}

export function toRoutingFormRow(row: RoutingConditionRow): RoutingFormConditionRow {
  const { time_start_min, time_end_min, ...rest } = row;
  if (row.operand === 'time_of_day') {
    return {
      ...rest,
      time_range: [minutesToDayjs(time_start_min), minutesToDayjs(time_end_min)],
    };
  }
  return { ...rest };
}

export function fromRoutingFormRow(row: RoutingFormConditionRow): RoutingConditionRow {
  const { time_range, ...rest } = row;
  if (row.operand === 'time_of_day' && time_range && time_range[0] && time_range[1]) {
    return {
      ...rest,
      time_start_min: dayjsToMinutes(time_range[0]),
      time_end_min: dayjsToMinutes(time_range[1]),
    };
  }
  return { ...rest };
}

function routingEditor(
  operand: RoutingConditionOperand,
  t: TFunction,
  groups: readonly { id: string; name: string }[],
  roleOptions: RoleOption[],
): ConditionValueEditorSpec {
  switch (operand) {
    case 'query_type':
      return { kind: 'multi', field: 'query_types', options: enumOptions(QUERY_TYPES, queryTypeLabel, t) };
    case 'referenced_table':
      return {
        kind: 'tags',
        field: 'table_globs',
        separators: [',', ' '],
        placeholder: t('admin.routing_policies.table_globs_placeholder'),
      };
    case 'risk_level':
      return { kind: 'multi', field: 'risk_levels', options: enumOptions(RISK_LEVELS, riskLevelLabel, t) };
    case 'risk_score':
      return {
        kind: 'comparison',
        operatorField: 'score_operator',
        valueField: 'score_value',
        input: 'number',
        min: 0,
        max: 100,
      };
    case 'requester_role':
      return { kind: 'multi', field: 'roles', options: roleOptions };
    case 'requester_group':
      return {
        kind: 'multi',
        field: 'group_ids',
        placeholder: t('admin.routing_policies.groups_placeholder'),
        options: groups.map((g) => ({ value: g.id, label: g.name })),
      };
    case 'time_of_day':
      return { kind: 'time-range', field: 'time_range' };
    case 'day_of_week':
      return { kind: 'multi', field: 'weekdays', options: enumOptions(WEEKDAYS, weekdayLabel, t) };
    case 'query_shape':
      return {
        kind: 'multi',
        field: 'shapes',
        ariaLabel: t('admin.routing_policies.query_shapes_label'),
        options: enumOptions(QUERY_SHAPES, queryShapeLabel, t),
      };
    case 'has_where':
    case 'has_limit':
    case 'transactional':
      return { kind: 'bool', field: 'bool_value', label: t('admin.routing_policies.bool_present_label') };
    case 'source_ip':
      return {
        kind: 'tags',
        field: 'cidrs',
        separators: [',', ' '],
        placeholder: t('admin.routing_policies.cidrs_placeholder'),
        validate: (values) => {
          const bad = values.filter((v) => !isCidr(v));
          return bad.length ? t('admin.routing_policies.cidr_invalid', { value: bad.join(', ') }) : null;
        },
      };
    case 'user_agent':
      return {
        kind: 'tags',
        field: 'ua_patterns',
        separators: [','],
        placeholder: t('admin.routing_policies.user_agent_placeholder'),
      };
    case 'time_since_last_approval':
      return {
        kind: 'comparison',
        operatorField: 'tsla_operator',
        valueField: 'tsla_minutes',
        input: 'number',
        min: 0,
        suffix: t('admin.routing_policies.minutes_suffix'),
      };
    case 'cicd_origin':
      return { kind: 'bool', field: 'bool_value', label: t('admin.routing_policies.cicd_present_label') };
    case 'estimated_rows':
      return {
        kind: 'comparison',
        operatorField: 'est_operator',
        valueField: 'est_value',
        input: 'number',
        min: 0,
      };
    case 'estimated_bytes_scanned':
      return {
        kind: 'comparison',
        operatorField: 'bytes_operator',
        valueField: 'bytes_value',
        input: 'bytes',
        min: 0,
        ariaLabel: t('admin.routing_policies.bytes_value_label'),
      };
    case 'data_budget_used_percent':
      return {
        kind: 'comparison',
        operatorField: 'budget_operator',
        valueField: 'budget_percent',
        input: 'number',
        min: 0,
        precision: 0,
        suffix: '%',
        ariaLabel: t('admin.routing_policies.budget_percent_label'),
      };
    case 'scan_type':
      return {
        kind: 'tags',
        field: 'scan_patterns',
        separators: [','],
        placeholder: t('admin.routing_policies.scan_type_placeholder'),
      };
  }
}

/** The full routing operand set for the shared condition editor. */
export function routingOperandSpecs(
  t: TFunction,
  groups: readonly { id: string; name: string }[],
  roleOptions: RoleOption[],
): ConditionOperandSpec<RoutingConditionOperand>[] {
  return CONDITION_OPERANDS.map((operand) => ({
    value: operand,
    label: conditionOperandLabel(t, operand),
    editor: routingEditor(operand, t, groups, roleOptions),
    defaultRow: () => toRoutingFormRow(defaultRow(operand)),
  }));
}
