import type {
  AuditEvent,
  Datasource,
  DatasourcePermission,
  NotificationChannel,
  QueryRequest,
  QueryStatus,
  QueryType,
  DemoReviewPlan,
  RiskLevel,
  User,
} from '@/types/api';

// Deterministic PRNG so the demo state is stable across reloads
const makeRand = (seed: number) => () => {
  seed = (seed * 9301 + 49297) % 233280;
  return seed / 233280;
};
const rand = makeRand(42);
const pick = <T>(arr: readonly T[]): T => arr[Math.floor(rand() * arr.length)]!;

export const USERS: User[] = [
  { id: 'u-01', email: 'alice.chen@acme.com', display_name: 'Alice Chen', role: 'ANALYST', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T09:12:00Z', created_at: '2026-01-15T10:00:00Z' },
  { id: 'u-02', email: 'marcus.holt@acme.com', display_name: 'Marcus Holt', role: 'REVIEWER', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T08:44:00Z', created_at: '2026-01-15T10:00:00Z' },
  { id: 'u-03', email: 'priya.raman@acme.com', display_name: 'Priya Raman', role: 'ADMIN', auth_provider: 'SAML', active: true, last_login_at: '2026-05-04T07:22:00Z', created_at: '2026-01-15T10:00:00Z' },
  { id: 'u-04', email: 'jonas.weber@acme.com', display_name: 'Jonas Weber', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-03T18:01:00Z', created_at: '2026-02-02T11:30:00Z' },
  { id: 'u-05', email: 'mei.tanaka@acme.com', display_name: 'Mei Tanaka', role: 'REVIEWER', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T06:30:00Z', created_at: '2026-02-09T08:15:00Z' },
  { id: 'u-06', email: 'david.okafor@acme.com', display_name: 'David Okafor', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-02T14:50:00Z', created_at: '2026-02-14T13:00:00Z' },
  { id: 'u-07', email: 'sara.lopez@acme.com', display_name: 'Sara Lopez', role: 'READONLY', auth_provider: 'LOCAL', active: true, last_login_at: '2026-04-29T11:11:00Z', created_at: '2026-02-20T09:45:00Z' },
  { id: 'u-08', email: 'tomas.novak@acme.com', display_name: 'Tomas Novak', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-01T16:25:00Z', created_at: '2026-03-01T10:20:00Z' },
  { id: 'u-09', email: 'aisha.bello@acme.com', display_name: 'Aisha Bello', role: 'REVIEWER', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T07:55:00Z', created_at: '2026-03-04T15:00:00Z' },
  { id: 'u-10', email: 'lukas.berg@acme.com', display_name: 'Lukas Berg', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-03T20:18:00Z', created_at: '2026-03-08T11:10:00Z' },
  { id: 'u-11', email: 'noor.ahmed@acme.com', display_name: 'Noor Ahmed', role: 'ADMIN', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T09:01:00Z', created_at: '2026-03-15T08:00:00Z' },
  { id: 'u-12', email: 'kenji.sato@acme.com', display_name: 'Kenji Sato', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-04T05:30:00Z', created_at: '2026-03-21T14:30:00Z' },
  { id: 'u-13', email: 'isabel.cruz@acme.com', display_name: 'Isabel Cruz', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-03T13:40:00Z', created_at: '2026-03-28T09:00:00Z' },
  { id: 'u-14', email: 'oscar.fields@acme.com', display_name: 'Oscar Fields', role: 'READONLY', auth_provider: 'LOCAL', active: false, last_login_at: '2026-04-12T10:00:00Z', created_at: '2026-04-01T10:00:00Z' },
  { id: 'u-15', email: 'hana.kim@acme.com', display_name: 'Hana Kim', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-04T08:00:00Z', created_at: '2026-04-08T10:00:00Z' },
  { id: 'u-16', email: 'rafael.silva@acme.com', display_name: 'Rafael Silva', role: 'REVIEWER', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T07:14:00Z', created_at: '2026-04-12T10:00:00Z' },
  { id: 'u-17', email: 'emma.larsen@acme.com', display_name: 'Emma Larsen', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-02T17:25:00Z', created_at: '2026-04-15T10:00:00Z' },
  { id: 'u-18', email: 'yuki.matsuda@acme.com', display_name: 'Yuki Matsuda', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-04T04:05:00Z', created_at: '2026-04-18T10:00:00Z' },
];

export const DATASOURCES: Datasource[] = [
  { id: 'ds-01', organization_id: 'org-demo', name: 'Production PostgreSQL', db_type: 'POSTGRESQL', host: 'db-prod.acme.internal', port: 5432, database_name: 'app_prod', username: 'accessflow_svc', ssl_mode: 'VERIFY_FULL', max_rows_per_query: 1000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: true, active: true, connection_pool_size: 25, review_plan_id: 'rp-strict', created_at: '2026-01-15T10:00:00Z' },
  { id: 'ds-02', organization_id: 'org-demo', name: 'Production MySQL', db_type: 'MYSQL', host: 'mysql-prod.acme.internal', port: 3306, database_name: 'commerce', username: 'accessflow_svc', ssl_mode: 'VERIFY_FULL', max_rows_per_query: 1000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: true, active: true, connection_pool_size: 20, review_plan_id: 'rp-strict', created_at: '2026-01-15T10:00:00Z' },
  { id: 'ds-03', organization_id: 'org-demo', name: 'Analytics Replica', db_type: 'POSTGRESQL', host: 'replica-01.acme.internal', port: 5432, database_name: 'analytics', username: 'accessflow_svc', ssl_mode: 'REQUIRE', max_rows_per_query: 50000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: true, active: true, connection_pool_size: 50, review_plan_id: 'rp-readonly', created_at: '2026-01-20T10:00:00Z' },
  { id: 'ds-04', organization_id: 'org-demo', name: 'Staging PostgreSQL', db_type: 'POSTGRESQL', host: 'db-stage.acme.internal', port: 5432, database_name: 'app_stage', username: 'accessflow_svc', ssl_mode: 'REQUIRE', max_rows_per_query: 5000, require_review_writes: false, require_review_reads: false, ai_analysis_enabled: true, active: true, connection_pool_size: 10, review_plan_id: 'rp-light', created_at: '2026-01-22T10:00:00Z' },
  { id: 'ds-05', organization_id: 'org-demo', name: 'Billing PostgreSQL', db_type: 'POSTGRESQL', host: 'db-billing.acme.internal', port: 5432, database_name: 'billing', username: 'accessflow_svc', ssl_mode: 'VERIFY_FULL', max_rows_per_query: 500, require_review_writes: true, require_review_reads: true, ai_analysis_enabled: true, active: true, connection_pool_size: 15, review_plan_id: 'rp-strict', created_at: '2026-02-05T10:00:00Z' },
  { id: 'ds-06', organization_id: 'org-demo', name: 'Marketing Warehouse', db_type: 'POSTGRESQL', host: 'warehouse.acme.internal', port: 5432, database_name: 'mkt_warehouse', username: 'accessflow_svc', ssl_mode: 'REQUIRE', max_rows_per_query: 100000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: false, active: true, connection_pool_size: 30, review_plan_id: 'rp-readonly', created_at: '2026-02-18T10:00:00Z' },
  { id: 'ds-07', organization_id: 'org-demo', name: 'Legacy Reporting MySQL', db_type: 'MYSQL', host: 'legacy-mysql.acme.internal', port: 3306, database_name: 'reporting_v1', username: 'accessflow_svc', ssl_mode: 'DISABLE', max_rows_per_query: 10000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: true, active: true, connection_pool_size: 8, review_plan_id: 'rp-strict', created_at: '2026-03-04T10:00:00Z' },
  { id: 'ds-08', organization_id: 'org-demo', name: 'Sandbox PG', db_type: 'POSTGRESQL', host: 'sandbox.acme.internal', port: 5432, database_name: 'sandbox', username: 'accessflow_svc', ssl_mode: 'DISABLE', max_rows_per_query: 10000, require_review_writes: false, require_review_reads: false, ai_analysis_enabled: true, active: false, connection_pool_size: 5, review_plan_id: 'rp-light', created_at: '2026-03-15T10:00:00Z' },
];

export const REVIEW_PLANS: DemoReviewPlan[] = [
  { id: 'rp-strict', name: 'Strict (production)', description: 'AI review + 2 human approvers (sequential)', requires_ai: true, requires_human: true, min_approvals: 2, timeout_hours: 24, auto_approve_reads: false, channels: ['email', 'slack'] },
  { id: 'rp-readonly', name: 'Read-only with AI', description: 'AI review only on SELECT, single approver on writes', requires_ai: true, requires_human: true, min_approvals: 1, timeout_hours: 12, auto_approve_reads: true, channels: ['slack'] },
  { id: 'rp-light', name: 'Light review', description: 'AI review only — auto-approve if LOW risk', requires_ai: true, requires_human: false, min_approvals: 0, timeout_hours: 4, auto_approve_reads: true, channels: ['slack'] },
];

interface SqlSample {
  sql: string;
  type: QueryType;
  risk: RiskLevel;
  score: number;
}

const SQL_SAMPLES: SqlSample[] = [
  { sql: "SELECT id, email, display_name FROM users WHERE last_login_at > NOW() - INTERVAL '7 days' ORDER BY last_login_at DESC LIMIT 100;", type: 'SELECT', risk: 'LOW', score: 12 },
  { sql: "UPDATE orders SET status = 'shipped', shipped_at = NOW() WHERE id = 88210;", type: 'UPDATE', risk: 'LOW', score: 18 },
  { sql: "DELETE FROM sessions WHERE expires_at < NOW() - INTERVAL '30 days';", type: 'DELETE', risk: 'MEDIUM', score: 45 },
  { sql: 'SELECT * FROM customers;', type: 'SELECT', risk: 'HIGH', score: 78 },
  { sql: "UPDATE pricing_rules SET discount_pct = 15 WHERE region = 'EU';", type: 'UPDATE', risk: 'MEDIUM', score: 52 },
  { sql: "INSERT INTO feature_flags (key, enabled, rollout_pct) VALUES ('new_checkout', true, 25);", type: 'INSERT', risk: 'LOW', score: 22 },
  { sql: "DELETE FROM audit_events WHERE created_at < '2024-01-01';", type: 'DELETE', risk: 'CRITICAL', score: 91 },
  { sql: 'ALTER TABLE orders ADD COLUMN refund_reason TEXT;', type: 'DDL', risk: 'HIGH', score: 72 },
  { sql: "SELECT customer_id, SUM(total_cents) FROM orders WHERE created_at > '2026-01-01' GROUP BY customer_id ORDER BY 2 DESC LIMIT 50;", type: 'SELECT', risk: 'LOW', score: 15 },
  { sql: "UPDATE users SET email_verified = true WHERE email LIKE '%@acme.com';", type: 'UPDATE', risk: 'MEDIUM', score: 48 },
  { sql: "SELECT o.id, c.email, o.total_cents, o.status FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.created_at > NOW() - INTERVAL '24 hours';", type: 'SELECT', risk: 'LOW', score: 20 },
  { sql: 'DROP TABLE legacy_user_imports;', type: 'DDL', risk: 'CRITICAL', score: 94 },
  { sql: "UPDATE subscriptions SET cancelled_at = NOW() WHERE customer_id = 'cus_8821';", type: 'UPDATE', risk: 'LOW', score: 19 },
  { sql: 'SELECT product_id, COUNT(*) AS purchases FROM order_items GROUP BY product_id ORDER BY 2 DESC LIMIT 20;', type: 'SELECT', risk: 'LOW', score: 14 },
  { sql: "INSERT INTO promo_codes (code, percent_off, expires_at) VALUES ('SUMMER26', 20, '2026-09-01');", type: 'INSERT', risk: 'LOW', score: 11 },
];

const STATUSES: QueryStatus[] = [
  'PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED', 'REJECTED',
  'EXECUTED', 'EXECUTED', 'EXECUTED', 'CANCELLED', 'FAILED',
  'PENDING_REVIEW', 'TIMED_OUT',
];
const JUSTIFICATIONS = [
  'Customer support ticket #8821 — order stuck in processing.',
  'Quarterly retention report for marketing team.',
  'Cleaning up expired session rows per data retention policy.',
  'Investigating spike in failed payments since Friday.',
  'Adding refund_reason column for new returns workflow (TKT-9112).',
  'Backfilling email_verified flag for internal users.',
  'EU pricing update per finance review (rev-2026-Q2).',
  'Manual cancellation requested by account exec for VIP customer.',
  'Ad-hoc analysis: top products YTD.',
  'Routine cleanup of legacy import staging table — confirmed unused.',
  'Promo code launch — SUMMER26 campaign.',
  'Weekly cohort retention pull for product analytics.',
];

function aiSummaryFor(s: SqlSample): string {
  if (s.risk === 'LOW') return 'Single-statement query with bounded scope. No issues detected. Estimated row impact within safe limits.';
  if (s.risk === 'MEDIUM') return 'Query passes structural checks but operates on multiple rows or shared columns. Suggest reviewing scope before approval.';
  if (s.risk === 'HIGH') return 'Query may return or modify a large number of rows. Consider narrowing the projection and adding LIMIT.';
  return 'CRITICAL: this statement is destructive and affects production data without sufficient guardrails. Strongly recommend manual review by a senior engineer.';
}
function aiIssuesFor(s: SqlSample) {
  const out: { severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'; category: string; message: string; suggestion: string }[] = [];
  if (s.sql.includes('SELECT *')) out.push({ severity: 'HIGH', category: 'SELECT_STAR', message: 'SELECT * returns all columns including potentially sensitive data', suggestion: 'Specify only the columns you need: id, email, display_name' });
  if (s.sql.includes('DROP TABLE')) out.push({ severity: 'CRITICAL', category: 'DESTRUCTIVE_DDL', message: 'DROP TABLE is irreversible and will permanently destroy data', suggestion: 'Take a backup snapshot first; consider RENAME to a quarantine schema instead' });
  if (s.sql.includes('DELETE') && s.sql.includes('< ')) out.push({ severity: 'MEDIUM', category: 'BULK_DELETE', message: 'Range-bounded DELETE may affect a large number of rows', suggestion: 'Run a SELECT COUNT(*) first to confirm scope' });
  if (s.sql.toLowerCase().includes("like '%")) out.push({ severity: 'MEDIUM', category: 'NON_SARGABLE', message: 'Leading-wildcard LIKE prevents index usage', suggestion: 'Consider a trigram index or refactor the predicate' });
  if (!s.sql.toLowerCase().includes('limit') && s.type === 'SELECT' && !s.sql.includes('=')) out.push({ severity: 'MEDIUM', category: 'NO_LIMIT', message: 'SELECT has no LIMIT clause and could return millions of rows', suggestion: 'Add LIMIT 1000 or a more selective WHERE' });
  if (s.sql.includes('ALTER TABLE')) out.push({ severity: 'HIGH', category: 'SCHEMA_CHANGE', message: 'ALTER TABLE may take a long lock on the target table', suggestion: 'Schedule during a maintenance window; consider pg_repack/online DDL' });
  return out;
}

const NOW = new Date('2026-05-04T10:30:00Z').getTime();

const rawQueries: QueryRequest[] = [];
for (let i = 0; i < 60; i++) {
  const sample = pick(SQL_SAMPLES);
  const status = (i < 12 ? STATUSES[i] : pick(STATUSES))!;
  const submitter = pick(USERS.filter((u) => u.role !== 'READONLY' && u.active));
  const ds = pick(DATASOURCES.filter((d) => d.active));
  const created = NOW - i * 1000 * 60 * Math.floor(rand() * 80 + 5);
  const id = `q-${(1000 + i).toString()}`;
  rawQueries.push({
    id,
    datasource_id: ds.id,
    datasource_name: ds.name,
    db_type: ds.db_type,
    submitted_by: submitter.id,
    submitter_name: submitter.display_name,
    submitter_email: submitter.email,
    sql: sample.sql,
    query_type: sample.type,
    status,
    risk_level: sample.risk,
    risk_score: sample.score,
    justification: pick(JUSTIFICATIONS),
    created_at: new Date(created).toISOString(),
    rows_affected: status === 'EXECUTED' ? Math.floor(rand() * 5000) : null,
    duration_ms: status === 'EXECUTED' ? Math.floor(rand() * 800 + 20) : null,
    ai_summary: aiSummaryFor(sample),
    ai_issues: aiIssuesFor(sample),
  });
}

export const QUERIES: QueryRequest[] = rawQueries;

const AUDIT_ACTIONS = [
  { action: 'QUERY_SUBMITTED', resource_type: 'query_request' },
  { action: 'QUERY_AI_ANALYZED', resource_type: 'query_request' },
  { action: 'QUERY_REVIEW_REQUESTED', resource_type: 'query_request' },
  { action: 'QUERY_APPROVED', resource_type: 'query_request' },
  { action: 'QUERY_REJECTED', resource_type: 'query_request' },
  { action: 'QUERY_EXECUTED', resource_type: 'query_request' },
  { action: 'QUERY_CANCELLED', resource_type: 'query_request' },
  { action: 'DATASOURCE_CREATED', resource_type: 'datasource' },
  { action: 'DATASOURCE_UPDATED', resource_type: 'datasource' },
  { action: 'PERMISSION_GRANTED', resource_type: 'datasource_permission' },
  { action: 'PERMISSION_REVOKED', resource_type: 'datasource_permission' },
  { action: 'USER_LOGIN', resource_type: 'user' },
  { action: 'USER_LOGIN_FAILED', resource_type: 'user' },
  { action: 'USER_CREATED', resource_type: 'user' },
  { action: 'USER_DEACTIVATED', resource_type: 'user' },
];

const auditOut: AuditEvent[] = [];
for (let i = 0; i < 80; i++) {
  const a = pick(AUDIT_ACTIONS);
  const actor = pick(USERS);
  const t = NOW - i * 1000 * 60 * Math.floor(rand() * 40 + 2);
  auditOut.push({
    id: `a-${10000 + i}`,
    organization_id: 'org-demo',
    actor_id: actor.id,
    actor_email: actor.email,
    actor_display_name: actor.display_name,
    action: a.action,
    resource_type: a.resource_type,
    resource_id:
      a.resource_type === 'query_request' ? `q-${1000 + Math.floor(rand() * 60)}`
      : a.resource_type === 'datasource' ? pick(DATASOURCES).id
      : pick(USERS).id,
    metadata: {},
    ip_address: `10.${Math.floor(rand() * 200)}.${Math.floor(rand() * 200)}.${Math.floor(rand() * 200)}`,
    user_agent: 'Mozilla/5.0 (Macintosh) AppleWebKit/537 Chrome/124',
    created_at: new Date(t).toISOString(),
  });
}
export const AUDIT: AuditEvent[] = auditOut;

const PERMS_CREATED_AT = '2026-01-15T10:00:00Z';
const permsOut: DatasourcePermission[] = [];
for (const u of USERS) {
  for (const d of DATASOURCES) {
    if (u.role === 'ADMIN') {
      permsOut.push({
        id: `perm-${u.id}-${d.id}`,
        datasource_id: d.id,
        user_id: u.id,
        user_email: u.email,
        user_display_name: u.display_name,
        can_read: true,
        can_write: true,
        can_ddl: true,
        row_limit_override: null,
        allowed_schemas: null,
        allowed_tables: null,
        expires_at: null,
        created_by: 'u-03',
        created_at: PERMS_CREATED_AT,
      });
    } else if (u.active && rand() > 0.45) {
      permsOut.push({
        id: `perm-${u.id}-${d.id}`,
        datasource_id: d.id,
        user_id: u.id,
        user_email: u.email,
        user_display_name: u.display_name,
        can_read: true,
        can_write: u.role === 'REVIEWER' || (u.role === 'ANALYST' && rand() > 0.4),
        can_ddl: u.role === 'REVIEWER' && rand() > 0.7,
        row_limit_override: rand() > 0.7 ? Math.floor(rand() * 5000 + 100) : null,
        allowed_schemas: rand() > 0.6 ? ['public'] : null,
        allowed_tables: null,
        expires_at: rand() > 0.85 ? '2026-09-30T23:59:59Z' : null,
        created_by: 'u-03',
        created_at: PERMS_CREATED_AT,
      });
    }
  }
}
export const PERMS: DatasourcePermission[] = permsOut;

export const CHANNELS: NotificationChannel[] = [
  { id: 'ch-01', organization_id: 'org-demo', channel_type: 'EMAIL', name: 'Engineering On-Call', active: true, config: { smtp_host: 'smtp.acme.com', smtp_port: 587, smtp_password: '********', from_address: 'accessflow@acme.com' }, created_at: '2026-05-01T08:12:00Z' },
  { id: 'ch-02', organization_id: 'org-demo', channel_type: 'SLACK', name: '#data-access', active: true, config: { webhook_url: 'https://hooks.slack.com/services/T0***/B0***/***', channel: '#data-access' }, created_at: '2026-05-01T09:45:00Z' },
  { id: 'ch-03', organization_id: 'org-demo', channel_type: 'SLACK', name: '#data-access-prod', active: true, config: { webhook_url: 'https://hooks.slack.com/services/T0***/B1***/***', channel: '#data-access-prod' }, created_at: '2026-05-01T10:01:00Z' },
  { id: 'ch-04', organization_id: 'org-demo', channel_type: 'WEBHOOK', name: 'PagerDuty Integration', active: true, config: { url: 'https://events.pagerduty.com/integration/abc123/enqueue', secret: '********', timeout_seconds: 10 }, created_at: '2026-05-01T22:30:00Z' },
  { id: 'ch-05', organization_id: 'org-demo', channel_type: 'WEBHOOK', name: 'SIEM Forwarder', active: true, config: { url: 'https://siem.acme.internal/ingest', secret: '********' }, created_at: '2026-05-01T07:00:00Z' },
  { id: 'ch-06', organization_id: 'org-demo', channel_type: 'EMAIL', name: 'DBA Team', active: false, config: { smtp_host: 'smtp.acme.com', smtp_port: 587, smtp_password: '********', from_address: 'accessflow@acme.com' }, created_at: '2026-04-10T11:00:00Z' },
];
