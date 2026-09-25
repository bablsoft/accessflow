import type { DbType, QueryShape } from '@/types/api';
import { SQL_REVIEW_DB_TYPES } from '@/utils/sqlReview';

/** Backend `@Size(max = 8)` on `denied_shapes` (#940). */
export const DENIED_SHAPES_MAX = 8;

/**
 * Query-shape blocking reads the JSqlParser AST, so it covers the same in-process relational
 * engines as the SQL review catalog. The backend refuses the field with 422
 * `DENIED_SHAPES_NOT_SUPPORTED` for any other engine.
 */
export const supportsDeniedShapes = (dbType: DbType): boolean =>
  SQL_REVIEW_DB_TYPES.includes(dbType);

/** Omit an empty selection from a grant payload, as the other deny-lists do. */
export const deniedShapesPayload = (shapes: QueryShape[] | undefined): QueryShape[] | null =>
  shapes && shapes.length > 0 ? shapes : null;
