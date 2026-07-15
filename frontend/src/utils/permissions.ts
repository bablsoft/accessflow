import type { AuthUser } from '@/api/auth';
import { useAuthStore } from '@/store/authStore';

/**
 * Functional permission names from the backend's fixed catalog (AF-522). The catalog is
 * code-defined server-side; this union mirrors it for type-safe gating. Route/nav gating checks
 * the user's `permissions` array (from the auth payload) rather than the role name, so custom
 * roles Just Work.
 */
export const PERMISSIONS = [
  'QUERY_SUBMIT_SELECT',
  'QUERY_SUBMIT_DML',
  'QUERY_SUBMIT_DDL',
  'QUERY_VIEW_ALL',
  'QUERY_REVIEW',
  'REVIEW_OVERRIDE',
  'QUERY_ADMIN',
  'ACCESS_REQUEST_REVIEW',
  'ACCESS_GRANT_REVOKE',
  'API_CONNECTOR_MANAGE',
  'API_REQUEST_REVIEW',
  'DATASOURCE_MANAGE',
  'DATASOURCE_PERMISSION_MANAGE',
  'MASKING_POLICY_MANAGE',
  'ROW_SECURITY_MANAGE',
  'DATA_CLASSIFICATION_MANAGE',
  'REVIEW_PLAN_MANAGE',
  'ROUTING_POLICY_MANAGE',
  'BREAK_GLASS_VIEW',
  'BREAK_GLASS_REVIEW',
  'RETENTION_POLICY_MANAGE',
  'ERASURE_REVIEW',
  'ATTESTATION_CAMPAIGN_MANAGE',
  'ATTESTATION_REVIEW',
  'ATTESTATION_EVIDENCE_EXPORT',
  'COMPLIANCE_REPORT_VIEW',
  'AUDIT_LOG_VIEW',
  'ANOMALY_VIEW',
  'ANOMALY_MANAGE',
  'USER_MANAGE',
  'GROUP_MANAGE',
  'ROLE_MANAGE',
  'AI_MANAGE',
  'NOTIFICATION_CHANNEL_MANAGE',
  'SSO_CONFIGURE',
  'SMTP_CONFIGURE',
  'LOCALIZATION_CONFIGURE',
  'SETUP_PROGRESS_VIEW',
] as const;

export type Permission = (typeof PERMISSIONS)[number];

type PermissionHolder = Pick<AuthUser, 'permissions'> | null | undefined;

export function hasPermission(user: PermissionHolder, permission: Permission): boolean {
  return !!user?.permissions?.includes(permission);
}

export function hasAnyPermission(
  user: PermissionHolder,
  permissions: readonly Permission[],
): boolean {
  return permissions.some((p) => hasPermission(user, p));
}

/** Hook variant reading the signed-in user from the auth store. */
export function usePermission(permission: Permission): boolean {
  const user = useAuthStore((s) => s.user);
  return hasPermission(user, permission);
}
