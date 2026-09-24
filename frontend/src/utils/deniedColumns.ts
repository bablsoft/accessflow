import type { DbType } from '@/types/api';
import { SQL_REVIEW_DB_TYPES } from '@/utils/sqlReview';

/** Backend `@Size(max = 200)` on `denied_columns` (#935). */
export const DENIED_COLUMNS_MAX = 200;

/**
 * Mirrors the backend `@Pattern` on `denied_columns`: `table.column` or `schema.table.column`,
 * every part non-blank.
 */
const QUALIFIED_COLUMN_REF = /^[^.]*[^.\s][^.]*(\.[^.]*[^.\s][^.]*){1,2}$/;

export const isQualifiedColumnRef = (entry: string): boolean =>
  QUALIFIED_COLUMN_REF.test(entry);

/**
 * Column-level blocking needs column references resolved from the JSqlParser AST, so it covers
 * the same in-process relational engines as the SQL review catalog. The backend refuses the field
 * with 422 `DENIED_COLUMNS_NOT_SUPPORTED` for any other engine.
 */
export const supportsDeniedColumns = (dbType: DbType): boolean =>
  SQL_REVIEW_DB_TYPES.includes(dbType);
