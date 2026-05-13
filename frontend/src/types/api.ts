export type Role = 'READONLY' | 'ANALYST' | 'REVIEWER' | 'ADMIN';
export type AuthProvider = 'LOCAL' | 'SAML' | 'OAUTH2';
export type OAuth2Provider = 'GOOGLE' | 'GITHUB' | 'MICROSOFT' | 'GITLAB';
export type DbType = 'POSTGRESQL' | 'MYSQL' | 'MARIADB' | 'ORACLE' | 'MSSQL' | 'CUSTOM';
export type SslMode = 'DISABLE' | 'REQUIRE' | 'VERIFY_CA' | 'VERIFY_FULL';
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
export type IssueSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type ChannelType = 'EMAIL' | 'SLACK' | 'WEBHOOK';
export type AiProvider = 'OPENAI' | 'ANTHROPIC' | 'OLLAMA';

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
  preferred_language: string | null;
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
}

export interface TestAiConfigResult {
  status: 'OK' | 'ERROR';
  detail: string;
}

export interface AiConfigInUseError {
  error: 'AI_CONFIG_IN_USE';
  boundDatasources: Array<{ id: string; name: string }>;
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
  default_role?: Role;
  active?: boolean;
}

export interface OAuth2Config {
  id: string | null;
  organization_id: string;
  provider: OAuth2Provider;
  client_id: string | null;
  client_secret: string | null;
  scopes_override: string | null;
  tenant_id: string | null;
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
  custom_driver_id: string | null;
  jdbc_url_override: string | null;
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
  custom_driver_id?: string | null;
  jdbc_url_override?: string | null;
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
  clear_ai_config?: boolean;
  jdbc_url_override?: string | null;
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

export type DriverSource = 'bundled' | 'uploaded';

export interface DatasourceTypeOption {
  code: DbType;
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
}

export interface DatasourceTypesResponse {
  types: DatasourceTypeOption[];
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

export interface AiIssue {
  severity: IssueSeverity;
  category: string;
  message: string;
  suggestion: string;
  line?: number;
}

export interface AiAnalysis {
  risk_level: RiskLevel;
  risk_score: number;
  summary: string;
  issues: AiIssue[];
  affects_rows?: number;
  prompt_tokens?: number;
  completion_tokens?: number;
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
  created_at: string;
}

export interface AiAnalysisDetail {
  id: string;
  risk_level: RiskLevel;
  risk_score: number;
  summary: string;
  issues: AiIssue[];
  missing_indexes_detected: boolean;
  affects_row_estimate: number | null;
  ai_provider: string;
  ai_model: string;
  prompt_tokens: number;
  completion_tokens: number;
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
  submitted_by: UserRef;
  sql_text: string;
  query_type: QueryType;
  status: QueryStatus;
  justification: string;
  ai_analysis: AiAnalysisDetail | null;
  rows_affected: number | null;
  duration_ms: number | null;
  error_message: string | null;
  review_plan_name: string | null;
  approval_timeout_hours: number | null;
  review_decisions: ReviewDecisionDetail[];
  created_at: string;
  updated_at: string;
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

export type NotificationChannelConfig =
  | NotificationChannelEmailConfig
  | NotificationChannelSlackConfig
  | NotificationChannelWebhookConfig;

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
export interface SchemaTable {
  name: string;
  columns: SchemaColumn[];
}
export interface SchemaNamespace {
  name: string;
  tables: SchemaTable[];
}
export interface DatasourceSchema {
  schemas: SchemaNamespace[];
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
