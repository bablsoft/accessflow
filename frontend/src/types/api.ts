export type Role = 'READONLY' | 'ANALYST' | 'REVIEWER' | 'ADMIN';
export type AuthProvider = 'LOCAL' | 'SAML' | 'OAUTH2';
export type OAuth2Provider =
  | 'GOOGLE'
  | 'GITHUB'
  | 'MICROSOFT'
  | 'GITLAB'
  | 'OIDC'
  | 'GITHUB_ENTERPRISE'
  | 'GITLAB_ENTERPRISE';
export type DbType =
  | 'POSTGRESQL'
  | 'MYSQL'
  | 'MARIADB'
  | 'ORACLE'
  | 'MSSQL'
  | 'CUSTOM'
  | 'MONGODB'
  | 'COUCHBASE'
  | 'REDIS'
  | 'CASSANDRA'
  | 'SCYLLADB'
  | 'ELASTICSEARCH'
  | 'OPENSEARCH'
  | 'DYNAMODB'
  | 'NEO4J';
/**
 * Connector family. RELATIONAL is the SQL (JDBC) umbrella; every other value belongs to the
 * NoSQL umbrella of engine-managed (native, non-JDBC) connectors.
 */
export type ConnectorCategory =
  | 'RELATIONAL'
  | 'DOCUMENT'
  | 'KEY_VALUE'
  | 'WIDE_COLUMN'
  | 'SEARCH'
  | 'GRAPH';
export type SslMode = 'DISABLE' | 'REQUIRE' | 'VERIFY_CA' | 'VERIFY_FULL';
export type MaskingStrategy = 'FULL' | 'PARTIAL' | 'HASH' | 'EMAIL' | 'FORMAT_PRESERVING';
export type QueryStatus =
  | 'PENDING_AI'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'EXECUTED'
  | 'REJECTED'
  | 'TIMED_OUT'
  | 'FAILED'
  | 'CANCELLED';
export type QueryType = 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE' | 'DDL';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type RoutingAction =
  | 'AUTO_APPROVE'
  | 'AUTO_REJECT'
  | 'REQUIRE_APPROVALS'
  | 'ESCALATE';
export type ComparisonOperator = 'LT' | 'LTE' | 'GT' | 'GTE' | 'EQ';
/** Leaf condition operands the guided routing-policy builder exposes. */
export type RoutingConditionOperand =
  | 'query_type'
  | 'referenced_table'
  | 'risk_level'
  | 'risk_score'
  | 'requester_role'
  | 'requester_group'
  | 'time_of_day'
  | 'day_of_week'
  | 'has_where'
  | 'has_limit'
  | 'transactional'
  | 'source_ip'
  | 'user_agent'
  | 'time_since_last_approval'
  | 'cicd_origin';
export type Weekday =
  | 'MONDAY'
  | 'TUESDAY'
  | 'WEDNESDAY'
  | 'THURSDAY'
  | 'FRIDAY'
  | 'SATURDAY'
  | 'SUNDAY';
export type IssueSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type ChannelType =
  | 'EMAIL'
  | 'SLACK'
  | 'WEBHOOK'
  | 'DISCORD'
  | 'TELEGRAM'
  | 'MS_TEAMS'
  | 'PAGERDUTY';
export type AiProvider =
  | 'OPENAI'
  | 'ANTHROPIC'
  | 'OLLAMA'
  | 'OPENAI_COMPATIBLE'
  | 'HUGGING_FACE';

export type RagStoreType = 'PGVECTOR' | 'QDRANT';

export interface User {
  id: string;
  email: string;
  display_name: string;
  role: Role;
  auth_provider: AuthProvider;
  active: boolean;
  totp_enabled: boolean;
  last_login_at: string | null;
  preferred_language: string | null;
  created_at: string;
}

export interface MeProfile {
  id: string;
  email: string;
  display_name: string;
  role: Role;
  auth_provider: AuthProvider;
  totp_enabled: boolean;
  platform_admin: boolean;
  preferred_language: string | null;
}

/** Multi-tenant organization (AF-456). Quota fields are null when unlimited. */
export interface Organization {
  id: string;
  name: string;
  slug: string;
  disabled: boolean;
  max_datasources: number | null;
  max_users: number | null;
  max_queries_per_day: number | null;
  created_at: string;
  updated_at: string;
}

export interface OrganizationUsage {
  organization_id: string;
  datasource_count: number;
  max_datasources: number | null;
  user_count: number;
  max_users: number | null;
  queries_last_24h: number;
  max_queries_per_day: number | null;
}

export interface OrganizationPage {
  content: Organization[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export interface CreateOrganizationInput {
  name: string;
  slug?: string | null;
  max_datasources?: number | null;
  max_users?: number | null;
  max_queries_per_day?: number | null;
}

export interface UpdateOrganizationInput {
  name?: string | null;
  max_datasources?: number | null;
  max_users?: number | null;
  max_queries_per_day?: number | null;
}

export interface UpdateProfileInput {
  display_name: string;
}

export interface ChangePasswordInput {
  current_password: string;
  new_password: string;
}

export interface TotpEnrollment {
  secret: string;
  otpauth_url: string;
  qr_data_uri: string;
}

export interface ConfirmTotpInput {
  code: string;
}

export interface TotpConfirmationResponse {
  backup_codes: string[];
}

export interface DisableTotpInput {
  current_password: string;
}

export interface LocalizationConfig {
  organization_id: string;
  available_languages: string[];
  default_language: string;
  ai_review_language: string;
}

export interface UpdateLocalizationConfigInput {
  available_languages: string[];
  default_language: string;
  ai_review_language: string;
}

export interface MeLocalization {
  available_languages: string[];
  default_language: string;
  current_language: string;
}

export interface PublicLocalizationConfig {
  available_languages: string[];
  default_language: string;
}

export interface PageEnvelope<T> {
  content: T[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export type UserPage = PageEnvelope<User>;

export interface CreateUserInput {
  email: string;
  password: string;
  display_name?: string | null;
  role: Role;
}

export interface UpdateUserInput {
  role?: Role;
  active?: boolean;
  display_name?: string | null;
  attributes?: Record<string, string>;
}

export interface UserAttributes {
  attributes: Record<string, string>;
}

export interface SetupProgress {
  datasources_configured: boolean;
  review_plans_configured: boolean;
  ai_provider_configured: boolean;
  completed_steps: number;
  total_steps: number;
  complete: boolean;
}

export interface AiConfig {
  id: string;
  organization_id: string;
  name: string;
  provider: AiProvider;
  model: string;
  endpoint: string | null;
  api_key: string | null;
  timeout_ms: number;
  max_prompt_tokens: number;
  max_completion_tokens: number;
  system_prompt_template: string | null;
  langfuse_prompt_name: string | null;
  langfuse_prompt_label: string | null;
  rag_enabled: boolean;
  rag_store_type: RagStoreType | null;
  rag_top_k: number;
  rag_similarity_threshold: number;
  rag_endpoint: string | null;
  rag_collection: string | null;
  rag_api_key: string | null;
  embedding_provider: AiProvider | null;
  embedding_model: string | null;
  embedding_endpoint: string | null;
  embedding_api_key: string | null;
  in_use_count: number;
  created_at: string;
  updated_at: string;
}

export interface CreateAiConfigInput {
  name: string;
  provider: AiProvider;
  model: string;
  endpoint?: string | null;
  api_key?: string | null;
  timeout_ms?: number;
  max_prompt_tokens?: number;
  max_completion_tokens?: number;
  system_prompt_template?: string | null;
  langfuse_prompt_name?: string | null;
  langfuse_prompt_label?: string | null;
  rag_enabled?: boolean;
  rag_store_type?: RagStoreType | null;
  rag_top_k?: number;
  rag_similarity_threshold?: number;
  rag_endpoint?: string | null;
  rag_collection?: string | null;
  rag_api_key?: string | null;
  embedding_provider?: AiProvider | null;
  embedding_model?: string | null;
  embedding_endpoint?: string | null;
  embedding_api_key?: string | null;
}

export interface UpdateAiConfigInput {
  name?: string;
  provider?: AiProvider;
  model?: string;
  endpoint?: string | null;
  api_key?: string | null;
  timeout_ms?: number;
  max_prompt_tokens?: number;
  max_completion_tokens?: number;
  system_prompt_template?: string | null;
  langfuse_prompt_name?: string | null;
  langfuse_prompt_label?: string | null;
  rag_enabled?: boolean;
  rag_store_type?: RagStoreType | null;
  rag_top_k?: number;
  rag_similarity_threshold?: number;
  rag_endpoint?: string | null;
  rag_collection?: string | null;
  rag_api_key?: string | null;
  embedding_provider?: AiProvider | null;
  embedding_model?: string | null;
  embedding_endpoint?: string | null;
  embedding_api_key?: string | null;
}

export interface TestAiConfigResult {
  status: 'OK' | 'ERROR';
  detail: string;
}

export interface RagTestResult {
  status: 'OK' | 'ERROR';
  detail: string;
  embedding_dimensions: number | null;
}

export interface RagCapabilities {
  pgvector_available: boolean;
}

export interface DefaultAiPromptResult {
  template: string;
}

export interface AiConfigInUseError {
  error: 'AI_CONFIG_IN_USE';
  boundDatasources: Array<{ id: string; name: string }>;
}

export interface KnowledgeDocument {
  id: string;
  ai_config_id: string;
  title: string;
  char_count: number;
  chunk_count: number;
  status: string;
  error_message: string | null;
  created_at: string;
  updated_at: string;
}

export interface CreateKnowledgeDocumentInput {
  title: string;
  content: string;
}

export interface SamlConfig {
  id: string | null;
  organization_id: string;
  idp_metadata_url: string | null;
  idp_entity_id: string | null;
  sp_entity_id: string | null;
  acs_url: string | null;
  slo_url: string | null;
  signing_cert_pem: string | null;
  attr_email: string;
  attr_display_name: string;
  attr_role: string | null;
  attr_groups: string | null;
  group_mappings: Record<string, string>;
  default_role: Role;
  active: boolean;
  created_at: string;
  updated_at: string;
}

export interface UpdateSamlConfigInput {
  idp_metadata_url?: string | null;
  idp_entity_id?: string | null;
  sp_entity_id?: string | null;
  acs_url?: string | null;
  slo_url?: string | null;
  signing_cert_pem?: string | null;
  attr_email?: string;
  attr_display_name?: string;
  attr_role?: string | null;
  attr_groups?: string | null;
  group_mappings?: Record<string, string> | null;
  default_role?: Role;
  active?: boolean;
}

export interface LangfuseConfig {
  id: string | null;
  organization_id: string;
  enabled: boolean;
  host: string | null;
  public_key: string | null;
  secret_key: string | null;
  tracing_enabled: boolean;
  prompt_management_enabled: boolean;
  created_at: string;
  updated_at: string;
}

export interface UpdateLangfuseConfigInput {
  enabled?: boolean;
  host?: string | null;
  public_key?: string | null;
  secret_key?: string | null;
  tracing_enabled?: boolean;
  prompt_management_enabled?: boolean;
}

export interface LangfuseConfigTestResult {
  status: 'OK' | 'ERROR';
  message: string;
}

export interface SlackAppConfig {
  id: string;
  organization_id: string;
  app_id: string;
  default_channel_id: string;
  active: boolean;
  has_bot_token: boolean;
  has_signing_secret: boolean;
  created_at: string;
  updated_at: string;
}

export interface UpsertSlackAppConfigInput {
  app_id: string;
  default_channel_id: string;
  bot_token?: string;
  signing_secret?: string;
  active?: boolean;
}

export interface TestSlackResult {
  status: 'OK' | 'ERROR';
  detail: string;
}

export interface SlackLinkCode {
  code: string;
  expires_at: string;
}

export interface SlackLinkStatus {
  linked: boolean;
  slack_user_id: string | null;
}

export interface OAuth2Config {
  id: string | null;
  organization_id: string;
  provider: OAuth2Provider;
  client_id: string | null;
  client_secret: string | null;
  scopes_override: string | null;
  tenant_id: string | null;
  display_name: string | null;
  authorization_uri: string | null;
  token_uri: string | null;
  user_info_uri: string | null;
  jwk_set_uri: string | null;
  issuer_uri: string | null;
  user_name_attribute: string | null;
  email_attribute: string | null;
  email_verified_attribute: string | null;
  display_name_attribute: string | null;
  groups_attribute: string | null;
  base_url: string | null;
  allowed_organizations: string[] | null;
  allowed_email_domains: string[] | null;
  group_mappings: Record<string, string>;
  default_role: Role;
  active: boolean;
  created_at: string;
  updated_at: string;
}

export interface UpdateOAuth2ConfigInput {
  client_id?: string | null;
  client_secret?: string | null;
  scopes_override?: string | null;
  tenant_id?: string | null;
  display_name?: string | null;
  authorization_uri?: string | null;
  token_uri?: string | null;
  user_info_uri?: string | null;
  jwk_set_uri?: string | null;
  issuer_uri?: string | null;
  user_name_attribute?: string | null;
  email_attribute?: string | null;
  email_verified_attribute?: string | null;
  display_name_attribute?: string | null;
  groups_attribute?: string | null;
  base_url?: string | null;
  allowed_organizations?: string[] | null;
  allowed_email_domains?: string[] | null;
  group_mappings?: Record<string, string> | null;
  default_role: Role;
  active: boolean;
}

export interface OAuth2ProviderSummary {
  provider: OAuth2Provider;
  display_name: string;
}

export interface Datasource {
  id: string;
  organization_id: string;
  name: string;
  db_type: DbType;
  host: string | null;
  port: number | null;
  database_name: string | null;
  username: string;
  ssl_mode: SslMode;
  connection_pool_size: number;
  max_rows_per_query: number;
  require_review_reads: boolean;
  require_review_writes: boolean;
  review_plan_id: string | null;
  ai_analysis_enabled: boolean;
  ai_config_id: string | null;
  text_to_sql_enabled: boolean;
  custom_driver_id: string | null;
  connector_id: string | null;
  jdbc_url_override: string | null;
  read_replica_jdbc_url: string | null;
  read_replica_username: string | null;
  local_datacenter: string | null;
  active: boolean;
  created_at: string;
}

export interface ConnectionTestResult {
  ok: boolean;
  latency_ms: number;
  message: string | null;
}

export interface CreateDatasourceInput {
  name: string;
  db_type: DbType;
  host?: string | null;
  port?: number | null;
  database_name?: string | null;
  username: string;
  password: string;
  ssl_mode: SslMode;
  connection_pool_size?: number;
  max_rows_per_query?: number;
  require_review_reads?: boolean;
  require_review_writes?: boolean;
  review_plan_id?: string | null;
  ai_analysis_enabled?: boolean;
  ai_config_id?: string | null;
  text_to_sql_enabled?: boolean;
  custom_driver_id?: string | null;
  connector_id?: string | null;
  jdbc_url_override?: string | null;
  read_replica_jdbc_url?: string | null;
  read_replica_username?: string | null;
  read_replica_password?: string | null;
  local_datacenter?: string | null;
  api_key?: string | null;
}

export interface UpdateDatasourceInput {
  name?: string;
  host?: string;
  port?: number;
  database_name?: string;
  username?: string;
  password?: string;
  ssl_mode?: SslMode;
  connection_pool_size?: number;
  max_rows_per_query?: number;
  require_review_reads?: boolean;
  require_review_writes?: boolean;
  review_plan_id?: string | null;
  ai_analysis_enabled?: boolean;
  ai_config_id?: string | null;
  text_to_sql_enabled?: boolean;
  clear_ai_config?: boolean;
  jdbc_url_override?: string | null;
  read_replica_jdbc_url?: string | null;
  read_replica_username?: string | null;
  read_replica_password?: string | null;
  local_datacenter?: string | null;
  api_key?: string | null;
  active?: boolean;
}

export interface CreatePermissionInput {
  user_id: string;
  can_read?: boolean;
  can_write?: boolean;
  can_ddl?: boolean;
  row_limit_override?: number | null;
  allowed_schemas?: string[] | null;
  allowed_tables?: string[] | null;
  restricted_columns?: string[] | null;
  expires_at?: string | null;
}

export type DriverStatus = 'READY' | 'AVAILABLE' | 'UNAVAILABLE';

export type DriverSource = 'bundled' | 'uploaded' | 'connector';

export interface DatasourceTypeOption {
  code: DbType;
  category: ConnectorCategory;
  display_name: string;
  icon_url: string;
  default_port: number;
  default_ssl_mode: SslMode;
  jdbc_url_template: string;
  driver_status: DriverStatus;
  bundled: boolean;
  source: DriverSource;
  custom_driver_id: string | null;
  vendor_name: string | null;
  driver_class: string | null;
  connector_id: string | null;
  description: string | null;
  documentation_url: string | null;
}

export interface DatasourceTypesResponse {
  types: DatasourceTypeOption[];
}

export interface Connector {
  id: string;
  db_type: DbType;
  category: ConnectorCategory;
  name: string;
  icon_url: string;
  vendor: string | null;
  description: string | null;
  documentation_url: string | null;
  default_port: number;
  default_ssl_mode: SslMode;
  jdbc_url_template: string;
  driver_class: string | null;
  driver_status: DriverStatus;
  bundled: boolean;
}

export interface ConnectorListResponse {
  connectors: Connector[];
}

export interface CustomDriver {
  id: string;
  organization_id: string;
  vendor_name: string;
  target_db_type: DbType;
  driver_class: string;
  jar_filename: string;
  jar_sha256: string;
  jar_size_bytes: number;
  uploaded_by_user_id: string;
  uploaded_by_display_name: string;
  created_at: string;
}

export interface CustomDriverListResponse {
  drivers: CustomDriver[];
}

export interface CustomDriverUploadInput {
  jar: File;
  vendor_name: string;
  target_db_type: DbType;
  driver_class: string;
  expected_sha256: string;
}

export type DatasourcePage = PaginatedResponse<Datasource>;

/**
 * Server-aligned review plan shape returned by `GET /api/v1/review-plans` and friends.
 * Field names match the backend snake_case JSON contract.
 */
export interface ReviewPlanApprover {
  user_id: string | null;
  role: 'ADMIN' | 'REVIEWER' | null;
  stage: number;
}

export interface ReviewPlan {
  id: string;
  organization_id: string;
  name: string;
  description: string | null;
  requires_ai_review: boolean;
  requires_human_approval: boolean;
  min_approvals_required: number;
  approval_timeout_hours: number;
  auto_approve_reads: boolean;
  notify_channels: string[];
  approvers: ReviewPlanApprover[];
  created_at: string;
}

export interface ReviewPlanWriteRequest {
  name?: string;
  description?: string | null;
  requires_ai_review?: boolean;
  requires_human_approval?: boolean;
  min_approvals_required?: number;
  approval_timeout_hours?: number;
  auto_approve_reads?: boolean;
  notify_channels?: string[];
  approvers?: ReviewPlanApprover[];
}

export interface ReviewPlanTemplateApprover {
  role: 'ADMIN' | 'REVIEWER';
  stage: number;
}

export interface ReviewPlanTemplateDefaults {
  requires_ai_review: boolean;
  requires_human_approval: boolean;
  min_approvals_required: number;
  approval_timeout_hours: number;
  auto_approve_reads: boolean;
  approvers: ReviewPlanTemplateApprover[];
}

export interface ReviewPlanTemplate {
  key: string;
  name: string;
  description: string;
  defaults: ReviewPlanTemplateDefaults;
}

export interface PendingReviewItem {
  id: string;
  datasource: { id: string; name: string };
  submitted_by: { id: string; email: string };
  sql_text: string;
  query_type: QueryType;
  justification: string;
  ai_analysis: {
    id: string;
    risk_level: RiskLevel;
    risk_score: number;
    summary: string;
  } | null;
  current_stage: number;
  created_at: string;
}

export interface PendingReviewsPage {
  content: PendingReviewItem[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export type ReviewDecisionType = 'APPROVED' | 'REJECTED' | 'REQUESTED_CHANGES';

export interface ReviewDecisionResult {
  query_request_id: string;
  decision_id: string;
  decision: ReviewDecisionType;
  resulting_status: QueryStatus;
  idempotent_replay: boolean;
}

export type BulkReviewRowStatus = 'SUCCESS' | 'FORBIDDEN' | 'INVALID_STATE' | 'NOT_FOUND';

export interface BulkReviewRowResult {
  query_request_id: string;
  status: BulkReviewRowStatus;
  decision?: ReviewDecisionType;
  resulting_status?: QueryStatus;
  decision_id?: string;
  idempotent_replay?: boolean;
  error?: string;
  error_code?: string;
}

export interface BulkReviewDecisionResponse {
  results: BulkReviewRowResult[];
}

// --- Access requests (JIT time-bound access) ---

export type AccessGrantStatus =
  | 'PENDING'
  | 'APPROVED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'REVOKED'
  | 'CANCELLED';

export interface AccessRequest {
  id: string;
  datasource_id: string;
  datasource_name: string | null;
  requester_id: string;
  requester_email: string | null;
  can_read: boolean;
  can_write: boolean;
  can_ddl: boolean;
  allowed_schemas: string[] | null;
  allowed_tables: string[] | null;
  requested_duration: string;
  justification: string;
  status: AccessGrantStatus;
  expires_at: string | null;
  granted_permission_id: string | null;
  created_at: string;
  updated_at: string;
}

export type AccessRequestPage = PageEnvelope<AccessRequest>;

export interface SubmitAccessRequestInput {
  datasource_id: string;
  can_read: boolean;
  can_write: boolean;
  can_ddl: boolean;
  allowed_schemas?: string[] | null;
  allowed_tables?: string[] | null;
  requested_duration: string;
  justification: string;
}

export interface RequestableDatasource {
  id: string;
  name: string;
}

export interface RequestableSchemaNamespace {
  name: string;
  tables: string[];
}

export interface RequestableDatasourceSchema {
  schemas: RequestableSchemaNamespace[];
}

export interface PendingAccessRequestItem {
  id: string;
  datasource: { id: string; name: string | null };
  requested_by: { id: string; email: string | null };
  can_read: boolean;
  can_write: boolean;
  can_ddl: boolean;
  allowed_schemas: string[] | null;
  allowed_tables: string[] | null;
  requested_duration: string;
  justification: string;
  current_stage: number;
  created_at: string;
}

export type PendingAccessRequestsPage = PageEnvelope<PendingAccessRequestItem>;

export interface AccessDecisionResult {
  access_request_id: string;
  decision_id: string | null;
  decision: ReviewDecisionType;
  resulting_status: AccessGrantStatus;
  idempotent_replay: boolean;
}

export interface AccessRevocationResult {
  access_request_id: string;
  resulting_status: AccessGrantStatus;
  no_op: boolean;
}

export interface AiIssue {
  severity: IssueSeverity;
  category: string;
  message: string;
  suggestion: string;
  line?: number;
}

export type OptimizationType = 'INDEX' | 'REWRITE';

export interface OptimizationSuggestion {
  type: OptimizationType;
  title: string;
  rationale: string;
  /** A concrete, dialect-aware statement (index DDL or rewritten query) ready to apply as a draft. */
  sql: string;
}

export type SubmissionReason = 'USER_SUBMITTED' | 'AI_SUGGESTION';

export interface AiAnalysis {
  risk_level: RiskLevel;
  risk_score: number;
  summary: string;
  issues: AiIssue[];
  affects_rows?: number;
  prompt_tokens?: number;
  completion_tokens?: number;
  optimizations?: OptimizationSuggestion[];
}

/** One node of a dry-run execution plan tree (AF-445). */
export interface QueryPlanNode {
  operation: string;
  target?: string | null;
  estimated_rows?: number | null;
  estimated_cost?: number | null;
  detail?: string | null;
  children: QueryPlanNode[];
}

/** Result of `POST /queries/dry-run` — a non-committing EXPLAIN plan + estimated impact (AF-445). */
export interface QueryDryRunResult {
  supported: boolean;
  engine_id: string;
  query_type?: QueryType | null;
  estimated_rows?: number | null;
  plan?: QueryPlanNode | null;
  raw_plan?: string | null;
  /** Localized reason when `supported` is false (engine has no plan concept). */
  unsupported_reason?: string | null;
  duration_ms: number;
}

export interface GeneratedSql {
  sql: string;
  ai_provider: AiProvider;
  ai_model: string;
  prompt_tokens: number;
  completion_tokens: number;
  /** Editor syntax id for the draft (matches engineModes ids: sql/shell/json/cli/cql/…). */
  syntax: string;
}

export interface QueryRequest {
  id: string;
  datasource_id: string;
  datasource_name: string;
  db_type: DbType;
  submitted_by: string;
  submitter_name: string;
  submitter_email: string;
  sql: string;
  query_type: QueryType;
  status: QueryStatus;
  risk_level: RiskLevel;
  risk_score: number;
  justification: string;
  created_at: string;
  rows_affected: number | null;
  duration_ms: number | null;
  ai_summary: string;
  ai_issues: AiIssue[];
}

export interface UserRef {
  id: string;
  email: string;
  display_name: string;
}

export interface DatasourceRef {
  id: string;
  name: string;
}

export interface QueryListItem {
  id: string;
  datasource: DatasourceRef;
  submitted_by: UserRef;
  query_type: QueryType;
  status: QueryStatus;
  risk_level: RiskLevel | null;
  risk_score: number | null;
  ai_failed: boolean;
  scheduled_for: string | null;
  created_at: string;
}

export interface AiAnalysisDetail {
  id: string;
  risk_level: RiskLevel;
  risk_score: number;
  summary: string;
  issues: AiIssue[];
  optimizations: OptimizationSuggestion[];
  missing_indexes_detected: boolean;
  affects_row_estimate: number | null;
  ai_provider: string;
  ai_model: string;
  prompt_tokens: number;
  completion_tokens: number;
  failed: boolean;
  error_message: string | null;
}

export interface ReviewDecisionDetail {
  id: string;
  reviewer: UserRef;
  decision: ReviewDecisionType;
  comment: string | null;
  stage: number;
  decided_at: string;
}

export interface QueryDetail {
  id: string;
  datasource: DatasourceRef;
  db_type: DbType;
  submitted_by: UserRef;
  sql_text: string;
  query_type: QueryType;
  status: QueryStatus;
  justification: string;
  ai_analysis: AiAnalysisDetail | null;
  rows_affected: number | null;
  duration_ms: number | null;
  error_message: string | null;
  previous_run_id: string | null;
  review_plan_name: string | null;
  approval_timeout_hours: number | null;
  matched_policy: MatchedRoutingPolicy | null;
  review_decisions: ReviewDecisionDetail[];
  scheduled_for: string | null;
  created_at: string;
  updated_at: string;
}

/** The routing policy that auto-decided a query, surfaced on the detail timeline. */
export interface MatchedRoutingPolicy {
  policy_id: string | null;
  policy_name: string | null;
  action: RoutingAction;
  reason: string | null;
}

/**
 * Typed, attribute-based condition tree mirroring the backend {@code routing_policy.condition}
 * JSONB wire shape (snake_case, "type"-discriminated).
 */
export type RoutingCondition =
  | { type: 'and'; children: RoutingCondition[] }
  | { type: 'or'; children: RoutingCondition[] }
  | { type: 'not'; child: RoutingCondition }
  | { type: 'query_type'; any_of: QueryType[] }
  | { type: 'referenced_table'; globs: string[] }
  | { type: 'risk_level'; any_of: RiskLevel[] }
  | { type: 'risk_score'; operator: ComparisonOperator; value: number }
  | { type: 'requester_role'; any_of: Role[] }
  | { type: 'requester_group'; group_ids: string[] }
  | { type: 'time_of_day'; start_minute_of_day: number; end_minute_of_day: number }
  | { type: 'day_of_week'; any_of: Weekday[] }
  | { type: 'has_where'; expected: boolean }
  | { type: 'has_limit'; expected: boolean }
  | { type: 'transactional'; expected: boolean }
  | { type: 'source_ip'; cidrs: string[] }
  | { type: 'user_agent'; patterns: string[] }
  | { type: 'time_since_last_approval'; operator: ComparisonOperator; minutes: number }
  | { type: 'cicd_origin'; expected: boolean };

export interface RoutingPolicy {
  id: string;
  organization_id: string;
  datasource_id: string | null;
  name: string;
  description: string | null;
  priority: number;
  enabled: boolean;
  condition: RoutingCondition;
  action: RoutingAction;
  required_approvals: number | null;
  reason: string | null;
  version: number;
  created_at: string;
  updated_at: string;
}

export interface RoutingPolicyWriteRequest {
  name: string;
  description?: string | null;
  datasource_id?: string | null;
  priority: number;
  enabled: boolean;
  condition: RoutingCondition;
  action: RoutingAction;
  required_approvals?: number | null;
  reason?: string | null;
}

export interface ReorderRoutingPoliciesRequest {
  ordered_ids: string[];
}

export interface QueryDiffResponse {
  current_run_id: string;
  previous_run_id: string;
  rows_affected_delta: number | null;
  execution_ms_delta: number | null;
  row_count_delta: number | null;
}

export interface PaginatedResponse<T> {
  content: T[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
  last: boolean;
}

export interface QueryResultColumn {
  name: string;
  type: string;
  restricted?: boolean;
}

export interface QueryResultsPage {
  columns: QueryResultColumn[];
  rows: unknown[][];
  row_count: number;
  truncated: boolean;
  page: number;
  size: number;
}

export interface SubmitQueryResponse {
  id: string;
  status: QueryStatus;
  ai_analysis: AiAnalysisDetail | null;
  review_plan: unknown | null;
  estimated_review_completion: string | null;
}

export interface ExecuteQueryResponse {
  id: string;
  status: QueryStatus;
  rows_affected: number | null;
  duration_ms: number | null;
}

export interface AuditEvent {
  id: string;
  organization_id: string;
  actor_id: string | null;
  actor_email: string | null;
  actor_display_name: string | null;
  action: string;
  resource_type: string;
  resource_id: string | null;
  metadata: Record<string, unknown>;
  ip_address: string | null;
  user_agent: string | null;
  created_at: string;
}

export type AuditLogPage = PageEnvelope<AuditEvent>;

export interface AuditLogFilters {
  actor_id?: string;
  action?: string;
  resource_type?: string;
  resource_id?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export interface AuditChainResult {
  ok: boolean;
  rows_checked: number;
  first_bad_row_id: string | null;
  first_bad_created_at: string | null;
  first_bad_reason: string | null;
}

export interface AuditChainFilters {
  from?: string;
  to?: string;
}

export interface UserListFilters {
  page?: number;
  size?: number;
  sort?: string;
}

export interface DatasourcePermission {
  id: string;
  datasource_id: string;
  user_id: string;
  user_email: string;
  user_display_name: string;
  can_read: boolean;
  can_write: boolean;
  can_ddl: boolean;
  row_limit_override: number | null;
  allowed_schemas: string[] | null;
  allowed_tables: string[] | null;
  restricted_columns: string[] | null;
  expires_at: string | null;
  created_by: string;
  created_at: string;
}

export interface MaskingPolicy {
  id: string;
  datasource_id: string;
  column_ref: string;
  strategy: MaskingStrategy;
  strategy_params: Record<string, string>;
  reveal_to_roles: Role[];
  reveal_to_group_ids: string[];
  reveal_to_user_ids: string[];
  enabled: boolean;
  created_at: string;
  updated_at: string;
}

export interface CreateMaskingPolicyInput {
  column_ref: string;
  strategy: MaskingStrategy;
  strategy_params?: Record<string, string>;
  reveal_to_roles?: string[];
  reveal_to_group_ids?: string[];
  reveal_to_user_ids?: string[];
  enabled?: boolean;
}

export type UpdateMaskingPolicyInput = CreateMaskingPolicyInput;

export type RowSecurityOperator =
  | 'EQUALS'
  | 'NOT_EQUALS'
  | 'LESS_THAN'
  | 'LESS_THAN_OR_EQUAL'
  | 'GREATER_THAN'
  | 'GREATER_THAN_OR_EQUAL'
  | 'IN'
  | 'NOT_IN';

export type RowSecurityValueType = 'VARIABLE' | 'LITERAL';

export interface RowSecurityPolicy {
  id: string;
  datasource_id: string;
  table_name: string;
  column_name: string;
  operator: RowSecurityOperator;
  value_type: RowSecurityValueType;
  value_expression: string;
  applies_to_roles: Role[];
  applies_to_group_ids: string[];
  applies_to_user_ids: string[];
  enabled: boolean;
  created_at: string;
  updated_at: string;
}

export interface CreateRowSecurityPolicyInput {
  table_name: string;
  column_name: string;
  operator: RowSecurityOperator;
  value_type: RowSecurityValueType;
  value_expression: string;
  applies_to_roles?: string[];
  applies_to_group_ids?: string[];
  applies_to_user_ids?: string[];
  enabled?: boolean;
}

export type UpdateRowSecurityPolicyInput = CreateRowSecurityPolicyInput;

export type DataClassification = 'PII' | 'PCI' | 'PHI' | 'GDPR' | 'FINANCIAL' | 'SENSITIVE';

export interface DataClassificationTag {
  id: string;
  datasource_id: string;
  table_name: string;
  column_name: string | null;
  classification: DataClassification;
  note: string | null;
  created_at: string;
  updated_at: string;
}

export interface CreateDataClassificationTagInput {
  table_name: string;
  column_name?: string;
  classifications: DataClassification[];
  note?: string;
  apply_masking?: boolean;
}

export interface ClassificationReviewPosture {
  requires_ai_review: boolean;
  requires_human_approval: boolean;
  min_approvals: number;
  driven_by: DataClassification[];
}

export interface ClassificationMaskingSuggestion {
  column_ref: string;
  classification: DataClassification;
  suggested_strategy: MaskingStrategy;
  suggested_params: Record<string, string>;
  already_applied: boolean;
}

export interface DataClassificationDerivation {
  suggested_review_posture: ClassificationReviewPosture;
  masking_suggestions: ClassificationMaskingSuggestion[];
}

export interface OrganizationDataClassification {
  id: string;
  datasource_id: string;
  datasource_name: string | null;
  table_name: string;
  column_name: string | null;
  classification: DataClassification;
  note: string | null;
  created_at: string;
  updated_at: string;
}

export interface NotificationChannelEmailConfig {
  smtp_host: string;
  smtp_port: number;
  smtp_user?: string;
  smtp_password?: string;
  smtp_tls?: boolean;
  from_address: string;
  from_name?: string;
}
export interface NotificationChannelSlackConfig {
  webhook_url: string;
  channel?: string;
  mention_users?: string[];
}
export interface NotificationChannelWebhookConfig {
  url: string;
  secret?: string;
  timeout_seconds?: number;
}
export interface NotificationChannelPagerDutyConfig {
  routing_key?: string;
  default_severity: 'critical' | 'error' | 'warning' | 'info';
  triggers: Array<'CRITICAL_RISK' | 'REVIEW_TIMEOUT'>;
}

export type NotificationChannelConfig =
  | NotificationChannelEmailConfig
  | NotificationChannelSlackConfig
  | NotificationChannelWebhookConfig
  | NotificationChannelPagerDutyConfig;

export interface NotificationChannel {
  id: string;
  organization_id: string;
  channel_type: ChannelType;
  name: string;
  active: boolean;
  config: Record<string, unknown>;
  created_at: string;
}

export interface CreateNotificationChannelInput {
  name: string;
  channel_type: ChannelType;
  config: Record<string, unknown>;
}

export interface UpdateNotificationChannelInput {
  name?: string;
  active?: boolean;
  config?: Record<string, unknown>;
}

export interface TestNotificationChannelInput {
  email?: string;
}

export interface TestNotificationResult {
  status: 'OK' | 'ERROR';
  detail: string;
}

export interface SchemaColumn {
  name: string;
  type: string;
  nullable: boolean;
  primary_key: boolean;
}
export interface ForeignKey {
  from_column: string;
  to_table: string;
  to_column: string;
}
export interface SchemaTable {
  name: string;
  columns: SchemaColumn[];
  foreign_keys: ForeignKey[];
}
export interface SchemaNamespace {
  name: string;
  tables: SchemaTable[];
}
export interface DatasourceSchema {
  schemas: SchemaNamespace[];
}

export interface SampleRowsColumn {
  name: string;
  type: string;
  restricted: boolean;
}
export interface SampleRowsResponse {
  columns: SampleRowsColumn[];
  rows: unknown[][];
  row_count: number;
  truncated: boolean;
  duration_ms: number;
}

export type UserNotificationEventType =
  | 'QUERY_SUBMITTED'
  | 'QUERY_APPROVED'
  | 'QUERY_REJECTED'
  | 'REVIEW_TIMEOUT'
  | 'AI_HIGH_RISK';

export interface UserNotificationPayload {
  query_id?: string;
  datasource?: string;
  submitter?: string;
  submitter_name?: string;
  risk_level?: RiskLevel;
  reviewer?: string;
  reviewer_comment?: string;
}

export interface UserNotification {
  id: string;
  event_type: UserNotificationEventType;
  query_request_id: string | null;
  payload: UserNotificationPayload;
  read: boolean;
  created_at: string;
  read_at: string | null;
}

export type UserNotificationPage = PageEnvelope<UserNotification>;

export interface UnreadCountResponse {
  count: number;
}

// API keys (MCP and programmatic access)
export interface ApiKey {
  id: string;
  name: string;
  key_prefix: string;
  created_at: string;
  last_used_at: string | null;
  expires_at: string | null;
  revoked_at: string | null;
}

export interface CreateApiKeyInput {
  name: string;
  expires_at?: string | null;
}

export interface CreateApiKeyResponse {
  api_key: ApiKey;
  raw_key: string;
}

// System SMTP configuration (per-org global SMTP for invitations + email fallback)
export interface SystemSmtpConfig {
  organization_id: string;
  host: string;
  port: number;
  username: string | null;
  smtp_password: string | null;
  tls: boolean;
  from_address: string;
  from_name: string | null;
  updated_at: string;
}

export interface UpdateSystemSmtpInput {
  host: string;
  port: number;
  username?: string | null;
  smtp_password?: string | null;
  tls: boolean;
  from_address: string;
  from_name?: string | null;
}

export interface TestSystemSmtpInput {
  to?: string | null;
  host?: string | null;
  port?: number | null;
  username?: string | null;
  smtp_password?: string | null;
  tls?: boolean | null;
  from_address?: string | null;
  from_name?: string | null;
}

// User invitations
export type InvitationStatus = 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';

export interface UserInvitation {
  id: string;
  organization_id: string;
  email: string;
  display_name: string | null;
  role: Role;
  status: InvitationStatus;
  expires_at: string;
  accepted_at: string | null;
  revoked_at: string | null;
  invited_by_user_id: string;
  created_at: string;
}

export type UserInvitationPage = PageEnvelope<UserInvitation>;

export interface InviteUserInput {
  email: string;
  display_name?: string | null;
  role: Role;
}

export interface InvitationPreview {
  email: string;
  display_name: string | null;
  role: Role;
  organization_name: string;
  expires_at: string;
}

export interface AcceptInvitationInput {
  password: string;
  display_name?: string | null;
}

export interface PasswordResetPreview {
  email: string;
  expires_at: string;
}

export interface ResetPasswordInput {
  password: string;
}

// Admin AI analysis statistics — GET /api/v1/admin/ai-analyses/stats
export interface AiAnalysisRiskScorePoint {
  date: string;
  success_avg_risk_score: number | null;
  total_count: number;
  success_count: number;
}

export interface AiAnalysisIssueCategory {
  category: string;
  count: number;
}

export interface AiAnalysisTopSubmitter {
  user_id: string;
  email: string;
  display_name: string | null;
  count: number;
}

export interface AiAnalysisStats {
  risk_score_over_time: AiAnalysisRiskScorePoint[];
  top_issue_categories: AiAnalysisIssueCategory[];
  top_submitters: AiAnalysisTopSubmitter[];
}

export interface AiAnalysisStatsFilters {
  from?: string;
  to?: string;
  datasource_id?: string;
}

// ── Datasource health dashboard (AF-365) ─────────────────────────────────────
export interface DatasourceHealth {
  datasource_id: string;
  datasource_name: string;
  db_type: DbType;
  active: boolean;
  // Live HikariCP pool gauges; null when no pool is currently cached for the datasource.
  pool_active: number | null;
  pool_idle: number | null;
  pool_waiting: number | null;
  pool_total: number | null;
  pool_max: number | null;
  queries_last_24h: number;
  // Execution-time percentiles (ms); null when no executed query carried a duration in the window.
  execution_ms_p50: number | null;
  execution_ms_p95: number | null;
  errors_last_24h: number;
}

export type DatasourceHealthPage = PageEnvelope<DatasourceHealth>;

// ── User groups (AF-353) ─────────────────────────────────────────────────────
export type GroupMembershipSource = 'MANUAL' | 'IDP';

export interface UserGroup {
  id: string;
  organization_id: string;
  name: string;
  description: string | null;
  member_count: number;
  created_at: string;
  updated_at: string;
}

export type UserGroupPage = PageEnvelope<UserGroup>;

export interface CreateUserGroupInput {
  name: string;
  description?: string | null;
}

export interface UpdateUserGroupInput {
  name?: string;
  description?: string | null;
}

export interface UserGroupMember {
  user_id: string;
  group_id: string;
  email: string;
  display_name: string | null;
  source: GroupMembershipSource;
  joined_at: string;
}

// ── Datasource reviewers (AF-353) ────────────────────────────────────────────
export interface DatasourceReviewer {
  id: string;
  datasource_id: string;
  user_id: string | null;
  user_email: string | null;
  user_display_name: string | null;
  group_id: string | null;
  group_name: string | null;
  created_by: string;
  created_at: string;
}

export interface CreateDatasourceReviewerInput {
  userId?: string;
  groupId?: string;
}

// ── Query templates (AF-364) ─────────────────────────────────────────────────
export type QueryTemplateVisibility = 'PRIVATE' | 'TEAM';

export interface QueryTemplate {
  id: string;
  organization_id: string;
  owner_id: string;
  owner_display_name: string | null;
  datasource_id: string | null;
  name: string;
  body: string;
  description: string | null;
  tags: string[];
  visibility: QueryTemplateVisibility;
  editable: boolean;
  created_at: string;
  updated_at: string;
}

export type QueryTemplatePage = PageEnvelope<QueryTemplate>;

export interface QueryTemplateFilters {
  page?: number;
  size?: number;
  datasourceId?: string;
  tag?: string;
  visibility?: QueryTemplateVisibility;
  q?: string;
}

export interface CreateQueryTemplateInput {
  name: string;
  body: string;
  description?: string | null;
  tags?: string[];
  datasource_id?: string | null;
  visibility: QueryTemplateVisibility;
}

export interface UpdateQueryTemplateInput {
  name: string;
  body: string;
  description?: string | null;
  tags?: string[];
  datasource_id?: string | null;
  visibility: QueryTemplateVisibility;
}

// ── Query template versions (AF-442) ─────────────────────────────────────────
export type QueryTemplateChangeType = 'CREATED' | 'UPDATED' | 'RESTORED';

export interface QueryTemplateVersion {
  id: string;
  template_id: string;
  version_number: number;
  datasource_id: string | null;
  name: string;
  body: string;
  description: string | null;
  tags: string[];
  visibility: QueryTemplateVisibility;
  change_type: QueryTemplateChangeType;
  author_id: string | null;
  author_display_name: string | null;
  created_at: string;
}

export type QueryTemplateVersionPage = PageEnvelope<QueryTemplateVersion>;

// --- AF-441 collaboration comments ---

export type CommentStatus = 'OPEN' | 'RESOLVED';

export interface CommentUserRef {
  id: string;
  display_name: string;
  email: string;
}

export interface QueryComment {
  id: string;
  query_request_id: string;
  parent_comment_id: string | null;
  author: CommentUserRef;
  anchor_start_line: number;
  anchor_end_line: number;
  anchor_snapshot: string | null;
  body: string;
  status: CommentStatus;
  resolved_by: CommentUserRef | null;
  resolved_at: string | null;
  created_at: string;
  updated_at: string;
}

export interface QueryCommentThread {
  root: QueryComment;
  replies: QueryComment[];
}

export interface CreateCommentInput {
  anchor_start_line: number;
  anchor_end_line: number;
  anchor_snapshot?: string | null;
  body: string;
}
