import type { TFunction } from 'i18next';

/**
 * Localised label for an attestation item's `row_limit_source` (#1084): `grant`,
 * `group:<name>`, `datasource_cap` or `global_ceiling`. Null for anything else, so an unknown
 * value is hidden rather than shown raw.
 */
export function rowLimitSourceLabel(t: TFunction, source: string | null | undefined): string | null {
  if (source == null) return null;
  if (source.startsWith('group:')) {
    return t('attestation.row_limit.source_group', { name: source.slice('group:'.length) });
  }
  switch (source) {
    case 'grant':
      return t('attestation.row_limit.source_grant');
    case 'datasource_cap':
      return t('attestation.row_limit.source_datasource_cap');
    case 'global_ceiling':
      return t('attestation.row_limit.source_global_ceiling');
    default:
      return null;
  }
}
