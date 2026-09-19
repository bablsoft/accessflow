import type { ServiceAccount, ServiceAccountKey } from '@/types/api';

export function account(partial: Partial<ServiceAccount> = {}): ServiceAccount {
  return {
    id: 'sa-1',
    email: 'ci-bot@example.com',
    display_name: 'CI bot',
    role: 'READONLY',
    role_id: 'r-readonly',
    role_name: 'READONLY',
    active: true,
    managed_by: 'UI',
    description: 'Nightly reporting',
    owner_user_id: 'u-owner',
    owner_email: 'alice@example.com',
    owner_display_name: 'Alice',
    mcp_tool_allow_list: ['list_datasources', 'validate_sql'],
    rate_limit_per_minute: 60,
    rate_limit_per_day: null,
    active_api_key_count: 1,
    last_used_at: '2026-09-16T08:11:02Z',
    last_login_at: null,
    created_at: '2026-09-10T12:00:00Z',
    updated_at: '2026-09-16T08:00:00Z',
    api_keys: [],
    ...partial,
  };
}

export function key(partial: Partial<ServiceAccountKey> = {}): ServiceAccountKey {
  return {
    id: 'k-1',
    name: 'github-actions',
    key_prefix: 'af_kQ7abcde',
    bootstrap_declared: false,
    created_at: '2026-09-10T12:00:00Z',
    last_used_at: null,
    expires_at: null,
    revoked_at: null,
    ...partial,
  };
}

export function page<T>(content: T[]) {
  return { content, page: 0, size: 20, total_elements: content.length, total_pages: 1 };
}

export const MCP_TOOLS = [
  'list_datasources',
  'get_datasource_schema',
  'list_my_queries',
  'get_query_status',
  'get_query_result',
  'submit_query',
  'cancel_query',
  'list_pending_reviews',
  'review_query',
  'validate_sql',
  'get_column_samples',
  'get_audit_log',
];

export const ROLES = [
  {
    id: 'r-readonly',
    organization_id: 'org-1',
    name: 'READONLY',
    description: null,
    system: true,
    permissions: ['QUERY_SUBMIT'],
    assigned_user_count: 1,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
  },
  {
    id: 'r-reviewer',
    organization_id: 'org-1',
    name: 'REVIEWER',
    description: null,
    system: true,
    permissions: ['QUERY_SUBMIT', 'QUERY_REVIEW'],
    assigned_user_count: 1,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
  },
];

export const HUMANS = page([
  {
    id: 'u-owner',
    email: 'alice@example.com',
    display_name: 'Alice',
    role: 'ADMIN',
    role_id: 'r-admin',
    role_name: 'ADMIN',
    auth_provider: 'LOCAL',
    active: true,
    totp_enabled: false,
    last_login_at: null,
    preferred_language: null,
    created_at: '2026-01-01T00:00:00Z',
    principal_type: 'HUMAN',
  },
]);
