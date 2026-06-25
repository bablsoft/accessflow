import type { Role } from '@/types/api';

/**
 * The landing route for a signed-in user, by role. AUDITOR is a read-only compliance role with no
 * personal query workflow, so it lands on the auditor dashboard; every other role lands on the
 * personalized dashboard (AF-498).
 */
export function homePathForRole(role: Role | undefined): string {
  return role === 'AUDITOR' ? '/admin/auditor' : '/dashboard';
}
