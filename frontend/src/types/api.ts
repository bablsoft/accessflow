export type Role = 'READONLY' | 'ANALYST' | 'REVIEWER' | 'ADMIN';
export type AuthProvider = 'LOCAL' | 'SAML';
export type Edition = 'COMMUNITY' | 'ENTERPRISE';
export type DbType = 'POSTGRESQL' | 'MYSQL';
export type SslMode = 'DISABLE' | 'REQUIRE' | 'VERIFY_CA' | 'VERIFY_FULL';
export type QueryStatus =
  | 'PENDING_AI'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'EXECUTED'
  | 'REJECTED'
  | 'FAILED'
  | 'CANCELLED';
export type QueryType = 'SELECT' | 'INSERT' | 'UPDATE' | 'DELETE' | 'DDL';
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type IssueSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type ChannelType = 'EMAIL' | 'SLACK' | 'WEBHOOK';

export interface User {
  id: string;
  email: string;
  display_name: string;
  role: Role;
  auth_provider: AuthProvider;
  active: boolean;
  last_login: string;
  created_at: string;
}

export interface Datasource {
  id: string;
  name: string;
  db_type: DbType;
  host: string;
  port: number;
  database_name: string;
  ssl_mode: SslMode;
  max_rows: number;
  require_review_writes: boolean;
  require_review_reads: boolean;
  ai_enabled: boolean;
  active: boolean;
  pool: number;
  plan: string;
  created_at: string;
}

export interface ReviewPlan {
  id: string;
  name: string;
  description: string;
  requires_ai: boolean;
  requires_human: boolean;
  min_approvals: number;
  timeout_hours: number;
  auto_approve_reads: boolean;
  channels: string[];
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

export interface AuditEvent {
  id: string;
  actor_id: string;
  actor_name: string;
  actor_email: string;
  action: string;
  resource_type: string;
  resource_id: string;
  ip_address: string;
  user_agent: string;
  created_at: string;
}

export interface DatasourcePermission {
  user_id: string;
  datasource_id: string;
  can_read: boolean;
  can_write: boolean;
  can_ddl: boolean;
  row_limit: number | null;
  allowed_schemas: string[] | null;
  allowed_tables: string[] | null;
  expires_at: string | null;
}

export interface NotificationChannelEmailConfig {
  to: string[];
  from: string;
}
export interface NotificationChannelSlackConfig {
  webhook: string;
  channel: string;
}
export interface NotificationChannelWebhookConfig {
  url: string;
  secret: string;
  signing: string;
}

export interface NotificationChannel {
  id: string;
  type: ChannelType;
  name: string;
  active: boolean;
  config:
    | NotificationChannelEmailConfig
    | NotificationChannelSlackConfig
    | NotificationChannelWebhookConfig;
  last_used: string;
}

export interface SchemaColumn {
  name: string;
  type: string;
  primary_key?: boolean;
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
