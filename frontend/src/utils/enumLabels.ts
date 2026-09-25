import type { TFunction } from 'i18next';
import type {
  AccessGrantStatus,
  SchemaChangePromotionStatus,
  SchemaChangeSetStatus,
  SchemaDriftBaseline,
  SchemaDriftFindingKind,
  SchemaDriftFindingStatus,
  ApiAuthMethod,
  ApiBodyType,
  ApiMaskingMatcherType,
  ApiVariableAlgorithm,
  ApiVariableEncoding,
  ApiVariableKind,
  ApiProtocol,
  DelegationScopeKind,
  PrincipalType,
  ReviewDelegationStatus,
  ServiceAccountDelegationStatus,
  ServiceAccountSource,
  SqlReviewRuleCategory,
  SqlReviewSeverity,
  ApiSchemaType,
  ErasureConditionOperator,
  ErasureStatus,
  LifecycleAction,
  LifecycleSubjectType,
  LifecycleTransform,
  Oauth2GrantType,
  Oauth2ClientAuth,
  AiProvider,
  AttestationCampaignScope,
  AttestationCampaignStatus,
  AttestationItemDecision,
  AttestationPendingDefault,
  AuditSinkType,
  AuthProvider,
  BehaviorAnomalyStatus,
  BreakGlassEventStatus,
  GrantResourceKind,
  GrantUsageRecommendation,
  JobCadenceType,
  JobExecutionStatus,
  ChannelType,
  ComparisonOperator,
  DataClassification,
  DatasourceEnvironment,
  DbType,
  InvitationStatus,
  ExportPolicyMode,
  MaskingStrategy,
  OAuth2Provider,
  OptimizationType,
  QueryStatus,
  QueryTemplateChangeType,
  QueryShape,
  QueryType,
  RagStoreType,
  RequestGroupItemStatus,
  RequestGroupStatus,
  RequestGroupTargetKind,
  ReviewDecisionType,
  RiskLevel,
  RoutingAction,
  RoutingConditionOperand,
  RowSecurityOperator,
  RowSecurityValueType,
  DiscoveryDetector,
  DeploymentOutcome,
  DeploymentRollbackReviewStatus,
  DiscoveryFindingStatus,
  CommentStatus,
  FreezeBehavior,
  PipelineProvider,
  SslMode,
  SubmissionReason,
  VotingStrategy,
  Weekday,
  StandingBypassKind,
  DatasourcePermissionSourceKind,
  AccessSourceKind,
  ApiDecisionStepKind,
  DecisionStepOutcome,
  DeploymentDecisionStepKind,
  EffectiveAccessTableScope,
  QueryDecisionStepKind,
  SimulatedAiOutcome,
  StatementCapability,
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

export const JOB_EXECUTION_STATUSES: readonly JobExecutionStatus[] = [
  'RUNNING',
  'SUCCESS',
  'FAILED',
] as const;

export const jobExecutionStatusLabel = (t: TFunction, v: JobExecutionStatus): string =>
  t(`enums.job_execution_status.${v}` as const);

export const jobCadenceTypeLabel = (t: TFunction, v: JobCadenceType): string =>
  t(`enums.job_cadence_type.${v}` as const);

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

export const reviewDecisionTypeLabel = (t: TFunction, v: ReviewDecisionType): string =>
  t(`enums.decision_type.${v}` as const);

export const commentStatusLabel = (t: TFunction, v: CommentStatus): string =>
  t(`enums.comment_status.${v}` as const);

export const riskLevelLabel = (t: TFunction, v: RiskLevel): string =>
  t(`enums.risk_level.${v}` as const);

/**
 * Role display label (AF-522). System role names resolve through `enums.role.<NAME>`;
 * custom role names have no translation key and render verbatim.
 */
export const roleLabel = (t: TFunction, v: string): string =>
  t(`enums.role.${v}`, { defaultValue: v });

export const dbTypeLabel = (t: TFunction, v: DbType): string =>
  t(`enums.db_type.${v}` as const);

export const sslModeLabel = (t: TFunction, v: SslMode): string =>
  t(`enums.ssl_mode.${v}` as const);

export const channelTypeLabel = (t: TFunction, v: ChannelType): string =>
  t(`enums.channel_type.${v}` as const);

export const AUDIT_SINK_TYPES: readonly AuditSinkType[] = [
  'SPLUNK_HEC',
  'SYSLOG_CEF',
  'HTTPS_BATCH',
  'S3_OBJECT_LOCK',
] as const;

export const auditSinkTypeLabel = (t: TFunction, v: AuditSinkType): string =>
  t(`enums.audit_sink_type.${v}` as const);

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

// Orchestration members drive chat completions, so every chat-capable provider qualifies — which
// excludes Voyage, an embeddings-only vendor. The backend rejects it with
// AI_CONFIG_ORCHESTRATION_INVALID.
export const ORCHESTRATION_PROVIDERS: readonly AiProvider[] = [
  'OPENAI',
  'ANTHROPIC',
  'OLLAMA',
  'OPENAI_COMPATIBLE',
  'HUGGING_FACE',
] as const;

// Anthropic has no embeddings API, so it is excluded from embedding-provider choices; Voyage is the
// mirror case — embeddings only, and absent from the chat tiles and ORCHESTRATION_PROVIDERS.
export const EMBEDDING_PROVIDERS: readonly AiProvider[] = [
  'OPENAI',
  'OPENAI_COMPATIBLE',
  'HUGGING_FACE',
  'OLLAMA',
  'VOYAGE',
] as const;

/**
 * Embedding providers that honour a requested vector length — OpenAI and its wire-compatible kin
 * take a `dimensions` request parameter, Voyage takes `output_dimension`. Ollama returns whatever
 * its model natively emits, so the backend rejects a length for it outright
 * (`error.ai_config.rag.embedding_dimensions_not_configurable`). Mirrors
 * `AiProviderCapabilities.supportsConfigurableDimensions`.
 */
export const DIMENSION_CAPABLE_PROVIDERS: readonly AiProvider[] = [
  'OPENAI',
  'OPENAI_COMPATIBLE',
  'HUGGING_FACE',
  'VOYAGE',
] as const;

/** The only vector lengths Voyage can emit; mirrors `AiProviderCapabilities.supportedDimensions`. */
export const VOYAGE_DIMENSIONS = [256, 512, 1024, 2048] as const;

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

export const EXPORT_POLICY_MODES: readonly ExportPolicyMode[] = [
  'ALLOW',
  'WATERMARK',
  'ROW_CAP',
  'DENY_CLASSIFIED',
] as const;

export const exportPolicyModeLabel = (t: TFunction, v: ExportPolicyMode): string =>
  t(`enums.export_policy_mode.${v}` as const);

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

export const API_MASKING_MATCHER_TYPES: readonly ApiMaskingMatcherType[] = [
  'SCHEMA_FIELD',
  'JSON_PATH',
  'XML_PATH',
  'REGEX',
] as const;

export const apiMaskingMatcherTypeLabel = (t: TFunction, v: ApiMaskingMatcherType): string =>
  t(`enums.api_masking_matcher_type.${v}` as const);

// ── API connector dynamic variables (AF-613) ─────────────────────────────────

export const API_VARIABLE_KINDS: readonly ApiVariableKind[] = [
  'CONSTANT',
  'UUID',
  'TIMESTAMP',
  'EPOCH_MILLIS',
  'RANDOM_HEX',
  'HASH',
  'HMAC',
  'ENCODE',
] as const;

export const apiVariableKindLabel = (t: TFunction, v: ApiVariableKind): string =>
  t(`enums.api_variable_kind.${v}` as const);

export const API_VARIABLE_ALGORITHMS: readonly ApiVariableAlgorithm[] = [
  'HMAC_SHA256',
  'HMAC_SHA512',
  'SHA256',
  'MD5',
] as const;

export const apiVariableAlgorithmLabel = (t: TFunction, v: ApiVariableAlgorithm): string =>
  t(`enums.api_variable_algorithm.${v}` as const);

export const API_VARIABLE_ENCODINGS: readonly ApiVariableEncoding[] = [
  'HEX',
  'BASE64',
  'BASE64URL',
] as const;

export const apiVariableEncodingLabel = (t: TFunction, v: ApiVariableEncoding): string =>
  t(`enums.api_variable_encoding.${v}` as const);

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
  'query_shape',
  'transactional',
  'source_ip',
  'user_agent',
  'time_since_last_approval',
  'cicd_origin',
  'estimated_rows',
  'scan_type',
] as const;

export const QUERY_SHAPES: readonly QueryShape[] = [
  'JOIN',
  'UNION',
  'SUBQUERY',
  'CTE',
  'GROUP_BY',
  'HAVING',
  'AGGREGATE',
  'WINDOW_FUNCTION',
] as const;

export const queryShapeLabel = (t: TFunction, v: QueryShape): string =>
  t(`enums.query_shape.${v}` as const);

export const routingActionLabel = (t: TFunction, v: RoutingAction): string =>
  t(`enums.routing_action.${v}` as const);

export const comparisonOperatorLabel = (t: TFunction, v: ComparisonOperator): string =>
  t(`enums.comparison_operator.${v}` as const);

export const weekdayLabel = (t: TFunction, v: Weekday): string =>
  t(`enums.weekday.${v}` as const);

export const conditionOperandLabel = (t: TFunction, v: RoutingConditionOperand): string =>
  t(`enums.condition_operand.${v}` as const);

// ── Attestation campaigns (AF-384) ─────────────────────────────────────────────
export const ATTESTATION_CAMPAIGN_STATUSES: readonly AttestationCampaignStatus[] = [
  'SCHEDULED',
  'OPEN',
  'CLOSED',
  'CANCELLED',
] as const;

export const attestationCampaignStatusLabel = (
  t: TFunction,
  v: AttestationCampaignStatus,
): string => t(`enums.attestation_campaign_status.${v}` as const);

export const ATTESTATION_CAMPAIGN_SCOPES: readonly AttestationCampaignScope[] = [
  'ORGANIZATION',
  'DATASOURCE',
] as const;

export const attestationCampaignScopeLabel = (
  t: TFunction,
  v: AttestationCampaignScope,
): string => t(`enums.attestation_campaign_scope.${v}` as const);

export const ATTESTATION_ITEM_DECISIONS: readonly AttestationItemDecision[] = [
  'PENDING',
  'CERTIFIED',
  'REVOKED',
] as const;

export const attestationItemDecisionLabel = (
  t: TFunction,
  v: AttestationItemDecision,
): string => t(`enums.attestation_item_decision.${v}` as const);

export const grantResourceKindLabel = (t: TFunction, v: GrantResourceKind): string =>
  t(`enums.grant_resource_kind.${v}` as const);

export const grantUsageRecommendationLabel = (
  t: TFunction,
  v: GrantUsageRecommendation,
): string => t(`enums.grant_usage_recommendation.${v}` as const);

export const GRANT_USAGE_RECOMMENDATIONS: readonly GrantUsageRecommendation[] = [
  'NEVER_USED',
  'STALE',
  'OVER_SCOPED',
  'ACTIVE',
  'INSUFFICIENT_DATA',
] as const;

export const GRANT_RESOURCE_KINDS: readonly GrantResourceKind[] = [
  'DATASOURCE',
  'API_CONNECTOR',
] as const;

// --- Privileged-access report (#968) ---

export const STANDING_BYPASS_KINDS: readonly StandingBypassKind[] = [
  'QUERY_ADMIN',
  'BREAK_GLASS',
] as const;

export const standingBypassKindLabel = (t: TFunction, v: StandingBypassKind): string =>
  t(`enums.standing_bypass_kind.${v}` as const);

export const permissionSourceKindLabel = (
  t: TFunction,
  v: DatasourcePermissionSourceKind,
): string => t(`enums.permission_source_kind.${v}` as const);

// --- Decision traces and the effective-access reverse index (#859, #967, #1066) ---

export const DECISION_STEP_OUTCOMES: readonly DecisionStepOutcome[] = [
  'ALLOW',
  'DENY',
  'MATCH',
  'NO_MATCH',
  'SKIP',
] as const;

export const decisionStepOutcomeLabel = (t: TFunction, v: DecisionStepOutcome): string =>
  t(`enums.decision_step_outcome.${v}` as const);

export const QUERY_DECISION_STEP_KINDS: readonly QueryDecisionStepKind[] = [
  'DATASOURCE_GATES',
  'QUOTA',
  'SQL_PARSE',
  'EFFECTIVE_PERMISSION',
  'SQL_REVIEW',
  'ROUTING_POLICIES',
  'GRANT_FAST_PATH',
  'REVIEW_PLAN',
  'ELIGIBLE_REVIEWERS',
  'ROW_SECURITY',
  'MASKING',
  'BREAK_GLASS',
] as const;

export const queryDecisionStepLabel = (t: TFunction, v: QueryDecisionStepKind): string =>
  t(`enums.query_decision_step.${v}` as const);

export const API_DECISION_STEP_KINDS: readonly ApiDecisionStepKind[] = [
  'CONNECTOR_GATES',
  'CALL_CLASSIFICATION',
  'SCHEMA_VALIDATION',
  'OPERATION_PERMISSION',
  'ROUTING_POLICIES',
  'REVIEW_REQUIREMENT',
  'ELIGIBLE_REVIEWERS',
  'RESPONSE_MASKING',
  'BREAK_GLASS',
] as const;

export const apiDecisionStepLabel = (t: TFunction, v: ApiDecisionStepKind): string =>
  t(`enums.api_decision_step.${v}` as const);

export const DEPLOYMENT_DECISION_STEP_KINDS: readonly DeploymentDecisionStepKind[] = [
  'PIPELINE_GATES',
  'TRIGGER_PERMISSION',
  'FREEZE_WINDOW',
  'ROUTING_POLICIES',
  'ENVIRONMENT_POLICY',
  'ELIGIBLE_REVIEWERS',
  'SCHEDULED_RELEASE',
  'GATE_RELEASABILITY',
  'BREAK_GLASS',
] as const;

export const deploymentDecisionStepLabel = (
  t: TFunction,
  v: DeploymentDecisionStepKind,
): string => t(`enums.deployment_decision_step.${v}` as const);

export const SIMULATED_AI_OUTCOMES: readonly SimulatedAiOutcome[] = [
  'SKIPPED',
  'COMPLETED',
  'FAILED',
] as const;

export const simulatedAiOutcomeLabel = (t: TFunction, v: SimulatedAiOutcome): string =>
  t(`enums.simulated_ai_outcome.${v}` as const);

export const STATEMENT_CAPABILITIES: readonly StatementCapability[] = [
  'READ',
  'WRITE',
  'DDL',
] as const;

export const statementCapabilityLabel = (t: TFunction, v: StatementCapability): string =>
  t(`enums.statement_capability.${v}` as const);

export const ACCESS_SOURCE_KINDS: readonly AccessSourceKind[] = [
  'DIRECT_PERMISSION',
  'GROUP_PERMISSION',
  'JIT_GRANT',
  'QUERY_ADMIN_BYPASS',
  'BREAK_GLASS',
] as const;

export const accessSourceKindLabel = (t: TFunction, v: AccessSourceKind): string =>
  t(`enums.access_source_kind.${v}` as const);

export const EFFECTIVE_ACCESS_TABLE_SCOPES: readonly EffectiveAccessTableScope[] = [
  'ALL_TABLES',
  'ALLOW_LISTED',
] as const;

export const effectiveAccessTableScopeLabel = (
  t: TFunction,
  v: EffectiveAccessTableScope,
): string => t(`enums.effective_access_table_scope.${v}` as const);

export const ATTESTATION_PENDING_DEFAULTS: readonly AttestationPendingDefault[] = [
  'KEEP',
  'REVOKE',
] as const;

export const attestationPendingDefaultLabel = (
  t: TFunction,
  v: AttestationPendingDefault,
): string => t(`enums.attestation_pending_default.${v}` as const);

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

// ── API Access Governance (AF-500) ──────────────────────────────────────────

export const API_PROTOCOLS: readonly ApiProtocol[] = ['REST', 'SOAP', 'GRAPHQL', 'GRPC'] as const;

export const apiProtocolLabel = (t: TFunction, v: ApiProtocol): string =>
  t(`enums.api_protocol.${v}` as const);

export const API_AUTH_METHODS: readonly ApiAuthMethod[] = [
  'NONE',
  'API_KEY',
  'BEARER_TOKEN',
  'BASIC',
  'OAUTH2_CLIENT_CREDENTIALS',
  'CUSTOM_HEADER',
  'MTLS',
] as const;

export const apiAuthMethodLabel = (t: TFunction, v: ApiAuthMethod): string =>
  t(`enums.api_auth_method.${v}` as const);

export const OAUTH2_GRANT_TYPES: readonly Oauth2GrantType[] = [
  'CLIENT_CREDENTIALS',
  'REFRESH_TOKEN',
  'PASSWORD',
] as const;

export const oauth2GrantTypeLabel = (t: TFunction, v: Oauth2GrantType): string =>
  t(`enums.oauth2_grant_type.${v}` as const);

export const OAUTH2_CLIENT_AUTHS: readonly Oauth2ClientAuth[] = [
  'CLIENT_SECRET_BASIC',
  'CLIENT_SECRET_POST',
] as const;

export const oauth2ClientAuthLabel = (t: TFunction, v: Oauth2ClientAuth): string =>
  t(`enums.oauth2_client_auth.${v}` as const);

export const API_SCHEMA_TYPES: readonly ApiSchemaType[] = [
  'OPENAPI',
  'WSDL',
  'GRAPHQL_SDL',
  'GRPC_PROTO',
  'POSTMAN_COLLECTION',
] as const;

export const apiSchemaTypeLabel = (t: TFunction, v: ApiSchemaType): string =>
  t(`enums.api_schema_type.${v}` as const);

export const API_BODY_TYPES: readonly ApiBodyType[] = [
  'NONE',
  'RAW',
  'FORM_DATA',
  'FORM_URLENCODED',
  'BINARY',
] as const;

export const apiBodyTypeLabel = (t: TFunction, v: ApiBodyType): string =>
  t(`enums.api_body_type.${v}` as const);

// ── Data Lifecycle Manager (AF-499) ──────────────────────────────────────────

export const LIFECYCLE_ACTIONS: readonly LifecycleAction[] = [
  'HARD_DELETE',
  'SOFT_DELETE',
  'PSEUDONYMIZE',
] as const;

export const lifecycleActionLabel = (t: TFunction, v: LifecycleAction): string =>
  t(`enums.lifecycle_action.${v}` as const);

export const LIFECYCLE_TRANSFORMS: readonly LifecycleTransform[] = [
  'SHA256_SALTED',
  'FORMAT_PRESERVING',
  'TOKENIZATION',
] as const;

export const lifecycleTransformLabel = (t: TFunction, v: LifecycleTransform): string =>
  t(`enums.lifecycle_transform.${v}` as const);

export const LIFECYCLE_SUBJECT_TYPES: readonly LifecycleSubjectType[] = [
  'USER_ID',
  'EMAIL',
  'CUSTOM',
] as const;

export const lifecycleSubjectTypeLabel = (t: TFunction, v: LifecycleSubjectType): string =>
  t(`enums.lifecycle_subject_type.${v}` as const);

export const ERASURE_STATUSES: readonly ErasureStatus[] = [
  'PENDING_SCOPE_AI',
  'PENDING_REVIEW',
  'APPROVED',
  'EXECUTED',
  'REJECTED',
  'FAILED',
  'CANCELLED',
] as const;

export const erasureStatusLabel = (t: TFunction, v: ErasureStatus): string =>
  t(`enums.erasure_status.${v}` as const);

// AF-519: structured erasure-condition operators (superset of row-security operators + IS_NULL).
export const ERASURE_CONDITION_OPERATORS: readonly ErasureConditionOperator[] = [
  'EQUALS',
  'NOT_EQUALS',
  'LESS_THAN',
  'LESS_THAN_OR_EQUAL',
  'GREATER_THAN',
  'GREATER_THAN_OR_EQUAL',
  'IN',
  'NOT_IN',
  'IS_NULL',
] as const;

export const erasureConditionOperatorLabel = (t: TFunction, v: ErasureConditionOperator): string =>
  t(`enums.erasure_condition_operator.${v}` as const);

// ── Request Groups: chaining & grouping (AF-501) ─────────────────────────────

export const REQUEST_GROUP_STATUSES: readonly RequestGroupStatus[] = [
  'DRAFT',
  'PENDING_AI',
  'PENDING_REVIEW',
  'APPROVED',
  'EXECUTING',
  'EXECUTED',
  'REJECTED',
  'TIMED_OUT',
  'PARTIALLY_EXECUTED',
  'FAILED',
  'CANCELLED',
] as const;

export const requestGroupStatusLabel = (t: TFunction, v: RequestGroupStatus): string =>
  t(`enums.request_group_status.${v}` as const);

export const REQUEST_GROUP_ITEM_STATUSES: readonly RequestGroupItemStatus[] = [
  'PENDING',
  'EXECUTED',
  'FAILED',
  'SKIPPED',
  'CANCELLED',
] as const;

export const requestGroupItemStatusLabel = (t: TFunction, v: RequestGroupItemStatus): string =>
  t(`enums.request_group_item_status.${v}` as const);

export const REQUEST_GROUP_TARGET_KINDS: readonly RequestGroupTargetKind[] = [
  'QUERY',
  'API_CALL',
] as const;

export const targetKindLabel = (t: TFunction, v: RequestGroupTargetKind): string =>
  t(`enums.request_group_target_kind.${v}` as const);

// ── Sensitive-data discovery (AF-623) ───────────────────────────────────────

export const DISCOVERY_DETECTORS: readonly DiscoveryDetector[] = [
  'EMAIL',
  'CREDIT_CARD',
  'SSN',
  'IBAN',
  'PHONE',
  'AI',
] as const;

export const discoveryDetectorLabel = (t: TFunction, v: DiscoveryDetector): string =>
  t(`enums.discovery_detector.${v}` as const);

export const DISCOVERY_FINDING_STATUSES: readonly DiscoveryFindingStatus[] = [
  'PENDING',
  'CONFIRMED',
  'DISMISSED',
  'STALE',
] as const;

export const discoveryFindingStatusLabel = (t: TFunction, v: DiscoveryFindingStatus): string =>
  t(`enums.discovery_finding_status.${v}` as const);

// ── Reviewer delegation (#622) ──────────────────────────────────────────────

export const DELEGATION_SCOPE_KINDS: readonly DelegationScopeKind[] = [
  'DATASOURCE',
  'API_CONNECTOR',
] as const;

export const delegationScopeKindLabel = (t: TFunction, v: DelegationScopeKind): string =>
  t(`enums.delegation_scope.${v}` as const);

export const DELEGATION_STATUSES: readonly ReviewDelegationStatus[] = [
  'SCHEDULED',
  'ACTIVE',
  'EXPIRED',
  'REVOKED',
] as const;

export const delegationStatusLabel = (t: TFunction, v: ReviewDelegationStatus): string =>
  t(`enums.delegation_status.${v}` as const);

// ── Deployment governance (#696, epic AF-682) ───────────────────────────────

export const PIPELINE_PROVIDERS: readonly PipelineProvider[] = [
  'GITHUB_ACTIONS',
  'GITLAB_CI',
  'AZURE_PIPELINES',
  'JENKINS',
  'CIRCLECI',
  'BITBUCKET_PIPELINES',
  'GENERIC',
] as const;

export const pipelineProviderLabel = (t: TFunction, v: PipelineProvider): string =>
  t(`enums.pipeline_provider.${v}` as const);

export const FREEZE_BEHAVIORS: readonly FreezeBehavior[] = ['HOLD', 'REJECT'] as const;

export const freezeBehaviorLabel = (t: TFunction, v: FreezeBehavior): string =>
  t(`enums.freeze_behavior.${v}` as const);

export const DEPLOYMENT_OUTCOMES: readonly DeploymentOutcome[] = [
  'SUCCEEDED',
  'FAILED',
  'ROLLED_BACK',
] as const;

export const deploymentOutcomeLabel = (t: TFunction, v: DeploymentOutcome): string =>
  t(`enums.deployment_outcome.${v}` as const);

export const DEPLOYMENT_ROLLBACK_REVIEW_STATUSES: readonly DeploymentRollbackReviewStatus[] = [
  'PENDING_REVIEW',
  'REVIEWED',
] as const;

export const deploymentRollbackReviewStatusLabel = (
  t: TFunction,
  v: DeploymentRollbackReviewStatus,
): string => t(`enums.deployment_rollback_review_status.${v}` as const);

/** ISO weekday order (Monday = 1 … Sunday = 7), as the freeze-window wire format uses. */
const ISO_WEEKDAYS: readonly Weekday[] = WEEKDAYS;

/** Label for an ISO weekday number 1–7; falls back to the raw number when out of range. */
export const isoWeekdayLabel = (t: TFunction, iso: number): string => {
  const name = ISO_WEEKDAYS[iso - 1];
  return name ? weekdayLabel(t, name) : String(iso);
};

// ── Deterministic SQL review (#865, epic #860) ─────────────────────────────────

export const SQL_REVIEW_SEVERITIES: readonly SqlReviewSeverity[] = ['OFF', 'WARN', 'BLOCK'] as const;

export const sqlReviewSeverityLabel = (t: TFunction, v: SqlReviewSeverity): string =>
  t(`enums.sql_review_severity.${v}` as const);

export const SQL_REVIEW_RULE_CATEGORIES: readonly SqlReviewRuleCategory[] = [
  'STATEMENT_SAFETY',
  'PERFORMANCE',
  'SCHEMA_CHANGE',
  'DATA_PROTECTION',
] as const;

export const sqlReviewRuleCategoryLabel = (t: TFunction, v: SqlReviewRuleCategory): string =>
  t(`enums.sql_review_rule_category.${v}` as const);

export const DATASOURCE_ENVIRONMENTS: readonly DatasourceEnvironment[] = [
  'DEVELOPMENT',
  'TEST',
  'STAGING',
  'PRODUCTION',
] as const;

export const datasourceEnvironmentLabel = (t: TFunction, v: DatasourceEnvironment): string =>
  t(`enums.datasource_environment.${v}` as const);

// ── Service accounts (epic #867, admin UI #875) ──────────────────────────────

export const PRINCIPAL_TYPES: readonly PrincipalType[] = ['HUMAN', 'SERVICE_ACCOUNT'] as const;

export const principalTypeLabel = (t: TFunction, v: PrincipalType): string =>
  t(`enums.principal_type.${v}` as const);

export const SERVICE_ACCOUNT_SOURCES: readonly ServiceAccountSource[] = ['UI', 'BOOTSTRAP'] as const;

export const serviceAccountSourceLabel = (t: TFunction, v: ServiceAccountSource): string =>
  t(`enums.service_account_source.${v}` as const);

export const SERVICE_ACCOUNT_DELEGATION_STATUSES: readonly ServiceAccountDelegationStatus[] = [
  'ACTIVE',
  'EXPIRED',
  'REVOKED',
] as const;

export const serviceAccountDelegationStatusLabel = (
  t: TFunction,
  v: ServiceAccountDelegationStatus,
): string => t(`enums.service_account_delegation_status.${v}` as const);

/**
 * One-line description of an MCP tool by wire name (#872). The catalog comes from the server, so
 * a tool this build does not know yet renders its name alone rather than a raw key.
 */
export const mcpToolDescription = (t: TFunction, toolName: string): string =>
  t(`enums.mcp_tool.${toolName}`, { defaultValue: '' });

// ── Schema change governance (epic #870, UI #883) ───────────────────────────

export const SCHEMA_CHANGE_SET_STATUSES: readonly SchemaChangeSetStatus[] = [
  'DRAFT',
  'ACTIVE',
  'ARCHIVED',
] as const;

/** A change-set statement's classification — `OTHER` is what the gate admits beyond DDL. */
export const statementQueryTypeLabel = (t: TFunction, v: QueryType | 'OTHER'): string =>
  t(`enums.query_type.${v}` as const);

export const schemaChangeSetStatusLabel = (t: TFunction, v: SchemaChangeSetStatus): string =>
  t(`enums.schema_change_set_status.${v}` as const);

export const SCHEMA_CHANGE_PROMOTION_STATUSES: readonly SchemaChangePromotionStatus[] = [
  'PENDING',
  'IN_REVIEW',
  'APPROVED',
  'APPLIED',
  'FAILED',
  'PARTIALLY_APPLIED',
  'CANCELLED',
] as const;

export const schemaChangePromotionStatusLabel = (
  t: TFunction,
  v: SchemaChangePromotionStatus,
): string => t(`enums.schema_change_promotion_status.${v}` as const);

export const SCHEMA_DRIFT_FINDING_KINDS: readonly SchemaDriftFindingKind[] = [
  'MISSING_IN_TARGET',
  'UNEXPECTED_IN_TARGET',
  'TYPE_MISMATCH',
  'NULLABILITY_MISMATCH',
  'PRIMARY_KEY_MISMATCH',
  'FOREIGN_KEY_MISMATCH',
] as const;

export const schemaDriftFindingKindLabel = (t: TFunction, v: SchemaDriftFindingKind): string =>
  t(`enums.schema_drift_finding_kind.${v}` as const);

export const SCHEMA_DRIFT_FINDING_STATUSES: readonly SchemaDriftFindingStatus[] = [
  'OPEN',
  'ACKNOWLEDGED',
  'RESOLVED',
] as const;

export const schemaDriftFindingStatusLabel = (
  t: TFunction,
  v: SchemaDriftFindingStatus,
): string => t(`enums.schema_drift_finding_status.${v}` as const);

export const SCHEMA_DRIFT_BASELINES: readonly SchemaDriftBaseline[] = [
  'PREVIOUS_ENVIRONMENT',
  'BASELINE_ENVIRONMENT',
  'PROMOTION_SNAPSHOT',
] as const;

export const schemaDriftBaselineLabel = (t: TFunction, v: SchemaDriftBaseline): string =>
  t(`enums.schema_drift_baseline.${v}` as const);
