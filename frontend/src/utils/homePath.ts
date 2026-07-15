import type { AuthUser } from '@/api/auth';
import { hasPermission } from '@/utils/permissions';

/**
 * The landing route for a signed-in user, by permissions (AF-522). A user with no personal query
 * workflow but compliance visibility (the system AUDITOR shape, or an equivalent custom role)
 * lands on the auditor dashboard; everyone else lands on the personalized dashboard (AF-498).
 */
export function homePathForUser(user: Pick<AuthUser, 'permissions'> | null | undefined): string {
  if (!user) return '/dashboard';
  const auditorShaped =
    !hasPermission(user, 'QUERY_SUBMIT_SELECT') && hasPermission(user, 'COMPLIANCE_REPORT_VIEW');
  return auditorShaped ? '/admin/auditor' : '/dashboard';
}
