import type { TFunction } from 'i18next';

/**
 * Formats a per-org quota limit for display (AF-456). A null or 0 limit means "unlimited".
 */
export function formatQuota(t: TFunction, value: number | null | undefined): string {
  if (value === null || value === undefined || value <= 0) {
    return t('admin.organizations.unlimited');
  }
  return String(value);
}
