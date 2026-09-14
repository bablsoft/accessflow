import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { evaluateSqlReview, sqlReviewKeys } from '@/api/sqlReview';
import { isInvalidSqlError } from '@/utils/apiErrors';
import {
  SQL_REVIEW_MAX_SQL_LENGTH,
  countBlockingFindings,
  isSqlReviewSupported,
} from '@/utils/sqlReview';
import type { DbType, SqlReviewFinding } from '@/types/api';
import { useDebouncedValue } from './useDebouncedValue';

export const SQL_REVIEW_LINT_DEBOUNCE_MS = 400;

const NO_FINDINGS: SqlReviewFinding[] = [];

export interface SqlReviewLintState {
  /** The datasource's engine is covered by the rule catalog; false hides the surface entirely. */
  supported: boolean;
  /** An evaluation for the current draft is in flight (the previous findings stay on screen). */
  evaluating: boolean;
  findings: SqlReviewFinding[];
  blockingCount: number;
  /** The draft does not parse yet — an expected state mid-keystroke, never a toast. */
  unparseable: boolean;
}

export interface UseSqlReviewLintArgs {
  datasourceId: string | undefined;
  dbType: DbType | undefined;
  sql: string;
  /** Test seam; production callers take the default. */
  debounceMs?: number;
}

/**
 * Live deterministic SQL review for the editor (#865): evaluates the draft the author has paused
 * on through `POST /sql-review/evaluate`. Fires only for a selected, catalog-covered datasource and
 * a non-blank draft. The raw SQL is sent (not trimmed) so line numbers match the editor exactly.
 */
export function useSqlReviewLint({
  datasourceId,
  dbType,
  sql,
  debounceMs = SQL_REVIEW_LINT_DEBOUNCE_MS,
}: UseSqlReviewLintArgs): SqlReviewLintState {
  const supported = isSqlReviewSupported(dbType);
  const debouncedSql = useDebouncedValue(sql, debounceMs);
  const enabled =
    supported &&
    !!datasourceId &&
    debouncedSql.trim().length > 0 &&
    debouncedSql.length <= SQL_REVIEW_MAX_SQL_LENGTH;

  const query = useQuery({
    queryKey: enabled
      ? sqlReviewKeys.evaluation(datasourceId, debouncedSql)
      : [...sqlReviewKeys.all, 'evaluation', 'idle'],
    queryFn: () => evaluateSqlReview({ datasource_id: datasourceId!, sql: debouncedSql }),
    enabled,
    // The previous draft's findings stay on screen (mapped through the author's edits by
    // CodeMirror) until the next evaluation replaces them — no flicker on every pause.
    placeholderData: keepPreviousData,
    // Overrides the global retry: a 422 is the author's draft not parsing yet, and the request
    // fires on every pause, so retrying only adds load.
    retry: false,
    // Above the global 30 s: a ruleset edit is an admin action, and re-typing a draft the author
    // already paused on within a minute should not cost another round trip.
    staleTime: 60_000,
  });

  const applicable = query.data?.applicable ?? true;
  const findings = enabled && applicable && query.data ? query.data.findings : NO_FINDINGS;
  return {
    supported: supported && applicable,
    evaluating: enabled && (query.isPlaceholderData || query.isPending),
    findings,
    blockingCount: countBlockingFindings(findings),
    unparseable: enabled && query.isError && isInvalidSqlError(query.error),
  };
}
