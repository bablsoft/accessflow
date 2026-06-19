import type { Role } from '@/types/api';

/**
 * The landing route for a signed-in user, by role. AUDITOR is a read-only compliance role with no
 * query editor access, so it lands on the auditor dashboard; everyone else lands on the editor.
 */
export function homePathForRole(role: Role | undefined): string {
  return role === 'AUDITOR' ? '/admin/auditor' : '/editor';
}
