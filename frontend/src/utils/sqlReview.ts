import type { DbType, SqlReviewFinding } from '@/types/api';

/**
 * The engines the deterministic SQL review catalog covers (#862) — every rule walks the JSqlParser
 * AST, so only the in-process relational dialects qualify. Mirrors the backend allow-list; the
 * server still answers `applicable: false` for anything else, this just spares the round trip.
 */
export const SQL_REVIEW_DB_TYPES: readonly DbType[] = [
  'POSTGRESQL',
  'MYSQL',
  'MARIADB',
  'ORACLE',
  'MSSQL',
  'CUSTOM',
] as const;

/** Backend `@Size(max = 100_000)` on the evaluate request body. */
export const SQL_REVIEW_MAX_SQL_LENGTH = 100_000;

export const isSqlReviewSupported = (dbType: DbType | undefined): boolean =>
  !!dbType && SQL_REVIEW_DB_TYPES.includes(dbType);

export const countBlockingFindings = (findings: readonly SqlReviewFinding[]): number =>
  findings.filter((f) => f.severity === 'BLOCK').length;

export const countWarningFindings = (findings: readonly SqlReviewFinding[]): number =>
  findings.filter((f) => f.severity === 'WARN').length;
