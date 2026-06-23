import type { TFunction } from 'i18next';
import type {
  AccessGrantStatus,
  AiProvider,
  AuthProvider,
  BehaviorAnomalyStatus,
  BreakGlassEventStatus,
  ChannelType,
  ComparisonOperator,
  DataClassification,
  DbType,
  InvitationStatus,
  MaskingStrategy,
  OAuth2Provider,
  OptimizationType,
  QueryStatus,
  QueryTemplateChangeType,
  QueryType,
  RagStoreType,
  RiskLevel,
  Role,
  RoutingAction,
  RoutingConditionOperand,
  RowSecurityOperator,
  RowSecurityValueType,
  CommentStatus,
  SslMode,
  SubmissionReason,
  VotingStrategy,
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

export const ANOMALY_STATUSES: readonly BehaviorAnomalyStatus[] = [
  'OPEN',
  'ACKNOWLEDGED',
  'DISMISSED',
] as const;

export const anomalyStatusLabel = (t: TFunction, v: BehaviorAnomalyStatus): string =>
  t(`enums.behavior_anomaly_status.${v}` as const);

export const BREAK_GLASS_STATUSES: readonly BreakGlassEventStatus[] = [
  'PENDING_REVIEW',
  'REVIEWED',
] as const;

export const breakGlassStatusLabel = (t: TFunction, v: BreakGlassEventStatus): string =>
  t(`enums.break_glass_status.${v}` as const);

export const queryTypeLabel = (t: TFunction, v: QueryType): string =>
  t(`enums.query_type.${v}` as const);

export const queryTemplateChangeLabel = (t: TFunction, v: QueryTemplateChangeType): string =>
  t(`enums.query_template_change_type.${v}` as const);

export const OPTIMIZATION_TYPES: readonly OptimizationType[] = ['INDEX', 'REWRITE'] as const;

export const optimizationTypeLabel = (t: TFunction, v: OptimizationType): string =>
  t(`enums.optimization_type.${v}` as const);

export const submissionReasonLabel = (t: TFunction, v: SubmissionReason): string =>
  t(`enums.submission_reason.${v}` as const);

export const commentStatusLabel = (t: TFunction, v: CommentStatus): string =>
  t(`enums.comment_status.${v}` as const);

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

export const RAG_STORE_TYPES: readonly RagStoreType[] = ['PGVECTOR', 'QDRANT'] as const;

export const ragStoreTypeLabel = (t: TFunction, v: RagStoreType): string =>
  t(`enums.rag_store_type.${v}` as const);

export const VOTING_STRATEGIES: readonly VotingStrategy[] = [
  'WEIGHTED_AVERAGE',
  'MAX_RISK',
  'MAJORITY',
] as const;

export const votingStrategyLabel = (t: TFunction, v: VotingStrategy): string =>
  t(`enums.voting_strategy.${v}` as const);

// All providers are valid orchestration members (unlike embedding, which excludes Anthropic).
export const ORCHESTRATION_PROVIDERS: readonly AiProvider[] = [
  'OPENAI',
  'ANTHROPIC',
  'OLLAMA',
  'OPENAI_COMPATIBLE',
  'HUGGING_FACE',
] as const;

// Anthropic has no embeddings API, so it is excluded from embedding-provider choices.
export const EMBEDDING_PROVIDERS: readonly AiProvider[] = [
  'OPENAI',
  'OPENAI_COMPATIBLE',
  'HUGGING_FACE',
  'OLLAMA',
] as const;

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

export const ROW_SECURITY_OPERATORS: readonly RowSecurityOperator[] = [
  'EQUALS',
  'NOT_EQUALS',
  'LESS_THAN',
  'LESS_THAN_OR_EQUAL',
  'GREATER_THAN',
  'GREATER_THAN_OR_EQUAL',
  'IN',
  'NOT_IN',
] as const;

export const rowSecurityOperatorLabel = (t: TFunction, v: RowSecurityOperator): string =>
  t(`enums.row_security_operator.${v}` as const);

export const ROW_SECURITY_VALUE_TYPES: readonly RowSecurityValueType[] = [
  'VARIABLE',
  'LITERAL',
] as const;

export const rowSecurityValueTypeLabel = (t: TFunction, v: RowSecurityValueType): string =>
  t(`enums.row_security_value_type.${v}` as const);

export const DATA_CLASSIFICATIONS: readonly DataClassification[] = [
  'PII',
  'PCI',
  'PHI',
  'GDPR',
  'FINANCIAL',
  'SENSITIVE',
] as const;

export const dataClassificationLabel = (t: TFunction, v: DataClassification): string =>
  t(`enums.data_classification.${v}` as const);

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
  'source_ip',
  'user_agent',
  'time_since_last_approval',
  'cicd_origin',
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
