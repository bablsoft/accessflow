import type { EffectiveAccessRow } from '@/types/api';

/** True when the identity reaches the table through the QUERY_ADMIN bypass rather than a grant. */
export function hasQueryAdminBypass(row: EffectiveAccessRow): boolean {
  return row.sources.some((s) => s.kind === 'QUERY_ADMIN_BYPASS');
}
