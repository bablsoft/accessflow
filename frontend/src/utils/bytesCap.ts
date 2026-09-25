import type { DbType, UpdateDatasourceInput } from '@/types/api';

/**
 * The engines whose dry-run reports a bytes-scanned estimate (AF-634), and therefore the only ones
 * a bytes-scanned cap may be set on (#941). The backend refuses the field on any other engine with
 * 422 `BYTES_SCANNED_CAP_NOT_SUPPORTED`.
 */
export const BYTES_CAP_DB_TYPES: readonly DbType[] = ['BIGQUERY', 'SNOWFLAKE', 'DATABRICKS'];

export const supportsBytesCap = (dbType: DbType): boolean => BYTES_CAP_DB_TYPES.includes(dbType);

/** Backend `@Min(1)` on every bytes-scanned cap field (#941). */
export const BYTES_CAP_MIN = 1;

/**
 * A null cap means "unchanged" to the update API, so an emptied field must clear explicitly — but
 * only when a cap was stored, so an untouched empty field sends nothing at all.
 */
export function toBytesCapUpdate(
  value: number | null | undefined,
  stored: number | null | undefined,
): Pick<UpdateDatasourceInput, 'max_bytes_scanned_per_query' | 'clear_max_bytes_scanned_per_query'> {
  if (value !== null && value !== undefined) {
    return { max_bytes_scanned_per_query: value };
  }
  return stored !== null && stored !== undefined
    ? { clear_max_bytes_scanned_per_query: true }
    : {};
}

/** Decimal (SI) units — warehouses bill per decimal TB, matching `formatBytes`. */
export const BYTE_INPUT_UNITS = [
  { unit: 'MB', factor: 1e6 },
  { unit: 'GB', factor: 1e9 },
  { unit: 'TB', factor: 1e12 },
  { unit: 'PB', factor: 1e15 },
] as const;

export type ByteInputUnit = (typeof BYTE_INPUT_UNITS)[number]['unit'];

export function byteUnitFactor(unit: ByteInputUnit): number {
  return BYTE_INPUT_UNITS.find((u) => u.unit === unit)?.factor ?? 1e9;
}

/** The largest unit the value fills at least once, so 2e12 reads as "2 TB" and not "2000 GB". */
export function pickUnit(bytes: number | null | undefined): ByteInputUnit {
  if (bytes === null || bytes === undefined || bytes <= 0) {
    return 'GB';
  }
  let chosen: ByteInputUnit = 'MB';
  for (const u of BYTE_INPUT_UNITS) {
    if (bytes >= u.factor) {
      chosen = u.unit;
    }
  }
  return chosen;
}
