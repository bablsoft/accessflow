import type {
  AuditEvent,
  Datasource,
  DatasourcePermission,
  NotificationChannel,
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
  { id: 'u-01', email: 'alice.chen@acme.com', display_name: 'Alice Chen', role: 'ANALYST', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T09:12:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-01-15T10:00:00Z' },
  { id: 'u-02', email: 'marcus.holt@acme.com', display_name: 'Marcus Holt', role: 'REVIEWER', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T08:44:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-01-15T10:00:00Z' },
  { id: 'u-03', email: 'priya.raman@acme.com', display_name: 'Priya Raman', role: 'ADMIN', auth_provider: 'SAML', active: true, last_login_at: '2026-05-04T07:22:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-01-15T10:00:00Z' },
  { id: 'u-04', email: 'jonas.weber@acme.com', display_name: 'Jonas Weber', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-03T18:01:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-02-02T11:30:00Z' },
  { id: 'u-05', email: 'mei.tanaka@acme.com', display_name: 'Mei Tanaka', role: 'REVIEWER', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T06:30:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-02-09T08:15:00Z' },
  { id: 'u-06', email: 'david.okafor@acme.com', display_name: 'David Okafor', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-02T14:50:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-02-14T13:00:00Z' },
  { id: 'u-07', email: 'sara.lopez@acme.com', display_name: 'Sara Lopez', role: 'READONLY', auth_provider: 'LOCAL', active: true, last_login_at: '2026-04-29T11:11:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-02-20T09:45:00Z' },
  { id: 'u-08', email: 'tomas.novak@acme.com', display_name: 'Tomas Novak', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-01T16:25:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-03-01T10:20:00Z' },
  { id: 'u-09', email: 'aisha.bello@acme.com', display_name: 'Aisha Bello', role: 'REVIEWER', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T07:55:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-03-04T15:00:00Z' },
  { id: 'u-10', email: 'lukas.berg@acme.com', display_name: 'Lukas Berg', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-03T20:18:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-03-08T11:10:00Z' },
  { id: 'u-11', email: 'noor.ahmed@acme.com', display_name: 'Noor Ahmed', role: 'ADMIN', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T09:01:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-03-15T08:00:00Z' },
  { id: 'u-12', email: 'kenji.sato@acme.com', display_name: 'Kenji Sato', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-04T05:30:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-03-21T14:30:00Z' },
  { id: 'u-13', email: 'isabel.cruz@acme.com', display_name: 'Isabel Cruz', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-03T13:40:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-03-28T09:00:00Z' },
  { id: 'u-14', email: 'oscar.fields@acme.com', display_name: 'Oscar Fields', role: 'READONLY', auth_provider: 'LOCAL', active: false, last_login_at: '2026-04-12T10:00:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-04-01T10:00:00Z' },
  { id: 'u-15', email: 'hana.kim@acme.com', display_name: 'Hana Kim', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-04T08:00:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-04-08T10:00:00Z' },
  { id: 'u-16', email: 'rafael.silva@acme.com', display_name: 'Rafael Silva', role: 'REVIEWER', auth_provider: 'LOCAL', active: true, last_login_at: '2026-05-04T07:14:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-04-12T10:00:00Z' },
  { id: 'u-17', email: 'emma.larsen@acme.com', display_name: 'Emma Larsen', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-02T17:25:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-04-15T10:00:00Z' },
  { id: 'u-18', email: 'yuki.matsuda@acme.com', display_name: 'Yuki Matsuda', role: 'ANALYST', auth_provider: 'SAML', active: true, last_login_at: '2026-05-04T04:05:00Z', totp_enabled: false, preferred_language: null, created_at: '2026-04-18T10:00:00Z' },
];

export const DATASOURCES: Datasource[] = [
  { id: 'ds-01', organization_id: 'org-demo', name: 'Production PostgreSQL', db_type: 'POSTGRESQL', host: 'db-prod.acme.internal', port: 5432, database_name: 'app_prod', username: 'accessflow_svc', ssl_mode: 'VERIFY_FULL', max_rows_per_query: 1000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: true, ai_config_id: null, active: true, connection_pool_size: 25, review_plan_id: 'rp-strict', created_at: '2026-01-15T10:00:00Z' },
  { id: 'ds-02', organization_id: 'org-demo', name: 'Production MySQL', db_type: 'MYSQL', host: 'mysql-prod.acme.internal', port: 3306, database_name: 'commerce', username: 'accessflow_svc', ssl_mode: 'VERIFY_FULL', max_rows_per_query: 1000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: true, ai_config_id: null, active: true, connection_pool_size: 20, review_plan_id: 'rp-strict', created_at: '2026-01-15T10:00:00Z' },
  { id: 'ds-03', organization_id: 'org-demo', name: 'Analytics Replica', db_type: 'POSTGRESQL', host: 'replica-01.acme.internal', port: 5432, database_name: 'analytics', username: 'accessflow_svc', ssl_mode: 'REQUIRE', max_rows_per_query: 50000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: true, ai_config_id: null, active: true, connection_pool_size: 50, review_plan_id: 'rp-readonly', created_at: '2026-01-20T10:00:00Z' },
  { id: 'ds-04', organization_id: 'org-demo', name: 'Staging PostgreSQL', db_type: 'POSTGRESQL', host: 'db-stage.acme.internal', port: 5432, database_name: 'app_stage', username: 'accessflow_svc', ssl_mode: 'REQUIRE', max_rows_per_query: 5000, require_review_writes: false, require_review_reads: false, ai_analysis_enabled: true, ai_config_id: null, active: true, connection_pool_size: 10, review_plan_id: 'rp-light', created_at: '2026-01-22T10:00:00Z' },
  { id: 'ds-05', organization_id: 'org-demo', name: 'Billing PostgreSQL', db_type: 'POSTGRESQL', host: 'db-billing.acme.internal', port: 5432, database_name: 'billing', username: 'accessflow_svc', ssl_mode: 'VERIFY_FULL', max_rows_per_query: 500, require_review_writes: true, require_review_reads: true, ai_analysis_enabled: true, ai_config_id: null, active: true, connection_pool_size: 15, review_plan_id: 'rp-strict', created_at: '2026-02-05T10:00:00Z' },
  { id: 'ds-06', organization_id: 'org-demo', name: 'Marketing Warehouse', db_type: 'POSTGRESQL', host: 'warehouse.acme.internal', port: 5432, database_name: 'mkt_warehouse', username: 'accessflow_svc', ssl_mode: 'REQUIRE', max_rows_per_query: 100000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: false, ai_config_id: null, active: true, connection_pool_size: 30, review_plan_id: 'rp-readonly', created_at: '2026-02-18T10:00:00Z' },
  { id: 'ds-07', organization_id: 'org-demo', name: 'Legacy Reporting MySQL', db_type: 'MYSQL', host: 'legacy-mysql.acme.internal', port: 3306, database_name: 'reporting_v1', username: 'accessflow_svc', ssl_mode: 'DISABLE', max_rows_per_query: 10000, require_review_writes: true, require_review_reads: false, ai_analysis_enabled: true, ai_config_id: null, active: true, connection_pool_size: 8, review_plan_id: 'rp-strict', created_at: '2026-03-04T10:00:00Z' },
  { id: 'ds-08', organization_id: 'org-demo', name: 'Sandbox PG', db_type: 'POSTGRESQL', host: 'sandbox.acme.internal', port: 5432, database_name: 'sandbox', username: 'accessflow_svc', ssl_mode: 'DISABLE', max_rows_per_query: 10000, require_review_writes: false, require_review_reads: false, ai_analysis_enabled: true, ai_config_id: null, active: false, connection_pool_size: 5, review_plan_id: 'rp-light', created_at: '2026-03-15T10:00:00Z' },
];

const NOW = new Date('2026-05-04T10:30:00Z').getTime();

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
        restricted_columns: null,
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
        restricted_columns: null,
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
