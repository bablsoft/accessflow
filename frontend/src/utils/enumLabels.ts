import type { TFunction } from 'i18next';
import type {
  AccessGrantStatus,
  AiProvider,
  AuthProvider,
  ChannelType,
  ComparisonOperator,
  DbType,
  InvitationStatus,
  MaskingStrategy,
  OAuth2Provider,
  QueryStatus,
  QueryType,
  RiskLevel,
  Role,
  RoutingAction,
  RoutingConditionOperand,
  SslMode,
  Weekday,
} from '@/types/api';

export const ACCESS_GRANT_STATUSES: readonly AccessGrantStatus[] = [
  'PENDING',
  'APPROVED',
  'REJECTED',
  'EXPIRED',
  'REVOKED',
  'CANCELLED',
] as const;

export const queryStatusLabel = (t: TFunction, v: QueryStatus): string =>
  t(`enums.query_status.${v}` as const);

export const accessGrantStatusLabel = (t: TFunction, v: AccessGrantStatus): string =>
  t(`enums.access_grant_status.${v}` as const);

export const queryTypeLabel = (t: TFunction, v: QueryType): string =>
  t(`enums.query_type.${v}` as const);

export const riskLevelLabel = (t: TFunction, v: RiskLevel): string =>
  t(`enums.risk_level.${v}` as const);

export const roleLabel = (t: TFunction, v: Role): string =>
  t(`enums.role.${v}` as const);

export const dbTypeLabel = (t: TFunction, v: DbType): string =>
  t(`enums.db_type.${v}` as const);

export const sslModeLabel = (t: TFunction, v: SslMode): string =>
  t(`enums.ssl_mode.${v}` as const);

export const channelTypeLabel = (t: TFunction, v: ChannelType): string =>
  t(`enums.channel_type.${v}` as const);

export const aiProviderLabel = (t: TFunction, v: AiProvider): string =>
  t(`enums.ai_provider.${v}` as const);

export const authProviderLabel = (t: TFunction, v: AuthProvider): string =>
  t(`enums.auth_provider.${v}` as const);

export const oauth2ProviderLabel = (t: TFunction, v: OAuth2Provider): string =>
  t(`enums.oauth2_provider.${v}` as const);

export const invitationStatusLabel = (t: TFunction, v: InvitationStatus): string =>
  t(`enums.invitation_status.${v}` as const);

export const MASKING_STRATEGIES: readonly MaskingStrategy[] = [
  'FULL',
  'PARTIAL',
  'HASH',
  'EMAIL',
  'FORMAT_PRESERVING',
] as const;

export const maskingStrategyLabel = (t: TFunction, v: MaskingStrategy): string =>
  t(`enums.masking_strategy.${v}` as const);

// ── Routing policies ──────────────────────────────────────────────────────────
export const QUERY_TYPES: readonly QueryType[] = [
  'SELECT',
  'INSERT',
  'UPDATE',
  'DELETE',
  'DDL',
] as const;

export const RISK_LEVELS: readonly RiskLevel[] = [
  'LOW',
  'MEDIUM',
  'HIGH',
  'CRITICAL',
] as const;

export const ROUTING_ROLES: readonly Role[] = [
  'READONLY',
  'ANALYST',
  'REVIEWER',
  'ADMIN',
] as const;

export const ROUTING_ACTIONS: readonly RoutingAction[] = [
  'AUTO_APPROVE',
  'AUTO_REJECT',
  'REQUIRE_APPROVALS',
  'ESCALATE',
] as const;

export const COMPARISON_OPERATORS: readonly ComparisonOperator[] = [
  'LT',
  'LTE',
  'GT',
  'GTE',
  'EQ',
] as const;

export const WEEKDAYS: readonly Weekday[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
] as const;

export const CONDITION_OPERANDS: readonly RoutingConditionOperand[] = [
  'query_type',
  'referenced_table',
  'risk_level',
  'risk_score',
  'requester_role',
  'requester_group',
  'time_of_day',
  'day_of_week',
  'has_where',
  'has_limit',
  'transactional',
] as const;

export const routingActionLabel = (t: TFunction, v: RoutingAction): string =>
  t(`enums.routing_action.${v}` as const);

export const comparisonOperatorLabel = (t: TFunction, v: ComparisonOperator): string =>
  t(`enums.comparison_operator.${v}` as const);

export const weekdayLabel = (t: TFunction, v: Weekday): string =>
  t(`enums.weekday.${v}` as const);

export const conditionOperandLabel = (t: TFunction, v: RoutingConditionOperand): string =>
  t(`enums.condition_operand.${v}` as const);

export interface EnumOption<V extends string> {
  value: V;
  label: string;
}

export function enumOptions<V extends string>(
  values: readonly V[],
  label: (t: TFunction, v: V) => string,
  t: TFunction,
): EnumOption<V>[] {
  return values.map((value) => ({ value, label: label(t, value) }));
}
